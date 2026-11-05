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
    /**
     * 执行耗时（毫秒）。
     *
     * 只统计 [Tool.execute] 本身，不含权限判断与征询 —— 那部分是用户等待，
     * 不是工具慢；混在一起会让用户误以为工具本身很卡。
     *
     * 提前返回（未知工具 / 权限不足）时为 0：没有真正执行。
     */
    val elapsedMs: Long = 0L,
) {
    /**
     * 呈现层用的状态。
     *
     * ## 为什么要从 `result` 里派生，而不是让 UI 自己判 `isError`
     *
     * `isError` 只回答「成没成」。而工具卡要显示的是「卡在哪个阶段」——
     * 「参数错了」和「执行到一半出错」在界面上应当是**不同的样子**
     * （前者是黄色提示，后者要提醒用户检查副作用）。
     *
     * 这个派生放在 `ToolExecution` 而不是 UI：UI 拿不到 `stage` 之外的
     * 上下文（比如是否超时），而它们都属于「呈现依据」。
     */
    val presentState: ToolPresentState
        get() = when {
            !result.isError -> ToolPresentState.Success
            result.stage == ToolFailureStage.Denied -> ToolPresentState.Denied
            result.stage == ToolFailureStage.Argument -> ToolPresentState.InvalidArguments
            // 执行中/输出阶段的失败就是「执行出错」，但要在卡上标注可能已产生副作用。
            else -> ToolPresentState.Failed(result.stage ?: ToolFailureStage.Execution)
        }
}

/**
 * 工具卡的呈现状态（与执行结果分开）。
 *
 * ## 为什么 UI 需要一个独立的枚举
 *
 * 直接从 `content` 字符串里做 `contains("权限")` 之类的判断是脆的：
 * 文案一改就失效，且**不会报错**，只是卡片突然都变成同一种颜色。
 * 枚举把「呈现」与「文案」解耦。
 */
sealed interface ToolPresentState {
    /** 成功。 */
    data object Success : ToolPresentState

    /** 权限被拒（能换方案或提权，不是「坏了」）。 */
    data object Denied : ToolPresentState

    /** 参数不合法（模型自己能改）。 */
    data object InvalidArguments : ToolPresentState

    /**
     * 执行失败。
     *
     * @param stage 失败位置。UI 据此决定是否显示「可能已产生副作用」的提醒。
     */
    data class Failed(val stage: ToolFailureStage) : ToolPresentState
}

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
                // 未知工具：模型给错了名字，属于「参数/契约」错误 ——
                // 改个名字就能成功，且此前不可能有任何副作用。
                result = ToolResult(
                    content = "未知工具：${invocation.name}。" +
                        "可用工具：${registry.names().joinToString(", ")}\n" +
                        ToolFailureStage.Argument.recoveryHint,
                    isError = true,
                    stage = ToolFailureStage.Argument,
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
                    content = "权限不足：${invocation.name} 需要 $required，当前为 ${context.tier}。已拒绝执行。\n" +
                        ToolFailureStage.Denied.recoveryHint,
                    isError = true,
                    stage = ToolFailureStage.Denied,
                ),
                decision = decision,
            )
        }

        val startedAt = System.nanoTime()
        val result = try {
            tool.execute(invocation.argumentsJson, context)
        } catch (arg: ToolArgumentException) {
            // 参数错误是「模型可以自己纠正」的，必须原样回传可读描述，
            // 不能包装成「工具执行异常」——那会让模型以为是工具坏了。
            ToolResult(
                content = "参数错误：${arg.message}\n" + ToolFailureStage.Argument.recoveryHint,
                isError = true,
                stage = ToolFailureStage.Argument,
            )
        } catch (cancel: CancellationException) {
            // 取消必须原样抛出，否则协程的取消语义会被吞掉。
            throw cancel
        } catch (e: Exception) {
            // ## 这里是 [ToolFailureStage.Execution]
            //
            // 关键区分：**可能已经产生副作用**。例如写文件的工具在 `write()`
            // 成功、但之后的 `fsync` 抛错，文件其实已经写进去了。
            // 因此提示里明确要求「重试前先确认实际状态」，而不是「重试即可」。
            ToolResult(
                content = "工具执行异常：${e.message ?: e::class.java.simpleName}\n" +
                    ToolFailureStage.Execution.recoveryHint,
                isError = true,
                stage = ToolFailureStage.Execution,
            )
        }

        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        return ToolExecution(
            invocation = invocation,
            result = result,
            decision = decision,
            elapsedMs = elapsedMs,
        )
    }
}
