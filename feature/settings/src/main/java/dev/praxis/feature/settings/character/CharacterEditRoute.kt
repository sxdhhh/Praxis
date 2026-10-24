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
 * 角色卡编辑页的接线层。
 *
 * ## `cardId == null` 表示新建
 *
 * 此时 [CharacterViewModel.loadForEdit] 给一张空卡，**保存时才落库**。
 * 不在进页面时就建一条空记录：用户点「新建」又立刻返回，会在库里
 * 留下一张「未命名角色」，列表里越积越多。
 *
 * ## 保存/删除后都返回列表
 *
 * 编辑页是列表页的下一级，操作完成即回上级 —— 这是导航层级的自然语义。
 * 停在编辑页让用户看着「保存成功」再手动返回，多一步且没有信息量。
 */
@Composable
fun CharacterEditRoute(
    repository: CharacterRepository,
    cardId: String?,
    onDone: () -> Unit,
    /**
     * 导出的落地动作。
     *
     * ## 为什么由调用方传入而不是在这里写文件
     *
     * `feature:settings` 不持有存储目录（那是 `:app` 的 `filesDir` /
     * `Download`），也不该申请存储权限。所以这里只把 JSON 文本交出去，
     * 由 `:app` 决定写到哪里、要不要弹分享。
     *
     * 默认空实现 + 界面隐藏导出按钮：没接线的页面不该显示一个点了没反应的按钮
     * （那正是「假按钮」缺陷的成因）。
     */
    onExportJson: ((fileName: String, json: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember(repository) { CharacterViewModel(repository, scope) }

    // key 含 cardId：从「编辑 A」直接切到「编辑 B」（理论上不该发生，
    // 但深链接可以）必须重新载入，否则界面上还是 A 的内容。
    LaunchedEffect(viewModel, cardId) { viewModel.loadForEdit(cardId) }

    val state by viewModel.editState.collectAsStateWithLifecycle()

    CharacterEditScreen(
        state = state,
        onUpdate = viewModel::updateCard,
        onToggleTag = viewModel::toggleTag,
        onSave = { viewModel.save(onSaved = onDone) },
        onDelete = { viewModel.deleteEditing(onDeleted = onDone) },
        onExport = {
            // 导出：取仓储生成的酒馆 JSON，交给调用方落地。
            // 命名用角色名（清理掉文件名非法字符），便于用户在文件管理器里认出来。
            viewModel.exportEditing { json ->
                val safeName = state.card.name
                    .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
                    .trim()
                    .ifEmpty { "character" }
                onExportJson?.invoke("$safeName.json", json)
            }
        },
        exportEnabled = onExportJson != null && !state.isNew,
        modifier = modifier,
    )

}
