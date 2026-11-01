package dev.praxis.data.linux

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ② proot 秒退：loader 铺放的两个决策点。
 *
 * ## 用户报的现象
 *
 * ```
 * /bin/sh: can't access tty
 * ls: Permission denied
 * ```
 *
 * proot 进程启动即退，终端退回宿主 shell。真因之一是 proot 找不到自己的
 * loader —— 它按运行名 `proot-loader` 找，而 Android 只允许 `lib*.so`
 * 进 `nativeLibraryDir`，两个名字对不上。铺一份改名副本到 `filesDir` 解决。
 *
 * ## 为什么这两个点必须测
 *
 * 1. **挑哪个打包文件**：挑错 = 拿不到 loader = proot 秒退，而报错是
 *    误导性的 `execve("/bin/sh"): No such file or directory`（`/bin/sh` 明明存在）。
 * 2. **副本要不要重拷**：判错 = 用着旧 loader，升级 App 后照样秒退，
 *    而且同样是那句误导性报错。
 *
 * 两条错了都不会响，只会表现成「容器起不来」。
 */
class ProotLoaderStagingTest {

    private lateinit var tmp: File

    @Before
    fun setUp() {
        tmp = File(System.getProperty("java.io.tmpdir"), "praxis-loader-test-" + System.nanoTime())
        tmp.mkdirs()
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    // ── pickPackagedLoader ────────────────────────────────────────────

    /**
     * 现行打包名 `libproot-loader.so`（短横线）优先。
     */
    @Test
    fun `优先挑短横线命名的 loader`() {
        val f = File(tmp, "libproot-loader.so").apply { writeText("loader") }
        assertEquals(f, ProotLoaderStaging.pickPackagedLoader(tmp.absolutePath))
    }

    /**
     * 历史打包名（下划线）也要认。
     *
     * 用户手机上装的旧包可能只有这个名字；不认就等于「升级后拿不到 loader」。
     */
    @Test
    fun `下划线命名也要认`() {
        val f = File(tmp, "libproot_loader.so").apply { writeText("loader") }
        assertEquals(f, ProotLoaderStaging.pickPackagedLoader(tmp.absolutePath))
    }

    /**
     * 两个都在时短横线优先（现行名）。
     */
    @Test
    fun `两个都在时短横线优先`() {
        File(tmp, "libproot-loader.so").writeText("new")
        File(tmp, "libproot_loader.so").writeText("old")
        assertEquals(
            "libproot-loader.so",
            ProotLoaderStaging.pickPackagedLoader(tmp.absolutePath)?.name,
        )
    }

    /**
     * 都没有时返回 null（调用方降级），而不是抛异常。
     *
     * 抛异常会让终端页整页崩 —— 「修一个坑不该制造新的坑」。
     */
    @Test
    fun `都没有时返回 null 让调用方降级`() {
        assertNull(ProotLoaderStaging.pickPackagedLoader(tmp.absolutePath))
    }

    /** 目录不算文件。 */
    @Test
    fun `同名目录不算 loader`() {
        File(tmp, "libproot-loader.so").mkdirs()
        assertNull(ProotLoaderStaging.pickPackagedLoader(tmp.absolutePath))
    }

    // ── stale ─────────────────────────────────────────────────────────

    /** 目标不存在时要重拷。 */
    @Test
    fun `目标不存在算过期`() {
        val src = File(tmp, "src").apply { writeText("aaaa") }
        assertTrue(ProotLoaderStaging.stale(File(tmp, "missing"), src))
    }

    /** 长度不同要重拷（APK 升级后 loader 变长）。 */
    @Test
    fun `长度不同算过期`() {
        val src = File(tmp, "src").apply { writeText("aaaaaaaa") }
        val dst = File(tmp, "dst").apply { writeText("aa") }
        assertTrue(ProotLoaderStaging.stale(dst, src))
    }

    /** 目标比源旧要重拷。 */
    @Test
    fun `目标比源旧算过期`() {
        val src = File(tmp, "src").apply { writeText("aaaa"); setLastModified(2_000_000_000_000L) }
        val dst = File(tmp, "dst").apply { writeText("aaaa"); setLastModified(1_000_000_000_000L) }
        assertTrue(ProotLoaderStaging.stale(dst, src))
    }

    /**
     * 大小与时间都一致时**不**重拷（热路径，每次 exec 都会走一遍）。
     *
     * 这条防「无条件重拷」：那会让每次起 proot 都多一次十几 KB 的写盘。
     */
    @Test
    fun `一致时不重拷`() {
        val t = 1_500_000_000_000L
        val src = File(tmp, "src").apply { writeText("aaaa"); setLastModified(t) }
        val dst = File(tmp, "dst").apply { writeText("aaaa"); setLastModified(t) }
        assertFalse(ProotLoaderStaging.stale(dst, src))
    }

    // ── 执行位（2026-11-02 模拟器实测确认的 ② 真根因）──────────────

    /**
     * 铺好的 loader 必须**可执行**，否则 proot 会以最误导的方式失败。
     *
     * ## 实测因果（API 30 + x86_64 proot 5.1.107）
     *
     * ```
     * chmod 644 -> proot error: execve("/bin/sh"): Permission denied   FAIL
     * chmod 755 -> RUN_OK                                                PASS
     * ```
     *
     * 来回切 4 次，4 次一致 —— 执行位就是那个开关。
     *
     * ## 为什么报错完全指不到真因
     *
     * 报的是 `execve("/bin/sh"): Permission denied` —— 说的是 guest 的 /bin/sh，
     * 不是 loader。于是排查方向永远是「rootfs 权限」或「proot 参数」，
     * 不会想到「loader 缺 +x」。而 proot 秒退后终端回退宿主 shell，
     * 用户最后看到的是 `/bin/sh: can't access tty` —— 整条链没有一句提到 loader。
     *
     * ## 这条为什么只能靠源码断言
     *
     * `stage()` 需要 `Context`，单测无法直接跑它，也就无法直接断言产物的 mode。
     * 所以退而求其次：断言源码里**确实调了** `setExecutable`。
     * 这不是最强的验证，但能拦住「有人觉得 loader 不用 +x 又把它删掉」。
     */
    @Test
    fun `铺 loader 时必须设置执行位`() {
        val src = File("src/main/java/dev/praxis/data/linux/ProotLoaderStaging.kt")
        val file = listOf(src, File("app/" + src.path), File("../app/" + src.path))
            .firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 ProotLoaderStaging.kt")
        val text = file.readText()
        org.junit.Assert.assertTrue(
            "ProotLoaderStaging 没有给 loader 设置执行位 —— 644 的 loader 会让 proot " +
                "报 execve(\"/bin/sh\"): Permission denied 然后秒退",
            text.contains("loader.setExecutable(true"),
        )
    }
}
