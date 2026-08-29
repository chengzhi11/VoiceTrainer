package com.femininevoicetrainer.audio

import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.AudioProcessor
import com.femininevoicetrainer.audio.VoiceFeatureExtractor.FrameDsp

/**
 * 逐帧特征采集器:挂在 PitchAnalyzer 创建的同一 AudioDispatcher 上,
 * 不动录音链路单消费者架构(AudioRecord 仍由录音线程唯一读取)。
 *
 * dispatcher 按添加顺序逐帧调用处理器:PitchAnalyzer 的 YIN 处理器在前,
 * 本采集器在后——同一帧内可直接读取 PitchAnalyzer 暴露的 raw F0/置信度
 * (volatile,由 YIN 处理器在本帧先行写入),实现帧级对齐。
 */
class VoiceFeatureCollector(
    private val sampleRate: Int,
    private val pitchAnalyzer: PitchAnalyzer
) : AudioProcessor {

    private val frames = ArrayList<FrameDsp>()

    // 复用缓冲(仅 dispatcher 消费线程访问,无并发写)
    private var window: DoubleArray? = null

    /** 会话帧数 */
    val frameCount: Int get() = synchronized(frames) { frames.size }

    /** 停止后取整轮帧特征快照 */
    fun snapshot(): List<FrameDsp> = synchronized(frames) { frames.toList() }

    fun reset() = synchronized(frames) { frames.clear() }

    override fun process(audioEvent: AudioEvent?): Boolean {
        audioEvent ?: return true
        val buffer = audioEvent.floatBuffer
        if (buffer.isEmpty()) return true

        val rms = VoiceFeatureExtractor.computeRms(buffer)
        val f0 = pitchAnalyzer.lastFrameF0
        val hnr = if (f0 > 0.0) {
            VoiceFeatureExtractor.computeHnr(buffer, sampleRate, f0)
        } else Double.NaN

        // 降采样 → 加窗 → 谱倾斜(LPC 用预加重版,倾斜用未预加重版)
        val down = VoiceFeatureExtractor.decimate(buffer, sampleRate, VoiceFeatureExtractor.DECIMATION_FACTOR)
        val w = windowFor(down.size)
        val windowed = DoubleArray(down.size) { down[it] * w[it] }
        val tilt = VoiceFeatureExtractor.computeSpectralTilt(windowed, sampleRate / VoiceFeatureExtractor.DECIMATION_FACTOR)

        // 共振峰:LPC 主方案,失败回退 FFT 谱包络峰值法(前期信号处理调研定稿的两级提取方案)
        val preemph = VoiceFeatureExtractor.preEmphasis(windowed)
        val formants = VoiceFeatureExtractor.computeFormantsLpc(preemph, sampleRate / VoiceFeatureExtractor.DECIMATION_FACTOR)
            ?: VoiceFeatureExtractor.computeFormantsFft(
                windowed,
                sampleRate / VoiceFeatureExtractor.DECIMATION_FACTOR
            ) ?: listOf(Double.NaN, Double.NaN, Double.NaN)

        synchronized(frames) {
            frames.add(
                FrameDsp(
                    rms = rms,
                    hnrDb = hnr,
                    f1 = formants.getOrElse(0) { Double.NaN },
                    f2 = formants.getOrElse(1) { Double.NaN },
                    f3 = formants.getOrElse(2) { Double.NaN },
                    tiltDbOct = tilt
                )
            )
        }
        return true
    }

    override fun processingFinished() {
        // dispatcher 停止时无需额外清理
    }

    private fun windowFor(n: Int): DoubleArray {
        val existing = window
        if (existing != null && existing.size == n) return existing
        val created = VoiceFeatureExtractor.hammingWindow(n)
        window = created
        return created
    }
}
