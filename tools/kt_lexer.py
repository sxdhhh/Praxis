#!/usr/bin/env python3
'''Kotlin 源码词法剥离 —— 仓库里做括号平衡校验的唯一实现。

用法：

    from kt_lexer import strip_noncode
    code = strip_noncode(src)      # 等长；非代码字符全部变成空格

## 为什么要有这个模块

构建禁令期间没有编译器，括号平衡是唯一的语法兜底。最初的实现只认
双引号字符串和块注释，于是下面三类被误判成语法错误 —— 它们全都是
仓库里的真实代码，不是构造出来的例子：

  1. 字符字面量里的括号

         if (c == '[') { ... }

     那个方括号被当成真括号计数。MarkdownParser.kt 因此报 parens=-1，
     而多算的 5 个字符正好是 '[' ']' '(' ')' ')'。

  2. 字符字面量里的引号

         .trim('"', ...)

     其中的双引号骗开一个「字符串」状态，一路吞到下一个引号。
     SessionTitle.kt 被从第 114 行吞到第 130 行的 Regex 调用。

  3. 三引号原始字符串

         Regex("""[\\/:*?"<>|]""")
         payload("""{"index":0,"arguments":"{\\"path\\":...}""")

     开头的两个引号被当成一个空字符串，后面整段错位。
     CharacterEditRoute.kt 和两个 OpenAi*Test.kt 都是这个原因。

## 处理规则

  - 行注释到行尾；
  - 块注释，按 Kotlin 语义支持嵌套；
  - 普通字符串，支持反斜杠转义；插值表达式内的括号算代码；
  - 字符字面量，含转义与「字面量本身就是引号」的写法；
  - 三引号原始字符串，内部引号不结束字符串；插值表达式内的括号算代码。

## 已知边界

原始字符串以引号收尾的极端写法（六个引号连排）按第一个三引号收尾。
仓库里目前没有这种写法。
'''

import re

# 与 fix_html_entities.ENTITIES 对齐：这些实体出现在代码区就是编辑事故。
# 数值实体一并覆盖，避免只认得 &#39; 这一种写法。
ENTITY_RE = re.compile(r'&(?:amp|lt|gt|quot|apos);|&#(?:x[0-9a-fA-F]+|[0-9]+);')

DOLLAR = chr(36)
QUOTE = chr(34)
SQ = chr(39)
BS = chr(92)
NL = chr(10)
LBRACE = chr(123)
RBRACE = chr(125)
TRIPLE = QUOTE * 3


def _scan_char(src, i):
    """i 指向开引号，返回闭合引号之后的下标。

    未闭合时只吃掉开引号，不吞后面的代码 —— 那样至少还能报出真正的错。
    """
    n = len(src)
    j = i + 1
    while j < n:
        c = src[j]
        if c == BS:
            j += 2
            continue
        if c == SQ:
            return j + 1
        if c == NL:
            break
        j += 1
    return i + 1


def _lex(src, i, out, stop_brace):
    """从 i 扫描。

    stop_brace 为 False：扫到文件末尾。
    stop_brace 为 True：在「当前这一层」的右花括号处停下并把它写进 out，
    用于字符串里的插值表达式。
    """
    n = len(src)
    depth = 0
    while i < n:
        c = src[i]

        if c == '/' and i + 1 < n and src[i + 1] == '/':
            j = src.find(NL, i)
            i = n if j < 0 else j
            continue

        if c == '/' and i + 1 < n and src[i + 1] == '*':
            d = 1
            j = i + 2
            while j < n and d:
                if src.startswith('/*', j):
                    d += 1
                    j += 2
                elif src.startswith('*/', j):
                    d -= 1
                    j += 2
                else:
                    j += 1
            i = j
            continue

        if src.startswith(TRIPLE, i):
            j = i + 3
            while j < n:
                if src.startswith(TRIPLE, j):
                    j += 3
                    break
                if src[j] == DOLLAR and j + 1 < n and src[j + 1] == LBRACE:
                    out[j + 1] = LBRACE
                    j = _lex(src, j + 2, out, True)
                    continue
                j += 1
            i = j
            continue

        if c == QUOTE:
            j = i + 1
            while j < n:
                d = src[j]
                if d == BS:
                    j += 2
                    continue
                if d == QUOTE:
                    j += 1
                    break
                if d == NL:
                    break
                if d == DOLLAR and j + 1 < n and src[j + 1] == LBRACE:
                    out[j + 1] = LBRACE
                    j = _lex(src, j + 2, out, True)
                    continue
                j += 1
            i = j
            continue

        if c == SQ:
            i = _scan_char(src, i)
            continue

        if stop_brace:
            if c == LBRACE:
                depth += 1
            elif c == RBRACE:
                if depth == 0:
                    out[i] = c
                    return i + 1
                depth -= 1

        out[i] = c
        i += 1

    return i


def strip_noncode(src):
    """返回与 src 等长的文本：字符串、字符字面量、注释的内容变成空格。

    代码字符原样保留，所以对返回值统计括号即可判断源码是否配平，
    且下标记与原文件对齐。
    """
    out = [' '] * len(src)
    _lex(src, 0, out, False)
    return ''.join(out)


def balance(src):
    """返回 (braces, parens, brackets, entities) 四个统计值。

    entities 只在代码区统计：字符串或注释里出现实体是合法内容，不算事故。
    这一点比 check_kt_balance.py 原来的实现更准 —— 它是在原文上扫的，
    会把字符串里的 &amp; 也算成错。
    """
    code = strip_noncode(src)
    return (
        code.count('{') - code.count('}'),
        code.count('(') - code.count(')'),
        code.count('[') - code.count(']'),
        len(ENTITY_RE.findall(code)),
    )
