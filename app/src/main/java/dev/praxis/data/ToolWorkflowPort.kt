package dev.praxis.data

import dev.praxis.core.tool.ToolContext
import dev.praxis.core.tool.ToolRegistry
import dev.praxis.core.workflow.WorkflowToolPort

/**
 * [WorkflowToolPort] 的实现：转给已注册的工具。
 *
 * ## 为什么不用 ToolExecutor
 *
 * `ToolExecutor.executeAll` 是给「模型返回 tool_calls」场景用的：它要一次处理
 * 多个调用、要弹 ASK 确认、要输出 [ToolExecution] 记录。而工作流是**用户在
 * 编辑器里亲手配置的确定流程**，不存在「模型乱发工具」的风险，也不需要在
 * 运行中途弹窗打断（弹窗会让自动化停滞等人）。
 *
 * 所以这里直接调 `tool.execute`，把权限判定留给工具本身（它内部会检查 tier）。
 *
 * ## 参数直接用 JSON 字符串
 *
 * `Tool.execute(argumentsJson: String, ...)` 本来就收 JSON 文本，
 * 而工作流模板插值后正好也是 JSON 文本——中间不需要任何解析。
 * （曾经这里写过 40 行手写 JSON 解析器，是对签名的误判产生的死代码。）
 */
class ToolWorkflowPort(
    private val registry: ToolRegistry,
    private val contextProvider: () -> ToolContext,
) : WorkflowToolPort {

    override suspend fun call(toolName: String, argsJson: String): String {
        val tool = registry.find(toolName)
            ?: return "工具不存在：$toolName"

        return runCatching {
            // 现取上下文：用户可能在工作流跑之前刚改了档位。
            val result = tool.execute(normalizeJson(argsJson), contextProvider())
            if (result.isError) "工具调用失败：${result.content}" else result.content
        }.getOrElse { e ->
            "工具调用异常：${e.message}"
        }
    }

    /**
     * 空串归一为 `{}`。
     *
     * 无参工具（如 list_files）在编辑器里往往留空；
     * 直接传空串给 `execute` 会让它去 `JSONObject("")` 抛异常，
     * 变成难懂的「工具调用异常」而不是正常的无参调用。
     */
    private fun normalizeJson(raw: String): String {
        val t = raw.trim()
        return if (t.isEmpty()) "{}" else t
    }
}
