package dev.praxis.core.memory

/**
 * 一条长期记忆。
 *
 * 刻意**不**包含向量：Praxis Phase 1 不做 embedding，检索靠「多路信号 + 融合打分」。
 * 理由：内置 embedding 模型会让 APK 涨几十 MB，且真机上算力受限；
 * 而 RRF 融合在「几十到几千条」量级的效果已足够，且完全可解释、可单测。
 */
data class MemoryItem(
    val id: Long = 0L,
    /** 正文。这是唯一必填字段，空正文在写入管线就被拒绝。 */
    val content: String,
    /** 标签，用于精确归类（不含权重，权重在检索时算）。 */
    val tags: List<String> = emptyList(),
    /** 创建时间（毫秒）。 */
    val createdAt: Long = 0L,
    /** 最后访问时间（毫秒）。时间衰减评分用它。 */
    val lastUsedAt: Long = 0L,
    /** 被检索命中的次数：命中越多越可能被再次需要。 */
    val hitCount: Int = 0,

    /**
     * 所属记忆空间。
     *
     * ## 为什么是字符串而不是外键
     *
     * 空间是用户自己建的（「工作」「副业」），量极小、几乎不改名。
     * 为它建一张表并加外键，换来的是每次读写都要 join，
     * 而收益只有「不会出现孤儿 spaceId」一项——那件事用一行校验就够了。
     *
     * ## 空串的含义
     *
     * 空串 = 「默认空间」。这样旧数据（没有这个列的时代）不需要特殊标记，
     * 而且不存在「未分类」这种模糊状态。
     */
    val spaceId: String = DEFAULT_SPACE_ID,
) {
    companion object {
        /** 默认空间的 id（空串）。 */
        const val DEFAULT_SPACE_ID = ""
    }
}

/**
 * 一次检索请求。
 *
 * [limit] 是**最终**返回条数；多路召回各自取 [candidatePool] 条再融合，
 * 所以 `candidatePool >= limit` 才有意义。
 */
data class MemoryQuery(
    val text: String,
    val limit: Int = 8,
    val candidatePool: Int = 32,
    /** 当前时间，由调用方传入（纯函数可测的关键）。 */
    val now: Long = 0L,
)

/**
 * 检索结果及其打分明细。
 *
 * 保留 [sources] 是为了可解释：用户问「为什么这条被翻出来」时，
 * 能指到具体是哪一路召回把它带进来的，而不是一个黑盒分数。
 */
data class MemoryHit(
    val item: MemoryItem,
    val score: Double,
    val sources: List<String> = emptyList(),
)

/**
 * 写入管线的三阶段结果。
 *
 * [merged] 是「与已有记忆高度重复、已合并」的条数；
 * [rejected] 是被拒的具体原因（空正文、超长等），不静默丢弃。
 */
data class MemoryWriteResult(
    val inserted: Int = 0,
    val merged: Int = 0,
    val rejected: List<String> = emptyList(),
)

/** 记忆存储端口（实现在 :app，走 Room）。 */
interface MemoryStore {

    /**
     * 读某个空间的全部记忆。
     *
     * [spaceId] 为 null 表示「跨空间全部」（用于设置页的总览与清理）。
     * 默认取默认空间，这样旧调用点（不带参数）行为不变。
     */
    suspend fun all(spaceId: String? = MemoryItem.DEFAULT_SPACE_ID): List<MemoryItem>

    suspend fun insert(item: MemoryItem): Long

    suspend fun update(item: MemoryItem)

    suspend fun delete(id: Long)

    /**
     * 在同一空间内找相似记忆（去重合并用）。
     *
     * 跨空间不能合并：「工作」里的一条和「副业」里的一条
     * 内容再像，也是两件事。
     */
    suspend fun findSimilar(
        content: String,
        threshold: Double,
        spaceId: String = MemoryItem.DEFAULT_SPACE_ID,
    ): MemoryItem?
}
