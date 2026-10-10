#!/usr/bin/env bash
# fat-multi：多模块 fat jar 演示（lib 模块打进主 jar 的 BOOT-INF/lib）
# 构建 -> 基线运行 -> 加密（-libjars 指定包内依赖）-> 加密运行 -> 断言
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
CFJAR="$REPO_ROOT/classfinal-fatjar/target/classfinal-fatjar-1.2.1.jar"
PWD_ARG=123456
EXPECT="RESULT=calc=50,greet=AB"
LIB_JAR_NAME="classfinal-demo-fat-multi-lib-1.0.0.jar"

[ -f "$CFJAR" ] || { echo "缺少 ${CFJAR}，请先在仓库根执行 mvn package -DskipTests"; exit 1; }

cd "$HERE"
echo "== [fat-multi] 构建 =="
mvn -q clean package -DskipTests

JAR="app/target/classfinal-demo-fat-multi-app-1.0.0.jar"
ENC="app/target/classfinal-demo-fat-multi-app-1.0.0-encrypted.jar"

echo "== [fat-multi] 基线运行（未加密）=="
BASE="$(java -jar "$JAR" | { grep '^RESULT=' || true; })"
echo "   $BASE"

echo "== [fat-multi] 加密（含 -libjars ${LIB_JAR_NAME}）=="
java -jar "$CFJAR" -file "$HERE/$JAR" -libjars "$LIB_JAR_NAME" \
     -packages com.example.fatmulti -pwd "$PWD_ARG" -Y >/dev/null

echo "== [fat-multi] 加密后运行 =="
ENC_OUT="$(java "-javaagent:$HERE/$ENC=-pwd $PWD_ARG" -jar "$ENC" | { grep '^RESULT=' || true; })"
echo "   $ENC_OUT"

if [ "$BASE" = "$EXPECT" ] && [ "$ENC_OUT" = "$EXPECT" ]; then
  echo "== [fat-multi] PASS =="
else
  echo "== [fat-multi] FAIL (base='$BASE' enc='$ENC_OUT' expect='$EXPECT') =="
  exit 1
fi
