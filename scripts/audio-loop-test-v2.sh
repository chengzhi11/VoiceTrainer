#!/usr/bin/env bash
# audio-loop-test-v2.sh — 锁屏兼容回环自验证 (auto_record_ms 钩子驱动, 零 UI 坐标依赖)
#
# 背景: v1 (audio-loop-test.sh) 依赖解锁+uiautomator FAB 点击, 真机安全锁屏下
# 会环境受阻 (ENV-BLOCKED)。v2 改用 App 内置的 --ei auto_record_ms 自动化钩子, 锁屏/灭屏可跑。
#
# 用法: ./scripts/audio-loop-test-v2.sh [tone|sweep|say|say_male]
#   tone      — 440Hz 正弦 5s,        判据: 录音 F0 = 440Hz ± 2% (同 v1)
#   sweep     — 200→600Hz 扫频 5s,    判据: 起始≈200 / 结束≈600 ±10% (同 v1)
#   say       — say Tingting 中文女声, 判据: 有声占比≥30%, F0 80-350Hz (同 v1)
#   say_male  — say Fred 男声代理,     判据: 同 say (用于四态判别方向核验)
#
# 产物: dist/loop2-<mode>-<ts>.wav / .png (录音中截图, 锁屏时为 keyguard 画面) / .db(快照)
# 退出码: 0=PASS 1=FAIL 2=环境不可用
set -uo pipefail

MODE="${1:-tone}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/dist"
ASSETS="$ROOT/assets"
cd "$ROOT"
PKG="com.femininevoicetrainer.debug"
ACT="com.femininevoicetrainer.debug/com.femininevoicetrainer.ui.MainActivity"
REC_DIR="files/recordings"
VOL="${VOL:-70}"
REC_MS="${REC_MS:-10000}"   # 钩子录音时长: 覆盖 冷启(~3s)+播放(5s)+余量
PLAYS="${PLAYS:-1}"         # 音源连播次数(提高有声占比越过 MIN_VOICED_RATIO=0.6 门限)
START_DELAY="${START_DELAY:-3}"

log()  { echo "[loop2] $*"; }
fail() { echo "[loop2] FAIL: $*"; exit 1; }
env_fail() { echo "[loop2] ENV-BLOCKED: $*"; exit 2; }

mkdir -p "$DIST" "$ASSETS"

# ---------- 1. 环境自检 ----------
adb get-state >/dev/null 2>&1 || env_fail "无 adb 设备"
STATE="$(adb get-state 2>/dev/null)"
[ "$STATE" = "device" ] || env_fail "设备状态异常: $STATE"
adb shell pm path "$PKG" >/dev/null 2>&1 || env_fail "未安装 $PKG"
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO 2>/dev/null

# ---------- 2. 测试音源 ----------
SR=44100
gen_tone() {
  python3 - "$1" <<'EOF'
import wave, struct, math, sys
sr = 44100
if sys.argv[1] == 'tone':
    path, gen, secs = 'assets/test_440hz.wav', lambda t: math.sin(2*math.pi*440*t), 5
else:
    path, secs = 'assets/test_sweep.wav', 5
    gen = lambda t: math.sin(2*math.pi*(200*t + 40*t*t))
w = wave.open(path, 'w'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
w.writeframes(b''.join(struct.pack('<h', int(32767*0.4*gen(t/sr))) for t in range(int(sr*secs))))
w.close()
EOF
}
case "$MODE" in
  tone)     [ -f "$ASSETS/test_440hz.wav" ] || gen_tone tone;  SRC="$ASSETS/test_440hz.wav" ;;
  sweep)    [ -f "$ASSETS/test_sweep.wav" ] || gen_tone sweep; SRC="$ASSETS/test_sweep.wav" ;;
  say)      SRC="$ASSETS/say_tingting_loop.aiff"; [ -f "$SRC" ] || say -v Tingting -o "$SRC" "你好，这里是语音训练应用的自动回环测试。" || env_fail "say 合成失败" ;;
  say_male) SRC="$ASSETS/say_fred_loop.aiff"; [ -f "$SRC" ] || say -v Fred -o "$SRC" "Hello, this is the automatic loopback test of the voice training app." || env_fail "say 合成失败" ;;
  *) fail "未知模式: $MODE (可选 tone|sweep|say|say_male)" ;;
esac

# ---------- 3. 回环: 钩子启动录音 → Mac 发声 ----------
TS="$(date +%Y%m%d-%H%M%S)"
BEFORE="$(adb shell "run-as $PKG ls $REC_DIR 2>/dev/null" | sort | tail -1)"

osascript -e "set volume output volume $VOL"
log "冷启 + auto_record_ms=$REC_MS + 连播${PLAYS}次 + 起播延迟${START_DELAY}s"
adb shell am start -S -n "$ACT" --ei auto_record_ms "$REC_MS" >/dev/null 2>&1
sleep "$START_DELAY"             # 冷启 + 钩子起录
adb exec-out screencap -p > "$DIST/loop2-$MODE-$TS.png"   # 留证(锁屏时为 keyguard, 记录执行环境)
for i in $(seq 1 "$PLAYS"); do afplay "$SRC"; done
SLEEP_WAIT=$(( (REC_MS + 6000) / 1000 ))
log "等待录音落盘(${SLEEP_WAIT}s)..."
sleep "$SLEEP_WAIT"
osascript -e "set volume output volume 40"

# ---------- 4. 取录音 ----------
NEW=""
for i in $(seq 1 15); do
  NEW="$(adb shell "run-as $PKG ls $REC_DIR 2>/dev/null" | sort | tail -1)"
  [ -n "$NEW" ] && [ "$NEW" != "$BEFORE" ] && break
  sleep 1
done
[ -n "$NEW" ] && [ "$NEW" != "$BEFORE" ] || fail "未产生新录音文件 (钩子未生效?)"
log "新录音: $NEW"
adb shell "run-as $PKG cat $REC_DIR/$NEW" > "$DIST/loop2-$MODE-$TS.wav" || fail "pull 录音失败"

# ---------- 5. app 侧分析输出快照 (DB 最新行) ----------
adb shell "run-as $PKG cat databases/feminine_voice_database" > "$DIST/loop2-$MODE-$TS.db" 2>/dev/null
adb shell "run-as $PKG cat databases/feminine_voice_database-wal" > "$DIST/loop2-$MODE-$TS.db-wal" 2>/dev/null
adb shell "run-as $PKG cat databases/feminine_voice_database-shm" > "$DIST/loop2-$MODE-$TS.db-shm" 2>/dev/null

# ---------- 6. 量化判据 (与 v1 脚本同口径) ----------
python3 - "$DIST/loop2-$MODE-$TS.wav" "$MODE" <<'EOF'
import wave, struct, math, sys

path, mode = sys.argv[1], sys.argv[2]
w = wave.open(path, 'rb')
sr, n = w.getframerate(), w.getnframes()
data = w.readframes(n); w.close()
s = struct.unpack('<%dh' % (len(data)//2), data)
if len(s) < sr: print(f"FAIL: 录音过短 {len(s)/sr:.1f}s"); sys.exit(1)

def f0(seg, fmin=70, fmax=1200):
    seg = [x - sum(seg)/len(seg) for x in seg]
    e0 = sum(x*x for x in seg)
    if e0 < 1: return 0.0, 0.0
    best_lag, best = 0, 0.0
    for lag in range(max(2, sr//fmax), sr//fmin):
        c = sum(seg[i]*seg[i+lag] for i in range(len(seg)-lag)) / e0
        if c > best: best, best_lag = c, lag
    return (sr/best_lag if best_lag else 0.0), best

WIN = sr
rms_all = [math.sqrt(sum(x*x for x in s[i:i+WIN])/WIN) for i in range(0, len(s)-WIN, WIN//2)]
active = [r for r in rms_all if r > 500]
if not active: print(f"FAIL: 全程静音 (max_rms={max(rms_all):.0f}) — 锁屏下麦克风可能被系统限制"); sys.exit(1)
snr_seg = max(rms_all) / (sorted(rms_all)[len(rms_all)//10] + 1e-9)

ok, notes = True, []
if mode == 'tone':
    hits = []
    for i, r in enumerate(rms_all):
        if r > 500:
            seg = s[i*sr//2 : i*sr//2 + 4096]
            f, c = f0(seg)
            if c > 0.8: hits.append(f)
    med = sorted(hits)[len(hits)//2] if hits else 0
    err = abs(med - 440) / 440 * 100
    ok = med > 0 and err <= 2.0
    notes.append(f"F0中位数={med:.1f}Hz (目标440±2%), 误差={err:.2f}%, 命中窗={len(hits)}")
elif mode == 'sweep':
    fs = []
    for i, r in enumerate(rms_all):
        if r > 500:
            seg = s[i*sr//2 : i*sr//2 + 4096]
            f, c = f0(seg)
            if c > 0.8: fs.append((i*0.5, f))
    if len(fs) < 2: ok = False; notes.append("有效F0窗不足")
    else:
        f_start, f_end = fs[1][1], fs[-1][1]
        e1, e2 = abs(f_start-200)/200*100, abs(f_end-600)/600*100
        ok = e1 <= 10 and e2 <= 10
        notes.append(f"起始F0={f_start:.0f}Hz (≈200±10%), 结束F0={f_end:.0f}Hz (≈600±10%)")
elif mode in ('say', 'say_male'):
    voiced = []
    for i, r in enumerate(rms_all):
        if r > 500:
            seg = s[i*sr//2 : i*sr//2 + 4096]
            f, c = f0(seg, 60, 500)
            if c > 0.6 and 80 <= f <= 350: voiced.append(f)
    frac = len(voiced) / max(1, len([r for r in rms_all if r > 500]))
    ok = frac >= 0.3
    med = sorted(voiced)[len(voiced)//2] if voiced else 0
    notes.append(f"有声占比={frac*100:.0f}% (≥30%), 语音F0中位数={med:.0f}Hz (80-350)")

print(f"指标: 时长={n/sr:.2f}s 峰值RMS={max(rms_all):.0f} 峰谷比={snr_seg:.0f}x")
for x in notes: print(f"判据: {x}")
print("PASS" if ok else "FAIL: 量化判据未达标")
sys.exit(0 if ok else 1)
EOF
RESULT=$?
if [ $RESULT -eq 0 ]; then
  log "PASS — 证据: dist/loop2-$MODE-$TS.wav / .png / .db"
else
  log "FAIL — 证据已存 dist/loop2-$MODE-$TS.wav"
fi
exit $RESULT
