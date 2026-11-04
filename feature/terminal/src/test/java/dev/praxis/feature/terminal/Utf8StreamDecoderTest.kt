package dev.praxis.feature.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Utf8StreamDecoder] 的行为契约。
 *
 * ## 为什么值得单独测（2026-11-05）
 *
 * 真机反馈「终端有时会出现乱码字符 / 只是部分乱码」。
 * 根因是每个字节块被独立解码 —— 跨块的 UTF-8 字符会变成 `�`。
 * 这个类把它改成增量解码，而「跨块」这件事**在单测里才能稳定复现**：
 * 真机上取决于内核怎么切块，不可控。
 */
class Utf8StreamDecoderTest {

    @Test
    fun `ascii passes through`() {
        val d = Utf8StreamDecoder()
        assertEquals("hello", d.decode("hello".toByteArray()))
    }

    /**
     * 核心用例：一个 3 字节的中文字符被切成两块。
     *
     * 「你」= E4 BD A0。第一块只给前两字节，第二块给第三字节。
     * 旧实现会得到 "��"；正确实现要得到 "你"。
     */
    @Test
    fun `chinese char split across two chunks is restored`() {
        val d = Utf8StreamDecoder()
        val bytes = "你".toByteArray(Charsets.UTF_8)
        assertEquals(3, bytes.size, "前提：「你」是 3 字节")

        val first = d.decode(bytes.copyOfRange(0, 2))
        val second = d.decode(bytes.copyOfRange(2, 3))

        // 第一块全是「半个字符」：按契约应返回空串，而不是替换字符。
        assertEquals("", first)
        assertEquals("你", second)
        assertTrue("\uFFFD" !in (first + second), "不该出现替换字符")
    }

    /** 前一个字符的后半 + 后一个字符，混在同一块里。 */
    @Test
    fun `tail of one char and full next char in same chunk`() {
        val d = Utf8StreamDecoder()
        val a = "你".toByteArray(Charsets.UTF_8)
        val b = "好".toByteArray(Charsets.UTF_8)

        d.decode(a.copyOfRange(0, 2))
        // 第二块 = 「你」的第 3 字节 + 完整的「好」
        val out = d.decode(a.copyOfRange(2, 3) + b)
        assertEquals("你好", out)
    }

    /** 逐字节喂：最极端的分块方式，也必须还原。 */
    @Test
    fun `byte by byte feeding reconstructs the text`() {
        val d = Utf8StreamDecoder()
        val text = "终端乱码测试 🚀"
        val sb = StringBuilder()
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            sb.append(d.decode(byteArrayOf(byte)))
        }
        assertEquals(text, sb.toString())
    }

    /** 空块返回空串（调用方据此跳过 ANSI 扫描）。 */
    @Test
    fun `empty chunk yields empty string`() {
        val d = Utf8StreamDecoder()
        assertEquals("", d.decode(ByteArray(0)))
    }

    /**
     * 非法字节不能抛异常。
     *
     * 真终端里 `cat` 一个二进制文件是常态。抛异常会断掉整条输出流 ——
     * 那是把「显示一个 �」升级成「终端没反应」。
     */
    @Test
    fun `malformed bytes are replaced not thrown`() {
        val d = Utf8StreamDecoder()
        // 0xFF 在 UTF-8 里永远非法
        val out = d.decode(byteArrayOf(0x41, 0xFF.toByte(), 0x42))
        assertTrue(out.startsWith("A"), "合法前缀要保留：$out")
        assertTrue(out.endsWith("B"), "合法后缀要保留：$out")
    }

    /** 两个解码器互不影响（各自记住自己的半个字符）。 */
    @Test
    fun `instances do not share state`() {
        val a = Utf8StreamDecoder()
        val b = Utf8StreamDecoder()
        val bytes = "你".toByteArray(Charsets.UTF_8)

        a.decode(bytes.copyOfRange(0, 2))
        // b 没收到过前两字节，直接喂第 3 字节只会得到一个替换字符。
        val fromB = b.decode(bytes.copyOfRange(2, 3))
        assertEquals("\uFFFD", fromB)
    }
}
