package dev.praxis.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 高级记忆检索的回归护栏（覆盖率乘子 / 图边传播 / 模式偏置）。
 *
 * ## 为什么这三条必须测
 *
 * 它们共同决定「聊天时注入哪几条记忆」。错了不会崩，只会让 AI
 * 悄悄用错旧信息 —— 用户看到的是「它怎么突然提起不相干的事」，
 * 而没人会怀疑是检索打分出了问题。
 */
class MemorySearchPipelineTest {

    private fun item(
        id: Long,
        content: String,
        tags: List<String> = emptyList(),
        lastUsed: Long = 0L,
    ) = MemoryItem(
        id = id,
        content = content,
        tags = tags,
        createdAt = 1_000L,
        lastUsedAt = lastUsed,
        hitCount = 1,
    )

    // ── 覆盖率乘子 ──────────────────────────────────────────────────

    /**
     * 「命中 1 个词 8 次」不该压过「命中 3 个词各 1 次」。
     *
     * 这正是 RRF 只看名次的老问题：单路排序时前者关键词次数高，
     * 但按覆盖率它会输。这是本次抄 Operit 的核心改进。
     */
    @Test
    fun `多词覆盖优于单词高频`() {
        val repeated = item(1, "咖啡咖啡咖啡咖啡咖啡咖啡咖啡咖啡")
        val covered = item(2, "咖啡 喜好 早晨")
        val all = listOf(repeated, covered)

        val hits = MemorySearchPipeline().search(
            all = all,
            query = MemoryQuery(text = "咖啡 喜好 早晨", limit = 2, candidatePool = 10),
        )
        assertEquals("覆盖 3 个词的应排第一", 2L, hits.first().item.id)
    }

    // ── 图边传播 ────────────────────────────────────────────────────

    /**
     * 命中一条后，沿边把邻居带进来（即使邻居字面完全不重合）。
     *
     * 这是「问夜间 → 找到深色主题」的那条能力。
     */
    @Test
    fun `图边把不重合的邻居带进结果`() {
        val night = item(1, "用户经常在夜间使用手机")
        val darkTheme = item(2, "用户偏好深色主题") // 与查询字面零重合
        val unrelated = item(3, "用户养了一只猫")

        val edges = listOf(MemoryEdge(fromId = 1, toId = 2, weight = 1.0))
        val hits = MemorySearchPipeline().search(
            all = listOf(night, darkTheme, unrelated),
            query = MemoryQuery(text = "夜间", limit = 3, candidatePool = 10),
            edges = edges,
        )
        val ids = hits.map { it.item.id }
        assertTrue("邻居应被边传播带进结果：$ids", 2L in ids)
        // 传播进来的条目要能被解释来源
        val propagated = hits.first { it.item.id == 2L }
        assertTrue(propagated.sources.contains(MemoryRanker.SOURCE_EDGE))
    }

    /** 没有边时不能凭空带进不相关记忆。 */
    @Test
    fun `无边时不传播`() {
        val night = item(1, "用户经常在夜间使用手机")
        val darkTheme = item(2, "用户偏好深色主题")
        val hits = MemorySearchPipeline().search(
            all = listOf(night, darkTheme),
            query = MemoryQuery(text = "夜间", limit = 5, candidatePool = 10),
            edges = emptyList(),
        )
        assertTrue("不该带回零命中条目：${hits.map { it.item.id }}", hits.none { it.item.id == 2L })
    }

    /** edgeWeight = 0 时边不生效（配置能真正关掉这条通路）。 */
    @Test
    fun `edgeWeight 为零时边不生效`() {
        val night = item(1, "用户经常在夜间使用手机")
        val darkTheme = item(2, "用户偏好深色主题")
        val pipeline = MemorySearchPipeline(
            config = MemorySearchConfig(edgeWeight = 0.0),
        )
        val hits = pipeline.search(
            all = listOf(night, darkTheme),
            query = MemoryQuery(text = "夜间", limit = 5, candidatePool = 10),
            edges = listOf(MemoryEdge(1, 2, weight = 1.0)),
        )
        assertTrue(hits.none { it.item.id == 2L })
    }

    // ── 配置归一化 ──────────────────────────────────────────────────

    @Test
    fun `向量权重恒为零`() {
        // 即使有人手改配置，也不能让没实现的信号参与打分。
        val cfg = MemorySearchConfig(vectorWeight = 5.0).normalized()
        assertEquals(0.0, cfg.vectorWeight, 0.0001)
    }

    @Test
    fun `负权重被钳零`() {
        val cfg = MemorySearchConfig(keywordWeight = -3.0, tagWeight = -1.0).normalized()
        assertEquals(0.0, cfg.keywordWeight, 0.0001)
        assertEquals(0.0, cfg.tagWeight, 0.0001)
    }

    @Test
    fun `关键词优先模式抬高关键词权重`() {
        val balanced = MemorySearchConfig(scoreMode = MemoryScoreMode.BALANCED).normalized()
        val keywordFirst = MemorySearchConfig(scoreMode = MemoryScoreMode.KEYWORD_FIRST).normalized()
        assertTrue(keywordFirst.keywordWeight > balanced.keywordWeight)
        assertTrue(keywordFirst.tagWeight < balanced.tagWeight)
    }

    @Test
    fun `语义优先模式抬高标签与边权重`() {
        val balanced = MemorySearchConfig(scoreMode = MemoryScoreMode.BALANCED).normalized()
        val semantic = MemorySearchConfig(scoreMode = MemoryScoreMode.SEMANTIC_FIRST).normalized()
        assertTrue(semantic.keywordWeight < balanced.keywordWeight)
        assertTrue(semantic.tagWeight > balanced.tagWeight)
        assertTrue(semantic.edgeWeight > balanced.edgeWeight)
    }

    @Test
    fun `未知模式名回落均衡`() {
        assertEquals(MemoryScoreMode.BALANCED, MemoryScoreMode.byName("不存在的模式"))
        assertEquals(MemoryScoreMode.BALANCED, MemoryScoreMode.byName(null))
    }

    // ── 兜底与边界 ──────────────────────────────────────────────────

    /** 一个词都没命中时不返回空，而是退回「最近使用」。 */
    @Test
    fun `零命中退回最近使用`() {
        val old = item(1, "完全无关的内容甲", lastUsed = 100L)
        val recent = item(2, "完全无关的内容乙", lastUsed = 9_999L)
        val hits = MemorySearchPipeline().search(
            all = listOf(old, recent),
            query = MemoryQuery(text = "一点都不存在", limit = 5, candidatePool = 10),
        )
        assertTrue("不该返回空", hits.isNotEmpty())
        assertEquals("最近使用的排第一", 2L, hits.first().item.id)
    }

    @Test
    fun `空库返回空结果`() {
        val hits = MemorySearchPipeline().search(
            all = emptyList(),
            query = MemoryQuery(text = "任意", limit = 5),
        )
        assertTrue(hits.isEmpty())
    }
}