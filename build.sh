#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════════
#  番茄本地下载（LSPosed 模块）—— 无 Gradle 手动构建
#  aapt2 → javac → d8 → 打包 classes.dex → zipalign → apksigner
#
#  为什么不用 Gradle：本机（Termux 环境）没有 Gradle，
#  而这条链路只需要 Android SDK 的 build-tools + 一个 android.jar，
#  产出完全一样，构建时间还短得多。
# ══════════════════════════════════════════════════════════════
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
# 全部可用环境变量覆盖，方便本机 / CI 两套环境共用同一个脚本：
#   ANDROID_SDK   Android SDK 根目录
#   BUILD_TOOLS   build-tools 目录（默认 $ANDROID_SDK/build-tools/35.0.0）
#   ANDROID_JAR   android.jar（默认 $ANDROID_SDK/platforms/android-35/android.jar）
#   KS            签名 keystore（密码固定 android/android）
#   AAPT2         aapt2 可执行文件
SDK="${ANDROID_SDK:-/data/data/com.dsharnessmobile.shell/files/usr/opt/android-sdk}"
BT="${BUILD_TOOLS:-$SDK/build-tools/35.0.0}"
PLATFORM="${ANDROID_JAR:-$SDK/platforms/android-35/android.jar}"
KS="${KS:-/data/data/com.dsharnessmobile.shell/files/home/.rt-sdk/debug.keystore}"

APP="$ROOT/app"
LIB="$ROOT/lib"
OUT="$ROOT/out"
XPOSED="$LIB/api-82.jar"

MIN_SDK=26
TGT_SDK=34
VER_CODE=3
VER_NAME=1.0.2

# ⚠ 本机 SDK 的 build-tools 可执行文件是 x86_64 ELF，跑不了。
#   所以：aapt2 用 Termux 包里的 ARM64 版；d8 / apksigner 是 shell 脚本（内部调 java，跨架构）；
#   zipalign 没有 ARM64 版 → 用自写的 tools/zipalign.py 代替。
AAPT2="${AAPT2:-$(command -v aapt2 || echo "$BT/aapt2")}"
D8="$BT/d8"
APKSIGNER="$BT/apksigner"
ZIPALIGN="$ROOT/tools/zipalign.py"

for t in "$AAPT2" "$D8" "$APKSIGNER" "$PLATFORM" "$XPOSED" "$ZIPALIGN"; do
  [ -e "$t" ] || { echo "!! 缺少依赖: $t"; exit 1; }
done

echo "==================================================="
echo " 构建 番茄本地下载 v$VER_NAME"
echo "==================================================="

rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/classes" "$OUT/dex"

echo "[1/7] aapt2 compile 资源…"
"$AAPT2" compile --dir "$APP/res" -o "$OUT/res.zip"

echo "[2/7] aapt2 link（含 assets/xposed_init）…"
"$AAPT2" link \
  -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$APP/AndroidManifest.xml" \
  -A "$APP/assets" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TGT_SDK" \
  --version-code "$VER_CODE" \
  --version-name "$VER_NAME" \
  "$OUT/res.zip"

echo "[3/7] javac 编译…"
SRCS=$(find "$APP/src" -name '*.java' | tr '\n' ' ')
echo "      源文件: $(echo $SRCS | wc -w) 个"
set +e
javac -encoding UTF-8 -nowarn \
  -source 8 -target 8 \
  -bootclasspath "$PLATFORM" \
  -classpath "$PLATFORM:$XPOSED" \
  -d "$OUT/classes" \
  $SRCS > "$OUT/javac.log" 2>&1
RC=$?
set -e
grep -v "bootstrap class path\|source value 8\|target value 8\|deprecat\|warning" "$OUT/javac.log" | head -60 || true
if [ $RC -ne 0 ]; then echo "!! javac 失败 (rc=$RC)"; cat "$OUT/javac.log"; exit 1; fi
echo "      javac OK"

echo "[4/7] d8 转 dex…"
find "$OUT/classes" -name '*.class' > "$OUT/classlist.txt"
echo "      class 数: $(wc -l < "$OUT/classlist.txt")"
set +e
"$D8" --release --min-api "$MIN_SDK" \
  --lib "$PLATFORM" --lib "$XPOSED" \
  --output "$OUT/dex" $(cat "$OUT/classlist.txt" | tr '\n' ' ') > "$OUT/d8.log" 2>&1
RC=$?
set -e
tail -20 "$OUT/d8.log"
if [ $RC -ne 0 ]; then echo "!! d8 失败 (rc=$RC)"; cat "$OUT/d8.log"; exit 1; fi
echo "      d8 OK"

echo "[5/7] 把 classes.dex 打进 APK…"
python3 - "$OUT/base.apk" "$OUT/unsigned.apk" "$OUT/dex/classes.dex" <<'PY'
import shutil, sys, zipfile, os
base, dst, dex = sys.argv[1], sys.argv[2], sys.argv[3]
shutil.copy2(base, dst)
with zipfile.ZipFile(dst, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
print("      classes.dex:", os.path.getsize(dex), "bytes")
PY

echo "[6/7] zipalign（纯 Python 实现）…"
python3 "$ZIPALIGN" "$OUT/unsigned.apk" "$OUT/aligned.apk" 4

echo "[7/7] 签名（v2，LSPosed 与 Android 7+ 都认）…"
"$APKSIGNER" sign \
  --ks "$KS" \
  --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled false --v2-signing-enabled true \
  --out "$OUT/fq-local-save.apk" "$OUT/aligned.apk"

echo
echo "==================================================="
ls -la "$OUT/fq-local-save.apk"
"$APKSIGNER" verify --print-certs "$OUT/fq-local-save.apk" 2>&1 | head -4
echo "==================================================="
echo "产物: $OUT/fq-local-save.apk"
