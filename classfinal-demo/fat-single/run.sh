#!/usr/bin/env bash
# fat-single：单模块 fat jar 演示
# 构建 -> 基线运行 -> 加密 -> 加密运行 -> 断言
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
CFJAR="$REPO_ROOT/classfinal-fatjar/target/classfinal-fatjar-1.2.1.jar"
PWD_ARG=123456
EXPECT="RESULT=hello-WORLD-3"

[ -f "$CFJAR" ] || { echo "缺少 ${CFJAR}，请先在仓库根执行 mvn package -DskipTests"; exit 1; }

cd "$HERE"
echo "== [fat-single] 构建 =="
mvn -q clean package -DskipTests

JAR="target/classfinal-demo-fat-single-1.0.0.jar"
ENC="target/classfinal-demo-fat-single-1.0.0-encrypted.jar"

echo "== [fat-single] 基线运行（未加密）=="
BASE="$(java -jar "$JAR" | { grep '^RESULT=' || true; })"
echo "   $BASE"

echo "== [fat-single] 加密 =="
java -jar "$CFJAR" -file "$HERE/$JAR" -packages com.example.fatsingle -pwd "$PWD_ARG" -Y >/dev/null

echo "== [fat-single] 加密后运行 =="
ENC_OUT="$(java "-javaagent:$HERE/$ENC=-pwd $PWD_ARG" -jar "$ENC" | { grep '^RESULT=' || true; })"
echo "   $ENC_OUT"

if [ "$BASE" = "$EXPECT" ] && [ "$ENC_OUT" = "$EXPECT" ]; then
  echo "== [fat-single] PASS =="
else
  echo "== [fat-single] FAIL (base='$BASE' enc='$ENC_OUT' expect='$EXPECT') =="
  exit 1
fi
