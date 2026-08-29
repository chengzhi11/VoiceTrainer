package com.femininevoicetrainer.data

import org.junit.Assert.*
import org.junit.Test
import java.util.Date

/**
 * 录音实体测试
 * Unit tests for the Recording entity
 */
class RecordingTest {

    /**
     * 测试录音实体创建
     */
    @Test
    fun testRecordingCreation() {
        val recording = Recording(
            id = 1,
            filePath = "/path/to/recording.wav",
            duration = 60000, // 1 minute
            date = System.currentTimeMillis(),
            averageF0 = 220.0,
            score = 40.0
        )

        assertEquals("ID should match", 1L, recording.id)
        assertEquals("File path should match", "/path/to/recording.wav", recording.filePath)
        assertEquals("Duration should match", 60000L, recording.duration)
        assertEquals("Average F0 should match", 220.0, recording.averageF0, 0.01)
        assertEquals("Score should match", 40.0, recording.score, 0.01)
    }

    /**
     * 测试格式化日期
     */
    @Test
    fun testGetFormattedDate() {
        val testTime = System.currentTimeMillis()
        val recording = Recording(
            id = 1,
            filePath = "/path/to/recording.wav",
            duration = 60000,
            date = testTime,
            averageF0 = 220.0,
            score = 40.0
        )

        val formattedDate = recording.getFormattedDate()
        assertNotNull("Formatted date should not be null", formattedDate)
        assertTrue("Formatted date should not be empty", formattedDate.isNotEmpty())

        // Check that the format is roughly correct (should contain numbers and colons)
        assertTrue("Date should contain year", formattedDate.matches(Regex(".*\\d{4}.*")))
        assertTrue("Date should contain time separator", formattedDate.contains(":"))
    }

    /**
     * 测试格式化时长
     */
    @Test
    fun testGetFormattedDuration() {
        val testCases = mapOf(
            1000L to "00:01",      // 1 second
            60000L to "01:00",     // 1 minute
            90000L to "01:30",     // 1 minute 30 seconds
            3600000L to "60:00",   // 1 hour
            3661000L to "61:01"    // 1 hour 1 second
        )

        for ((duration, expected) in testCases) {
            val recording = Recording(
                id = 1,
                filePath = "/path/to/recording.wav",
                duration = duration,
                date = System.currentTimeMillis(),
                averageF0 = 220.0,
                score = 40.0
            )

            val formatted = recording.getFormattedDuration()
            assertEquals("Duration $duration should format to $expected", expected, formatted)
        }
    }

    /**
     * 测试格式化F0
     */
    @Test
    fun testGetFormattedF0() {
        val testCases = mapOf(
            100.0 to "100.0 Hz",
            220.5 to "220.5 Hz",
            180.0 to "180.0 Hz",
            0.0 to "0.0 Hz"
        )

        for ((f0, expected) in testCases) {
            val recording = Recording(
                id = 1,
                filePath = "/path/to/recording.wav",
                duration = 60000,
                date = System.currentTimeMillis(),
                averageF0 = f0,
                score = 40.0
            )

            val formatted = recording.getFormattedF0()
            assertEquals("F0 $f0 should format to $expected", expected, formatted)
        }
    }

    /**
     * 测试格式化评分
     */
    @Test
    fun testGetFormattedScore() {
        val testCases = mapOf(
            0.0 to "0.0 / 40",
            20.5 to "20.5 / 40",
            40.0 to "40.0 / 40",
            35.7 to "35.7 / 40"
        )

        for ((score, expected) in testCases) {
            val recording = Recording(
                id = 1,
                filePath = "/path/to/recording.wav",
                duration = 60000,
                date = System.currentTimeMillis(),
                averageF0 = 220.0,
                score = score
            )

            val formatted = recording.getFormattedScore()
            assertEquals("Score $score should format to $expected", expected, formatted)
        }
    }

    /**
     * 测试女声化评价
     */
    @Test
    fun testGetFeminizationDescription() {
        val testCases = mapOf(
            40.0 to "高度女声化",
            35.0 to "中高女声化",
            20.0 to "中等女声化",
            10.0 to "低度女声化",
            5.0 to "极低女声化"
        )

        for ((score, expected) in testCases) {
            val recording = Recording(
                id = 1,
                filePath = "/path/to/recording.wav",
                duration = 60000,
                date = System.currentTimeMillis(),
                averageF0 = 220.0,
                score = score
            )

            val description = recording.getFeminizationDescription()
            assertEquals("Score $score should have description $expected", expected, description)
        }
    }

    /**
     * 测试边界评分
     */
    @Test
    fun testBoundaryScores() {
        // Test maximum score
        val maxRecording = Recording(
            id = 1,
            filePath = "/path/to/recording.wav",
            duration = 60000,
            date = System.currentTimeMillis(),
            averageF0 = 300.0,
            score = 40.0
        )
        assertEquals("Max score should be 高度女声化", "高度女声化", maxRecording.getFeminizationDescription())

        // Test minimum score
        val minRecording = Recording(
            id = 2,
            filePath = "/path/to/recording.wav",
            duration = 60000,
            date = System.currentTimeMillis(),
            averageF0 = 50.0,
            score = 0.0
        )
        assertEquals("Min score should be 极低女声化", "极低女声化", minRecording.getFeminizationDescription())
    }

    /**
     * 测试默认值
     */
    @Test
    fun testDefaultValues() {
        val recording = Recording(
            id = 0, // Default ID
            filePath = "", // Empty path
            duration = 0L, // No duration
            date = System.currentTimeMillis(),
            averageF0 = 0.0, // No F0
            score = 0.0 // No score
        )

        assertEquals("Default ID should be 0", 0L, recording.id)
        assertEquals("Default file path should be empty", "", recording.filePath)
        assertEquals("Default duration should be 0", 0L, recording.duration)
        assertEquals("Default F0 should be 0.0", 0.0, recording.averageF0, 0.01)
        assertEquals("Default score should be 0.0", 0.0, recording.score, 0.01)
    }

    /**
     * 测试负值处理
     */
    @Test
    fun testNegativeValues() {
        val recording = Recording(
            id = -1,
            filePath = "/path/to/recording.wav",
            duration = -1000L,
            date = System.currentTimeMillis(),
            averageF0 = -10.0,
            score = -5.0
        )

        // The class should handle negative values gracefully
        assertEquals("Negative ID should be stored", -1L, recording.id)
        assertEquals("Negative duration should be stored", -1000L, recording.duration)
        assertEquals("Negative F0 should be stored", -10.0, recording.averageF0, 0.01)
        assertEquals("Negative score should be stored", -5.0, recording.score, 0.01)

        // Formatted outputs should still work
        assertNotNull("Duration formatting should handle negative", recording.getFormattedDuration())
        assertNotNull("F0 formatting should handle negative", recording.getFormattedF0())
        assertNotNull("Score formatting should handle negative", recording.getFormattedScore())
    }

    /**
     * 测试时长格式化的边界情况
     */
    @Test
    fun testDurationFormattingEdgeCases() {
        // Zero duration
        val zeroDuration = Recording(
            id = 1,
            filePath = "/path/to/recording.wav",
            duration = 0L,
            date = System.currentTimeMillis(),
            averageF0 = 220.0,
            score = 40.0
        )
        assertEquals("Zero duration should format to 00:00", "00:00", zeroDuration.getFormattedDuration())

        // Very long duration (24 hours)
        val longDuration = Recording(
            id = 2,
            filePath = "/path/to/recording.wav",
            duration = 86400000L, // 24 hours
            date = System.currentTimeMillis(),
            averageF0 = 220.0,
            score = 40.0
        )
        val formatted = longDuration.getFormattedDuration()
        assertTrue("Long duration should contain valid time format", formatted.matches(Regex("\\d+:\\d{2}")))
    }

    /**
     * 测试F0格式化精度
     */
    @Test
    fun testF0FormattingPrecision() {
        val precisionTest = Recording(
            id = 1,
            filePath = "/path/to/recording.wav",
            duration = 60000,
            date = System.currentTimeMillis(),
            averageF0 = 187.654321, // High precision value
            score = 40.0
        )

        val formatted = precisionTest.getFormattedF0()
        assertTrue("F0 should be formatted with 1 decimal place",
            formatted.matches(Regex("\\d+\\.\\d Hz")))
        assertEquals("F0 should be rounded to 1 decimal", "187.7 Hz", formatted)
    }

    /**
     * 测试评分格式化精度
     */
    @Test
    fun testScoreFormattingPrecision() {
        val precisionTest = Recording(
            id = 1,
            filePath = "/path/to/recording.wav",
            duration = 60000,
            date = System.currentTimeMillis(),
            averageF0 = 220.0,
            score = 37.87654321 // High precision value
        )

        val formatted = precisionTest.getFormattedScore()
        assertTrue("Score should be formatted with 1 decimal place",
            formatted.matches(Regex("\\d+\\.\\d / \\d{2}")))
        assertEquals("Score should be rounded to 1 decimal", "37.9 / 40", formatted)
    }

    /**
     * 测试日期格式的有效性
     */
    @Test
    fun testDateFormatValidity() {
        val recording = Recording(
            id = 1,
            filePath = "/path/to/recording.wav",
            duration = 60000,
            date = 1693000000000L, // Fixed timestamp
            averageF0 = 220.0,
            score = 40.0
        )

        val formatted = recording.getFormattedDate()

        // Check format: YYYY-MM-DD HH:MM:SS
        val datePattern = Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")
        assertTrue("Date should match format YYYY-MM-DD HH:MM:SS",
            datePattern.matches(formatted))
    }

    /**
     * 测试不同的女声化评分描述
     */
    @Test
    fun testDifferentFeminizationDescriptions() {
        val descriptions = listOf(
            "高度女声化",
            "中高女声化",
            "中等女声化",
            "低度女声化",
            "极低女声化"
        )

        val scores = listOf(40.0, 32.0, 25.0, 15.0, 5.0)

        for ((i, score) in scores.withIndex()) {
            val recording = Recording(
                id = i.toLong(),
                filePath = "/path/to/recording.wav",
                duration = 60000,
                date = System.currentTimeMillis(),
                averageF0 = 220.0,
                score = score
            )

            val description = recording.getFeminizationDescription()
            assertTrue("Description should be one of the valid options",
                descriptions.contains(description))
        }
    }

    /**
     * 测试录音实体的完整性
     */
    @Test
    fun testRecordingCompleteness() {
        val recording = Recording(
            id = 123,
            filePath = "/storage/emulated/0/recordings/test_123456.wav",
            duration = 120000, // 2 minutes
            date = 1693000000000L,
            averageF0 = 187.5,
            score = 32.5
        )

        // Test all getter methods
        assertNotNull("ID should be accessible", recording.id)
        assertNotNull("File path should be accessible", recording.filePath)
        assertNotNull("Duration should be accessible", recording.duration)
        assertNotNull("Date should be accessible", recording.date)
        assertNotNull("Average F0 should be accessible", recording.averageF0)
        assertNotNull("Score should be accessible", recording.score)

        // Test all formatting methods
        assertNotNull("Formatted date should not be null", recording.getFormattedDate())
        assertNotNull("Formatted duration should not be null", recording.getFormattedDuration())
        assertNotNull("Formatted F0 should not be null", recording.getFormattedF0())
        assertNotNull("Formatted score should not be null", recording.getFormattedScore())
        assertNotNull("Feminization description should not be null", recording.getFeminizationDescription())
    }

    // ---- 回归:新旧行混存三分支格式化 ----

    /** 分支一 v1 旧行:voiceType/voiceCondition 均 null → 0-40 口径,非数据不足 */
    @Test
    fun testRowBranches_V1LegacyRow() {
        val recording = Recording(
            id = 1,
            filePath = "/path/legacy.wav",
            duration = 60000,
            averageF0 = 220.0,
            score = 32.5
        )
        assertFalse("v1 行不应有声线画像", recording.hasVoiceProfile())
        assertFalse("v1 行不应判为数据不足", recording.isDataInsufficient())
        assertEquals("v1 行保持 0-40 口径", "32.5 / 40", recording.getFormattedScore())
    }

    /** 分支二 v2 已分析行:voiceType 有值 → 0-100 口径 + 声线 chip */
    @Test
    fun testRowBranches_V2AnalyzedRow() {
        val recording = Recording(
            id = 2,
            filePath = "/path/analyzed.wav",
            duration = 60000,
            averageF0 = 210.0,
            score = 72.3,
            voiceType = "普通女声",
            voiceCondition = "自然女声"
        )
        assertTrue("v2 已分析行应有声线画像", recording.hasVoiceProfile())
        assertFalse("已分析行不应判为数据不足", recording.isDataInsufficient())
        assertEquals("v2 已分析行为 0-100 口径", "72.3 / 100", recording.getFormattedTotalScore())
    }

    /** 分支三 v2 数据不足行:voiceType=null 但 voiceCondition 有值 → 空态,不得按新旧任一口径格式化 */
    @Test
    fun testRowBranches_V2DataInsufficientRow() {
        val recording = Recording(
            id = 3,
            filePath = "/path/insufficient.wav",
            duration = 3000,
            averageF0 = 0.0,
            score = 0.0,
            voiceType = null,
            voiceCondition = "数据不足(有效语音过短)"
        )
        assertFalse("数据不足行不得当已分析行(禁 x/100)", recording.hasVoiceProfile())
        assertTrue("数据不足行应命中空态分支", recording.isDataInsufficient())
    }
}
