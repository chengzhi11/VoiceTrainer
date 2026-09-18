package com.femininevoicetrainer.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 语音特征 DSP 原语与会话级聚合(实现前期信号处理调研定义的八项特征 F1-F8)
 *
 * 纯 Kotlin/JVM 实现,零新增依赖(TarsosDSP jar 无 LPC/HNR/Jitter 类)。
 * 所有函数无 Android 依赖,可用合成信号直接单测。
 *
 * 特征清单对照:
 * - F1/F2 F0 分位数与变异系数 → [analyze] 聚合(输入 raw 逐帧 F0)
 * - F3 共振峰 → [computeFormantsLpc](自相关+Levinson-Durbin,降采样 11kHz 13 阶),
 *   失败回退 [computeFormantsFft](谱包络峰值法)
 * - F4 共鸣指标 → (F3-F1)/3,聚合于 [VoiceFeatures.resonanceSpacing]
 * - F5 HNR → [computeHnr] 帧自相关 r(τ0):10·log10(r/(1-r))
 * - F6 谱倾斜 → [computeSpectralTilt] 100-4000Hz log 谱线性回归
 * - F7 jitter → [computeJitterRap] RAP(3 周期窗口),必须用 raw 未平滑 F0(平滑会抹掉周期扰动)
 * - F8 shimmer → [computeShimmerDb] 逐帧 RMS 扰动
 */
object VoiceFeatureExtractor {

    // ---- 分析参数(降采样 11kHz,LPC 阶数 ≈ sr/1000+2) ----
    const val ANALYSIS_SAMPLE_RATE = 11025
    const val DECIMATION_FACTOR = 4
    const val LPC_ORDER = 13
    const val PRE_EMPHASIS_COEF = 0.97
    /** 谱分析通带截止 (Hz),低于降采样后 Nyquist (5512Hz) */
    private const val LP_CUTOFF_HZ = 4500.0
    private const val LP_TAPS = 45
    private const val FFT_SIZE = 512
    /** 谱倾斜回归频带 (Hz) */
    private const val TILT_F_MIN = 100.0
    private const val TILT_F_MAX = 4000.0
    /** 共振峰搜索范围与峰挑选约束 */
    private const val FORMANT_F_MIN = 180.0
    private const val FORMANT_F_MAX = 5000.0
    private const val FORMANT_MIN_PEAK_DB = 3.0
    private const val FORMANT_MIN_SPACING_HZ = 180.0

    /** 置信度过滤阈值(防环境噪声拉低统计的系统性解法;权威定义在 [VoiceTypeThresholds],COD-45 后保持 0.7 不动) */
    const val MIN_PITCH_CONFIDENCE = VoiceTypeThresholds.MIN_PITCH_CONFIDENCE

    /** dispatcher 帧时长 (ms):2048 样本 @44100Hz 无重叠,会话时长/有效时长按帧数 × 此值换算 */
    const val FRAME_MS = 2048.0 / 44100.0 * 1000.0

    // ================= 数据结构 =================

    /**
     * 单帧 DSP 标量特征(与 raw F0 序列按 dispatcher 帧序 1:1 对齐)。
     * 无效值用 [Double.NaN](无 pitch 的帧 HNR/共振峰无效)。
     */
    data class FrameDsp(
        val rms: Double,
        val hnrDb: Double,
        val f1: Double,
        val f2: Double,
        val f3: Double,
        val tiltDbOct: Double
    )

    /**
     * 失败归因(COD-45 失败归因判定表,按序互斥、先命中先归因:
     * TOO_SHORT → TOO_QUIET(含子集 SILENT)→ TOO_NOISY → INSUFF_VOICED)。
     * label 用于 UI 标题与 DB 归因留存,advice 为用户提示文案(报告建议稿)。
     */
    enum class SessionFailReason(val label: String, val advice: String) {
        TOO_SHORT("录音太短", "录音太短啦,请长按多说几秒(建议 5 秒以上)"),
        SILENT("几乎无声", "几乎没听到声音,请检查麦克风是否被遮挡"),
        TOO_QUIET("声音太轻", "声音有点小,请把手机拿近一点或稍微大声一点"),
        TOO_NOISY("环境噪声大", "环境噪声有点大,请靠近麦克风,或降低背景噪声(关车窗/调低空调)后再试"),
        INSUFF_VOICED("有效人声不足", "没有捕捉到足够的人声,请对着手机底部麦克风、用平稳的声音连续说话");

        /** INSUFF_VOICED 且活跃段有声占比极低时追加的文案(报告:疑似在放音乐/电视) */
        fun extraAdvice(activeVoicedRatio: Double): String? =
            if (this == INSUFF_VOICED && activeVoicedRatio < 0.1) "若在放音乐/电视,请先关掉" else null
    }

    /** 会话级特征聚合结果(判别与评分共用的 features 集合) */
    data class VoiceFeatures(
        val frameCount: Int,
        val voicedFrameCount: Int,
        val voicedRatio: Double,
        val isUsable: Boolean,
        val f0P10: Double,
        val f0P50: Double,
        val f0P90: Double,
        val f0Cv: Double,
        val f1: Double,
        val f2: Double,
        val f3: Double,
        /** (F3-F1)/3,共鸣性别归一输入 */
        val resonanceSpacing: Double,
        val hnrDb: Double,
        val tiltDbOct: Double,
        val jitterRap: Double,
        val shimmerDb: Double,
        val rmsMean: Double,
        /** 语音电平(帧 RMS P90),有效性门限与 TOO_QUIET/SILENT 归因输入(COD-45 #4) */
        val speechLevel: Double = 0.0,
        /** 噪声底(YIN 未检出帧 RMS P50,不足 10% 帧时退全体帧 P10)(COD-45 #7) */
        val noiseFloor: Double = 0.0,
        /** 活跃段有声占比 war = 有声帧/活跃帧,取代整段占比(COD-45 #3) */
        val activeVoicedRatio: Double = 0.0,
        /** 失败归因(可用时为 null;判定顺序见 [SessionFailReason]) */
        val failReason: SessionFailReason? = null
    ) {
        companion object {
            fun empty(): VoiceFeatures = VoiceFeatures(
                frameCount = 0, voicedFrameCount = 0, voicedRatio = 0.0, isUsable = false,
                f0P10 = 0.0, f0P50 = 0.0, f0P90 = 0.0, f0Cv = 0.0,
                f1 = 0.0, f2 = 0.0, f3 = 0.0, resonanceSpacing = 0.0,
                hnrDb = 0.0, tiltDbOct = 0.0, jitterRap = 0.0, shimmerDb = 0.0, rmsMean = 0.0
            )
        }
    }

    // ================= 会话级聚合 =================

    /**
     * 聚合一轮录音的特征。
     *
     * 有效性判定(COD-45 门限修订):总时长 ≥3.5s ∧ 有效语音 ≥3.0s ∧ 活跃段有声占比 war ≥0.35
     * ∧ 语音电平 P90 ≥0.02 ∧ 电平/底噪比 ≥3.5;不满足时按
     * TOO_SHORT → SILENT/TOO_QUIET → TOO_NOISY → INSUFF_VOICED 先命中先归因。
     * YIN 置信度门(>0.7)是有声帧判定的前置闸,保持不动(防假分主闸)。
     *
     * @param dspFrames 逐帧 DSP 标量(与 rawF0/rawProb 按帧序对齐)
     * @param rawF0 逐帧 raw 未平滑 F0(Hz,未检出为 0)— jitter 必须用它,不能用平滑值
     * @param rawProb 逐帧 YIN 置信度
     */
    fun analyze(dspFrames: List<FrameDsp>, rawF0: List<Double>, rawProb: List<Double>): VoiceFeatures {
        val n = minOf(dspFrames.size, rawF0.size, rawProb.size)
        if (n == 0) return VoiceFeatures.empty()

        val voicedMask = BooleanArray(n)
        for (i in 0 until n) {
            voicedMask[i] = rawProb[i] > MIN_PITCH_CONFIDENCE && rawF0[i] > 0.0
        }
        val voiced = (0 until n).filter { voicedMask[it] }
        val voicedRatio = voiced.size.toDouble() / n

        // ---- 会话级有效性指标(门限见 VoiceTypeThresholds,COD-45 建议表 #1-#7) ----
        val t = VoiceTypeThresholds
        val totalDurationMs = n * FRAME_MS
        val voicedDurationMs = voiced.size * FRAME_MS
        val allRms = dspFrames.take(n).map { it.rms }
        val speechLevel = percentile(allRms, 0.90)
        val unvoicedRms = (0 until n).filterNot { voicedMask[it] }.map { dspFrames[it].rms }
        val noiseFloor = if (unvoicedRms.size >= n * t.NOISE_FLOOR_FALLBACK_UNVOICED_FRACTION) {
            percentile(unvoicedRms, 0.50)
        } else {
            percentile(allRms, 0.10)
        }
        val activeThreshold = max(t.ACTIVE_FRAME_NOISE_MULT * noiseFloor, t.ACTIVE_FRAME_MIN_RMS)
        val activeCount = allRms.count { it > activeThreshold }
        val activeVoicedRatio = if (activeCount > 0) {
            (voiced.size.toDouble() / activeCount).coerceAtMost(1.0)
        } else 0.0
        // 底噪为 0(数字静音)视为信噪无穷大,不因除零误报 TOO_NOISY
        val levelSnr = if (noiseFloor > 0.0) speechLevel / noiseFloor else Double.POSITIVE_INFINITY

        val usable = totalDurationMs >= t.MIN_TOTAL_DURATION_MS &&
            voicedDurationMs >= t.MIN_VOICED_DURATION_MS &&
            activeVoicedRatio >= t.MIN_ACTIVE_VOICED_RATIO &&
            speechLevel >= t.MIN_SPEECH_LEVEL &&
            levelSnr >= t.MIN_LEVEL_SNR
        val failReason = if (usable) null else when {
            totalDurationMs < t.MIN_TOTAL_DURATION_MS -> SessionFailReason.TOO_SHORT
            speechLevel < t.SILENT_SPEECH_LEVEL -> SessionFailReason.SILENT
            speechLevel < t.MIN_SPEECH_LEVEL -> SessionFailReason.TOO_QUIET
            levelSnr < t.MIN_LEVEL_SNR -> SessionFailReason.TOO_NOISY
            else -> SessionFailReason.INSUFF_VOICED
        }

        if (!usable) {
            // 数据不足:仍给出电平/底噪/占比等归因指标但标记不可判别,评分判别层据此短路
            val rmsMean = allRms.average()
            return VoiceFeatures(
                frameCount = n, voicedFrameCount = voiced.size, voicedRatio = voicedRatio,
                isUsable = false,
                f0P10 = 0.0, f0P50 = 0.0, f0P90 = 0.0, f0Cv = 0.0,
                f1 = 0.0, f2 = 0.0, f3 = 0.0, resonanceSpacing = 0.0,
                hnrDb = 0.0, tiltDbOct = 0.0, jitterRap = 0.0, shimmerDb = 0.0,
                rmsMean = if (rmsMean.isFinite()) rmsMean else 0.0,
                speechLevel = speechLevel,
                noiseFloor = noiseFloor,
                activeVoicedRatio = activeVoicedRatio,
                failReason = failReason
            )
        }

        val f0Series = voiced.map { rawF0[it] }
        val f0P10 = percentile(f0Series, 0.10)
        val f0P50 = percentile(f0Series, 0.50)
        val f0P90 = percentile(f0Series, 0.90)
        val f0Cv = coefficientOfVariation(f0Series)

        val med = { pick: (FrameDsp) -> Double -> voiced.map { pick(dspFrames[it]) }.filter { it.isFinite() } }
        val f1 = med { it.f1 }.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.5) } ?: 0.0
        val f2 = med { it.f2 }.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.5) } ?: 0.0
        val f3 = med { it.f3 }.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.5) } ?: 0.0
        val hnr = med { it.hnrDb }.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.5) } ?: 0.0
        val tilt = med { it.tiltDbOct }.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.5) } ?: 0.0

        val jitter = computeJitterRap(f0Series)
        val shimmer = computeShimmerDb(voiced.map { dspFrames[it].rms })

        return VoiceFeatures(
            frameCount = n, voicedFrameCount = voiced.size, voicedRatio = voicedRatio,
            isUsable = true,
            f0P10 = f0P10, f0P50 = f0P50, f0P90 = f0P90, f0Cv = f0Cv,
            f1 = f1, f2 = f2, f3 = f3,
            resonanceSpacing = (f3 - f1) / 3.0,
            hnrDb = hnr, tiltDbOct = tilt,
            jitterRap = jitter, shimmerDb = shimmer,
            rmsMean = voiced.map { dspFrames[it].rms }.average(),
            speechLevel = speechLevel,
            noiseFloor = noiseFloor,
            activeVoicedRatio = activeVoicedRatio,
            failReason = null
        )
    }

    // ================= 统计原语 =================

    /** 线性插值分位数 */
    fun percentile(sortedOrNot: List<Double>, q: Double): Double {
        if (sortedOrNot.isEmpty()) return 0.0
        val s = sortedOrNot.sorted()
        if (s.size == 1) return s[0]
        val pos = q * (s.size - 1)
        val lo = pos.toInt().coerceIn(0, s.size - 1)
        val hi = (lo + 1).coerceAtMost(s.size - 1)
        val frac = pos - lo
        return s[lo] * (1 - frac) + s[hi] * frac
    }

    /** 变异系数 CV = std/mean(总体标准差) */
    fun coefficientOfVariation(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        if (mean == 0.0) return 0.0
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return sqrt(variance) / mean
    }

    /**
     * jitter (RAP,Relative Average Perturbation):相邻 3 点窗口平均的相对扰动。
     * 输入必须是 raw 未平滑逐帧/逐周期 F0,平滑序列会把扰动抹成 0。
     */
    fun computeJitterRap(f0Series: List<Double>): Double {
        if (f0Series.size < 3) return 0.0
        val meanF0 = f0Series.average()
        if (meanF0 <= 0.0) return 0.0
        var sum = 0.0
        var count = 0
        for (i in 1 until f0Series.size - 1) {
            val localAvg = (f0Series[i - 1] + f0Series[i] + f0Series[i + 1]) / 3.0
            sum += abs(f0Series[i] - localAvg)
            count++
        }
        return (sum / count) / meanF0
    }

    /** shimmer:相邻帧 RMS 的 dB 域平均扰动 */
    fun computeShimmerDb(rmsSeries: List<Double>): Double {
        if (rmsSeries.size < 2) return 0.0
        var sum = 0.0
        var count = 0
        for (i in 0 until rmsSeries.size - 1) {
            val a = max(rmsSeries[i], 1e-10)
            val b = max(rmsSeries[i + 1], 1e-10)
            sum += abs(20.0 * log10(b / a))
            count++
        }
        return sum / count
    }

    // ================= 帧级 DSP 原语 =================

    /** 帧均方根 */
    fun computeRms(frame: FloatArray): Double {
        if (frame.isEmpty()) return 0.0
        var sum = 0.0
        for (v in frame) sum += v.toDouble() * v
        return sqrt(sum / frame.size)
    }

    /**
     * HNR(帧自相关法):
     * 在 F0 对应滞后 τ0±15% 邻域内取归一化自相关峰值 r(τ),
     * HNR = 10·log10(r/(1-r)) dB;r 越接近 1 谐音成分越纯。
     *
     * @return dB 值;f0 无效或滞后超出帧长一半返回 NaN
     */
    fun computeHnr(frame: FloatArray, sampleRate: Int, f0: Double): Double {
        if (f0 <= 0.0 || frame.isEmpty()) return Double.NaN
        val tau0 = (sampleRate / f0).toInt()
        if (tau0 < 2 || tau0 * 2 >= frame.size) return Double.NaN

        val lo = max(2, (tau0 * 0.85).toInt())
        val hi = min(frame.size / 2 - 1, (tau0 * 1.15).toInt())
        if (hi <= lo) return Double.NaN

        var best = -1.0
        for (lag in lo..hi) {
            var num = 0.0
            var e1 = 0.0
            var e2 = 0.0
            for (i in 0 until frame.size - lag) {
                val a = frame[i].toDouble()
                val b = frame[i + lag].toDouble()
                num += a * b
                e1 += a * a
                e2 += b * b
            }
            val denom = sqrt(e1 * e2)
            if (denom > 1e-12) {
                val r = num / denom
                if (r > best) best = r
            }
        }
        if (best <= 0.0) return Double.NaN
        val rClamped = best.coerceIn(0.01, 0.999)
        return 10.0 * log10(rClamped / (1.0 - rClamped))
    }

    /**
     * 谱倾斜(dB/octave):对加窗帧 FFT 幅度谱在 [100,4000]Hz 做线性回归,
     * x = log2(f),y = 20·log10(|X(f)|)。
     */
    fun computeSpectralTilt(windowedFrame: DoubleArray, sampleRate: Int): Double {
        val mag = fftMagnitude(windowedFrame)
        val paddedN = (mag.size - 1) * 2

        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        var count = 0
        val binHz = sampleRate.toDouble() / paddedN
        for (k in 1 until mag.size) {
            val f = k * binHz
            if (f < TILT_F_MIN || f > TILT_F_MAX) continue
            val x = log2(f)
            val y = 20.0 * log10(mag[k] + 1e-12)
            sx += x; sy += y; sxx += x * x; sxy += x * y
            count++
        }
        if (count < 2) return 0.0
        val denom = count * sxx - sx * sx
        if (abs(denom) < 1e-9) return 0.0
        return (count * sxy - sx * sy) / denom
    }

    /**
     * 共振峰 F1-F3:LPC 自相关法 + Levinson-Durbin(主方案)。
     * 输入应为降采样、预加重、加窗后的帧。
     *
     * @return listOf(F1,F2,F3) Hz;LPC 失败(奇异/不稳定)或峰不足 3 个返回 null(调用方回退 FFT 法)
     */
    fun computeFormantsLpc(frame: DoubleArray, sampleRate: Int): List<Double>? {
        val r = autocorrelation(frame, LPC_ORDER) ?: return null
        val lpc = levinsonDurbin(r, LPC_ORDER) ?: return null
        return pickFormantsFromLpcEnvelope(lpc, sampleRate)
    }

    /**
     * FFT 谱包络峰值法(LPC 失败时的回退方案):
     * 1/3 倍频程频带能量积分(消除谐波纹波)后局部极大峰挑选,取前 3 峰。
     */
    fun computeFormantsFft(windowedFrame: DoubleArray, sampleRate: Int): List<Double>? {
        val mag = fftMagnitude(windowedFrame)
        val paddedN = (mag.size - 1) * 2
        val binHz = sampleRate.toDouble() / paddedN

        // 1/3 倍频程频带:中心频率 180·2^(k/3),上/下边 1/6 倍频程
        val thirdOctave = 2.0.pow(1.0 / 3.0)
        val sixthOctave = 2.0.pow(1.0 / 6.0)
        val bandDb = ArrayList<Double>()
        val bandHz = ArrayList<Double>()
        var fc = FORMANT_F_MIN
        while (fc < FORMANT_F_MAX) {
            val loBin = max(1, (fc / sixthOctave / binHz).toInt())
            val hiBin = min(mag.size - 1, (fc * sixthOctave / binHz).toInt())
            if (hiBin >= loBin) {
                var power = 0.0
                for (k in loBin..hiBin) power += mag[k] * mag[k]
                bandDb.add(10.0 * log10(power + 1e-20))
                bandHz.add(fc)
            }
            fc *= thirdOctave
        }
        if (bandDb.size < 3) return null

        // 频带局部极大:突出度 ≥2dB,与已选峰间距 ≥180Hz
        val picked = mutableListOf<Double>()
        var lastPickIdx = -100
        for (k in 1 until bandDb.size - 1) {
            if (bandDb[k] > bandDb[k - 1] && bandDb[k] >= bandDb[k + 1]) {
                var leftMin = bandDb[k]
                var i = k - 1
                while (i > 0 && bandDb[i] <= bandDb[i + 1]) { leftMin = bandDb[i]; i-- }
                var rightMin = bandDb[k]
                i = k + 1
                while (i < bandDb.size - 1 && bandDb[i] <= bandDb[i - 1]) { rightMin = bandDb[i]; i++ }
                if (bandDb[k] - max(leftMin, rightMin) < 2.0) continue
                if (lastPickIdx >= 0 && bandHz[k] - bandHz[lastPickIdx] < FORMANT_MIN_SPACING_HZ) continue
                picked.add(bandHz[k])
                lastPickIdx = k
                if (picked.size == 3) break
            }
        }
        return if (picked.size == 3) picked else null
    }

    // ================= 内部实现 =================

    /** 降采样:窗 sinc 低通(抗混叠)+ 抽取 */
    fun decimate(input: FloatArray, inputSampleRate: Int, factor: Int): DoubleArray {
        val lp = designLowPass(inputSampleRate.toDouble(), LP_CUTOFF_HZ, LP_TAPS)
        val half = LP_TAPS / 2
        val outLen = (input.size - LP_TAPS + 1) / factor
        val out = DoubleArray(max(0, outLen))
        for (o in out.indices) {
            val center = o * factor + half
            var acc = 0.0
            for (t in 0 until LP_TAPS) {
                acc += input[center + t - half] * lp[t]
            }
            out[o] = acc
        }
        return out
    }

    private fun designLowPass(sampleRate: Double, cutoffHz: Double, taps: Int): DoubleArray {
        val omegaC = 2.0 * PI * cutoffHz / sampleRate
        val m = (taps - 1) / 2.0
        val h = DoubleArray(taps)
        var sum = 0.0
        for (n in 0 until taps) {
            val k = n - m
            val v = if (abs(k) < 1e-9) omegaC / PI else sin(omegaC * k) / (PI * k)
            // Hamming 窗
            val w = 0.54 - 0.46 * cos(2.0 * PI * n / (taps - 1))
            h[n] = v * w
            sum += h[n]
        }
        // 归一化直流增益为 1
        for (n in 0 until taps) h[n] /= sum
        return h
    }

    fun hammingWindow(n: Int): DoubleArray {
        val w = DoubleArray(n)
        for (i in 0 until n) w[i] = 0.54 - 0.46 * cos(2.0 * PI * i / (n - 1).coerceAtLeast(1))
        return w
    }

    fun preEmphasis(frame: DoubleArray, coef: Double = PRE_EMPHASIS_COEF): DoubleArray {
        if (frame.isEmpty()) return frame
        val out = DoubleArray(frame.size)
        out[0] = frame[0]
        for (i in 1 until frame.size) out[i] = frame[i] - coef * frame[i - 1]
        return out
    }

    /** 自相关系数 r[0..order](能量不足返回 null) */
    private fun autocorrelation(frame: DoubleArray, order: Int): DoubleArray? {
        if (frame.size <= order + 1) return null
        var e0 = 0.0
        for (v in frame) e0 += v * v
        if (e0 < 1e-9) return null
        val r = DoubleArray(order + 1)
        r[0] = e0
        for (k in 1..order) {
            var acc = 0.0
            for (i in 0 until frame.size - k) acc += frame[i] * frame[i + k]
            r[k] = acc
        }
        return r
    }

    /**
     * Levinson-Durbin 求 LPC 系数 a[1..order](A(z) = 1 - Σ a_k z^-k)。
     * 反射系数 |k|≥1 视为不稳定,返回 null(调用方走 FFT 回退)。
     */
    private fun levinsonDurbin(r: DoubleArray, order: Int): DoubleArray? {
        val a = DoubleArray(order + 1)
        if (r[0] <= 0.0) return null
        var err = r[0]
        for (i in 1..order) {
            var acc = r[i]
            for (j in 1 until i) acc -= a[j] * r[i - j]
            val k = acc / err
            if (abs(k) >= 1.0) return null
            // 更新系数
            val prev = DoubleArray(i)
            for (j in 1 until i) prev[j] = a[j]
            a[i] = k
            for (j in 1 until i) a[j] = prev[j] - k * prev[i - j]
            err *= (1.0 - k * k)
            if (err <= 0.0) return null
        }
        return a
    }

    /** LPC 谱包络 1/|A(e^jw)| 取前 3 峰 */
    private fun pickFormantsFromLpcEnvelope(a: DoubleArray, sampleRate: Int): List<Double>? {
        val points = FFT_SIZE / 2
        val envDb = DoubleArray(points)
        val binHz = sampleRate.toDouble() / (2 * points)
        for (p in 0 until points) {
            val f = p * binHz
            val w = 2.0 * PI * f / sampleRate
            // A(e^jw) = 1 - Σ a_k e^{-jkw},实部/虚部分开累加
            var re = 1.0
            var im = 0.0
            for (k in 1 until a.size) {
                re -= a[k] * cos(k * w)
                im += a[k] * sin(k * w)
            }
            val mag = sqrt(re * re + im * im)
            envDb[p] = -20.0 * log10(mag + 1e-12)
        }
        return pickFirstThreePeaks(envDb, binHz)
    }

    /**
     * 峰挑选:局部极大、相对邻近谷底突出 ≥ [FORMANT_MIN_PEAK_DB] dB、
     * 与已选峰间距 ≥ [FORMANT_MIN_SPACING_HZ],取前 3 个([FORMANT_F_MIN,FORMANT_F_MAX] 内)。
     */
    private fun pickFirstThreePeaks(envDb: DoubleArray, binHz: Double): List<Double>? {
        val loIdx = (FORMANT_F_MIN / binHz).toInt().coerceAtLeast(1)
        val hiIdx = min(envDb.size - 2, (FORMANT_F_MAX / binHz).toInt())
        if (hiIdx <= loIdx + 2) return null

        val picked = mutableListOf<Double>()
        var lastPeakIdx = -100
        for (k in loIdx..hiIdx) {
            if (envDb[k] > envDb[k - 1] && envDb[k] >= envDb[k + 1]) {
                // 突出度:与前后最近谷底比较
                var leftMin = envDb[k]
                var i = k - 1
                while (i >= loIdx && envDb[i] <= envDb[i + 1]) { leftMin = envDb[i]; i-- }
                var rightMin = envDb[k]
                i = k + 1
                while (i <= hiIdx && envDb[i] <= envDb[i - 1]) { rightMin = envDb[i]; i++ }
                val prominence = envDb[k] - max(leftMin, rightMin)
                if (prominence < FORMANT_MIN_PEAK_DB) continue
                if ((k - lastPeakIdx) * binHz < FORMANT_MIN_SPACING_HZ) continue
                picked.add(k * binHz)
                lastPeakIdx = k
                if (picked.size == 3) break
            }
        }
        return if (picked.size == 3) picked else null
    }

    // ================= FFT =================

    /** 基-2 迭代 FFT,输入长度补零到 2 的幂,返回幅度谱(0..n/2) */
    fun fftMagnitude(samples: DoubleArray): DoubleArray {
        var n = 1
        while (n < samples.size) n = n shl 1
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        System.arraycopy(samples, 0, re, 0, samples.size)
        fftInPlace(re, im)
        val half = n / 2
        val mag = DoubleArray(half + 1)
        for (k in 0..half) mag[k] = sqrt(re[k] * re[k] + im[k] * im[k]) / samples.size
        return mag
    }

    private fun fftInPlace(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        // 位反转置换
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        // 蝶形
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
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
    }
}
