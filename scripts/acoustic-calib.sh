#!/usr/bin/env bash
# acoustic-calib.sh — COD-45 真机声学定标实验
#
# 目的:量化 距离/音量(以激励电平代理,USB 固定无法移机)× 环境噪声(激励内混入
# 粉红噪声,控制 SNR)组合下,App 评分前置门限(有声帧数/占比、YIN 置信度)的行为,
# 并记录 App 自身判例(DB voiceCondition)与离线复刻分析(scripts/calib-analyze.py)对照。
#
# 用法: ./scripts/acoustic-calib.sh          # 全矩阵 ~7min
# 产物: dist/calib/<cond>-<ts>.wav + verdict-<cond>.txt + calib-results.tsv
# 依赖: adb 真机(已装 debug 包)、afplay/osascript(Mac 声源)、numpy venv(PY 变量)
#
# COD-46 变更:激励源 ×3 循环(~12.9s)+ REC_MS=13000——新门限要求有效语音 ≥3.0s,
# 旧 7.2s 协议录音最多含 ~3.2s 语音(~2.4s 有声),物理上攒不够,vol55~100 永远
# INSUFF_VOICED;循环后 vol55~100 应稳定出分(回归预期见 docs/research-calibration.md)。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/dist/calib"
PY="${PY:-/tmp/calib-venv/bin/python}"
PKG="com.femininevoicetrainer.debug"
ACT="$PKG/com.femininevoicetrainer.ui.MainActivity"
REC_DIR="files/recordings"
REC_MS=13000
START_DELAY=4

log() { echo "[calib] $*"; }
die() { echo "[calib] ENV-BLOCKED: $*"; exit 2; }

mkdir -p "$DIST"

# ---------- 1. 环境自检 ----------
adb get-state >/dev/null 2>&1 || die "无 adb 设备"
[ "$(adb get-state 2>/dev/null)" = "device" ] || die "设备状态异常"
adb shell pm path "$PKG" >/dev/null 2>&1 || die "未安装 $PKG"
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO 2>/dev/null
# 静音设备媒体音量:App 录音结束自动回放,避免回放声污染下一轮
adb shell media volume --stream 3 --set 0 >/dev/null 2>&1
log "设备媒体音量已静音(防自动回放污染)"

# ---------- 2. 激励源 ----------
afconvert -f WAVE -d LEI16@44100 -c 1 "$ROOT/assets/say_tingting_loop.aiff" "$DIST/speech44.wav" >/dev/null 2>&1
"$PY" - "$DIST" <<'EOF'
import wave, sys
import numpy as np

dist = sys.argv[1]
sr = 44100

def read_wav(p):
    w = wave.open(p, 'rb'); n = w.getnframes()
    s = np.frombuffer(w.readframes(n), '<i2').astype(np.float64) / 32768.0
    w.close(); return s

def write_wav(p, s):
    w = wave.open(p, 'w'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
    # 注意:float→int16 必须先乘 32767,astype 直接截断会把 [-1,1] 全变 0
    w.writeframes((np.clip(s, -1, 1) * 32767).astype('<i2').tobytes()); w.close()

speech = read_wav(f'{dist}/speech44.wav')
# COD-46:激励 ×3 循环(总 ~12.9s),保证新门限下录音窗内可攒够 ≥3.0s 有声语音
speech = np.tile(speech, 3)
write_wav(f'{dist}/speech_loop.wav', speech)
# 语音活跃段 RMS(2048 帧口径,>0.1×P95)
fr = 2048
frms = np.array([np.sqrt(np.mean(speech[i:i+fr]**2)) for i in range(0, len(speech)-fr, fr)])
thr = 0.1 * np.percentile(frms, 95)
active = frms[frms > thr]
sp_rms = np.sqrt(np.mean(active**2))
print(f"speech_loop dur={len(speech)/sr:.1f}s active rms={sp_rms:.4f} active_frame_frac={len(active)/len(frms):.2f}")

# 粉红噪声:白噪声 FFT 1/f 成形
rng = np.random.default_rng(4517)
m = 1 << 19
white = rng.standard_normal(m)
spec = np.fft.rfft(white)
freqs = np.fft.rfftfreq(m, 1/sr)
freqs[0] = freqs[1]
pink = np.fft.irfft(spec / np.sqrt(freqs), m)
pink /= np.max(np.abs(pink))
pink *= 0.5  # 峰值 -6dBFS

def snr_mix(snr_db):
    noise = np.tile(pink, (len(speech) + len(pink) - 1) // len(pink))[:len(speech)]
    # 对齐活跃段位置的噪声 RMS(整体)
    n_rms = np.sqrt(np.mean(noise**2))
    gain = sp_rms / (10**(snr_db/20) * n_rms)
    return speech + noise * gain

def write_checked(p, s):
    # P-016 预防:float→int16 全零坑,写前抽检非零样本
    nz = int(np.count_nonzero(s))
    assert nz > len(s) * 0.3, f"{p}: 非零样本仅 {nz}/{len(s)},疑似全零(P-016)"
    write_wav(p, s)

for snr in (20, 10, 5, 0):
    write_checked(f'{dist}/snr_{snr}.wav', snr_mix(snr))
# 噪声单放:取 snr_10 中的噪声分量电平
noise = np.tile(pink, (len(speech) + len(pink) - 1) // len(pink))[:len(speech)]
n_rms = np.sqrt(np.mean(noise**2))
write_checked(f'{dist}/noise_only.wav', noise * (sp_rms / (10**(10/20) * n_rms)))
# 高电平纯噪声(强噪声环境假分压力测试:噪声=语音电平)
write_checked(f'{dist}/noise_only_hi.wav', noise * (sp_rms / n_rms))
print("stimuli ready: speech_loop.wav snr_20/10/5/0.wav noise_only.wav noise_only_hi.wav")
EOF
[ -f "$DIST/snr_10.wav" ] || die "激励生成失败"

# ---------- 3. 实验矩阵 ----------
TS="$(date +%Y%m%d-%H%M%S)"
RESULTS="$DIST/calib-results-$TS.tsv"

run_case() {
  local name="$1" rec_ms="$2" vol="$3" src="$4"
  local before new wavtag
  before="$(adb shell "run-as $PKG ls $REC_DIR 2>/dev/null" | sort | tail -1)"
  adb shell am start -S -n "$ACT" --ei auto_record_ms "$rec_ms" >/dev/null 2>&1
  sleep "$START_DELAY"
  osascript -e "set volume output volume $vol" >/dev/null
  [ -n "$src" ] && [ "$src" != "-" ] && afplay "$src"
  osascript -e "set volume output volume 40" >/dev/null
  sleep $(( rec_ms / 1000 + 6 ))
  # 取新录音
  new=""
  for i in 1 2 3 4 5 6 7 8; do
    new="$(adb shell "run-as $PKG ls $REC_DIR 2>/dev/null" | sort | tail -1)"
    [ -n "$new" ] && [ "$new" != "$before" ] && break
    sleep 1
  done
  if [ -z "$new" ] || [ "$new" = "$before" ]; then
    echo "$name	NO_WAV" >> "$RESULTS"; log "$name: 未产生录音!"; return
  fi
  adb shell "run-as $PKG cat $REC_DIR/$new" > "$DIST/$name-$TS.wav" 2>/dev/null
  # App 自身判例:DB 最新行
  adb shell "run-as $PKG cat databases/feminine_voice_database" > "$DIST/db-$$.db" 2>/dev/null
  adb shell "run-as $PKG cat databases/feminine_voice_database-wal" > "$DIST/db-$$.db-wal" 2>/dev/null
  adb shell "run-as $PKG cat databases/feminine_voice_database-shm" > "$DIST/db-$$.db-shm" 2>/dev/null
  local verdict="DB_UNREADABLE"
  if command -v sqlite3 >/dev/null; then
    verdict="$(sqlite3 "$DIST/db-$$.db" "SELECT voiceCondition || ' | ' || IFNULL(voiceType,'-') || ' | score=' || ROUND(score,1) || ' | dur=' || duration FROM recordings ORDER BY id DESC LIMIT 1;" 2>/dev/null)"
    [ -n "$verdict" ] || verdict="EMPTY"
  fi
  echo "$name	$verdict" >> "$DIST/verdict-$TS.txt"
  echo "$name	$verdict" >> "$RESULTS"
  log "$name → $verdict"
}

log "=== 矩阵开始 (REC_MS=$REC_MS, 每轮 ~20s) ==="

# 静音底噪基线
if [ "${SNR_ONLY:-0}" != "1" ]; then
run_case silence "$REC_MS" 40 "-"
# 音量阶梯(VOL→激励电平;距离轴以电平衰减代理)
run_case vol30 "$REC_MS" 30 "$DIST/speech_loop.wav"
run_case vol40 "$REC_MS" 40 "$DIST/speech_loop.wav"
run_case vol55 "$REC_MS" 55 "$DIST/speech_loop.wav"
run_case vol70 "$REC_MS" 70 "$DIST/speech_loop.wav"
run_case vol85 "$REC_MS" 85 "$DIST/speech_loop.wav"
run_case vol100 "$REC_MS" 100 "$DIST/speech_loop.wav"
# 短录音(太短归因用例)
run_case short2500ms 2500 85 "$DIST/speech_loop.wav"
# pass12s 复放(定标基准样例:12.2s 录音 8.5s 连续语音,新门限应出分)
[ -f "$DIST/pass12s.wav" ] && run_case pass12s 13500 85 "$DIST/pass12s.wav"
fi
# SNR 阶梯(固定 VOL85;SNR_ONLY=1 单独补跑噪声臂)
run_case snr20 "$REC_MS" 85 "$DIST/snr_20.wav"
run_case snr10 "$REC_MS" 85 "$DIST/snr_10.wav"
run_case snr5  "$REC_MS" 85 "$DIST/snr_5.wav"
run_case snr0  "$REC_MS" 85 "$DIST/snr_0.wav"
# 纯噪声(假分压力测试:无语音,只有噪声)
run_case noise_only "$REC_MS" 85 "$DIST/noise_only.wav"
run_case noise_only_hi "$REC_MS" 85 "$DIST/noise_only_hi.wav"

rm -f "$DIST/db-$$.db" "$DIST/db-$$.db-wal" "$DIST/db-$$.db-shm"

# ---------- 4. 离线分析 ----------
log "=== 离线帧级分析 ==="
"$PY" "$ROOT/scripts/calib-analyze.py" "$DIST"/{silence,vol30,vol40,vol55,vol70,vol85,vol100,snr20,snr10,snr5,snr0,noise_only,noise_only_hi,short2500ms,pass12s}-$TS.wav >> "$RESULTS" 2>/dev/null || log "离线分析失败(单独跑 calib-analyze.py)"

osascript -e "set volume output volume 40" >/dev/null
log "完成。产物: $RESULTS (verdict 行 + 离线指标 TSV)"
