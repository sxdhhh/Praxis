#!/usr/bin/env python3
"""未接线审计：找出「定义了但生产代码从不引用」的公开声明。

## 为什么需要这个脚本

本项目已出现过三次同类事故，每次单测全绿：

  1. `MemoryWritePipeline` —— DI 里注册了，但从没被调用。界面能看，库里永远空。
  2. `NavBackStack.ensureSeeded` —— 有定义、有单测，生产零调用。栈被清空时
     `NavDisplay` 直接抛异常（启动白屏）。
  3. `CompactionPolicy` / 整层 kyant 液态玻璃（701 行）—— 全是零调用。

共同点：**单测只验证函数本身，验证不了「有没有人调它」**。
所以这类问题必须在构建门禁里用静态引用扫描兜住。

## 判定口径

- 只看 `src/main`（`src/test` 的引用不算「生产接线」）。
- 去掉注释再匹配，避免「只在 KDoc 里被提到」被算成已接线。
- 顶层声明（列 0 起始）才算，成员函数由所在类被调用来代表。
- `MainActivity`、`AppGlideModule` 这类由 Manifest / 注解处理器引用的，
  放进 EXPECTED_EXTERNAL 白名单。

## 用法

    python3 tools/audit_unwired.py            # 只报告
    python3 tools/audit_unwired.py --strict   # 有新增未接线项时退出码 1

`--strict` 用于 CI。白名单在 KNOWN_UNWIRED 里，每接一个线就删一条。
"""

import glob
import io
import re
import sys

# 由外部机制引用：Manifest 声明、注解处理器、序列化框架。
EXPECTED_EXTERNAL = {
    "MainActivity",
    "PraxisBuildConfig",
}

# 已知未接线项：`名字 -> 原因`。接线后必须从本表删除，
# 否则 --strict 会一直替它撒谎。
KNOWN_UNWIRED = {
    "AppListScaffold": "备用变体：列表页目前直接用 LazyColumn，未迁移到此骨架",
    "AppScrollScaffold": "备用变体：同上",
    "AppTabScaffold": "备用变体：同上",
    "AutoFollowScroll": "Lazy 版已接线，非 Lazy 版暂无调用点",
    "LiquidGlassButton": "kyant 液态玻璃整层未接线（底栏仍用 miuix 实色）",
    "rememberPageBackdropOrFallback": "同上：无任何代码提供 LocalKyantBackdrop",
    "rememberCombinedBackdrop": "同上",
    "DropdownItem": "控件库候选项，页面尚未使用",
    "MiuixCheckboxWidget": "控件库候选项",
    "MiuixMultiApkCheckboxWidget": "控件库候选项",
    "MiuixBlockingLoadingDialog": "控件库候选项",
    "MiuixBackButton": "控件库候选项（页面用 AppScaffold 内置返回位）",
    "WarningCard": "控件库候选项",
    "MiuixSettingsTipCard": "控件库候选项",
    "MiuixInstallerTipCard": "控件库候选项",
    "MiuixErrorTextBlock": "控件库候选项",
    "MiuixConfirmDialog": "控件库候选项",
    "MiuixIntNumberPickerWidget": "控件库候选项",
    "MiuixDropdown": "控件库候选项",
    "MiuixHintTextField": "控件库候选项",
    "ProgressButton": "控件库候选项",
}

DECL = re.compile(
    r"^(?:@\w+\s*)?(?:public\s+)?(?:internal\s+)?(?:suspend\s+)?(?:inline\s+)?"
    r"(?:fun|class|object|interface)\s+([A-Za-z_][A-Za-z0-9_]*)"
)


def strip_comments(text: str) -> str:
    """去掉行注释与块注释，避免「只在注释里被提到」被算成接线。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    out = []
    for line in text.split("\n"):
        i = line.find("//")
        out.append(line if i < 0 else line[:i])
    return "\n".join(out)


def main() -> int:
    strict = "--strict" in sys.argv

    files = [
        f
        for f in glob.glob("**/*.kt", recursive=True)
        if "/build/" not in f
        and not f.startswith("build/")
        and "/src/test/" not in f
    ]
    if not files:
        print("在仓库根目录运行本脚本（找不到 .kt 文件）")
        return 2

    sources = {f: strip_comments(io.open(f, encoding="utf-8").read()) for f in files}

    decls = []  # (name, file, line_no)
    for f in files:
        for no, line in enumerate(io.open(f, encoding="utf-8"), 1):
            if line[:1] in (" ", "\t", "\n", "/", "@", "*"):
                continue
            m = DECL.match(line)
            if m:
                decls.append((m.group(1), f, no))

    names = {d[0] for d in decls}
    occurrences = {n: 0 for n in names}
    for text in sources.values():
        for n in names:
            occurrences[n] += len(re.findall(r"\b" + re.escape(n) + r"\b", text))

    unwired = [
        (name, f, line)
        for name, f, line in decls
        # 只出现一次 = 只有定义本身，没有任何读取/调用。
        if occurrences[name] <= 1 and name not in EXPECTED_EXTERNAL
    ]

    print("=== 未接线公开声明 ===")
    if not unwired:
        print("（无）")
    new_items = []
    for name, f, line in sorted(unwired, key=lambda x: (x[1], x[2])):
        reason = KNOWN_UNWIRED.get(name)
        if reason is None:
            new_items.append((name, f, line))
            flag = "新增"
        else:
            flag = "已知"
        print("  [%s] %-38s %s:%d" % (flag, name, f, line))

    print()
    print("合计未接线 %d 项，其中已知 %d 项、新增 %d 项"
          % (len(unwired), len(unwired) - len(new_items), len(new_items)))

    # 已知项若已经不存在未接线，说明白名单过期了，要删掉，别让它替现实撒谎。
    stale = [n for n in KNOWN_UNWIRED if n not in {u[0] for u in unwired}]
    if stale:
        print()
        print("白名单过期（这些已接线或已删除，应从 KNOWN_UNWIRED 移除）：")
        for n in sorted(stale):
            print("  " + n)

    if strict and new_items:
        print()
        print("FAIL: 出现未登记的未接线声明。接线，或加进 KNOWN_UNWIRED 并写原因。")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())