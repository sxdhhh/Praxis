package dev.praxis.core.memory

/**
 * 记忆检索的可调权重（对齐 Operit `MemorySearchConfig`）。
 *
 * ## Operit 那套的四路信号
 *
 * 取证 `/root/Operit/.../data/model/MemorySearchConfig.kt`，它的字段是
 * `keywordWeight / tagWeight / vectorWeight / edgeWeight`，默认值分别是
 * **10.0 / 0.0 / 0.0 / 0.4**，另有 `MemoryScoreMode` 三档（BALANCED /
 * KEYWORD_FIRST / SEMANTIC_FIRST）做整体偏置。
 *
 * 这说明它的默认形态其实就是**关键词主导 + 图边传播辅助**，
 * 向量与标签默认关闭（用户要开才开）。这个默认值很有信息量：
 * 在「几十到几千条」的记忆量级上，关键词其实比向量更好用。
 *
 * ## Praxis 的取舍
 *
 * | 信号 | Operit | Praxis | 原因 |
 * |---|---|---|---|
 * | 关键词 | 10.0 | 10.0 | 同值：默认主力 |
 * | 标签 | 0.0 | 1.0 | 打开：Praxis 写入时**总是**带标签（Operit 不一定），信号可靠 |
 * | 向量 | 0.0 | **不支持** | 内置 embedding 模型会让 APK 涨几十 MB；纯 JVM 模块也没有推理能力 |
 * | 图边 | 0.4 | 0.4 | 同值：同值语义的「相关记忆」加权 |
 *
 * `vectorWeight` 保留字段但恒为 0，并在 `normalized()` 里强制归零 ——
 * 这样以后真接入 embedding 时，配置格式不用改。
 */
data class MemorySearchConfig(
    /** 整体模式，只影响三路权重的**相对**偏置，不改绝对语义。 */
    val scoreMode: MemoryScoreMode = MemoryScoreMode.BALANCED,
    val keywordWeight: Double = 10.0,
    val tagWeight: Double = 1.0,
    /** 恒为 0：Praxis 不做向量检索。保留字段是为了配置格式稳定。 */
    val vectorWeight: Double = 0.0,
    val edgeWeight: Double = 0.4,
) {
    /**
     * 归一化：负数钳到 0（用户可能在滑杆上拖出负值），并按模式调偏置。
     *
     * 为什么在读取时归一化而不是在写入时校验：
     * 配置可能来自旧版本、手改的 JSON、或未来的新字段。读取时兜底，
     * 任何脏数据都只影响这一次检索，不会把坏值写回存储。
     */
    fun normalized(): MemorySearchConfig {
        val base = copy(
            keywordWeight = keywordWeight.coerceAtLeast(0.0),
            tagWeight = tagWeight.coerceAtLeast(0.0),
            // 强制归零：即使有人手改配置成非 0，也不能让「没有实现的信号」参与打分。
            vectorWeight = 0.0,
            edgeWeight = edgeWeight.coerceAtLeast(0.0),
        )
        return when (scoreMode) {
            // 关键词优先：把标签与图边压到 1/4，语义上等于「只看字面命中」。
            MemoryScoreMode.KEYWORD_FIRST -> base.copy(
                keywordWeight = base.keywordWeight * 2.0,
                tagWeight = base.tagWeight * 0.25,
                edgeWeight = base.edgeWeight * 0.25,
            )
            // 语义优先：没有向量时，「语义」只能由标签与图边承担，所以抬这两个。
            MemoryScoreMode.SEMANTIC_FIRST -> base.copy(
                keywordWeight = base.keywordWeight * 0.5,
                tagWeight = base.tagWeight * 3.0,
                edgeWeight = base.edgeWeight * 3.0,
            )
            MemoryScoreMode.BALANCED -> base
        }
    }
}

/** 检索打分模式。 */
enum class MemoryScoreMode(val label: String) {
    BALANCED("均衡"),
    KEYWORD_FIRST("关键词优先"),
    SEMANTIC_FIRST("语义优先"),
    ;

    companion object {
        fun byName(name: String?): MemoryScoreMode =
            entries.firstOrNull { it.name == name } ?: BALANCED
    }
}

/**
 * 记忆之间的关系边（对齐 Operit 的记忆图）。
 *
 * ## 为什么需要边
 *
 * 记忆不是孤立事实：「用户偏好深色主题」与「用户在夜间使用」高度相关，
 * 但字面上一个词都不重叠。只靠关键词召回，问「夜间」时找不到「深色主题」。
 *
 * 边解决的正是这件事：命中一条后，沿边把相邻记忆也带进候选（权重打折）。
 * Operit 的 `edgeWeight = 0.4` 就是这个折价系数。
 */
data class MemoryEdge(
    val fromId: Long,
    val toId: Long,
    /** 关系强度 0..1。写入方决定（比如「同一次对话里提取」= 0.8）。 */
    val weight: Double = 0.7,
)

/**
 * 检索重排：RRF 基础分 + 关键词覆盖率乘子 + 图边传播。
 *
 * ## 为什么另起一个类而不是改 [MemoryRetriever]
 *
 * [MemoryRetriever] 已经有了完整的 RRF 融合与大量注释，并且有测试锁着。
 * 这里要做的是**在它之上再加两层**（覆盖率乘子、边传播），
 * 直接改它会让那份实现承担两套职责，也不好对比「加之前 / 加之后」。
 *
 * 组合方式：`MemorySearchPipeline` 先用 [MemoryRetriever] 拿到候选，
 * 再用本类打分。这样两份逻辑都能单独测。
 */
class MemoryRanker(
    private val config: MemorySearchConfig = MemorySearchConfig(),
) {

    /**
     * 重排候选。
     *
     * @param candidates 已经过初筛的候选（通常来自 [MemoryRetriever] 的池）
     * @param query 原始查询
     * @param edges 关系边（用于传播）
     * @param rrfK 与 [MemoryRetriever] 保持一致的 RRF 常数
     */
    fun rank(
        candidates: List<MemoryItem>,
        query: MemoryQuery,
        edges: List<MemoryEdge> = emptyList(),
        rrfK: Int = MemoryRetriever.DEFAULT_RRF_K,
    ): List<MemoryHit> {
        if (candidates.isEmpty()) return emptyList()
        val cfg = config.normalized()
        val terms = tokenize(query.text)

        // ① 每条的「关键词覆盖率」：命中了查询里多少个不同的词（0..1）。
        //
        // 这一个乘子解决的是 RRF 的一个真实缺陷：RRF 只看名次，
        // 于是「命中 1 个词但命中 8 次」的记录会排在「命中 4 个词各 1 次」的前面。
        // 用户感受到的是「越查越偏」。Operit 也做了同一件事
        // （`computeKeywordCoverageMultiplier`）。
        val coverage = candidates.associate { item ->
            item.id to keywordCoverage(item, terms)
        }

        // ② 关键词 / 标签两路名次（与 MemoryRetriever 的判据一致，但这里要加权）。
        val byKeyword = candidates.sortedByDescending { keywordHits(it, terms) }
        val byTag = candidates.sortedByDescending { tagHits(it, terms) }

        val scores = HashMap<Long, Double>()
        val sources = HashMap<Long, MutableList<String>>()

        fun accumulate(ranked: List<MemoryItem>, weight: Double, label: String) {
            if (weight <= 0.0) return
            ranked.forEachIndexed { index, item ->
                scores[item.id] = (scores[item.id] ?: 0.0) + weight * (1.0 / (rrfK + index + 1))
                sources.getOrPut(item.id) { mutableListOf() }.add(label)
            }
        }

        accumulate(byKeyword, cfg.keywordWeight, MemoryRetriever.SOURCE_KEYWORD)
        accumulate(byTag, cfg.tagWeight, MemoryRetriever.SOURCE_TAG)

        // ③ 覆盖率乘子：只放大、不缩小（下限 0.5 保底），
        //    否则一条相关的短记忆会被「没覆盖到全部词」直接判死。
        //
        // 注意必须遍历 entries 的 key 去查 coverage，**不能**在 replaceAll 里
        // 拿「score 反查 id」：score 是 Double，两条记忆可能同分，
        // 而且 `===` 比的是装箱引用、永远不成立（这是写这段代码时差点踩进去的坑）。
        scores.entries.toList().forEach { (id, score) ->
            scores[id] = score * (0.5 + 0.5 * (coverage[id] ?: 0.0))
        }

        // ④ 图边传播：命中的记忆把分数按 edgeWeight 传给**一跳**邻居。
        //
        // 只做一跳：两跳会让「全库都能互相到达」（记忆图很容易连成一片），
        // 最终所有记忆都带分，等于没有排序。
        if (cfg.edgeWeight > 0.0 && edges.isNotEmpty()) {
            val seedScores = scores.toMap()
            val outgoing = edges.groupBy { it.fromId }
            seedScores.forEach { (id, score) ->
                outgoing[id]?.forEach { edge ->
                    if (edge.toId == id) return@forEach
                    scores[edge.toId] = (scores[edge.toId] ?: 0.0) +
                        cfg.edgeWeight * score * edge.weight
                    sources.getOrPut(edge.toId) { mutableListOf() }.add(SOURCE_EDGE)
                }
            }
        }

        val byId = candidates.associateBy { it.id }
        return scores.entries
            .mapNotNull { (id, score) -> byId[id]?.let { score to it } }
            .sortedWith(
                compareByDescending<Pair<Double, MemoryItem>> { it.first }
                    .thenByDescending { effectiveTime(it.second) }
                    .thenBy { it.second.id },
            )
            .take(query.limit)
            .map { (score, item) ->
                MemoryHit(
                    item = item,
                    score = score,
                    sources = sources[item.id].orEmpty().distinct(),
                )
            }
    }

    /** 命中了查询词中的**多少个不同词**，归一化到 0..1。 */
    private fun keywordCoverage(item: MemoryItem, terms: List<String>): Double {
        if (terms.isEmpty()) return 0.0
        val text = item.content.lowercase()
        val hit = terms.count { text.contains(it) }
        return hit.toDouble() / terms.size
    }

    private fun keywordHits(item: MemoryItem, terms: List<String>): Int {
        if (terms.isEmpty()) return 0
        val text = item.content.lowercase()
        return terms.sumOf { term ->
            var count = 0
            var from = 0
            while (true) {
                val at = text.indexOf(term, from)
                if (at < 0) break
                count++
                from = at + term.length
            }
            count
        }
    }

    private fun tagHits(item: MemoryItem, terms: List<String>): Int {
        if (terms.isEmpty()) return 0
        return item.tags.count { tag ->
            val lower = tag.lowercase()
            terms.any { lower.contains(it) }
        }
    }

    private fun effectiveTime(item: MemoryItem): Long =
        if (item.lastUsedAt > 0) item.lastUsedAt else item.createdAt

    /** 复用 [MemoryRetriever] 的切词，保证两处判据一致。 */
    private fun tokenize(text: String): List<String> = MemoryRetriever().tokenize(text)

    companion object {
        const val SOURCE_EDGE = "edge"
    }
}

/**
 * 记忆检索管线：召回 → 重排。
 *
 * 把两步串起来的理由：召回要「尽量多」（宁可多带几条），
 * 重排要「尽量准」（只留最相关）。合成一个类会让两件事的调试互相干扰。
 */
class MemorySearchPipeline(
    private val retriever: MemoryRetriever = MemoryRetriever(),
    private val config: MemorySearchConfig = MemorySearchConfig(),
) {

    /** 完整检索。纯函数，不碰 IO。 */
    fun search(
        all: List<MemoryItem>,
        query: MemoryQuery,
        edges: List<MemoryEdge> = emptyList(),
    ): List<MemoryHit> {
        // 召回池放宽：先按 candidatePool 取一批，重排后再裁到 limit。
        val pool = retriever.retrieve(
            candidates = all,
            query = query.copy(limit = query.candidatePool),
        ).map { it.item }

        // 召回为空时（查询词一个都没命中）退回「最近使用」：
        // 空结果对聊天注入没有任何帮助，而「最近用过的」至少兜住了上下文连续性。
        val recalled = if (pool.isEmpty()) {
            all.sortedByDescending { if (it.lastUsedAt > 0) it.lastUsedAt else it.createdAt }
                .take(query.candidatePool)
        } else {
            pool
        }

        // ## 把边邻居并进候选（2026-10-22 修正）
        //
        // 这一步是**边传播能生效的前提**，之前漏了会导致一个很隐蔽的失效：
        //   1. 召回按关键词做，「深色主题」与查询「夜间」零字面重合 → 不在候选里；
        //   2. `MemoryRanker` 的边传播只给「已在候选里」的条目加分；
        //   3. 于是「命中夜间 → 带出深色主题」这条能力**从未真正生效**，
        //      而代码看起来是完整的。
        //
        // 所以先按「候选集合的 id」找出它们的直接邻居，把邻居补进候选。
        // 只补一跳：两跳会让记忆图连成一片，最终全部带分、等于没有排序。
        val base = if (edges.isEmpty()) {
            recalled
        } else {
            val recalledIds = recalled.mapTo(HashSet()) { it.id }
            val byId = all.associateBy { it.id }
            val neighbours = edges.asSequence()
                .filter { it.fromId in recalledIds && it.toId !in recalledIds }
                .mapNotNull { byId[it.toId] }
                .toList()
            recalled + neighbours
        }
        return MemoryRanker(config).rank(base, query, edges)
    }
}