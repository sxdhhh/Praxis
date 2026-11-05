package dev.praxis.feature.chat

import dev.praxis.core.ai.ChatImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PendingQueueCodec] 的往返契约。
 *
 * ## 为什么这个测试必须存在
 *
 * 队列持久化的唯一价值就是「重启后一条不差、顺序不变」。
 * 而这份编解码手写了转义（base64）与行结构 —— 手写的东西就要有测试，
 * 否则用户在排队 10 条后切后台被系统杀掉，回来发现全没了，
 * 而代码里看不出任何异常。
 */
class PendingQueueCodecTest {

    private fun msg(id: Long, text: String, images: List<ChatImage> = emptyList()) =
        QueuedMessage(id = id, text = text, images = images)

    @Test
    fun `空队列往返仍为空`() {
        val encoded = PendingQueueCodec.encode(PendingQueue())
        assertEquals(PendingQueue(), PendingQueueCodec.decode(encoded))
    }

    @Test
    fun `单条纯文本往返`() {
        val queue = PendingQueue(items = listOf(msg(1, "你好")))
        val back = PendingQueueCodec.decode(PendingQueueCodec.encode(queue))
        assertEquals(queue, back)
    }

    /**
     * 正文里包含换行 / 制表符 / 反斜杠 / 引号 / emoji —— 全是手写 JSON 会翻车的东西。
     *
     * base64 之后这些字符都不会出现在行结构里，所以必须能原样还原。
     */
    @Test
    fun `特殊字符往返`() {
        val nasty = "第一行\n第二行\t制表 \\ 反斜杠 \" 引号 🚀 \u0000 控制符"
        val queue = PendingQueue(items = listOf(msg(7, nasty)))
        val back = PendingQueueCodec.decode(PendingQueueCodec.encode(queue))
        assertEquals(nasty, back.items.single().text)
    }

    @Test
    fun `多条顺序保持不变`() {
        val queue = PendingQueue(
            items = listOf(msg(3, "a"), msg(1, "b"), msg(2, "c")),
        )
        val back = PendingQueueCodec.decode(PendingQueueCodec.encode(queue))
        // 顺序是队列的语义，不是可选项：用户调过顺序，重启后必须还是那个顺序。
        assertEquals(listOf(3L, 1L, 2L), back.items.map { it.id })
    }

    @Test
    fun `带图片的条目往返`() {
        val image = ChatImage(mimeType = "image/png", base64 = "iVBORw0KGgoAAAANSUhEUg==")
        val queue = PendingQueue(items = listOf(msg(9, "看图", listOf(image))))
        val back = PendingQueueCodec.decode(PendingQueueCodec.encode(queue))
        val item = back.items.single()
        assertEquals("看图", item.text)
        assertEquals(1, item.images.size)
        assertEquals("image/png", item.images[0].mimeType)
        assertEquals("iVBORw0KGgoAAAANSUhEUg==", item.images[0].base64)
        assertEquals(QueueItemKind.WithImages, item.kind)
    }

    /** 无图时 `kind` 必须是 Text，否则面板会画错图标。 */
    @Test
    fun `无图条目类型为 Text`() {
        assertEquals(QueueItemKind.Text, msg(1, "x").kind)
    }

    @Test
    fun `损坏存档返回空队列而不抛`() {
        assertTrue(PendingQueueCodec.decode("垃圾数据").isEmpty)
        assertTrue(PendingQueueCodec.decode("v1\nQ5\n").isEmpty)   // 声明 5 条但一条没有
        assertTrue(PendingQueueCodec.decode("v2\nQ0\n").isEmpty)   // 未知版本
        assertTrue(PendingQueueCodec.decode(null).isEmpty)
        assertTrue(PendingQueueCodec.decode("").isEmpty)
    }

    /**
     * 条数与声明不符时判无效（写了一半的存档）。
     *
     * 不做部分恢复：半份队列会静默丢掉后面几条，用户却以为都还在。
     */
    @Test
    fun `条数不符判无效`() {
        val queue = PendingQueue(items = listOf(msg(1, "a"), msg(2, "b")))
        val encoded = PendingQueueCodec.encode(queue)
        // 手工把声明的条数改成 1
        val tampered = encoded.replaceFirst("Q2", "Q1")
        assertTrue(PendingQueueCodec.decode(tampered).isEmpty)
    }
}
