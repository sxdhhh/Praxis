package dev.praxis.core.gateway

/**
 * 模型页候选清单的合并（文档 §9.1）。
 *
 * ## 两个来源，谁优先
 *
 * 1. **结构化条目**（`gateway_model` 表）：带启停与能力位，是"用户明确管过"的模型；
 * 2. **目录名**（各家声明 + 路由映射别名）：账号接进来就有的名字。
 *
 * 结构化条目排在前面：用户手动管过的那批应当一眼看到，而不是被几十个目录名埋掉。
 * 去重按大小写不敏感 —— 上游对模型名大小写并不统一（`GPT-4o` 与 `gpt-4o` 是同一个）。
 *
 * ## 为什么不是"有结构化就用结构化"(二选一)
 *
 * 那会让"刚导入账号、还没逐条管过模型"的用户看不到任何候选。
 * 合并是唯一同时满足两种状态的做法，而不是兜底 —— 两个来源都是真实数据。
 */
object GatewayModelOptions {

    fun merge(structured: List<String>, catalog: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<String>()
        for (name in structured + catalog) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) continue
            val key = trimmed.lowercase()
            if (!seen.add(key)) continue
            result += trimmed
        }
        return result
    }
}
