package dev.praxis.core.tool

import kotlinx.coroutines.CancellationException

/**
 * 一次工具调用的请求（对应 core:ai 的 ToolCall）。
 */
data class ToolInvocation(
    val callId: String,
    val name: String,
    val argumentsJson: String,
)

/**
 * 一次工具调用的执行记录（送进上下文 + 渲染到 UI 用）。
 */
data class ToolExecution(
    val invocation: ToolInvocation,
    val result: ToolResult,
    val decision: PermissionDecision,
)

/**
 * 工具执行器。
 *
 * ## 两条硬规矩
 *
 * 1. **严格串行**。模型可能一口气返回 3 个工具调用，必须一个一个跑完。
 *    并发跑会出真问题：先写文件再读文件，并发时「读」可能先执行，读到旧内容。
 *    这与「执行速度」无关，是正确性问题。
 *
 * 2. **结果顺序 = 调用顺序**。即使将来改成并发，回给模型的结果也必须按
 *    原始 index 排好；否则模型会拿着错位的结果推下一步。
 *
 * ## 为何不做自动重试
 *
 * 工具失败（文件不存在、命令非零退出）是**业务结果**，要让模型看到并自己纠正。
 * 自动重试只会把同一个错误重复烧 token，还会掩盖「模型给错了参数」这个真问题。
 */
class ToolExecutor(
    private val registry: ToolRegistry,
    private val policy: (granted: PermissionTier, required: PermissionTier) -> PermissionDecision =
        DefaultPermissionPolicy::decide,
) {

    /**
     * 执行一批调用，返回与输入**同序**的执行记录。
     *
     * @param askHandler 遇到 ASK 时的征询器；返回 false 表示用户拒绝。
     *   传 null 表示当前环境下无法弹框（例如后台执行），ASK 一律降级为 FORBID。
     */
    suspend fun executeAll(
        invocations: List<ToolInvocation>,
        context: ToolContext,
        askHandler: (suspend (ToolInvocation) -> Boolean)? = null,
    ): List<ToolExecution> = invocations.map { invocation ->
        executeOne(invocation, context, askHandler)
    }

    private suspend fun executeOne(
        invocation: ToolInvocation,
        context: ToolContext,
        askHandler: (suspend (ToolInvocation) -> Boolean)?,
    ): ToolExecution {
        val tool = registry.find(invocation.name)
            ?: return ToolExecution(
                invocation = invocation,
                result = ToolResult(
                    content = "未知工具：${invocation.name}。可用工具：${registry.names().joinToString(", ")}",
                    isError = true,
                ),
                decision = PermissionDecision.FORBID,
            )

        val required = tool.spec.tier
        val decision = policy(context.tier, required)

        val denied = when (decision) {
            PermissionDecision.ALLOW -> false
            PermissionDecision.FORBID -> true
            PermissionDecision.ASK -> {
                // 无法征询时按拒绝处理，不能默认放行。
                val approved = askHandler?.invoke(invocation) ?: false
                !approved
            }
        }

        if (denied) {
            return ToolExecution(
                invocation = invocation,
                result = ToolResult(
                    // 明确告诉模型「被拒了」而不是「失败了」，它的下一步完全不同：
                    // 被拒应该换方案或请求提权，失败才应该重试。
                    content = "权限不足：${invocation.name} 需要 $required，当前为 ${context.tier}。已拒绝执行。",
                    isError = true,
                ),
                decision = decision,
            )
        }

        val result = try {
            tool.execute(invocation.argumentsJson, context)
        } catch (arg: ToolArgumentException) {
            // 参数错误是「模型可以自己纠正」的，必须原样回传可读描述，
            // 不能包装成「工具执行异常」——那会让模型以为是工具坏了。
            ToolResult(content = "参数错误：${arg.message}", isError = true)
        } catch (cancel: CancellationException) {
            // 取消必须原样抛出，否则协程的取消语义会被吞掉。
            throw cancel
        } catch (e: Exception) {
            ToolResult(
                content = "工具执行异常：${e.message ?: e::class.java.simpleName}",
                isError = true,
            )
        }

        return ToolExecution(invocation = invocation, result = result, decision = decision)
    }
}
