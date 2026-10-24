#!/usr/bin/env python3
"""静态校验：括号平衡 + 字符串/注释剥离。构建禁令期替代编译。

为什么需要它：构建被禁止时，语法级错误（缺大括号、字符串未闭合、
编辑工具写入的 HTML 实体）没有任何工具会报出来，只能靠文本校验兜住。
"""
import re
import sys
from pathlib import Path

TARGETS = [
    "ui/design/src/main/java/dev/praxis/ui/design/onboarding/PraxisPermissionPage.kt",
    "ui/design/src/main/java/dev/praxis/ui/design/onboarding/PraxisOnboardingFlow.kt",
    "core/data/src/main/kotlin/dev/praxis/core/data/settings/SettingsRepository.kt",
    "app/src/main/java/dev/praxis/MainActivity.kt",
]


def strip_noise(src: str) -> str:
    """剥掉块注释、行注释、字符串字面量。

    顺序很重要：先剥块注释，再剥字符串 —— 反过来的话 Kotlin 的三引号
    多行字符串里若出现块注释开头，会被当成注释开头，把后面整段代码吞掉。
    """
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    src = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', src)
    return src


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    bad = 0
    for rel in TARGETS:
        path = root / rel
        if not path.exists():
            print(f"MISSING {rel}")
            bad += 1
            continue
        raw = path.read_text(encoding="utf-8")
        clean = strip_noise(raw)
        braces = clean.count("{") - clean.count("}")
        parens = clean.count("(") - clean.count(")")
        brackets = clean.count("[") - clean.count("]")
        entities = len(re.findall(r"&(?:gt|lt|amp|quot);", raw))
        status = "OK" if braces == 0 and parens == 0 and brackets == 0 and entities == 0 else "BAD"
        if status == "BAD":
            bad += 1
        print(
            f"{status:3} {path.name:32} "
            f"braces={braces:+d} parens={parens:+d} brackets={brackets:+d} "
            f"entities={entities} lines={len(raw.splitlines())}"
        )
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
