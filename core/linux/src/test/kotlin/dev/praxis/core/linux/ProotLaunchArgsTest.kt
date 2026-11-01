package dev.praxis.core.linux

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ProotCommand.launchArgs] 的 argv 候选规则。
 *
 * ## 为什么值得单独测（2026-11-01）
 *
 * 这条规则决定「proot 能不能找到它的 loader」，而一旦错了，表现是
 * proot 启动后立即退出，报一句极具误导性的
 * `execve("/bin/sh"): No such file or directory`。
 *
 * 更麻烦的是**错了也能跑**：候选列表里还有 `libproot.so` 兜底，
 * 所以别名路径失效时不会立刻报错，只是这个修复白做了。
 * 真机上就发生过一次 —— 铺文件把别名建在 filesDir，而这里还按
 * nativeLibraryDir 推路径，两边对不上，静默回退，现象与修之前一样。
 *
 * 所以这里既测「该给别名时给了别名」，也测「没铺文件时必须老样子」。
 */
class ProotLaunchArgsTest {

    private val libDir = File("/data/app/dev.praxis/lib/arm64")
    private val prootBinary = File(libDir, "libproot.so")
    private val stagedDir = File("/data/user/0/dev.praxis/files/proot/bin")
    private val args = listOf("--link2symlink", "-0", "/bin/sh")

    private fun envWithLoader(loader: String?) =
        if (loader == null) emptyMap() else mapOf("PROOT_LOADER" to loader)

    @Test
    fun `没有 PROOT_LOADER 时只给本体一条`() {
        val out = ProotCommand.launchArgs(prootBinary, envWithLoader(null), args)
        assertEquals(1, out.size)
        assertEquals("/data/app/dev.praxis/lib/arm64/libproot.so", out[0].first())
    }

    @Test
    fun `给了 loader 但没传 stagedDir 时只给本体一条`() {
        // 调用方没铺文件 = 走旧路径，行为必须与历史逐字一致。
        val out = ProotCommand.launchArgs(
            prootBinary, envWithLoader("/some/proot-loader"), args, stagedDir = null,
        )
        assertEquals(1, out.size)
        assertEquals("libproot.so", File(out[0].first()).name)
    }

    @Test
    fun `给了 loader 与 stagedDir 时别名优先本体兜底`() {
        val out = ProotCommand.launchArgs(
            prootBinary, envWithLoader("/some/proot-loader"), args, stagedDir = stagedDir,
        )
        assertEquals(2, out.size)
        // 第一条必须是可写目录下的 proot 别名，不是 nativeLibraryDir
        assertEquals("proot", File(out[0].first()).name)
        assertEquals(stagedDir.absolutePath, File(out[0].first()).parentFile.absolutePath)
        // 第二条仍是原名本体，供失败时回退
        assertEquals("libproot.so", File(out[1].first()).name)
    }

    @Test
    fun `参数原样追加到每个候选`() {
        val out = ProotCommand.launchArgs(
            prootBinary, envWithLoader("/some/proot-loader"), args, stagedDir = stagedDir,
        )
        for (argv in out) {
            assertEquals(args, argv.drop(1))
        }
    }

    @Test
    fun `可执行文件不是 libproot_so 时不启用别名`() {
        // 有些设备上 proot 是别的文件名，别名机制不适用，必须原样返回。
        val other = File(libDir, "proot-custom")
        val out = ProotCommand.launchArgs(
            other, envWithLoader("/some/proot-loader"), args, stagedDir = stagedDir,
        )
        assertEquals(1, out.size)
        assertEquals(other.absolutePath, out[0].first())
    }

    @Test
    fun `stagedDir 与本体同目录时避免重复候选`() {
        // 极端情形：调用方把 stagedDir 传成了 nativeLibraryDir。
        // 此时别名路径与本体路径相同，不该给出两条一样的候选。
        val out = ProotCommand.launchArgs(
            prootBinary, envWithLoader("/some/proot-loader"), args, stagedDir = libDir,
        )
        assertEquals(1, out.size)
        assertEquals(prootBinary.absolutePath, out[0].first())
    }
}
