package dev.praxis.core.tool.builtin

import dev.praxis.core.tool.PermissionTier
import dev.praxis.core.tool.Tool
import dev.praxis.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 文件工具的沙箱边界与错误语义。 */
class FileToolsTest {

    private lateinit var root: File
    private lateinit var tools: List<Tool>

    private val ctx = object : ToolContext {
        override val tier = PermissionTier.STANDARD
    }

    @Before
    fun setUp() {
        root = Files.createTempDirectory("praxis-tool-test").toFile()
        tools = FileTools(root).all()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun tool(name: String): Tool = tools.first { it.spec.name == name }

    @Test
    fun `reading outside root is rejected`() = runTest {
        val r = tool("read_file").execute("{\"path\":\"../secret.txt\"}", ctx)
        assertTrue(r.isError)
        assertTrue(r.content.contains("越界"))
    }

    @Test
    fun `absolute path outside root is rejected`() = runTest {
        val r = tool("read_file").execute("{\"path\":\"/etc/passwd\"}", ctx)
        assertTrue(r.isError)
        assertTrue(r.content.contains("越界"))
    }

    @Test
    fun `write then read round trips utf8`() = runTest {
        tool("write_file").execute("{\"path\":\"sub/a.txt\",\"content\":\"hello\"}", ctx)
        val r = tool("read_file").execute("{\"path\":\"sub/a.txt\"}", ctx)
        assertEquals("hello", r.content)
    }

    @Test
    fun `reading missing file is error result`() = runTest {
        val r = tool("read_file").execute("{\"path\":\"nope.txt\"}", ctx)
        assertTrue(r.isError)
        assertTrue(r.content.contains("不存在"))
    }

    @Test
    fun `missing required parameter yields readable error`() = runTest {
        val r = tool("read_file").execute("{}", ctx)
        assertTrue(r.isError)
        assertTrue(r.content.contains("缺少必需"))
    }

    @Test
    fun `delete refuses directories`() = runTest {
        File(root, "adir").mkdirs()
        val r = tool("delete_file").execute("{\"path\":\"adir\"}", ctx)
        assertTrue(r.isError)
        assertTrue(r.content.contains("不允许删除目录"))
    }
}
