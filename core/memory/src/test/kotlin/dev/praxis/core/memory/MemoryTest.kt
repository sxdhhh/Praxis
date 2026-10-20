package dev.praxis.core.memory

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 仅内存实现的 [MemoryStore]，用于测试写入管线。 */
private class FakeStore(initial: List<MemoryItem> = emptyList()) : MemoryStore {
    val items = initial.toMutableList()
    var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1

    override suspend fun all(): List<MemoryItem> = items.toList()

    override suspend fun insert(item: MemoryItem): Long {
        val id = nextId++
        items += item.copy(id = id)
        return id
    }

    override suspend fun update(item: MemoryItem) {
        val index = items.indexOfFirst { it.id == item.id }
        if (index >= 0) items[index] = item
    }

    override suspend fun delete(id: Long) {
        items.removeAll { it.id == id }
    }

    override suspend fun findSimilar(content: String, threshold: Double): MemoryItem? =
        items.firstOrNull { jaccardSimilarity(it.content, content) >= threshold }
}

class MemoryRetrieverTest {

    private val retriever = MemoryRetriever()

    private fun item(id: Long, content: String, tags: List<String> = emptyList(), createdAt: Long = 0, lastUsed: Long = 0, hits: Int = 0) =
        MemoryItem(id = id, content = content, tags = tags, createdAt = createdAt, lastUsedAt = lastUsed, hitCount = hits)

    @Test
    fun `empty candidates returns empty`() {
        assertTrue(retriever.retrieve(emptyList(), MemoryQuery("x")).isEmpty())
    }

    @Test
    fun `keyword match ranks higher than unrelated`() {
        val items = listOf(
            item(1, "用户喜欢喝咖啡"),
            item(2, "用户住在上海"),
        )
        val hits = retriever.retrieve(items, MemoryQuery("咖啡"))

        assertEquals(1L, hits.first().item.id)
    }

    @Test
    fun `more keyword occurrences score higher`() {
        val items = listOf(
            item(1, "猫"),
            item(2, "猫猫猫"),
        )
        val hits = retriever.retrieve(items, MemoryQuery("猫"))

        assertEquals(2L, hits.first().item.id)
    }

    @Test
    fun `recency does not pull in unrelated items when there are relevant hits`() {
        val items = listOf(
            // 与查询相关
            item(1, "用户喜欢喝咖啡", createdAt = 100),
            // 与查询完全无关，但很新
            item(2, "今天天气不错", createdAt = 10_000),
        )
        val hits = retriever.retrieve(items, MemoryQuery("咖啡", limit = 8))

        // 关键：无关记忆不能因为「新」被塞进结果。
        assertEquals(1, hits.size)
        assertEquals(1L, hits.single().item.id)
    }

    @Test
    fun `recency ranks within relevant set`() {
        val items = listOf(
            item(1, "用户喜欢喝咖啡", createdAt = 100),
            item(2, "用户也喜欢喝咖啡", createdAt = 9_000),
        )
        val hits = retriever.retrieve(items, MemoryQuery("咖啡", limit = 8))

        // 两条都相关，较新的那条排前。
        assertEquals(2L, hits.first().item.id)
    }

    @Test
    fun `recency boosts older-but-recently-used item`() {
        val items = listOf(
            // id 1：创建晚，但从未再用
            item(1, "无关正文", createdAt = 9_000, lastUsed = 0),
            // id 2：创建早，但昨天用过
            item(2, "无关正文", createdAt = 1_000, lastUsed = 9_500),
        )
        val hits = retriever.retrieve(items, MemoryQuery("任何一个字", limit = 2), )

        // 时间路把 id2 排前面
        assertEquals(2L, hits.first().item.id)
    }

    @Test
    fun `tag match contributes via fusion`() {
        val items = listOf(
            item(1, "完全无关的正文", tags = listOf("工作")),
            item(2, "完全无关的正文", tags = listOf("生活")),
        )
        val hits = retriever.retrieve(items, MemoryQuery("工作", limit = 2))

        assertEquals(1L, hits.first().item.id)
        assertTrue(hits.first().sources.contains(MemoryRetriever.SOURCE_TAG))
    }

    @Test
    fun `limit caps result count`() {
        val items = (1L..20L).map { item(it, "共同词") }
        val hits = retriever.retrieve(items, MemoryQuery("共同词", limit = 3))

        assertEquals(3, hits.size)
    }

    @Test
    fun `scores are deterministic for equal input`() {
        val items = listOf(item(1, "a b"), item(2, "a b"), item(3, "a b"))
        val first = retriever.retrieve(items, MemoryQuery("a", limit = 3)).map { it.item.id }
        val second = retriever.retrieve(items, MemoryQuery("a", limit = 3)).map { it.item.id }

        // 同分时按 id 升序，两次结果必须一致（防 flaky）。
        assertEquals(first, second)
        assertEquals(listOf(1L, 2L, 3L), first)
    }

    @Test
    fun `tokenizer splits latin words and cjk chars`() {
        assertEquals(listOf("hello", "world"), retriever.tokenize("Hello, world!"))
        assertEquals(listOf("记", "得", "咖", "啡"), retriever.tokenize("记得咖啡"))
    }

    @Test
    fun `tokenizer drops single latin letters as noise`() {
        // 单字母信息量太低（'a', 'I'），会带来大量噪声命中。
        assertEquals(emptyList<String>(), retriever.tokenize("a b c"))
    }
}

class MemoryWritePipelineTest {

    @Test
    fun `writes new item`() = runTest {
        val store = FakeStore()
        val pipeline = MemoryWritePipeline(store) { 1000L }

        val result = pipeline.write("用户喜欢喝咖啡")

        assertEquals(1, result.inserted)
        assertEquals(0, result.merged)
        assertEquals(1, store.items.size)
        assertEquals(1000L, store.items.single().createdAt)
    }

    @Test
    fun `rejects blank content`() = runTest {
        val store = FakeStore()
        val pipeline = MemoryWritePipeline(store) { 1000L }

        val result = pipeline.write("   ")

        assertEquals(0, result.inserted)
        assertTrue(result.rejected.isNotEmpty())
        assertEquals(0, store.items.size)
    }

    @Test
    fun `rejects over long content instead of truncating`() = runTest {
        val store = FakeStore()
        val pipeline = MemoryWritePipeline(store) { 1000L }

        val result = pipeline.write("字".repeat(MemoryWritePipeline.MAX_CONTENT_LENGTH + 1))

        assertEquals(0, result.inserted)
        assertTrue(result.rejected.isNotEmpty())
        // 关键：拒绝而非截断，库里不能出现半句话。
        assertEquals(0, store.items.size)
    }

    @Test
    fun `merges near duplicate instead of inserting`() = runTest {
        val store = FakeStore(
            listOf(MemoryItem(id = 7, content = "用户喜欢喝咖啡", createdAt = 500, lastUsedAt = 500, hitCount = 1)),
        )
        val pipeline = MemoryWritePipeline(store) { 2000L }

        val result = pipeline.write("用户喜欢喝咖啡。")

        assertEquals(1, result.merged)
        assertEquals(0, result.inserted)
        assertEquals(1, store.items.size)
        // 合并推进时间与命中数，不新建条目。
        assertEquals(7L, store.items.single().id)
        assertEquals(2, store.items.single().hitCount)
        assertEquals(2000L, store.items.single().lastUsedAt)
    }

    @Test
    fun `distinct content inserts separately`() = runTest {
        val store = FakeStore(
            listOf(MemoryItem(id = 1, content = "用户住在上海", createdAt = 500, lastUsedAt = 500)),
        )
        val pipeline = MemoryWritePipeline(store) { 2000L }

        val result = pipeline.write("用户喜欢喝咖啡")

        assertEquals(1, result.inserted)
        assertEquals(2, store.items.size)
    }

    @Test
    fun `jaccard similarity behaves`() {
        assertEquals(1.0, jaccardSimilarity("咖啡", "咖啡"), 0.0001)
        assertEquals(0.0, jaccardSimilarity("咖啡", "牛奶"), 0.0001)
        // 「咖啡」vs「咖啡豆」：交集 2，并集 3
        assertEquals(2.0 / 3.0, jaccardSimilarity("咖啡", "咖啡豆"), 0.0001)
    }
}
