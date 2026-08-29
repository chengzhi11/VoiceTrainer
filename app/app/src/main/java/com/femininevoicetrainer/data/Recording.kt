package com.femininevoicetrainer.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 录音实体类
 * Represents a voice recording with metadata
 *
 * v2:新增五维子分/声线标签/mismatch 列支撑进步曲线与判别展示;
 * score 列存新一轮总分(0-100,五维加权),v1 旧行仍为 0-40 口径。
 */
@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val filePath: String,
    val duration: Long, // in milliseconds
    val date: Long = System.currentTimeMillis(),
    val averageF0: Double, // 平均基频 in Hz(F0 中位数 P50)
    val score: Double, // 总评分(新录音 0-100;v1 旧行 0-40)
    @ColumnInfo(defaultValue = "0.0") val pitchScore: Double = 0.0, // 音高 0-35
    @ColumnInfo(defaultValue = "0.0") val resonanceScore: Double = 0.0, // 共鸣 0-35
    @ColumnInfo(defaultValue = "0.0") val stabilityScore: Double = 0.0, // 稳定性 0-10
    @ColumnInfo(defaultValue = "0.0") val qualityScore: Double = 0.0, // 音质 HNR 0-12
    @ColumnInfo(defaultValue = "0.0") val smoothnessScore: Double = 0.0, // 平滑度 0-8
    @ColumnInfo(defaultValue = "0.0") val mismatch: Double = 0.0, // F0-共鸣错位指标
    val voiceType: String? = null, // 声线标签(气泡音/普通男声/普通女声/萝莉音/御姐音)
    val voiceCondition: String? = null // 判别四态(自然女声/太监音风险/男声区/过渡区)
) {
    /**
     * 格式化日期字符串
     */
    fun getFormattedDate(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return formatter.format(Date(date))
    }

    /**
     * 格式化时长字符串
     */
    fun getFormattedDuration(): String {
        val seconds = (duration / 1000).toInt()
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, remainingSeconds)
    }

    /**
     * 格式化F0字符串
     */
    fun getFormattedF0(): String {
        return String.format(Locale.getDefault(), "%.1f Hz", averageF0)
    }

    /**
     * 格式化评分字符串
     */
    fun getFormattedScore(): String {
        return String.format(Locale.getDefault(), "%.1f / 40", score)
    }

    /**
     * v2 行是否有声线画像(五维/判别数据)。
     * 数据不足行(v2 产生但 voiceType=null)不算已分析行,避免误按 x/100 格式化。
     */
    fun hasVoiceProfile(): Boolean {
        return voiceType != null
    }

    /**
     * v2 数据不足行:评估已入库但有效语音过短(voiceCondition 有值而 voiceType=null)。
     * 与 v1 旧行(两列均 null,0-40 口径)区分,UI 展示「数据不足」空态。
     */
    fun isDataInsufficient(): Boolean {
        return voiceType == null && voiceCondition != null
    }

    /**
     * 格式化总分字符串(五维加权制,v2 录音)
     */
    fun getFormattedTotalScore(): String {
        return String.format(Locale.getDefault(), "%.1f / 100", score)
    }

    /**
     * 获取女声化评价
     */
    fun getFeminizationDescription(): String {
        return when {
            score >= 38.0 -> "高度女声化"
            score >= 30.0 -> "中高女声化"
            score >= 20.0 -> "中等女声化"
            score >= 10.0 -> "低度女声化"
            else -> "极低女声化"
        }
    }
}
