cmake_minimum_required(VERSION 3.18.1)

project("pty")

# 输出名必须是 `pty`：Kotlin 侧 `System.loadLibrary("pty")` 按这个名字找。
# Android 打包时会把它放进 lib/arm64-v8a/libpty.so，运行时能直接 load。
add_library(pty SHARED pty.c)

target_link_libraries(pty
        # forkpty / tcgetattr / ioctl 都在 libc 里，但 android/log.h 需要显式加。
        # 不写这行也能编过，但 __android_log_print 会在运行时找不到符号。
        log)

# ═══════════════════════════════════════════════════════════════════════
# 16 KB 页对齐（2026-10-24 修，这是「进入 App 弹兼容性警告」的根因）
# ═══════════════════════════════════════════════════════════════════════
#
# ## 症状
#
# 每次启动弹「此应用不符合 16 KB 对齐要求」，列出 7 个 lib/*.so。
# 实测（`tools/check_apk_alignment.py`）发现其中**只有** `libpty.so`
# 的 ELF `p_align` 是 4096，其余 6 个（`libproot.so` / `libtalloc.so` /
# `libandroid-shmem.so` / `libproot-loader.so` / `libandroidx.graphics.path.so` /
# `libdatastore_shared_counter.so`）都已经是 16384。
#
# ## 为什么只有它
#
# 那 6 个是**预编译好再放进 jniLibs** 的（或来自依赖 aar），
# 它们的产出方已经用了 16 KB 对齐。
# 只有 `libpty.so` 是**本项目 CMake 现场编译**的 ——
# 而 NDK r28 的 clang 默认 `-z max-page-size=4096`，除非显式指定。
#
# 证据：`app/.cxx/Debug/*/arm64-v8a/build.ninja` 的链接行里
# `grep -o 'max-page-size=[0-9]*'` 结果为空 —— 一个都没传。
#
# ## 为什么必须改链接参数，而不是改 ELF 头
#
# 16 KB 对齐的硬性条件是：**每个 PT_LOAD 段都要满足
# `p_offset ≡ p_vaddr (mod 16384)`**。
#
# 实测本库的两个 LOAD 段：
#
# ```
# LOAD0  off=0x0     vaddr=0x0     align=0x1000   ← 同余成立
# LOAD1  off=0x18e0  vaddr=0x28e0  align=0x1000   ← 0x18e0 ≠ 0x28e0
# ```
#
# 第二个段不满足同余关系。直接改 `p_align` 字段会让加载器按 16 KB
# 对齐去映射，但文件内偏移与虚拟地址对不上 → 加载失败。
# 这不是「打个补丁」，而是必须让**链接器**重新排布段偏移。
#
# ## 参数说明
#
# `-Wl,-z,max-page-size=16384`
#   → 让链接器按 16 KB 的粒度安排 LOAD 段，`p_align` 随之写成 16384，
#     段偏移与虚拟地址满足同余。
#
# `-Wl,-z,common-page-size=16384`
#   → 同步设置「公共页大小」。只设 `max-page-size` 而不同步这个值时，
#     部分 NDK 版本仍会留下 4 KB 对齐的空隙，导致校验工具仍报不齐。
#     两个一起设才是完整做法（Android 官方 16 KB 迁移文档也是这么写的）。
#
# ## 代价
#
# 库体积会略增（段间填充变多）。本库编译产物约 30 KB，
# 填充带来的增量在百字节量级，可以忽略。
#
# ## 兼容性
#
# 16 KB 对齐的 `.so` 在 4 KB 页设备上**照常加载** ——
# 对齐是「下界」不是「精确匹配」，更大的对齐值在所有设备上都合法。
# 所以这个改动不影响 minSdk 28 的老机器。
target_link_options(pty PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384")