package dev.praxis.feature.chat

/**
 * 聊天持久化端口。
 *
 * ## 为什么是接口而不是直接用 ChatRepository
 *
 * [ChatPersistence] 在 `feature:chat`，而 Room 实现在 `:app`。
 * 这样 feature 模块不依赖 Room / Android，测试可以传一个内存实现，
 * 也避免「UI 模块知道数据库表结构」这种越层。
 *
 * 这与 core:tool 的 ToolContext 是同一个思路：**端口在里、实现在外**。
 */
interface ChatPersistence {

    /** 建会话（已存在则刷新标题与时间）。 */
    suspend fun ensureSession(sessionId: String, title: String)

    /** 追加一条消息，返回数据库行 id。 */
    suspend fun appendMessage(
        sessionId: String,
        role: String,
        content: String,
        toolName: String? = null,
        toolArgs: String? = null,
        toolCallId: String? = null,
        isError: Boolean = false,
    ): Long

    /** 更新消息正文（流式增量）。 */
    suspend fun updateMessageContent(messageId: Long, content: String)

    /**
     * 读一个会话的历史（按写入顺序）。
     *
     * 返回 [PersistedMessage] 而不是 Room 实体：feature 模块不该知道表结构，
     * 否则改一列就要改两个模块。
     */
    suspend fun loadMessages(sessionId: String): List<PersistedMessage>

    /** 列出全部会话（按最近更新倒序）。 */
    suspend fun listSessions(): List<SessionItem>

    /** 删除会话（消息由数据库外键级联清除）。 */
    suspend fun deleteSession(sessionId: String)
}

/** 从数据库读回的一条消息（与 UI 气泡同构）。 */
data class PersistedMessage(
    val id: Long,
    val role: String,
    val content: String,
    val toolName: String?,
    val isError: Boolean,
)
