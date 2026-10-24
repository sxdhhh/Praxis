package dev.praxis.core.plugin

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 安装 / 卸载 / 启停 / 更新检查。
 *
 * ## 为什么这些必须测
 *
 * 包管理是「用户手动点一下就会改变磁盘状态」的操作。装错了、删错了
 * 都没有「撤销」按钮（用户写的脚本可能没了）。所以每条路径都要固定住：
 * 预检失败不落盘、卸载幂等、内置包启停、更新只升不降。
 */
class PluginRepositoryTest {

    private lateinit var storage: InMemoryPluginStorage
    private lateinit var repository: PluginRepository

    /** 预置的「安装源」内容：`sourcePath` → 文件表。 */
    private val externalSources = mutableMapOf<String, Map<String, String>>()

    private val validManifest = """
        toolpkg_id: com.example.tool
        main: packages/main.js
        version: 1.0.0
        display_name: 示例工具
        description: 一个用于测试的包
        author: tester
    """.trimIndent()

    @Before
    fun setUp() {
        storage = InMemoryPluginStorage()
        repository = PluginRepository(storage, now = { 1_000L })
        externalSources.clear()
        // 测试注入：把「外部路径」映射到内存内容。
        PluginRepository.externalArchiveFactory = { path ->
            externalSources[path]?.let { files ->
                InMemoryPluginArchive.ofText(files)
            }
        }
    }

    @After
    fun tearDown() {
        // 静态工厂是全局的，测完必须清掉，否则会影响其它测试类
        // （单测之间共享 JVM，静态状态泄漏是最难查的测试污染）。
        PluginRepository.externalArchiveFactory = null
    }

    private fun seedSource(key: String, manifest: String = validManifest, files: Map<String, String> = emptyMap()) {
        val all = mutableMapOf("manifest.hjson" to manifest)
        all.putAll(files)
        externalSources["mem://$key"] = all
    }

    // ── 扫描 ─────────────────────────────────────────────────

    @Test
    fun `没有包时扫描返回空`() {
        assertTrue(repository.scanAll().isEmpty())
    }

    @Test
    fun `扫描能读出清单字段`() {
        storage.seed(validManifest, files = mapOf("packages/main.js" to "// js"))
        val plugin = repository.scanAll().single()
        assertEquals("com.example.tool", plugin.id)
        assertEquals("示例工具", plugin.title)
        assertEquals("1.0.0", plugin.manifest.version)
        assertEquals(listOf("tester"), plugin.manifest.authors)
        assertTrue(plugin.isActive)
    }

    @Test
    fun `清单语法坏掉时仍能出现在列表里`() {
        // ## 这是最关键的一条：坏包不能从列表里消失
        //
        // 消失了用户就没法删它，磁盘会一直占着，而且每次启动都会加载失败
        // （日志里刷错误）却找不到是谁。所以必须能列出来、能删掉。
        //
        // 用 `InMemoryPluginStorage` 的原始内容通道绕过 seed 的 id 校验，
        // 模拟「用户手动塞进一个坏包」。
        storage.putRaw(
            packageId = "broken.pkg",
            files = mapOf("manifest.hjson" to "toolpkg_id: broken.pkg\nmain: [\n"),
        )

        val plugin = repository.scanAll().single()
        assertEquals("broken.pkg", plugin.id)
        assertFalse("坏包不该是启用状态", plugin.enabled)
        val fatal = plugin.problems.filterIsInstance<ManifestProblem.Fatal>().single()
        assertEquals("manifest", fatal.field)
        assertTrue("要说清是语法问题：${fatal.message}", fatal.message.contains("语法"))
        assertNotNull("用户要能看到失败原因", plugin.failureText())
    }

    @Test
    fun `未显式记录启停状态时按启用处理`() {
        storage.seed(validManifest)
        assertTrue(repository.scanAll().single().enabled)
    }

    @Test
    fun `显式禁用后扫描得到未启用`() {
        storage.seed(validManifest, enabled = false)
        assertFalse(repository.scanAll().single().enabled)
    }

    @Test
    fun `扫描按启用优先再按标题排序`() {
        storage.seed(validManifest.replace("com.example.tool", "z.pkg").replace("示例工具", "Z包"))
        storage.seed(
            validManifest.replace("com.example.tool", "a.pkg").replace("示例工具", "A包"),
            enabled = false,
        )
        val list = repository.scanAll()
        assertEquals(listOf("z.pkg", "a.pkg"), list.map { it.id })
    }

    // ── 安装 ─────────────────────────────────────────────────

    @Test
    fun `安装成功并写入来源`() {
        seedSource("pkg1", files = mapOf("packages/main.js" to "// js"))
        val result = repository.install("mem://pkg1", origin = PluginOrigin.Local)
        assertTrue("安装应成功：${result.exceptionOrNull()?.message}", result.isSuccess)
        val installed = result.getOrThrow()
        assertEquals("com.example.tool", installed.id)
        assertTrue(storage.exists("com.example.tool"))
        assertEquals(PluginOrigin.Local, storage.readOrigin("com.example.tool"))
    }

    @Test
    fun `清单有致命问题时安装失败且不落盘`() {
        // main 指向不存在的文件 → 预检必须拦住，不能留下半装状态。
        seedSource("bad") // 没有 packages/main.js
        val result = repository.install("mem://bad")
        assertTrue(result.isFailure)
        assertFalse("失败时不能落盘", storage.exists("com.example.tool"))
    }

    @Test
    fun `缺 toolpkg_id 时安装失败`() {
        seedSource("noid", manifest = "main: index.js")
        assertTrue(repository.install("mem://noid").isFailure)
    }

    @Test
    fun `id 含路径分隔符时拒绝安装`() {
        // 这是 zip slip 式的防护：包自己声明 id 为 `../../evil` 时不能写出去。
        seedSource(
            "slip",
            manifest = validManifest.replace("com.example.tool", "a/b"),
        )
        val result = repository.install("mem://slip")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("分隔符"))
    }

    @Test
    fun `市场安装时用 expectedId 覆盖包自报 id`() {
        // 服务端 id 是权威的，不能由包自己决定装成什么。
        seedSource("market1", files = mapOf("packages/main.js" to "// js"))
        val result = repository.install(
            sourcePath = "mem://market1",
            origin = PluginOrigin.Market(marketId = "com.example.tool", version = "1.0.0"),
            expectedId = "com.example.tool",
        )
        assertTrue(result.isSuccess)
        assertEquals("com.example.tool", result.getOrThrow().id)
    }

    @Test
    fun `打开不存在的外部路径时给出可读错误`() {
        val result = repository.install("mem://nope")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("无法打开"))
    }

    // ── 卸载 ─────────────────────────────────────────────────

    @Test
    fun `卸载删掉目录与状态`() {
        storage.seed(validManifest, enabled = true)
        assertNull(repository.uninstall("com.example.tool"))
        assertFalse(storage.exists("com.example.tool"))
        assertTrue(repository.scanAll().isEmpty())
    }

    @Test
    fun `卸载不存在的包视为成功`() {
        // 已被外部删掉时不该报错 —— 报错纯属噪音。
        assertNull(repository.uninstall("not.there"))
    }

    // ── 启停 ─────────────────────────────────────────────────

    @Test
    fun `启用与停用`() {
        storage.seed(validManifest)
        repository.setEnabled("com.example.tool", false)
        assertFalse(repository.scanAll().single().enabled)
        repository.setEnabled("com.example.tool", true)
        assertTrue(repository.scanAll().single().enabled)
    }

    @Test
    fun `子包单独停用`() {
        val manifest = """
            toolpkg_id: com.example.multi
            main: index.js
            subpackages: [
              { id: one, entry: one.js }
              { id: two, entry: two.js }
            ]
        """.trimIndent()
        storage.seed(manifest, files = mapOf("index.js" to "", "one.js" to "", "two.js" to ""))

        repository.setSubpackageEnabled("com.example.multi", "two", false)
        val plugin = repository.scanAll().single()
        assertTrue(plugin.isSubpackageEnabled("one"))
        assertFalse(plugin.isSubpackageEnabled("two"))
        assertEquals(listOf("one"), plugin.activeSubpackages.map { it.id })
    }

    @Test
    fun `包被停用时子包全部不可用`() {
        val manifest = """
            toolpkg_id: com.example.multi
            main: index.js
            subpackages: [{ id: one, entry: one.js }]
        """.trimIndent()
        storage.seed(manifest, files = mapOf("index.js" to "", "one.js" to ""), enabled = false)
        val plugin = repository.scanAll().single()
        assertFalse(plugin.isSubpackageEnabled("one"))
        assertTrue(plugin.activeSubpackages.isEmpty())
    }

    // ── 更新 ─────────────────────────────────────────────────

    @Test
    fun `市场包有新版本时提示更新`() {
        storage.seed(validManifest, origin = PluginOrigin.Market("com.example.tool", "1.0.0"))
        val plugin = repository.scanAll().single()
        assertEquals("2.0.0", repository.findUpdate(plugin, "2.0.0"))
    }

    @Test
    fun `远端版本更低时不提示更新（避免降级）`() {
        storage.seed(
            validManifest.replace("1.0.0", "2.0.0"),
            origin = PluginOrigin.Market("com.example.tool", "2.0.0"),
        )
        val plugin = repository.scanAll().single()
        assertNull(repository.findUpdate(plugin, "1.0.0"))
    }

    @Test
    fun `本地导入的包不检查更新`() {
        storage.seed(validManifest, origin = PluginOrigin.Local)
        val plugin = repository.scanAll().single()
        assertNull("本地导入没有远端对应物", repository.findUpdate(plugin, "9.9.9"))
    }

    @Test
    fun `版本号非法时不崩`() {
        storage.seed(
            validManifest.replace("1.0.0", "not-a-version"),
            origin = PluginOrigin.Market("com.example.tool", "x"),
        )
        assertNull(repository.findUpdate(repository.scanAll().single(), "1.0.0"))
    }
}