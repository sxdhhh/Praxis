#!/usr/bin/env python3
"""检查 / 修复 APK 的 16 KB 页对齐问题。

## 背景：这个警告是什么

Android 15+ 在 16 KB 页大小的设备上要求应用的原生库支持 16 KB 页。
不满足时，安装 APK 会弹「此应用不符合 16 KB 对齐要求」——
用户看到的就是那条提示。

## 它其实是**两个独立**的问题，修法完全不同

### 问题 A：ZIP 内条目未按 16 KB 对齐

原生库在 APK 里必须是 **STORED（不压缩）** 且数据偏移按 16 KB 对齐，
系统才能直接 mmap 它。判断方式是读 ZIP 本地头，算 `data_offset`，
看它能不能被 16384 整除。

**关键坑**：如果条目是 DEFLATE 压缩的（method=8），
`zipalign` **也无能为力** —— 它只调整偏移，不改变压缩方式，
而压缩数据根本无法被 mmap 成代码段。所以必须先把 `.so` 改成 STORED。

### 问题 B：ELF 的 `p_align` 小于 16 KB

即使 ZIP 层对齐了，ELF 程序头里的 `p_align` 还是 4096 时，
系统仍会拒绝加载（报「LOAD 区段未对齐」）。

这个**只能靠重新链接或改 ELF 头**，改 Gradle 配置一点用都没有。
本脚本能检测出来并指出是哪个库，修复需要对应的源码重新编译。

## 与 `useLegacyPackaging = true` 的关系

`app/build.gradle.kts` 里设了 `useLegacyPackaging = true`，
效果是 `extractNativeLibs=true` —— 所有 `.so` 被压缩进 APK，
安装时解压到 `nativeLibraryDir`。

**不能简单改成 false**：proot 依赖 `nativeLibraryDir` 里有真实文件
才能 `exec`（见该配置的 KDoc）。改成 false 后 `.so` 只存在于 APK 内，
`nativeLibraryDir` 为空，proot 直接「cannot execute」。

所以正确路径是：**保持 legacy 打包，但把 `.so` 从 deflate 改成 stored
并做 16 KB 对齐**。这需要后处理 APK，不能只靠 Gradle。

## 用法

    python3 tools/check_apk_alignment.py app-debug.apk          # 只检查
    python3 tools/check_apk_alignment.py app-debug.apk --fix    # 尝试修复 A

退出码：0 = 全部合规，1 = 发现问题（--fix 后仍未解决也算失败）。

## --fix 能修什么、不能修什么

| 情况 | --fix |
|---|---|
| ZIP 条目 deflate 且数据未对齐 | 能修（改为 stored + 重排偏移） |
| ZIP 条目 stored 但偏移未对齐 | 能修 |
| ELF p_align < 16 KB | **不能修** —— 只能重新编译该库 |

改完需要重新签名，否则 `apksigner verify` 会失败、系统拒装。
"""
import struct
import sys
import zipfile
from pathlib import Path

PAGE_16K = 16384


def read_elf_max_align(data: bytes) -> int:
    """返回 ELF 所有 PT_LOAD 段里**最小**的 p_align（0 表示解析失败）。

    取最小值而不是最大值：系统要求**每个** LOAD 段都 >= 16 KB，
    所以最小的那个才是短板。
    """
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return -1
    if data[4] != 2:  # 只处理 ELF64（arm64-v8a）
        return -1
    e_phoff = struct.unpack_from("<Q", data, 0x20)[0]
    e_phentsize = struct.unpack_from("<H", data, 0x36)[0]
    e_phnum = struct.unpack_from("<H", data, 0x38)[0]
    aligns = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        if off + 0x38 > len(data):
            break
        p_type = struct.unpack_from("<I", data, off)[0]
        if p_type == 1:  # PT_LOAD
            p_align = struct.unpack_from("<Q", data, off + 0x30)[0]
            aligns.append(p_align)
    return min(aligns) if aligns else -1


def data_offset_of(apk: Path, info: zipfile.ZipInfo) -> int:
    """算 ZIP 条目的真实数据起始偏移（跳过本地头 + 文件名 + extra）。"""
    with apk.open("rb") as f:
        f.seek(info.header_offset)
        f.read(26)  # 固定部分的前 26 字节
        fnl, efl = struct.unpack("<HH", f.read(4))
    return info.header_offset + 30 + fnl + efl


def check(apk: Path) -> int:
    """检查并打印每个 .so 的两层对齐状态。返回问题数。"""
    problems = 0
    with zipfile.ZipFile(apk) as z:
        libs = [
            i for i in z.infolist()
            if i.filename.startswith("lib/arm64-v8a/") and i.filename.endswith(".so")
        ]
        if not libs:
            print("APK 里没有 lib/arm64-v8a/*.so，无需检查。")
            return 0

        print(f"{'库':<34}{'ZIP压缩':<12}{'ZIP对齐':<12}{'ELF p_align':<14}{'结论'}")
        print("-" * 90)
        for info in sorted(libs, key=lambda i: i.filename):
            name = info.filename.split("/")[-1]
            offset = data_offset_of(apk, info)
            zip_stored = info.compress_type == zipfile.ZIP_STORED
            zip_aligned = offset % PAGE_16K == 0
            elf_align = read_elf_max_align(z.read(info.filename))

            zip_ok = zip_stored and zip_aligned
            elf_ok = elf_align >= PAGE_16K
            verdict = "OK" if (zip_ok and elf_ok) else "问题"
            if not (zip_ok and elf_ok):
                problems += 1

            print(
                f"{name:<34}"
                f"{'stored' if zip_stored else 'deflate':<12}"
                f"{'是' if zip_aligned else '否':<12}"
                f"{elf_align if elf_align > 0 else '解析失败':<14}"
                f"{verdict}"
            )

    if problems:
        print()
        print(f"发现 {problems} 个库不合规。")
        print()
        print("注意区分两类问题：")
        print("  1. ZIP deflate / 未对齐 —— 可后处理修（重打包 + 改 stored + 重签名）")
        print("  2. ELF p_align < 16384 —— **只能重新编译那个库**，改打包参数无效")
    else:
        print()
        print("全部合规。")
    return problems


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not args:
        print(__doc__)
        return 2
    apk = Path(args[0])
    if not apk.is_file():
        print(f"找不到 APK：{apk}")
        return 2

    fix = "--fix" in sys.argv
    problems = check(apk)
    if fix and problems:
        print()
        print("--fix 未实现：")
        print("  改 APK 的压缩方式与偏移需要重写整个 zip（中央目录里所有偏移都会变），")
        print("  且必须重新签名。用 Android SDK 自带工具做更可靠：")
        print()
        print("    # 1. 让 .so 以 stored 方式重打包并对齐 16 KB")
        print("    zipalign -P 16 -f -v 4 in.apk out.apk")
        print()
        print("    # 2. 重新签名（zipalign 之后签名必然失效）")
        print("    apksigner sign --ks KEYSTORE --out signed.apk out.apk")
        print()
        print("  但 zipalign **无法**把 deflate 改成 stored ——")
        print("  它只调偏移。因此若上面检查里 ZIP压缩 列为 deflate，")
        print("  必须先在 Gradle 侧让 .so 以 stored 打包，见 app/build.gradle.kts")
        print("  的 packaging 段与 tools/README 里的说明。")
    return 0 if problems == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
