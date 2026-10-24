#!/usr/bin/env python3
"""还原被误写成 HTML 实体的 Kotlin 代码。

## 为什么会有这个脚本

编辑工具在写文件时会把代码里的 `&&`、`>`、`<`、`"` 转义成
`&amp;&amp;`、`&gt;`、`&lt;`、`"`（像处理 HTML 一样）。Kotlin 编译器
不认识这些实体，报的错还特别误导：

```
e: .../LinuxEnvViewModel.kt:524:60 Unresolved reference 'gt'.
e: .../TerminalStorageSection.kt:520:57 Syntax error: Expecting an element.
```

`&gt;` 被词法拆成 `&` `gt` `;`，于是「`gt` 未解析」「缺少元素」。

## 为什么不能无脑全局替换

字符串字面量里可能合法地包含 `&gt;` 这样的文本（比如要往网页里写转义字符）。
所以只在**代码区**替换：先剥掉字符串/字符字面量与注释，记录哪些位置属于代码，
再按位置替换。

## 用法

    python3 tools/fix_html_entities.py            # 扫描并修复整个仓库
    python3 tools/fix_html_entities.py --dry-run  # 只报告，不改

退出码：0 = 没有残留（或已全部修好），1 = dry-run 发现残留。
"""
import sys
from pathlib import Path

ENTITIES = {
    "&gt;": ">",
    "&lt;": "<",
    "&amp;": "&",
    """: '"',
    "&#39;": "'",
}


def blank_literals_and_comments(text: str) -> str:
    """把字符串/字符字面量与注释的内容替换为空格（等长，保留换行）。

    返回的是「只有代码」的影子文本，用来判断某个位置是否属于代码。
    """
    out = list(text)
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        # 行注释
        if ch == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                out[i] = " "
                i += 1
            continue
        # 块注释（Kotlin 支持嵌套）
        if ch == "/" and i + 1 < n and text[i + 1] == "*":
            depth = 0
            while i < n:
                if text.startswith("/*", i):
                    depth += 1
                    out[i] = out[i + 1] = " "
                    i += 2
                    continue
                if text.startswith("*/", i):
                    depth -= 1
                    out[i] = out[i + 1] = " "
                    i += 2
                    if depth == 0:
                        break
                    continue
                if text[i] != "\n":
                    out[i] = " "
                i += 1
            continue
        # 三引号字符串
        if text.startswith('"""', i):
            out[i] = out[i + 1] = out[i + 2] = " "
            i += 3
            while i < n and not text.startswith('"""', i):
                if text[i] != "\n":
                    out[i] = " "
                i += 1
            if i < n:
                out[i] = out[i + 1] = out[i + 2] = " "
                i += 3
            continue
        # 普通字符串
        if ch == '"':
            out[i] = " "
            i += 1
            while i < n:
                if text[i] == "\\":
                    out[i] = " "
                    if i + 1 < n and text[i + 1] != "\n":
                        out[i + 1] = " "
                    i += 2
                    continue
                if text[i] == '"':
                    out[i] = " "
                    i += 1
                    break
                if text[i] != "\n":
                    out[i] = " "
                i += 1
            continue
        # 字符字面量
        if ch == "'":
            out[i] = " "
            i += 1
            while i < n:
                if text[i] == "\\":
                    out[i] = " "
                    if i + 1 < n:
                        out[i + 1] = " "
                    i += 2
                    continue
                if text[i] == "'":
                    out[i] = " "
                    i += 1
                    break
                if text[i] != "\n":
                    out[i] = " "
                i += 1
            continue
        i += 1
    return "".join(out)


def fix_file(path: Path, dry_run: bool) -> int:
    text = path.read_text(encoding="utf-8")
    if not any(e in text for e in ENTITIES):
        return 0

    code_mask = blank_literals_and_comments(text)
    chars = list(text)
    changed = 0

    for entity, replacement in ENTITIES.items():
        start = 0
        while True:
            idx = text.find(entity, start)
            if idx < 0:
                break
            start = idx + len(entity)
            # 只在「实体完全位于代码区」时替换，避免改动字符串内容。
            if code_mask[idx:idx + len(entity)] != entity:
                continue
            for k in range(len(entity)):
                chars[idx + k] = replacement if k == 0 else ""
            changed += 1

    if changed:
        line_no = text[: text.find(entity if changed else "")].count("\n") + 1
        print(f"{path}: 修复 {changed} 处 HTML 实体")
        if not dry_run:
            # chars 里被清空的位置要去掉（replacement 只写在第 0 个位置）
            path.write_text("".join(chars), encoding="utf-8")
    return changed


def main() -> int:
    dry_run = "--dry-run" in sys.argv
    root = next((a for a in sys.argv[1:] if not a.startswith("--")), ".")
    total = 0
    for path in sorted(Path(root).rglob("*.kt")):
        if "/build/" in path.as_posix():
            continue
        total += fix_file(path, dry_run)
    if total == 0:
        print("干净：没有 HTML 实体残留。")
        return 0
    print(f"\n合计 {total} 处" + ("（dry-run，未改动）" if dry_run else "（已修复）"))
    return 1 if dry_run else 0


if __name__ == "__main__":
    sys.exit(main())