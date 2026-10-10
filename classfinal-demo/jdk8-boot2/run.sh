#!/usr/bin/env bash
# jdk8-boot2：JDK 8 + Spring Boot 2.6.3
# 用 JDK 8 编译并运行，验证「老字节码（major 52）+ 老式嵌套 jar URL（jar:file:...）」。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
CFJAR="$REPO_ROOT/classfinal-fatjar/target/classfinal-fatjar-1.2.1.jar"
PWD_ARG=123456
EXPECT="RESULT=hello-WORLD-3"

# 目标 JDK：优先取环境变量 JAVA_HOME_8，否则用 macOS 的 java_home 定位
JAVA_HOME_TARGET="${JAVA_HOME_8:-$(/usr/libexec/java_home -v 1.8 2>/dev/null || true)}"
if [ -z "$JAVA_HOME_TARGET" ] || [ ! -x "$JAVA_HOME_TARGET/bin/java" ]; then
  echo "== [jdk8-boot2] SKIP：未找到可用的 JDK 8（可设 JAVA_HOME_8 指向 JDK 8 后重跑）=="
  exit 0
fi
[ -f "$CFJAR" ] || { echo "缺少 ${CFJAR}，请先在仓库根执行 mvn package -DskipTests"; exit 1; }

JAVA="$JAVA_HOME_TARGET/bin/java"
echo "== [jdk8-boot2] JDK: $JAVA_HOME_TARGET =="

cd "$HERE"
echo "== [jdk8-boot2] 构建（Spring Boot 2.6.3 / Java 8）=="
JAVA_HOME="$JAVA_HOME_TARGET" mvn -q clean package -DskipTests

JAR="target/classfinal-demo-jdk8-boot2-1.0.0.jar"
ENC="target/classfinal-demo-jdk8-boot2-1.0.0-encrypted.jar"

echo "== [jdk8-boot2] 基线运行 =="
BASE="$("$JAVA" -jar "$JAR" | { grep '^RESULT=' || true; })"
echo "   $BASE"

echo "== [jdk8-boot2] 加密 =="
"$JAVA" -jar "$CFJAR" -file "$HERE/$JAR" -packages com.example.jdk8boot2 -pwd "$PWD_ARG" -Y >/dev/null

echo "== [jdk8-boot2] 加密后运行 =="
ENC_OUT="$("$JAVA" "-javaagent:$HERE/$ENC=-pwd $PWD_ARG" -jar "$ENC" | { grep '^RESULT=' || true; })"
echo "   $ENC_OUT"

if [ "$BASE" = "$EXPECT" ] && [ "$ENC_OUT" = "$EXPECT" ]; then
  echo "== [jdk8-boot2] PASS =="
else
  echo "== [jdk8-boot2] FAIL (base='$BASE' enc='$ENC_OUT' expect='$EXPECT') =="
  exit 1
fi
