#!/usr/bin/env bash
# jdk17-boot3：JDK 17 + Spring Boot 3.5.5
# 用 JDK 17 编译并运行，验证 class file major 61 + nested: 嵌套 jar URL。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
CFJAR="$REPO_ROOT/classfinal-fatjar/target/classfinal-fatjar-1.2.1.jar"
PWD_ARG=123456
EXPECT="RESULT=hello-WORLD-3"

JAVA_HOME_TARGET="${JAVA_HOME_17:-$(/usr/libexec/java_home -v 17 2>/dev/null || true)}"
if [ -z "$JAVA_HOME_TARGET" ] || [ ! -x "$JAVA_HOME_TARGET/bin/java" ]; then
  echo "== [jdk17-boot3] SKIP：未找到可用的 JDK 17（可设 JAVA_HOME_17 指向 JDK 17 后重跑）=="
  exit 0
fi
[ -f "$CFJAR" ] || { echo "缺少 ${CFJAR}，请先在仓库根执行 mvn package -DskipTests"; exit 1; }

JAVA="$JAVA_HOME_TARGET/bin/java"
echo "== [jdk17-boot3] JDK: $JAVA_HOME_TARGET =="

cd "$HERE"
echo "== [jdk17-boot3] 构建（Spring Boot 3.5.5 / Java 17）=="
JAVA_HOME="$JAVA_HOME_TARGET" mvn -q clean package -DskipTests

JAR="target/classfinal-demo-jdk17-boot3-1.0.0.jar"
ENC="target/classfinal-demo-jdk17-boot3-1.0.0-encrypted.jar"

echo "== [jdk17-boot3] 基线运行 =="
BASE="$("$JAVA" -jar "$JAR" | { grep '^RESULT=' || true; })"
echo "   $BASE"

echo "== [jdk17-boot3] 加密 =="
"$JAVA" -jar "$CFJAR" -file "$HERE/$JAR" -packages com.example.jdk17boot3 -pwd "$PWD_ARG" -Y >/dev/null

echo "== [jdk17-boot3] 加密后运行 =="
ENC_OUT="$("$JAVA" "-javaagent:$HERE/$ENC=-pwd $PWD_ARG" -jar "$ENC" | { grep '^RESULT=' || true; })"
echo "   $ENC_OUT"

if [ "$BASE" = "$EXPECT" ] && [ "$ENC_OUT" = "$EXPECT" ]; then
  echo "== [jdk17-boot3] PASS =="
else
  echo "== [jdk17-boot3] FAIL (base='$BASE' enc='$ENC_OUT' expect='$EXPECT') =="
  exit 1
fi
