package com.femininevoicetrainer.audio

import com.femininevoicetrainer.audio.VoiceFeatureExtractor.SessionFailReason
import com.femininevoicetrainer.audio.VoiceFeatureExtractor.VoiceFeatures

/**
 * rescue-on-TOO_NOISY 两 pass 重评(GH#5 集成模式,选型定稿见 docs/research-denoise.md §5):
 *
 * 录音结束 → analyze()(现状不动)→ 不可用且归因 TOO_NOISY、电平排除「太轻」
 * → 对整段 PCM 施加 HPF@120Hz → 谱减 → 重跑同一 analyze(五门门限不变)
 * → 重评可用则出分(留痕 denoiseApplied=true);仍不可用维持 TOO_NOISY 文案。
 *
 * 不是无条件常开:实测干净臂无条件过链会 3.50→3.41 反向翻 FAIL(边界会话反受损),
 * 只有已拒判的会话才尝试拯救,干净会话零漂移零回归。
 */
object DenoiseRescue {

    /**
     * 第一 pass 结果是否值得跑降噪重评:
     * ①不可用 ②归因 TOO_NOISY(其余归因对应别的解法)③语音电平达标
     * (排除「太轻」归因混淆;按归因优先级 TOO_NOISY 时③恒真,防御性保留)。
     */
    fun shouldRescue(firstPass: VoiceFeatures): Boolean =
        !firstPass.isUsable &&
            firstPass.failReason == SessionFailReason.TOO_NOISY &&
            firstPass.speechLevel >= VoiceTypeThresholds.MIN_SPEECH_LEVEL

    /**
     * 对整段 PCM 跑第二 pass:降噪主链 → 同一判据重评。
     * 调用方负责在后台线程执行(整段 STFT 约 1-3s)。
     */
    fun rescue(pcm: FloatArray): VoiceFeatures =
        OfflinePcmAnalyzer.analyze(DenoiseProcessor.process(pcm))
}
