package com.femininevoicetrainer.audio

import com.femininevoicetrainer.audio.VoiceFeatureExtractor.SessionFailReason
import com.femininevoicetrainer.audio.VoiceFeatureExtractor.VoiceFeatures
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

/**
 * rescue-on-TOO_NOISY 触发条件分支测试(GH#5 集成模式):
 * 仅「不可用 ∧ 归因 TOO_NOISY ∧ 电平达标」的会话才跑降噪重评,其余归因不触发。
 */
class DenoiseRescueTest {

    private fun features(
        isUsable: Boolean,
        failReason: SessionFailReason?,
        speechLevel: Double
    ): VoiceFeatures = VoiceFeatures(
        frameCount = 100, voicedFrameCount = 80, voicedRatio = 0.8, isUsable = isUsable,
        f0P10 = 0.0, f0P50 = 0.0, f0P90 = 0.0, f0Cv = 0.0,
        f1 = 0.0, f2 = 0.0, f3 = 0.0, resonanceSpacing = 0.0,
        hnrDb = 0.0, tiltDbOct = 0.0, jitterRap = 0.0, shimmerDb = 0.0, rmsMean = 0.0,
        speechLevel = speechLevel, noiseFloor = 0.0, activeVoicedRatio = 0.0,
        failReason = failReason
    )

    @Test
    fun usableSession_neverRescued() {
        assertFalse(DenoiseRescue.shouldRescue(features(true, null, 0.2)))
    }

    @Test
    fun tooShort_notRescued() {
        assertFalse(DenoiseRescue.shouldRescue(features(false, SessionFailReason.TOO_SHORT, 0.2)))
    }

    @Test
    fun tooQuiet_notRescued() {
        assertFalse(DenoiseRescue.shouldRescue(features(false, SessionFailReason.TOO_QUIET, 0.01)))
    }

    @Test
    fun silent_notRescued() {
        assertFalse(DenoiseRescue.shouldRescue(features(false, SessionFailReason.SILENT, 0.001)))
    }

    @Test
    fun insuffVoiced_notRescued() {
        assertFalse(DenoiseRescue.shouldRescue(features(false, SessionFailReason.INSUFF_VOICED, 0.2)))
    }

    @Test
    fun tooNoisy_withSufficientLevel_rescued() {
        assertTrue(
            "TOO_NOISY 且电平 ≥ MIN_SPEECH_LEVEL 是唯一触发分支",
            DenoiseRescue.shouldRescue(features(false, SessionFailReason.TOO_NOISY, 0.2))
        )
    }

    @Test
    fun tooNoisy_withLowLevel_notRescued() {
        // 归因优先级下 TOO_NOISY 时电平恒达标,此为防御性兜底(排除「太轻」归因混淆)
        assertFalse(
            DenoiseRescue.shouldRescue(
                features(false, SessionFailReason.TOO_NOISY, VoiceTypeThresholds.MIN_SPEECH_LEVEL / 2)
            )
        )
    }

    @Test
    fun rescue_onTooShortPcm_returnsWellFormedRejection() {
        // 0.5s 纯音:重评后仍应因 TOO_SHORT 不可用(不崩溃、结构完整)
        val pcm = FloatArray(22050) { (0.3 * sin(2.0 * Math.PI * 220.0 * it / 44100.0)).toFloat() }
        val result = DenoiseRescue.rescue(pcm)
        assertNotNull(result)
        assertFalse(result.isUsable)
        assertEquals(SessionFailReason.TOO_SHORT, result.failReason)
    }

    /** 帧特征构造冒烟:computeFrameDsp 对静音帧不崩溃,rms=0 */
    @Test
    fun computeFrameDsp_silenceFrame() {
        val dsp = VoiceFeatureCollector.computeFrameDsp(FloatArray(2048), 44100, 0.0)
        assertEquals(0.0, dsp.rms, 1e-12)
        assertTrue(dsp.hnrDb.isNaN())
    }

    /** 帧特征构造冒烟:带 F0 静音帧,HNR 无效但不抛异常 */
    @Test
    fun computeFrameDsp_silenceFrameWithF0() {
        val dsp = VoiceFeatureCollector.computeFrameDsp(FloatArray(2048), 44100, 220.0)
        assertEquals(0.0, dsp.rms, 1e-12)
    }
}
