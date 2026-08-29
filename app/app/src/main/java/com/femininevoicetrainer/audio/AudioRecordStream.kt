package com.femininevoicetrainer.audio

import be.tarsos.dsp.io.TarsosDSPAudioFormat
import be.tarsos.dsp.io.TarsosDSPAudioInputStream
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * TarsosDSP 音频输入流(内存分发版)
 *
 * 不直读 AudioRecord——录音线程与 dispatcher 线程并发 read 同一 AudioRecord 实例
 * 会踩踏 framework 层 buffer 记账,触发 libaudioclient 断言 abort。
 * 由 AudioRecorder 的唯一录音线程把字节副本 publish 进队列,本类阻塞式供给 dispatcher。
 */
class AudioRecordStream(sampleRate: Int) : TarsosDSPAudioInputStream {

    companion object {
        private const val QUEUE_CAPACITY = 64
        private const val POLL_TIMEOUT_MS = 20L
    }

    private val format = TarsosDSPAudioFormat(
        sampleRate.toFloat(),
        16,     // PCM 16-bit (与 AudioRecorder.AUDIO_FORMAT 一致)
        1,      // 单声道
        true,   // signed
        false   // little-endian (AudioRecord 本机字节序)
    )

    private val queue = LinkedBlockingQueue<ByteArray>(QUEUE_CAPACITY)

    @Volatile
    private var closed = false

    // 以下两个字段仅 dispatcher(消费)线程访问,无并发
    private var pending: ByteArray? = null
    private var pendingOffset = 0

    /**
     * 录音线程调用:发布一份字节副本供 F0 分析。
     * data 是录音循环复用的缓冲,必须拷贝;队列满时丢弃本块(实时 F0 显示容忍缺口,
     * 录音主链路不因此阻塞)。
     */
    fun publish(data: ByteArray, length: Int) {
        if (closed || length <= 0) return
        queue.offer(data.copyOf(length))
    }

    override fun getFormat(): TarsosDSPAudioFormat = format

    /**
     * 阻塞式读满 len 字节;close() 后排空余量再返回 -1(流结束)。
     */
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        var written = 0
        while (written < len) {
            val chunk = pending
            if (chunk != null) {
                val toCopy = minOf(chunk.size - pendingOffset, len - written)
                System.arraycopy(chunk, pendingOffset, b, off + written, toCopy)
                pendingOffset += toCopy
                written += toCopy
                if (pendingOffset >= chunk.size) {
                    pending = null
                    pendingOffset = 0
                }
            } else if (closed) {
                pending = queue.poll()
                if (pending == null) {
                    return if (written > 0) written else -1
                }
            } else {
                try {
                    pending = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    // 消费中断位而不是回设:本线程的取消约定是 close() 标志位,
                    // 无 interrupt 取消路径;回设会让后续 poll 立即再抛导致空转
                }
            }
        }
        return written
    }

    override fun skip(bytesToSkip: Long): Long {
        throw IOException("skip not supported")
    }

    // dispatcher.stop() 会触发此 close():仅结束本流的数据供给并解除 read 阻塞。
    // AudioRecord 仍由 AudioRecorder 唯一持有与释放(stopAnalysis 后仍需读取保存 WAV,
    // AudioRecord 的持有与释放语义不变,且本类已不持有 AudioRecord 引用)
    override fun close() {
        closed = true
    }

    override fun getFrameLength(): Long = -1
}
