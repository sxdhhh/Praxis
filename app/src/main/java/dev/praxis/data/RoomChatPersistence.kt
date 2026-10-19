package dev.praxis.data

import dev.praxis.feature.chat.ChatPersistence

/**
 * [ChatPersistence] 的 Room 实现。
 *
 * 只是一层薄适配：所有逻辑（orderIndex 分配、updatedAt 刷新）都在
 * [ChatRepository] 里，这里不重复实现，避免两处各写一遍然后不一致。
 */
class RoomChatPersistence(
    private val repository: ChatRepository,
) : ChatPersistence {

    override suspend fun ensureSession(sessionId: String, title: String) {
        repository.ensureSession(sessionId, title)
    }

    override suspend fun appendMessage(
        sessionId: String,
        role: String,
        content: String,
        toolName: String?,
        toolArgs: String?,
        toolCallId: String?,
        isError: Boolean,
    ): Long = repository.appendMessage(
        sessionId = sessionId,
        role = role,
        content = content,
        toolName = toolName,
        toolArgs = toolArgs,
        toolCallId = toolCallId,
        isError = isError,
    )

    override suspend fun updateMessageContent(messageId: Long, content: String) {
        repository.updateMessageContent(messageId, content)
    }

    override suspend fun loadMessages(sessionId: String): List<dev.praxis.feature.chat.PersistedMessage> =
        repository.loadMessagesOnce(sessionId).map { row ->
            dev.praxis.feature.chat.PersistedMessage(
                id = row.id,
                role = row.role,
                content = row.content,
                toolName = row.toolName,
                isError = row.isError,
            )
        }

    override suspend fun listSessions(): List<dev.praxis.feature.chat.SessionItem> =
        repository.loadSessionsOnce().map { row ->
            dev.praxis.feature.chat.SessionItem(
                id = row.id,
                title = row.title,
                updatedAt = row.updatedAt,
            )
        }

    override suspend fun deleteSession(sessionId: String) {
        repository.deleteSession(sessionId)
    }
}
