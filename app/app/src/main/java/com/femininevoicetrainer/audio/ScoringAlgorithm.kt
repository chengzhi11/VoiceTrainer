package com.femininevoicetrainer.audio

/**
 * 女声化打分算法实现
 * Scoring algorithm for voice feminization based on fundamental frequency (F0)
 *
 * 算法设计原理：
 * - 基频 (F0) 是女声化的最关键指标
 * - 女声典型基频范围: 165-255 Hz (平均约 220 Hz)
 * - 男声典型基频范围: 85-180 Hz (平均约 120 Hz)
 * - 评分为 0-40 分，基于 F0 分段线性映射
 *
 * 打分公式：
 * - F0 >= 205 Hz: 40 分 (完全女声化)
 * - F0 >= 180 Hz: 35-40 分 (高度女声化，线性插值)
 * - F0 >= 150 Hz: 20-35 分 (中等女声化，线性插值)
 * - F0 >= 100 Hz: 0-20 分 (低度女声化，线性插值)
 * - F0 < 100 Hz: 0 分 (典型男声)
 */
object ScoringAlgorithm {

    /**
     * 计算女声化评分
     * @param f0 基频，单位 Hz
     * @return 评分 0-40 分
     */
    fun calculateFemininityScore(f0: Double): Double {
        return when {
            f0 >= 205.0 -> 40.0
            f0 >= 180.0 -> 35.0 + (f0 - 180.0) / 25.0 * 5.0
            f0 >= 150.0 -> 20.0 + (f0 - 150.0) / 30.0 * 15.0
            f0 >= 100.0 -> (f0 - 100.0) / 50.0 * 20.0
            else -> 0.0
        }
    }

    /**
     * 计算女声化评分的百分比形式
     * @param f0 基频，单位 Hz
     * @return 评分百分比 0-100%
     */
    fun calculateFemininityPercentage(f0: Double): Double {
        return (calculateFemininityScore(f0) / 40.0) * 100.0
    }

    /**
     * 获取女声化评价等级
     * @param score 评分 0-40 分
     * @return 评价描述
     */
    fun getFeminizationDescription(score: Double): String {
        return when {
            score >= 38.0 -> "高度女声化"
            score >= 30.0 -> "中高女声化"
            score >= 20.0 -> "中等女声化"
            score >= 10.0 -> "低度女声化"
            else -> "极低女声化"
        }
    }

    /**
     * 批量计算多个F0值的平均评分
     * @param f0Values 基频值列表
     * @return 平均评分
     */
    fun calculateAverageScore(f0Values: List<Double>): Double {
        if (f0Values.isEmpty()) return 0.0

        val validF0Values = f0Values.filter { it > 0 }
        if (validF0Values.isEmpty()) return 0.0

        val totalScore = validF0Values.sumOf { calculateFemininityScore(it) }
        return totalScore / validF0Values.size
    }

    /**
     * 判断F0是否在女声范围内
     * @param f0 基频，单位 Hz
     * @return true表示在女声范围 (>= 150 Hz)
     */
    fun isInFeminineRange(f0: Double): Boolean {
        return f0 >= 150.0
    }

    /**
     * 获取典型的F0参考值
     * @return 各种声音类型的典型F0值
     */
    fun getTypicalF0Values(): Map<String, Double> {
        return mapOf(
            "典型男声" to 120.0,
            "男声高音" to 180.0,
            "女声低音" to 165.0,
            "典型女声" to 220.0,
            "女声高音" to 255.0,
            "童声" to 300.0
        )
    }

    /**
     * 分析F0分布情况
     * @param f0Values 基频值列表
     * @return 分析结果
     */
    fun analyzeF0Distribution(f0Values: List<Double>): F0AnalysisResult {
        val validF0Values = f0Values.filter { it > 0 }
        if (validF0Values.isEmpty()) {
            return F0AnalysisResult(0.0, 0.0, 0.0, 0.0, emptyMap())
        }

        val averageF0 = validF0Values.average()
        val maxF0 = validF0Values.maxOrNull() ?: 0.0
        val minF0 = validF0Values.minOrNull() ?: 0.0
        val score = calculateFemininityScore(averageF0)

        val distribution = mutableMapOf<String, Int>()
        validF0Values.forEach { f0 ->
            val category = when {
                f0 >= 205.0 -> "高度女声化"
                f0 >= 180.0 -> "中高女声化"
                f0 >= 150.0 -> "中等女声化"
                f0 >= 100.0 -> "低度女声化"
                else -> "极低女声化"
            }
            distribution[category] = distribution.getOrDefault(category, 0) + 1
        }

        return F0AnalysisResult(averageF0, maxF0, minF0, score, distribution)
    }

    /**
     * F0分析结果数据类
     */
    data class F0AnalysisResult(
        val averageF0: Double,
        val maxF0: Double,
        val minF0: Double,
        val score: Double,
        val distribution: Map<String, Int>
    )
}
