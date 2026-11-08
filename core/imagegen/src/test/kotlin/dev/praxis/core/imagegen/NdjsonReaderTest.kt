package dev.praxis.core.imagegen

import java.io.StringReader
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NDJSON 解析与分发（文档 §3.3）。
 *
 * ## 为什么这一层要写满
 *
 * 它接的是**一个我们控制不了的进程**：断流、半行、未知消息、超大帧都会真的发生，
 * 而且只在真机上偶发 —— 到那时日志里只剩一句「没出图」。
 * 把解析做成纯函数之后，这些情况全部能在 JVM 里穷举，真机上只剩网络与进程两件事。
 */
class NdjsonReaderTest {

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun progress帧解析出数值与预览() {
        val preview = b64(byteArrayOf(1, 2, 3))

        val event = NdjsonReader.parseLine("""{"type":"progress","progress":0.42,"image":"$preview"}""")

        assertEquals(ImageGenEvent.Progress(value = 0.42f, previewJpegBase64 = preview), event)
    }

    @Test
    fun image字段缺失时预览为null() {
        val event = NdjsonReader.parseLine("""{"type":"progress","progress":0.1}""")

        assertEquals(ImageGenEvent.Progress(value = 0.1f, previewJpegBase64 = null), event)
    }

    @Test
    fun image字段为空串时预览也算缺失() {
        // 引擎「不推帧」可能表现为不写字段，也可能写成空串；两种都必须是 null，
        // 否则 UI 会去 decode 一个空字符串然后崩在 BitmapFactory 里。
        val event = NdjsonReader.parseLine("""{"type":"progress","progress":0.1,"image":""}""")

        assertEquals(ImageGenEvent.Progress(value = 0.1f, previewJpegBase64 = null), event)
    }

    @Test
    fun complete帧解出图字节与seed() {
        val payload = b64(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00))

        val event = NdjsonReader.parseLine(
            """{"type":"complete","image":"$payload","seed":2001048,"width":512,"height":768}""",
        )

        assertEquals(ImageGenEvent.Complete(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00), 2001048L, 512, 768), event)
    }

    @Test
    fun complete相等按字节内容比较() {
        // data class 的默认 equals 比数组引用，重试拿到同一张图会被判成「不一样」。
        val a = ImageGenEvent.Complete(byteArrayOf(1, 2, 3), 7L, 512, 512)
        val b = ImageGenEvent.Complete(byteArrayOf(1, 2, 3), 7L, 512, 512)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun 半行截断的整行解析返回null而不是抛异常() {
        val half = """{"type":"progress","progress":0.9,"image":"AAAA"""

        assertNull(NdjsonReader.parseLine(half))
    }

    @Test
    fun 流结束在半个JSON上时只丢最后一行() {
        val stream = """{"type":"progress","progress":0.1}
{"type":"progress","progress":0.2}
{"type":"complete","image":"AAAA"""

        val events = NdjsonReader.read(StringReader(stream)).toList()

        assertEquals(2, events.size)
        assertEquals(ImageGenEvent.Progress(0.1f, null), events[0])
        assertEquals(ImageGenEvent.Progress(0.2f, null), events[1])
    }

    @Test
    fun 空行与纯空白行返回null() {
        assertNull(NdjsonReader.parseLine(""))
        assertNull(NdjsonReader.parseLine("   "))
        assertNull(NdjsonReader.parseLine("\t"))
    }

    @Test
    fun 未知type返回null而不是崩() {
        // 引擎换代多出一种消息（比如 3.0.0 的新事件）时，旧客户端必须忽略它。
        assertNull(NdjsonReader.parseLine("""{"type":"preview_frame","progress":0.5}"""))
    }

    @Test
    fun type缺失返回null() {
        assertNull(NdjsonReader.parseLine("""{"progress":0.5}"""))
        assertNull(NdjsonReader.parseLine("""{"type":123,"progress":0.5}"""))
    }

    @Test
    fun 非JSON的错误页返回null() {
        // 端口上如果是别的服务，返回的可能是 HTML。
        assertNull(NdjsonReader.parseLine("<!DOCTYPE html><html><body>404</body></html>"))
        assertNull(NdjsonReader.parseLine("Internal Server Error"))
    }

    @Test
    fun JSON数组返回null() {
        assertNull(NdjsonReader.parseLine("[1,2,3]"))
        assertNull(NdjsonReader.parseLine("\"just a string\""))
    }

    @Test
    fun progress不是数字时返回null() {
        assertNull(NdjsonReader.parseLine("""{"type":"progress","progress":"half"}"""))
        assertNull(NdjsonReader.parseLine("""{"type":"progress"}"""))
    }

    @Test
    fun progress越界被夹进区间() {
        assertEquals(
            ImageGenEvent.Progress(1f, null),
            NdjsonReader.parseLine("""{"type":"progress","progress":1.5}"""),
        )
        assertEquals(
            ImageGenEvent.Progress(0f, null),
            NdjsonReader.parseLine("""{"type":"progress","progress":-0.2}"""),
        )
    }

    @Test
    fun 超大base64行能完整解出() {
        // 文档 §12：1024² 的 JPEG 完成图 base64 之后就是几百 KB 的单行。
        val bytes = ByteArray(400_000) { (it % 251).toByte() }
        val payload = b64(bytes)

        val event = NdjsonReader.parseLine(
            """{"type":"complete","image":"$payload","seed":1,"width":1024,"height":1024}""",
        ) as ImageGenEvent.Complete

        assertEquals(400_000, event.bytes.size)
        assertTrue(event.bytes.contentEquals(bytes))
    }

    @Test
    fun complete缺image时是空字节而不是崩() {
        val event = NdjsonReader.parseLine("""{"type":"complete","seed":1}""") as ImageGenEvent.Complete

        assertEquals(0, event.bytes.size)
        assertEquals(1L, event.seed)
        assertEquals(0, event.width)
    }

    @Test
    fun complete的base64坏掉时仍然产出事件() {
        // 「图坏了」与「没有图」必须可区分：前者该报错，后者该重试。
        val event = NdjsonReader.parseLine(
            """{"type":"complete","image":"这不是base64!!!","seed":5,"width":512,"height":512}""",
        ) as ImageGenEvent.Complete

        assertEquals(0, event.bytes.size)
        assertEquals(5L, event.seed)
        assertEquals(512, event.width)
    }

    @Test
    fun read按顺序产出多行事件() {
        val preview = b64(byteArrayOf(9, 9))
        val image = b64(byteArrayOf(7, 7, 7))
        val stream = """{"type":"progress","progress":0.25}
{"type":"progress","progress":0.5,"image":"$preview"}
{"type":"heartbeat"}

{"type":"complete","image":"$image","seed":42,"width":512,"height":512}"""

        val events = NdjsonReader.read(StringReader(stream)).toList()

        assertEquals(3, events.size)
        assertEquals(ImageGenEvent.Progress(0.25f, null), events[0])
        assertEquals(ImageGenEvent.Progress(0.5f, preview), events[1])
        assertEquals(ImageGenEvent.Complete(byteArrayOf(7, 7, 7), 42L, 512, 512), events[2])
    }

    @Test
    fun CRLF行尾也能解析() {
        val stream = "{\"type\":\"progress\",\"progress\":0.3}\r\n{\"type\":\"progress\",\"progress\":0.6}\r\n"

        assertEquals(2, NdjsonReader.read(StringReader(stream)).toList().size)
        assertEquals(
            ImageGenEvent.Progress(0.3f, null),
            NdjsonReader.parseLine("""{"type":"progress","progress":0.3}""" + "\r"),
        )
    }

    @Test
    fun parseLines对同一序列与read给出一致结果() {
        val lines = listOf(
            """{"type":"progress","progress":0.1}""",
            "",
            """{"type":"progress","progress":0.9}""",
        )

        assertEquals(
            listOf(ImageGenEvent.Progress(0.1f, null), ImageGenEvent.Progress(0.9f, null)),
            NdjsonReader.parseLines(lines.asSequence()).toList(),
        )
    }
}
