#!/usr/bin/env bash
# 纯 JVM 跑全部单元/端到端测试 —— 不需要 Android 运行时，也不需要真机。
# 原理：被测的几个类（Xhtml / Exporter.prepareChapter / Store.order）在静态初始化
#       阶段不碰 Android API，碰到的都被 try/catch 兜住了，所以直接 java 就能跑。
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK:-/data/data/com.dsharnessmobile.shell/files/usr/opt/android-sdk}"
PLATFORM="${ANDROID_JAR:-$SDK/platforms/android-35/android.jar}"
XPOSED="$ROOT/lib/api-82.jar"

cd "$ROOT"
[ -d out/classes ] || { echo "!! 先跑一次 bash build.sh 生成 out/classes"; exit 1; }

CP="out/classes:$PLATFORM:$XPOSED"
mkdir -p test/classes
javac -encoding UTF-8 -nowarn -cp "$CP" -d test/classes \
  $(find test/src -name '*.java')

FAIL=0
for t in ParseTest OrderTest DupTest com.fq.localsave.ExportTest; do
  printf '── %-30s ' "$t"
  if java -cp "test/classes:$CP" "$t" > "test/classes/$t.log" 2>&1; then
    echo "PASS"
  else
    echo "FAIL"; FAIL=1
    tail -20 "test/classes/$t.log"
  fi
done
echo
[ $FAIL -eq 0 ] && echo "全部通过 ✔" || { echo "有失败 ✘"; exit 1; }
