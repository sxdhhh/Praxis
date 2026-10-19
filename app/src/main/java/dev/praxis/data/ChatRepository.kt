package dev.praxis.data

import dev.praxis.data.db.MessageDao
import dev.praxis.data.db.MessageEntity
import dev.praxis.data.db.SessionDao
import dev.praxis.data.db.SessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * 会话与消息的持久化入口。
 *
 * 只暴露领域最需要的操作，不把 DAO 直接透传出去 —— 否则调用方会绕过
 * 「orderIndex 维护」「updatedAt 刷新」这些必须成对做的事。
 */
class ChatRepository(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val now: () -> Long = System::currentTimeMillis,
) {

    fun observeSessions(): Flow<List<SessionEntity>> = sessionDao.observeAll()

    fun observeMessages(sessionId: String): Flow<List<MessageEntity>> =
        messageDao.observeSession(sessionId)

    /**
     * 建会话（若已存在则只更新标题与时间）。
     *
     * 用 REPLACE 语义而不是 insert-or-ignore：用户改名时要能落库。
     */
    suspend fun ensureSession(sessionId: String, title: String) {
        val ts = now()
        sessionDao.upsert(
            SessionEntity(
                id = sessionId,
                title = title,
                createdAt = ts,
                updatedAt = ts,
            ),
        )
    }

    /**
     * 追加一条消息，自动分配 orderIndex，并刷新会话的 updatedAt。
     *
     * 两步必须一起做：只写消息不刷 updatedAt，会话列表的「最近」排序就永远不动，
     * 用户会觉得新对话沉底了。
     */
    suspend fun appendMessage(
        sessionId: String,
        role: String,
        content: String,
        toolName: String? = null,
        toolArgs: String? = null,
        toolCallId: String? = null,
        isError: Boolean = false,
    ): Long {
        val nextIndex = messageDao.maxOrderIndex(sessionId) + 1
        val id = messageDao.insert(
            MessageEntity(
                sessionId = sessionId,
                orderIndex = nextIndex,
                role = role,
                content = content,
                toolName = toolName,
                toolArgs = toolArgs,
                toolCallId = toolCallId,
                isError = isError,
                createdAt = now(),
            ),
        )
        touchSession(sessionId)
        return id
    }

    /** 流式期间的增量写入（只改 content，不动其他列）。 */
    suspend fun updateMessageContent(messageId: Long, content: String) {
        messageDao.updateContent(messageId, content)
    }

    /** 一次性列出会话（按 updatedAt 倒序）。 */
    suspend fun loadSessionsOnce(): List<SessionEntity> = sessionDao.observeAll().first()

    /** 一次性读全部消息（启动恢复用；实时观察走 observeMessages）。 */
    suspend fun loadMessagesOnce(sessionId: String): List<MessageEntity> =
        messageDao.observeSession(sessionId).first()

    suspend fun deleteSession(sessionId: String) {
        // 消息由外键 CASCADE 自动清除，这里不手动删 —— 手动删容易漏。
        sessionDao.delete(sessionId)
    }

    private suspend fun touchSession(sessionId: String) {
        val existing = sessionDao.find(sessionId) ?: return
        sessionDao.upsert(existing.copy(updatedAt = now()))
    }
}
