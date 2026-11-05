package dev.praxis.data.spill

import dev.praxis.core.tool.PermissionTier
import dev.praxis.core.tool.Tool
import dev.praxis.core.tool.ToolContext
import dev.praxis.core.tool.ToolResult
import dev.praxis.core.tool.ToolRisk
import dev.praxis.core.tool.ToolSpec

/**
 * 取回溢出存储里的完整输出。
 *
 * ## 为什么必须有这个工具
 *
 * 溢出存储把长输出落盘后只给模型一个定位符与首尾片段。**没有取回入口时，
 * 那个定位符就是一句空话** —— 模型知道「还有更多内容」，却永远看不到。
 *
 * ## 为什么档位是 STANDARD 而不是 ROOT
 *
 * 溢出文件是**本应用自己写出来的命令输出**，不含系统信息、不跑命令。
 * 要求 ROOT 会让普通用户在最需要排查的时候看不到这个工具。
 *
 * ## 为什么风险是 Safe
 *
 * 只读本地文件、路径由定位符白名单限定（见 `SpillStore.read`），
 * 不改任何状态。
 */
class ReadSpillTool(
    private val store: SpillStore,
) : Tool {

    override val spec: ToolSpec = ToolSpec(
        name = "read_spill",
        description = """
            取回被溢出存储的完整命令输出。
            当你看到「[输出过大，已保存到溢出存储]」与一个 locator 时，
            用本工具把它取回来。
            定位符是随机串，不能自己猜；只接受上一条工具结果里给出的那个。
        """.trimIndent(),
        parametersJson = """
            {"type":"object","properties":{
              "locator":{"type":"string","description":"上一条结果里给出的定位符，形如 spill-1a2b3c4d5e6f7a8b"}
            },"required":["locator"]}
        """.trimIndent(),
        tier = PermissionTier.STANDARD,
        risk = ToolRisk.Safe,
    )

    override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
        val args = dev.praxis.core.tool.ToolArguments.parse(argumentsJson)
        val locator = args.string("locator")
        if (locator.isBlank()) {
            return ToolResult(content = "缺少 locator 参数。", isError = true)
        }
        val text = store.read(locator)
            ?: return ToolResult(
                content = "找不到定位符 \"$locator\"。" +
                    "它可能已被清理（溢出存储只保留最近若干份），" +
                    "或者不是本工具生成的。请重新执行原命令。",
                isError = true,
            )
        return ToolResult(content = text)
    }
}
