#!/usr/bin/env python3
'''校验指定 .kt 文件的括号平衡（剥离注释与字面量后统计）。

用法：
    python3 tools/check_kt_files.py <file1.kt> <file2.kt> ...

为什么需要它：check_kt_balance.py 只盯一个硬编码的重点名单，扫不到其它
改动过的文件。构建禁令期间需要对任意文件做同一套校验。

剥离逻辑在 kt_lexer.py —— 那边修掉了字符字面量与三引号原始字符串的
长期误判，详见该模块的说明与 test_kt_lexer.py 的用例。

判据：{} () [] 三个差值全为 0，且码面里没有 HTML 实体残留。
'''
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from kt_lexer import strip_noncode


def main(paths):
    bad = 0
    for p in paths:
        try:
            src = open(p, encoding='utf-8').read()
        except OSError as e:
            print('ERR %s: %s' % (p, e))
            bad += 1
            continue
        code = strip_noncode(src)
        b = code.count('{') - code.count('}')
        pr = code.count('(') - code.count(')')
        br = code.count('[') - code.count(']')
        ent = code.count('&#') + code.count('&amp;')
        ok = (b == 0 and pr == 0 and br == 0 and ent == 0)
        if not ok:
            bad += 1
        # 两个平台的路径分隔符都要处理，否则 Windows 上打的是全路径
        name = p.replace('\\', '/').rsplit('/', 1)[-1]
        print(
            '%s %-24s braces=%+d parens=%+d brackets=%+d entities=%d lines=%d'
            % ('OK ' if ok else 'BAD', name, b, pr, br, ent, src.count(chr(10)) + 1)
        )
    return 1 if bad else 0


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1:]))
