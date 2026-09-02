package com.femininevoicetrainer.training

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.femininevoicetrainer.R

/**
 * 训练课程静态数据模型(GH#3 / COD-54)。
 *
 * 课程大纲来自上游调研 docs/research-training.md §2(7 主题 × 3–5 步),
 * 每步含要领 / 常见错误 / 自检三段文案;文案全部收在 strings.xml
 * (图形与文字分离,不把中文烧进图);P0 七张图解为自制 VectorDrawable。
 *
 * v1 不接评分内核:自检文案仅引导用户查看现有读数(录音→评分→历史链路零改动)。
 */
data class TrainingStep(
    /** 步骤稳定 id,如 "T1S1" */
    val id: String,
    @StringRes val titleRes: Int,
    /** 要领 */
    @StringRes val focusRes: Int,
    /** 常见错误 */
    @StringRes val mistakesRes: Int,
    /** 自检(结合 App 读数) */
    @StringRes val checkRes: Int,
    /** 图解;null = 首版无图(P1/P2 后续小迭代补充) */
    @DrawableRes val illustrationRes: Int?
)

data class TrainingTopic(
    /** 主题稳定 id,如 "T1" */
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val goalRes: Int,
    val steps: List<TrainingStep>
)

object TrainingCourse {

    val topics: List<TrainingTopic> = listOf(
        topic(
            "T1", R.string.training_t1_title, R.string.training_t1_goal,
            step("T1S1", R.string.training_t1_s1_title, R.string.training_t1_s1_focus,
                R.string.training_t1_s1_mistakes, R.string.training_t1_s1_check,
                R.drawable.ill_t1_s1_red_flags),
            step("T1S2", R.string.training_t1_s2_title, R.string.training_t1_s2_focus,
                R.string.training_t1_s2_mistakes, R.string.training_t1_s2_check, null),
            step("T1S3", R.string.training_t1_s3_title, R.string.training_t1_s3_focus,
                R.string.training_t1_s3_mistakes, R.string.training_t1_s3_check, null)
        ),
        topic(
            "T2", R.string.training_t2_title, R.string.training_t2_goal,
            step("T2S1", R.string.training_t2_s1_title, R.string.training_t2_s1_focus,
                R.string.training_t2_s1_mistakes, R.string.training_t2_s1_check,
                R.drawable.ill_t2_s1_belly_breath),
            step("T2S2", R.string.training_t2_s2_title, R.string.training_t2_s2_focus,
                R.string.training_t2_s2_mistakes, R.string.training_t2_s2_check, null),
            step("T2S3", R.string.training_t2_s3_title, R.string.training_t2_s3_focus,
                R.string.training_t2_s3_mistakes, R.string.training_t2_s3_check, null),
            step("T2S4", R.string.training_t2_s4_title, R.string.training_t2_s4_focus,
                R.string.training_t2_s4_mistakes, R.string.training_t2_s4_check, null)
        ),
        topic(
            "T3", R.string.training_t3_title, R.string.training_t3_goal,
            step("T3S1", R.string.training_t3_s1_title, R.string.training_t3_s1_focus,
                R.string.training_t3_s1_mistakes, R.string.training_t3_s1_check, null),
            step("T3S2", R.string.training_t3_s2_title, R.string.training_t3_s2_focus,
                R.string.training_t3_s2_mistakes, R.string.training_t3_s2_check, null),
            step("T3S3", R.string.training_t3_s3_title, R.string.training_t3_s3_focus,
                R.string.training_t3_s3_mistakes, R.string.training_t3_s3_check, null),
            step("T3S4", R.string.training_t3_s4_title, R.string.training_t3_s4_focus,
                R.string.training_t3_s4_mistakes, R.string.training_t3_s4_check, null)
        ),
        topic(
            "T4", R.string.training_t4_title, R.string.training_t4_goal,
            step("T4S1", R.string.training_t4_s1_title, R.string.training_t4_s1_focus,
                R.string.training_t4_s1_mistakes, R.string.training_t4_s1_check,
                R.drawable.ill_t4_s1_pitch_band),
            step("T4S2", R.string.training_t4_s2_title, R.string.training_t4_s2_focus,
                R.string.training_t4_s2_mistakes, R.string.training_t4_s2_check, null),
            step("T4S3", R.string.training_t4_s3_title, R.string.training_t4_s3_focus,
                R.string.training_t4_s3_mistakes, R.string.training_t4_s3_check,
                R.drawable.ill_t4_s2_hum_lock),
            step("T4S4", R.string.training_t4_s4_title, R.string.training_t4_s4_focus,
                R.string.training_t4_s4_mistakes, R.string.training_t4_s4_check, null),
            step("T4S5", R.string.training_t4_s5_title, R.string.training_t4_s5_focus,
                R.string.training_t4_s5_mistakes, R.string.training_t4_s5_check, null)
        ),
        topic(
            "T5", R.string.training_t5_title, R.string.training_t5_goal,
            step("T5S1", R.string.training_t5_s1_title, R.string.training_t5_s1_focus,
                R.string.training_t5_s1_mistakes, R.string.training_t5_s1_check,
                R.drawable.ill_t5_s1_larynx_space),
            step("T5S2", R.string.training_t5_s2_title, R.string.training_t5_s2_focus,
                R.string.training_t5_s2_mistakes, R.string.training_t5_s2_check,
                R.drawable.ill_t5_s2_forward_focus),
            step("T5S3", R.string.training_t5_s3_title, R.string.training_t5_s3_focus,
                R.string.training_t5_s3_mistakes, R.string.training_t5_s3_check, null),
            step("T5S4", R.string.training_t5_s4_title, R.string.training_t5_s4_focus,
                R.string.training_t5_s4_mistakes, R.string.training_t5_s4_check,
                R.drawable.ill_t5_s4_pitch_resonance)
        ),
        topic(
            "T6", R.string.training_t6_title, R.string.training_t6_goal,
            step("T6S1", R.string.training_t6_s1_title, R.string.training_t6_s1_focus,
                R.string.training_t6_s1_mistakes, R.string.training_t6_s1_check, null),
            step("T6S2", R.string.training_t6_s2_title, R.string.training_t6_s2_focus,
                R.string.training_t6_s2_mistakes, R.string.training_t6_s2_check, null),
            step("T6S3", R.string.training_t6_s3_title, R.string.training_t6_s3_focus,
                R.string.training_t6_s3_mistakes, R.string.training_t6_s3_check, null)
        ),
        topic(
            "T7", R.string.training_t7_title, R.string.training_t7_goal,
            step("T7S1", R.string.training_t7_s1_title, R.string.training_t7_s1_focus,
                R.string.training_t7_s1_mistakes, R.string.training_t7_s1_check, null),
            step("T7S2", R.string.training_t7_s2_title, R.string.training_t7_s2_focus,
                R.string.training_t7_s2_mistakes, R.string.training_t7_s2_check, null),
            step("T7S3", R.string.training_t7_s3_title, R.string.training_t7_s3_focus,
                R.string.training_t7_s3_mistakes, R.string.training_t7_s3_check, null)
        )
    )

    /**
     * 固定朗读自测语料(冻结 2026-09-02,调研遗留项定稿):
     * 3 句均含干净的 /a/ /i/ /u/ 高低元音,正常语速连读约 10 秒,
     * 满足评分有效性门(总时长 ≥3.5s / 有效语音 ≥3.0s,COD-45),
     * 且保证历史曲线纵向可比。
     */
    @StringRes
    val fixedReadingSentences: List<Int> = listOf(
        R.string.training_corpus_1,
        R.string.training_corpus_2,
        R.string.training_corpus_3
    )

    /** P0 七张自制图解(T1-1 / T2-1 / T4-1 / T4-2 / T5-1 / T5-2 / T5-4),供数据完整性测试 */
    @DrawableRes
    val p0IllustrationResIds: Set<Int> = setOf(
        R.drawable.ill_t1_s1_red_flags,
        R.drawable.ill_t2_s1_belly_breath,
        R.drawable.ill_t4_s1_pitch_band,
        R.drawable.ill_t4_s2_hum_lock,
        R.drawable.ill_t5_s1_larynx_space,
        R.drawable.ill_t5_s2_forward_focus,
        R.drawable.ill_t5_s4_pitch_resonance
    )

    /** 图解 → 无障碍描述(contentDescription)映射;每个带图步骤都必须有条目 */
    @StringRes
    val illustrationDescriptions: Map<Int, Int> = mapOf(
        R.drawable.ill_t1_s1_red_flags to R.string.training_illus_t1_s1_desc,
        R.drawable.ill_t2_s1_belly_breath to R.string.training_illus_t2_s1_desc,
        R.drawable.ill_t4_s1_pitch_band to R.string.training_illus_t4_s1_desc,
        R.drawable.ill_t4_s2_hum_lock to R.string.training_illus_t4_s2_desc,
        R.drawable.ill_t5_s1_larynx_space to R.string.training_illus_t5_s1_desc,
        R.drawable.ill_t5_s2_forward_focus to R.string.training_illus_t5_s2_desc,
        R.drawable.ill_t5_s4_pitch_resonance to R.string.training_illus_t5_s4_desc
    )

    private fun topic(
        id: String,
        @StringRes titleRes: Int,
        @StringRes goalRes: Int,
        vararg steps: TrainingStep
    ): TrainingTopic = TrainingTopic(id, titleRes, goalRes, steps.toList())

    private fun step(
        id: String,
        @StringRes titleRes: Int,
        @StringRes focusRes: Int,
        @StringRes mistakesRes: Int,
        @StringRes checkRes: Int,
        @DrawableRes illustrationRes: Int?
    ): TrainingStep = TrainingStep(id, titleRes, focusRes, mistakesRes, checkRes, illustrationRes)
}
