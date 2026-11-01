#!/usr/bin/env python3
'''kt_lexer 的回归测试。

每个用例都对应仓库里真实发生过的误判，不是凭空构造的：

  - MarkdownParser.kt     字符字面量里的括号被当真括号
  - SessionTitle.kt       字符字面量 '"' 骗开字符串状态
  - CharacterEditRoute.kt 三引号原始字符串内嵌引号
  - OpenAiStreamAssemblerTest.kt / OpenAiToolCallProtocolTest.kt
                          三引号原始字符串内嵌转义引号 + 插值

跑法：python3 tools/test_kt_lexer.py
退出码：0 = 全过，1 = 有失败。
'''
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from kt_lexer import strip_noncode, balance

Q = chr(34)
SQ = chr(39)
D = chr(36)
TQ = Q * 3
NL = chr(10)
BS = chr(92)

BALANCED = (0, 0, 0, 0)


def code_at(src, needle, occurrence=1):
    '''needle 第 occurrence 次出现的位置，是否被判为代码。'''
    pos = -1
    start = 0
    for _ in range(occurrence):
        pos = src.index(needle, start)
        start = pos + 1
    return strip_noncode(src)[pos] == src[pos]


# ---------- 1. 平衡性 ----------

BALANCE_CASES = [
    # 真实来源：MarkdownParser.kt —— 一个 '(' 对两个 ')'，旧实现报 parens=-1
    ('字符字面量里的括号（奇数个）',
     "val a = '['" + NL + "val b = ']'" + NL
     + "val c = '('" + NL + "val d = ')'" + NL + "val e = ')'" + NL,
     BALANCED),

    # 真实来源：SessionTitle.kt —— '"' 后面还有代码
    ('字符字面量就是双引号',
     "val q = '" + Q + "'" + NL + "val s = " + Q + "x" + Q + NL + "fun f() { g() }" + NL,
     BALANCED),

    # 真实来源：CharacterEditRoute.kt
    ('三引号原始字符串内嵌引号',
     'val re = Regex(' + TQ + '[\\\\/:*?' + Q + '<>|]' + TQ + ')' + NL + 'fun f() { }' + NL,
     BALANCED),

    # 真实来源：OpenAiStreamAssemblerTest.kt
    ('原始字符串内嵌转义引号',
     'a.feed(toolChunk(' + TQ + '{"index":0,"arguments":"{\\\\"path\\\\":1}"}' + TQ + '))' + NL,
     BALANCED),

    # 真实来源：OpenAiToolCallProtocolTest.kt
    ('原始字符串内嵌引号 + 插值',
     'a.feed(payload(' + TQ + '{"choices":[{"delta":' + D + 'delta}]}' + TQ + '))' + NL,
     BALANCED),

    ('块注释嵌套',
     '/* 外层 /* 内层 */ 仍在注释里 */ fun f() { }' + NL,
     BALANCED),

    ('行注释里的括号与花括号',
     '// ((( ))) {{{ [[[' + NL + 'fun f() { }' + NL,
     BALANCED),

    ('转义引号的字符字面量',
     "val c = '" + BS + SQ + "'" + NL + "fun f() { }" + NL,
     BALANCED),

    ('字符串插值里的括号算代码',
     'val s = ' + Q + D + '{listOf(1).map { it }}' + Q + NL + 'fun f() { }' + NL,
     BALANCED),

    ('字符串里的括号不算代码',
     'val s = ' + Q + '{{{ ))) [[[' + Q + NL + 'fun f() { }' + NL,
     BALANCED),

    # 真错误必须被抓到
    ('缺右花括号',
     'fun f() {' + NL,
     (1, 0, 0, 0)),

    ('多一个右括号',
     'fun f() { g( }' + NL,
     (0, 1, 0, 0)),

    ('多一个左方括号',
     'val a = [1, 2' + NL,
     (0, 0, 1, 0)),

    ('码面里的 HTML 实体（&amp;）',
     'if (a &amp;&amp; b) { }' + NL,
     (0, 0, 0, 2)),

    ('码面里的 HTML 实体（&gt;）',
     'if (a &gt; b) { }' + NL,
     (0, 0, 0, 1)),

    ('码面里的数值实体',
     'val s = 1&#39;' + NL,
     (0, 0, 0, 1)),

    ('字符串里的实体是合法内容，不算事故',
     'val s = ' + Q + '&gt; &amp; &quot;' + Q + NL,
     BALANCED),
]


# ---------- 2. 掩码位置 ----------

MASK_CASES = [
    # 关键回归：'"' 之后的花括号必须仍被当成代码。
    # 旧实现在这里开了个假字符串，把后面全吞掉。
    ('引号字面量之后的代码仍算代码',
     "val q = '" + Q + "'" + NL + "fun f() { }" + NL,
     '{', True),

    ('原始字符串之后的代码仍算代码',
     'val re = Regex(' + TQ + 'a?' + Q + 'b' + TQ + ')' + NL + 'fun f() { }' + NL,
     '{', True),

    ('字符字面量里的括号不算代码',
     "val a = '['" + NL,
     '[', False),

    ('字符串里的花括号不算代码',
     'val s = ' + Q + '{}' + Q + NL,
     '{', False),

    ('注释里的花括号不算代码',
     '// {}' + NL,
     '{', False),

    ('嵌套块注释里的内容不算代码',
     '/* a /* b */ c */ fun f() { }' + NL,
     '{', True),
]


def main():
    fails = []

    for name, src, want in BALANCE_CASES:
        got = balance(src)
        if got != want:
            fails.append('%s: 期望 %s，实际 %s' % (name, want, got))

    for name, src, needle, want in MASK_CASES:
        got = code_at(src, needle)
        if got != want:
            fails.append('%s: %r 期望代码=%s，实际=%s' % (name, needle, want, got))

    # 等长性：strip_noncode 的下标记必须与原文对齐
    for name, src, _ in BALANCE_CASES:
        if len(strip_noncode(src)) != len(src):
            fails.append('%s: strip_noncode 长度与原文不一致' % name)

    total = len(BALANCE_CASES) + len(MASK_CASES)
    if fails:
        for f in fails:
            print('FAIL ' + f)
        print('%d/%d 通过，%d 失败' % (total - len(fails), total, len(fails)))
        return 1
    print('%d/%d 全部通过' % (total, total))
    return 0


if __name__ == '__main__':
    sys.exit(main())
