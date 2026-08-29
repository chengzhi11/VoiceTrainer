package com.femininevoicetrainer.audio

import com.femininevoicetrainer.audio.VoiceEvaluator.VoiceCondition
import com.femininevoicetrainer.audio.VoiceEvaluator.VoiceType
import com.femininevoicetrainer.audio.VoiceFeatureExtractor.VoiceFeatures
import org.junit.Assert.*
import org.junit.Test

/**
 * 五维评分 + 太监音判别 + 声线规则树测试(按判别与评分规格逐条覆盖)
 */
class VoiceEvaluatorTest {

    /** 构造可用特征(全部达标:高 HNR/低 jitter/自然 CV) */
    private fun usableFeatures(
        f0P50: Double,
        resonanceSpacing: Double,
        hnrDb: Double = 20.0,
        f0Cv: Double = 0.06,
        jitterRap: Double = 0.008
    ): VoiceFeatures = VoiceFeatures(
        frameCount = 100, voicedFrameCount = 90, voicedRatio = 0.9, isUsable = true,
        f0P10 = f0P50 * 0.9, f0P50 = f0P50, f0P90 = f0P50 * 1.1, f0Cv = f0Cv,
        f1 = resonanceSpacing * 3 * 0.25, f2 = 1500.0, f3 = resonanceSpacing * 3 * 0.25 + resonanceSpacing * 3,
        resonanceSpacing = resonanceSpacing,
        hnrDb = hnrDb, tiltDbOct = -3.0,
        jitterRap = jitterRap, shimmerDb = 0.3, rmsMean = 0.1
    )

    // ---- 权重与归一化 ----

    @Test
    fun testWeightsSumTo100() {
        val sum = VoiceTypeThresholds.WEIGHT_PITCH + VoiceTypeThresholds.WEIGHT_RESONANCE +
            VoiceTypeThresholds.WEIGHT_STABILITY + VoiceTypeThresholds.WEIGHT_QUALITY +
            VoiceTypeThresholds.WEIGHT_SMOOTHNESS
        assertEquals("五维权重应为 100(35/35/10/12/8)", 100.0, sum, 1e-9)
    }

    @Test
    fun testNormClamping() {
        // F0 归一化锚点 120/220
        assertEquals(0.0, VoiceEvaluator.evaluate(usableFeatures(120.0, 2075.0)).f0Norm, 1e-9)
        assertEquals(1.0, VoiceEvaluator.evaluate(usableFeatures(220.0, 2075.0)).f0Norm, 1e-9)
        assertEquals("F0 above anchor clamps to 1",
            1.0, VoiceEvaluator.evaluate(usableFeatures(300.0, 2075.0)).f0Norm, 1e-9)
        assertEquals("F0 below anchor clamps to 0",
            0.0, VoiceEvaluator.evaluate(usableFeatures(80.0, 2075.0)).f0Norm, 1e-9)

        // 共鸣归一化锚点 1750/2400
        assertEquals(0.0, VoiceEvaluator.evaluate(usableFeatures(220.0, 1750.0)).resNorm, 1e-9)
        assertEquals(1.0, VoiceEvaluator.evaluate(usableFeatures(220.0, 2400.0)).resNorm, 1e-9)
        assertEquals("Spacing below anchor clamps to 0",
            0.0, VoiceEvaluator.evaluate(usableFeatures(220.0, 1500.0)).resNorm, 1e-9)
    }

    @Test
    fun testMismatchEqualsF0NormMinusResNorm() {
        val evaluation = VoiceEvaluator.evaluate(usableFeatures(180.0, 1900.0))
        val expectedF0Norm = (180.0 - 120.0) / 100.0
        val expectedResNorm = (1900.0 - 1750.0) / 650.0
        assertEquals(expectedF0Norm - expectedResNorm, evaluation.mismatch, 1e-9)
    }

    // ---- 五维评分结构 ----

    @Test
    fun testSubScoresWithinWeightBounds() {
        val evaluation = VoiceEvaluator.evaluate(usableFeatures(200.0, 2100.0))
        assertTrue(evaluation.subScores.pitch in 0.0..35.0)
        assertTrue(evaluation.subScores.resonance in 0.0..35.0)
        assertTrue(evaluation.subScores.stability in 0.0..10.0)
        assertTrue(evaluation.subScores.quality in 0.0..12.0)
        assertTrue(evaluation.subScores.smoothness in 0.0..8.0)
        assertEquals(evaluation.subScores.total, evaluation.totalScore, 1e-9)
        assertTrue(evaluation.totalScore in 0.0..100.0)
    }

    @Test
    fun testOnlyPushingF0CannotReachHighScore() {
        // 结构性防刷分:F0 满格但共鸣全无 → 总分被共鸣 35 分压住,拿不到高分
        val eval = VoiceEvaluator.evaluate(usableFeatures(260.0, 1750.0, hnrDb = 24.0))
        assertTrue("Pure-F0 push must not exceed 70 (got ${eval.totalScore})", eval.totalScore < 70.0)
    }

    // ---- 四态判别(分档阈值见 VoiceTypeThresholds) ----

    @Test
    fun testCondition_NaturalFemale() {
        // f0Norm 0.9 / resNorm 0.885 / mismatch 0.015 → 自然女声
        val evaluation = VoiceEvaluator.evaluate(usableFeatures(210.0, 2325.0))
        assertEquals(VoiceCondition.NATURAL_FEMALE, evaluation.condition)
        assertTrue(evaluation.totalScore >= 70.0)
    }

    @Test
    fun testCondition_EunuchRisk() {
        // F0 满格 + 共鸣男声区 → mismatch ≈ 0.92 → 太监音风险
        val evaluation = VoiceEvaluator.evaluate(usableFeatures(220.0, 1770.0))
        assertEquals(VoiceCondition.EUNUCH_RISK, evaluation.condition)
        assertTrue(evaluation.mismatch > 0.35)
    }

    @Test
    fun testCondition_MaleRegion() {
        val evaluation = VoiceEvaluator.evaluate(usableFeatures(110.0, 1800.0))
        assertEquals(VoiceCondition.MALE_REGION, evaluation.condition)
    }

    @Test
    fun testCondition_Transition() {
        // f0Norm 0.4,共鸣中段,总分 <70 → 过渡区
        val evaluation = VoiceEvaluator.evaluate(usableFeatures(160.0, 2010.0))
        assertEquals(VoiceCondition.TRANSITION, evaluation.condition)
    }

    @Test
    fun testCondition_DataInsufficient() {
        val features = VoiceFeatures.empty()
        val evaluation = VoiceEvaluator.evaluate(features)
        assertEquals(VoiceCondition.DATA_INSUFFICIENT, evaluation.condition)
        assertEquals(0.0, evaluation.totalScore, 0.0)
    }

    @Test
    fun testCondition_DataInsufficient_VoiceTypeNull() {
        // 回归:数据不足不得硬编码声线(旧行为误标「普通男声」),必须置 null
        val evaluation = VoiceEvaluator.evaluate(VoiceFeatures.empty())
        assertNull("数据不足时 voiceType 必须为 null", evaluation.voiceType)
        assertEquals(VoiceCondition.DATA_INSUFFICIENT, evaluation.condition)
        assertEquals(0.0, evaluation.totalScore, 0.0)
        assertEquals(0.0, evaluation.mismatch, 0.0)
        // 可用录音仍产出声线标签(短路分支不影响正常路径)
        assertNotNull(VoiceEvaluator.evaluate(usableFeatures(200.0, 2100.0)).voiceType)
    }

    // ---- 声线规则树(阈值与优先级见 VoiceTypeThresholds) ----

    private fun classify(
        f0P50: Double,
        resNorm: Double,
        hnrDb: Double = 15.0
    ): VoiceType = VoiceEvaluator.classifyVoiceType(
        usableFeatures(f0P50, 1750.0 + resNorm * 650.0, hnrDb = hnrDb),
        resNorm
    )

    @Test
    fun testRuleTree_VocalFry() {
        assertEquals(VoiceType.VOCAL_FRY, classify(55.0, 0.2))
        assertEquals(VoiceType.VOCAL_FRY, classify(50.0, 0.0))
    }

    @Test
    fun testRuleTree_Male() {
        assertEquals(VoiceType.MALE, classify(120.0, 0.2))
        assertEquals(VoiceType.MALE, classify(85.0, 0.3))
        assertEquals("70-85Hz gap belongs to male low region", VoiceType.MALE, classify(75.0, 0.2))
    }

    @Test
    fun testRuleTree_MaleYujieOverlapArbitration() {
        // [140,155] 重叠区:共鸣/HNR 达标 → 御姐,否则男声
        assertEquals(VoiceType.MALE, classify(145.0, 0.2, hnrDb = 5.0))
        assertEquals(VoiceType.YUJIE, classify(145.0, 0.5))
        assertEquals("HNR arbitration alone can flip to yujie", VoiceType.YUJIE, classify(145.0, 0.2, hnrDb = 15.0))
    }

    @Test
    fun testRuleTree_Yujie() {
        assertEquals("155-165 neutral gap belongs to yujie", VoiceType.YUJIE, classify(160.0, 0.3))
        assertEquals(VoiceType.YUJIE, classify(175.0, 0.4))
    }

    @Test
    fun testRuleTree_Female() {
        assertEquals(VoiceType.FEMALE, classify(200.0, 0.7))
        assertEquals("165-185 overlap: mid-low resonance stays yujie, mid-high goes female",
            VoiceType.FEMALE, classify(175.0, 0.7))
    }

    @Test
    fun testRuleTree_Loli() {
        // 萝莉 vs 女声高音区靠共鸣高区区分(高共鸣阈值见 VoiceTypeThresholds)
        assertEquals(VoiceType.LOLI, classify(300.0, 0.7))
        assertEquals(VoiceType.LOLI, classify(380.0, 0.8))
        assertEquals("High pitch without high resonance stays female",
            VoiceType.FEMALE, classify(300.0, 0.4))
    }

    // ---- 完整评估与声线一致性 ----

    @Test
    fun testEvaluate_ReturnsVoiceTypeConsistently() {
        val features = usableFeatures(230.0, 2200.0)
        val evaluation = VoiceEvaluator.evaluate(features)
        assertEquals(VoiceEvaluator.classifyVoiceType(features, evaluation.resNorm), evaluation.voiceType)
    }
}
