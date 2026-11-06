package dev.praxis.data

import dev.praxis.data.db.MessageDao
import dev.praxis.data.db.MessageEntity
import dev.praxis.data.db.SessionDao
import dev.praxis.data.db.SessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 中间插入的锚点解析（2026-11-07，R9）。
 *
 * ## 它守的是什么
 *
 * 摘要要插在「被折叠那段的末尾」，靠的是在前后两行的时间戳之间**取中点**。
 * 取不出来时必须**拒绝**，不能硬塞一个近似值 ——
 * 因为 `CompactionApplier` 是按摘要的**位置**裁剪上下文的：
 * 位置错 = 压缩行为错，而且重启后依然错。
 *
 * ## 五项校验
 *
 *  1. 会话为空 -> 用分配器（这不是中间插入）；
 *  2. 只有后锚点 -> 插在最前，比它小 1；
 *  3. 只有前锚点 -> 插在最后，比它大 1；
 *  4. 顺序非法（前锚点的时间戳 >= 后锚点）-> 拒绝；
 *  5. 间隔不足 1（没有更细的刻度）-> 拒绝。
 *
 * 拒绝的表现是**退化为追加**：位置不理想，但内容一定在。
 */
class AnchoredInsertTest {

    private class FakeMessageDao(private val existing: List<MessageEntity>) : MessageDao {
        val inserted = mutableListOf<MessageEntity>()

        override suspend fun atOrderIndex(sessionId: String, orderIndex: Int): MessageEntity? =
            existing.firstOrNull { it.sessionId == sessionId && it.orderIndex == orderIndex }

        override suspend fun maxOrderIndex(sessionId: String): Int =
            (existing + inserted)
                .filter { it.sessionId == sessionId }
                .maxOfOrNull { it.orderIndex } ?: -1

        override suspend fun insert(message: MessageEntity): Long {
            inserted += message
            return (existing.size + inserted.size).toLong()
        }

        override suspend fun shiftOrderIndexFrom(sessionId: String, fromIndex: Int, delta: Int) = Unit

        override fun observeSession(sessionId: String): Flow<List<MessageEntity>> = flowOf(emptyList())
        override suspend fun deleteAllInSession(sessionId: String) = TODO()
        override suspend fun insertAll(messages: List<MessageEntity>) = TODO()
        override suspend fun updateContent(id: Long, content: String) = TODO()
        override suspend fun updateReasoning(id: Long, reasoning: String?) = TODO()
        override suspend fun update(message: MessageEntity) = TODO()
        override suspend fun deleteById(id: Long) = TODO()
        override suspend fun countInSession(sessionId: String): Int = TODO()
        override suspend fun variantsOf(sessionId: String, groupId: String): List<MessageEntity> = TODO()
        override suspend fun maxVariantIndex(sessionId: String, groupId: String): Int? = TODO()
        override suspend fun attachToVariantGroup(id: Long, groupId: String, index: Int) = TODO()
    }

    private class FakeSessionDao : SessionDao {
        override suspend fun touch(id: String, ts: Long) = Unit

        // R10 新增：用量持久化。测试不关心它，但要满足接口。
        override suspend fun setContextUsage(id: String, used: Int, limit: Int) = Unit
        override fun observeAll(): Flow<List<SessionEntity>> = flowOf(emptyList())
        override fun count(): Flow<Int> = flowOf(0)
        override suspend fun upsert(session: SessionEntity) = TODO()
        override suspend fun find(id: String): SessionEntity? = TODO()
        override suspend fun delete(id: String) = TODO()
        override suspend fun setPinned(id: String, pinned: Boolean) = TODO()
        override suspend fun setTitle(id: String, title: String) = TODO()
        override suspend fun setTitleIfMatches(id: String, title: String, allowed: List<String>) = TODO()
    }

    private fun row(orderIndex: Int, createdAt: Long) = MessageEntity(
        sessionId = "s1",
        orderIndex = orderIndex,
        role = "user",
        content = "c$orderIndex",
        createdAt = createdAt,
    )

    private fun repo(rows: List<MessageEntity>): Pair<ChatRepository, FakeMessageDao> {
        val dao = FakeMessageDao(rows)
        return ChatRepository(sessionDao = FakeSessionDao(), messageDao = dao) to dao
    }

    /** ④ 顺序非法：拒绝，退化为追加（拿一个比历史都大的时间戳）。 */
    @Test
    fun outOfOrderAnchorsAreRejected() = runTest {
        // orderIndex 说 0 在前，但它的 createdAt 比 1 还大 —— 脏数据。
        val (r, dao) = repo(listOf(row(0, 9_000L), row(1, 1_000L)))

        r.insertMessageAt("s1", atOrderIndex = 1, role = "summary", content = "x")

        val createdAt = dao.inserted.single().createdAt
        assertTrue("拒绝后应退化为追加（时间戳大于全部历史）", createdAt > 9_000L)
    }

    /** ⑤ 间隔不足 1：拒绝。 */
    @Test
    fun adjacentTimestampsAreRejected() = runTest {
        val (r, dao) = repo(listOf(row(0, 1_000L), row(1, 1_001L)))

        r.insertMessageAt("s1", atOrderIndex = 1, role = "summary", content = "x")

        val createdAt = dao.inserted.single().createdAt
        assertTrue("间隔不足时不该硬塞中点", createdAt > 1_001L)
    }

    /** 正常情况：取中点，严格落在两者之间。 */
    @Test
    fun normalCaseTakesMidpoint() = runTest {
        val (r, dao) = repo(listOf(row(0, 1_000L), row(1, 2_000L)))

        r.insertMessageAt("s1", atOrderIndex = 1, role = "summary", content = "x")

        val createdAt = dao.inserted.single().createdAt
        assertEquals(1_500L, createdAt)
        assertTrue(createdAt > 1_000L && createdAt < 2_000L)
    }

    /** ② 只有后锚点：插在最前，比它小 1。 */
    @Test
    fun insertingAtHeadGoesBeforeFirstRow() = runTest {
        val (r, dao) = repo(listOf(row(1, 5_000L)))

        r.insertMessageAt("s1", atOrderIndex = 1, role = "summary", content = "x")

        assertEquals(4_999L, dao.inserted.single().createdAt)
    }

    /** ③ 只有前锚点：插在最后，比它大 1。 */
    @Test
    fun insertingAtTailGoesAfterLastRow() = runTest {
        val (r, dao) = repo(listOf(row(0, 5_000L)))

        // atOrderIndex=1 时，前一条是 orderIndex=0，目标位置为空。
        r.insertMessageAt("s1", atOrderIndex = 1, role = "summary", content = "x")

        assertEquals(5_001L, dao.inserted.single().createdAt)
    }

    /** ① 会话为空：第一条消息，交给分配器。 */
    @Test
    fun emptySessionGetsAllocatorTimestamp() = runTest {
        val (r, dao) = repo(emptyList())

        r.insertMessageAt("s1", atOrderIndex = 0, role = "summary", content = "x")

        val createdAt = dao.inserted.single().createdAt
        assertNotNull(createdAt)
        assertTrue("第一条消息的时间戳应大于 0", createdAt > 0L)
    }
}
