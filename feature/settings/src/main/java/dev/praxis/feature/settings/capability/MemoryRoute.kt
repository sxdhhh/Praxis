package dev.praxis.feature.settings.capability

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.praxis.core.memory.MemoryItem
import dev.praxis.core.memory.MemoryStore
import kotlinx.coroutines.launch

/**
 * 记忆页的接线层。
 *
 * ## 为什么 store 从上层传入而不是自己 koinInject
 *
 * 这是 feature 层的既有约定：[ModelRoute] 收 `SettingsRepository`、
 * [SettingsHost] 收它要的一切，依赖一律从 `:app` 注入。
 *
 * 好处有两个：
 *  - feature 模块不依赖 DI 框架，单测与 @Preview 可以直接传假实现；
 *  - 依赖关系在 `:app` 一处可见，改 DI 不用满仓库找 koinInject。
 *
 * ## 为什么删除后整表重读
 *
 * 记忆写入可能在另一条协程里发生（对话中自动提取）。
 * 本地 list.remove 会让 UI 与库产生偏差，而重读只有几毫秒。
 */
@Composable
fun MemoryRoute(
    store: MemoryStore,
    modifier: Modifier = Modifier,
    /** 当前活动空间（与对话页共用同一个值）。 */
    activeSpaceId: String = MemoryItem.DEFAULT_SPACE_ID,
    /** 切换空间；null = 不提供切换（预览/单测）。 */
    onSelectSpace: ((String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<MemoryItem>>(emptyList()) }
    var spaceCounts by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var reloadTick by remember { mutableStateOf(0) }

    // runCatching：首次启动时数据库迁移失败也不该整页崩掉，显示空比白屏好。
    LaunchedEffect(reloadTick, activeSpaceId) {
        items = runCatching { store.all(activeSpaceId) }.getOrDefault(emptyList())
        // 空间列表取全局（跨空间）：用户要能切到一个尚未打开的空间。
        spaceCounts = runCatching {
            store.all(null)
                .groupingBy { it.spaceId }
                .eachCount()
                .map { (id, n) -> id to n }
                .sortedBy { it.first }
        }.getOrDefault(emptyList())
    }

    MemoryScreen(
        items = items,
        activeSpaceId = activeSpaceId,
        spaceCounts = spaceCounts,
        onSelectSpace = onSelectSpace,
        onDelete = { id ->
            scope.launch {
                runCatching { store.delete(id) }
                reloadTick++
            }
        },
        modifier = modifier,
    )
}
