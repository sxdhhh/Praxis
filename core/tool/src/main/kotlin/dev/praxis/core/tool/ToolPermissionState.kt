package dev.praxis.core.tool

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 工具权限的**运行时可改**状态。
 *
 * 为什么需要它：[ToolContext.tier] 原本是个固定值，但用户要在设置页里随时提权/降权。
 * 如果每次改完都重建 ToolContext，正在执行中的调用会拿到新档位，
 * 同一批工具里前一个用旧权限、后一个用新权限 —— 行为不可预测。
 *
 * 所以用「状态持有者 + 快照」：调用方在**发起一批调用时**取一次当前档位，
 * 这一批全部按该档位判定；改档位只影响下一批。
 *
 * [disabledTools] 与档位是独立维度：档位是「能力上限」，禁用列表是「用户不想用的」。
 * 两者都要满足工具才可见 —— 高权限不等于用户想让它跑。
 */
class ToolPermissionState(
    initialTier: PermissionTier = PermissionTier.STANDARD,
    initialDisabled: Set<String> = emptySet(),
) {

    private val _tier = MutableStateFlow(initialTier)
    val tier: StateFlow<PermissionTier> = _tier.asStateFlow()

    private val _disabledTools = MutableStateFlow(initialDisabled)
    val disabledTools: StateFlow<Set<String>> = _disabledTools.asStateFlow()

    fun setTier(newTier: PermissionTier) {
        _tier.value = newTier
    }

    fun setToolEnabled(name: String, enabled: Boolean) {
        _disabledTools.update { current ->
            if (enabled) current - name else current + name
        }
    }

    fun isEnabled(name: String): Boolean = name !in _disabledTools.value

    /** 取一个快照（供一批调用使用）。 */
    fun snapshot(): ToolPermissionSnapshot = ToolPermissionSnapshot(
        tier = _tier.value,
        disabledTools = _disabledTools.value,
    )
}

/**
 * 权限快照：一批调用的固定依据。
 *
 * 同时实现 [ToolContext]，因为执行器只需要「当前档位」这一个事实。
 */
data class ToolPermissionSnapshot(
    override val tier: PermissionTier,
    val disabledTools: Set<String>,
) : ToolContext {

    fun isEnabled(name: String): Boolean = name !in disabledTools
}

/**
 * 按档位 + 禁用列表过滤可暴露的工具。
 *
 * 这是「发给模型什么」的唯一口径：档位不够的、用户禁用的，一律不出现。
 * 把不可用工具发给模型是负面设计 —— 它会反复尝试，白烧 token。
 */
fun ToolRegistry.descriptorsFor(snapshot: ToolPermissionSnapshot): List<ToolSpec> =
    descriptorsFor(snapshot.tier).filter { snapshot.isEnabled(it.name) }
