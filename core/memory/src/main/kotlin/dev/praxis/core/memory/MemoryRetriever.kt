package dev.praxis.core.memory

/**
 * 多路召回 + RRF 融合的检索器。
 *
 * ## 为什么用 RRF 而不是加权求和
 *
 * 三路信号的**量纲完全不同**：关键词命中数是 0~N 的整数、时间衰减是 0~1 的小数、
 * 标签匹配是布尔。直接加权求和必须先归一化，而归一化系数又依赖当前候选集
 * （同一条记忆在不同查询里分数会飘），既难调也不可复现。
 *
 * RRF（Reciprocal Rank Fusion）只看**名次**：`1 / (k + rank)`。
 * 好处是：
 *  - 三路信号天然可比，不需要归一化；
 *  - 对异常值不敏感（一条命中 100 次的关键词不会把分数拉到不可理喻）；
 *  - 完全确定、可单测。
 *
 * `k = 60` 是 RRF 原论文的经验值：它压制了「第一名与第二名」的差距，
 * 让多路召回都有机会贡献分数，而不是让某一路独占。
 */
class MemoryRetriever(
    private val rrfK: Int = DEFAULT_RRF_K,
) {

    /**
     * 检索。【纯函数】不碰 IO：候选集由调用方从 [MemoryStore] 取出后传入。
     *
     * 这样切开的理由：检索逻辑是最容易写错、最需要测试的部分；
     * 把它与 Room 解耦后，单测不需要 Robolectric，也不需真实数据库。
     */
    fun retrieve(candidates: List<MemoryItem>, query: MemoryQuery): List<MemoryHit> {
        if (candidates.isEmpty()) return emptyList()

        val terms = tokenize(query.text)

        // 只融合「相关性」类信号（关键词、标签）。
        val keywordRanked = rankByKeyword(candidates, terms).take(query.candidatePool)
        val tagRanked = rankByTag(candidates, terms).take(query.candidatePool)

        val fused = LinkedHashMap<Long, Double>()
        val sources = LinkedHashMap<Long, MutableList<String>>()

        fun accumulate(ranked: List<MemoryItem>, label: String) {
            ranked.forEachIndexed { index, item ->
                fused[item.id] = (fused[item.id] ?: 0.0) + 1.0 / (rrfK + index + 1)
                sources.getOrPut(item.id) { mutableListOf() }.add(label)
            }
        }

        accumulate(keywordRanked, SOURCE_KEYWORD)
        accumulate(tagRanked, SOURCE_TAG)

        // ## 为什么时间不作为一路 RRF
        //
        // 曾经把时间也当一路融合，实测发现一个真缺陷：时间排序是「永不空」的通道，
        // 当它与关键词路信息对称时（比如两条记忆命中次数相同），两路会**相互抵消**——
        // 关键词路说 A 第一、时间路说 B 第一，两份 1/(k+rank) 加完完全相等，
        // 最后只能按 id 排序，等于「命中更多的」和「更新的」两个信号全丢了。
        //
        // 正确的分工：
        //  - **RRF 只融合相关性信号**（关键词、标签）——它们量纲不同但语义同类；
        //  - **时间只做同分仲裁**（tie-breaker）——两条一样相关时，较新的优先；
        //  - **无任何相关命中时**（空查询 / 纯浏览）才用时间排序兜底，
        //    否则一条不相关的记忆会仅因为「新」被注入上下文。
        val byId = candidates.associateBy { it.id }

        if (fused.isEmpty()) {
            return rankByRecency(candidates)
                .take(query.limit)
                .map { MemoryHit(item = it, score = 0.0, sources = listOf(SOURCE_RECENCY)) }
        }

        return fused.entries
            .mapNotNull { (id, score) -> byId[id]?.let { score to it } }
            .sortedWith(
                compareByDescending<Pair<Double, MemoryItem>> { it.first }
                    .thenByDescending { effectiveTime(it.second) }
                    // 同分且同时闲时按 id 升序：保证结果稳定，不因 Map 遍历顺序而变化。
                    .thenBy { it.second.id },
            )
            .take(query.limit)
            .map { (score, item) ->
                MemoryHit(item = item, score = score, sources = sources[item.id].orEmpty())
            }
    }

    /**
     * 关键词召回：按查询词在正文里的命中**次数**降序。
     *
     * 不做 BM25 的 IDF 部分：候选集规模小（几十到几千），
     * IDF 的收益盖不过它引入的「需要全库统计」这一复杂度。
     *
     * ## 同分时必须用时间而不是 id 作次序
     *
     * 这是一个**影响正确性**的细节：RRF 只看名次，如果两条命中次数相同、
     * 这里按 id 排，它们就拿 a 1/(k) 和 1/(k+1) 两个**不相等**的分数，
     * 于是外层「同分时用时间仲裁」永远不会生效（分数已经不等了）。
     * 表现为：两条一样相关，旧的那条因为 id 小反而排前面。
     *
     * 因此命中次数相同时按「最近使用时间」降序。
     */
    private fun rankByKeyword(candidates: List<MemoryItem>, terms: List<String>): List<MemoryItem> {
        if (terms.isEmpty()) return emptyList()
        return candidates
            .map { item -> item to keywordScore(item, terms) }
            .filter { (_, score) -> score > 0 }
            .sortedWith(
                compareByDescending<Pair<MemoryItem, Int>> { it.second }
                    .thenByDescending { effectiveTime(it.first) }
                    .thenBy { it.first.id },
            )
            .map { it.first }
    }

    private fun keywordScore(item: MemoryItem, terms: List<String>): Int {
        val text = item.content.lowercase()
        return terms.sumOf { term -> countOccurrences(text, term) }
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    /**
     * 时间召回：最近用过的排前面。
     *
     * 用 [MemoryItem.lastUsedAt] 而不是 `createdAt`：一个半年前创建但昨天刚被引用的记忆，
     * 显然比「上个月创建就再没用过」的更相关。优先委时用 createdAt 兜底。
     */
    private fun rankByRecency(candidates: List<MemoryItem>): List<MemoryItem> =
        candidates.sortedWith(
            compareByDescending<MemoryItem> { effectiveTime(it) }.thenBy { it.id },
        )

    private fun effectiveTime(item: MemoryItem): Long =
        if (item.lastUsedAt > 0) item.lastUsedAt else item.createdAt

    /** 标签召回：正文或标签命中查询词。 */
    private fun rankByTag(candidates: List<MemoryItem>, terms: List<String>): List<MemoryItem> {
        if (terms.isEmpty()) return emptyList()
        return candidates
            .filter { item -> item.tags.any { tag -> terms.any { it in tag.lowercase() } } }
            .sortedWith(compareByDescending<MemoryItem> { it.hitCount }.thenBy { it.id })
    }

    /**
     * 切词。
     *
     * 中文没有空格，按空格切会把整句当成一个词，命中率为 0。
     * 这里采用「拉丁词按非字母数字切 + 每个汉字单独成词」的混合策略：
     *  - 对英文：`hello world` -> [hello, world]；
     *  - 对中文：`记得我喜欢喝咖啡` -> [记, 得, 我, 喜, 欢, 喝, 咖, 啡]，
     *    任意两字重叠都会命中，召回率足够且实现只有几行。
     *
     * 单字切词会产生跟多噪声（比如「的」），所以过滤掉 1 字宽的非汉字项，
     * 汉字则保留单字（汉字单字信息量远高于字母单字）。
     */
    internal fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        val latin = StringBuilder()

        fun flushLatin() {
            if (latin.length >= 2) out += latin.toString().lowercase()
            latin.setLength(0)
        }

        for (c in text) {
            if (isCjk(c)) {
                flushLatin()
                out += c.toString()
            } else if (c.isLetterOrDigit()) {
                latin.append(c)
            } else {
                flushLatin()
            }
        }
        flushLatin()
        return out.toList()
    }

    private fun isCjk(c: Char): Boolean =
        c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF

    companion object {
        /** RRF 原论文经验值：压制名次间差距，让多路都有贡献。 */
        const val DEFAULT_RRF_K = 60

        const val SOURCE_KEYWORD = "keyword"
        const val SOURCE_RECENCY = "recency"
        const val SOURCE_TAG = "tag"
    }
}
