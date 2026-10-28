package dev.praxis.feature.settings

import dev.praxis.core.tool.DefaultPermissionPolicy
import dev.praxis.core.tool.PermissionDecision
import dev.praxis.core.tool.PermissionTier
import dev.praxis.core.tool.ToolRisk
import dev.praxis.core.tool.ToolSpec
import dev.praxis.core.workflow.WorkflowNode
import dev.praxis.feature.settings.tools.riskLabel
import dev.praxis.feature.settings.tools.tierLabel
import dev.praxis.feature.settings.tools.toAccessLevel
import dev.praxis.feature.settings.capability.detailRows
import dev.praxis.feature.settings.capability.kindLabel
import dev.praxis.feature.settings.capability.primaryNextId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁死「页面显示的东西」与「运行时真实行为」一致。
 *
 * ## 为什么这几个函数值得单测
 *
 * 它们都是**映射**：业务模型 → UI 参数。映射错了不会崩溃，只会安静地显示错的信息，
 * 而用户会照着错的信息做决定（比如看到「放行」就以为高危工具会直接执行）。
 * 这类缺陷在真机上很难发现 —— 你得正好知道正确值该是什么。
 *
 * 纯 JVM 可测（不碰 Compose 运行时），所以放在这里而不是 UI 测试。
 */
class SettingsMappingTest {

    // ── 权限判定映射 ────────────────────────────────────────────

    @Test
    fun `tier covering requirement maps to Allow`() {
        assertEquals(
            dev.praxis.feature.settings.tools.ToolAccessLevel.Allow,
            DefaultPermissionPolicy.decide(PermissionTier.ROOT, PermissionTier.STANDARD).toAccessLevel(),
        )
    }

    @Test
    fun `one tier short maps to Ask`() {
        // 差一档：策略会给 ASK。页面上必须显示「需确认」而不是「拒绝」。
        assertEquals(
            dev.praxis.feature.settings.tools.ToolAccessLevel.Ask,
            DefaultPermissionPolicy.decide(PermissionTier.DEBUGGER, PermissionTier.ROOT).toAccessLevel(),
        )
    }

    @Test
    fun `two tiers short maps to Forbid`() {
        assertEquals(
            dev.praxis.feature.settings.tools.ToolAccessLevel.Forbid,
            DefaultPermissionPolicy.decide(PermissionTier.STANDARD, PermissionTier.ROOT).toAccessLevel(),
        )
    }

    @Test
    fun `every decision maps to a distinct level`() {
        // 三态必须一一对应：任何两个 decision 映射到同一 level，
        // 就意味着用户看不出「要问」和「直接拒」的区别。
        val mapped = PermissionDecision.entries.map { it.toAccessLevel() }
        assertEquals(mapped.size, mapped.toSet().size)
    }

    // ── 工作流节点映射 ──────────────────────────────────────────

    @Test
    fun `every node kind has a non blank label`() {
        // 新增节点类型时若忘了补分支，`when` 会编译失败；
        // 但补了一个空字符串不会 —— 这条用例就是防那个。
        val nodes = listOf(
            WorkflowNode.Trigger(id = "a", nextId = null),
            WorkflowNode.Tool(id = "b", title = "t", toolName = "x", nextId = null),
            WorkflowNode.Condition(id = "c", title = "t", expression = "x", thenId = null, elseId = null),
            WorkflowNode.Transform(id = "d", title = "t", template = "x", outputKey = "k", nextId = null),
            WorkflowNode.Output(id = "e"),
        )
        val labels = nodes.map { it.kindLabel() }
        assertEquals(5, labels.size)
        assertTrue(labels.all { it.isNotBlank() })
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun `tool node exposes tool name arguments and output key`() {
        val node = WorkflowNode.Tool(
            id = "b",
            title = "读文件",
            toolName = "read_file",
            argsTemplate = """{"path":"a.md"}""",
            outputKey = "content",
            nextId = "next",
        )
        val rows = node.detailRows().toMap()
        assertEquals("read_file", rows["工具"])
        assertEquals("""{"path":"a.md"}""", rows["参数"])
        assertEquals("content", rows["输出键"])
    }

    @Test
    fun `tool node without output key says so explicitly`() {
        // outputKey 为 null 是常见手误（后续节点引用不到结果），
        // 必须显示成「（不保存）」而不是省略这一行。
        val node = WorkflowNode.Tool(
            id = "b",
            title = "t",
            toolName = "x",
            outputKey = null,
            nextId = null,
        )
        val rows = node.detailRows().toMap()
        assertTrue(rows.containsKey("输出键"))
        assertTrue(rows["输出键"]!!.contains("不保存"))
    }

    @Test
    fun `condition node reports both branches through primary and branch row`() {
        val node = WorkflowNode.Condition(
            id = "c",
            title = "t",
            expression = "x contains y",
            thenId = "yes",
            elseId = "no",
        )
        // 单链后继只给 then 侧（卡片上的「→ x」）。
        assertEquals("yes", node.primaryNextId())
        // else 侧由 WorkflowBranchRow 显示，两处都要有值，不能丢分支。
        assertEquals("no", node.elseId)
    }

    @Test
    fun `output node has no successor and no detail rows when template is empty`() {
        val node = WorkflowNode.Output(id = "e", title = "结束", template = "")
        assertTrue(node.detailRows().isEmpty())
        assertEquals(null, node.primaryNextId())
    }

    // ── 工具风险文案 ────────────────────────────────────────────

    @Test
    fun `risk labels are the documented wording`() {
        // 断言**生产代码**的字面量。不在测试里再抄一份文案：
        // 抄一份等于「自测自」，两边一起改就永远绿，失去护栏意义。
        assertEquals("只读", ToolRisk.Safe.riskLabel)
        assertEquals("写入", ToolRisk.Moderate.riskLabel)
        assertEquals("高危", ToolRisk.Dangerous.riskLabel)
    }

    @Test
    fun `tier labels are the documented wording`() {
        assertEquals("标准", PermissionTier.STANDARD.tierLabel)
        assertEquals("调试", PermissionTier.DEBUGGER.tierLabel)
        assertEquals("Root", PermissionTier.ROOT.tierLabel)
    }

    // 注意：这里**不要**再包一层同名 helper。
    //
    // RiskLabel/TierLabel 已经通过 import 拿到（生产代码的扩展属性）。
    // 若写 `private fun riskLabel(risk) = with(risk) { riskLabel }`，
    // 花括号里的 `riskLabel` 会先解析到**这个私有函数自己**，无限递归 / 栈溢出；
    // 编译器不会报警（返回类型也对得上），只会在运行时挂。

    @Test
    fun `tool spec keeps its declared tier and risk`() {
        // 防止将来有人在构造 ToolSpec 时把 tier 默认值改掉而徽章跟着错位。
        val spec = ToolSpec(name = "shell", description = "d", parametersJson = "{}")
        assertEquals(PermissionTier.STANDARD, spec.tier)
        assertEquals(ToolRisk.Safe, spec.risk)
    }
}
