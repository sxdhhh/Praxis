package dev.praxis.core.model.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 软件包组与安装脚本的单测。
 *
 * ## 为什么这几条值得锁
 *
 * 脚本是**要送到容器里跑**的字符串，写错了不会编译报错，只会在真机上
 * 表现为「点了安装、什么都没装上」。三个最容易错且后果具体的点：
 *
 *  1. **缺 `DEBIAN_FRONTEND=noninteractive`** → `apt` 在 proot 里
 *     弹 debconf 菜单，永久挂住（没有 TTY 可交互）；
 *  2. **`set -e` 直接跟 `apt-get update`** → 某个源 404 时整次安装失败，
 *     而用户看到的是「什么都没装」，完全不知道原因；
 *  3. **空选返回非空脚本** → 空跑一遍显示「安装完成」，实际什么都没变。
 */
class LinuxPackageGroupsTest {

    // ── 组定义 ────────────────────────────────────────────────

    @Test
    fun `六组 id 唯一`() {
        // 重复 id 会让 `filter { it.id in groupIds }` 命中两次，
        // 包名去重后不报错、但「选了一组装了两组」的语义是错的。
        val ids = BuiltInPackageGroups.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `每组都有包且包名不含空格`() {
        // 包名带空格会让 `apt-get install a b` 的语义从「一个包」变成「两个包」，
        // 拼进命令行后行为完全改变且不报错。
        BuiltInPackageGroups.forEach { group ->
            assertTrue("${group.id} 不应为空", group.packages.isNotEmpty())
            group.packages.forEach { pkg ->
                assertFalse("包名不应含空格：$pkg", pkg.contains(' '))
                assertTrue("包名不应为空", pkg.isNotBlank())
            }
        }
    }

    @Test
    fun `installArgs 是空格拼接`() {
        val group = LinuxPackageGroup("t", "t", "t", listOf("a", "b", "c"))
        assertEquals("a b c", group.installArgs)
    }

    // ── 脚本生成 ──────────────────────────────────────────────

    @Test
    fun `空选返回空串`() {
        // 调用方据此拒绝执行（见 `installPackages` 的空串判断）。
        // 返回一个只有 set -e 的脚本会「成功」跑完，让用户以为装上了。
        assertEquals("", buildPackageInstallScript(emptySet()))
    }

    @Test
    fun `未知 id 被忽略`() {
        // 老版本存的 id 可能已删除 —— 不能因此崩或生成空脚本以外的怪东西。
        assertEquals("", buildPackageInstallScript(setOf("nonexistent")))
    }

    @Test
    fun `未知 id 与有效 id 混选时只装有效的`() {
        val script = buildPackageInstallScript(setOf("python", "ghost"))
        assertTrue(script.contains("python3"))
        assertFalse("不该出现幽灵组的痕迹", script.contains("ghost"))
    }

    @Test
    fun `脚本必含非交互环境变量`() {
        // 缺这一行 → debconf 在无 TTY 的 proot 里永久挂住。
        val script = buildPackageInstallScript(setOf("python"))
        assertTrue(script.contains("DEBIAN_FRONTEND=noninteractive"))
    }

    @Test
    fun `apt update 失败不中断安装`() {
        // ## 这条锁的是「不要写成裸的 set -e + apt-get update」
        //
        // 裸写在第三方源 404 时会让整次安装中止，而索引其实已拿到大部分。
        // 正确写法是 `|| echo ...` 兜住。
        val script = buildPackageInstallScript(setOf("node"))
        val updateLine = script.lineSequence().first { it.contains("apt-get update") }
        assertTrue("update 行必须容错：$updateLine", updateLine.contains("||"))
    }

    @Test
    fun `install 行带 -y 且含组内所有包`() {
        val script = buildPackageInstallScript(setOf("rust"))
        val installLine = script.lineSequence().first { it.contains("apt-get install") }
        assertTrue(installLine.contains("-y"))
        assertTrue(installLine.contains("rustc"))
        assertTrue(installLine.contains("cargo"))
    }

    @Test
    fun `多组时包名跨组去重`() {
        // `go` 组带 git，`tools` 组不带，但若将来两组都带同一个包，
        // 重复出现在命令行里虽不致命，却会让日志难以核对。
        val script = buildPackageInstallScript(setOf("go", "tools"))
        val installLine = script.lineSequence().first { it.contains("apt-get install") }
        val gitCount = Regex("\\bgit\\b").findAll(installLine).count()
        assertEquals("git 只应出现一次", 1, gitCount)
    }

    @Test
    fun `脚本以 apt-get clean 收尾前会清缓存`() {
        // 不清缓存，装完 Java+Rust 能留几百 MB 在 /var/cache/apt/archives，
        // 而那正是设置页「环境占用」显示的数字。
        val script = buildPackageInstallScript(setOf("java"))
        assertTrue(script.contains("apt-get clean"))
    }
}