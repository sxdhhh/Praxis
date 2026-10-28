package dev.praxis.feature.settings.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.praxis.core.plugin.InstalledPlugin
import dev.praxis.core.plugin.MarketEntrySummary
import dev.praxis.feature.settings.common.SettingsList
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 扩展中心。
 *
 * ## 三段结构：已安装 → 市场 → 说明
 *
 * 已安装放最上面：用户进这个页最常见的目的就是「我装的什么、开没开」。
 * 市场在下面，是「还想装点什么」的第二诉求。
 *
 * ## 这里不显示插件注册了多少工具
 *
 * 工具是**运行时**注册的（`PluginRuntimeHost.load` 之后才知道），而这一页
 * 只做文件与开关管理。显示一个静态的「0 个工具」比不显示更误导 ——
 * 用户会以为包装坏了。等真正接上运行时加载再补。
 */
@Composable
fun PluginsScreen(
    state: PluginUiState,
    installedTitles: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onUninstall: (String) -> Unit,
    onInstallMarket: (MarketEntrySummary) -> Unit,
    onRefreshMarket: () -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsList(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "__title_installed__") {
            SmallTitle("已安装（${state.installed.size}）")
        }

        if (state.installed.isEmpty()) {
            item(key = "__empty_installed__") {
                Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        text = "还没有安装任何扩展包。可以从下面的市场装一个，" +
                            "也可以把 .toolpkg 文件放到应用私有目录后导入。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        items(state.installed) { plugin ->
            InstalledRow(
                plugin = plugin,
                onToggle = { enabled -> onToggle(plugin.id, enabled) },
                onUninstall = { onUninstall(plugin.id) },
            )
        }

        item(key = "__title_market__") {
            Spacer(Modifier.height(8.dp))
            SmallTitle("市场")
        }

        if (!state.marketEnabled) {
            item(key = "__market_off__") {
                Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        text = "市场已关闭。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        } else {
            item(key = "__market_actions__") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        text = if (state.loadingMarket) "拉取中…" else "刷新",
                        enabled = !state.loadingMarket,
                        onClick = onRefreshMarket,
                    )
                }
            }

            if (state.market.isEmpty() && !state.loadingMarket) {
                item(key = "__market_empty__") {
                    Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            text = "市场没有返回内容。可能是网络不可达，" +
                                "或这个接口暂时没有数据。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }

            items(state.market) { entry ->
                MarketRow(
                    entry = entry,
                    installed = entry.title in installedTitles,
                    installing = state.installingId == entry.marketId,
                    onInstall = { onInstallMarket(entry) },
                )
            }

            if (state.market.isNotEmpty()) {
                item(key = "__market_more__") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        TextButton(
                            text = if (state.loadingMarket) "加载中…" else "加载更多",
                            enabled = !state.loadingMarket,
                            onClick = onLoadMore,
                        )
                    }
                }
            }
        }

        item(key = "__tail__") { Spacer(Modifier.height(24.dp)) }
    }
}

/** 一个已安装的包。 */
@Composable
private fun InstalledRow(
    plugin: InstalledPlugin,
    onToggle: (Boolean) -> Unit,
    onUninstall: () -> Unit,
) {
    Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = plugin.title,
                        style = MiuixTheme.textStyles.body1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildList {
                            add(plugin.origin.describe())
                            val version = plugin.manifest.version
                            if (version.isNotBlank()) add("v$version")
                        }.joinToString(" · "),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Switch(checked = plugin.enabled, onCheckedChange = onToggle)
            }

            val failure = plugin.failureText
            if (failure != null) {
                Spacer(Modifier.height(4.dp))
                // 坏的包必须显示原因：用户唯一的动作是「删掉它」，
                // 不知道原因就会一直以为是应用的问题。
                Text(
                    text = "无法加载：$failure",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(text = "卸载", onClick = onUninstall)
            }
        }
    }
}

/** 一条市场条目。 */
@Composable
private fun MarketRow(
    entry: MarketEntrySummary,
    installed: Boolean,
    installing: Boolean,
    onInstall: () -> Unit,
) {
    Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = entry.title,
                style = MiuixTheme.textStyles.body1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = entry.description,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = buildList {
                        if (entry.version.isNotBlank()) add("v${entry.version}")
                        add(entry.kindHint.label)
                        if (entry.authors.isNotEmpty()) add(entry.authors.first())
                    }.joinToString(" · "),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                TextButton(
                    text = when {
                        installing -> "安装中…"
                        installed -> "已安装"
                        else -> "安装"
                    },
                    // 「已安装」仍然可点：重装是合法需求（包坏了想覆盖）。
                    enabled = !installing,
                    onClick = onInstall,
                )
            }
        }
    }
}
