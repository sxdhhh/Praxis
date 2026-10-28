package dev.praxis.data

import dev.praxis.core.memory.MemoryQuery
import dev.praxis.core.memory.MemoryRetriever
import dev.praxis.core.memory.MemoryStore
import dev.praxis.feature.chat.MemoryPort

/**
 * [MemoryPort] 的实现：读全库 + RRF 检索。
 *
 * ## 为什么每次现读全库
 *
 * 记忆条数是千级以内，全量读取（< 10ms）+ 内存检索（< 5ms）完全够用，
 * 而缓存会引入「另一台设备/另一条协程写入后读不到」的失效问题。
 * 等条数真的上万再上索引不迟。
 *
 * ## 为什么在这里兜住异常
 *
 * 记忆检索失败不应该让对话发不出去。返回空列表 = 「这次没有相关记忆」，
 * 比抛异常让 [dev.praxis.feature.chat.ChatViewModel] 去处理要干净
 * （ViewModel 那里也有 runCatching，但那是防御最后一道，不是唯一一道）。
 */
class MemoryPortImpl(
    private val store: MemoryStore,
    private val retriever: MemoryRetriever,
    private val pipeline: dev.praxis.core.memory.MemoryWritePipeline,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** 当前活动空间。默认空串 = 默认空间。 */
    private val spaceProvider: () -> String = { dev.praxis.core.memory.MemoryItem.DEFAULT_SPACE_ID },

    /** 当前空间（可改）。由 [setActiveSpace] 写入。 */
    @Volatile
    private var activeSpaceValue: String = spaceProvider()
) : MemoryPort {

    override suspend fun recall(query: String, limit: Int): List<String> =
        runCatching {
            val candidates = store.all(activeSpace())
            retriever
                .retrieve(
                    candidates = candidates,
                    query = MemoryQuery(text = query, limit = limit, now = nowMs()),
                )
                .map { it.item.content }
        }.getOrDefault(emptyList())

    /**
     * 写入记忆。
     *
     * 同样兜住异常：写记忆失败不该影响对话（用户要的是回复，不是
     * 「因为记忆库满了所以这轮发送失败」）。
     */
    override suspend fun remember(content: String) {
        runCatching { pipeline.write(content, spaceId = activeSpace()) }
    }

    /**
     * 取最近写入的记忆（新的在前）。
     *
     * ## 为什么按 createdAt 排序而不是靠 store 的返回顺序
     *
     * `store.all()` 的顺序由 DAO 的 ORDER BY 决定（当前是 id 升序）。
     * 依赖它等于把「最近」这件事交给另一个模块的实现细节 —— 
     * 谁改一句 `ORDER BY`，这里就悄悄变成返回最旧的。显式排序更稳。
     */
    override suspend fun recent(limit: Int): List<String> =
        runCatching {
            store.all(activeSpace())
                .sortedByDescending { it.createdAt }
                .take(limit)
                .map { it.content },
        }.getOrDefault(emptyList())

    /**
     * 当前活动空间。
     *
     * 从 `spaceProvider` 现取，而不是在构造时快照：用户切空间后
     * 下一句就要生效，快照会让它一直用旧空间。
     */
    private fun activeSpace(): String = activeSpaceValue

    /**
     * 切换空间。
     *
     * 写入后下一次 recall/recent/remember 就用新空间。不做回到默认的降级：
     * 用户选了一个还没数据的空间，就应该看到它是空的。
     */
    override suspend fun setActiveSpace(spaceId: String) {
        activeSpaceValue = spaceId
    }
}
