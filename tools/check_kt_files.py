#!/usr/bin/env python3
"""校验指定 .kt 文件的括号平衡（剥离注释与字符串后统计）。

用法：
    python3 tools/check_kt_files.py <file1.kt> <file2.kt> ...

为什么需要它：`check_kt_balance.py` 只盯一个硬编码的 4 文件清单（那是历次
踩坑后积累的「重点关注名单」），扫不到其它改动过的文件。构建禁令期间需要
一个能对任意文件做同一套校验的工具。

判据与 `check_kt_balance.py` 一致：
  - 先剥块注释（含 KDoc）、再剥行注释、再剥字符串（含转义）；
  - 统计 `{}` `()` `[]` 的差值，全 0 才算通过；
  - 扫 HTML 实体：Kotlin 源码里出现 `"` / `&#` / `&amp;` 说明
    某次编辑把转义字符写进了码面，必然编译失败。
"""
import sys

def strip(src: str) -> str:
    out = []
    i, n = 0, len(src)
    while i < n:
        if src.startswith('/**', i) or src.startswith('/*', i):
            j = src.find('*/', i + 2)
            end = (j + 2) if j >= 0 else n
            out.append(' ' * (end - i))
            i = end
            continue
        if src.startswith('//', i):
            j = src.find('\n', i)
            i = n if j < 0 else j
            continue
        if src[i] == '"':
            q = src[i]
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == q:
                    j += 1
                    break
                j += 1
            out.append(' ' * (j - i))
            i = j
            continue
        out.append(src[i])
        i += 1
    return ''.join(out)

def main(paths):
    bad = 0
    for p in paths:
        try:
            src = open(p, encoding='utf-8').read()
        except OSError as e:
            print(f'ERR {p}: {e}')
            bad += 1
            continue
        code = strip(src)
        b = code.count('{') - code.count('}')
        pr = code.count('(') - code.count(')')
        br = code.count('[') - code.count(']')
        ent = code.count('&#') + code.count('&amp;')
        ok = (b == 0 and pr == 0 and br == 0 and ent == 0)
        if not ok:
            bad += 1
        name = p.rsplit('/', 1)[-1]
        print(
            f"{'OK ' if ok else 'BAD'} {name:<24} "
            f"braces={b:+d} parens={pr:+d} brackets={br:+d} "
            f"entities={ent} lines={src.count(chr(10)) + 1}"
        )
    return 1 if bad else 0

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1:]))
