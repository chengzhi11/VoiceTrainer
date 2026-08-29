package com.femininevoicetrainer.audio

/**
 * 声线判别与评分的集中阈值配置(判别伪代码、声线规则树与评分公式用到的全部可调参数)
 *
 * 所有区间初值来自西方语言文献典型值,中文人群需真机样本校准;
 * 调参只改本对象,不散落在逻辑代码里。
 */
object VoiceTypeThresholds {

    // ---- F0 归一化(判别 mismatch 用) ----
    /** 0=男声区锚点 (Hz) */
    const val F0_NORM_LOW = 120.0
    /** 1=女声区锚点 (Hz) */
    const val F0_NORM_HIGH = 220.0

    // ---- 共鸣归一化:平均共振峰间距 (F3-F1)/3 的性别参考 (Hz) ----
    /** 男声平均共振峰间距近似(元音平均/schwa 近似) */
    const val RES_MASC = 1750.0
    /** 女声近似值(女声共振峰约比男声高 15-20%) */
    const val RES_FEM = 2400.0

    // ---- 太监音判别四态阈值(分档判别) ----
    /** 自然女声:总分 ≥ 此值 且 mismatch ≤ NATURAL_MISMATCH_MAX */
    const val NATURAL_SCORE_MIN = 0.7
    const val NATURAL_MISMATCH_MAX = 0.2
    /** 太监音风险:F0 归一 ≥ 此值 且 mismatch > 此值 */
    const val EUNUCH_F0_NORM_MIN = 0.6
    const val EUNUCH_MISMATCH_MIN = 0.35
    /** 男声区:F0 归一 < 此值 */
    const val MALE_REGION_F0_NORM_MAX = 0.3

    // ---- 输入有效性(有声帧 ≥15 且占比 ≥60% 方出具判别结论) ----
    /** 有声帧(pitch 且置信度达标)占比低于此值 → 判数据不足,不输出四态 */
    const val MIN_VOICED_RATIO = 0.6
    /** 有声帧绝对数量下限(防止超短录音误判) */
    const val MIN_VOICED_FRAMES = 15

    // ---- 五维评分权重(总 100):音高/共鸣/稳定性/音质/平滑度 ----
    const val WEIGHT_PITCH = 35.0
    const val WEIGHT_RESONANCE = 35.0
    const val WEIGHT_STABILITY = 10.0
    const val WEIGHT_QUALITY = 12.0
    const val WEIGHT_SMOOTHNESS = 8.0

    /** sigmoid 陡度(稳定性/平滑度归一区间到 [0,1] 的软过渡) */
    const val SIGMOID_STEEPNESS = 10.0

    // ---- 稳定性:F0 变异系数软区间(过低僵持与过高失控都扣分) ----
    const val F0_CV_LOW = 0.02
    const val F0_CV_HIGH = 0.20

    // ---- 音质:HNR (dB),≥~20dB 接近满分(sigmoid((hnr-12)/6) 中心 12dB) ----
    const val HNR_CENTER_DB = 12.0
    const val HNR_SLOPE_DB = 6.0

    // ---- 平滑度:jitter(RAP)软区间 ----
    const val JITTER_LOW = 0.005
    const val JITTER_HIGH = 0.02

    // ---- 声线规则树 F0 中位数区间 (Hz) ----
    /** 气泡音 vocal fry:文献主流 20-70(检测下限受 2048 窗约束 ~50) */
    const val FRY_F0_MAX = 70.0
    /** 气泡音上界与男声下界之间的间隙归入男声低区 */
    const val MALE_F0_MIN = 85.0
    const val MALE_F0_MAX = 155.0
    /** 御姐 ~140-185(女声低区/中性区) */
    const val YUJIE_F0_MIN = 140.0
    const val YUJIE_F0_MAX = 185.0
    /** 普通女声 165-255 */
    const val FEMALE_F0_MIN = 165.0
    const val FEMALE_F0_MAX = 255.0
    /** 萝莉 ~250-400 */
    const val LOLI_F0_MIN = 250.0
    const val LOLI_F0_MAX = 400.0

    // ---- 规则树仲裁阈值 ----
    /** [140,155] 男声/御姐重叠区:共鸣 ≥ 此值 或 HNR 达标判御姐 */
    const val YUJIE_ARBITRATION_RES_NORM = 0.45
    const val YUJIE_ARBITRATION_HNR_DB = 12.0
    /** [165,185] 御姐/女声重叠区:共鸣 < 此值判御姐(偏中低),否则女声 */
    const val YUJIE_VS_FEMALE_RES_NORM = 0.55
    /** [250,400+] 萝莉仲裁:共鸣 ≥ 此值(小腔体高区)判萝莉,否则女声高音区 */
    const val LOLI_ARBITRATION_RES_NORM = 0.6
}
