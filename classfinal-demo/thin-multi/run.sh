#!/usr/bin/env bash
# thin-multi：多模块 thin jar 演示（layout=ZIP，lib 外置到 app/target/lib）
# 构建 -> 基线运行 -> 加密主 jar + 逐个加密外置依赖 -> 加密运行 -> 断言
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
CFJAR="$REPO_ROOT/classfinal-fatjar/target/classfinal-fatjar-1.2.1.jar"
PWD_ARG=123456
EXPECT="RESULT=calc=50,greet=AB"
LIB_JAR="app/target/lib/classfinal-demo-thin-multi-lib-1.0.0.jar"

[ -f "$CFJAR" ] || { echo "缺少 ${CFJAR}，请先在仓库根执行 mvn package -DskipTests"; exit 1; }

cd "$HERE"
echo "== [thin-multi] 构建 =="
mvn -q clean package -DskipTests

JAR="app/target/classfinal-demo-thin-multi-app-1.0.0.jar"
ENC="app/target/classfinal-demo-thin-multi-app-1.0.0-encrypted.jar"

echo "== [thin-multi] 基线运行（未加密）=="
BASE="$(java -Dloader.path=app/target/lib -jar "$JAR" | { grep '^RESULT=' || true; })"
echo "   $BASE"

echo "== [thin-multi] 加密主 jar（app 自身类）=="
java -jar "$CFJAR" -file "$HERE/$JAR" -packages com.example.thinmulti.app -pwd "$PWD_ARG" -Y >/dev/null

echo "== [thin-multi] 单独加密外置依赖并替换 =="
java -jar "$CFJAR" -file "$HERE/$LIB_JAR" -packages com.example.thinmulti.lib -pwd "$PWD_ARG" -Y >/dev/null
mv "${LIB_JAR%.jar}-encrypted.jar" "$LIB_JAR"

echo "== [thin-multi] 加密后运行 =="
ENC_OUT="$(java "-javaagent:$HERE/$ENC=-pwd $PWD_ARG" -Dloader.path=app/target/lib -jar "$ENC" | { grep '^RESULT=' || true; })"
echo "   $ENC_OUT"

if [ "$BASE" = "$EXPECT" ] && [ "$ENC_OUT" = "$EXPECT" ]; then
  echo "== [thin-multi] PASS =="
else
  echo "== [thin-multi] FAIL (base='$BASE' enc='$ENC_OUT' expect='$EXPECT') =="
  exit 1
fi
