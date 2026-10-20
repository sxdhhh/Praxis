#!/usr/bin/env python3
"""滚动终点审计：找出「用了 LazyColumn/verticalScroll 但没给浮层底栏留位」的页面。

## 为什么要这个脚本

底栏是**浮层**：它浮在内容之上，内容从它下方穿过（玻璃折射成立的前提）。
由此推出两条必须同时满足的约束：

  1. 内容区**容器**不能给底栏留 padding（否则内容被整体抬高，什么都不从底栏下经过）；
  2. 滚动**终点**必须抬高 `LocalScrollBottomLimit`（否则最后一项永远滚不到底栏上方，
     被压住 96dp 看不见）。

第 2 条漏过一整批：8 个设置页 + 会话列表页全都硬编码 `bottom = 24.dp`，
底栏占位是 96dp，于是每个页面最后一项被遮住约 72dp。
这种缺陷单测看不见（它是布局问题），lint 也不报（语法完全合法）。

## 判定口径

对 `src/main` 里每个出现 `LazyColum`/`verticalScroll` 的文件：
- 若文件里既没有 `LocalScrollBottomLimit`，也没有把它传给消费点（如
  `bottom = ... + scrollLimit`），则标记可疑。
- 白名单里的文件写清原因（例如本身就在底栏之上、或根本没有底栏）。

用法：
    python3 tools/audit_scroll_inset.py
    python3 tools/audit_scroll_inset.py --strict   # 有可疑文件时退出 1
"""

import glob
import io
import re
import sys

# 已知可用豁免：文件名 -> 原因
ALLOWED = {
    # 这些是「消费方」本身：实现就在它们内部读 LocalScrollBottomLimit。
    "AppScaffold.kt": "消费 LocalScrollBottomLimit 并下发给页面",
    "LowAppScaffold.kt": "低版本骨架，同样下发 LocalScrollBottomLimit",
    # 滚动组件实现：不自带底栏语境，由调用方决定 contentPadding。
    "AutoFollowScroll.kt": "纯跟随组件，不改 contentPadding",
    "SettingsList.kt": "设置页统一容器，内部已读 LocalScrollBottomLimit",
    # 非页面：ViewModel 里出现在注释中。
    "ChatViewModel.kt": "无 UI 布局",
    # 控件内部的局部滚动（卡片内容超长时内部可滚），不是页面级滚动容器，
    # 不涉及底栏让位：它被嵌在页面里，外层页面自己负责滚动终点。
    "MiuixCards.kt": "控件内局部滚动，不承担页面底部让位",
}

SCROLL = re.compile(r"\bLazyColumn\b|\bLazyRow\b|\bverticalScroll\b")


def main() -> int:
    strict = "--strict" in sys.argv

    files = [
        f
        for f in glob.glob("**/*.kt", recursive=True)
        if "/build/" not in f and not f.startswith("build/") and "/src/test/" not in f
    ]
    if not files:
        print("在仓库根目录运行本脚本（找不到 .kt 文件）")
        return 2

    suspicious = []
    checked = 0
    for f in files:
        text = io.open(f, encoding="utf-8").read()
        # 去掉注释后再找，避免 KDoc 里提到 LazyColumn 被误判。
        code = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
        code = "\n".join(
            (line if line.find("//") < 0 else line[: line.find("//")])
            for line in code.split("\n")
        )
        if not SCROLL.search(code):
            continue
        checked += 1
        name = f.rsplit("/", 1)[-1]
        if "LocalScrollBottomLimit" in code:
            continue
        if name in ALLOWED:
            continue
        suspicious.append((f, name))

    print("=== 滚动容器底部让位审计 ===")
    print("检查了 %d 个含滚动容器的文件" % checked)
    if not suspicious:
        print("（全部通过：每个滚动页面都消费了 LocalScrollBottomLimit）")
    else:
        print()
        print("可疑（有滚动容器但未读 LocalScrollBottomLimit）：")
        for f, name in suspicious:
            print("  %s" % f)
        print()
        print("修法：contentPadding.bottom 加上 `LocalScrollBottomLimit.current`，")
        print("   或在白名单里写明为什么这个页面不需要（例如它本身就在底栏之上）。")

    if strict and suspicious:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())