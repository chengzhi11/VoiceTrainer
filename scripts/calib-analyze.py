#!/usr/bin/env python3
# calib-analyze.py — 离线复刻 App 评分前置链路的帧级分析(COD-45 定标 / COD-46 门限回归)
#
# 忠实对齐 app 侧参数(勿改动任一侧而不改另一侧):
#   - 帧: 2048 样本 @44100Hz, 无重叠 (PitchAnalyzer BUFFER_SIZE/OVERLAP)
#   - YIN: TarsosDSP Yin(sr, 2048) → yinHalf=1024, 绝对阈值 0.2,
#          probability = 1 - d'(tauEstimate)
#     (阈值 0.2 系反编译 TarsosDSP 2.4 Yin.class 核实:两参构造器 Yin(float,int)
#      字节码 ldc2_w 0.2d 委托三参构造器;此前研究阶段假设的 0.1 导致离线镜像
#      系统性少认边缘有声帧——d'∈[0.1,0.2) 的帧 Java 判 prob 0.8~0.9 有声,
#      python 判 prob=0 无声。snr5 回归臂 App 出分/离线 INSUFF_VOICED 的分歧
#      即源于此,App 侧行为才是真值,App 代码未动)
#   - 有声帧: prob > 0.7 (MIN_PITCH_CONFIDENCE) 且 50 ≤ F0 ≤ 500 (MIN/MAX_F0)
#   - 有效性 (COD-46 修订, 对齐 VoiceTypeThresholds):
#       总时长 ≥ 3500ms ∧ 有声时长 ≥ 3000ms ∧ war ≥ 0.35
#       ∧ speech_level(帧RMS P90) ≥ 0.02 ∧ level_snr(P90/noise_floor) ≥ 3.5
#     war = 有声帧/活跃帧, 活跃帧 = rms > max(2×noise_floor, 0.004);
#     noise_floor = YIN 未检出帧 RMS P50(未检出帧不足 10% 时退全体帧 P10)
#   - 失败归因(互斥先命中): TOO_SHORT → SILENT/TOO_QUIET → TOO_NOISY → INSUFF_VOICED
#
# 用法: calib-analyze.py file1.wav [file2.wav ...] > results.tsv
# 依赖: numpy (临时 venv: /tmp/calib-venv/bin/python)
import sys
import wave
import struct
import numpy as np

SR = 44100
FRAME = 2048          # app 帧长 = YIN bufferSize
YIN_HALF = FRAME // 2 # yinBuffer 长度
YIN_THRESHOLD = 0.2   # TarsosDSP Yin(float,int) 构造器硬编码绝对阈值(见文件头注释)
MIN_PROB = 0.7        # MIN_PITCH_CONFIDENCE(防假分主闸,保持不动)
F0_MIN, F0_MAX = 50.0, 500.0
FRAME_MS = FRAME / SR * 1000.0

# ---- COD-46 有效性门限(VoiceTypeThresholds) ----
MIN_TOTAL_DURATION_MS = 3500.0
MIN_VOICED_DURATION_MS = 3000.0
MIN_ACTIVE_VOICED_RATIO = 0.35
MIN_SPEECH_LEVEL = 0.02
MIN_LEVEL_SNR = 3.5
ACTIVE_FRAME_MIN_RMS = 0.004
ACTIVE_FRAME_NOISE_MULT = 2.0
NOISE_FLOOR_FALLBACK_UNVOICED_FRACTION = 0.1
SILENT_SPEECH_LEVEL = 0.005


def yin_frame(x):
    """单帧 YIN(向量化)。x 长度 ≥ FRAME。"""
    half = YIN_HALF
    w = x[:half]
    # difference function: d[tau] = sum_{j<half} (w[j]-x[tau+j])^2, tau ∈ [0, half)
    # lag matrix 拆半计算避免一次性大矩阵
    d = np.empty(half)
    d[0] = 0.0
    for tau in range(1, half):
        diff = w - x[tau:tau + half]
        d[tau] = np.dot(diff, diff)
    # cumulative mean normalized difference
    dp = np.empty(half)
    dp[0] = 1.0
    run = 0.0
    for tau in range(1, half):
        run += d[tau]
        dp[tau] = d[tau] * tau / run if run > 0 else 1.0
    # absolute threshold: 首个低于阈值的 tau,再走到局部极小
    tau_est = -1
    for tau in range(2, half):
        if dp[tau] < YIN_THRESHOLD:
            while tau + 1 < half and dp[tau + 1] < dp[tau]:
                tau += 1
            tau_est = tau
            break
    if tau_est == -1:
        return 0.0, 0.0
    # parabolic interpolation
    x0 = dp[tau_est - 1] if tau_est >= 1 else dp[tau_est]
    x2 = dp[tau_est + 1] if tau_est + 1 < half else dp[tau_est]
    denom = x0 - 2 * dp[tau_est] + x2
    shift = 0.0 if denom == 0 else (x0 - x2) / (2 * denom)
    better_tau = tau_est + shift
    f0 = SR / better_tau
    prob = max(0.0, min(1.0, 1.0 - dp[tau_est]))
    return f0, prob


def read_wav(path):
    w = wave.open(path, 'rb')
    sr, n = w.getframerate(), w.getnframes()
    raw = w.readframes(n)
    w.close()
    s = np.frombuffer(raw, dtype='<i2').astype(np.float64) / 32768.0
    if sr != SR:
        # 简单线性重采样到 44100(设备录音本就是 44100,仅兜底)
        t = np.arange(len(s)) / sr
        t2 = np.arange(int(len(s) * SR / sr)) / SR
        s = np.interp(t2, t, s)
    return s


def analyze(path):
    s = read_wav(path)
    n_frames = len(s) // FRAME
    voiced_n = 0
    voiced_f0 = []
    voiced_prob = []
    rms_list = []
    voiced_mask = []
    for i in range(n_frames):
        fr = s[i * FRAME:(i + 1) * FRAME]
        rms_list.append(float(np.sqrt(np.mean(fr * fr))))
        f0, prob = yin_frame(fr)
        is_voiced = prob > MIN_PROB and F0_MIN <= f0 <= F0_MAX
        voiced_mask.append(is_voiced)
        if is_voiced:
            voiced_n += 1
            voiced_f0.append(f0)
            voiced_prob.append(prob)
    rms_arr = np.array(rms_list) if n_frames else np.array([0.0])
    voiced_mask_arr = np.array(voiced_mask, dtype=bool) if n_frames else np.array([False])

    # ---- COD-46 会话有效性指标(对齐 VoiceFeatureExtractor.analyze) ----
    total_ms = n_frames * FRAME_MS
    voiced_ms = voiced_n * FRAME_MS
    speech_level = float(np.percentile(rms_arr, 90)) if n_frames else 0.0
    unvoiced_rms = rms_arr[~voiced_mask_arr] if n_frames else rms_arr
    if n_frames and len(unvoiced_rms) >= n_frames * NOISE_FLOOR_FALLBACK_UNVOICED_FRACTION:
        noise_floor = float(np.percentile(unvoiced_rms, 50))
    else:
        noise_floor = float(np.percentile(rms_arr, 10)) if n_frames else 0.0
    active_thr = max(ACTIVE_FRAME_NOISE_MULT * noise_floor, ACTIVE_FRAME_MIN_RMS)
    active_n = int((rms_arr > active_thr).sum()) if n_frames else 0
    war = min(voiced_n / active_n, 1.0) if active_n > 0 else 0.0
    level_snr = (speech_level / noise_floor) if noise_floor > 0 else float('inf')

    usable = (total_ms >= MIN_TOTAL_DURATION_MS and
              voiced_ms >= MIN_VOICED_DURATION_MS and
              war >= MIN_ACTIVE_VOICED_RATIO and
              speech_level >= MIN_SPEECH_LEVEL and
              level_snr >= MIN_LEVEL_SNR)
    if usable:
        fail_reason = '-'
    elif total_ms < MIN_TOTAL_DURATION_MS:
        fail_reason = 'TOO_SHORT'
    elif speech_level < SILENT_SPEECH_LEVEL:
        fail_reason = 'SILENT'
    elif speech_level < MIN_SPEECH_LEVEL:
        fail_reason = 'TOO_QUIET'
    elif level_snr < MIN_LEVEL_SNR:
        fail_reason = 'TOO_NOISY'
    else:
        fail_reason = 'INSUFF_VOICED'

    f0med = float(np.median(voiced_f0)) if voiced_f0 else 0.0
    pmed = float(np.median(voiced_prob)) if voiced_prob else 0.0
    return {
        'file': path.split('/')[-1], 'dur_s': round(len(s) / SR, 2),
        'frames': n_frames,
        'speech_level': round(speech_level, 4), 'noise_floor': round(noise_floor, 5),
        'active_n': active_n, 'war': round(war, 3),
        'level_snr': round(level_snr, 2) if np.isfinite(level_snr) else 'inf',
        'voiced_n': voiced_n, 'voiced_ratio': round(voiced_n / n_frames, 3) if n_frames else 0.0,
        'voiced_ms': int(voiced_ms),
        'f0_med': round(f0med, 1), 'prob_med': round(pmed, 3),
        'usable': 'PASS' if usable else 'FAIL',
        'fail_reason': fail_reason,
    }


KEYS = ['file', 'dur_s', 'frames', 'speech_level', 'noise_floor', 'active_n', 'war',
        'level_snr', 'voiced_n', 'voiced_ratio', 'voiced_ms', 'f0_med', 'prob_med',
        'usable', 'fail_reason']

if __name__ == '__main__':
    print('\t'.join(KEYS))
    for p in sys.argv[1:]:
        try:
            r = analyze(p)
        except FileNotFoundError:
            print(f"# skip missing: {p}", file=sys.stderr)
            continue
        print('\t'.join(str(r[k]) for k in KEYS))
