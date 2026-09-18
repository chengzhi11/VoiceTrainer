package com.femininevoicetrainer.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 会话级降噪处理器(GH#5,选型定稿见 docs/research-denoise.md §2/§5)
 *
 * 纯 Kotlin 预处理链:①二阶 Butterworth 高通(RBJ biquad,Q=0.707,截止 120Hz)
 * ②STFT 幅度域谱减(N=1024/hop=512/Hann,噪声 PSD=帧能量最低 10% 帧逐 bin 均值,
 * |Y|² = max(|X|² − α·σ², β·|X|²),保相位 ISTFT 重叠相加)。
 *
 * 逐常量镜像调研原型 scripts/denoise-baseline.py(hpf_biquad/spectral_subtract,
 * numpy 等价实现),离线验收基线 dist/denoise/baseline-gates.tsv 由本实现复现。
 *
 * 仅用于 rescue-on-TOO_NOISY 的第二 pass 离线重评(整段缓存 PCM 一次性处理),
 * 不进录音线程、不影响实时 YIN 显示;回放链路保持原始 PCM(音乐噪声不进用户耳朵,
 * 决策见 docs/decisions.md)。
 */
object DenoiseProcessor {

    /** 主链截止频率 (Hz):车噪主能量 <300Hz 正中 HPF 甜区 */
    const val HPF_FREQ_HZ = 120.0

    /** 二阶 Butterworth Q(RBJ cookbook) */
    const val HPF_Q = 0.707

    const val STFT_N = 1024
    const val STFT_HOP = 512

    /** 谱减过减因子 α */
    const val SS_ALPHA = 2.0

    /** 谱底 β(防音乐噪声) */
    const val SS_BETA = 0.1

    /** 噪声 PSD 估计:取帧能量最低此比例帧 */
    const val NOISE_FRAME_FRACTION = 0.10

    /**
     * 主降噪链:HPF@120Hz → 谱减(rescue 第二 pass 用)
     */
    fun process(x: FloatArray): FloatArray = spectralSubtract(highPass(x, HPF_FREQ_HZ))

    /**
     * 二阶 Butterworth 高通(RBJ cookbook,直接 II 型转置)。
     * 与调研原型 hpf_biquad 同一递推式,fc 可调(基线复现需要 hpf150 变体)。
     */
    fun highPass(x: FloatArray, fcHz: Double = HPF_FREQ_HZ, sampleRate: Int = 44100, q: Double = HPF_Q): FloatArray {
        val w0 = 2.0 * PI * fcHz / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cw = cos(w0)
        val a0 = 1.0 + alpha
        val b0 = ((1.0 + cw) / 2.0) / a0
        val b1 = (-(1.0 + cw)) / a0
        val b2 = ((1.0 + cw) / 2.0) / a0
        val a1 = (-2.0 * cw) / a0
        val a2 = (1.0 - alpha) / a0

        val y = FloatArray(x.size)
        var z1 = 0.0
        var z2 = 0.0
        for (i in x.indices) {
            val xn = x[i].toDouble()
            val yn = b0 * xn + z1
            z1 = b1 * xn - a1 * yn + z2
            z2 = b2 * xn - a2 * yn
            y[i] = yn.toFloat()
        }
        return y
    }

    /**
     * 幅度域谱减(Berouti 风格):噪声 PSD 取帧能量最低 10% 帧的逐 bin 均值;
     * |Y|²=max(|X|²−α·σ², β|X|²);保相位(实增益乘复谱),ISTFT Hann
     * 重叠相加后按窗功率和归一。短于一个 STFT 窗的输入原样返回。
     */
    fun spectralSubtract(
        x: FloatArray,
        nFft: Int = STFT_N,
        hop: Int = STFT_HOP,
        alpha: Double = SS_ALPHA,
        beta: Double = SS_BETA,
        noiseFraction: Double = NOISE_FRAME_FRACTION
    ): FloatArray {
        if (x.size < nFft) return x.copyOf()

        val win = hannWindow(nFft)
        val nFrames = 1 + (x.size - nFft) / hop
        val bins = nFft / 2 + 1

        // 逐帧 STFT(实输入的完整复谱;镜像 bin 与 rfft 半谱数值一致)
        val specRe = Array(nFrames) { DoubleArray(nFft) }
        val specIm = Array(nFrames) { DoubleArray(nFft) }
        val mag2 = Array(nFrames) { DoubleArray(bins) }
        val energy = DoubleArray(nFrames)
        for (f in 0 until nFrames) {
            val off = f * hop
            for (n in 0 until nFft) specRe[f][n] = x[off + n].toDouble() * win[n]
            fftInPlace(specRe[f], specIm[f], inverse = false)
            var e = 0.0
            for (k in 0 until bins) {
                val m = specRe[f][k] * specRe[f][k] + specIm[f][k] * specIm[f][k]
                mag2[f][k] = m
                e += m
            }
            energy[f] = e
        }

        // 噪声 PSD:能量最低 10% 帧逐 bin 均值;全程高噪兜底取最低 1/3 帧(镜像原型)
        val threshold = VoiceFeatureExtractor.percentile(energy.toList(), noiseFraction)
        var sel = (0 until nFrames).filter { energy[it] <= threshold }
        if (sel.size < 3) {
            sel = energy.indices.sortedBy { energy[it] }.take(maxOf(3, nFrames / 3))
        }
        val noisePsd = DoubleArray(bins)
        for (k in 0 until bins) {
            var sum = 0.0
            for (f in sel) sum += mag2[f][k]
            noisePsd[k] = sum / sel.size
        }

        // 谱减:实增益乘复谱保相位 → 逆 FFT → 加窗重叠相加 → 窗功率归一
        val out = DoubleArray(x.size + nFft)
        val wsum = DoubleArray(x.size + nFft)
        for (f in 0 until nFrames) {
            for (k in 0 until nFft) {
                val kk = if (k <= nFft / 2) k else nFft - k
                val m = mag2[f][kk]
                val y2 = max(m - alpha * noisePsd[kk], beta * m)
                val gain = sqrt(y2) / max(sqrt(m), 1e-12)
                specRe[f][k] *= gain
                specIm[f][k] *= gain
            }
            fftInPlace(specRe[f], specIm[f], inverse = true)
            val off = f * hop
            for (n in 0 until nFft) {
                out[off + n] += specRe[f][n] * win[n]
                wsum[off + n] += win[n] * win[n]
            }
        }

        val y = FloatArray(x.size)
        for (i in 0 until x.size) {
            y[i] = if (wsum[i] > 1e-8) (out[i] / wsum[i]).toFloat() else 0.0f
        }
        return y
    }

    /** 对称 Hann窗(镜像 np.hanning:w[n]=0.5−0.5cos(2πn/(N−1)),首尾为 0) */
    private fun hannWindow(n: Int): DoubleArray {
        val w = DoubleArray(n)
        for (i in 0 until n) w[i] = 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))
        return w
    }

    /** 基-2 迭代复数 FFT,原位计算;inverse=true 时旋转因子取共轭并除以 N */
    private fun fftInPlace(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        // 位反转置换
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        // 蝶形
        var len = 2
        while (len <= n) {
            val ang = (if (inverse) 2.0 else -2.0) * PI / len
            val wRe = cos(ang)
            val wIm = sin(ang)
            var start = 0
            while (start < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val uRe = re[start + k]
                    val uIm = im[start + k]
                    val vRe = re[start + k + len / 2] * curRe - im[start + k + len / 2] * curIm
                    val vIm = re[start + k + len / 2] * curIm + im[start + k + len / 2] * curRe
                    re[start + k] = uRe + vRe
                    im[start + k] = uIm + vIm
                    re[start + k + len / 2] = uRe - vRe
                    im[start + k + len / 2] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                start += len
            }
            len = len shl 1
        }
        if (inverse) {
            val inv = 1.0 / n
            for (i in 0 until n) {
                re[i] *= inv
                im[i] *= inv
            }
        }
    }
}
