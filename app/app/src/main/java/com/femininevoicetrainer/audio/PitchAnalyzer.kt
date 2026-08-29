package com.femininevoicetrainer.audio

import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.AudioProcessor
import be.tarsos.dsp.io.TarsosDSPAudioInputStream
import be.tarsos.dsp.pitch.PitchDetectionHandler
import be.tarsos.dsp.pitch.PitchDetectionResult
import be.tarsos.dsp.pitch.PitchDetector
import be.tarsos.dsp.pitch.Yin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.LinkedList

/**
 * 音高分析器
 * Real-time pitch (F0) analyzer using TarsosDSP YIN algorithm
 *
 * 功能：
 * - 实时检测基频 (F0)
 * - 基于 YIN 算法，业界领先的基频检测算法
 * - 低延迟 (<50ms)
 * - 高准确度 (适用于人声频率范围 80-500 Hz)
 */
class PitchAnalyzer {

    companion object {
        /**
         * 音频配置参数
         */
        private const val SAMPLE_RATE = 44100 // 采样率 44.1kHz
        private const val BUFFER_SIZE = 2048  // 缓冲区大小 (YIN算法推荐)
        private const val OVERLAP = 0         // 缓冲区重叠

        /**
         * F0检测范围 (Hz)
         * MIN_F0 从 80 放宽到 50:声线分类需覆盖 vocal fry(20-70Hz,文献主流;
         * 2048 窗 @44.1kHz 的 2 周期下限约 43Hz,50 为可检测下限)
         */
        private const val MIN_F0 = 50.0
        private const val MAX_F0 = 500.0

        /**
         * 平滑窗口大小
         * 用于减少F0值的抖动
         */
        private const val SMOOTHING_WINDOW = 5
    }

    /**
     * F0检测结果数据类
     */
    data class PitchResult(
        val f0: Double,       // 基频 in Hz
        val probability: Double, // 检测概率 0-1
        val isReliable: Boolean,  // 是否可靠
        val timestamp: Long   // 时间戳
    )

    /**
     * 实时F0值 (StateFlow用于UI订阅)
     */
    private val _currentF0 = MutableStateFlow(0.0)
    val currentF0: StateFlow<Double> = _currentF0.asStateFlow()

    /**
     * 实时概率值
     */
    private val _currentProbability = MutableStateFlow(0.0)
    val currentProbability: StateFlow<Double> = _currentProbability.asStateFlow()

    /**
     * 历史F0值 (用于平滑和统计)
     */
    private val f0History = LinkedList<Double>()

    /**
     * 历史概率值
     */
    private val probabilityHistory = LinkedList<Double>()

    /**
     * raw 未平滑 F0 通道(会话级,逐 dispatcher 帧 1:1,未检出帧为 0.0)。
     * jitter/shimmer 与会话统计必须用它——smoothF0 移动平均会抹掉周期扰动,
     * 用平滑值算 jitter 恒近 0。
     * 仅 dispatcher 线程写入,读取发生在 stopAnalysis 之后,无需加锁。
     */
    private val rawF0History = ArrayList<Double>()

    /**
     * raw 未平滑置信度通道(与 rawF0History 帧对齐)
     */
    private val rawProbabilityHistory = ArrayList<Double>()

    /**
     * 最近一帧 raw F0( dispatcher 线程逐帧写入,供后挂的特征采集器同帧读取)
     */
    @Volatile
    var lastFrameF0: Double = 0.0
        private set

    /**
     * 最近一帧 raw 置信度
     */
    @Volatile
    var lastFrameProbability: Double = 0.0
        private set

    /**
     * YIN 算法检测器
     */
    private var pitchDetector: PitchDetector? = null

    /**
     * 音频调度器
     */
    private var audioDispatcher: AudioDispatcher? = null

    /**
     * 分析器状态
     */
    @Volatile
    private var isAnalyzing = false

    /**
     * 初始化YIN算法检测器
     */
    private fun initializePitchDetector(): PitchDetector {
        return Yin(
            SAMPLE_RATE.toFloat(),
            BUFFER_SIZE
        )
    }

    /**
     * 开始分析音频流
     * @param audioStream 音频输入流(AudioRecorder 分发数据,不直读 AudioRecord)
     * @param extraProcessor 可选的额外处理器(如 VoiceFeatureCollector),
     *        挂到同一 dispatcher、按添加顺序在 YIN 处理器之后逐帧执行,
     *        不动录音链路单消费者架构
     */
    fun startAnalysis(audioStream: TarsosDSPAudioInputStream, extraProcessor: AudioProcessor? = null) {
        if (isAnalyzing) {
            stopAnalysis()
        }

        isAnalyzing = true
        f0History.clear()
        probabilityHistory.clear()
        rawF0History.clear()
        rawProbabilityHistory.clear()

        // 初始化YIN检测器
        pitchDetector = initializePitchDetector()

        // 创建音频调度器
        audioDispatcher = AudioDispatcher(
            audioStream,
            BUFFER_SIZE,
            OVERLAP
        )

        // 添加YIN处理器
        val pitchProcessor = object : AudioProcessor {
            override fun process(audioEvent: AudioEvent?): Boolean {
                audioEvent?.let { event ->
                    val audioBuffer = event.floatBuffer

                    // 使用YIN算法检测音高
                    val result = pitchDetector?.getPitch(audioBuffer)

                    // raw 通道:逐帧无条件记录(未检出记 0),保持与特征采集器帧序 1:1
                    var frameF0 = 0.0
                    var frameProb = 0.0

                    result?.let { pitchResult ->
                        if (pitchResult.isPitched()) {
                            val f0 = pitchResult.pitch.toDouble()
                            val probability = pitchResult.probability

                            // 检查F0是否在人声范围内
                            if (f0 in MIN_F0..MAX_F0) {
                                frameF0 = f0
                                frameProb = probability.toDouble()

                                // 应用平滑处理(仅用于实时显示;统计走 raw 通道)
                                val smoothedF0 = smoothF0(f0)
                                val smoothedProbability = smoothProbability(probability.toDouble())

                                // 更新StateFlow
                                _currentF0.value = smoothedF0
                                _currentProbability.value = smoothedProbability

                                // 保存历史值
                                f0History.add(smoothedF0)
                                probabilityHistory.add(smoothedProbability)

                                // 限制历史记录大小
                                while (f0History.size > SMOOTHING_WINDOW * 2) {
                                    f0History.removeFirst()
                                    probabilityHistory.removeFirst()
                                }
                            }
                        } else {
                            // 未检测到有效音高
                            _currentF0.value = 0.0
                            _currentProbability.value = 0.0
                        }
                    }

                    lastFrameF0 = frameF0
                    lastFrameProbability = frameProb
                    rawF0History.add(frameF0)
                    rawProbabilityHistory.add(frameProb)
                }
                return true
            }

            override fun processingFinished() {
                // 处理完成
            }
        }

        audioDispatcher?.addAudioProcessor(pitchProcessor)
        extraProcessor?.let { audioDispatcher?.addAudioProcessor(it) }

        // 启动音频处理线程
        Thread {
            audioDispatcher?.run()
        }.start()
    }

    /**
     * 停止分析
     */
    fun stopAnalysis() {
        isAnalyzing = false
        audioDispatcher?.stop()
        audioDispatcher = null
        pitchDetector = null

        // 重置F0值
        _currentF0.value = 0.0
        _currentProbability.value = 0.0
        lastFrameF0 = 0.0
        lastFrameProbability = 0.0
    }

    /**
     * 会话级 raw F0 序列(未平滑,与帧序对齐;未检出帧为 0)
     */
    fun getRawF0Series(): List<Double> = rawF0History.toList()

    /**
     * 会话级 raw 置信度序列(与 raw F0 帧对齐)
     */
    fun getRawProbabilitySeries(): List<Double> = rawProbabilityHistory.toList()

    /**
     * 平滑F0值 (移动平均)
     */
    private fun smoothF0(newF0: Double): Double {
        if (f0History.isEmpty()) return newF0

        // 取最近N个值的平均值
        val windowSize = minOf(SMOOTHING_WINDOW, f0History.size)
        val recentF0s = f0History.takeLast(windowSize - 1) + newF0
        return recentF0s.average()
    }

    /**
     * 平滑概率值
     */
    private fun smoothProbability(newProbability: Double): Double {
        if (probabilityHistory.isEmpty()) return newProbability

        val windowSize = minOf(SMOOTHING_WINDOW, probabilityHistory.size)
        val recentProbabilities = probabilityHistory.takeLast(windowSize - 1) + newProbability
        return recentProbabilities.average()
    }

    /**
     * 获取当前F0值
     */
    fun getCurrentF0(): Double {
        return _currentF0.value
    }

    /**
     * 获取当前概率值
     */
    fun getCurrentProbability(): Double {
        return _currentProbability.value
    }

    /**
     * 获取F0历史记录
     */
    fun getF0History(): List<Double> {
        return f0History.toList()
    }

    /**
     * 获取平均F0值
     */
    fun getAverageF0(): Double {
        val validF0s = f0History.filter { it > 0 }
        return if (validF0s.isNotEmpty()) {
            validF0s.average()
        } else {
            0.0
        }
    }

    /**
     * 获取最高F0值
     */
    fun getMaxF0(): Double {
        return f0History.filter { it > 0 }.maxOrNull() ?: 0.0
    }

    /**
     * 获取最低F0值
     */
    fun getMinF0(): Double {
        return f0History.filter { it > 0 }.minOrNull() ?: 0.0
    }

    /**
     * 清空历史记录
     */
    fun clearHistory() {
        f0History.clear()
        probabilityHistory.clear()
        _currentF0.value = 0.0
        _currentProbability.value = 0.0
    }

    /**
     * 是否正在分析
     */
    fun isAnalyzing(): Boolean {
        return isAnalyzing
    }

    /**
     * 获取F0统计信息
     */
    fun getF0Statistics(): F0Statistics {
        val validF0s = f0History.filter { it > 0 }
        if (validF0s.isEmpty()) {
            return F0Statistics(0.0, 0.0, 0.0, 0)
        }

        return F0Statistics(
            average = validF0s.average(),
            max = validF0s.maxOrNull() ?: 0.0,
            min = validF0s.minOrNull() ?: 0.0,
            sampleCount = validF0s.size
        )
    }

    /**
     * F0统计信息数据类
     */
    data class F0Statistics(
        val average: Double,
        val max: Double,
        val min: Double,
        val sampleCount: Int
    )
}
