#!/usr/bin/env bash
# 一键验证：构建 classfinal 工具，然后依次运行四种打包形态的 demo。
# 每个 demo 都会：构建 -> 基线运行 -> 加密 -> 加密运行 -> 断言 RESULT。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/.." && pwd)"

echo "########## 构建 classfinal 工具 ##########"
( cd "$REPO_ROOT" && mvn -q -pl classfinal-core,classfinal-fatjar -am package -DskipTests )

for d in fat-single fat-multi thin-single thin-multi jdk8-boot2 jdk17-boot3 jdk21-boot3; do
  echo
  echo "########## demo: $d ##########"
  bash "$HERE/$d/run.sh"
done

echo
echo "########## 全部 demo 完成（缺 JDK 的项见上方 SKIP）##########"
