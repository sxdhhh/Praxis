#!/usr/bin/env python3
'''校验 Kotlin 源码的字面量闭合：字符串、字符字面量、三引号原始字符串。

用法：
    python3 tools/check_kt_literals.py <file1.kt> [file2.kt ...]
    python3 tools/check_kt_literals.py --all          # 扫全仓

## 为什么还需要这个（check_kt_files.py 已经查括号了）

括号平衡查不出「字符串没闭合」。比如写成了

    val s = "没有闭合的字符串

或者三引号少了一个。这类错误括号完全平衡（check_kt_files.py 报 OK），
Kotlin 编译器则一定拒绝。构建禁令下没有编译器，只能靠字面量扫描兜。

## ⚠️ 它能查什么、查不到什么（务必分清）

**能查**：引号数量不配对导致的未闭合 —— 单引号、双引号、三引号都覆盖。

**查不到**：引号数量配对、但内容被误切分的情况。例如

    "ProotCommand 里的 filterNot 用 "apt-get update" 字面量过滤，" +

这里有 4 个引号，偶数，机器视角下就是「字符串 + 标识符 + 字符串」，
词法上闭得上。错误要等解析器发现 `apt-get` 不是合法标识符才暴露。
本工具对这种写法无能为力（实测确认为假阴性）。

所以它是一条**补充**防线，不是编译器的替代品。写内层引号时仍然要自己用
反引号或转义，例如：

    "filterNot 用 `apt-get update` 字面量过滤"
    "filterNot 用 \"apt-get update\" 字面量过滤"

## 必须处理字符串插值

    fun f() = "支持 ${list.joinToString(", ")} 个"

插值里的引号属于代码，不是外层字符串的结束。早期版本不处理插值，
在 PluginManifest.kt 上报了假阳性 —— 那个文件是完全正确的。

## 退出码
    0 = 全部闭合；1 = 有问题；2 = 用法错误。
'''
import os
import sys

# 复用 kt_lexer 的剥离实现，与 check_kt_files / check_kt_balance 同一口径。
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from kt_lexer import strip_noncode  # noqa: E402

Q = chr(34)
SQ = chr(39)
BS = chr(92)
NL = chr(10)
D = chr(36)
LB = chr(123)
RB = chr(125)


def _scan_string(src, i):
    """i 指向开引号；返回 (结束下标, 是否闭合)。处理 ${...} 插值。"""
    n = len(src)
    j = i + 1
    while j < n:
        c = src[j]
        if c == BS:
            j += 2
            continue
        if c == Q:
            return j + 1, True
        if c == NL:
            return j, False
        if c == D and j + 1 < n and src[j + 1] == LB:
            j = _scan_interp(src, j + 2)
            continue
        j += 1
    return j, False


def _scan_interp(src, i):
    """i 指向 ${ 之后；返回匹配 } 之后的下标。"""
    n = len(src)
    depth = 1
    while i < n:
        c = src[i]
        if c == LB:
            depth += 1
            i += 1
            continue
        if c == RB:
            depth -= 1
            i += 1
            if depth == 0:
                return i
            continue
        if c == Q:
            i, _ = _scan_string(src, i)
            continue
        if c == SQ:
            k = i + 1
            while k < n:
                if src[k] == BS:
                    k += 2
                    continue
                if src[k] == SQ:
                    k += 1
                    break
                if src[k] == NL:
                    break
                k += 1
            i = k
            continue
        i += 1
    return i


def check(src):
    """返回 [(行号, 说明)]，空列表表示通过。"""
    i, n = 0, len(src)
    errors = []
    while i < n:
        c = src[i]

        # 行注释
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            j = src.find(NL, i)
            i = n if j < 0 else j
            continue

        # 块注释（Kotlin 可嵌套）
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            d, j = 1, i + 2
            while j < n and d:
                if src.startswith("/*", j):
                    d += 1
                    j += 2
                elif src.startswith("*/", j):
                    d -= 1
                    j += 2
                else:
                    j += 1
            i = j
            continue

        # 三引号原始字符串
        if src.startswith(Q * 3, i):
            line = src.count(NL, 0, i) + 1
            j = i + 3
            while j < n and not src.startswith(Q * 3, j):
                j += 1
            if j >= n:
                errors.append((line, "三引号原始字符串未闭合"))
                break
            i = j + 3
            continue

        # 普通字符串
        if c == Q:
            line = src.count(NL, 0, i) + 1
            i, closed = _scan_string(src, i)
            if not closed:
                errors.append((line, "字符串未闭合（引号是否配对？插值里的引号要成对）"))
            continue

        # 字符字面量
        if c == SQ:
            line = src.count(NL, 0, i) + 1
            j = i + 1
            closed = False
            while j < n:
                if src[j] == BS:
                    j += 2
                    continue
                if src[j] == SQ:
                    closed = True
                    j += 1
                    break
                if src[j] == NL:
                    break
                j += 1
            if not closed:
                errors.append((line, "字符字面量未闭合"))
            i = j if closed else i + 1
            continue

        i += 1
    return errors


def _iter_targets(argv):
    if '--all' in argv:
        root = "."
        for dp, dn, fn in os.walk(root):
            dn[:] = [d for d in dn if d not in ("build", ".git", ".gradle")]
            for f in sorted(fn):
                if f.endswith(".kt"):
                    yield os.path.join(dp, f)
    else:
        for p in argv:
            if not p.startswith('--'):
                yield p


def check_escaped_backticks(src):
    '''找出**代码区**里被反斜杠转义的反引号。

    ## 为什么必须有这一项（2026-11-01 血的教训）

    我往测试文件里写 Kotlin 反引号函数名时，生成脚本把「反斜杠 + 反引号」
    原样写进了文件，而 Kotlin 要求反引号标识符直接写、不转义。
    Kotlin 于是报 `Function declaration must have a name`（90 条级联错误），
    而**现有静态校验一条都没报**：括号平衡、字面量闭合都正常。
    结果是带着坏代码推上去跑了一轮 CI 才发现。

    注释里提到这种写法是合法的（文档本身就在说明转义），所以要剥掉注释
    与字符串再判 —— 与其它检查同一套口径。
    '''
    code = strip_noncode(src)
    out = []
    needle = chr(92) + chr(96)
    idx = code.find(needle)
    while idx >= 0:
        line = src.count(chr(10), 0, idx) + 1
        out.append((line, "代码区出现反斜杠转义的反引号（Kotlin 反引号标识符不该转义）"))
        idx = code.find(needle, idx + 1)
    return out


def main(argv):
    paths = list(_iter_targets(argv))
    if not paths:
        print(__doc__)
        return 2
    bad = 0
    for p in paths:
        try:
            src = open(p, encoding="utf-8").read()
        except OSError as e:
            print("ERR  %s: %s" % (p, e))
            bad += 1
            continue
        errs = check(src)
        errs.extend(check_escaped_backticks(src))
        if errs:
            bad += 1
            for line, msg in errs:
                print("BAD  %s:%d  %s" % (p, line, msg))
        elif len(paths) <= 5:
            print("OK   %s" % p)
    if len(paths) > 5:
        print("%d 个文件，%d 个有问题" % (len(paths), bad))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
