package com.femininevoicetrainer.audio

import org.junit.Assert.*
import org.junit.Test

/**
 * 打分算法测试
 * Unit tests for the voice feminization scoring algorithm
 */
class ScoringAlgorithmTest {

    /**
     * 测试边界值 - F0 >= 205Hz 应得40分
     */
    @Test
    fun testCalculateFemininityScore_HighF0_ReturnsMaxScore() {
        val f0Values = listOf(205.0, 220.0, 250.0, 255.0, 300.0)

        for (f0 in f0Values) {
            val score = ScoringAlgorithm.calculateFemininityScore(f0)
            assertEquals("F0 = $f0 Hz should return max score (40.0)", 40.0, score, 0.01)
        }
    }

    /**
     * 测试边界值 - F0 = 180Hz 应得35分
     */
    @Test
    fun testCalculateFemininityScore_Boundary180_Returns35() {
        val score = ScoringAlgorithm.calculateFemininityScore(180.0)
        assertEquals("F0 = 180 Hz should return 35.0", 35.0, score, 0.01)
    }

    /**
     * 测试边界值 - F0 = 150Hz 应得20分
     */
    @Test
    fun testCalculateFemininityScore_Boundary150_Returns20() {
        val score = ScoringAlgorithm.calculateFemininityScore(150.0)
        assertEquals("F0 = 150 Hz should return 20.0", 20.0, score, 0.01)
    }

    /**
     * 测试边界值 - F0 = 100Hz 应得0分
     */
    @Test
    fun testCalculateFemininityScore_Boundary100_Returns0() {
        val score = ScoringAlgorithm.calculateFemininityScore(100.0)
        assertEquals("F0 = 100 Hz should return 0.0", 0.0, score, 0.01)
    }

    /**
     * 测试低F0 - F0 < 100Hz 应得0分
     */
    @Test
    fun testCalculateFemininityScore_LowF0_Returns0() {
        val f0Values = listOf(0.0, 50.0, 80.0, 99.0)

        for (f0 in f0Values) {
            val score = ScoringAlgorithm.calculateFemininityScore(f0)
            assertEquals("F0 = $f0 Hz should return 0.0", 0.0, score, 0.01)
        }
    }

    /**
     * 测试线性插值 - 180-205Hz范围
     */
    @Test
    fun testCalculateFemininityScore_LinearInterpolation_180To205() {
        // Test midpoint
        val midScore = ScoringAlgorithm.calculateFemininityScore(192.5) // (180+205)/2
        assertEquals("F0 = 192.5 Hz should return 37.5 (midpoint)", 37.5, midScore, 0.1)

        // Test 3/4 point
        val threeQuarterScore = ScoringAlgorithm.calculateFemininityScore(198.75)
        assertEquals("F0 = 198.75 Hz should return 38.75", 38.75, threeQuarterScore, 0.1)
    }

    /**
     * 测试线性插值 - 150-180Hz范围
     */
    @Test
    fun testCalculateFemininityScore_LinearInterpolation_150To180() {
        // Test midpoint
        val midScore = ScoringAlgorithm.calculateFemininityScore(165.0) // (150+180)/2
        assertEquals("F0 = 165 Hz should return 27.5 (midpoint)", 27.5, midScore, 0.1)

        // Test 3/4 point
        val threeQuarterScore = ScoringAlgorithm.calculateFemininityScore(172.5)
        assertEquals("F0 = 172.5 Hz should return 31.25", 31.25, threeQuarterScore, 0.1)
    }

    /**
     * 测试线性插值 - 100-150Hz范围
     */
    @Test
    fun testCalculateFemininityScore_LinearInterpolation_100To150() {
        // Test midpoint
        val midScore = ScoringAlgorithm.calculateFemininityScore(125.0) // (100+150)/2
        assertEquals("F0 = 125 Hz should return 10.0 (midpoint)", 10.0, midScore, 0.1)

        // Test 3/4 point
        val threeQuarterScore = ScoringAlgorithm.calculateFemininityScore(137.5)
        assertEquals("F0 = 137.5 Hz should return 15.0", 15.0, threeQuarterScore, 0.1)
    }

    /**
     * 测试百分比转换
     */
    @Test
    fun testCalculateFemininityPercentage() {
        val percentage1 = ScoringAlgorithm.calculateFemininityPercentage(205.0)
        assertEquals("Max score should return 100%", 100.0, percentage1, 0.1)

        val percentage2 = ScoringAlgorithm.calculateFemininityPercentage(180.0)
        assertEquals("35/40 should return 87.5%", 87.5, percentage2, 0.1)

        val percentage3 = ScoringAlgorithm.calculateFemininityPercentage(100.0)
        assertEquals("0/40 should return 0%", 0.0, percentage3, 0.1)
    }

    /**
     * 测试女声化评价描述
     */
    @Test
    fun testGetFeminizationDescription() {
        assertEquals("Score >= 38 should be 高度女声化", "高度女声化",
            ScoringAlgorithm.getFeminizationDescription(38.0))
        assertEquals("Score >= 30 should be 中高女声化", "中高女声化",
            ScoringAlgorithm.getFeminizationDescription(30.0))
        assertEquals("Score >= 20 should be 中等女声化", "中等女声化",
            ScoringAlgorithm.getFeminizationDescription(20.0))
        assertEquals("Score >= 10 should be 低度女声化", "低度女声化",
            ScoringAlgorithm.getFeminizationDescription(10.0))
        assertEquals("Score < 10 should be 极低女声化", "极低女声化",
            ScoringAlgorithm.getFeminizationDescription(5.0))
    }

    /**
     * 测试批量计算平均评分
     */
    @Test
    fun testCalculateAverageScore() {
        val f0Values = listOf(220.0, 180.0, 150.0, 120.0)
        val averageScore = ScoringAlgorithm.calculateAverageScore(f0Values)

        val expectedScore = (40.0 + 35.0 + 20.0 + 8.0) / 4.0 // Expected average
        assertEquals("Average score calculation", expectedScore, averageScore, 0.1)
    }

    /**
     * 测试空列表批量计算
     */
    @Test
    fun testCalculateAverageScore_EmptyList_Returns0() {
        val averageScore = ScoringAlgorithm.calculateAverageScore(emptyList())
        assertEquals("Empty list should return 0.0", 0.0, averageScore, 0.01)
    }

    /**
     * 测试无效F0值批量计算
     */
    @Test
    fun testCalculateAverageScore_InvalidF0s_Returns0() {
        val f0Values = listOf(0.0, -1.0, -100.0)
        val averageScore = ScoringAlgorithm.calculateAverageScore(f0Values)
        assertEquals("Invalid F0 values should return 0.0", 0.0, averageScore, 0.01)
    }

    /**
     * 测试判断是否在女声范围
     */
    @Test
    fun testIsInFeminineRange() {
        assertTrue("F0 >= 150 should be in feminine range",
            ScoringAlgorithm.isInFeminineRange(150.0))
        assertTrue("F0 >= 150 should be in feminine range",
            ScoringAlgorithm.isInFeminineRange(220.0))
        assertFalse("F0 < 150 should not be in feminine range",
            ScoringAlgorithm.isInFeminineRange(149.0))
        assertFalse("F0 < 150 should not be in feminine range",
            ScoringAlgorithm.isInFeminineRange(100.0))
    }

    /**
     * 测试典型F0值
     */
    @Test
    fun testGetTypicalF0Values() {
        val typicalValues = ScoringAlgorithm.getTypicalF0Values()

        assertNotNull("Typical values should not be null", typicalValues)
        assertTrue("Should contain typical male voice", typicalValues.containsKey("典型男声"))
        assertTrue("Should contain typical female voice", typicalValues.containsKey("典型女声"))
        assertEquals("Typical male voice should be ~120 Hz", 120.0, typicalValues["典型男声"]!!, 1.0)
        assertEquals("Typical female voice should be ~220 Hz", 220.0, typicalValues["典型女声"]!!, 1.0)
    }

    /**
     * 测试F0分布分析
     */
    @Test
    fun testAnalyzeF0Distribution() {
        val f0Values = listOf(220.0, 180.0, 200.0, 190.0, 210.0)
        val result = ScoringAlgorithm.analyzeF0Distribution(f0Values)

        assertNotNull("Analysis result should not be null", result)
        assertTrue("Average F0 should be positive", result.averageF0 > 0)
        assertTrue("Max F0 should be >= min F0", result.maxF0 >= result.minF0)
        assertTrue("Score should be positive", result.score > 0)
        assertNotNull("Distribution should not be null", result.distribution)
    }

    /**
     * 测试F0分布分析 - 空列表
     */
    @Test
    fun testAnalyzeF0Distribution_EmptyList() {
        val result = ScoringAlgorithm.analyzeF0Distribution(emptyList())

        assertNotNull("Analysis result should not be null", result)
        assertEquals("Average F0 should be 0.0", 0.0, result.averageF0, 0.01)
        assertEquals("Max F0 should be 0.0", 0.0, result.maxF0, 0.01)
        assertEquals("Min F0 should be 0.0", 0.0, result.minF0, 0.01)
        assertEquals("Score should be 0.0", 0.0, result.score, 0.01)
        assertTrue("Distribution should be empty", result.distribution.isEmpty())
    }

    /**
     * 测试F0分布分析 - 全部无效值
     */
    @Test
    fun testAnalyzeF0Distribution_AllInvalid() {
        val f0Values = listOf(0.0, -1.0, -100.0)
        val result = ScoringAlgorithm.analyzeF0Distribution(f0Values)

        assertNotNull("Analysis result should not be null", result)
        assertEquals("Average F0 should be 0.0", 0.0, result.averageF0, 0.01)
        assertTrue("Distribution should be empty", result.distribution.isEmpty())
    }

    /**
     * 测试评分单调递增
     */
    @Test
    fun testScoreMonotonicIncrease() {
        var previousScore = -1.0

        // Test from 80 to 300 Hz
        for (f0 in 80..300 step 10) {
            val score = ScoringAlgorithm.calculateFemininityScore(f0.toDouble())
            assertTrue("Score should be monotonically increasing: F0=$f0, score=$score, previous=$previousScore",
                score >= previousScore)
            previousScore = score
        }
    }

    /**
     * 测试实际女声范围
     */
    @Test
    fun testRealFemaleVoiceRange() {
        // Typical female voice range: 165-255 Hz
        val femaleF0Values = listOf(165.0, 180.0, 200.0, 220.0, 240.0, 255.0)

        for (f0 in femaleF0Values) {
            val score = ScoringAlgorithm.calculateFemininityScore(f0)
            assertTrue("Female voice F0=$f0 should score >= 20, got $score",
                score >= 20.0)
            assertTrue("Female voice F0=$f0 should score <= 40, got $score",
                score <= 40.0)
        }
    }

    /**
     * 测试实际男声范围
     */
    @Test
    fun testRealMaleVoiceRange() {
        // Typical male voice range: 85-180 Hz
        val maleF0Values = listOf(85.0, 100.0, 120.0, 140.0, 160.0, 180.0)

        for (f0 in maleF0Values) {
            val score = ScoringAlgorithm.calculateFemininityScore(f0)
            assertTrue("Male voice F0=$f0 should score >= 0, got $score",
                score >= 0.0)
            assertTrue("Male voice F0=$f0 should score <= 35, got $score",
                score <= 35.0)
        }
    }

    /**
     * 测试评分精度
     */
    @Test
    fun testScorePrecision() {
        val f0 = 192.5
        val score = ScoringAlgorithm.calculateFemininityScore(f0)

        // Score should be within reasonable precision
        assertTrue("Score should have reasonable precision",
            score > 0 && score <= 40)

        // Test that the same input always produces the same output
        val score2 = ScoringAlgorithm.calculateFemininityScore(f0)
        assertEquals("Same F0 should produce same score", score, score2, 0.001)
    }

    /**
     * 测试极端F0值
     */
    @Test
    fun testExtremeF0Values() {
        // Very high F0 (beyond human voice)
        val veryHighScore = ScoringAlgorithm.calculateFemininityScore(1000.0)
        assertEquals("Very high F0 should return max score", 40.0, veryHighScore, 0.01)

        // Negative F0 (invalid)
        val negativeScore = ScoringAlgorithm.calculateFemininityScore(-100.0)
        assertEquals("Negative F0 should return 0", 0.0, negativeScore, 0.01)
    }

    /**
     * 测试女声化等级分布
     */
    @Test
    fun testFeminizationLevelDistribution() {
        val testCases = mapOf(
            40.0 to "高度女声化",
            35.0 to "中高女声化",
            25.0 to "中等女声化",
            15.0 to "低度女声化",
            5.0 to "极低女声化"
        )

        for ((score, expectedDescription) in testCases) {
            val actualDescription = ScoringAlgorithm.getFeminizationDescription(score)
            assertEquals("Score $score should have correct description",
                expectedDescription, actualDescription)
        }
    }
}
