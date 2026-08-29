package com.femininevoicetrainer.audio

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 音高分析器测试
 * Unit tests for the pitch analyzer
 */
class PitchAnalyzerTest {

    private lateinit var pitchAnalyzer: PitchAnalyzer

    @Before
    fun setup() {
        pitchAnalyzer = PitchAnalyzer()
    }

    /**
     * 测试初始状态
     */
    @Test
    fun testInitialState() {
        assertEquals("Initial F0 should be 0.0", 0.0, pitchAnalyzer.getCurrentF0(), 0.01)
        assertEquals("Initial probability should be 0.0", 0.0, pitchAnalyzer.getCurrentProbability(), 0.01)
        assertFalse("Initial state should not be analyzing", pitchAnalyzer.isAnalyzing())
        assertTrue("Initial history should be empty", pitchAnalyzer.getF0History().isEmpty())
    }

    /**
     * 测试清空历史记录
     */
    @Test
    fun testClearHistory() {
        // Add some simulated data (in real test, we'd use mock audio)
        pitchAnalyzer.clearHistory()

        assertEquals("After clear, F0 should be 0.0", 0.0, pitchAnalyzer.getCurrentF0(), 0.01)
        assertEquals("After clear, probability should be 0.0", 0.0, pitchAnalyzer.getCurrentProbability(), 0.01)
        assertTrue("After clear, history should be empty", pitchAnalyzer.getF0History().isEmpty())
    }

    /**
     * 测试F0统计信息 - 空历史
     */
    @Test
    fun testGetF0Statistics_EmptyHistory() {
        val stats = pitchAnalyzer.getF0Statistics()

        assertEquals("Average should be 0.0", 0.0, stats.average, 0.01)
        assertEquals("Max should be 0.0", 0.0, stats.max, 0.01)
        assertEquals("Min should be 0.0", 0.0, stats.min, 0.01)
        assertEquals("Sample count should be 0", 0, stats.sampleCount)
    }

    /**
     * 测试分析状态
     */
    @Test
    fun testAnalyzingState() {
        assertFalse("Initial state should not be analyzing", pitchAnalyzer.isAnalyzing())

        // Note: We can't fully test the actual analysis without mock audio hardware
        // but we can test the state management
        assertFalse("Should not be analyzing after initialization", pitchAnalyzer.isAnalyzing())
    }

    /**
     * 测试当前F0获取
     */
    @Test
    fun testGetCurrentF0() {
        val f0 = pitchAnalyzer.getCurrentF0()
        assertTrue("Current F0 should be non-negative", f0 >= 0.0)
    }

    /**
     * 测试当前概率获取
     */
    @Test
    fun testGetCurrentProbability() {
        val probability = pitchAnalyzer.getCurrentProbability()
        assertTrue("Current probability should be non-negative", probability >= 0.0)
        assertTrue("Current probability should be <= 1.0", probability <= 1.0)
    }

    /**
     * 测试平均F0计算
     */
    @Test
    fun testGetAverageF0_EmptyHistory() {
        val averageF0 = pitchAnalyzer.getAverageF0()
        assertEquals("Average F0 should be 0.0 for empty history", 0.0, averageF0, 0.01)
    }

    /**
     * 测试最大F0获取
     */
    @Test
    fun testGetMaxF0_EmptyHistory() {
        val maxF0 = pitchAnalyzer.getMaxF0()
        assertEquals("Max F0 should be 0.0 for empty history", 0.0, maxF0, 0.01)
    }

    /**
     * 测试最小F0获取
     */
    @Test
    fun testGetMinF0_EmptyHistory() {
        val minF0 = pitchAnalyzer.getMinF0()
        assertEquals("Min F0 should be 0.0 for empty history", 0.0, minF0, 0.01)
    }

    /**
     * 测试F0历史记录
     */
    @Test
    fun testGetF0History() {
        val history = pitchAnalyzer.getF0History()
        assertNotNull("History should not be null", history)
        assertTrue("Initial history should be empty", history.isEmpty())
    }

    /**
     * 测试清空后的状态
     */
    @Test
    fun testStateAfterClear() {
        pitchAnalyzer.clearHistory()

        assertEquals("F0 should be 0.0 after clear", 0.0, pitchAnalyzer.getCurrentF0(), 0.01)
        assertEquals("Probability should be 0.0 after clear", 0.0, pitchAnalyzer.getCurrentProbability(), 0.01)
        assertTrue("History should be empty after clear", pitchAnalyzer.getF0History().isEmpty())
        assertFalse("Should not be analyzing after clear", pitchAnalyzer.isAnalyzing())
    }

    /**
     * 测试统计分析的完整性
     */
    @Test
    fun testF0StatisticsIntegrity() {
        val stats = pitchAnalyzer.getF0Statistics()

        assertNotNull("Statistics should not be null", stats)
        assertTrue("Sample count should be non-negative", stats.sampleCount >= 0)
        assertTrue("Average should be non-negative", stats.average >= 0.0)
        assertTrue("Max should be >= min", stats.max >= stats.min)
    }
}
