package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 落盘层的规格。
 *
 * 这里跑的是**真实文件读写**（临时目录），不是 mock：导出最容易出的两类事故
 * ——「嵌套目录没建，文件写到一半失败」与「路径越界覆盖了别处」—— 都只有真写才暴露。
 */
class GatewayExportWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun plan(vararg files: Pair<String, String>) = GatewayExportPlan(
        files = files.map { (path, content) -> GatewayExportFile(path, content) },
        notes = listOf("apiKeys 未包含"),
    )

    @Test
    fun `写入嵌套目录并原样保留内容`() {
        val root = File(tmp.root, "exports")
        val result = GatewayExportWriter.write(
            root,
            plan("accounts/openai-工作号-20261108-001.json" to "{\"name\":\"工作号\"}"),
        )

        val written = File(root, "accounts/openai-工作号-20261108-001.json")
        assertTrue("文件应该被写出来", written.isFile)
        assertEquals("{\"name\":\"工作号\"}", written.readText())
        assertEquals(listOf("accounts/openai-工作号-20261108-001.json"), result.writtenPaths)
        assertEquals(root.canonicalPath, result.rootPath)
    }

    @Test
    fun `根目录不存在时会被创建`() {
        val root = File(tmp.root, "a/b/exports")
        assertFalse(root.exists())
        GatewayExportWriter.write(root, plan("gateway-config.json" to "{}"))
        assertTrue(root.isDirectory)
    }

    @Test
    fun `越界路径被拒绝而不是写到目录外`() {
        val root = File(tmp.root, "exports")
        val evil = plan("../escaped.json" to "x")
        var thrown: IllegalArgumentException? = null
        try {
            GatewayExportWriter.write(root, evil)
        } catch (e: IllegalArgumentException) {
            thrown = e
        }
        assertTrue("应当抛 IllegalArgumentException", thrown != null)
        assertFalse("不能写到目录外", File(tmp.root, "escaped.json").exists())
    }

    @Test
    fun `说明原样带回给界面`() {
        val result = GatewayExportWriter.write(File(tmp.root, "exports"), plan("gateway-config.json" to "{}"))
        assertEquals(listOf("apiKeys 未包含"), result.notes)
    }

    @Test
    fun `重复导出同一个计划不会失败且内容一致`() {
        val root = File(tmp.root, "exports")
        val p = plan("accounts/a.json" to "first")
        GatewayExportWriter.write(root, p)
        val second = GatewayExportWriter.write(root, plan("accounts/a.json" to "second"))
        assertEquals(listOf("accounts/a.json"), second.writtenPaths)
        assertEquals("second", File(root, "accounts/a.json").readText())
    }
}
