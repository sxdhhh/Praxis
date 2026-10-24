#!/usr/bin/env python3
"""检测 Kotlin 源码里「注释中的 `/*`」—— 会让块注释意外嵌套。

## 为什么必须单独查这一项

Kotlin 的块注释**可以嵌套**（与 Java 不同）：

```kotlin
/** 说明里提到 `sources.list.d/*` 就会出事 */
fun foo() {}
```

上面的 `/*`（来自 `d/*`）开启了一个**新的嵌套层**，于是这个 KDoc 需要
两个 `*/` 才能闭合。少一个就把后面的代码全吞进注释里。

## 症状为什么难查

编译错误出现在**很远的地方**，而且完全不指向真凶：

```
LinuxDiskUsage.kt:50:31 Unresolved reference 'humanBytes'.
LinuxDiskUsage.kt:51:6  Syntax error: Missing '}.
LinuxDiskUsage.kt:264:1 Syntax error: Unclosed comment.
```

`humanBytes` 明明定义在同文件里 —— 因为它的定义被吞进了注释。
「Missing `}`」也是同一原因（后面的 `}` 也都被吞了）。

真实案例（2026-10-24 构建）：三个文件各有一处，全是解释性文字里的
`/usr/bin/*`、`sources.list.d/*` 这种通配符写法。

## 判定规则

逐行扫描，跳过字符串字面量后统计注释内的 `/*`：

- 行注释 `//` 里出现 `/*` → **报告**（Kotlin 仍会开启嵌套）；
- 块注释里出现 `/*` → **报告**；
- 文件末尾的 `/*` / `*/` 总数不平衡 → **报告**。

字符串里的 `/*`（`appendLine("rm -f /etc/apt/sources.list")` 之类）不算：
它在字符串字面量内，编译器不把它当注释。所以要先剥字符串。

用法：python3 tools/check_nested_comments.py [根目录]
退出码：0 = 干净，1 = 发现问题。
"""
import re
import sys
from pathlib import Path

# 剥掉字符串字面量（含三引号），保留换行以维持行号。
# 先把三引号替换掉，再处理单/双引号。
_TRIPLE = re.compile(r'"""(?:\\.|[^\\])*?"""|"""[\s\S]*?"""', re.S)
_SINGLE = re.compile(r'"(?:\\.|[^"\\\n])*"')
_CHAR = re.compile(r"'(?:\\.|[^'\\\n]){1,2}'")


def strip_strings(text: str) -> str:
    """把字符串/字符字面量的内容替换为等长空白，保留换行。"""

    def blank(m: re.Match) -> str:
        return re.sub(r'[^\n]', ' ', m.group(0))

    out = _TRIPLE.sub(blank, text)
    out = _SINGLE.sub(blank, out)
    out = _CHAR.sub(blank, out)
    return out


def check_file(path: Path) -> list[str]:
    raw = path.read_text(encoding="utf-8", errors="replace")
    code = strip_strings(raw)

    problems = []
    depth = 0
    line_no = 1
    i = 0
    n = len(code)
    while i < n:
        ch = code[i]
        if ch == "\n":
            line_no += 1
            i += 1
            continue
        # ★ 必须先判 `*/` 再判 `/*`。
        #
        # 反过来写会出误报：遇到 `/** 说明 */` 这种**单行** KDoc 时，
        # 先看到开头的 `/*` 进深度 1，接着在 `*/` 处「先判 /*」的分支
        # 会把 `*/` 末尾的 `/` 与下一个字符组成 `/*`（若后面紧跟 `/` 或 `*`），
        # 于是一次性把一整段合法注释判成嵌套。
        #
        # 正确顺序：`*/` 是闭合、`/*` 是开启。两者都以 `/` 或 `*` 开头，
        # 不存在同时匹配的情况，所以先判 `*/` 不会漏掉 `/*`。
        if ch == "*" and i + 1 < n and code[i + 1] == "/":
            if depth > 0:
                depth -= 1
            i += 2
            continue
        # 行注释：只在**块注释之外**才有意义。
        #
        # ## 这里踩过自己的坑（必须记住）
        #
        # 第一版没有 `depth == 0` 这个条件，于是把块注释里的
        # `https://api.x.com` 中的 `//` 当成行注释起点，一路吞到行尾 ——
        # **连该行末尾的 `*/` 一起吞掉**，深度就永远停在 1，
        # 后续每个合法 KDoc 都被判成「嵌套」。
        #
        # 而 Kotlin 编译器在块注释内只认 `/*` 与 `*/`，`//` 是普通字符。
        # 所以 `https://` 这种 URL 在 KDoc 里完全合法。
        if depth == 0 and ch == "/" and i + 1 < n and code[i + 1] == "/":
            end = code.find("\n", i)
            if end < 0:
                end = n
            body = code[i:end]
            if "/*" in body:
                problems.append(
                    f"{path}:{line_no}: 行注释里出现 '/*'，会开启嵌套块注释"
                    f" —— {raw.splitlines()[line_no - 1].strip()[:80]}"
                )
            i = end
            continue
        # 块注释开始
        if ch == "/" and i + 1 < n and code[i + 1] == "*":
            if depth > 0:
                problems.append(
                    f"{path}:{line_no}: 块注释里出现 '/*'（嵌套层数 {depth}）"
                    f" —— {raw.splitlines()[line_no - 1].strip()[:80]}"
                )
            depth += 1
            i += 2
            continue
        i += 1

    if depth != 0:
        problems.append(f"{path}: 文件末尾块注释未闭合（残留层数 {depth}）")
    return problems


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    total = 0
    for path in sorted(root.rglob("*.kt")):
        if "build/" in path.as_posix():
            continue
        for problem in check_file(path):
            print(problem)
            total += 1
    if total:
        print(f"\n发现问题 {total} 处。修法：把注释里的 '/*' 改写成不含 "
              f"斜杠星号的说法（如 `sources.list.d 目录`）。")
        return 1
    print("干净：没有注释内的 '/*'。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
