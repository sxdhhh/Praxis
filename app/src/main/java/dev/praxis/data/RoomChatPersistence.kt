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

    /**
     * 中间插入（摘要用）。
     *
     * **必须覆写**：端口的默认实现会退化成 `appendMessage`，
     * 那会让摘要重启后沉到会话末尾 —— 见 [ChatPersistence.insertMessageAt] 的 KDoc。
     */
    override suspend fun insertMessageAt(
        sessionId: String,
        atOrderIndex: Int,
        role: String,
        content: String,
    ): Long = repository.insertMessageAt(
        sessionId = sessionId,
        atOrderIndex = atOrderIndex,
        role = role,
        content = content,
    )

    override suspend fun loadMessages(sessionId: String): List<dev.praxis.feature.chat.PersistedMessage> =
        repository.loadMessagesOnce(sessionId).map { row ->
            dev.praxis.feature.chat.PersistedMessage(
                id = row.id,
                role = row.role,
                content = row.content,
                toolName = row.toolName,
                isError = row.isError,
                createdAt = row.createdAt,
                orderIndex = row.orderIndex,
                // 必须带出来：恢复时要用它判断「这条 tool 消息能否安全回放」，
                // 见 `PersistedMessage.toolCallId` 的 KDoc。
                toolCallId = row.toolCallId,
                // 变体信息同样必须带出来：否则同一组的 N 条版本在重启后
                // 会各自变回独立气泡，等于变体功能只在本次进程内成立。
                variantGroupId = row.variantGroupId,
                variantIndex = row.variantIndex,
            )
        }

    override suspend fun attachToVariantGroup(messageId: Long, groupId: String, index: Int) {
        // 走 repository 而不是直接 `messageDao`：`RoomChatPersistence` 只持有
        // repository（见类 KDoc「只是一层薄适配」），而 orderIndex 分配、
        // appendLock 都在 repository 里。绕过它会丢掉那把锁。
        repository.attachToVariantGroup(messageId, groupId, index)
    }

    override suspend fun appendVariant(
        sessionId: String,
        groupId: String,
        index: Int,
        content: String,
    ): Long = repository.appendVariant(
        sessionId = sessionId,
        groupId = groupId,
        index = index,
        content = content,
    )

    override suspend fun loadVariants(
        sessionId: String,
        groupId: String,
    ): List<dev.praxis.feature.chat.PersistedMessage> =
        repository.loadVariants(sessionId, groupId).map { row ->
            dev.praxis.feature.chat.PersistedMessage(
                id = row.id,
                role = row.role,
                content = row.content,
                toolName = row.toolName,
                isError = row.isError,
                createdAt = row.createdAt,
                orderIndex = row.orderIndex,
                toolCallId = row.toolCallId,
                variantGroupId = row.variantGroupId,
                variantIndex = row.variantIndex,
            )
        }

    override suspend fun listSessions(): List<dev.praxis.feature.chat.SessionItem> =
        repository.loadSessionsOnce().map { row ->
            dev.praxis.feature.chat.SessionItem(
                id = row.id,
                title = row.title,
                updatedAt = row.updatedAt,
                pinned = row.pinned,
            )
        }

    override suspend fun deleteSession(sessionId: String) {
        repository.deleteSession(sessionId)
    }

    override suspend fun setSessionPinned(sessionId: String, pinned: Boolean) {
        repository.setSessionPinned(sessionId, pinned)
    }

    override suspend fun updateSessionTitle(sessionId: String, title: String) {
        repository.updateSessionTitle(sessionId, title)
    }
}
