package dev.praxis.core.memory

/**
 * 记忆写入管线：抽取 → 去重/合并 → 落库。
 *
 * ## 为什么必须有去重阶段
 *
 * 模型的输出天然重复：同一件事会在多个会话里被反复总结，措辞轻微不同。
 * 如果每条都落地，记忆库会在几周内涨到几万条，然后检索质量崩掉
 * （全部近似重复，RRF 也分不出信号）。去重是**可用性的前提**，不是优化。
 *
 * ## 去重的三条实现约束
 *
 * 1. **不调模型做语义去重**：一次去重要一次 API 调用，写入就变成慢操作；
 *    而且模型会给出不一致的判定。用字符集合相似度（Jaccard）代替，确定且快。
 * 2. **不删旧条目，只合并**：用户可能已经把旧那条引用过（在某个会话里）。
 *    删掉会让历史对话里的引用变成悬空。合并 = 更新旧条目的时间与命中数。
 * 3. **拒绕不静默丢弃**：空正文、超长都写入 [MemoryWriteResult.rejected]，
 *    调用方能看到「为什么没写成」，而不是以为成功了。
 */
class MemoryWritePipeline(
    private val store: MemoryStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /**
     * 写入一条记忆。
     *
     * @return 各阶段结果。注意 [MemoryWriteResult.rejected] 非空**不影响**其他条，
     *   调用方仍应检查 [MemoryWriteResult.inserted] 是否大于 0。
     */
    suspend fun write(content: String, tags: List<String> = emptyList()): MemoryWriteResult {
        val trimmed = content.trim()

        when {
            trimmed.isEmpty() -> return MemoryWriteResult(rejected = listOf("内容为空"))
            trimmed.length > MAX_CONTENT_LENGTH -> return MemoryWriteResult(
                rejected = listOf("内容超长（${trimmed.length} > $MAX_CONTENT_LENGTH）"),
            )
        }

        val existing = store.findSimilar(trimmed, SIMILARITY_THRESHOLD)

        return if (existing != null) {
            // 合并：保留原有 id 与 createdAt，只推进时间与命中数，并合并标签。
            store.update(
                existing.copy(
                    lastUsedAt = nowMs(),
                    hitCount = existing.hitCount + 1,
                    tags = (existing.tags + tags).distinct(),
                ),
            )
            MemoryWriteResult(merged = 1)
        } else {
            store.insert(
                MemoryItem(
                    content = trimmed,
                    tags = tags.distinct(),
                    createdAt = nowMs(),
                    lastUsedAt = nowMs(),
                ),
            )
            MemoryWriteResult(inserted = 1)
        }
    }

    companion object {
        /**
         * 单条记忆长度上限。
         *
         * 800 字：一条记忆应该是「一句可复用的事实」，而不是整段对话。
         * 超过就说明调用方把整段回复当记忆写了，这是设计错误，应当拒绝而不是截断——
         * 截断会产生半句话，比没有更糟。
         */
        const val MAX_CONTENT_LENGTH = 800

        /**
         * 判为重复的门槛。
         *
         * 0.85 是实测取舍：0.7 会把「喜欢喝咖啡」和「喜欢喝茶」判成重复；
         * 0.95 则放过措辞微调（加一个「一直」）的重复。
         */
        const val SIMILARITY_THRESHOLD = 0.85
    }
}

/**
 * 字符集合 Jaccard 相似度。
 *
 * 刻意用**字符集合**而非词集合：
 *  - 中文没有空格，分词需要额外依赖（见 [MemoryRetriever.tokenize] 的单字策略）；
 *  - 「喜欢喝咖啡」与「喜欢喝咖啡。」的字符集合几乎相同，这正好符合「同义」的直觉；
 *  - 实现只有几行，不引入任何依赖。
 *
 * 对英文短句这个度量偏严（字母集合会重复计入），但记忆正文以中文为主，可接受。
 */
fun jaccardSimilarity(a: String, b: String): Double {
    if (a.isEmpty() && b.isEmpty()) return 1.0
    val setA = a.toSet()
    val setB = b.toSet()
    if (setA.isEmpty() || setB.isEmpty()) return 0.0
    val intersection = setA.count { it in setB }
    val union = setA.size + setB.size - intersection
    return if (union == 0) 0.0 else intersection.toDouble() / union
}
