#!/usr/bin/env python3
'''静态校验：括号平衡 + 字符串/注释剥离。构建禁令期替代编译。

为什么需要它：构建被禁止时，语法级错误（缺大括号、字符串未闭合、
编辑工具写入的 HTML 实体）没有任何工具会报出来，只能靠文本校验兜住。

剥离逻辑在 kt_lexer.py，与 check_kt_files.py 共用同一份实现。历史上两个
脚本各写了一套剥离代码，结果字符字面量和三引号原始字符串的误判在两边
同时存在、也同时没人发现。

TARGETS 是「重点关注名单」：改动频繁或改错代价大的文件。名单外的文件用
check_kt_files.py 点名校验。
'''
import os
import sys
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from kt_lexer import balance

TARGETS = [
    # 容器层：HANDOVER 第 5 节列的全部坑都在这一层，改错代价最大
    'core/linux/src/main/kotlin/dev/praxis/core/linux/ProotCommand.kt',
    'core/linux/src/main/kotlin/dev/praxis/core/linux/FtpServer.kt',
    # 设置存储与入口 Activity：改动最频繁
    'core/data/src/main/kotlin/dev/praxis/core/data/settings/SettingsRepository.kt',
    'app/src/main/java/dev/praxis/MainActivity.kt',
]


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else '.')
    bad = 0
    for rel in TARGETS:
        path = root / rel
        if not path.exists():
            # 名单过期会让这个脚本静默失效 —— 比校验失败更麻烦，所以说清楚修法。
            print('MISSING %s' % rel)
            print('        文件已移动或删除，请更新本脚本的 TARGETS')
            bad += 1
            continue
        raw = path.read_text(encoding='utf-8')
        braces, parens, brackets, entities = balance(raw)
        ok = braces == 0 and parens == 0 and brackets == 0 and entities == 0
        if not ok:
            bad += 1
        print(
            '%s %-32s braces=%+d parens=%+d brackets=%+d entities=%d lines=%d'
            % ('OK ' if ok else 'BAD', path.name, braces, parens, brackets,
               entities, len(raw.splitlines()))
        )
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
