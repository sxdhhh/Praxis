package dev.praxis.data

import dev.praxis.core.memory.MemoryItem
import dev.praxis.core.memory.MemoryRetriever
import dev.praxis.core.memory.MemoryStore
import dev.praxis.core.memory.MemoryWritePipeline
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeStore(
    var items: List<MemoryItem> = emptyList(),
    var boom: Boolean = false,
) : MemoryStore {
    var inserted = mutableListOf<MemoryItem>()
    var updated = mutableListOf<MemoryItem>()

    /** 与 `MemoryStore.all(spaceId)` 对齐（2026-10-29）：spaceId 为 null 表示跨空间。 */
    override suspend fun all(spaceId: String?): List<MemoryItem> {
        if (boom) error("store down")
        if (spaceId == null) return items
        return items.filter { it.spaceId == spaceId }
    }

    override suspend fun insert(item: MemoryItem): Long {
        inserted += item
        return inserted.size.toLong()
    }

    override suspend fun update(item: MemoryItem) {
        updated += item
    }

    override suspend fun delete(id: Long) = Unit

    override suspend fun findSimilar(
        content: String,
        threshold: Double,
        spaceId: String,
    ): MemoryItem? = null
}

private fun item(id: Long, text: String) = MemoryItem(
    id = id,
    content = text,
    createdAt = 1000,
    lastUsedAt = 1000,
)

class MemoryPortImplTest {

    private fun port(store: MemoryStore) = MemoryPortImpl(
        store = store,
        retriever = MemoryRetriever(),
        pipeline = MemoryWritePipeline(store) { 2000L },
        nowMs = { 2000L },
    )

    @Test
    fun `recall returns relevant memory content`() = runTest {
        val p = port(FakeStore(items = listOf(item(1, "用户喜欢喝咖啡"), item(2, "用户住在上海"))))

        val out = p.recall("咖啡")

        assertEquals(listOf("用户喜欢喝咖啡"), out)
    }

    @Test
    fun `recall respects limit`() = runTest {
        val p = port(
            FakeStore(
                items = (1L..10L).map { item(it, "咖啡$it") },
            ),
        )

        assertEquals(3, p.recall("咖啡", limit = 3).size)
    }

    @Test
    fun `recall swallows store failure and returns empty`() = runTest {
        // 关键：记忆库坏了不能让对话发不出去。
        val p = port(FakeStore(boom = true))

        assertTrue(p.recall("咖啡").isEmpty())
    }

    @Test
    fun `recall on empty store returns empty`() = runTest {
        assertTrue(port(FakeStore()).recall("咖啡").isEmpty())
    }

    @Test
    fun `remember writes through pipeline`() = runTest {
        val store = FakeStore()
        val p = port(store)

        p.remember("用户喜欢喝咖啡")

        assertEquals(1, store.inserted.size)
        assertEquals("用户喜欢喝咖啡", store.inserted.single().content)
    }

    @Test
    fun `remember swallows write failure`() = runTest {
        // pipeline 内部拒绕空内容，不应抛到聊天层。
        val store = FakeStore()
        val p = port(store)

        p.remember("   ")

        assertTrue(store.inserted.isEmpty())
    }
}
