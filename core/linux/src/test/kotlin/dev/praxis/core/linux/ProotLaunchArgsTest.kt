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
     * 没有「proot 别名」这个概念了：候选里不该出现 `proot` 这个文件名。
     *
     * ## 这条替代了原来的 `PROOT_EXECUTABLE_NAMES` 断言（2026-11-02）
     *
     * 原先有个 `PROOT_EXECUTABLE_NAMES = listOf("libproot.so")` 常量，
     * KDoc 写着「`ProotEnvPort` 的『容器是否在跑』探测按进程名匹配」。
     * 但清理时查证：那段探测**早已不存在** —— 运行状态来自内存里的
     * `shellSession?.isAlive`（见 `LinuxEnvViewModel`），不看进程名。
     * 那个常量成了只有测试引用的孤儿，留着会误导后人以为它在起作用。
     *
     * 删掉常量，把真正要守的东西留在这里：**别名方案不能回来**。
     */
    @Test
    fun `候选里不出现 proot 这个文件名`() {
        val out = ProotCommand.launchArgs(prootBinary, args)
        val all = out.flatten()
        assertTrue(
            "launchArgs 里出现了名为 proot 的可执行文件 —— 别名方案在 Android 10+ " +
                "上必然 Permission denied，不该回来：" + all,
            all.none { it.endsWith("/proot") },
        )
    }
}
