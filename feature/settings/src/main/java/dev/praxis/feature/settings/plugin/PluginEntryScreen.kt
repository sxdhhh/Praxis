package dev.praxis.feature.settings.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.core.plugin.ComposeDslParser
import dev.praxis.core.plugin.ComposeDslRenderResult
import dev.praxis.core.plugin.PluginScriptRuntime
import dev.praxis.feature.settings.common.SettingsList
import dev.praxis.feature.settings.home.SettingsUnfinished
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 打开一个插件入口。
 *
 * ## 三段式状态
 *
 * 加载中 / 成功 / 失败，各自有明确画面。失败时把**脚本侧的原话**显示出来 ——
 * 「渲染失败」四个字对排查毫无帮助，而脚本抛出的异常文本通常直接指出问题。
 *
 * ## 为什么失败可以重试
 *
 * 脚本第一次渲染可能依赖还没就绪的资源；重试是用户最自然的动作，
 * 也是唯一不需要重启应用的恢复手段。
 */
@Composable
fun PluginEntryScreen(
    pluginId: String,
    entryId: String,
    runtime: PluginScriptRuntime?,
    modifier: Modifier = Modifier,
    onAction: (String) -> Unit = {},
) {
    if (runtime == null) {
        SettingsUnfinished(
            title = "插件入口",
            reason = "插件运行时未装配（需要 WebView 运行时）。",
            modifier = modifier,
        )
        return
    }

    // 重试计数：变化时 LaunchedEffect 重新执行。
    var attempt by remember(pluginId, entryId) { mutableIntStateOf(0) }
    var state by remember(pluginId, entryId) {
        mutableStateOf<EntryViewState>(EntryViewState.Loading)
    }

    LaunchedEffect(pluginId, entryId, attempt) {
        state = EntryViewState.Loading
        state = runtime.render(pluginId, entryId)
            .mapCatching { raw -> ComposeDslParser.parse(raw).getOrThrow() }
            .fold(
                onSuccess = { EntryViewState.Ready(it) },
                onFailure = { EntryViewState.Failed(it.message ?: it.javaClass.simpleName) },
            )
    }

    when (val current = state) {
        EntryViewState.Loading -> SettingsList(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "__loading__") {
                Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        CircularProgressIndicator()
                        Text(
                            text = "正在加载插件页面…",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }

        is EntryViewState.Failed -> SettingsList(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "__failed__") {
                Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "插件页面渲染失败",
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.error,
                        )
                        Text(
                            text = current.message,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Button(
                            onClick = { attempt += 1 },
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            Text("重试")
                        }
                    }
                }
            }
        }

        is EntryViewState.Ready -> SettingsList(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "__tree__") {
                ComposeDslView(
                    node = current.result.tree,
                    modifier = Modifier.fillMaxWidth(),
                    onAction = onAction,
                )
            }
        }
    }
}

/** 入口页的三种状态。 */
private sealed interface EntryViewState {
    data object Loading : EntryViewState
    data class Ready(val result: ComposeDslRenderResult) : EntryViewState
    data class Failed(val message: String) : EntryViewState
}
