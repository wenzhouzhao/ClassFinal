#!/usr/bin/env bash
# thin-single：单模块 thin jar 演示（layout=ZIP，依赖外置到 target/lib）
# 构建 -> 基线运行 -> 加密 -> 加密运行 -> 断言
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
CFJAR="$REPO_ROOT/classfinal-fatjar/target/classfinal-fatjar-1.2.1.jar"
PWD_ARG=123456
EXPECT="RESULT=hello-WORLD-3"

[ -f "$CFJAR" ] || { echo "缺少 ${CFJAR}，请先在仓库根执行 mvn package -DskipTests"; exit 1; }

cd "$HERE"
echo "== [thin-single] 构建 =="
mvn -q clean package -DskipTests

JAR="target/classfinal-demo-thin-single-1.0.0.jar"
ENC="target/classfinal-demo-thin-single-1.0.0-encrypted.jar"

echo "== [thin-single] 基线运行（未加密）=="
BASE="$(java -Dloader.path=target/lib -jar "$JAR" | { grep '^RESULT=' || true; })"
echo "   $BASE"

echo "== [thin-single] 加密（app 自身类）=="
java -jar "$CFJAR" -file "$HERE/$JAR" -packages com.example.thinsingle -pwd "$PWD_ARG" -Y >/dev/null

echo "== [thin-single] 加密后运行 =="
ENC_OUT="$(java "-javaagent:$HERE/$ENC=-pwd $PWD_ARG" -Dloader.path=target/lib -jar "$ENC" | { grep '^RESULT=' || true; })"
echo "   $ENC_OUT"

if [ "$BASE" = "$EXPECT" ] && [ "$ENC_OUT" = "$EXPECT" ]; then
  echo "== [thin-single] PASS =="
else
  echo "== [thin-single] FAIL (base='$BASE' enc='$ENC_OUT' expect='$EXPECT') =="
  exit 1
fi
