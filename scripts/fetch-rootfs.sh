#!/usr/bin/env bash
#
# 下载内置 rootfs 到 app/src/main/assets/rootfs/
#
# 用法：
#   ./scripts/fetch-rootfs.sh            # 两个都下
#   ./scripts/fetch-rootfs.sh ubuntu     # 只下 Ubuntu
#   ./scripts/fetch-rootfs.sh kali       # 只下 Kali
#
# ## 为什么用脚本而不是让 Gradle 直接下载
#
# 1. **构建可重复**：`./gradlew assembleRelease` 不应该依赖网络。
#    rootfs 是「发布前一次性准备好」的输入，和 `libproot.so` 同类。
# 2. **产物不该进 git**：Kali 131 MB + Ubuntu 30 MB，进 git 会让仓库
#    膨胀到 160 MB+，且每次换版本都要重写历史。已在 .gitignore 排除。
# 3. **CI 要能缓存**：单独一条脚本，CI 里可以按文件名做 cache key。
#
# ## 为什么要校验大小
#
# 两个上游地址都可能是「HTTP 200 但返回一个 HTML 错误页」。
# 只检查 curl 退出码不够 —— 那种响应也是 0 退出。
# 所以下完必须比对 Content-Length，并且检查压缩魔数。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DEST="$REPO_ROOT/app/src/main/assets/rootfs"

# ── 资产清单 ─────────────────────────────────────────────────────
# 文件名必须与 LinuxDistro.builtInAsset 完全一致：
#   Ubuntu → "rootfs/ubuntu-noble-arm64.tar.gz"
#   Kali   → "rootfs/kali-nethunter-minimal-arm64.tar.xz"
# 后缀也要一致，因为 app/build.gradle.kts 的 noCompress 按后缀匹配。

UBUNTU_FILE="ubuntu-noble-arm64.tar.gz"
UBUNTU_URLS=(
  "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"
  "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"
)
# 实测 29,865,086 字节（2026-10-22）。允许 ±2% 的漂移。
UBUNTU_EXPECT=29865086

KALI_FILE="kali-nethunter-minimal-arm64.tar.xz"
KALI_URLS=(
  "https://kali.download/nethunter-images/current/rootfs/kali-nethunter-rootfs-minimal-arm64.tar.xz"
  "https://mirrors.tuna.tsinghua.edu.cn/kali/nethunter-images/current/rootfs/kali-nethunter-rootfs-minimal-arm64.tar.xz"
)
# 实测 137,313,840 字节（2026-10-22）。
KALI_EXPECT=137313840

mkdir -p "$DEST"

# 下载一个文件：依次试候选地址，成功后校验大小与魔数。
# 参数：目标名 / 期望字节 / 魔数(gzip|xz) / 候选 URL 列表
fetch_one() {
  local name="$1"; shift
  local expect="$1"; shift
  local magic="$1"; shift
  local urls=("$@")
  local out="$DEST/$name"

  if [[ -f "$out" ]]; then
    local have
    have=$(stat -c%s "$out" 2>/dev/null || echo 0)
    if [[ "$have" -gt 0 ]] && size_ok "$have" "$expect"; then
      echo "✓ 已存在且大小正确：$name ($(human "$have"))"
      return 0
    fi
    echo "· 已有文件大小不符，重新下载：$name"
    rm -f "$out"
  fi

  for url in "${urls[@]}"; do
    echo "↓ $url"
    # -f：HTTP 错误码直接失败（否则 404 也会把 HTML 存成文件）
    # -L：跟随重定向；--retry：抖动网络重试
    if ! curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 \
         -o "$out.part" "$url"; then
      echo "  ✗ 下载失败，换下一个地址"
      rm -f "$out.part"
      continue
    fi

    local have
    have=$(stat -c%s "$out.part" 2>/dev/null || echo 0)
    if ! size_ok "$have" "$expect"; then
      echo "  ✗ 大小异常：$have 字节（期望约 $expect），可能是错误页"
      rm -f "$out.part"
      continue
    fi
    if ! check_magic "$out.part" "$magic"; then
      echo "  ✗ 不是有效的 $magic 文件（魔数不符）"
      rm -f "$out.part"
      continue
    fi

    mv "$out.part" "$out"
    echo "✓ $name ($(human "$have"))"
    return 0
  done

  echo "✗ $name 所有地址都失败"
  return 1
}

# 允许 ±2% 漂移：上游重打包会让字节数略变，但不会是数量级差异。
size_ok() {
  local have="$1" expect="$2"
  local lo=$(( expect * 98 / 100 ))
  local hi=$(( expect * 102 / 100 ))
  [[ "$have" -ge "$lo" && "$have" -le "$hi" ]]
}

human() {
  local b="$1"
  if [[ "$b" -ge 1048576 ]]; then echo "$(( b / 1048576 )) MB"
  elif [[ "$b" -ge 1024 ]]; then echo "$(( b / 1024 )) KB"
  else echo "$b B"; fi
}

# 检查压缩魔数：gzip "1f 8b"，xz "fd 37 7a 58 5a 00"。
# 这是「不是错误页」的第二道防线 —— HTML 页面也能通过大小检查（偶尔）。
check_magic() {
  local f="$1" kind="$2"
  local hex
  hex=$(od -An -tx1 -N6 "$f" | tr -d ' \n')
  case "$kind" in
    gzip) [[ "${hex:0:4}" == "1f8b" ]] ;;
    xz)   [[ "${hex:0:12}" == "fd377a585a00" ]] ;;
    *)    return 1 ;;
  esac
}

# ── 主流程 ───────────────────────────────────────────────────────
TARGET="${1:-all}"
rc=0

case "$TARGET" in
  ubuntu)
    fetch_one "$UBUNTU_FILE" "$UBUNTU_EXPECT" gzip "${UBUNTU_URLS[@]}" || rc=1
    ;;
  kali)
    fetch_one "$KALI_FILE" "$KALI_EXPECT" xz "${KALI_URLS[@]}" || rc=1
    ;;
  all)
    fetch_one "$UBUNTU_FILE" "$UBUNTU_EXPECT" gzip "${UBUNTU_URLS[@]}" || rc=1
    fetch_one "$KALI_FILE" "$KALI_EXPECT" xz "${KALI_URLS[@]}" || rc=1
    ;;
  *)
    echo "用法：$0 [all|ubuntu|kali]"
    exit 2
    ;;
esac

echo
echo "assets 目录占用：$(du -sh "$DEST" 2>/dev/null | cut -f1)"
echo "注意：这两个文件不会进 git（见 .gitignore），CI 需要单独缓存。"
exit "$rc"