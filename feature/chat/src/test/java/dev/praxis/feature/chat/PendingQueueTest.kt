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
}