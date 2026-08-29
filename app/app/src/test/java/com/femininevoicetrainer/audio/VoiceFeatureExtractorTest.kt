package com.femininevoicetrainer.audio

import be.tarsos.dsp.pitch.Yin
import com.femininevoicetrainer.audio.VoiceFeatureExtractor.FrameDsp
import com.femininevoicetrainer.audio.VoiceFeatureExtractor.VoiceFeatures
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * 特征管线合成信号验证:
 * 已知 F0 正弦验证 F0/HNR,已知共振峰合成谱验证 LPC,vocal fry 低频样例验证检测链路
 */
class VoiceFeatureExtractorTest {

    companion object {
        const val SR = 44100
    }

    // ================= 合成信号工具 =================

    /** 正弦 */
    private fun sine(f0: Double, seconds: Double, sr: Int = SR, amplitude: Double = 0.5): FloatArray {
        val n = (seconds * sr).toInt()
        return FloatArray(n) { i ->
            (amplitude * sin(2.0 * PI * f0 * i / sr)).toFloat()
        }
    }

    /** 加白噪声(固定种子,保证可重复) */
    private fun addNoise(signal: FloatArray, sigma: Double, seed: Int = 42): FloatArray {
        val rnd = Random(seed)
        return FloatArray(signal.size) { i ->
            (signal[i] + sigma * rnd.nextDouble(-1.0, 1.0)).toFloat()
        }
    }

    /**
     * Klatt 共振器并联合成元音:已知 F1-F3 的全极点语音,
     * 用于 LPC 共振峰提取验证(formants = (F, BW, amp))
     */
    private fun synthesizeVowel(
        f0: Double,
        formants: List<Triple<Double, Double, Double>>,
        seconds: Double,
        sr: Int
    ): FloatArray {
        val n = (seconds * sr).toInt()
        val out = FloatArray(n)
        val states = Array(formants.size) { DoubleArray(2) } // y[n-1], y[n-2]
        var phase = 0.0
        for (i in 0 until n) {
            phase += f0 / sr
            val impulse = if (phase >= 1.0) { phase -= 1.0; 1.0 } else 0.0
            var sample = 0.0
            for ((idx, spec) in formants.withIndex()) {
                val (f, bw, amp) = spec
                val y1 = states[idx][0]
                val y2 = states[idx][1]
                val b = 2.0 * exp(-PI * bw / sr) * kotlin.math.cos(2.0 * PI * f / sr)
                val c = -exp(-2.0 * PI * bw / sr)
                val a = 1.0 - b - c
                val y = amp * a * impulse + b * y1 + c * y2
                states[idx][1] = y1
                states[idx][0] = y
                sample += y
            }
            out[i] = sample.toFloat()
        }
        return out
    }

    /** 与采集器一致的预处理链:降采样 → 加窗 → 预加重 */
    private fun preprocess(frame: FloatArray): Pair<DoubleArray, Int> {
        val down = VoiceFeatureExtractor.decimate(frame, SR, VoiceFeatureExtractor.DECIMATION_FACTOR)
        val w = VoiceFeatureExtractor.hammingWindow(down.size)
        val windowed = DoubleArray(down.size) { down[it] * w[it] }
        return Pair(windowed, SR / VoiceFeatureExtractor.DECIMATION_FACTOR)
    }

    // ================= YIN raw F0 链路 =================

    /**
     * 已知 F0 正弦验证:220Hz 正弦经 YIN 检出 ≈220
     */
    @Test
    fun testYinDetectsKnownF0_220Hz() {
        val yin = Yin(SR.toFloat(), 2048)
        val frame = sine(220.0, 2048.0 / SR)
        val result = yin.getPitch(frame)
        assertTrue("220Hz sine should be pitched", result.isPitched)
        assertEquals("YIN should detect ~220Hz", 220.0, result.pitch.toDouble(), 8.0)
    }

    /**
     * vocal fry 低频样例验证:55Hz 正弦在 MIN_F0 放宽到 50 后可检出
     * (MIN_F0 取 80Hz 下限时 fry 会被丢弃;2048 窗 2 周期下限 ≈43Hz)
     */
    @Test
    fun testYinDetectsVocalFry_55Hz() {
        val yin = Yin(SR.toFloat(), 2048)
        val frame = sine(55.0, 2048.0 / SR)
        val result = yin.getPitch(frame)
        assertTrue("55Hz fry-register sine should be pitched", result.isPitched)
        assertEquals("YIN should detect ~55Hz", 55.0, result.pitch.toDouble(), 6.0)
    }

    // ================= HNR =================

    /** 纯正弦 → 谐音成分纯,HNR 应接近上限(>15dB) */
    @Test
    fun testHnrPureSine_High() {
        val frame = sine(220.0, 2048.0 / SR)
        val hnr = VoiceFeatureExtractor.computeHnr(frame, SR, 220.0)
        assertTrue("Pure sine HNR should be >15dB, got $hnr", hnr > 15.0)
    }

    /** 半功率混入白噪声 → HNR 显著下降 */
    @Test
    fun testHnrNoisySine_Lower() {
        val frame = addNoise(sine(220.0, 2048.0 / SR), 0.5)
        val hnr = VoiceFeatureExtractor.computeHnr(frame, SR, 220.0)
        assertTrue("Noisy sine HNR should be <10dB, got $hnr", hnr < 10.0)
        assertTrue("Noisy sine HNR should stay >-15dB, got $hnr", hnr > -15.0)
    }

    @Test
    fun testHnrInvalidF0_ReturnsNaN() {
        val frame = sine(220.0, 2048.0 / SR)
        assertTrue(VoiceFeatureExtractor.computeHnr(frame, SR, 0.0).isNaN())
        assertTrue("f0 too low for frame should be NaN",
            VoiceFeatureExtractor.computeHnr(frame, SR, 20.0).isNaN())
    }

    // ================= LPC 共振峰 =================

    /**
     * 已知共振峰合成谱验证 LPC:三共振器合成元音
     * (F1=700,F2=1220,F3=2600,男声 /a/ 典型值)应被恢复(±20% 或 ±80Hz)
     */
    @Test
    fun testLpcFormants_SyntheticVowel() {
        val targets = listOf(700.0, 1220.0, 2600.0)
        val signal = synthesizeVowel(
            f0 = 120.0,
            formants = listOf(
                Triple(700.0, 100.0, 1.0),
                Triple(1220.0, 120.0, 0.5),
                Triple(2600.0, 150.0, 0.25)
            ),
            seconds = 0.5,
            sr = SR
        )
        val (windowed, srOut) = preprocess(signal)
        val formants = VoiceFeatureExtractor.computeFormantsLpc(
            VoiceFeatureExtractor.preEmphasis(windowed), srOut
        )
        assertNotNull("LPC should extract 3 formants", formants)
        for (k in 0 until 3) {
            val err = kotlin.math.abs(formants!![k] - targets[k])
            val tol = maxOf(targets[k] * 0.2, 80.0)
            assertTrue(
                "F${k + 1}: expected ~${targets[k]}, got ${formants[k]} (tol $tol)",
                err <= tol
            )
        }
    }

    /** 回退方案:FFT 谱包络峰值法在合成元音上也应找到 3 个共振峰区域 */
    @Test
    fun testFftFormantFallback_SyntheticVowel() {
        val targets = listOf(700.0, 1220.0, 2600.0)
        val signal = synthesizeVowel(
            f0 = 120.0,
            formants = listOf(
                Triple(700.0, 100.0, 1.0),
                Triple(1220.0, 120.0, 0.5),
                Triple(2600.0, 150.0, 0.25)
            ),
            seconds = 0.5,
            sr = SR
        )
        val (windowed, srOut) = preprocess(signal)
        val formants = VoiceFeatureExtractor.computeFormantsFft(windowed, srOut)
        assertNotNull("FFT fallback should find 3 peaks", formants)
        // 回退法精度更宽松(±25% 或 ±120Hz)
        for (k in 0 until 3) {
            val err = kotlin.math.abs(formants!![k] - targets[k])
            val tol = maxOf(targets[k] * 0.25, 120.0)
            assertTrue(
                "FFT fallback F${k + 1}: expected ~${targets[k]}, got ${formants[k]}",
                err <= tol
            )
        }
    }

    // ================= 谱倾斜 =================

    /** 低通信号 → 负倾斜;高通(差分)信号 → 正倾斜 */
    @Test
    fun testSpectralTilt_Sign() {
        val rnd = Random(7)
        val noise = DoubleArray(2048) { rnd.nextDouble(-1.0, 1.0) }

        // 一阶 IIR 低通(强负倾斜)
        val lp = DoubleArray(2048)
        var prev = 0.0
        for (i in 0 until 2048) {
            prev = 0.98 * prev + 0.02 * noise[i]
            lp[i] = prev
        }
        val w = VoiceFeatureExtractor.hammingWindow(2048)
        val lpTilt = VoiceFeatureExtractor.computeSpectralTilt(
            DoubleArray(2048) { lp[it] * w[it] }, SR
        )
        assertTrue("Lowpassed noise should have negative tilt, got $lpTilt", lpTilt < -3.0)

        // 差分高通(理想 +6dB/oct)
        val hp = DoubleArray(2048) { i -> if (i == 0) 0.0 else noise[i] - noise[i - 1] }
        val hpTilt = VoiceFeatureExtractor.computeSpectralTilt(
            DoubleArray(2048) { hp[it] * w[it] }, SR
        )
        assertTrue("Highpassed noise should have positive tilt, got $hpTilt", hpTilt > 3.0)
    }

    // ================= jitter / shimmer =================

    @Test
    fun testJitterRap_ConstantSeries_IsZero() {
        val jitter = VoiceFeatureExtractor.computeJitterRap(List(50) { 200.0 })
        assertEquals("Constant F0 series should have zero jitter", 0.0, jitter, 1e-12)
    }

    @Test
    fun testJitterRap_PerturbedSeries_KnownValue() {
        // [200,204] 交替:RAP = 2.667/202 ≈ 0.0132
        val series = List(50) { if (it % 2 == 0) 200.0 else 204.0 }
        val jitter = VoiceFeatureExtractor.computeJitterRap(series)
        assertEquals("Alternating series RAP should be ~0.0132", 0.0132, jitter, 0.001)
    }

    @Test
    fun testShimmerDb_FlatRms_IsZero() {
        val shimmer = VoiceFeatureExtractor.computeShimmerDb(List(20) { 0.3 })
        assertEquals("Flat RMS should have zero shimmer", 0.0, shimmer, 1e-12)
    }

    // ================= 统计原语 =================

    @Test
    fun testPercentile() {
        val values = List(100) { it + 1.0 } // 1..100
        assertEquals(10.9, VoiceFeatureExtractor.percentile(values, 0.10), 0.01)
        assertEquals(50.5, VoiceFeatureExtractor.percentile(values, 0.50), 0.01)
        assertEquals(90.1, VoiceFeatureExtractor.percentile(values, 0.90), 0.01)
        assertEquals(1.0, VoiceFeatureExtractor.percentile(values, 0.0), 0.01)
        assertEquals(100.0, VoiceFeatureExtractor.percentile(values, 1.0), 0.01)
    }

    @Test
    fun testCoefficientOfVariation() {
        assertEquals(0.0, VoiceFeatureExtractor.coefficientOfVariation(listOf(5.0, 5.0, 5.0)), 1e-12)
        // [100,200]:mean 150,总体 std 50 → CV = 1/3
        assertEquals(1.0 / 3.0, VoiceFeatureExtractor.coefficientOfVariation(listOf(100.0, 200.0)), 0.001)
        assertEquals(0.0, VoiceFeatureExtractor.coefficientOfVariation(emptyList()), 0.0)
    }

    // ================= 会话聚合(置信度过滤,防噪声拉低统计) =================

    private fun frameDsp(rms: Double = 0.1): FrameDsp = FrameDsp(
        rms = rms, hnrDb = 18.0, f1 = 700.0, f2 = 1220.0, f3 = 2600.0, tiltDbOct = -3.0
    )

    /**
     * 置信度 >0.7 过滤:噪声帧(prob=0.3, F0=60)不得污染会话统计
     */
    @Test
    fun testAnalyze_ConfidenceFilterExcludesNoiseFrames() {
        val frames = mutableListOf<FrameDsp>()
        val f0 = mutableListOf<Double>()
        val prob = mutableListOf<Double>()
        // 80 帧有效语音 (220Hz)
        repeat(80) {
            frames.add(frameDsp())
            f0.add(220.0)
            prob.add(0.95)
        }
        // 20 帧环境噪声(低置信度、低 F0)
        repeat(20) {
            frames.add(frameDsp())
            f0.add(60.0)
            prob.add(0.3)
        }

        val features = VoiceFeatureExtractor.analyze(frames, f0, prob)

        assertTrue(features.isUsable)
        assertEquals(100, features.frameCount)
        assertEquals(80, features.voicedFrameCount)
        assertEquals(0.8, features.voicedRatio, 1e-9)
        assertEquals("Noise frames (60Hz) must be excluded", 220.0, features.f0P50, 0.01)
        assertTrue("P10 should also exclude noise frames", features.f0P10 > 200.0)
        assertEquals(0.0, features.f0Cv, 1e-12)
        assertEquals(700.0, features.f1, 0.01)
        assertEquals((2600.0 - 700.0) / 3.0, features.resonanceSpacing, 0.01)
    }

    /** 有效语音占比过低 → 不可判别(isUsable=false) */
    @Test
    fun testAnalyze_InsufficientVoicedRatio_NotUsable() {
        val frames = mutableListOf<FrameDsp>()
        val f0 = mutableListOf<Double>()
        val prob = mutableListOf<Double>()
        repeat(40) {
            frames.add(frameDsp())
            f0.add(0.0)
            prob.add(0.0)
        }
        repeat(10) {
            frames.add(frameDsp())
            f0.add(200.0)
            prob.add(0.95)
        }
        val features = VoiceFeatureExtractor.analyze(frames, f0, prob)
        assertFalse("20% voiced ratio should not be usable", features.isUsable)
        assertEquals(0.2, features.voicedRatio, 1e-9)
    }

    /** 空输入安全 */
    @Test
    fun testAnalyze_EmptyInput() {
        val features = VoiceFeatureExtractor.analyze(emptyList(), emptyList(), emptyList())
        assertFalse(features.isUsable)
        assertEquals(0, features.frameCount)
        assertEquals(0.0, features.f0P50, 0.0)
    }

    /** raw 通道值经聚合不被平滑抹平:交替 F0 序列的 CV 保持真实扰动 */
    @Test
    fun testAnalyze_RawChannelPreservesPerturbation() {
        val frames = List(60) { frameDsp() }
        val f0 = List(60) { if (it % 2 == 0) 210.0 else 230.0 }
        val prob = List(60) { 0.95 }
        val features = VoiceFeatureExtractor.analyze(frames, f0, prob)
        // 交替 220±10:总体 std=10,mean=220 → CV≈0.045
        assertTrue("Raw channel CV should reflect real perturbation", features.f0Cv > 0.03)
        assertTrue(features.jitterRap > 0.02)
    }
}
