#!/usr/bin/env bash
# Praxis CI 主流程：lint + 单测 + debug 构建，三者并行且互不阻塞。
#
# 设计取舍：
#  - 用 --continue 让三类检查都跑完，一次 CI 就把问题全暴露，不要一个个试。
#  - 不用 --no-daemon：本地反复跑时 daemon 能省一大截配置时间；
#    在一次性容器里跑请自行加 --no-daemon。
#  - lint 不开基线：现有告警是 warning，abortOnError 只拦 error。
set -euo pipefail

cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-arm64}"
export PATH="$JAVA_HOME/bin:$PATH"

GRADLE_ARGS=(--parallel --continue --stacktrace)
if [[ "${PRAXIS_CI_OFFLINE:-0}" == "1" ]]; then
  GRADLE_ARGS+=(--offline)
fi
if [[ "${PRAXIS_CI_NO_DAEMON:-0}" == "1" ]]; then
  GRADLE_ARGS+=(--no-daemon)
fi

echo "==> Gradle: ${GRADLE_ARGS[*]}"
echo "==> JAVA_HOME=$JAVA_HOME"

./gradlew "${GRADLE_ARGS[@]}" \
  :app:lintDebug \
  :app:testDebugUnitTest \
  :app:assembleDebug

echo
echo "==> 产物"
ls -lh app/build/outputs/apk/debug/*.apk || true
echo "==> 测试报告"
echo "   app/build/reports/tests/testDebugUnitTest/index.html"
echo "==> Lint 报告"
echo "   app/build/reports/lint-results-debug.html"

# release 构建单独一段：签名材料缺失时 assembleRelease 只产出未签名包，
# 所以把它放在独立脚本入口（ci-release.sh）里，不拖累日常 CI。
