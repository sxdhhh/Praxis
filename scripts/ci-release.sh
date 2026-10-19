#!/usr/bin/env bash
# release 构建：签名材料全部从环境变量读，仓库里不留任何 jks / 口令。
#
# 前置（一次性）：
#   export PRAXIS_STORE_PASS='...' PRAXIS_KEY_PASS='...'
#   mkdir -p /root/.keys
#   keytool -genkeypair -v -keystore /root/.keys/praxis-release.jks \
#     -alias praxis -keyalg RSA -keysize 4096 -validity 10000 \
#     -storepass "$PRAXIS_STORE_PASS" -keypass "$PRAXIS_KEY_PASS" \
#     -dname "CN=Praxis, O=sxd91"
#
# 用法：
#   PRAXIS_KEYSTORE=/root/.keys/praxis-release.jks bash scripts/ci-release.sh
set -euo pipefail

cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-arm64}"
export PATH="$JAVA_HOME/bin:$PATH"

: "${PRAXIS_KEYSTORE:?需要 PRAXIS_KEYSTORE 指向 keystore 文件}"
: "${PRAXIS_STORE_PASS:?需要 PRAXIS_STORE_PASS}"
: "${PRAXIS_KEY_PASS:?需要 PRAXIS_KEY_PASS}"
export PRAXIS_KEY_ALIAS="${PRAXIS_KEY_ALIAS:-praxis}"

echo "==> assembleRelease (keystore=$PRAXIS_KEYSTORE, alias=$PRAXIS_KEY_ALIAS)"
./gradlew --parallel :app:assembleRelease

APK="app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$APK" ]]; then
  echo "!! 未找到 $APK" >&2
  exit 1
fi

APKSIGNER="/opt/android-sdk/build-tools/36.0.0/apksigner"
if [[ -x "$APKSIGNER" ]]; then
  echo "==> 验签"
  "$APKSIGNER" verify --print-certs "$APK"
else
  echo "!! 未找到 apksigner，跳过验签（apk 仍已产出）" >&2
fi

ls -lh "$APK"