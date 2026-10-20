package dev.praxis.feature.chat

/**
 * 长期记忆对聊天层的单向端口。
 *
 * ## 为什么只暴露一个方法
 *
 * 聊天层只需要「给我当前这句相关的记忆」，不需要写入、删除、遍历。
 * 暴露整个 `MemoryStore` 会让聊天层有机会写记忆，而记忆该在什么时机写
 * 是一个独立问题（见 MemoryWritePipeline）；让聊天层能写会诱使它在错误时机写。
 *
 * 用挂起函数而不是 Flow：记忆检索在发送前调用一次，不需要订阅变化。
 */
interface MemoryPort {

    /**
     * 取与 [query] 相关的记忆文本（已按相关度排序，最多 [limit] 条）。
     *
     * 返回空列表表示「没有相关记忆」，不是错误。
     */
    suspend fun recall(query: String, limit: Int = DEFAULT_LIMIT): List<String>

    /**
     * 把 [content] 写为一条长期记忆。
     *
     * 为什么不给聊天层完整的 MemoryStore：聊天层只需要「记住这句话」与
     * 「想起相关的话」两个动作，不需要删除 / 遍历 / 改标签。
     * 暴露得越窄，它犯错的面就越小。
     *
     * 去重与长度校验都在 MemoryWritePipeline 里（实现方），
     * 聊天层不需要预先判断「这条值不值得记」。
     */
    suspend fun remember(content: String)

    companion object {
        /**
         * 注入上下文的最大条数。
         *
         * 5 条：再多会挤占对话历史与工具描述的预算。
         * 记忆是**补充**，不是主体；一条 800 字的记忆已经能占满半屏上下文。
         */
        const val DEFAULT_LIMIT = 5
    }
}
