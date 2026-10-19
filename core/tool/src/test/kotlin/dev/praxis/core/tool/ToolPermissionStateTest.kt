package dev.praxis.core.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 权限状态的行为锁。
 *
 * 重点：档位与禁用列表是两个独立维度，必须都满足才可见。
 * 这条如果写反（只看档位或只看禁用），表现是「用户关了工具但模型还能调」。
 */
class ToolPermissionStateTest {

    @Test
    fun `default tier is standard`() {
        assertEquals(PermissionTier.STANDARD, ToolPermissionState().snapshot().tier)
    }

    @Test
    fun `setTier takes effect on next snapshot`() {
        val state = ToolPermissionState()
        state.setTier(PermissionTier.ROOT)
        assertEquals(PermissionTier.ROOT, state.snapshot().tier)
    }

    @Test
    fun `disabling a tool removes it from snapshot`() {
        val state = ToolPermissionState()
        assertTrue(state.snapshot().isEnabled("read_file"))

        state.setToolEnabled("read_file", false)
        assertFalse(state.snapshot().isEnabled("read_file"))

        state.setToolEnabled("read_file", true)
        assertTrue(state.snapshot().isEnabled("read_file"))
    }

    @Test
    fun `disabling twice is idempotent`() {
        val state = ToolPermissionState()
        state.setToolEnabled("x", false)
        state.setToolEnabled("x", false)
        assertEquals(setOf("x"), state.snapshot().disabledTools)
    }

    @Test
    fun `descriptors respect both tier and disabled list`() {
        val registry = ToolRegistry()
        registry.registerAll(
            FakeTool("s1", PermissionTier.STANDARD),
            FakeTool("s2", PermissionTier.STANDARD),
            FakeTool("d1", PermissionTier.DEBUGGER),
        )

        // 标准档 + 禁用 s2 → 只剩 s1
        val snapshot = ToolPermissionSnapshot(
            tier = PermissionTier.STANDARD,
            disabledTools = setOf("s2"),
        )
        val names = registry.descriptorsFor(snapshot).map { it.name }
        assertEquals(listOf("s1"), names)
    }

    @Test
    fun `higher tier unlocks more tools but disabled still excluded`() {
        val registry = ToolRegistry()
        registry.registerAll(
            FakeTool("s1", PermissionTier.STANDARD),
            FakeTool("d1", PermissionTier.DEBUGGER),
        )

        val debuggerWithoutD1 = ToolPermissionSnapshot(
            tier = PermissionTier.DEBUGGER,
            disabledTools = setOf("d1"),
        )
        assertEquals(listOf("s1"), registry.descriptorsFor(debuggerWithoutD1).map { it.name })

        val debuggerAllOn = ToolPermissionSnapshot(
            tier = PermissionTier.DEBUGGER,
            disabledTools = emptySet(),
        )
        assertEquals(listOf("s1", "d1"), registry.descriptorsFor(debuggerAllOn).map { it.name })
    }

    @Test
    fun `snapshot is immutable - later tier change does not affect it`() {
        val state = ToolPermissionState()
        val snap = state.snapshot()
        state.setTier(PermissionTier.ROOT)

        // 快照必须保持不变：一批调用不能中途换档位。
        assertEquals(PermissionTier.STANDARD, snap.tier)
        assertEquals(PermissionTier.ROOT, state.snapshot().tier)
    }

    private class FakeTool(
        private val name: String,
        tier: PermissionTier,
    ) : Tool {
        override val spec = ToolSpec(
            name = name,
            description = "fake",
            parametersJson = "{}",
            tier = tier,
        )

        override suspend fun execute(argumentsJson: String, context: ToolContext) =
            ToolResult("ok")
    }
}
