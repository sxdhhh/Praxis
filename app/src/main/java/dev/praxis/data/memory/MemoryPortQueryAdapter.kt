package dev.praxis.data.memory

import dev.praxis.feature.chat.MemoryPort

/**
 * 把聊天层的 [MemoryPort] 适配成工具层的 [MemoryQueryPort]。
 *
 * ## 为什么用适配器而不是让 MemoryPortImpl 直接实现两个接口
 *
 * `MemoryPort` 是**聊天层的单向端口**，语义是「给我这句相关的记忆」；
 * `MemoryQueryPort` 是**工具层的端口**，语义是「模型主动来查/来记」。
 *
 * 两者方法签名恰好一样，但**归属不同层**。让实现类同时实现两个接口，
 * 会把「工具层的契约」耦合进「聊天层的实现」，将来任一侧加方法都会
 * 迫使另一侧跟着改。适配器只有几行，却把这条依赖挡在中间。
 */
class MemoryPortQueryAdapter(
    private val delegate: MemoryPort,
) : MemoryQueryPort {

    override suspend fun recall(query: String, limit: Int): List<String> =
        delegate.recall(query, limit)

    override suspend fun remember(content: String) {
        delegate.remember(content)
    }
}
