package dev.praxis.feature.chat

import dev.praxis.feature.chat.editor.ParsedMessagePart
import dev.praxis.feature.chat.editor.PartType
import dev.praxis.feature.chat.editor.parseMessageContentForEditor
import dev.praxis.feature.chat.editor.recomposeMessageFromParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `MessageEditor` 的片段双向转换（纯函数，不需 Android 环境）。
 *
 * ## 为什么这两个函数必须先有测试
 *
 * 它们是**有损往返**的入口：用户点开编辑器再点保存，数据会走
 * 「原文 → 片段 → 原文」一圈。任何一个环节漏东西，现象是
 * 「我只是看了一眼没改，内容却变了」—— 这是最难被接受的一类缺陷。
 *
 * 所以这里锁三件事：
 *  1. 无标签文本 → 一个 TEXT 片段 → 拼回原样；
 *  2. 带标签 → XML 片段（tag / attributes / content 三项都对）→ 拼回原样；
 *  3. 属性原样保留（不规范化、不重排序）。
 */
class MessageEditorPartsTest {

    @Test
    fun `无标签文本往返不变`() {
        val raw = "就是一段普通文本，没有标签。"
        assertEquals(raw, recomposeMessageFromParts(parseMessageContentForEditor(raw)))
    }

    @Test
    fun `单个标签往返不变`() {
        val raw = "<think>思考内容</think>"
        val parts = parseMessageContentForEditor(raw)
        assertEquals(1, parts.size)
        assertEquals(PartType.XML, parts[0].type)
        assertEquals("think", parts[0].tag)
        assertEquals("思考内容", parts[0].content)
        assertEquals(raw, recomposeMessageFromParts(parts))
    }

    @Test
    fun `文本与标签混合往返不变`() {
        val raw = "前面的话<tool>{\"a\":1}</tool>后面的话"
        val parts = parseMessageContentForEditor(raw)
        assertEquals(3, parts.size)
        assertEquals(PartType.TEXT, parts[0].type)
        assertEquals(PartType.XML, parts[1].type)
        assertEquals(PartType.TEXT, parts[2].type)
        assertEquals(raw, recomposeMessageFromParts(parts))
    }

    @Test
    fun `属性原样保留`() {
        // 注意：解析正则的第二组包含前导空格，所以 attributes 是 ` color="red"`。
        val raw = "<font color=\"red\">红字</font>"
        val parts = parseMessageContentForEditor(raw)
        assertEquals("font", parts[0].tag)
        assertEquals(" color=\"red\"", parts[0].attributes)
        // 回写不能多补空格（否则每次保存都会多一个空格）。
        assertEquals(raw, recomposeMessageFromParts(parts))
    }

    @Test
    fun `多行标签内容不会被切碎`() {
        val raw = "<think>第一行\n第二行\n第三行</think>"
        val parts = parseMessageContentForEditor(raw)
        assertEquals(1, parts.size)
        assertTrue("应当保留内部换行", parts[0].content.contains("\n第二行\n"))
        assertEquals(raw, recomposeMessageFromParts(parts))
    }

    @Test
    fun `不配对的标签不当成一对`() {
        // `<a>` 后面跟 `</b>`：正则用了反向引用，所以这里不该产生 XML 片段。
        val raw = "<a>内容</b>"
        val parts = parseMessageContentForEditor(raw)
        // 全部当成纯文本（不做任何 "修复"），这样往返仍然无损。
        assertEquals(1, parts.size)
        assertEquals(PartType.TEXT, parts[0].type)
        assertEquals(raw, recomposeMessageFromParts(parts))
    }

    @Test
    fun `相邻标签间的空白片段被丢弃`() {
        val raw = "<a>1</a>\n\n<b>2</b>"
        val parts = parseMessageContentForEditor(raw)
        // 两个 XML + 中间纯空白被丢 → 只剩 2 个片段。
        assertEquals(2, parts.size)
        assertEquals(PartType.XML, parts[0].type)
        assertEquals(PartType.XML, parts[1].type)
    }

    @Test
    fun `空文本得到空片段列表`() {
        assertEquals(0, parseMessageContentForEditor("").size)
        assertEquals("", recomposeMessageFromParts(emptyList()))
    }

    @Test
    fun `手工构造的片段能正确拼回`() {
        val parts = listOf(
            ParsedMessagePart(PartType.TEXT, "A"),
            ParsedMessagePart(PartType.XML, "B", "tag", " attr=\"1\""),
            ParsedMessagePart(PartType.XML, "C", "tag2", null),
        )
        assertEquals("A<tag attr=\"1\">B</tag><tag2>C</tag2>", recomposeMessageFromParts(parts))
    }
}
