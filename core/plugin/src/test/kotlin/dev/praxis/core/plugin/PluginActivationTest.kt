package dev.praxis.core.plugin

import dev.praxis.core.tool.ToolRegistry
import dev.praxis.core.tool.ToolResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动装载编排。
 *
 * ## 守的是什么
 *
 * 这段逻辑决定「哪些包在开机后是可用的」。它错在哪一类都不好查：
 * 静默不装 → 用户点入口得到「运行时已释放」；装到一半抛异常 → 整个启动崩掉。
 * 所以每个分支都要有用例：启用/停用、归档缺失、脚本报错、空列表。
 */
class PluginActivationTest {

    /** 内存包来源：id -> 归档条目。 */
    private class FakeSource(
        private val plugins: List<InstalledPlugin>,
        private val archives: Map<String, Map<String, String>>,
    ) : PluginSource {
        override fun scanAll(): List<InstalledPlugin> = plugins
        override fun openArchive(packageId: String): PluginArchive? {
            val entries = archives[packageId] ?: return null
            return InMemoryPluginArchive.ofText(entries)
        }
    }

    private class FakeRuntime(
        private val errors: List<String> = emptyList(),
    ) : PluginScriptRuntime {
        val loaded = mutableListOf<String>()

        override suspend fun load(
            packageId: String,
            main: PluginScriptSource,
            subpackages: List<Pair<String, PluginScriptSource>>,
            resourceResolver: (String) -> ByteArray?,
            readFile: (String) -> String?,
        ): PluginLoadOutcome {
            loaded += packageId
            return PluginLoadOutcome(tools = emptyList(), errors = errors)
        }

        override suspend fun invoke(
            packageId: String,
            toolName: String,
            entryFunction: String,
            argumentsJson: String,
        ): ToolResult = ToolResult(content = "ok")

        override suspend fun render(packageId: String, entryId: String): Result<String> =
            Result.success("{}")

        override suspend fun unload(packageId: String) = Unit
    }

    private fun plugin(id: String, enabled: Boolean = true) = InstalledPlugin(
        manifest = PluginManifest(id = id, main = "index.js"),
        installPath = "/packages/" + id,
        enabled = enabled,
    )

    private fun archive() = mapOf(
        "manifest.json" to """{"toolpkg_id":"a","main":"index.js"}""",
        "index.js" to "// main",
    )

    @Test
    fun 启用的包被装载停用的被跳过() = runTest {
        val runtime = FakeRuntime()
        val source = FakeSource(
            plugins = listOf(plugin("a"), plugin("b", enabled = false)),
            archives = mapOf("a" to archive()),
        )
        val activation = PluginActivation(source, PluginRuntimeHost(ToolRegistry(), runtime))

        val report = activation.activateAll()

        assertEquals(listOf("a"), report.loaded)
        assertEquals(listOf("b"), report.skipped)
        assertTrue("不该有失败：" + report.failed, report.failed.isEmpty())
        assertEquals(listOf("a"), runtime.loaded)
    }

    @Test
    fun 读不到归档记为失败而不是跳过() = runTest {
        val source = FakeSource(
            plugins = listOf(plugin("missing")),
            archives = emptyMap(),
        )
        val activation = PluginActivation(source, PluginRuntimeHost(ToolRegistry(), FakeRuntime()))

        val report = activation.activateAll()

        assertEquals(1, report.failed.size)
        assertEquals("missing", report.failed[0].packageId)
        // 失败必须带原因：只说「失败」等于没说。
        assertTrue(report.failed[0].errors.single().isNotEmpty())
        assertTrue(report.loaded.isEmpty())
    }

    @Test
    fun 运行时报错记为失败并带上原因() = runTest {
        val source = FakeSource(listOf(plugin("a")), mapOf("a" to archive()))
        val activation = PluginActivation(
            source,
            PluginRuntimeHost(ToolRegistry(), FakeRuntime(errors = listOf("主脚本装载失败：boom"))),
        )

        val report = activation.activateAll()

        assertEquals(1, report.failed.size)
        assertEquals(listOf("主脚本装载失败：boom"), report.failed[0].errors)
        assertTrue("有错误就不算装载成功", report.loaded.isEmpty())
    }

    @Test
    fun 空列表得到空报告() = runTest {
        val activation = PluginActivation(
            FakeSource(emptyList(), emptyMap()),
            PluginRuntimeHost(ToolRegistry(), FakeRuntime()),
        )

        val report = activation.activateAll()

        assertTrue(report.isEmpty)
    }

    @Test
    fun 一个包坏掉不影响其它包() = runTest {
        val source = FakeSource(
            plugins = listOf(plugin("bad"), plugin("good")),
            // bad 没有归档，good 正常。
            archives = mapOf("good" to mapOf(
                "manifest.json" to """{"toolpkg_id":"good","main":"index.js"}""",
                "index.js" to "// main",
            )),
        )
        val activation = PluginActivation(source, PluginRuntimeHost(ToolRegistry(), FakeRuntime()))

        val report = activation.activateAll()

        assertEquals(listOf("good"), report.loaded)
        assertEquals(listOf("bad"), report.failed.map { it.packageId })
    }

    @Test
    fun 装载结果可以被观察() = runTest {
        val activation = PluginActivation(
            FakeSource(listOf(plugin("a")), mapOf("a" to archive())),
            PluginRuntimeHost(ToolRegistry(), FakeRuntime()),
        )

        // 还没跑过时是 null：界面据此区分「还没装载」与「装载完了但一个都没有」。
        assertNull(activation.report.value)

        activation.activateAll()

        assertEquals(listOf("a"), activation.report.value!!.loaded)
    }

    @Test
    fun 清单缺少_toolpkg_id_时记为失败() = runTest {
        // 真机教训：清单校验失败不抛异常，只记 problems。
        // 之前只看异常，于是这种包被当成「装载成功」。
        val broken = mapOf(
            "manifest.json" to """{"id":"a","main":"index.js"}""",
            "index.js" to "// main",
        )
        val activation = PluginActivation(
            FakeSource(listOf(plugin("a")), mapOf("a" to broken)),
            PluginRuntimeHost(ToolRegistry(), FakeRuntime()),
        )

        val report = activation.activateAll()

        assertTrue("不该算装载成功", report.loaded.isEmpty())
        assertEquals(1, report.failed.size)
        assertTrue("原因要指出清单问题：" + report.failed[0].errors.toString(), report.failed[0].errors.any { it.contains("清单问题") })
    }
}
