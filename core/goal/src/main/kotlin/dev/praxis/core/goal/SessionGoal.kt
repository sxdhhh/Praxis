package dev.praxis.core.goal

/**
 * 同会话目标的阶段。
 *
 * ## 为什么需要显式的目标状态机（2026-11-06）
 *
 * 模型在长任务里最容易出的问题不是「不会做」，而是**忘了自己在做什么**：
 * 做了一半去处理细节，细节处理完就停了，而原目标还没达成。
 *
 * 让模型每轮看到「目标是什么、做到哪了」能显著缓解这个问题，
 * 但前提是这份状态**跨轮持久且由框架维护**，而不是靠模型自己记得。
 *
 * ## 阶段按「模型接下来该做什么」划分
 *
 * 同一个 Active 阶段里模型可能跑很多轮；而 Paused 与 Done 要求它
 * **立刻改变行为**（停手 / 收尾）。
 *
 * ## 为什么没有 Failed
 *
 * 目标失败是「用户判断」而不是「框架判断」。框架能知道的只有
 * 「还在做」「暂停了」「完成了」。把「失败」也变成状态会诱使模型
 * 过早宣布放弃 —— 而用户往往只想要它再试试。
 */
enum class GoalPhase {
    /** 没有设置目标。 */
    None,

    /** 目标进行中。 */
    Active,

    /**
     * 暂停。
     *
     * ## 为什么暂停必须是独立状态
     *
     * 「设备不在线就暂停，避免一直烧 token」是用户的明确要求。
     * 暂停时模型**不应继续推进**，但目标内容要留着 —— 恢复后从原处继续。
     * 若实现成「清空目标」，恢复时用户得重新描述一遍。
     */
    Paused,

    /** 目标已达成。 */
    Done,
}

/** 一个计划步骤。 */
data class PlanStep(
    /** 这一步要做什么（一句话）。 */
    val text: String,
    val done: Boolean = false,
)

/**
 * 同会话的目标与计划。
 *
 * ## 为什么不合并成一个字符串塞进提示词
 *
 * 字符串没法回答「第几步没做完」。而那正是模型需要的信息：
 * 它要知道**下一件该做的事**，而不是回顾整份计划。
 */
data class SessionGoal(
    val objective: String = "",
    val phase: GoalPhase = GoalPhase.None,
    val steps: List<PlanStep> = emptyList(),
) {

    val isActive: Boolean get() = phase == GoalPhase.Active && objective.isNotBlank()

    /** 第一个未完成的步骤下标；全部完成返回 -1。 */
    val nextStepIndex: Int get() = steps.indexOfFirst { !it.done }

    val doneCount: Int get() = steps.count { it.done }

    /**
     * 拼给模型看的文本。
     *
     * ## 为什么只列「下一步」而不列全部步骤
     *
     * 计划可能有十几步。全部列出会占掉大量上下文，而模型真正需要的
     * 是「下一件做什么」。已完成步骤的信息已经在对话历史里了。
     */
    fun toPromptText(): String = buildString {
        if (objective.isBlank()) return ""
        val label = when (phase) {
            GoalPhase.Active -> "进行中"
            GoalPhase.Paused -> "已暂停"
            GoalPhase.Done -> "已达成"
            GoalPhase.None -> ""
        }
        appendLine("# 当前目标（$label）")
        appendLine(objective.trim())
        if (steps.isNotEmpty()) {
            appendLine()
            appendLine("进度：$doneCount/${steps.size}")
            val next = nextStepIndex
            if (next >= 0) {
                appendLine("下一步：" + steps[next].text.trim())
            }
        }
        when (phase) {
            GoalPhase.Paused ->
                appendLine("\n目标已暂停，**不要继续推进**。等用户恢复后再接着做。")
            GoalPhase.Done ->
                appendLine("\n目标已达成。请做收尾说明，不要开始新任务。")
            else -> Unit
        }
    }
}

/**
 * 目标状态机的操作（全部返回新实例）。
 *
 * ## 为什么把变换放在 object 而不是 data class 的方法
 *
 * 与 PendingQueueOps 同一取舍：状态与变换分开，单测可以只测变换，
 * 读代码时也能一眼看出「哪些操作会改状态」。
 */
object SessionGoalOps {

    /** 设置目标（会清空旧计划）。 */
    fun setObjective(goal: SessionGoal, objective: String): SessionGoal {
        val trimmed = objective.trim()
        if (trimmed.isEmpty()) return SessionGoal()
        return SessionGoal(objective = trimmed, phase = GoalPhase.Active, steps = emptyList())
    }

    /** 设置计划步骤（保留目标与阶段）。 */
    fun setSteps(goal: SessionGoal, steps: List<PlanStep>): SessionGoal =
        goal.copy(steps = steps)

    /**
     * 勾选/取消某一步。
     *
     * ## 为什么越界时返回原实例而不是报错
     *
     * 模型给的下标可能过期（它记得的步骤数与当前不符）。报错会让整轮
     * 工具调用失败，而「忽略一个无效下标」的结果与用户预期一致：
     * 那一步本来就不存在。
     */
    fun markStep(goal: SessionGoal, index: Int, done: Boolean = true): SessionGoal {
        if (index !in goal.steps.indices) return goal
        return goal.copy(
            steps = goal.steps.mapIndexed { i, step ->
                if (i == index) step.copy(done = done) else step
            },
        )
    }

    /** 暂停（只对 Active 生效）。 */
    fun pause(goal: SessionGoal): SessionGoal =
        if (goal.phase == GoalPhase.Active) goal.copy(phase = GoalPhase.Paused) else goal

    /** 恢复（只对 Paused 生效）。 */
    fun resume(goal: SessionGoal): SessionGoal =
        if (goal.phase == GoalPhase.Paused) goal.copy(phase = GoalPhase.Active) else goal

    /** 标记达成。 */
    fun complete(goal: SessionGoal): SessionGoal =
        if (goal.objective.isBlank()) goal else goal.copy(phase = GoalPhase.Done)

    /** 清除目标（回到无目标）。 */
    fun clear(): SessionGoal = SessionGoal()
}
