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

    /**
     * 取最近写入的 [limit] 条记忆正文（新的在前）。
     *
     * ## 与 [recall] 的区别
     *
     * `recall` 是「跟这句话相关的」，需要查询词；这个方法是「最近记了什么」，
     * 不需要查询词。用途是把记忆作为**附件**插进输入框（附件面板的「记忆」格）
     * —— 用户想说「按我记的来」，得先看到记了什么。
     *
     * ## 默认实现返回空
     *
     * 接口新增方法带默认实现，避免破坏已有实现与测试里的匿名对象。
     */
    suspend fun recent(limit: Int = RECENT_LIMIT): List<String> = emptyList()

    /**
     * 切换当前记忆空间。
     * 由 :app 在用户点选择器时调用；不靠反向引用 UI 层的 Flow（那会形成循环依赖）。
     * 默认空实现是为不破坏测试里的匿名实现。
     */
    suspend fun setActiveSpace(spaceId: String) {}

    companion object {
        /**
         * 注入上下文的最大条数。
         *
         * 5 条：再多会挤占对话历史与工具描述的预算。
         * 记忆是**补充**，不是主体；一条 800 字的记忆已经能占满半屏上下文。
         */
        /** 附件面板「记忆」格一次最多带几条（多了会把输入框塞满）。 */
        const val RECENT_LIMIT = 20

        const val DEFAULT_LIMIT = 5
    }
}
