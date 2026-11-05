package dev.praxis.core.tool.custom

import dev.praxis.core.tool.PermissionTier
import dev.praxis.core.tool.Tool
import dev.praxis.core.tool.ToolArguments
import dev.praxis.core.tool.ToolContext
import dev.praxis.core.tool.ToolResult
import dev.praxis.core.tool.ToolRisk
import dev.praxis.core.tool.ToolSpec

/**
 * 把一条 [CustomToolDefinition] 包装成可注册的 [Tool]。
 *
 * ## 为什么需要这一层
 *
 * `ToolRegistry` 只认 `Tool` 接口。自定义工具是**数据**，需要一层适配
 * 把它变成行为 —— 这也是「声明式」与「可注册」之间唯一的一层胶水。
 *
 * ## 为什么权限档位固定为 STANDARD、风险固定为 Moderate
 *
 * 自定义工具能做的事**完全由它引用的原语决定**。这里不按定义内容动态
 * 升降档位，理由：
 *
 *  - 升到 ROOT 会让普通用户直接看不到自己刚创建的工具；
 *  - 降到 Safe 会掩盖「它内部可能调 linux_exec」这个事实。
 *
 * 真正把风险表达清楚的是每一步自己的档位 —— 它们在 [CustomToolRunner]
 * 里逐个过 `ToolExecutor`，所以危险原语仍然会被拦。
 */
class CustomTool(
    val definition: CustomToolDefinition,
    private val runner: CustomToolRunner,
) : Tool {

    override val spec: ToolSpec = ToolSpec(
        name = definition.name,
        description = buildString {
            append(definition.description)
            append("\n\n（用户自定义工具。步骤：")
            append(definition.steps.mapIndexed { i, s -> (i + 1).toString() + "." + s.op }.joinToString(" → "))
            append("）")
        },
        parametersJson = buildParametersJson(),
        tier = PermissionTier.STANDARD,
        risk = if (definition.hasSideEffects) ToolRisk.Moderate else ToolRisk.Safe,
    )

    override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
        val parsed = ToolArguments.parse(argumentsJson)
        val args = definition.params.keys.associateWith { key -> parsed.string(key) }
        val outcome = runner.run(definition, args, context)
        return ToolResult(content = outcome.text, isError = outcome.isError)
    }

    /**
     * 由参数声明生成 JSON Schema。
     *
     * 与内置工具同样手写字符串（见 `ToolSpec.parametersJson` 的说明：
     * 各家模型的 schema 方言不统一，从 Kotlin 类型自动生成反而不可控）。
     */
    private fun buildParametersJson(): String = buildString {
        append("{\"type\":\"object\",\"properties\":{")
        definition.params.entries.forEachIndexed { index, entry ->
            if (index > 0) append(',')
            append('"').append(entry.key).append("\":{")
            append("\"type\":\"").append(entry.value.type).append("\"")
            if (entry.value.description.isNotBlank()) {
                append(",\"description\":\"").append(entry.value.description).append("\"")
            }
            append('}')
        }
        append("},")
        val required = definition.params.filterValues { it.required }.keys
        append("\"required\":[")
        required.forEachIndexed { index, name ->
            if (index > 0) append(',')
            append('"').append(name).append('"')
        }
        append("]}")
    }
}
