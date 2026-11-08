package dev.praxis.core.gateway

/** 一条候选：某家的某个上游真名，外加可选的推理档位。 */
data class ModelCandidate(
    val provider: String,
    /** 真正发给上游的名字。冷却键要用它，不能用对外名（文档 6.5）。 */
    val wireModel: String,
    val reasoning: String? = null,
)

/** 某家清单里的一个原生模型。 */
data class NativeModel(
    val provider: String,
    val id: String,
)

/** modelRules.mappings 的一条（文档 8.3）。 */
data class ModelMapping(
    val alias: String,
    val target: String,
    /** null 表示用默认家。 */
    val provider: String? = null,
    val reasoning: String? = null,
    val enabled: Boolean = true,
)

/** 候选链生成结果。 */
data class CandidateChainResult(
    val candidates: List<ModelCandidate>,
    /**
     * 有映射但既没写 provider、也没有默认家 —— 这些条目**无法解析**。
     *
     * 单独列出来而不是静默丢弃：用户配了映射却发不出去，界面上必须能说清
     * 「是哪一条映射缺提供商」，否则他只能看到「请求失败」。
     */
    val unresolvedMappings: List<ModelMapping>,
)

/**
 * 候选链生成（文档 7.1）。
 *
 * ## 两种承载
 *
 * - 原生承载：某家清单里有 id 等于对外名的模型，直接用；
 * - 映射承载：modelRules.mappings 里有 alias 等于对外名的条目，
 *   每条映射给出 (provider 或默认家, target, reasoning)。
 *
 * 同一对外名**可以有多条映射**（跨家），顺序就是配置顺序 —— 这正是候选链要表达的：
 * 第一家不行就换第二家。
 *
 * ## 一处文档没写的取舍：映射优先于原生
 *
 * 7.1 只画了两种承载，没说两者同时命中时谁赢。这里选**映射优先**：映射是用户
 * 显式配置的意图（我想让 fast 走 gpt-x），而原生只是「碰巧有个同名模型」。
 * 若将来发现网关是反过来的，改这一处即可，行为集中在 [of] 里。
 */
object CandidateChain {

    fun of(
        requestedModel: String,
        nativeModels: List<NativeModel>,
        mappings: List<ModelMapping>,
        /** mapping.provider 为空时的默认家；null 表示没有默认家。 */
        defaultProvider: String?,
    ): CandidateChainResult {
        val requested = requestedModel.trim()
        if (requested.isEmpty()) {
            return CandidateChainResult(emptyList(), emptyList())
        }

        val matchedMappings = mappings.filter { it.enabled && it.alias == requested }
        if (matchedMappings.isNotEmpty()) {
            val unresolved = mutableListOf<ModelMapping>()
            val candidates = mutableListOf<ModelCandidate>()
            matchedMappings.forEach { mapping ->
                val provider = mapping.provider?.takeIf { it.isNotBlank() } ?: defaultProvider
                if (provider.isNullOrBlank()) {
                    unresolved += mapping
                } else {
                    candidates += ModelCandidate(
                        provider = provider,
                        wireModel = mapping.target,
                        reasoning = mapping.reasoning,
                    )
                }
            }
            return CandidateChainResult(candidates, unresolved)
        }

        // 原生承载：可能多家都有同名模型，按传入顺序全部成为候选。
        val native = nativeModels
            .filter { it.id == requested }
            .map { ModelCandidate(provider = it.provider, wireModel = it.id) }
        return CandidateChainResult(native, emptyList())
    }
}
