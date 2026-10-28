package dev.praxis.feature.settings.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import dev.praxis.core.plugin.MarketApi
import dev.praxis.core.plugin.PluginRepository

/**
 * 扩展中心的接线层。
 *
 * ## 为什么 VM 的 key 是 (repository, market)
 *
 * DI 换了实现（比如测试注入假市场）时必须重建 VM，否则它持有的是
 * 上一份依赖，用户的点击会打到旧对象上。这与项目里 `CharacterViewModel`
 * 用 `remember(repository)` 是同一条纪律。
 */
@Composable
fun PluginsRoute(
    repository: PluginRepository,
    market: MarketApi,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember(repository, market) {
        PluginViewModel(repository = repository, market = market, scope = scope)
    }
    LaunchedEffect(viewModel) { viewModel.start() }
    val state by viewModel.state.collectAsState()

    PluginsScreen(
        state = state,
        installedTitles = remember(state.installed) {
            state.installed.map { it.title }.toSet()
        },
        onToggle = viewModel::setEnabled,
        onUninstall = viewModel::uninstall,
        onInstallMarket = { entry -> viewModel.installFromMarket(entry, entry.primaryAsset) },
        onRefreshMarket = { viewModel.refreshMarket(reset = true) },
        onLoadMore = { viewModel.refreshMarket(reset = false) },
        modifier = modifier,
    )
}
