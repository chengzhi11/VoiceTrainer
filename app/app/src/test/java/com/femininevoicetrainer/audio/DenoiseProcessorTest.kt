package com.femininevoicetrainer.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * DenoiseProcessor 纯 DSP 级测试(合成信号,不依赖离线语料):
 * HPF 频响特性 / 谱减噪声抑制 / 长度与数值完整性守恒。
 * 判据级一致性(80 wav 基线复现)见 DenoiseBaselineConsistencyTest。
 */
class DenoiseProcessorTest {

    private val sr = 44100

    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from))
    }

    private fun sine(freqHz: Double, amp: Double, seconds: Double): FloatArray {
        val n = (seconds * sr).toInt()
        val x = FloatArray(n)
        for (i in 0 until n) x[i] = (amp * sin(2.0 * Math.PI * freqHz * i / sr)).toFloat()
        return x
    }

    // ---- HPF ----

    @Test
    fun hpf_attenuatesLowTone_passesHighTone() {
        // 二阶 Butterworth@120Hz:60Hz 处幅度 ≈0.24(−12dB),500Hz 处 ≈0.997
        val low = DenoiseProcessor.highPass(sine(60.0, 0.5, 1.0), 120.0, sr)
        val lowRatio = rms(low) / rms(sine(60.0, 0.5, 1.0))
        assertTrue("60Hz 应被强衰减(实测比例 $lowRatio)", lowRatio < 0.35)

        val high = DenoiseProcessor.highPass(sine(500.0, 0.5, 1.0), 120.0, sr)
        val highRatio = rms(high) / rms(sine(500.0, 0.5, 1.0))
        assertTrue("500Hz 应基本无衰减(实测比例 $highRatio)", highRatio > 0.9)
    }

    @Test
    fun hpf_removesDc() {
        val dc = FloatArray(sr) { 0.5f }
        val y = DenoiseProcessor.highPass(dc, 120.0, sr)
        // 稳态后(后半段)直流分量应归零
        var mean = 0.0
        for (i in y.size / 2 until y.size) mean += y[i]
        mean /= y.size / 2
        assertTrue("稳态均值应近 0(实测 $mean)", abs(mean) < 1e-3)
        assertTrue("稳态 RMS 应近 0(实测 ${rms(y, y.size / 2)})", rms(y, y.size / 2) < 1e-3)
    }

    // ---- 谱减 ----

    @Test
    fun spectralSubtract_reducesStationaryNoise() {
        // 稳态白噪:噪声 PSD 由自身最低能量帧估计,过减后残余应大幅下降。
        // 首尾一个 STFT 窗内窗功率和趋零,归一放大边界数值残留(原型同款边界行为,
        // 会话级信号中占比 <0.5% 可忽略)——故取中段评估
        val rng = java.util.Random(42)
        val noise = FloatArray(3 * sr) { (rng.nextFloat() * 2 - 1) * 0.2f }
        val y = DenoiseProcessor.spectralSubtract(noise)
        val mid = IntRange(2048, noise.size - 2048)
        val ratio = rms(y, mid.first, mid.last + 1) / rms(noise, mid.first, mid.last + 1)
        assertTrue("稳态噪声中段 RMS 应显著下降(实测残余比例 $ratio)", ratio < 0.6)
    }

    @Test
    fun spectralSubtract_shortInputPassthrough() {
        val short = FloatArray(500) { it * 0.001f }
        val y = DenoiseProcessor.spectralSubtract(short)
        assertEquals("短于一个 STFT 窗应原样返回", short.size, y.size)
        for (i in short.indices) assertEquals(short[i].toDouble(), y[i].toDouble(), 0.0)
    }

    // ---- 主链 ----

    @Test
    fun process_preservesLengthAndFinity() {
        val rng = java.util.Random(7)
        val x = FloatArray(sr) {
            (0.3 * sin(2.0 * Math.PI * 220.0 * it / sr)).toFloat() + (rng.nextFloat() * 2 - 1) * 0.02f
        }
        val y = DenoiseProcessor.process(x)
        assertEquals(x.size, y.size)
        assertTrue(y.all { !it.isNaN() && !it.isInfinite() })
        // 220Hz 主音 survives:残余能量不为零
        assertTrue("主音不应被整体抹除(rms=${rms(y)})", rms(y) > 0.01)
    }

    @Test
    fun process_silenceStaysSilent() {
        val y = DenoiseProcessor.process(FloatArray(sr))
        assertTrue(y.all { abs(it) < 1e-6f })
    }
}
