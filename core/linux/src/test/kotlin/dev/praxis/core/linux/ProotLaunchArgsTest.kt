package dev.praxis.core.linux

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ProotCommand.launchArgs] 的契约。
 *
 * ## 2026-11-02 重写：别名方案在真机上被封死
 *
 * 上一版这里断言「别名优先、本体兜底」两条候选。真机装完直接报：
 *
 * ```
 * Cannot run program ".../files/proot/bin/proot": error=13, Permission denied
 * ```
 *
 * 原因是 **Android 10 起禁止从应用可写数据目录执行文件**（SELinux 策略），
 * `setExecutable(true, false)` 改不了它。可执行的目录只有 `nativeLibraryDir`，
 * 而那是只读的 —— 别名方案从根上不成立，不是「某台设备不允许」。
 *
 * 所以现在的契约只有一条：**永远执行 nativeLibraryDir 里的 `libproot.so`**。
 * loader 靠 `PROOT_LOADER` 显式指路（它只被 mmap 读，放哪都行）。
 */
class ProotLaunchArgsTest {

    private val libDir = File("/data/app/dev.praxis/lib/arm64")
    private val prootBinary = File(libDir, "libproot.so")
    private val args = listOf("--link2symlink", "-0", "/bin/sh")

    @Test
    fun `argv 第一个元素是 nativeLibraryDir 里的 libproot_so`() {
        val out = ProotCommand.launchArgs(prootBinary, args)
        assertEquals(1, out.size)
        assertEquals(prootBinary.absolutePath, out[0].first())
    }

    @Test
    fun `参数原样跟在可执行文件之后`() {
        val out = ProotCommand.launchArgs(prootBinary, args)
        assertEquals(1 + args.size, out[0].size)
        assertEquals(args, out[0].drop(1))
    }

    /**
     * 这条是本次修复的核心断言：候选里**不允许**再出现 filesDir。
     *
     * 之前那版会把 `filesDir/proot/bin/proot` 排在第一候选，而它在
     * Android 10+ 上必然 EACCES —— 每次启动都白等一次 400ms 超时，
     * 还把真实报错埋在「试了两种都不行」后面。
     */
    @Test
    fun `候选里绝不出现 filesDir 下的路径`() {
        val out = ProotCommand.launchArgs(prootBinary, args)
        val all = out.flatten()
        assertTrue(
            "候选 argv 不该引用 filesDir（Android 10+ 禁止在那里执行文件）：" + all,
            all.none { it.contains("/files/") },
        )
    }

    @Test
    fun `多出来的候选一个都没有`() {
        // 多候选意味着「第一次注定失败」，那 400ms 纯属浪费。
        val out = ProotCommand.launchArgs(prootBinary, args)
        assertEquals("只应有一个候选", 1, out.size)
    }

    @Test
    fun `空参数也能生成合法 argv`() {
        val out = ProotCommand.launchArgs(prootBinary, emptyList())
        assertEquals(listOf(prootBinary.absolutePath), out[0])
    }

    @Test
    fun `可执行文件在别处时同样直接采用`() {
        val other = File("/data/user/0/dev.praxis/files/proot/bin/proot")
        val out = ProotCommand.launchArgs(other, args)
        assertEquals(other.absolutePath, out[0].first())
        assertEquals(1, out.size)
    }

    /**
     * `PROOT_EXECUTABLE_NAMES` 与 `launchArgs` 必须指向同一个名字。
     *
     * `ProotEnvPort` 靠它按进程名判断「容器在不在跑」。两者一旦分叉，
     * 表现是「容器明明起来了，设置页却显示未运行」。
     */
    @Test
    fun `进程名清单与执行名一致`() {
        assertTrue(
            "PROOT_EXECUTABLE_NAMES 必须包含 libproot.so：" +
                ProotCommand.PROOT_EXECUTABLE_NAMES,
            ProotCommand.PROOT_EXECUTABLE_NAMES.contains("libproot.so"),
        )
        // 别名已删除，不该再有人按 "proot" 这个进程名去找容器。
        assertTrue(
            "别名已废弃，进程名清单里不该再有 proot：" +
                ProotCommand.PROOT_EXECUTABLE_NAMES,
            !ProotCommand.PROOT_EXECUTABLE_NAMES.contains("proot"),
        )
    }
}
