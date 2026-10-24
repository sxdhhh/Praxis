package dev.praxis.feature.settings.character

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import dev.praxis.core.data.character.CharacterRepository

/**
 * 角色卡列表页的接线层。
 *
 * ## 为什么用 `remember` 建 ViewModel 而不是 `viewModel()`
 *
 * 这是 feature 层的既有约定（见 `MemoryRoute` 的 KDoc）：依赖一律从 `:app`
 * 注入，feature 模块不依赖 DI 框架。
 *
 * 更实际的理由：`CharacterViewModel` 需要一个**外部 Scope**（它不用
 * `viewModelScope`，为了能纯 JVM 单测）。`viewModel()` 工厂里拿不到
 * Compose 的 `rememberCoroutineScope()`，只能绕一圈拿 `ViewModelStoreOwner`，
 * 反而更复杂。这里 `remember` + `rememberCoroutineScope` 直白且正确：
 * 离开页面时 scope 自动取消，不会有「退出页面后还在写数据库」的协程泄漏。
 *
 * ## 为什么 `LaunchedEffect(Unit)` 而不是在 `remember` 块里 start
 *
 * `remember` 的 lambda 可能在重组时被丢弃（虽然罕见），在里面启动协程
 * 会出现「启动了但没有人取消」。`LaunchedEffect` 的生命周期是确定的：
 * 进入组合启动、离开组合取消，重复进入会重新 start（而 `start()` 自己幂等）。
 */
@Composable
fun CharactersRoute(
    repository: CharacterRepository,
    onEdit: (String) -> Unit,
    onOpenTags: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    // key 用 repository 的实例身份：DI 换实现时应当重建 ViewModel。
    val viewModel = remember(repository) { CharacterViewModel(repository, scope) }

    LaunchedEffect(viewModel) { viewModel.start() }

    val state by viewModel.listState.collectAsStateWithLifecycle()

    CharactersScreen(
        state = state,
        onSelect = viewModel::setActive,
        onEdit = onEdit,
        onCreate = {
            // 新建后直接进编辑页：用户点「新建」就是想填内容，
            // 停在列表页让他再点一次「编辑」是多余的一步。
            viewModel.createAndEdit(onCreated = onEdit)
        },
        onImportJson = { raw ->
            viewModel.importTavernJson(raw) { newId ->
                // 导入成功后同样直接进编辑页：让用户立刻核对导入结果
                // （标签是否正确挂上、字段有没有丢）。
                onEdit(newId)
            }
        },
        onOpenTags = onOpenTags,
        modifier = modifier,
    )
}