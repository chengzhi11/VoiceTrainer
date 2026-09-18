#!/usr/bin/env python3
# denoise-baseline.py — GH#5 高底噪拒判:多档 SNR 基线量化 + 降噪原型离线验证(纯调研,不接 app)
#
# 复用 calib-analyze.py 的五门镜像(YIN 阈值 0.2,P-017 已反编译核对)做判据层;
# 本脚本新增:
#   1. 混音源生成:干净人声(say_probe_Tingting, 22050→44100 线性重采样, ×3 tile ≈12.8s)
#      × 两种噪声(粉噪[与 COD-45/46 校准臂同法 seed 4517] / 合成车噪[<300Hz 为主])
#      SNR 档 +15/+10/+5/0/-5 dB + 纯噪声两臂;wav 写法遵循 P-016(clip×32767 再 astype)
#   2. 降噪原型(dev 候选 a 的离线等价实现):
#      hpf120/hpf150  = 二阶 Butterworth 高通(RBJ biquad,Q=0.707)
#      ss             = 幅度域谱减(STFT 1024/512 Hann, 噪声 PSD=能量最低 10% 帧均值,
#                       过减因子 α=2.0, 谱底 β=0.1, 保相位, ISTFT 重叠相加)
#   3. 全臂 × 全变体过五门镜像 → dist/denoise/baseline-gates.tsv(拒判墙定位)
#   4. 干净臂/snr15 臂五维特征漂移(逐步镜像 VoiceFeatureExtractor/VoiceFeatureCollector
#      全公式与数值常量:decimate sinc45@4500、hamming、tilt 100-4000、LPC13+Levinson、
#      HNR 自相关 ±15%、jitter RAP、shimmer dB)→ dist/denoise/baseline-drift.tsv
#
# 用法: python3 scripts/denoise-baseline.py  (在仓库根执行;产物写 dist/denoise/)
# 依赖: numpy(系统 python3 自带 2.5.x;wav 写前抽检非零样本防 P-016)
import importlib.util
import os
import sys
import wave

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIST = f"{ROOT}/dist/denoise"
SR = 44100

# ---- 载入五门镜像(勿复制粘贴,保持 COD-46 验证过的唯一实现) ----
_spec = importlib.util.spec_from_file_location("calib", f"{ROOT}/scripts/calib-analyze.py")
calib = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(calib)


# ================= 音源生成 =================

def write_wav(path, s, sr=SR):
    w = wave.open(path, "w"); w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
    w.writeframes((np.clip(s, -1, 1) * 32767).astype("<i2").tobytes()); w.close()

def write_checked(path, s):
    """P-016 预防:写前抽检非零样本占比。纯噪声臂经谱减后大面积归零是预期抑制效果,
    不算转换事故——降级为警告;真正的全零(浮点转换坑)仍会在 stdout 显式暴露。"""
    nz = int(np.count_nonzero(s))
    if nz < len(s) * 0.3:
        print(f"  [P-016 注意] {path}: 非零样本 {nz}/{len(s)}(纯噪声臂经降噪属预期,其余臂需人工核对)")
    write_wav(path, s)

def resample_lin(x, sr_from, sr_to):
    t = np.arange(len(x)) / sr_from
    t2 = np.arange(int(len(x) * sr_to / sr_from)) / sr_to
    return np.interp(t2, t, x)

def speech_active_rms(speech):
    """活跃段 RMS(2048 帧口径,>0.1×P95)——与 acoustic-calib.sh 完全一致"""
    fr = 2048
    frms = np.array([np.sqrt(np.mean(speech[i:i + fr] ** 2)) for i in range(0, len(speech) - fr, fr)])
    thr = 0.1 * np.percentile(frms, 95)
    active = frms[frms > thr]
    return float(np.sqrt(np.mean(active ** 2)))

def make_pink(seed=4517):
    """粉红噪声:白噪声 FFT 1/f 成形——与 acoustic-calib.sh 同法同 seed"""
    rng = np.random.default_rng(seed)
    m = 1 << 19
    white = rng.standard_normal(m)
    spec = np.fft.rfft(white)
    freqs = np.fft.rfftfreq(m, 1 / SR)
    freqs[0] = freqs[1]
    pink = np.fft.irfft(spec / np.sqrt(freqs), m)
    pink /= np.max(np.abs(pink))
    return pink * 0.5  # 峰值 -6dBFS

def make_car_noise(n, seed=20260918):
    """合成车噪(稳态巡航近似):座舱隆隆(<250Hz 粉噪低通)+ 引擎阶次谐波(基频
    ~85Hz 慢摆 ±10Hz,1..6 次,1/k^1.5 衰减)+ 轮胎/风宽带嘶声(~5% 能量)。
    能量集中 <300Hz,符合车内噪声谱以低频为主的文献共识;合成音源无许可证负担。"""
    rng = np.random.default_rng(seed)
    pink = make_pink(seed + 1)[:n] if len(make_pink(seed + 1)) >= n else np.resize(make_pink(seed + 1), n)
    # 平滑低通 250Hz(FFT 域 soft knee,避免振铃)
    spec = np.fft.rfft(pink)
    f = np.fft.rfftfreq(len(pink), 1 / SR)
    spec *= 1.0 / (1.0 + (f / 250.0) ** 4)
    rumble = np.fft.irfft(spec, len(pink))
    # 引擎阶次谐波
    t = np.arange(n) / SR
    f0 = 85.0 + 10.0 * np.sin(2 * np.pi * 0.15 * t)
    phase = 2 * np.pi * np.cumsum(f0) / SR
    engine = np.zeros(n)
    for k in range(1, 7):
        engine += (1.0 / k ** 1.5) * np.sin(k * phase + rng.uniform(0, 2 * np.pi))
    # 宽带嘶声
    hiss = rng.standard_normal(n) * 0.05
    x = rumble / np.sqrt(np.mean(rumble ** 2)) + 0.6 * engine / np.sqrt(np.mean(engine ** 2)) + hiss
    return (x / np.max(np.abs(x)) * 0.5).astype(np.float64)

def tile_noise(noise, n):
    if len(noise) >= n:
        return noise[:n]
    reps = (n + len(noise) - 1) // len(noise)
    return np.tile(noise, reps)[:n]

def snr_mix(speech, sp_rms, noise, snr_db):
    n_rms = np.sqrt(np.mean(noise ** 2))
    gain = sp_rms / (10 ** (snr_db / 20) * n_rms)
    return speech + noise * gain


# ================= 降噪原型 =================

def hpf_biquad(x, fc, sr=SR, q=0.707):
    """二阶 Butterworth 高通(RBJ cookbook,直接 II 型转置;dev 在 Kotlin 的等价实现)"""
    w0 = 2 * np.pi * fc / sr
    alpha = np.sin(w0) / (2 * q)
    cw, sw = np.cos(w0), np.sin(w0)
    b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = (1 + cw) / 2
    a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
    b0, b1, b2, a1, a2 = b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0
    y = np.empty_like(x)
    z1 = z2 = 0.0
    for i, xn in enumerate(x):
        yn = b0 * xn + z1
        z1 = b1 * xn - a1 * yn + z2
        z2 = b2 * xn - a2 * yn
        y[i] = yn
    return y

def spectral_subtract(x, n_fft=1024, hop=512, alpha=2.0, beta=0.1, noise_frac=0.10):
    """幅度域谱减(Berouti 风格):噪声 PSD 取帧能量最低 10% 帧的逐 bin 均值;
    |Y|²=max(|X|²-α·σ², β|X|²);保相位,ISTFT Hann 重叠相加(窗功率归一)。"""
    win = np.hanning(n_fft)
    n_frames = 1 + (len(x) - n_fft) // hop
    frames = np.stack([x[i * hop:i * hop + n_fft] for i in range(n_frames)])
    spec = np.fft.rfft(frames * win, axis=1)
    mag2 = np.abs(spec) ** 2
    energy = mag2.sum(axis=1)
    thr = np.quantile(energy, noise_frac)
    sel = np.where(energy <= thr)[0]
    if len(sel) < 3:  # 全程高噪兜底:全体帧最低 1/3
        sel = np.argsort(energy)[: max(3, n_frames // 3)]
    noise_psd = mag2[sel].mean(axis=0)
    y2 = np.maximum(mag2 - alpha * noise_psd, beta * mag2)
    gain = np.sqrt(y2) / np.maximum(np.abs(spec), 1e-12)
    y_spec = spec * gain
    # ISTFT
    out = np.zeros(len(x) + n_fft)
    wsum = np.zeros(len(x) + n_fft)
    for i in range(n_frames):
        out[i * hop:i * hop + n_fft] += np.fft.irfft(y_spec[i], n_fft) * win
        wsum[i * hop:i * hop + n_fft] += win ** 2
    nz = wsum > 1e-8
    out[nz] /= wsum[nz]
    return out[:len(x)]

VARIANTS = {
    "raw": lambda x: x,
    "hpf120": lambda x: hpf_biquad(x, 120.0),
    "hpf150": lambda x: hpf_biquad(x, 150.0),
    "ss": lambda x: spectral_subtract(x),
    "hpf120+ss": lambda x: spectral_subtract(hpf_biquad(x, 120.0)),
}


# ================= 五维特征镜像(P-017 口径:公式+常量逐项对齐 Kotlin) =================

ANALYSIS_SR = 11025
DECIM = 4
LPC_ORDER = 13
PRE_EMPH = 0.97
LP_CUTOFF = 4500.0
LP_TAPS = 45
FFT_SIZE = 512
FORMANT_F_MIN, FORMANT_F_MAX = 180.0, 5000.0
FORMANT_MIN_PEAK_DB = 3.0
FORMANT_MIN_SPACING_HZ = 180.0

def design_lowpass(sr, cutoff, taps):
    """镜像 VoiceFeatureExtractor.designLowPass(sinc+Hamming,直流增益 1)"""
    omega = 2 * np.pi * cutoff / sr
    m = (taps - 1) / 2
    n = np.arange(taps)
    k = n - m
    v = np.where(np.abs(k) < 1e-9, omega / np.pi, np.sin(omega * k) / (np.pi * np.where(np.abs(k) < 1e-9, 1, k)))
    w = 0.54 - 0.46 * np.cos(2 * np.pi * n / (taps - 1))
    h = v * w
    return h / h.sum()

_LP = design_lowpass(SR, LP_CUTOFF, LP_TAPS)

def decimate(x):
    """镜像 decimate(x,44100,4):out[o]=Σ x[4o+t]·lp[t]"""
    half = LP_TAPS // 2
    out_len = (len(x) - LP_TAPS + 1) // DECIM
    out = np.empty(out_len)
    for o in range(out_len):
        out[o] = np.dot(x[o * DECIM:o * DECIM + LP_TAPS], _LP)
    return out

def hamming(n):
    return 0.54 - 0.46 * np.cos(2 * np.pi * np.arange(n) / max(n - 1, 1))

def compute_hnr(frame, f0):
    """镜像 computeHnr:τ0±15% 邻域归一化自相关峰值 r → 10log10(r/(1-r))"""
    if f0 <= 0:
        return np.nan
    tau0 = int(SR / f0)
    if tau0 < 2 or tau0 * 2 >= len(frame):
        return np.nan
    lo = max(2, int(tau0 * 0.85)); hi = min(len(frame) // 2 - 1, int(tau0 * 1.15))
    if hi <= lo:
        return np.nan
    best = -1.0
    for lag in range(lo, hi + 1):
        a = frame[:len(frame) - lag]; b = frame[lag:]
        num = float(np.dot(a, b)); e1 = float(np.dot(a, a)); e2 = float(np.dot(b, b))
        d = np.sqrt(e1 * e2)
        if d > 1e-12:
            best = max(best, num / d)
    if best <= 0:
        return np.nan
    r = min(max(best, 0.01), 0.999)
    return 10 * np.log10(r / (1 - r))

def spectral_tilt(windowed):
    """镜像 computeSpectralTilt:FFT 幅度谱(÷帧长)在 100-4000Hz 的 log2 f 回归"""
    n = 1
    while n < len(windowed):
        n <<= 1
    re = np.zeros(n); re[:len(windowed)] = windowed
    mag = np.abs(np.fft.rfft(re)) / len(windowed)
    bin_hz = ANALYSIS_SR / n
    ks = np.arange(1, len(mag))
    f = ks * bin_hz
    m = (f >= 100) & (f <= 4000)
    x = np.log2(f[m]); y = 20 * np.log10(mag[ks[m]] + 1e-12)
    if len(x) < 2:
        return 0.0
    denom = len(x) * np.dot(x, x) - x.sum() ** 2
    if abs(denom) < 1e-9:
        return 0.0
    return (len(x) * np.dot(x, y) - x.sum() * y.sum()) / denom

def levinson(r, order):
    a = np.zeros(order + 1)
    if r[0] <= 0:
        return None
    err = r[0]
    for i in range(1, order + 1):
        acc = r[i]
        for j in range(1, i):
            acc -= a[j] * r[i - j]
        k = acc / err
        if abs(k) >= 1:
            return None
        prev = a[:i].copy()
        a[i] = k
        for j in range(1, i):
            a[j] = prev[j] - k * prev[i - j]
        err *= (1 - k * k)
        if err <= 0:
            return None
    return a

def pick_first_three_peaks(env_db, bin_hz):
    lo = max(int(FORMANT_F_MIN / bin_hz), 1)
    hi = min(len(env_db) - 2, int(FORMANT_F_MAX / bin_hz))
    if hi <= lo + 2:
        return None
    picked = []
    last = -100
    for kk in range(lo, hi + 1):
        if env_db[kk] > env_db[kk - 1] and env_db[kk] >= env_db[kk + 1]:
            left_min = env_db[kk]; i = kk - 1
            while i >= lo and env_db[i] <= env_db[i + 1]:
                left_min = env_db[i]; i -= 1
            right_min = env_db[kk]; i = kk + 1
            while i <= hi and env_db[i] <= env_db[i - 1]:
                right_min = env_db[i]; i += 1
            if env_db[kk] - max(left_min, right_min) < FORMANT_MIN_PEAK_DB:
                continue
            if (kk - last) * bin_hz < FORMANT_MIN_SPACING_HZ:
                continue
            picked.append(kk * bin_hz)
            last = kk
            if len(picked) == 3:
                break
    return picked if len(picked) == 3 else None

def formants_lpc(preemph):
    if len(preemph) <= LPC_ORDER + 1:
        return None
    e0 = float(np.dot(preemph, preemph))
    if e0 < 1e-9:
        return None
    r = [e0] + [float(np.dot(preemph[:-k], preemph[k:])) for k in range(1, LPC_ORDER + 1)]
    a = levinson(np.array(r), LPC_ORDER)
    if a is None:
        return None
    points = FFT_SIZE // 2
    bin_hz = ANALYSIS_SR / (2 * points)
    freqs = np.arange(points) * bin_hz
    w = 2 * np.pi * freqs / ANALYSIS_SR
    re = np.ones(points); im = np.zeros(points)
    for k in range(1, len(a)):
        re -= a[k] * np.cos(k * w)
        im += a[k] * np.sin(k * w)
    env_db = -20 * np.log10(np.sqrt(re ** 2 + im ** 2) + 1e-12)
    return pick_first_three_peaks(env_db, bin_hz)

def compute_jitter_rap(f0):
    if len(f0) < 3:
        return 0.0
    mean = np.mean(f0)
    if mean <= 0:
        return 0.0
    s = 0.0
    for i in range(1, len(f0) - 1):
        local = (f0[i - 1] + f0[i] + f0[i + 1]) / 3
        s += abs(f0[i] - local)
    return (s / (len(f0) - 2)) / mean

def compute_shimmer(rms):
    if len(rms) < 2:
        return 0.0
    a = np.maximum(rms[:-1], 1e-10); b = np.maximum(rms[1:], 1e-10)
    return float(np.mean(np.abs(20 * np.log10(b / a))))

def five_dim_features(path):
    """镜像 VoiceFeatureCollector.process 逐帧链路 + VoiceFeatureExtractor.analyze 聚合,
    返回会话级五维特征(仅在五门 PASS 或接近通过时数值有意义)"""
    s = calib.read_wav(path)
    n_frames = len(s) // 2048
    f0s, probs, voiced_idx = [], [], []
    rms44, hnr_list, f1s, f2s, f3s, tilts, vrms = [], [], [], [], [], [], []
    for i in range(n_frames):
        fr = s[i * 2048:(i + 1) * 2048]
        f0, prob = calib.yin_frame(fr)
        is_voiced = prob > calib.MIN_PROB and calib.F0_MIN <= f0 <= calib.F0_MAX
        rms = float(np.sqrt(np.mean(fr * fr)))
        rms44.append(rms)
        hnr_list.append(compute_hnr(fr, f0) if is_voiced else np.nan)
        down = decimate(fr)
        w = hamming(len(down))
        windowed = down * w
        tilts.append(spectral_tilt(windowed))
        if is_voiced:
            fm = formants_lpc((lambda p: np.concatenate(([p[0]], p[1:] - 0.97 * p[:-1])))(windowed))
            f1s.append(fm[0] if fm else np.nan); f2s.append(fm[1] if fm else np.nan); f3s.append(fm[2] if fm else np.nan)
            f0s.append(f0); probs.append(prob); voiced_idx.append(i); vrms.append(rms)
        else:
            f1s.append(np.nan); f2s.append(np.nan); f3s.append(np.nan)
    f0s = np.array(f0s)
    def med(vals):
        v = [x for x in vals if np.isfinite(x)]
        return float(np.percentile(v, 50)) if v else 0.0
    f1, f2, f3 = med(f1s), med(f2s), med(f3s)
    return {
        "f0P10": float(np.percentile(f0s, 10)), "f0P50": float(np.percentile(f0s, 50)),
        "f0P90": float(np.percentile(f0s, 90)),
        "f0Cv": float(np.std(f0s) / np.mean(f0s)) if len(f0s) > 1 else 0.0,
        "f1": f1, "f2": f2, "f3": f3, "resSpacing": (f3 - f1) / 3.0,
        "hnrDb": med(hnr_list), "tiltDbOct": med(tilts),
        "jitterRap": compute_jitter_rap(f0s), "shimmerDb": compute_shimmer(np.array(vrms)),
        "voiced_n": len(f0s),
    }


# ================= 主流程 =================

def main():
    os.makedirs(DIST, exist_ok=True)
    # 干净人声:与校准臂同源(say_probe_Tingting);calib.read_wav 兜底已线性重采样到 44100,×3 tile
    speech = np.tile(calib.read_wav(f"{ROOT}/assets/say_probe_Tingting.wav"), 3)
    sp_rms = speech_active_rms(speech)
    print(f"speech: dur={len(speech)/SR:.1f}s active_rms={sp_rms:.4f}")

    pink = make_pink()
    noises = {"pink": tile_noise(pink, len(speech)), "car": make_car_noise(len(speech))}

    # 臂 × 变体网格
    gate_rows = []
    for nname, noise in noises.items():
        arms = {f"snr{+15:+d}": snr_mix(speech, sp_rms, noise, 15),
                f"snr{+10:+d}": snr_mix(speech, sp_rms, noise, 10),
                f"snr{+5:+d}": snr_mix(speech, sp_rms, noise, 5),
                f"snr{0:+d}": snr_mix(speech, sp_rms, noise, 0),
                f"snr{-5:+d}": snr_mix(speech, sp_rms, noise, -5)}
        n_rms = np.sqrt(np.mean(noise ** 2))
        arms["noise_only"] = noise * (sp_rms / n_rms)          # 噪声=语音电平(0dB)
        arms["noise_only_hi"] = noise * (sp_rms / n_rms) * 2.0  # 噪声=语音+6dB(强噪假分压力)
        arms["clean"] = speech
        for aname, sig in arms.items():
            for vname, fn in VARIANTS.items():
                tag = f"{nname}-{aname}-{vname}"
                p = f"{DIST}/{tag}.wav"
                write_checked(p, fn(sig.copy()))
                r = calib.analyze(p)
                gate_rows.append((nname, aname, vname, r))
                print(f"{tag}: {r['usable']} {r['fail_reason']} snr={r['level_snr']} "
                      f"war={r['war']} voiced_ms={r['voiced_ms']} f0med={r['f0_med']}")

    with open(f"{DIST}/baseline-gates.tsv", "w") as f:
        f.write("noise\tarm\tvariant\tusable\tfail_reason\tspeech_level\tnoise_floor\t"
                "level_snr\twar\tvoiced_ms\tf0_med\n")
        for nname, aname, vname, r in gate_rows:
            f.write(f"{nname}\t{aname}\t{vname}\t{r['usable']}\t{r['fail_reason']}\t"
                    f"{r['speech_level']}\t{r['noise_floor']}\t{r['level_snr']}\t{r['war']}\t"
                    f"{r['voiced_ms']}\t{r['f0_med']}\n")

    # 五维漂移:clean 与 snr+15 臂,raw/hpf120/ss/hpf120+ss
    drift_rows = []
    for nname in noises:
        for aname in ("clean", "snr+15"):
            base = None
            for vname in ("raw", "hpf120", "ss", "hpf120+ss"):
                tag = f"{nname}-{aname}-{vname}"
                feat = five_dim_features(f"{DIST}/{tag}.wav")
                if vname == "raw":
                    base = feat
                feat["dF0P50_cents"] = 1200 * np.log2(feat["f0P50"] / base["f0P50"]) if base["f0P50"] > 0 else 0.0
                feat["dHNR_db"] = feat["hnrDb"] - base["hnrDb"]
                feat["dJitter"] = feat["jitterRap"] - base["jitterRap"]
                feat["dResHz"] = feat["resSpacing"] - base["resSpacing"]
                drift_rows.append((nname, aname, vname, feat))
                print(f"drift {tag}: f0P50={feat['f0P50']:.1f}({feat['dF0P50_cents']:+.0f}c) "
                      f"hnr={feat['hnrDb']:.1f}({feat['dHNR_db']:+.1f}dB) jitter={feat['jitterRap']:.4f}"
                      f"({feat['dJitter']:+.4f}) res={feat['resSpacing']:.0f}Hz({feat['dResHz']:+.0f})")

    keys = ["f0P10", "f0P50", "f0P90", "f0Cv", "f1", "f2", "f3", "resSpacing",
            "hnrDb", "tiltDbOct", "jitterRap", "shimmerDb", "voiced_n",
            "dF0P50_cents", "dHNR_db", "dJitter", "dResHz"]
    with open(f"{DIST}/baseline-drift.tsv", "w") as f:
        f.write("noise\tarm\tvariant\t" + "\t".join(keys) + "\n")
        for nname, aname, vname, feat in drift_rows:
            f.write(f"{nname}\t{aname}\t{vname}\t" + "\t".join(f"{feat[k]:.4f}" for k in keys) + "\n")

    print(f"\nwrote {DIST}/baseline-gates.tsv ({len(gate_rows)} rows), baseline-drift.tsv ({len(drift_rows)} rows)")

if __name__ == "__main__":
    main()
