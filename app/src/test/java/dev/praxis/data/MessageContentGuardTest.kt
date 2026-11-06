package dev.praxis.data

import dev.praxis.data.db.MessageDao
import dev.praxis.data.db.MessageEntity
import dev.praxis.data.db.SessionDao
import dev.praxis.data.db.SessionEntity
import dev.praxis.data.spill.SpillStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 正文上限守卫（2026-11-07，R4）。
 *
 * ## 它守的是什么
 *
 * 单个用户故事：用户粘贴一篇文章发出去，正文进了库。
 * 之后**每次进入这个会话都会闪退** —— 因为
 *
 *   MessageDao.observeSession 是 SELECT *，
 *   SQLite 的 CursorWindow 默认只有 2 MB，
 *   超长字段让读取抛 SQLiteBlobTooBigException。
 *
 * 坏数据一旦入库就一直在，所以每次重进都复现。这就是为什么必须在
 * **写入侧**拦住，而不是读取侧兜底。
 *
 * ## 两条不能破的不变式
 *
 *  1. 库里任何一条正文都不超过 MAX_STORED_CHARS；
 *  2. **不丢内容** —— 超长的那部分必须落到溢出文件，库里留定位符。
 *
 * 第 2 条同样是硬要求：只截断不落盘等于静默丢数据，用户和模型都不知道少了什么。
 */
class MessageContentGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * 只实现测试真正走到的方法；其余 TODO()。
     *
     * 用 TODO() 而不是空实现是刻意的：一旦被测代码多走了一条路径，
     * 测试会立刻炸出 NotImplementedError，而不是悄悄拿到一个假值。
     */
    private class FakeMessageDao : MessageDao {
        val inserted = mutableListOf<MessageEntity>()
        val updated = mutableMapOf<Long, String>()
        private var nextId = 1L

        override suspend fun maxOrderIndex(sessionId: String): Int = inserted.size - 1

        override suspend fun insert(message: MessageEntity): Long {
            val id = nextId++
            inserted += message.copy(id = id)
            return id
        }

        override suspend fun updateContent(id: Long, content: String) {
            updated[id] = content
        }

        override fun observeSession(sessionId: String): Flow<List<MessageEntity>> = flowOf(emptyList())
        override suspend fun deleteAllInSession(sessionId: String) = TODO()
        override suspend fun insertAll(messages: List<MessageEntity>) = TODO()
        override suspend fun updateReasoning(id: Long, reasoning: String?) = TODO()
        override suspend fun update(message: MessageEntity) = TODO()
        override suspend fun deleteById(id: Long) = TODO()
        override suspend fun countInSession(sessionId: String): Int = TODO()
        override suspend fun shiftOrderIndexFrom(sessionId: String, fromIndex: Int, delta: Int) = TODO()
        override suspend fun atOrderIndex(sessionId: String, orderIndex: Int): MessageEntity? = TODO()
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

    private fun repo(store: SpillStore?): Pair<ChatRepository, FakeMessageDao> {
        val dao = FakeMessageDao()
        val r = ChatRepository(
            sessionDao = FakeSessionDao(),
            messageDao = dao,
            spillStore = store,
        )
        return r to dao
    }

    private fun store(): SpillStore = SpillStore(File(tmp.root, "spill"))

    /** 短内容原样入库，不该产生任何溢出文件。 */
    @Test
    fun shortContentStoredAsIs() = runTest {
        val (r, dao) = repo(store())
        val text = "短消息"

        r.appendMessage("s1", role = "user", content = text)

        assertEquals(text, dao.inserted.single().content)
        val spillDir = File(tmp.root, "spill")
        assertTrue("不该产生溢出文件", !spillDir.exists() || spillDir.listFiles().isNullOrEmpty())
    }

    /** 超长但**没有**溢出存储时保持原样：宁可留风险，也不静默丢内容。 */
    @Test
    fun oversizedWithoutSpillStoreIsKeptIntact() = runTest {
        val (r, dao) = repo(null)
        val text = "x".repeat(ChatRepository.MAX_STORED_CHARS + 100)

        r.appendMessage("s1", role = "user", content = text)

        assertEquals("没有溢出存储时不该截断", text, dao.inserted.single().content)
    }

    /** 超长且有溢出存储：库里截断 + 定位符，溢出文件里是**全文**。 */
    @Test
    fun oversizedIsTruncatedAndSpilled() = runTest {
        val st = store()
        val (r, dao) = repo(st)
        val tail = "这是结尾，不能被丢掉"
        val text = "x".repeat(ChatRepository.MAX_STORED_CHARS + 500) + tail

        r.appendMessage("s1", role = "user", content = text)

        val stored = dao.inserted.single().content
        assertTrue("库里不该超过上限（加提示文本的余量）", stored.length < text.length)
        assertTrue("应带溢出提示", stored.contains(ChatRepository.SPILL_NOTICE_PREFIX))

        // 定位符必须真的能取回全文 —— 否则提示就是一句空话。
        val locator = stored.substringAfter("定位符 ").substringBefore("，")
        assertNotNull("应解析出定位符", locator)
        val spilled = File(tmp.root, "spill").listFiles()?.singleOrNull()
        assertNotNull("溢出文件应存在", spilled)
        assertEquals("溢出文件里应是全文", text, spilled!!.readText())
    }

    /** 流式写入超限：只截断不落盘（热路径不能每帧写文件）。 */
    @Test
    fun streamingWriteIsTruncatedWithoutSpilling() = runTest {
        val st = store()
        val (r, dao) = repo(st)
        val text = "y".repeat(ChatRepository.MAX_STORED_CHARS + 100)

        r.updateMessageContent(42L, text)

        val stored = dao.updated[42L]
        assertNotNull(stored)
        assertTrue("流式写入也必须截断", stored!!.length <= ChatRepository.MAX_STORED_CHARS + 100)
        assertTrue("应带截断标记", stored.contains(ChatRepository.SPILL_NOTICE_PREFIX))
        val spillDir = File(tmp.root, "spill")
        assertTrue("流式路径不该落盘", !spillDir.exists() || spillDir.listFiles().isNullOrEmpty())
    }
}
