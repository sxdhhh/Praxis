package dev.praxis.core.gateway

/**
 * 会话粘性表：会话键 → 账号 id（纯内存，TTL 过期，容量上限）。
 *
 * ## 为什么必须有它（G6 的修复）
 *
 * `GatewayRoutingPolicy.stickyEnabled` 默认开着、路由页有开关、`RouterSession`
 * 有 setStickyAccount / stickyAfterSuccess —— 但**没有人存**「上次这个对话用了哪个账号」，
 * 于是粘性整条机制空转：开关真开、行为永假。
 *
 * ## 为什么放内存而不是 DataStore
 *
 * 粘性是「这段连续对话别换号」的**体验优化**：进程重启后回到顺序/轮询的首位是完全
 * 可接受的行为，持久化反而会把「上次的临时状态」变成需要清理的负担。
 *
 * ## 键从哪来
 *
 * `ConversationKey.of`：显式头优先，否则首条内容哈希。Praxis 的 `ChatRequest`
 * 没有 headers 字段，所以实际只会走内容键 —— 同一对话首条消息不变，键就稳定。
 * 两个人恰好打了同一句开场白会共享一个键：这正是该 KDoc 写明的取舍，不是 bug。
 */
class StickyAccountStore(
    /** 过期时长。30 分钟：覆盖一次连续对话的合理长度，又不至于把账号钉死半天。 */
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    /** 容量上限：超限淘汰最旧。防的是「几千个对话把表撑爆」这类低级事故。 */
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    /** 时钟可注入：测试里判定 TTL 不用真的等 30 分钟。 */
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private data class Entry(val accountId: String, val atMillis: Long)

    private val entries = LinkedHashMap<String, Entry>()

    /** 取该会话上次成功用的账号；键为空或已过期返回 null。 */
    @Synchronized
    fun get(key: String?): String? {
        if (key == null) return null
        val entry = entries[key] ?: return null
        return if (now() - entry.atMillis > ttlMillis) {
            entries.remove(key)
            null
        } else {
            entry.accountId
        }
    }

    /** 记下「这个会话刚在这个账号上成功」。键为空 = 没有粘性可言，直接忽略。 */
    @Synchronized
    fun put(key: String?, accountId: String) {
        if (key == null || key.isBlank() || accountId.isBlank()) return
        if (maxEntries <= 0) return
        val t = now()
        // 先清过期，再判容量：否则「满了」可能是过期项占的坑。
        entries.entries.removeAll { t - it.value.atMillis > ttlMillis }
        entries[key] = Entry(accountId, t)
        while (entries.size > maxEntries) {
            val iterator = entries.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
    }

    /** 仅测试与诊断用：当前条目数。 */
    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun clear() {
        entries.clear()
    }

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 30L * 60 * 1000
        const val DEFAULT_MAX_ENTRIES: Int = 256
    }
}