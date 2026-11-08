package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选链生成（文档 7.1）。
 *
 * ## 为什么不验「只有一条候选」就够
 *
 * 候选链的价值全在**多条**：同一对外名在第一家额度用尽时要能落到第二家。
 * 只测单条的话，「顺序」和「跨家」这两个最容易出错的性质都没被验到。
 */
class CandidateChainTest {

    private val native = listOf(
        NativeModel(provider = "openai", id = "gpt-x"),
        NativeModel(provider = "anthropic", id = "gpt-x"),
        NativeModel(provider = "openai", id = "gpt-y"),
    )

    @Test
    fun 原生承载_同名模型按传入顺序成链() {
        val result = CandidateChain.of(
            requestedModel = "gpt-x",
            nativeModels = native,
            mappings = emptyList(),
            defaultProvider = null,
        )

        assertEquals(
            listOf(
                ModelCandidate("openai", "gpt-x"),
                ModelCandidate("anthropic", "gpt-x"),
            ),
            result.candidates,
        )
        assertTrue(result.unresolvedMappings.isEmpty())
    }

    @Test
    fun 映射承载_用映射里的提供商与目标() {
        val result = CandidateChain.of(
            requestedModel = "fast",
            nativeModels = native,
            mappings = listOf(
                ModelMapping(alias = "fast", target = "gpt-4o-mini", provider = "openai", reasoning = "low"),
            ),
            defaultProvider = null,
        )

        assertEquals(
            listOf(ModelCandidate("openai", "gpt-4o-mini", "low")),
            result.candidates,
        )
    }

    @Test
    fun 多条映射保持配置顺序() {
        val result = CandidateChain.of(
            requestedModel = "fast",
            nativeModels = native,
            mappings = listOf(
                ModelMapping(alias = "fast", target = "m-a", provider = "openai"),
                ModelMapping(alias = "fast", target = "m-b", provider = "anthropic"),
                ModelMapping(alias = "fast", target = "m-c", provider = "openai"),
            ),
            defaultProvider = null,
        )

        // 顺序 = 配置顺序，这是「第一家不行换第二家」的唯一依据。
        assertEquals(
            listOf("m-a", "m-b", "m-c"),
            result.candidates.map { it.wireModel },
        )
    }

    @Test
    fun 映射没写提供商时用默认家() {
        val result = CandidateChain.of(
            requestedModel = "fast",
            nativeModels = native,
            mappings = listOf(ModelMapping(alias = "fast", target = "m-a")),
            defaultProvider = "openai",
        )

        assertEquals(listOf(ModelCandidate("openai", "m-a")), result.candidates)
    }

    @Test
    fun 既没提供商也没默认家时列入未解析() {
        val mapping = ModelMapping(alias = "fast", target = "m-a")

        val result = CandidateChain.of(
            requestedModel = "fast",
            nativeModels = native,
            mappings = listOf(mapping),
            defaultProvider = null,
        )

        assertTrue(result.candidates.isEmpty())
        assertEquals(
            "配了映射却发不出去，必须能让界面说清是哪一条",
            listOf(mapping),
            result.unresolvedMappings,
        )
    }

    @Test
    fun 未启用的映射不参与选路() {
        val result = CandidateChain.of(
            requestedModel = "fast",
            nativeModels = native,
            mappings = listOf(
                ModelMapping(alias = "fast", target = "m-off", provider = "openai", enabled = false),
                ModelMapping(alias = "fast", target = "m-on", provider = "openai"),
            ),
            defaultProvider = null,
        )

        assertEquals(listOf("m-on"), result.candidates.map { it.wireModel })
    }

    @Test
    fun 映射优先于同名原生模型() {
        // 文档 7.1 没写两者同时命中时谁赢。本实现的决策：显式映射优先，
        // 因为映射是用户主动配置的意图，原生只是「碰巧有同名模型」。
        val result = CandidateChain.of(
            requestedModel = "gpt-x",
            nativeModels = native,
            mappings = listOf(ModelMapping(alias = "gpt-x", target = "m-mapped", provider = "openai")),
            defaultProvider = null,
        )

        assertEquals(listOf(ModelCandidate("openai", "m-mapped")), result.candidates)
    }

    @Test
    fun 空名字得到空链() {
        val result = CandidateChain.of(
            requestedModel = "   ",
            nativeModels = native,
            mappings = emptyList(),
            defaultProvider = "openai",
        )

        assertTrue(result.candidates.isEmpty())
        assertTrue(result.unresolvedMappings.isEmpty())
    }

    @Test
    fun 查不到任何候选时返回空链() {
        val result = CandidateChain.of(
            requestedModel = "nobody-knows",
            nativeModels = native,
            mappings = emptyList(),
            defaultProvider = null,
        )

        assertTrue(result.candidates.isEmpty())
        assertTrue(result.unresolvedMappings.isEmpty())
    }

    // ── 大小写与遮蔽（文档 5.4，2026-11-08 修正）──

    @Test
    fun 原生匹配忽略大小写() {
        // 文档 5.4：网关全链路 eq_ignore_ascii_case。精确比较会让
        // 「模型页填 gpt-4o、配置里写 GPT-4o」匹配不上，表现只是「没有可用候选」。
        val result = CandidateChain.of(
            requestedModel = "GPT-X",
            nativeModels = native,
            mappings = emptyList(),
            defaultProvider = null,
        )

        assertEquals(listOf("gpt-x", "gpt-x"), result.candidates.map { it.wireModel })
    }

    @Test
    fun 映射别名匹配忽略大小写() {
        val result = CandidateChain.of(
            requestedModel = "FAST",
            nativeModels = native,
            mappings = listOf(ModelMapping(alias = "fast", target = "m-a", provider = "openai")),
            defaultProvider = null,
        )

        assertEquals(listOf("m-a"), result.candidates.map { it.wireModel })
    }

    @Test
    fun 停用的条目不参与选路() {
        val entries = listOf(
            ModelEntry(provider = "openai", id = "gpt-x", source = ModelSource.Native),
            ModelEntry(provider = "anthropic", id = "gpt-x", source = ModelSource.Native, enabled = false),
        )

        val result = CandidateChain.ofEntries(
            requestedModel = "gpt-x",
            entries = entries,
            mappings = emptyList(),
            defaultProvider = null,
        )

        assertEquals(listOf("openai"), result.candidates.map { it.provider })
    }

    @Test
    fun 条目候选链与原生链一致() {
        val entries = listOf(
            ModelEntry(provider = "openai", id = "gpt-x", source = ModelSource.Native),
            ModelEntry(provider = "anthropic", id = "GPT-X", source = ModelSource.Native),
        )

        val result = CandidateChain.ofEntries("gpt-x", entries, emptyList(), null)

        // 顺带证明 ofEntries 没有偷偷改写成大小写：wireModel 仍是原文。
        assertEquals(listOf("gpt-x", "GPT-X"), result.candidates.map { it.wireModel })
    }

    @Test
    fun 遮蔽检测只差大小写算冲突() {
        val collisions = CandidateChain.detectCaseCollisions(listOf("fast", "FAST", "slow"))

        assertEquals(listOf(CaseCollision("fast", "FAST")), collisions)
    }

    @Test
    fun 完全相同的重复不算遮蔽() {
        // 同名重复是列表里的两个位置，不是遮蔽；文档要求拦的是「只差大小写」。
        assertTrue(CandidateChain.detectCaseCollisions(listOf("fast", "fast")).isEmpty())
    }

    @Test
    fun 没有冲突时返回空() {
        assertTrue(CandidateChain.detectCaseCollisions(listOf("a", "b", "c")).isEmpty())
    }
}
