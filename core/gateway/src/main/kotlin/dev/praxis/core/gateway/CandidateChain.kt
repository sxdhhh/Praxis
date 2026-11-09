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
 * 名称冲突：两个名字忽略大小写后相同，但原文不同。
 *
 * 文档 5.4 的原话是「网关全链路 eq_ignore_ascii_case。别名与 id 只差大小写会
 * 互相遮蔽 —— **配置时就要拦并说明**」（#208）。所以它不是一个运行期判定，
 * 而是保存配置时要报给用户的东西。
 */
data class CaseCollision(val first: String, val second: String)

/**
 * 候选链生成（文档 7.1）。
 *
 * ## 两种承载
 *
 * - 原生承载：某家清单里有 id 等于对外名的模型，直接用；
 * - 映射承载：modelRules.mappings 里有 alias 等于对外名的条目，
 *   每条映射给出 (provider 或默认家, target, reasoning)。
 *
 * 同一对外名**可以有多条映射**（跨家），顺序就是配置顺序 —— 这正是候选链要
 * 表达的：第一家不行就换第二家。
 *
 * ## 匹配一律忽略大小写（2026-11-08 修正）
 *
 * 文档 5.4 明确写着网关全链路是 eq_ignore_ascii_case。我这版一开始用了精确
 * 比较，那会让用户在模型页填 gpt-4o、配置里写 GPT-4o 时匹配不上 —— 而错误
 * 表现只是「没有可用候选」，看不出是大小写的问题。
 *
 * ## 一处文档没写的取舍：映射优先于原生
 *
 * 7.1 只画了两种承载，没说两者同时命中时谁赢。这里选**映射优先**：映射是用户
 * 显式配置的意图（我想让 fast 走 gpt-x），而原生只是「碰巧有个同名模型」。
 */
object CandidateChain {

    fun of(
        requestedModel: String,
        nativeModels: List<NativeModel>,
        mappings: List<ModelMapping>,
        /** mapping.provider 为空时的默认家；null 表示没有默认家。 */
        defaultProvider: String?,
        /**
         * 路由组（文档 §5.5）。命中时**优先于映射**：
         * 组是更明确的「对外名」概念，而且天然多对多。
         */
        groups: List<GatewayRouteGroup> = emptyList(),
    ): CandidateChainResult {
        val requested = requestedModel.trim()
        if (requested.isEmpty()) {
            return CandidateChainResult(emptyList(), emptyList())
        }

        GatewayRouteGroupCodec.candidatesFor(requested, groups)?.let { members ->
            return CandidateChainResult(members, emptyList())
        }

        val matchedMappings = mappings.filter { it.enabled && it.alias.equals(requested, ignoreCase = true) }
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
            .filter { it.id.equals(requested, ignoreCase = true) }
            .map { ModelCandidate(provider = it.provider, wireModel = it.id) }
        return CandidateChainResult(native, emptyList())
    }

    /**
     * 从结构化条目生成候选链（文档 6.3 + 7.1）。
     *
     * 比 [of] 多一条过滤：**enabled = false 的条目不参与选路**（文档 5.4 第 2 条
     * 「逐模型启停」）。
     */
    fun ofEntries(
        requestedModel: String,
        entries: List<ModelEntry>,
        mappings: List<ModelMapping>,
        defaultProvider: String?,
    ): CandidateChainResult {
        val usable = entries.filter { it.enabled }
        return of(
            requestedModel = requestedModel,
            nativeModels = usable.map { NativeModel(provider = it.provider, id = it.id) },
            mappings = mappings,
            defaultProvider = defaultProvider,
        )
    }

    /**
     * 找出「忽略大小写后相同、原文不同」的名字对。
     *
     * 文档 5.4 要求配置时就拦：别名与 id 只差大小写会互相遮蔽，运行期无法判断
     * 用户想要哪一个。返回所有冲突对（按发现顺序），空表示没有冲突。
     */
    fun detectCaseCollisions(names: List<String>): List<CaseCollision> {
        val distinct = names.filter { it.isNotBlank() }.distinct()
        val collisions = mutableListOf<CaseCollision>()
        for (i in distinct.indices) {
            for (j in i + 1 until distinct.size) {
                val a = distinct[i]
                val b = distinct[j]
                if (a != b && a.equals(b, ignoreCase = true)) {
                    collisions += CaseCollision(a, b)
                }
            }
        }
        return collisions
    }
}
