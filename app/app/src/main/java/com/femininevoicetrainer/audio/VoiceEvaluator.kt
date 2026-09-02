package com.femininevoicetrainer.audio

import com.femininevoicetrainer.audio.VoiceFeatureExtractor.VoiceFeatures
import kotlin.math.exp

/**
 * 五维加权评分 + 自然女声(防太监音)判别 + 声线类型规则树
 * (严格按前期信号处理调研定稿的判别伪代码与声线规则表实现)
 *
 * 结构性防刷分设计:总分含共鸣(35 分)与自然度(20 分)后,只推 F0 拿不到高分。
 * 全部阈值在 [VoiceTypeThresholds],便于中文人群后续校准。
 */
object VoiceEvaluator {

    /** 声线类型(共五类) */
    enum class VoiceType(val label: String) {
        VOCAL_FRY("气泡音"),
        MALE("普通男声"),
        FEMALE("普通女声"),
        LOLI("萝莉音"),
        YUJIE("御姐音")
    }

    /** 太监音判别四态分档 + 数据不足保护态 */
    enum class VoiceCondition(val label: String) {
        NATURAL_FEMALE("自然女声"),
        EUNUCH_RISK("太监音风险(F0 高但共鸣未跟上)"),
        MALE_REGION("男声区"),
        TRANSITION("过渡区(女声化进行中)"),
        DATA_INSUFFICIENT("数据不足")
    }

    /** 五维子分(各自权重点制)与总分(0-100) */
    data class SubScores(
        val pitch: Double,       // 0-35
        val resonance: Double,   // 0-35
        val stability: Double,   // 0-10
        val quality: Double,     // 0-12
        val smoothness: Double   // 0-8
    ) {
        val total: Double get() = pitch + resonance + stability + quality + smoothness
    }

    data class VoiceEvaluation(
        val condition: VoiceCondition,
        /** 声线标签;数据不足时为 null(不产出误导性声线) */
        val voiceType: VoiceType?,
        val subScores: SubScores,
        /** 总分 0-100(= 自然女声分 natural_fem_score × 100) */
        val totalScore: Double,
        val f0Norm: Double,
        val resNorm: Double,
        /** 核心错位指标 >0.35 即「F0-共鸣错位」(太监音签名) */
        val mismatch: Double
    )

    /**
     * 完整评估:特征 → 归一化 → mismatch → 五维评分 → 四态判别 → 声线规则树。
     * 数据不足(时长/电平/信噪/有效人声任一门未过)时短路输出 DATA_INSUFFICIENT,
     * 细化原因见 [VoiceFeatureExtractor.VoiceFeatures.failReason](COD-45 失败归因)。
     */
    fun evaluate(features: VoiceFeatures): VoiceEvaluation {
        if (!features.isUsable) {
            return VoiceEvaluation(
                condition = VoiceCondition.DATA_INSUFFICIENT,
                voiceType = null,
                subScores = SubScores(0.0, 0.0, 0.0, 0.0, 0.0),
                totalScore = 0.0,
                f0Norm = 0.0, resNorm = 0.0, mismatch = 0.0
            )
        }

        val f0Norm = clamp(
            (features.f0P50 - VoiceTypeThresholds.F0_NORM_LOW) /
                (VoiceTypeThresholds.F0_NORM_HIGH - VoiceTypeThresholds.F0_NORM_LOW)
        )
        val resNorm = clamp(
            (features.resonanceSpacing - VoiceTypeThresholds.RES_MASC) /
                (VoiceTypeThresholds.RES_FEM - VoiceTypeThresholds.RES_MASC)
        )
        val mismatch = f0Norm - resNorm

        // ---- 五维子分(归一 × 权重) ----
        // 音高:复用既有 40 分制 F0 评分算法作为五维中的「音高」单项,f0P50 代入其分段映射后归一
        val pitchNorm = clamp(ScoringAlgorithm.calculateFemininityScore(features.f0P50) / 40.0)
        val pitch = pitchNorm * VoiceTypeThresholds.WEIGHT_PITCH
        val resonance = resNorm * VoiceTypeThresholds.WEIGHT_RESONANCE
        val stability = (1.0 - sigmoidSteep(
            normalize01(features.f0Cv, VoiceTypeThresholds.F0_CV_LOW, VoiceTypeThresholds.F0_CV_HIGH)
        )) * VoiceTypeThresholds.WEIGHT_STABILITY
        val quality = sigmoid(
            (features.hnrDb - VoiceTypeThresholds.HNR_CENTER_DB) / VoiceTypeThresholds.HNR_SLOPE_DB
        ) * VoiceTypeThresholds.WEIGHT_QUALITY
        val smoothness = (1.0 - sigmoidSteep(
            normalize01(features.jitterRap, VoiceTypeThresholds.JITTER_LOW, VoiceTypeThresholds.JITTER_HIGH)
        )) * VoiceTypeThresholds.WEIGHT_SMOOTHNESS

        val subScores = SubScores(pitch, resonance, stability, quality, smoothness)
        val naturalFemScore = subScores.total / 100.0

        // ---- 四态判别(分档输出,判断顺序即优先级) ----
        val condition = when {
            naturalFemScore >= VoiceTypeThresholds.NATURAL_SCORE_MIN &&
                mismatch <= VoiceTypeThresholds.NATURAL_MISMATCH_MAX -> VoiceCondition.NATURAL_FEMALE
            f0Norm >= VoiceTypeThresholds.EUNUCH_F0_NORM_MIN &&
                mismatch > VoiceTypeThresholds.EUNUCH_MISMATCH_MIN -> VoiceCondition.EUNUCH_RISK
            f0Norm < VoiceTypeThresholds.MALE_REGION_F0_NORM_MAX -> VoiceCondition.MALE_REGION
            else -> VoiceCondition.TRANSITION
        }

        val voiceType = classifyVoiceType(features, resNorm)

        return VoiceEvaluation(
            condition = condition,
            voiceType = voiceType,
            subScores = subScores,
            totalScore = subScores.total,
            f0Norm = f0Norm,
            resNorm = resNorm,
            mismatch = mismatch
        )
    }

    /**
     * 声线规则树:F0 中位数定位大类 → 共鸣/HNR 特征二次仲裁。
     * 区间重叠处([140,155] 男/御、[165,185] 御/女、[250+] 女/萝)按辅助特征仲裁。
     */
    fun classifyVoiceType(features: VoiceFeatures, resNorm: Double): VoiceType {
        val f0 = features.f0P50
        return when {
            f0 <= VoiceTypeThresholds.FRY_F0_MAX -> VoiceType.VOCAL_FRY

            f0 < VoiceTypeThresholds.MALE_F0_MIN ->
                // 70-85Hz 间隙:归男声低区
                VoiceType.MALE

            f0 <= VoiceTypeThresholds.MALE_F0_MAX && f0 < VoiceTypeThresholds.YUJIE_F0_MIN ->
                VoiceType.MALE

            // [140,155] 男声/御姐重叠:HNR+共鸣仲裁(御姐 HNR 正常、共鸣偏中)
            f0 <= VoiceTypeThresholds.MALE_F0_MAX ->
                if (resNorm >= VoiceTypeThresholds.YUJIE_ARBITRATION_RES_NORM ||
                    features.hnrDb >= VoiceTypeThresholds.YUJIE_ARBITRATION_HNR_DB
                ) VoiceType.YUJIE else VoiceType.MALE

            f0 < VoiceTypeThresholds.FEMALE_F0_MIN ->
                // 155-165 中性间隙:归御姐
                VoiceType.YUJIE

            // [165,185] 御姐/女声重叠:共鸣偏中低判御姐
            f0 <= VoiceTypeThresholds.YUJIE_F0_MAX ->
                if (resNorm < VoiceTypeThresholds.YUJIE_VS_FEMALE_RES_NORM) VoiceType.YUJIE
                else VoiceType.FEMALE

            f0 < VoiceTypeThresholds.LOLI_F0_MIN -> VoiceType.FEMALE

            // [250,400+] 萝莉/女声高音区:共鸣高区(小腔体感)仲裁
            else ->
                if (resNorm >= VoiceTypeThresholds.LOLI_ARBITRATION_RES_NORM) VoiceType.LOLI
                else VoiceType.FEMALE
        }
    }

    // ---- 归一化工具 ----

    private fun clamp(v: Double, lo: Double = 0.0, hi: Double = 1.0): Double =
        v.coerceIn(lo, hi)

    /** 线性归一到 [0,1]:v ≤ low → 0,v ≥ high → 1 */
    private fun normalize01(v: Double, low: Double, high: Double): Double =
        clamp((v - low) / (high - low))

    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))

    /** 陡 sigmoid:0.5 处过 0.5,陡度 [VoiceTypeThresholds.SIGMOID_STEEPNESS] */
    private fun sigmoidSteep(n: Double): Double =
        sigmoid(VoiceTypeThresholds.SIGMOID_STEEPNESS * (n - 0.5))
}
