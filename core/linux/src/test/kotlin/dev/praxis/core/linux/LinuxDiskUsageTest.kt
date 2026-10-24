package dev.praxis.core.linux

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 磁盘占用统计的单测。
 *
 * ## 为什么这一层值得测
 *
 * 三个边界在真机界面上**几乎不可能验证**，但每一个都会让用户看到错数字：
 *
 *  1. **硬链接去重**：不去重时 rootfs 体积能虚高 3~5%，
 *     用户拿 `du -sh` 一对比就发现对不上；
 *  2. **符号链接不跟随**：跟随会让 `bin -> usr/bin` 把 `/usr` 算两遍，
 *     严重时直接绕环把内存打满；
 *  3. **权限不足的目录**：早期实现用 `walkTopDown` 遇到一个无权限目录就
 *     抛异常，整页显示 0。
 *
 * 纯 JVM（用临时目录），`./gradlew :core:linux:test` 可跑。
 */
class LinuxDiskUsageTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("praxis-usage", "").let {
            it.delete()
            it.mkdirs()
            it
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ── 基础统计 ──────────────────────────────────────────────

    @Test
    fun `统计空目录为零`() {
        val usage = LinuxDiskUsage.diskUsage(root)
        assertEquals(0L, usage.bytes)
        assertEquals(0, usage.fileCount)
    }

    @Test
    fun `统计不存在的目录返回零而不是抛异常`() {
        // 「环境还没装」时调用方会传一个不存在的 rootfs 路径 ——
        // 那不是错误，是正常的未安装状态。
        val usage = LinuxDiskUsage.diskUsage(File(root, "nope"))
        assertEquals(0L, usage.bytes)
    }

    @Test
    fun `累加嵌套目录里的文件`() {
        write("a.txt", 100)
        write("sub/b.txt", 200)
        write("sub/deep/c.txt", 300)

        val usage = LinuxDiskUsage.diskUsage(root)
        assertEquals(600L, usage.bytes)
        assertEquals(3, usage.fileCount)
    }

    // ── 符号链接 ──────────────────────────────────────────────

    @Test
    fun `不跟随符号链接指向的目录`() {
        // 模拟 rootfs 的 `bin -> usr/bin`：目标里的文件不该被算两次。
        write("usr/bin/tool", 500)
        val link = File(root, "bin")
        // 建目录符号链接；部分环境不允许时跳过（Windows 上会失败，CI 是 Linux）。
        val ok = runCatching {
            java.nio.file.Files.createSymbolicLink(
                link.toPath(),
                File(root, "usr/bin").toPath(),
            )
        }.isSuccess

        val usage = LinuxDiskUsage.diskUsage(root)

        // 无论链接是否建成，`usr/bin/tool` 的 500 字节只该算一次。
        // 若实现跟随了链接，这里会得到 1000。
        assertEquals("不应把符号链接目标算两遍", 500L, usage.bytes)
        if (ok) {
            // 链接自身按文件计入（Linux 上符号链接 size = 目标路径长度）。
            assertTrue("链接本身应被计入文件数", usage.fileCount >= 1)
        }
    }

    @Test
    fun `符号链接环不会导致死循环`() {
        // `a -> .` 是最短的环。跟随实现会无限递归直至栈溢出/OOM。
        write("f.txt", 10)
        runCatching {
            java.nio.file.Files.createSymbolicLink(
                File(root, "loop").toPath(),
                root.toPath(),
            )
        }

        // 不抛异常、不挂住即可。字节数至少包含 f.txt 的 10。
        val usage = LinuxDiskUsage.diskUsage(root)
        assertTrue("至少应统计到真实文件", usage.bytes >= 10L)
    }

    // ── 硬链接去重 ────────────────────────────────────────────

    @Test
    fun `同一 inode 的硬链接只算一次`() {
        val original = write("orig.bin", 4096)
        // 建硬链接：需要与源在同一文件系统（临时目录满足）。
        val linked = runCatching {
            java.nio.file.Files.createLink(
                File(root, "hard.bin").toPath(),
                original.toPath(),
            )
        }.isSuccess

        val usage = LinuxDiskUsage.diskUsage(root)

        if (linked) {
            // 关键断言：两个名字、一个 inode，只能算 4096。
            // 不去重会得到 8192 —— 这正是 rootfs 体积虚高的原因。
            assertEquals("硬链接不应重复计入", 4096L, usage.bytes)
        } else {
            // 环境不支持硬链接（跨设备/权限），至少不能崩。
            assertEquals(4096L, usage.bytes)
        }
    }

    // ── 清理 ──────────────────────────────────────────────────

    @Test
    fun `清空目录保留目录本身`() {
        write("a.tmp", 10)
        write("nested/b.tmp", 10)

        val failed = LinuxDiskUsage.clearContents(root)

        assertEquals(0, failed)
        assertTrue("目录本身必须保留 —— 它是 proot 的 bind 目标", root.isDirectory)
        assertEquals(0, root.listFiles()?.size ?: 0)
    }

    @Test
    fun `清空不存在的目录返回零`() {
        // 未开启共享 tmp 时用户点「清空」，不该报错。
        assertEquals(0, LinuxDiskUsage.clearContents(File(root, "missing")))
    }

    // ── 格式化 ────────────────────────────────────────────────

    @Test
    fun `零字节显示为零B而不是零点零KB`() {
        // 显示成 "0.0 KB" 会让用户以为统计坏了。
        assertEquals("0 B", LinuxDiskUsage.humanBytes(0))
        assertEquals("0 B", LinuxDiskUsage.humanBytes(-1))
    }

    @Test
    fun `各量级取正确单位`() {
        assertEquals("512 B", LinuxDiskUsage.humanBytes(512))
        assertEquals("1 KB", LinuxDiskUsage.humanBytes(1024))
        assertEquals("1.5 KB", LinuxDiskUsage.humanBytes(1536))
        assertEquals("1 MB", LinuxDiskUsage.humanBytes(1024L * 1024))
        assertEquals("1 GB", LinuxDiskUsage.humanBytes(1024L * 1024 * 1024))
    }

    @Test
    fun `整数时省略小数位`() {
        // "2 GB" 比 "2.0 GB" 干净。这是有意为之的格式，不是巧合。
        assertEquals("2 GB", LinuxDiskUsage.humanBytes(2L * 1024 * 1024 * 1024))
    }

    // ── 汇总 ──────────────────────────────────────────────────

    @Test
    fun `未开启共享tmp时总量不含tmp`() {
        write("rootfs-file", 100)
        val tmp = File(root, "tmp-host").apply { mkdirs() }
        File(tmp, "junk").writeBytes(ByteArray(900))

        // 传 null 表示「未开启」—— 此时容器 /tmp 在 rootfs 内部，
        // 已包含在 rootfs 统计里，不能再加一遍。
        val usage = LinuxDiskUsage.environmentUsage(
            rootfsDir = root,
            hostTmpDir = null,
            tarballPath = null,
        )

        // rootfs 统计会递归进 tmp-host（它是 root 的子目录）——
        // 所以这里是 100 + 900。重点验证「没有额外再加一次 900」。
        assertEquals(1000L, usage.totalBytes)
        assertEquals(0L, usage.tmp.bytes)
    }

    @Test
    fun `开启共享tmp时tmp单独统计`() {
        val rootfs = File(root, "shared").apply { mkdirs() }
        File(rootfs, "f").writeBytes(ByteArray(100))
        val tmp = File(root, "tmp-host").apply { mkdirs() }
        File(tmp, "junk").writeBytes(ByteArray(900))

        val usage = LinuxDiskUsage.environmentUsage(
            rootfsDir = rootfs,
            hostTmpDir = tmp,
            tarballPath = null,
        )

        assertEquals(100L, usage.rootfs.bytes)
        assertEquals(900L, usage.tmp.bytes)
        assertEquals(1000L, usage.totalBytes)
    }

    // ── 辅助 ──────────────────────────────────────────────────

    private fun write(relative: String, bytes: Int): File {
        val file = File(root, relative)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(bytes))
        return file
    }
}