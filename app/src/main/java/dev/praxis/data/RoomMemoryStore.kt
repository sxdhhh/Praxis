package dev.praxis.data

import dev.praxis.core.memory.MemoryItem
import dev.praxis.core.memory.MemoryStore
import dev.praxis.core.memory.jaccardSimilarity
import dev.praxis.data.db.MemoryDao
import dev.praxis.data.db.MemoryEntity

/**
 * [MemoryStore] 的 Room 实现。
 *
 * ## 为什么「相似查询」在内存里做
 *
 * [MemoryStore.findSimilar] 需要 Jaccard 相似度，SQLite 没有内置这个函数。
 * 三条路：注册自定义函数（要改 Room 的 openHelper、跨进程不通）、
 * 写 SQL 硬算（不可读且慢）、或在内存里算。
 *
 * 选内存：记忆条数在千级以内，全量读出后逐条比对的开销是几毫秒；
 * 而这条路径只在**写入**时走一次，不是热路径。
 *
 * ## 标签编解码
 *
 * 用 [MemoryEntity.TAG_SEPARATOR]（U+001F）连接/切分：
 * 用户标签里可能含逗号（「工作,副业」），逗号会切错。
 */
class RoomMemoryStore(
    private val dao: MemoryDao,
) : MemoryStore {

    override suspend fun all(): List<MemoryItem> = dao.all().map { it.toItem() }

    override suspend fun insert(item: MemoryItem): Long =
        dao.insert(item.toEntity())

    override suspend fun update(item: MemoryItem) {
        dao.update(item.toEntity())
    }

    override suspend fun delete(id: Long) {
        dao.delete(id)
    }

    override suspend fun findSimilar(content: String, threshold: Double): MemoryItem? =
        dao.all()
            .asSequence()
            .map { it.toItem() }
            .firstOrNull { jaccardSimilarity(it.content, content) >= threshold }

    private fun MemoryEntity.toItem(): MemoryItem = MemoryItem(
        id = id,
        content = content,
        tags = decodeTags(tags),
        createdAt = createdAt,
        lastUsedAt = lastUsedAt,
        hitCount = hitCount,
    )

    private fun MemoryItem.toEntity(): MemoryEntity = MemoryEntity(
        id = id,
        content = content,
        tags = encodeTags(tags),
        createdAt = createdAt,
        lastUsedAt = lastUsedAt,
        hitCount = hitCount,
    )

    private fun encodeTags(tags: List<String>): String =
        tags.filter { it.isNotBlank() }.joinToString(MemoryEntity.TAG_SEPARATOR.toString())

    private fun decodeTags(raw: String): List<String> =
        if (raw.isEmpty()) emptyList() else raw.split(MemoryEntity.TAG_SEPARATOR)
}
