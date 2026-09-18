package com.femininevoicetrainer.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 音频录制器与播放器
 * Audio recorder and player for voice training
 *
 * 功能：
 * - 低延迟音频录制 (<100ms)
 * - 音频文件保存 (WAV格式)
 * - 音频回放
 * - 实时音频流处理
 */
class AudioRecorder(private val context: Context) {

    companion object {
        /**
         * 音频配置参数
         */
        private const val SAMPLE_RATE = 44100  // 采样率 44.1kHz (CD质量)
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO  // 单声道
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT  // 16-bit PCM
        private const val BUFFER_SIZE_MULTIPLIER = 4  // 缓冲区大小倍数

        /**
         * 录音文件存储目录
         */
        private const val RECORDINGS_DIR = "recordings"

        /**
         * WAV文件头大小
         */
        private const val WAV_HEADER_SIZE = 44
        private const val WAV_FILE_EXTENSION = ".wav"
    }

    /**
     * 录音状态
     */
    enum class RecordingState {
        IDLE,       // 空闲
        RECORDING,  // 录音中
        PAUSED,     // 暂停
        STOPPED     // 已停止
    }

    /**
     * 播放状态
     */
    enum class PlaybackState {
        IDLE,       // 空闲
        PLAYING,    // 播放中
        PAUSED,     // 暂停
        STOPPED     // 已停止
    }

    /**
     * 当前录音状态
     */
    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    /**
     * 当前播放状态
     */
    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    /**
     * 录音时长 (毫秒)
     */
    private val _recordingDuration = MutableStateFlow(0L)
    val recordingDuration: StateFlow<Long> = _recordingDuration.asStateFlow()

    /**
     * 音频录制器
     */
    private var audioRecord: AudioRecord? = null

    /**
     * 分发给 PitchAnalyzer 的内存流(录音线程是 AudioRecord 的唯一 reader,
     * 经此流分发字节副本,规避并发读崩溃)
     */
    private var audioRecordStream: AudioRecordStream? = null

    /**
     * 逐帧特征采集器(挂在 PitchAnalyzer 的 dispatcher 上,不动录音链路;
     * stopRecording 后保留供会话级特征读取,下次 startRecording 时重建)
     */
    private var featureCollector: VoiceFeatureCollector? = null

    /**
     * 音频播放器
     */
    private var audioTrack: android.media.AudioTrack? = null

    /**
     * 录音缓冲区大小
     */
    private var bufferSize = 0

    /**
     * 录音文件
     */
    private var recordingFile: File? = null

    /**
     * 录音开始时间
     */
    private var recordingStartTime: Long = 0

    /**
     * 录音数据
     */
    private val audioData = mutableListOf<Byte>()

    /**
     * 音高分析器
     */
    private val pitchAnalyzer = PitchAnalyzer()

    /**
     * 获取音高分析器
     */
    fun getPitchAnalyzer(): PitchAnalyzer {
        return pitchAnalyzer
    }

    /**
     * 获取最近一轮录音的逐帧特征采集器(停止后读取,未开始过录音为 null)
     */
    fun getVoiceFeatureCollector(): VoiceFeatureCollector? {
        return featureCollector
    }

    /**
     * 初始化录音器
     */
    private fun initializeRecorder(): Boolean {
        try {
            // 计算缓冲区大小
            bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                return false
            }

            // 增加缓冲区大小以避免缓冲下溢
            bufferSize *= BUFFER_SIZE_MULTIPLIER

            // 创建AudioRecord
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            // 检查AudioRecord是否初始化成功
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                return false
            }

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * 开始录音
     */
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun startRecording(): File? = withContext(Dispatchers.IO) {
        try {
            // 清理之前的录音
            releaseRecorder()

            // 初始化录音器
            if (!initializeRecorder()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "录音器初始化失败", Toast.LENGTH_SHORT).show()
                }
                return@withContext null
            }

            // 创建录音文件
            val recordingsDir = File(context.filesDir, RECORDINGS_DIR)
            if (!recordingsDir.exists()) {
                recordingsDir.mkdirs()
            }

            val fileName = "recording_${System.currentTimeMillis()}$WAV_FILE_EXTENSION"
            recordingFile = File(recordingsDir, fileName)

            // 开始录音
            audioRecord?.startRecording()
            recordingStartTime = System.currentTimeMillis()
            audioData.clear()

            // 更新状态
            _recordingState.value = RecordingState.RECORDING
            _recordingDuration.value = 0L

            // 启动音高分析:新建内存分发流,dispatcher 从流取数据,不直读 AudioRecord
            val stream = AudioRecordStream(SAMPLE_RATE)
            audioRecordStream = stream
            // 特征采集器挂同一 dispatcher(YIN 处理器之后逐帧执行),不动录音链路
            val collector = VoiceFeatureCollector(SAMPLE_RATE, pitchAnalyzer)
            featureCollector = collector
            pitchAnalyzer.startAnalysis(stream, collector)

            // 启动录音线程
            Thread {
                recordAudio()
            }.start()

            recordingFile
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "录音启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            null
        }
    }

    /**
     * 录音线程
     */
    private fun recordAudio() {
        val buffer = ByteArray(bufferSize)

        while (_recordingState.value == RecordingState.RECORDING) {
            val read = audioRecord?.read(buffer, 0, bufferSize) ?: -1

            if (read > 0) {
                // 保存音频数据
                synchronized(audioData) {
                    for (i in 0 until read) {
                        audioData.add(buffer[i])
                    }
                }

                // 分发字节副本给实时 F0 分析
                audioRecordStream?.publish(buffer, read)

                // 更新录音时长
                _recordingDuration.value = System.currentTimeMillis() - recordingStartTime
            } else {
                // 读取失败或结束
                break
            }
        }
    }

    /**
     * 停止录音
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun stopRecording(): File? {
        try {
            if (_recordingState.value != RecordingState.RECORDING) {
                return null
            }

            // 更新状态
            _recordingState.value = RecordingState.STOPPED

            // 停止音高分析(dispatcher.stop 会触发流 close 解除其 read 阻塞;
            // 此处再显式关流兜底)
            pitchAnalyzer.stopAnalysis()
            audioRecordStream?.close()
            audioRecordStream = null

            // 停止录音
            audioRecord?.stop()

            // 保存WAV文件
            val file = saveWavFile()

            // 更新最终时长
            _recordingDuration.value = System.currentTimeMillis() - recordingStartTime

            return file
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            releaseRecorder()
        }
    }

    /**
     * 保存WAV文件
     */
    private fun saveWavFile(): File? {
        try {
            val file = recordingFile ?: return null
            val fos = FileOutputStream(file)

            synchronized(audioData) {
                // 写入WAV文件头
                writeWavHeader(fos, audioData.size)

                // 写入音频数据
                fos.write(audioData.toByteArray())
            }

            fos.close()
            return file
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * 写入WAV文件头
     */
    private fun writeWavHeader(fos: FileOutputStream, dataSize: Int) {
        val totalSize = dataSize + WAV_HEADER_SIZE - 8
        val byteRate = SAMPLE_RATE * 2  // 16-bit = 2 bytes per sample

        // RIFF header
        fos.write("RIFF".toByteArray())  // ChunkID
        fos.write(intToByteArray(totalSize))  // ChunkSize
        fos.write("WAVE".toByteArray())  // Format

        // fmt subchunk
        fos.write("fmt ".toByteArray())  // Subchunk1ID
        fos.write(intToByteArray(16))  // Subchunk1Size (16 for PCM)
        fos.write(shortToByteArray(1))  // AudioFormat (1 for PCM)
        fos.write(shortToByteArray(1))  // NumChannels (1 for mono)
        fos.write(intToByteArray(SAMPLE_RATE))  // SampleRate
        fos.write(intToByteArray(byteRate))  // ByteRate
        fos.write(shortToByteArray(2))  // BlockAlign (2 bytes per sample)
        fos.write(shortToByteArray(16))  // BitsPerSample

        // data subchunk
        fos.write("data".toByteArray())  // Subchunk2ID
        fos.write(intToByteArray(dataSize))  // Subchunk2Size
    }

    /**
     * 整数转字节数组 (little-endian)
     */
    private fun intToByteArray(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }

    /**
     * 短整数转字节数组 (little-endian)
     */
    private fun shortToByteArray(value: Int): ByteArray {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
    }

    /**
     * 读回录音文件 PCM(saveWavFile 写出的 44 字节头 + 16-bit 小端单声道;
     * 缩放口径与 dispatcher 字节→float 转换一致,int16/32768)。
     * rescue 降噪重评对整段缓存 PCM 离线处理,供 DenoiseRescue 使用。
     */
    fun readRecordingPcm(file: File): FloatArray? {
        return try {
            val bytes = file.readBytes()
            if (bytes.size <= WAV_HEADER_SIZE) return null
            val n = (bytes.size - WAV_HEADER_SIZE) / 2
            val pcm = FloatArray(n)
            val buf = ByteBuffer.wrap(bytes, WAV_HEADER_SIZE, n * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) {
                pcm[i] = buf.short.toInt() / 32768.0f
            }
            pcm
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 播放录音文件
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun playRecording(file: File): Boolean {
        try {
            // 停止当前播放
            stopPlayback()

            // 释放之前的播放器
            releasePlayer()

            // 读取音频文件
            val fis = FileInputStream(file)
            val fileSize = fis.available()

            // 跳过WAV文件头
            fis.skip(WAV_HEADER_SIZE.toLong())

            val audioData = ByteArray(fileSize - WAV_HEADER_SIZE)
            fis.read(audioData)
            fis.close()

            // 创建AudioTrack
            val minBufferSize = android.media.AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_OUT_MONO,
                AUDIO_FORMAT
            )

            audioTrack = android.media.AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize)
                .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                .build()

            // 启动播放
            audioTrack?.play()
            _playbackState.value = PlaybackState.PLAYING

            // 启动播放线程
            Thread {
                audioTrack?.write(audioData, 0, audioData.size)
                audioTrack?.stop()
                _playbackState.value = PlaybackState.STOPPED
            }.start()

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * 停止播放
     */
    fun stopPlayback() {
        try {
            audioTrack?.stop()
            _playbackState.value = PlaybackState.STOPPED
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 暂停播放
     */
    fun pausePlayback() {
        try {
            audioTrack?.pause()
            _playbackState.value = PlaybackState.PAUSED
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 恢复播放
     */
    fun resumePlayback() {
        try {
            audioTrack?.play()
            _playbackState.value = PlaybackState.PLAYING
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 获取音频文件时长
     */
    fun getAudioDuration(file: File): Long {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            return durationStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            e.printStackTrace()
            return 0L
        }
    }

    /**
     * 释放录音器资源
     */
    private fun releaseRecorder() {
        try {
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 释放播放器资源
     */
    private fun releasePlayer() {
        try {
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 释放所有资源
     */
    fun release() {
        stopRecording()
        stopPlayback()
        releaseRecorder()
        releasePlayer()
    }

    /**
     * 删除录音文件
     */
    fun deleteRecording(file: File): Boolean {
        try {
            if (file.exists()) {
                return file.delete()
            }
            return false
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * 获取所有录音文件
     */
    fun getAllRecordings(): List<File> {
        try {
            val recordingsDir = File(context.filesDir, RECORDINGS_DIR)
            if (!recordingsDir.exists()) {
                return emptyList()
            }

            return recordingsDir.listFiles()
                ?.filter { it.extension == "wav" }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
    }

    /**
     * 获取录音目录
     */
    fun getRecordingsDirectory(): File {
        val dir = File(context.filesDir, RECORDINGS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
}
