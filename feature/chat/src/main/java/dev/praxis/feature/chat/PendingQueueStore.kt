package dev.praxis.feature.chat

/**
 * 待发队列的持久化端口。
 *
 * ## 为什么单独一个接口，而不是让 ViewModel 直接用 `SettingsRepository`
 *
 * 与 `ChatPersistence` / `MemoryPort` 同一条分层纪律：
 * `feature:chat` 是纯 UI + 状态机，不该知道 DataStore 的存在。
 * 端口在里、实现在 `:app` —— 于是本模块的单测不必起 DataStore。
 *
 * ## 为什么方法体给默认空实现
 *
 * 存量单测与预览大量构造 `ChatViewModel(runner = ...)` 这类最小入参，
 * 强制传端口会让它们全改一遍，而那些用例与队列持久化无关。
 * 默认「不落盘」时行为与改动前一致。
 */
interface PendingQueueStore {

    /** 写入某会话的队列存档（编码由 `PendingQueueCodec` 负责）。 */
    suspend fun save(sessionId: String, encoded: String) {}

    /** 读某会话的队列存档；没有返回 null。 */
    suspend fun load(sessionId: String): String? = null
}
