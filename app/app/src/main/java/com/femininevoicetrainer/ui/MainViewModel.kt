package com.femininevoicetrainer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.femininevoicetrainer.audio.AudioRecorder
import com.femininevoicetrainer.audio.PitchAnalyzer
import com.femininevoicetrainer.audio.ScoringAlgorithm
import com.femininevoicetrainer.audio.VoiceEvaluator
import com.femininevoicetrainer.audio.VoiceFeatureExtractor
import com.femininevoicetrainer.data.AppDatabase
import com.femininevoicetrainer.data.Recording
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 主界面ViewModel
 * MainViewModel for managing voice training UI state and business logic
 *
 * 功能：
 * - 录音控制(长按录音:按下开始,松开结束,结束自动回放)
 * - 实时F0显示
 * - 会话级特征聚合 + 五维评分/太监音判别/声线识别
 * - 历史记录管理
 * - 自动化钩子(auto_record_ms extra,供回环脚本摆脱 UI 坐标依赖)
 * - 权限处理
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    // Database
    private val database = AppDatabase.getInstance(application)
    private val recordingDao = database.recordingDao()

    // Audio Recorder
    val audioRecorder = AudioRecorder(application)

    // UI State
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Current recording file
    private var currentRecordingFile: File? = null

    // auto_record_ms 去重标记
    private var lastAutoRecordMs: Int = -1
    private var lastAutoRecordAt: Long = 0L

    init {
        // Load recordings from database
        loadRecordings()

        // Observe pitch analyzer updates
        observePitchUpdates()
    }

    /**
     * UI状态数据类
     */
    data class UiState(
        val isRecording: Boolean = false,
        val isPlaying: Boolean = false,
        val currentF0: Double = 0.0,
        val currentScore: Double = 0.0,
        val currentProbability: Double = 0.0,
        val recordingDuration: Long = 0L,
        val recordings: List<Recording> = emptyList(),
        val isLoading: Boolean = false,
        val errorMessage: String? = null,
        val permissionGranted: Boolean = false,
        val showPermissionDialog: Boolean = false,
        /** 本轮录音结果(停止后展示:声线/五维/判别/预警) */
        val lastResult: SessionResult? = null,
        /** 回放结束,显示「重新录制/重听」操作 */
        val showReplayActions: Boolean = false
    )

    /**
     * 一轮录音的评估结果(特征聚合 + 判别)
     */
    data class SessionResult(
        val evaluation: VoiceEvaluator.VoiceEvaluation,
        val features: VoiceFeatureExtractor.VoiceFeatures,
        val filePath: String,
        val durationMs: Long
    )

    companion object {
        /** 松开后等待录音真正开始的超时(快速点放手势兜底) */
        private const val WAIT_START_TIMEOUT_MS = 2000L
        private const val WAIT_START_POLL_MS = 25L
        /** 回放结束后展示操作按钮的轮询上限 */
        private const val WAIT_PLAYBACK_TIMEOUT_MS = 120_000L
        /** auto_record_ms 去重窗口(旋转/重建 Activity 重复派发 same extra) */
        private const val AUTO_RECORD_DEDUPE_MS = 10_000L
    }

    /**
     * 观察音高分析器更新
     */
    private fun observePitchUpdates() {
        val pitchAnalyzer = audioRecorder.getPitchAnalyzer()

        viewModelScope.launch {
            // Observe F0 changes
            pitchAnalyzer.currentF0.collect { f0 ->
                _uiState.value = _uiState.value.copy(
                    currentF0 = f0,
                    currentScore = if (f0 > 0) ScoringAlgorithm.calculateFemininityScore(f0) else 0.0
                )
            }
        }

        viewModelScope.launch {
            // Observe probability changes
            pitchAnalyzer.currentProbability.collect { probability ->
                _uiState.value = _uiState.value.copy(currentProbability = probability)
            }
        }

        viewModelScope.launch {
            // Observe recording duration changes
            audioRecorder.recordingDuration.collect { duration ->
                _uiState.value = _uiState.value.copy(recordingDuration = duration)
            }
        }

        viewModelScope.launch {
            // Observe recording state changes
            audioRecorder.recordingState.collect { state ->
                _uiState.value = _uiState.value.copy(
                    isRecording = state == AudioRecorder.RecordingState.RECORDING
                )
            }
        }

        viewModelScope.launch {
            // Observe playback state changes
            audioRecorder.playbackState.collect { state ->
                _uiState.value = _uiState.value.copy(
                    isPlaying = state == AudioRecorder.PlaybackState.PLAYING
                )
            }
        }
    }

    /**
     * 开始录音(长按按下触发)
     */
    fun startRecording() {
        if (_uiState.value.isRecording) return
        viewModelScope.launch {
            try {
                // 新一轮开始:清上一轮结果与回放操作
                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    errorMessage = null,
                    lastResult = null,
                    showReplayActions = false
                )

                val file = audioRecorder.startRecording()
                if (file != null) {
                    currentRecordingFile = file
                    _uiState.value = _uiState.value.copy(
                        isLoading = false
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "录音启动失败"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "录音启动失败: ${e.message}"
                )
            }
        }
    }

    /**
     * 停止录音(长按松开触发),随后:
     * 特征聚合 → 五维评分/判别/声线识别 → 入库 → 自动回放 → 显示「重新录制/重听」
     */
    fun stopRecording() {
        viewModelScope.launch {
            try {
                // 长按极快松开时录音可能仍在启动中:短暂等待,超时按取消处理
                val waitDeadline = System.currentTimeMillis() + WAIT_START_TIMEOUT_MS
                while (audioRecorder.recordingState.value != AudioRecorder.RecordingState.RECORDING) {
                    if (System.currentTimeMillis() > waitDeadline) return@launch
                    delay(WAIT_START_POLL_MS)
                }

                val file = audioRecorder.stopRecording()
                if (file != null) {
                    currentRecordingFile = file
                    val durationMs = audioRecorder.recordingDuration.value

                    // 会话级特征聚合(置信度>0.7 过滤,防环境噪声拉低统计的系统性解法)
                    val pitchAnalyzer = audioRecorder.getPitchAnalyzer()
                    val collector = audioRecorder.getVoiceFeatureCollector()
                    val features = VoiceFeatureExtractor.analyze(
                        collector?.snapshot() ?: emptyList(),
                        pitchAnalyzer.getRawF0Series(),
                        pitchAnalyzer.getRawProbabilitySeries()
                    )
                    val evaluation = VoiceEvaluator.evaluate(features)

                    // 入库(五维子分/声线标签/判别结果,migration v2)
                    val recording = Recording(
                        filePath = file.absolutePath,
                        duration = durationMs,
                        averageF0 = features.f0P50,
                        score = evaluation.totalScore,
                        pitchScore = evaluation.subScores.pitch,
                        resonanceScore = evaluation.subScores.resonance,
                        stabilityScore = evaluation.subScores.stability,
                        qualityScore = evaluation.subScores.quality,
                        smoothnessScore = evaluation.subScores.smoothness,
                        mismatch = evaluation.mismatch,
                        voiceType = evaluation.voiceType?.label,
                        voiceCondition = evaluation.condition.label
                    )
                    recordingDao.insert(recording)
                    loadRecordings()

                    _uiState.value = _uiState.value.copy(
                        errorMessage = null,
                        currentF0 = 0.0,
                        currentScore = 0.0,
                        lastResult = SessionResult(evaluation, features, file.absolutePath, durationMs),
                        showReplayActions = false
                    )

                    // 结束后自动回放,回放完显示「重新录制/重听」
                    autoReplayThenShowActions(file)
                } else {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "录音保存失败"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "录音保存失败: ${e.message}"
                )
            }
        }
    }

    /**
     * 自动回放;播放结束(或启动失败)后弹出「重新录制/重听」操作
     */
    private suspend fun autoReplayThenShowActions(file: File) {
        val started = audioRecorder.playRecording(file)
        val deadline = System.currentTimeMillis() + WAIT_PLAYBACK_TIMEOUT_MS
        while (started &&
            audioRecorder.playbackState.value == AudioRecorder.PlaybackState.PLAYING &&
            System.currentTimeMillis() < deadline
        ) {
            delay(100)
        }
        if (_uiState.value.lastResult?.filePath == file.absolutePath) {
            _uiState.value = _uiState.value.copy(showReplayActions = true)
        }
    }

    /**
     * 「重听」:再放一遍本轮录音,放完继续显示操作
     */
    fun relistenRecording() {
        val path = _uiState.value.lastResult?.filePath ?: return
        val file = File(path)
        if (!file.exists()) {
            _uiState.value = _uiState.value.copy(errorMessage = "录音文件不存在")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(showReplayActions = false)
            autoReplayThenShowActions(file)
        }
    }

    /**
     * 「重新录制」:清回放操作,回到待长按状态
     */
    fun resetForRerecord() {
        audioRecorder.stopPlayback()
        _uiState.value = _uiState.value.copy(showReplayActions = false)
    }

    /**
     * 自动化钩子(为真机回环自测而设):
     * adb shell am start --ei auto_record_ms <毫秒> → 自动开始并在 N ms 后停止,
     * 回环脚本摆脱 UI 坐标依赖。带去重窗口防止 Activity 重建重复触发。
     */
    fun onAutoRecordRequested(autoRecordMs: Int) {
        if (autoRecordMs <= 0) return
        val now = System.currentTimeMillis()
        if (autoRecordMs == lastAutoRecordMs && now - lastAutoRecordAt < AUTO_RECORD_DEDUPE_MS) return
        lastAutoRecordMs = autoRecordMs
        lastAutoRecordAt = now

        viewModelScope.launch {
            // 等权限状态就绪(自动化场景权限通常已授予;否则按启动失败报错)
            val permDeadline = now + 8000
            while (!_uiState.value.permissionGranted && System.currentTimeMillis() < permDeadline) {
                delay(100)
            }
            if (!_uiState.value.permissionGranted) {
                _uiState.value = _uiState.value.copy(errorMessage = "auto_record: 录音权限未授予")
                return@launch
            }
            startRecording()
            val startDeadline = System.currentTimeMillis() + WAIT_START_TIMEOUT_MS
            while (audioRecorder.recordingState.value != AudioRecorder.RecordingState.RECORDING &&
                System.currentTimeMillis() < startDeadline
            ) {
                delay(WAIT_START_POLL_MS)
            }
            if (audioRecorder.recordingState.value != AudioRecorder.RecordingState.RECORDING) return@launch
            delay(autoRecordMs.toLong())
            stopRecording()
        }
    }

    /**
     * 播放录音
     */
    fun playRecording(recording: Recording) {
        viewModelScope.launch {
            try {
                val file = File(recording.filePath)
                if (file.exists()) {
                    audioRecorder.playRecording(file)
                } else {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "录音文件不存在"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "播放失败: ${e.message}"
                )
            }
        }
    }

    /**
     * 停止播放
     */
    fun stopPlayback() {
        audioRecorder.stopPlayback()
    }

    /**
     * 删除录音
     */
    fun deleteRecording(recording: Recording) {
        viewModelScope.launch {
            try {
                // Delete from database
                recordingDao.delete(recording)

                // Delete file
                val file = File(recording.filePath)
                audioRecorder.deleteRecording(file)

                // Reload recordings
                loadRecordings()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "删除失败: ${e.message}"
                )
            }
        }
    }

    /**
     * 加载录音记录
     */
    private fun loadRecordings() {
        viewModelScope.launch {
            try {
                recordingDao.getAllRecordings().collect { recordings ->
                    _uiState.value = _uiState.value.copy(recordings = recordings)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "加载录音记录失败: ${e.message}"
                )
            }
        }
    }

    /**
     * 清除错误消息
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /**
     * 设置权限状态
     */
    fun setPermissionGranted(granted: Boolean) {
        _uiState.value = _uiState.value.copy(
            permissionGranted = granted,
            showPermissionDialog = !granted
        )
    }

    /**
     * 显示权限对话框
     */
    fun showPermissionDialog() {
        _uiState.value = _uiState.value.copy(showPermissionDialog = true)
    }

    /**
     * 隐藏权限对话框
     */
    fun hidePermissionDialog() {
        _uiState.value = _uiState.value.copy(showPermissionDialog = false)
    }

    /**
     * 获取统计信息
     */
    fun getStatistics(): RecordingStatistics {
        val recordings = _uiState.value.recordings
        return RecordingStatistics(
            totalRecordings = recordings.size,
            averageScore = recordings.map { it.score }.average(),
            highestScore = recordings.map { it.score }.maxOrNull() ?: 0.0,
            lowestScore = recordings.map { it.score }.minOrNull() ?: 0.0,
            averageF0 = recordings.map { it.averageF0 }.average(),
            totalDuration = recordings.sumOf { it.duration }
        )
    }

    /**
     * 录音统计信息数据类
     */
    data class RecordingStatistics(
        val totalRecordings: Int,
        val averageScore: Double,
        val highestScore: Double,
        val lowestScore: Double,
        val averageF0: Double,
        val totalDuration: Long
    )

    /**
     * 获取女声化评价
     */
    fun getFeminizationDescription(score: Double): String {
        return ScoringAlgorithm.getFeminizationDescription(score)
    }

    /**
     * 格式化时长
     */
    fun formatDuration(duration: Long): String {
        val seconds = (duration / 1000).toInt()
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format("%02d:%02d", minutes, remainingSeconds)
    }

    /**
     * 格式化F0
     */
    fun formatF0(f0: Double): String {
        return if (f0 > 0) {
            String.format("%.1f Hz", f0)
        } else {
            "-- Hz"
        }
    }

    /**
     * 格式化评分
     */
    fun formatScore(score: Double): String {
        return String.format("%.1f / 40", score)
    }

    override fun onCleared() {
        super.onCleared()
        audioRecorder.release()
    }
}
