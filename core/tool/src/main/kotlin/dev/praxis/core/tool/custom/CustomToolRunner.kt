package dev.praxis.core.tool.custom

import dev.praxis.core.tool.ToolContext
import dev.praxis.core.tool.ToolExecutor
import dev.praxis.core.tool.ToolInvocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 执行一条自定义工具定义。
 *
 * ## 三步走的生命周期（照 Harness extensions 的 define/load/run）
 *
 * | 阶段 | 做什么 | 是否执行 |
 * |---|---|---|
 * | define | 解析 + 校验（CustomToolValidator） | **否** |
 * | load | 注册到 ToolRegistry | 否 |
 * | run | 逐步执行原语 | 是 |
 *
 * 定义阶段绝不执行任何东西 —— 这是整个设计的基石。用户/AI 写下的定义
 * 在装载时只做静态检查，唯一能产生效果的时刻是模型真正调用它。
 *
 * ## 为什么每一步都走 ToolExecutor
 *
 * 自定义工具**不绕过任何既有约束**：每一步都是标准工具调用，都要过
 * 权限判定、都能被用户拒绝、都带五类失败归因。
 *
 * 如果这里自己直接调原语实现，权限系统就被绕过去了 —— 而用户完全看
 * 不出来（界面显示的是「调用了自定义工具」）。
 */
class CustomToolRunner(
    private val executor: ToolExecutor,
    /** 总超时（毫秒）。默认 120 秒，与 PTC runtime 默认值一致。 */
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    suspend fun run(
        def: CustomToolDefinition,
        args: Map<String, String>,
        context: ToolContext,
    ): RunOutcome {
        val problems = CustomToolValidator.validate(def).filter { it.isError }
        if (problems.isNotEmpty()) {
            // 定义不合法就不该走到执行 —— 万一走到了，明确拒绝而不是硬跑。
            return RunOutcome(
                text = "工具定义不合法：" + problems.joinToString("；") { it.message },
                isError = true,
            )
        }

        val missing = def.params.filterValues { it.required }.keys - args.keys
        if (missing.isNotEmpty()) {
            return RunOutcome(
                text = "缺少必填参数：" + missing.joinToString(", ") + "。",
                isError = true,
            )
        }

        val outputs = mutableListOf<String>()
        val failure = withTimeoutOrNull(timeoutMs) {
            for ((index, step) in def.steps.withIndex()) {
                val bound = step.args.mapValues { (_, raw) -> substitute(raw, args) }
                val invocation = ToolInvocation(
                    callId = "custom-" + def.name + "-" + index,
                    name = step.op,
                    argumentsJson = toJsonObject(bound),
                )
                val execution = try {
                    executor.executeAll(listOf(invocation), context).single()
                } catch (cancel: CancellationException) {
                    throw cancel
                }
                if (execution.result.isError) {
                    // ## 某步失败时**不继续**后续步骤
                    //
                    // 继续跑会让「前一步写了一半」的状态被后续步骤建立在上面。
                    // 直接停下并说明是哪一步失败，让模型自己判断怎么补救。
                    return@withTimeoutOrNull RunOutcome(
                        text = "第 " + (index + 1) + " 步（" + step.op + "）失败：" +
                            execution.result.content,
                        isError = true,
                    )
                }
                outputs += execution.result.content
            }
            null
        }

        // withTimeoutOrNull 返回非 null 表示循环内提前返回（某步失败）。
        if (failure != null) return failure
        if (outputs.size == def.steps.size) {
            return RunOutcome(
                text = outputs.filter { it.isNotBlank() }.joinToString("\n"),
                isError = false,
            )
        }
        return RunOutcome(
            text = "执行超时（" + (timeoutMs / 1000) + " 秒），已完成 " +
                outputs.size + "/" + def.steps.size + " 步。" +
                "**已完成的步骤可能已产生副作用**，请先确认实际状态。",
            isError = true,
        )
    }

    /**
     * 把 {{name}} 替换成实参。
     *
     * ## 只做文本替换，不改语义
     *
     * 替换结果成为原语参数的值，由原语自己决定怎么用（write_file 的 content、
     * linux_exec 的 cmd……）。自定义工具**没有新增注入面**：它只是把参数
     * 填进一个本来就要执行的操作里。
     *
     * 真正的安全边界在「谁能创建自定义工具」（审批）与原语白名单，
     * 而不在这一层做字符串过滤 —— 那种过滤永远漏。
     */
    private fun substitute(raw: String, args: Map<String, String>): String {
        var result = raw
        args.forEach { (key, value) ->
            result = result.replace("{{" + key + "}}", value)
        }
        return result
    }

    /** 极简 JSON 对象拼装（值全是字符串）。 */
    private fun toJsonObject(map: Map<String, String>): String = buildString {
        append('{')
        map.entries.forEachIndexed { index, entry ->
            if (index > 0) append(',')
            append('"').append(escape(entry.key)).append('"').append(':')
            append('"').append(escape(entry.value)).append('"')
        }
        append('}')
    }

    private fun escape(s: String): String = s
        .replace(CHR_BACKSLASH, CHR_BACKSLASH + CHR_BACKSLASH)
        .replace(CHR_QUOTE, CHR_BACKSLASH + CHR_QUOTE)
        .replace(CHR_NL, CHR_BACKSLASH + "n")
        .replace(CHR_CR, CHR_BACKSLASH + "r")
        .replace(CHR_TAB, CHR_BACKSLASH + "t")

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 120_000L
        private const val CHR_BACKSLASH = "\\"
        private const val CHR_QUOTE = "\""
        private const val CHR_NL = "\n"
        private const val CHR_CR = "\r"
        private const val CHR_TAB = "\t"
    }
}

/** 执行结果。 */
data class RunOutcome(
    val text: String,
    val isError: Boolean,
)
