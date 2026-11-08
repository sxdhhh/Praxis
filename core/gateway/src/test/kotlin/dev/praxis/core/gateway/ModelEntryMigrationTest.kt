package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 老模型池到结构化条目的迁移（文档 6.3）。
 *
 * ## 这一层最怕的不是报错，而是悄悄少一条
 *
 * 文档要求 modelIndex 语义保留。少一条、换一次序、合并一次重复项，下标就全错位：
 * 用户升级后打开对话，用的模型跟他选的不是同一个 —— 而且他不会收到任何提示。
 */
class ModelEntryMigrationTest {

    private val provider = "openai"

    @Test
    fun 等长同序保住下标语义() {
        val pool = listOf("gpt-a", "gpt-b", "gpt-c")

        val result = ModelEntryMigration.migrate(pool, provider)

        assertEquals(3, result.entries.size)
        assertEquals(pool, result.entries.map { it.id })
    }

    @Test
    fun 补上档案级的字段() {
        val entry = ModelEntryMigration.migrate(listOf("gpt-a"), provider).entries.single()

        assertEquals(provider, entry.provider)
        assertEquals("gpt-a", entry.name)
        assertEquals(ModelSource.Manual, entry.source)
        assertTrue(entry.enabled)
        assertEquals(ModelKind.Chat, entry.kind)
        assertNull(entry.credits)
    }

    @Test
    fun 能力位留空表示未声明() {
        val entry = ModelEntryMigration.migrate(listOf("gpt-a"), provider).entries.single()

        // 填 false 会把「不知道」说成「不支持」—— 老账号一个能力位都没声明过，
        // 那样会让所有带图请求无号可用（文档 7.2 的缺键放行就失效了）。
        assertNull(entry.supportsImages)
        assertNull(entry.supportsToolCall)
        assertNull(entry.supportsReasoning)
        assertNull(entry.maxInputTokens)
        assertTrue(entry.inputModalities.isEmpty())
    }

    @Test
    fun 空段被保留但下标被报出来() {
        val result = ModelEntryMigration.migrate(listOf("gpt-a", "", "gpt-c"), provider)

        // 删掉空段会让 gpt-c 从下标 2 变成 1，当前模型下标就指错了。
        assertEquals(3, result.entries.size)
        assertEquals(listOf(1), result.blankIndices)
    }

    @Test
    fun 重复项不合并() {
        val result = ModelEntryMigration.migrate(listOf("gpt-a", "gpt-a"), provider)

        assertEquals("合并会让下标偏移", 2, result.entries.size)
    }

    @Test
    fun 不改名字里的空格() {
        val result = ModelEntryMigration.migrate(listOf("  gpt-a  "), provider)

        assertEquals("trim 等于改名", "  gpt-a  ", result.entries.single().id)
    }

    @Test
    fun 空池得到空列表() {
        val result = ModelEntryMigration.migrate(emptyList(), provider)

        assertTrue(result.entries.isEmpty())
        assertTrue(result.blankIndices.isEmpty())
    }

    @Test
    fun 全是空段时逐一报出() {
        val result = ModelEntryMigration.migrate(listOf("", " "), provider)

        assertEquals(listOf(0, 1), result.blankIndices)
        assertEquals(2, result.entries.size)
    }
}
