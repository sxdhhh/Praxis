package dev.praxis.core.tool.builtin

import dev.praxis.core.tool.PermissionTier
import dev.praxis.core.tool.Tool
import dev.praxis.core.tool.ToolArguments
import dev.praxis.core.tool.ToolContext
import dev.praxis.core.tool.ToolResult
import dev.praxis.core.tool.ToolRisk
import dev.praxis.core.tool.ToolSpec

/**
 * 用户交互端口（由 :app 实现，接管真正弹给用户的问题）。
 *
 * ## 为什么是端口而不是直接在工具里弹窗
 *
 * `:core:tool` 是纯 JVM（无 Context、无 Compose）。弹窗只有 UI 层能做，
 * 所以工具只负责「提出一个问题并等待答复」，呈现留给上层。
 */
interface AskUserPort {
    /**
     * 向用户提一个问题并等待答复。
     *
     * @param question 问题正文。
     * @param options 可选项（空表示自由输入）。
     * @param timeoutSeconds 超时秒数；-1 表示无限期等待。
     * @return 用户的答复；超时或无法询问返回 null。
     */
    suspend fun ask(question: String, options: List<String>, timeoutSeconds: Int): String?
}

/**
 * 让模型**主动向用户提问**并等待答复。
 *
 * ## 为什么需要它（2026-11-06）
 *
 * 没有这个工具时，模型遇到「缺少关键信息」只有两条路：
 *
 *  1. 猜一个继续做 —— 猜错的代价是整轮白做；
 *  2. 在回复里问一句然后停下 —— 但那一轮就结束了，用户答完还得再说「继续」。
 *
 * 第二条的问题在于：**模型无法在一个回合内等到答复**。它只能结束回合，
 * 而用户以为任务中断了。
 *
 * 有了这个工具，提问变成一次工具调用：模型在同一回合里等待，用户答完
 * 它立刻接着做。
 *
 * ## 为什么超时后返回 null 而不是抛错
 *
 * 超时是「没人回答」，不是「工具坏了」。模型应当据此决定「用默认值继续」
 * 或「结束回合」，而不该重试 —— 重试只会再等一轮。
 */
class AskUserQuestionTool(
    private val port: AskUserPort,
) : Tool {

    override val spec: ToolSpec = ToolSpec(
        name = "ask_user_question",
        description = """
            向用户提一个问题并**等待答复**后再继续。
            在你缺少关键信息、且猜错代价很高时使用（比如要删除哪些文件、用哪个分支）。
            普通的确认不要用它 —— 那会打断用户。
            用户可能不回答（超时返回空），此时请自行决定或结束回合。
        """.trimIndent(),
        parametersJson = """
            {"type":"object","properties":{
              "question":{"type":"string","description":"要问的问题，一句话说清"},
              "options":{"type":"array","items":{"type":"string"},"description":"可选项；留空表示让用户自由输入"},
              "timeout_seconds":{"type":"integer","description":"等待秒数，默认 60；-1 表示一直等"}
            },"required":["question"]}
        """.trimIndent(),
        tier = PermissionTier.STANDARD,
        risk = ToolRisk.Safe,
    )

    override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
        val args = ToolArguments.parse(argumentsJson)
        val question = args.string("question").trim()
        if (question.isEmpty()) {
            return ToolResult(content = "缺少 question 参数。", isError = true)
        }
        // ## 为什么用手写解析数组而不是引 JSON 库
        //
        // `ToolArguments` 只提供标量读取。数组要么引 kotlinx.serialization 的
        // JsonArray（`:core:tool` 已依赖它，但工具层读参数一直是标量口径），
        // 要么从 raw() 里按 JsonArray 取。这里用后者，保持与既有工具一致。
        val options = runCatching {
            args.raw()["options"]
                ?.let { element ->
                    if (element is kotlinx.serialization.json.JsonArray) {
                        element.mapNotNull { it.toString().trim('"') }
                    } else {
                        emptyList()
                    }
                }
                .orEmpty()
        }.getOrDefault(emptyList())
        val timeout = args.int("timeout_seconds", 60)
        val answer = port.ask(question, options, timeout)
        return if (answer.isNullOrBlank()) {
            ToolResult(
                content = "用户没有回答（超时或被关闭）。请自行决定最合理的做法，或结束本回合。",
                isError = false,
            )
        } else {
            ToolResult(content = "用户回答：" + answer)
        }
    }
}
