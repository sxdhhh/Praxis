package dev.praxis.data.chat

import dev.praxis.core.data.settings.SettingsRepository
import dev.praxis.feature.chat.PendingQueueStore

/**
 * [PendingQueueStore] 的 DataStore 实现。
 *
 * ## 为什么是独立的小类而不是让 SettingsRepository 直接实现端口接口
 *
 * `SettingsRepository` 在 `:core:data`，而 `PendingQueueStore` 在 `:feature:chat`。
 * 让前者实现后者会把「聊天层的端口契约」反向依赖进数据层 ——
 * 将来聊天层改端口，数据层要跟着改。
 *
 * 适配器只有几行，却把这条反向依赖挡在中间。
 * 与 `MemoryPortQueryAdapter` 同一取舍。
 */
class SettingsPendingQueueStore(
    private val repository: SettingsRepository,
) : PendingQueueStore {

    override suspend fun save(sessionId: String, encoded: String) {
        repository.setPendingQueue(sessionId, encoded)
    }

    override suspend fun load(sessionId: String): String? =
        repository.pendingQueue(sessionId)
}
