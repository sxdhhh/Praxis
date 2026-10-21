package dev.praxis.core.data.log

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁死「写」和「读」共用同一份格式。
 *
 * ## 为什么这个测试比 UI 测试更值钱
 *
 * 日志页最容易出现的失效不是样式错，而是**写了但读不出来**：
 * 磁盘上有文件、页面上是「文件为空」。此时所有单测都绿、UI 也不报错。
 * 根因永远是写端正则和读端正则不一致（改了一边忘了另一边）。
 *
 * 这一组用例的做法是：用写端 [LogLine.format] 真的渲染出文本，
 * 再喂给读端 [LogParser.parse]，断言字段一个不差。任何一边改了格式，
 * 这个测试立刻红，而不是等到用户打开日志页看到空白。
 */
class LogParserTest {

    private fun at(second: Int = 22) = LocalDateTime.of(2026, 10, 21, 10, 36, second, 123_000_000)

    @Test
    fun `format and parse round trip keeps every field`() {
        val rendered = LogLine.format('E', "ChatViewModel", "上游返回 500", at())
        // 顺带锁死字面格式：改格式必须同时改这里，防止「悄悄变了但测试还绿」。
        assertEquals("2026-10-21 10:36:22.123 E/Praxis ChatViewModel: 上游返回 500", rendered)

        val entries = LogParser.parse(rendered)
        assertEquals(1, entries.size)
        val e = entries.single()
        assertEquals("10:36:22.123", e.time)
        assertEquals('E', e.level)
        assertEquals("ChatViewModel", e.tag)
        assertEquals("上游返回 500", e.message)
    }

    @Test
    fun `colons inside message do not eat the tag`() {
        // 真实场景：URL 与键值对里全是冒号。tag 必须只切到第一个冒号。
        val rendered = LogLine.format('I', "Http", "GET https://example.com:8443/a: b", at())
        val e = LogParser.parse(rendered).single()
        assertEquals("Http", e.tag)
        assertEquals("GET https://example.com:8443/a: b", e.message)
    }

    @Test
    fun `multi line message folds continuation lines into one entry`() {
        val stack = "java.lang.IllegalStateException: boom\n\tat A.b(A.kt:1)\n\tat C.d(C.kt:2)"
        val rendered = LogLine.format('E', "Repo", stack, at())
        val entries = LogParser.parse(rendered)
        // 三行文本 → 一条卡片（堆栈不拆成三条，否则日志页全是无来源的孤立行）。
        assertEquals(1, entries.size)
        assertEquals(stack, entries.single().message)
    }

    @Test
    fun `empty lines inside a stack trace are preserved`() {
        val rendered = LogLine.format('E', "Repo", "head\n\n\ttail", at())
        val e = LogParser.parse(rendered).single()
        assertEquals("head\n\n\ttail", e.message)
    }

    @Test
    fun `null tag round trips as literal null text and parses back to null`() {
        // 写端把 null 渲染成字面 "null"，读端再把 "null" 归一为 null。
        // 这条链路不能断：断了日志卡片会显示一个叫 "null" 的 tag。
        val rendered = LogLine.format('D', null, "no tag", at())
        assertTrue(rendered.contains(" D/Praxis null: no tag"))
        assertNull(LogParser.parse(rendered).single().tag)
    }

    @Test
    fun `orphan lines before any entry become a metadata-less card`() {
        // 文件被截断成「从堆栈中间开始」时的形态：不能丢行。
        val text = "\tat X.y(X.kt:9)\n" + LogLine.format('I', "T", "ok", at())
        val entries = LogParser.parse(text)
        assertEquals(2, entries.size)
        assertNull(entries[0].level)
        assertEquals("\tat X.y(X.kt:9)", entries[0].message)
        assertEquals("T", entries[1].tag)
    }

    @Test
    fun `trailing newline does not append an empty line to the last entry`() {
        // 日志文件每条记录都以 \n 结尾。Kotlin 的 lineSequence 保留末尾空串，
        // 不处理就会把 "" 并进最后一条 → 卡片正文多一个换行（真机上最后一张比别的高一截）。
        val text = LogLine.format('I', "T", "last", at()) + "\n"
        val entries = LogParser.parse(text)
        assertEquals(1, entries.size)
        assertEquals("last", entries.single().message)
    }

    @Test
    fun `blank file yields no entries`() {
        assertTrue(LogParser.parse("").isEmpty())
        assertTrue(LogParser.parse("\n\n").isEmpty())
    }

    @Test
    fun `malformed timestamp line stays as plain text`() {
        // 少一位毫秒 → 不匹配 → 落进「续行/孤立行」分支，而不是被丢掉。
        val text = "2026-10-21 10:36:22.12 E/Praxis T: short millis"
        val e = LogParser.parse(text).single()
        assertNull(e.level)
        assertEquals(text, e.message)
    }

    @Test
    fun `all levels are recognised`() {
        for (level in listOf('V', 'D', 'I', 'W', 'E', 'A', 'F')) {
            val e = LogParser.parse(LogLine.format(level, "T", "m", at())).single()
            assertEquals(level, e.level)
        }
    }
}