#!/usr/bin/env bash
# 装机冒烟：装 debug 包 → 启动 → 等冷启动 → 看崩溃。
#
# 踩过的坑：冷启动 5~7 秒内 pidof 为空是正常的（SplashScreen + Compose 首帧），
# 不要一看到空就判定崩溃；判据永远是 logcat 里有没有 FATAL EXCEPTION。
set -euo pipefail

cd "$(dirname "$0")/.."

APP_ID="${PRAXIS_APP_ID:-dev.praxis.debug}"
ACTIVITY="${PRAXIS_ACTIVITY:-dev.praxis/.MainActivity}"
APK="${PRAXIS_APK:-app/build/outputs/apk/debug/app-debug.apk}"
WAIT_SECONDS="${PRAXIS_SMOKE_WAIT:-8}"

if [[ ! -f "$APK" ]]; then
  echo "!! 找不到 $APK，先跑 scripts/ci.sh" >&2
  exit 1
fi

echo "==> 安装 $APK"
adb install -r "$APK"

echo "==> 清空 logcat"
adb logcat -c

echo "==> 启动 $ACTIVITY"
adb shell am start -n "$ACTIVITY"

echo "==> 等待 ${WAIT_SECONDS}s（冷启动）"
sleep "$WAIT_SECONDS"

echo "==> 进程状态"
if adb shell pidof "$APP_ID" >/dev/null 2>&1; then
  echo "   pid = $(adb shell pidof "$APP_ID")"
else
  echo "   进程未在运行（若上面的等待时间偏短，可能是首帧还没完成）"
fi

echo "==> 崩溃检查"
CRASHES="$(adb logcat -d -s AndroidRuntime:E || true)"
if grep -q "FATAL EXCEPTION" <<<"$CRASHES"; then
  echo "$CRASHES" >&2
  echo "!! 检测到 FATAL EXCEPTION" >&2
  exit 1
fi

echo "==> 无崩溃。手动确认清单："
echo "   1) 底栏 4 个 tab 可来回切换"
echo "   2) 进任意功能块子页后按返回键只退一层"
echo "   3) 系统暗色切换后界面立即跟随"
echo "   4) 旋转屏幕后仍停留在原子页（路由 Saver 生效）"