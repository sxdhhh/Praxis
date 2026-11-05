package dev.praxis.core.goal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目标状态机。
 *
 * ## 为什么这条链值得测
 *
 * 模型在长任务里最容易出的问题不是「不会做」，而是**忘了自己在做什么**。
 * 状态机维护错了不会报任何错 —— 只会让模型安静地跑偏。
 */
class SessionGoalTest {

    @Test
    fun `设置目标后进入进行中`() {
        val goal = SessionGoalOps.setObjective(SessionGoal(), "把项目迁移到新架构")
        assertEquals(GoalPhase.Active, goal.phase)
        assertTrue(goal.isActive)
    }

    @Test
    fun `空目标等于清除`() {
        val goal = SessionGoalOps.setObjective(SessionGoal(), "   ")
        assertEquals(GoalPhase.None, goal.phase)
        assertFalse(goal.isActive)
    }

    /** 重新设置目标会清空旧计划 —— 旧步骤属于旧目标。 */
    @Test
    fun `重设目标清空旧计划`() {
        var goal = SessionGoalOps.setObjective(SessionGoal(), "A")
        goal = SessionGoalOps.setSteps(goal, listOf(PlanStep("s1")))
        goal = SessionGoalOps.setObjective(goal, "B")
        assertTrue("旧计划应被清空", goal.steps.isEmpty())
    }

    @Test
    fun `标记步骤完成`() {
        var goal = SessionGoalOps.setObjective(SessionGoal(), "A")
        goal = SessionGoalOps.setSteps(goal, listOf(PlanStep("s1"), PlanStep("s2")))
        goal = SessionGoalOps.markStep(goal, 0)
        assertEquals(1, goal.doneCount)
        assertEquals(1, goal.nextStepIndex)
    }

    /**
     * 越界下标返回原实例而不是报错。
     *
     * 模型给的下标可能过期（它记得的步骤数与当前不符）。报错会让整轮
     * 工具调用失败，而忽略无效下标的结果与用户预期一致。
     */
    @Test
    fun `越界下标被忽略`() {
        val goal = SessionGoalOps.setSteps(
            SessionGoalOps.setObjective(SessionGoal(), "A"),
            listOf(PlanStep("s1")),
        )
        assertTrue(SessionGoalOps.markStep(goal, 5) === goal)
        assertTrue(SessionGoalOps.markStep(goal, -1) === goal)
    }

    /** 暂停保留目标内容 —— 恢复后从原处继续，不用用户重述。 */
    @Test
    fun `暂停保留目标`() {
        val goal = SessionGoalOps.setObjective(SessionGoal(), "A")
        val paused = SessionGoalOps.pause(goal)
        assertEquals(GoalPhase.Paused, paused.phase)
        assertEquals("A", paused.objective)
        assertFalse(paused.isActive)
    }

    @Test
    fun `暂停只对进行中生效`() {
        val done = SessionGoalOps.complete(SessionGoalOps.setObjective(SessionGoal(), "A"))
        assertEquals(GoalPhase.Done, SessionGoalOps.pause(done).phase)
    }

    @Test
    fun `恢复只对暂停生效`() {
        val active = SessionGoalOps.setObjective(SessionGoal(), "A")
        assertEquals(GoalPhase.Active, SessionGoalOps.resume(active).phase)
    }

    /**
     * 暂停的目标必须在提示词里**明确要求停手**。
     *
     * 「设备不在线就暂停，避免一直烧 token」是用户的明确要求；
     * 只改状态而不告诉模型，它仍会继续推进。
     */
    @Test
    fun `暂停时提示词要求停手`() {
        val paused = SessionGoalOps.pause(SessionGoalOps.setObjective(SessionGoal(), "A"))
        val text = paused.toPromptText()
        assertTrue("缺少停手要求：$text", text.contains("不要继续推进"))
        assertTrue("缺少已暂停标记", text.contains("已暂停"))
    }

    /** 达成后要求收尾，不开始新任务。 */
    @Test
    fun `达成时提示词要求收尾`() {
        val done = SessionGoalOps.complete(SessionGoalOps.setObjective(SessionGoal(), "A"))
        val text = done.toPromptText()
        assertTrue("缺少收尾要求：$text", text.contains("不要开始新任务"))
    }

    /** 无目标时提示词为空串（调用方据此决定不插 system）。 */
    @Test
    fun `无目标时提示词为空`() {
        assertEquals("", SessionGoal().toPromptText())
        assertEquals("", SessionGoalOps.clear().toPromptText())
    }

    /** 提示词里要显示进度与下一步 —— 那是模型真正需要的信息。 */
    @Test
    fun `提示词含进度与下一步`() {
        var goal = SessionGoalOps.setObjective(SessionGoal(), "迁移")
        goal = SessionGoalOps.setSteps(goal, listOf(PlanStep("读旧代码", true), PlanStep("写新代码")))
        val text = goal.toPromptText()
        assertTrue("缺进度：$text", text.contains("1/2"))
        assertTrue("缺下一步：$text", text.contains("下一步：写新代码"))
    }
}
