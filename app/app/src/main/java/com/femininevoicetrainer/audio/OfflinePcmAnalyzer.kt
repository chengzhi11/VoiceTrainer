package com.femininevoicetrainer.audio

import be.tarsos.dsp.pitch.Yin
import com.femininevoicetrainer.audio.VoiceFeatureExtractor.FrameDsp
import com.femininevoicetrainer.audio.VoiceFeatureExtractor.VoiceFeatures

/**
 * 整段 PCM 的离线会话分析(rescue 第二 pass 专用):
 * 对一段已缓存/已落盘的 PCM 逐帧重跑「YIN 检测 + 帧级 DSP + 会话聚合」,
 * 与录音期实时链路(PitchAnalyzer YIN 处理器 + VoiceFeatureCollector → analyze)
 * 同帧长、同判据、同置信度门——同一 PCM 两条链路结果逐位一致。
 *
 * 仅在录音停止后调用一次,不接触录音线程与实时显示。
 */
object OfflinePcmAnalyzer {

    /** 帧长 2048@44100 无重叠(与 PitchAnalyzer 的 dispatcher 帧口径一致) */
    private const val FRAME_SIZE = 2048

    /** F0 检测带 (Hz),与 PitchAnalyzer.MIN_F0/MAX_F0 一致(带外检出记 0) */
    private const val MIN_F0 = 50.0
    private const val MAX_F0 = 500.0

    /**
     * 整段 PCM → 会话级特征(含五门判定与失败归因)。
     * 尾部不足一帧的样本丢弃(与 dispatcher 行为一致)。
     */
    fun analyze(pcm: FloatArray, sampleRate: Int = 44100): VoiceFeatures {
        val nFrames = pcm.size / FRAME_SIZE
        if (nFrames == 0) return VoiceFeatures.empty()

        val yin = Yin(sampleRate.toFloat(), FRAME_SIZE)
        val dspFrames = ArrayList<FrameDsp>(nFrames)
        val rawF0 = ArrayList<Double>(nFrames)
        val rawProb = ArrayList<Double>(nFrames)
        val frame = FloatArray(FRAME_SIZE)

        for (i in 0 until nFrames) {
            System.arraycopy(pcm, i * FRAME_SIZE, frame, 0, FRAME_SIZE)

            // YIN 检测规则镜像 PitchAnalyzer:isPitched 且带内才记录 (f0, prob),否则 (0, 0)
            var f0 = 0.0
            var prob = 0.0
            val result = yin.getPitch(frame)
            if (result.isPitched()) {
                val p = result.pitch.toDouble()
                if (p in MIN_F0..MAX_F0) {
                    f0 = p
                    prob = result.probability.toDouble()
                }
            }

            dspFrames.add(VoiceFeatureCollector.computeFrameDsp(frame, sampleRate, f0))
            rawF0.add(f0)
            rawProb.add(prob)
        }
        return VoiceFeatureExtractor.analyze(dspFrames, rawF0, rawProb)
    }
}
