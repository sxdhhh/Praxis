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
    private val nowMs: () -> Long = System::currentTimeMillis,
) : MemoryPort {

    override suspend fun recall(query: String, limit: Int): List<String> =
        runCatching {
            val candidates = store.all()
            retriever
                .retrieve(
                    candidates = candidates,
                    query = MemoryQuery(text = query, limit = limit, now = nowMs()),
                )
                .map { it.item.content }
        }.getOrDefault(emptyList())
}
