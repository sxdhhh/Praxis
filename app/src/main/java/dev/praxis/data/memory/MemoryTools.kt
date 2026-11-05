package dev.praxis.data.memory

import dev.praxis.core.tool.PermissionTier
import dev.praxis.core.tool.Tool
import dev.praxis.core.tool.ToolContext
import dev.praxis.core.tool.ToolResult
import dev.praxis.core.tool.ToolRisk
import dev.praxis.core.tool.ToolSpec

/**
 * 记忆工具的最小端口（避免工具层直接依赖 :app 的记忆实现）。
 *
 * 与 ChatViewModel 的 MemoryPort 分开：那个是聊天层的单向端口，
 * 这个是工具层的端口，两者生命周期与用途不同。
 */
interface MemoryQueryPort {
    suspend fun recall(query: String, limit: Int): List<String>
    suspend fun remember(content: String)
}

/**
 * 让模型**自己检索**长期记忆。
 *
 * ## 为什么改成工具，而不是每轮自动注入（2026-11-05）
 *
 * 用户原话：「长期记忆应该让 ai 自己去搜而不是直接放提示词里。
 * 每轮先搜记忆 -> 开工 -> 写回记忆」。
 *
 * 旧做法是每轮把 recall(最后一句话) 的结果拼进 system，两个问题：
 *
 * 1. **搜得太早**。发送那一刻还不知道模型这轮要做什么，只能拿用户那句话
 *    当 query。而模型真正需要记忆的时刻往往是「执行到一半想起某个约定」，
 *    那时的 query 已经完全不同了。
 * 2. **搜错了没有补救**。自动注入的记忆一旦不相关就白占上下文，
 *    而且模型无法要求「换一批」。
 *
 * 改成工具后，检索时机与 query 都由模型掌握 —— 这正是它擅长的判断。
 *
 * ## 为什么档位是 STANDARD
 *
 * 记忆只存在应用私有目录，不触系统、不跑命令。要求 ROOT 会让普通用户
 * 直接看不到这个工具，而记忆恰恰是普通用户最需要的。
 */
class SearchMemoryTool(
    private val port: MemoryQueryPort,
) : Tool {

    override val spec: ToolSpec = ToolSpec(
        name = "search_memory",
        description = """
            检索长期记忆。开工前先调一次，把与当前任务相关的约定/偏好捞出来。
            返回空表示没有相关记忆（不是错误）。
            建议每轮对话开始时调用一次；执行中途想到别的线索也可以再调。
        """.trimIndent(),
        parametersJson = """
            {"type":"object","properties":{
              "query":{"type":"string","description":"检索关键词，用当前任务的核心名词，不要写整句"},
              "limit":{"type":"integer","description":"最多几条，默认 5"}
            },"required":["query"]}
        """.trimIndent(),
        tier = PermissionTier.STANDARD,
        risk = ToolRisk.Safe,
    )

    override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
        val args = dev.praxis.core.tool.ToolArguments.parse(argumentsJson)
            ?: return ToolResult(content = "参数不是合法 JSON：" + argumentsJson, isError = true)
        val query = args.string("query").trim()
        if (query.isEmpty()) {
            return ToolResult(content = "query 不能为空：请给出检索关键词。", isError = true)
        }
        val limit = args.int("limit", 5).coerceIn(1, 20)
        val hits = port.recall(query, limit)
        return if (hits.isEmpty()) {
            ToolResult(content = "没有找到与「" + query + "」相关的长期记忆。")
        } else {
            ToolResult(
                content = buildString {
                    appendLine("与「" + query + "」相关的长期记忆（" + hits.size + " 条）：")
                    hits.forEachIndexed { index, line ->
                        appendLine((index + 1).toString() + ". " + line)
                    }
                }.trimEnd(),
            )
        }
    }
}

/**
 * 让模型把这一轮学到的东西写回长期记忆。
 *
 * ## 为什么与检索分成两个工具
 *
 * 「读」与「写」的时机、频次、风险都不同：
 *  - 读：可以多次、无副作用；
 *  - 写：应当少量、有价值。否则记忆会被噪音填满，下一轮检索质量反而下降。
 *
 * 分开之后模型能明确区分「我在查」还是「我在记」，
 * prompt 里也才能只对写提要求（「确认值得长期保留再写」）。
 */
class SaveMemoryTool(
    private val port: MemoryQueryPort,
) : Tool {

    override val spec: ToolSpec = ToolSpec(
        name = "save_memory",
        description = """
            把值得长期保留的事实写入长期记忆（用户偏好、环境约定、做过的决定）。
            只写「下次还会用到」的内容；一次性的中间结果不要写。
        """.trimIndent(),
        parametersJson = """
            {"type":"object","properties":{
              "content":{"type":"string","description":"一条自包含的事实，写清主语与结论，不要写「上面那个」这类指代"}
            },"required":["content"]}
        """.trimIndent(),
        tier = PermissionTier.STANDARD,
        risk = ToolRisk.Moderate,
    )

    override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
        val args = dev.praxis.core.tool.ToolArguments.parse(argumentsJson)
            ?: return ToolResult(content = "参数不是合法 JSON：" + argumentsJson, isError = true)
        val content = args.string("content").trim()
        if (content.isEmpty()) {
            return ToolResult(content = "content 不能为空。", isError = true)
        }
        port.remember(content)
        return ToolResult(content = "已写入长期记忆（" + content.length + " 字符）。")
    }
}
