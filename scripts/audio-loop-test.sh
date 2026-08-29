#!/usr/bin/env bash
# audio-loop-test.sh — Mac发声→安卓录音回环自验证 (uiautomator UI 坐标驱动)
#
# 用法: ./scripts/audio-loop-test.sh [tone|sweep|say]
#   tone  — 440Hz 正弦 5s (默认), 判据: 录音 F0 = 440Hz ± 2%
#   sweep — 200→600Hz 线性扫频 5s, 判据: F0 轨迹单调上行 + 线性拟合斜率≈80Hz/s、跨度≈400Hz
#   say   — macOS say 合成语音, 判据: 有声段 F0 落在 80-350Hz
#
# 依赖: adb(真机已授权USB调试), afplay, python3(仅标准库)
# 产物: dist/loop-<mode>-<时间戳>.wav / .png (录音中截图)
# 退出码: 0=PASS 1=FAIL 2=环境不可用
set -uo pipefail

MODE="${1:-tone}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/dist"
ASSETS="$ROOT/assets"
cd "$ROOT"   # gen_tone 用相对路径写 assets/
PKG="com.femininevoicetrainer.debug"
REC_DIR="files/recordings"
VOL="${VOL:-70}"

log()  { echo "[loop] $*"; }
fail() { echo "[loop] FAIL: $*"; exit 1; }
env_fail() { echo "[loop] ENV-BLOCKED: $*"; exit 2; }

mkdir -p "$DIST" "$ASSETS"

# ---------- 1. 环境自检 ----------
adb get-state >/dev/null 2>&1 || env_fail "无 adb 设备, 请检查 USB 连接"
STATE="$(adb get-state 2>/dev/null)"
[ "$STATE" = "device" ] || env_fail "设备状态异常: $STATE (离线/未授权)"
adb shell pm path "$PKG" >/dev/null 2>&1 || env_fail "未安装 $PKG"

# 唤醒 → 解锁 → 锁竖屏。坑: MIUI 在 keyguard 解锁瞬间会把 accelerometer_rotation 翻回 1
# (传感器再断言), 必须先 dismiss-keyguard 再锁竖屏, 并连续两次确认后才能 dump。
# uiautomator 只在竖屏下给出正确的 FAB bounds; 横屏下 FAB 被 Compose 裁剪, 点击无效。
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard 2>/dev/null
adb shell svc power stayon usb 2>/dev/null   # 测试期间保持亮屏
sleep 2
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO 2>/dev/null
locked=0
for i in 1 2 3 4 5; do
  adb shell settings put system accelerometer_rotation 0
  adb shell settings put system user_rotation 0
  sleep 1
  R1="$(adb shell dumpsys window displays | grep -o 'mRotation=ROTATION_[0-9]*' | head -1)"
  sleep 1
  R2="$(adb shell dumpsys window displays | grep -o 'mRotation=ROTATION_[0-9]*' | head -1)"
  if [ "$R1" = "mRotation=ROTATION_0" ] && [ "$R2" = "mRotation=ROTATION_0" ]; then locked=1; break; fi
done
[ "$locked" = 1 ] || env_fail "无法锁定竖屏 (当前 $R2), MIUI 旋转锁被反复重置"

# ---------- 2. 测试音源 ----------
SR=44100
gen_tone() {
  python3 - "$1" <<'EOF'
import wave, struct, math, sys
sr = 44100
if sys.argv[1] == 'tone':
    path, gen, secs = 'assets/test_440hz.wav', lambda t: math.sin(2*math.pi*440*t), 5
else:
    # 线性扫频 200→600Hz: f(t)=200+80t, 相位=2π(200t+40t²)
    path, secs = 'assets/test_sweep.wav', 5
    gen = lambda t: math.sin(2*math.pi*(200*t + 40*t*t))
w = wave.open(path, 'w'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
w.writeframes(b''.join(struct.pack('<h', int(32767*0.4*gen(t/sr))) for t in range(int(sr*secs))))
w.close()
EOF
}
case "$MODE" in
  tone)  [ -f "$ASSETS/test_440hz.wav" ] || gen_tone tone;  SRC="$ASSETS/test_440hz.wav" ;;
  sweep) [ -f "$ASSETS/test_sweep.wav" ] || gen_tone sweep; SRC="$ASSETS/test_sweep.wav" ;;
  say)   SRC="$ASSETS/test_say.aiff"; say -o "$SRC" "你好，这里是语音训练应用的自动回环测试。" || env_fail "say 合成失败" ;;
  *) fail "未知模式: $MODE (可选 tone|sweep|say)" ;;
esac

# ---------- 3. 启动 App 并定位录音按钮 ----------
adb shell "monkey -p $PKG -c android.intent.category.LAUNCHER 1" >/dev/null 2>&1
sleep 3
# dump 必须是竖屏 (rotation="0"), 横屏 dump 的 FAB bounds 不可用, 最多重试 2 次
DUMP_OK=0
for i in 1 2; do
  adb shell uiautomator dump /sdcard/loop_ui.xml >/dev/null 2>&1 && break
  sleep 1
done
adb pull /sdcard/loop_ui.xml "$DIST/loop_ui.xml" >/dev/null || fail "uiautomator dump 失败"
grep -q 'rotation="0"' "$DIST/loop_ui.xml" || fail "dump 非竖屏 (rotation!=0), 旋转锁被 MIUI 重置"
# 确保在「录音」tab (上次可能停在历史记录 tab)
REC_TAB="$(python3 - "$DIST/loop_ui.xml" <<'EOF'
import re, sys
xml = open(sys.argv[1], encoding='utf-8', errors='ignore').read()
for n in re.findall(r'<node[^>]*>', xml):
    if 'text="录音"' in n:
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b:
            x1,y1,x2,y2 = map(int, b.groups())
            print((x1+x2)//2, (y1+y2)//2); break
EOF
)"
if [ -n "$REC_TAB" ]; then
  read -r TX TY <<< "$REC_TAB"
  adb shell input tap "$TX" "$TY"
  sleep 1.5
  adb shell uiautomator dump /sdcard/loop_ui.xml >/dev/null 2>&1
  adb pull /sdcard/loop_ui.xml "$DIST/loop_ui.xml" >/dev/null
fi
FAB="$(python3 - "$DIST/loop_ui.xml" <<'EOF'
import re, sys
xml = open(sys.argv[1], encoding='utf-8', errors='ignore').read()
nodes = re.findall(r'<node[^>]*>', xml)
root_w = 0
for n in nodes:
    b = re.search(r'bounds="\[0,0\]\[(\d+),(\d+)\]"', n)
    if b: root_w = max(root_w, int(b.group(1)))
best = None
for n in nodes:
    if 'clickable="true"' not in n: continue
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if not b: continue
    x1,y1,x2,y2 = map(int, b.groups())
    w, h = x2-x1, y2-y1
    cx = (x1+x2)//2
    # 录音 FAB: 近正方形 150-300px, 中心在屏幕宽度中间 1/3 (横竖屏通用, 与 input tap 同坐标系)
    if 150 <= w <= 300 and abs(w-h) <= 10 and root_w and root_w/3 <= cx <= 2*root_w/3:
        best = f"{cx} {(y1+y2)//2}"; break
print(best or "")
EOF
)"
[ -n "$FAB" ] || fail "未在 UI 中定位到录音按钮 (竖屏 FAB 未匹配)"
read -r FX FY <<< "$FAB"
log "录音按钮坐标: ($FX,$FY)"

TS="$(date +%Y%m%d-%H%M%S)"
BEFORE="$(adb shell "run-as $PKG ls $REC_DIR 2>/dev/null" | sort | tail -1)"

# ---------- 4. 回环: 开始录音 → Mac发声 → 停止 ----------
osascript -e "set volume output volume $VOL"
adb shell input tap "$FX" "$FY"
sleep 1.5
adb exec-out screencap -p > "$DIST/loop-$MODE-$TS.png"   # 录音中截图留证
afplay "$SRC"
sleep 0.8
adb shell input tap "$FX" "$FY"
sleep 1.5
osascript -e "set volume output volume 40"

# 停止后 WAV 才落盘, 轮询等待新文件
NEW=""
for i in $(seq 1 10); do
  NEW="$(adb shell "run-as $PKG ls $REC_DIR 2>/dev/null" | sort | tail -1)"
  [ -n "$NEW" ] && [ "$NEW" != "$BEFORE" ] && break
  sleep 1
done
[ -n "$NEW" ] && [ "$NEW" != "$BEFORE" ] || fail "未产生新录音文件 (开始/停止点击可能未生效)"
log "新录音: $NEW"

adb shell "run-as $PKG cat $REC_DIR/$NEW" > "$DIST/loop-$MODE-$TS.wav" || fail "pull 录音失败"

# ---------- 5. 量化判据 ----------
python3 - "$DIST/loop-$MODE-$TS.wav" "$MODE" <<'EOF'
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

WIN = sr  # 1s 窗
rms_all = [math.sqrt(sum(x*x for x in s[i:i+WIN])/WIN) for i in range(0, len(s)-WIN, WIN//2)]
active = [r for r in rms_all if r > 500]
if not active: print(f"FAIL: 全程静音 (max_rms={max(rms_all):.0f})"); sys.exit(1)
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
    # 判据: 轨迹单调性 + 线性拟合。
    # 旧判据「首末窗均值 ≈200/≈600 ±10%」数学上不可达标: 0.5-1.5s 窗的真实均值即 ~280Hz,
    # 且 MIUI 麦链路扫频中后段有能量衰减,窗会缺失。改为检验轨迹形状本身:
    # 单调上行比例、拟合斜率(理论 80Hz/s)、拟合跨度(理论 400Hz)。
    fs = []
    for i, r in enumerate(rms_all):
        if r > 500:
            seg = s[i*sr//2 : i*sr//2 + 4096]
            f, c = f0(seg)
            if c > 0.8: fs.append((i*0.5, f))
    if len(fs) < 4: ok = False; notes.append(f"有效F0窗不足 ({len(fs)}<4)")
    else:
        vs = [f for _, f in fs]
        pairs = max(1, len(vs) - 1)
        mono_ratio = sum(1 for a, b in zip(vs, vs[1:]) if b >= a * 0.9) / pairs
        n = len(fs)
        mt = sum(t for t, _ in fs) / n
        mv = sum(vs) / n
        denom = sum((t - mt) ** 2 for t, _ in fs)
        slope = (sum((t - mt) * (v - mv) for t, v in fs) / denom) if denom > 1e-9 else 0.0
        intercept = mv - slope * mt
        f_lo = intercept + slope * fs[0][0]
        f_hi = intercept + slope * fs[-1][0]
        span = f_hi - f_lo
        ok = mono_ratio >= 0.8 and 40 <= slope <= 120 and 250 <= span <= 500
        notes.append(
            f"轨迹单调比例={mono_ratio*100:.0f}% (≥80%), "
            f"拟合斜率={slope:.0f}Hz/s (40-120), "
            f"拟合轨迹={f_lo:.0f}→{f_hi:.0f}Hz 跨度={span:.0f}Hz (250-500), 窗数={n}"
        )
elif mode == 'say':
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
  log "PASS — 证据: dist/loop-$MODE-$TS.wav, dist/loop-$MODE-$TS.png"
else
  log "FAIL — 证据已存 dist/loop-$MODE-$TS.wav"
fi
exit $RESULT
