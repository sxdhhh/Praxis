package dev.praxis.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 待发队列的顺序调整。
 *
 * 这些用例锁的是「越界时不能崩、也不能静默改变内容」——
 * 队列是用户随手记下的想法，丢一条比排序错更严重。
 */
class PendingQueueTest {

    private fun q(vararg texts: String): PendingQueue =
        texts.foldIndexed(PendingQueue()) { index, acc, text ->
            PendingQueueOps.enqueue(acc, index.toLong(), text)
        }

    @Test
    fun `入队保持顺序且忽略空白`() {
        val queue = q("一", "二", "   ", "三")
        assertEquals(listOf("一", "二", "三"), queue.items.map { it.text })
        assertEquals(3, queue.size)
    }

    @Test
    fun `超过上限不静默丢弃（返回原实例）`() {
        var queue = PendingQueue()
        repeat(PendingQueue.MAX_SIZE) { queue = PendingQueueOps.enqueue(queue, it.toLong(), "m$it") }
        val full = queue
        val result = PendingQueueOps.enqueue(queue, 999L, "溢出")
        // 用 === 判断「没变化」：调用方据此决定是否提示用户，而不是靠 size 猜。
        assertSame(full, result)
        assertEquals(PendingQueue.MAX_SIZE, result.size)
    }

    @Test
    fun `上移首位无变化`() {
        val queue = q("一", "二")
        assertSame(queue, PendingQueueOps.moveUp(queue, 0L))
    }

    @Test
    fun `上移正常交换`() {
        val queue = q("一", "二", "三")
        val moved = PendingQueueOps.moveUp(queue, 2L)
        assertEquals(listOf("一", "三", "二"), moved.items.map { it.text })
    }

    @Test
    fun `下移末位无变化`() {
        val queue = q("一", "二")
        assertSame(queue, PendingQueueOps.moveDown(queue, 1L))
    }

    @Test
    fun `下移正常交换`() {
        val queue = q("一", "二", "三")
        val moved = PendingQueueOps.moveDown(queue, 0L)
        assertEquals(listOf("二", "一", "三"), moved.items.map { it.text })
    }

    @Test
    fun `置顶与幂等`() {
        val queue = q("一", "二", "三")
        assertEquals(listOf("三", "一", "二"), PendingQueueOps.moveToTop(queue, 2L).items.map { it.text })
        // 已经在首位：返回原实例（不触发一次无谓重组）。
        assertSame(queue, PendingQueueOps.moveToTop(queue, 0L))
    }

    @Test
    fun `未知 id 的所有操作都不改变队列`() {
        val queue = q("一", "二")
        assertSame(queue, PendingQueueOps.moveUp(queue, 404L))
        assertSame(queue, PendingQueueOps.moveDown(queue, 404L))
        assertSame(queue, PendingQueueOps.moveToTop(queue, 404L))
        assertEquals(2, PendingQueueOps.remove(queue, 404L).size)
    }

    @Test
    fun `poll 取出队首并移除`() {
        val queue = q("一", "二")
        val (head, rest) = PendingQueueOps.poll(queue)
        assertNotNull(head)
        assertEquals("一", head?.text)
        assertEquals(listOf("二"), rest.items.map { it.text })
    }

    @Test
    fun `poll 空队列返回 null 且不改变`() {
        val empty = PendingQueue()
        val (head, rest) = PendingQueueOps.poll(empty)
        assertNull(head)
        assertSame(empty, rest)
    }

    @Test
    fun `preview 压成单行并截断`() {
        val long = QueuedMessage(0L, "第一行\n" + "字".repeat(60))
        assertFalse(long.preview.contains('\n'))
        assertTrue(long.preview.endsWith("…"))
        assertEquals(40, long.preview.length)
    }
    // ── 编辑 / 重排（2026-11-06）──────────────────────────────────

    /**
     * 编辑成空串时**保留原正文**。
     *
     * 「编辑成空」与「删除」是两个意图。编辑弹窗清空后点确定，
     * 用户以为只是没改到内容；若这里顺手删掉条目，那条排队就凭空消失了。
     */
    @Test
    fun `编辑成空不删条目`() {
        val queue = PendingQueue(items = listOf(QueuedMessage(1L, "原文")))
        val next = PendingQueueOps.updateText(queue, 1L, "   ")
        assertEquals("原文", next.items.single().text)
    }

    @Test
    fun `编辑替换正文且不动其他条`() {
        val queue = PendingQueue(
            items = listOf(QueuedMessage(1L, "a"), QueuedMessage(2L, "b")),
        )
        val next = PendingQueueOps.updateText(queue, 2L, "改了")
        assertEquals("a", next.items[0].text)
        assertEquals("改了", next.items[1].text)
    }

    @Test
    fun `重排按给定顺序`() {
        val queue = PendingQueue(
            items = listOf(QueuedMessage(1L, "a"), QueuedMessage(2L, "b"), QueuedMessage(3L, "c")),
        )
        val next = PendingQueueOps.reorder(queue, listOf(3L, 1L, 2L))
        assertEquals(listOf(3L, 1L, 2L), next.items.map { it.id })
    }

    /**
     * 重排时缺失的条目必须**追加在末尾**而不是丢掉。
     *
     * 拖拽期间若有新消息入队，UI 传来的 id 列表可能少一条。
     * 直接按传入列表重建会静默丢掉那条 —— 用户排的消息就没了。
     */
    @Test
    fun `重排不丢未列出的条目`() {
        val queue = PendingQueue(
            items = listOf(QueuedMessage(1L, "a"), QueuedMessage(2L, "b"), QueuedMessage(3L, "c")),
        )
        val next = PendingQueueOps.reorder(queue, listOf(3L, 1L))
        assertEquals(listOf(3L, 1L, 2L), next.items.map { it.id })
    }

    @Test
    fun `空重排返回原实例`() {
        val queue = PendingQueue(items = listOf(QueuedMessage(1L, "a")))
        assertSame(queue, PendingQueueOps.reorder(queue, emptyList()))
    }

    /** 类型由 images 派生，两者不可能不一致。 */
    @Test
    fun `带图的条目类型为 WithImages`() {
        val item = QueuedMessage(
            id = 1L,
            text = "x",
            images = listOf(dev.praxis.core.ai.ChatImage(mimeType = "image/png", base64 = "AA==")),
        )
        assertEquals(QueueItemKind.WithImages, item.kind)
    }
}