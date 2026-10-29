package dev.praxis.feature.settings.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.praxis.core.ai.AiModelOption
import dev.praxis.ui.widgets.MiuixAnimatedDialog
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 单值模型选择弹窗。
 *
 * ## 为什么不复用 [ModelPickerDialog]
 *
 * 那个是**多选**：确认返回 `List<String>`，用于「模型池」。
 * 而功能位（`FunctionModelOverride.model`）是**单值**，语义完全不同：
 *
 *  - 多选的点一颗勾选不关窗，用户要继续挑；
 *  - 单选的点一行就应当关窗并生效 —— 他心里的动作是「选它」，不是「标记它」。
 *
 * 把两者塞进一个组件，必然要一堆 `if (single) ... else ...` 分支，
 * 而且「点了不关」会让功能位用户以为没选上，再点一次又取消。
 *
 * ## 只要求「拉取过一次」就能用
 *
 * 空列表且未加载时显示提示，而不是一个空框 —— 用户点「选择」却看到空白，
 * 会以为坏了。提示会明确告诉他需要先在主模型页配好服务商、或直接手填。
 *
 * @param options 可选模型（来自 `ModelCatalogFetcher`）
 * @param selected 当前已选模型 id（用于打勾与预高亮）
 * @param loading 拉取中
 * @param error 拉取失败原因
 * @param onRefresh 重新拉取
 * @param onPick 选中某个模型（调用方负责关窗）
 * @param onDismiss 关闭
 * @param visible 是否显示（常驻挂载，只翻这个布尔 —— 退场动画需要组件还在组合里）
 */
@Composable
fun ModelSelectDialog(
    options: List<AiModelOption>,
    selected: String,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    visible: Boolean = true,
) {
    var search by remember { mutableStateOf("") }
    // 与多选弹窗同一个理由：常驻挂载后 remember 生命周期变成「整页」，
    // 必须在「从关到开」的边沿清一次，否则上次的搜索词会留到下次。
    // 而且不能用 `remember(visible)` —— 那样关的瞬间就清，退场动画期间
    // 列表会突然变空。
    LaunchedEffect(visible) {
        if (visible) search = ""
    }
    val filtered = remember(search, options) {
        if (search.isBlank()) {
            options
        } else {
            options.filter {
                it.id.contains(search, ignoreCase = true) ||
                    it.name.contains(search, ignoreCase = true)
            }
        }
    }
    MiuixAnimatedDialog(
        visible = visible,
        onDismissRequest = onDismiss,
    ) {
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "选择模型",
                            style = MiuixTheme.textStyles.body1,
                        )
                        Text(
                            // 说清「点一下就用这个」：单选弹窗没有确认按钮，
                            // 不解释清楚用户会找「确定」找不到。
                            text = "点击即选中该模型并关闭",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    IconButton(
                        onClick = onRefresh,
                        enabled = !loading,
                        modifier = Modifier.size(40.dp),
                    ) {
                        if (loading) {
                            CircularProgressIndicator(size = 18.dp, strokeWidth = 1.8.dp)
                        } else {
                            Text(
                                text = "刷新",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "搜索模型",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 320.dp),
                ) {
                    when {
                        loading && options.isEmpty() -> Box(
                            modifier = Modifier.fillMaxWidth().height(120.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(size = 24.dp)
                        }
                        error != null -> Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        ) {
                            Text(
                                text = "拉取失败",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = error,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(text = "重试", onClick = onRefresh)
                        }
                        filtered.isEmpty() -> Box(
                            modifier = Modifier.fillMaxWidth().height(120.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (search.isNotBlank()) {
                                    "没有匹配「\$search」的模型"
                                } else if (options.isEmpty()) {
                                    "还没有拉取到模型列表。先确认服务商地址与密钥已填好，" +
                                        "再点右上角「刷新」；也可以直接手填模型 ID。"
                                } else {
                                    "列表为空"
                                },
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(filtered, key = { it.id }) { option ->
                                SingleModelRow(
                                    option = option,
                                    selected = option.id == selected,
                                    onClick = { onPick(option.id) },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(text = "关闭", onClick = onDismiss)
                }
            }
        }
    }
}

/** 单选弹窗里的一行。与多选的 [ModelRow] 形态一致，但点击即选中。 */
@Composable
private fun SingleModelRow(
    option: AiModelOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = option.id,
                    style = MiuixTheme.textStyles.body2,
                    color = if (selected) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (option.name.isNotBlank() && option.name != option.id) {
                    Text(
                        text = option.name,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "使用中",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }
        HorizontalDivider(color = MiuixTheme.colorScheme.dividerLine)
    }
}
