package dev.praxis.feature.settings.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.praxis.core.plugin.InstalledEntryPoint
import dev.praxis.core.plugin.PluginEntryPoint
import dev.praxis.core.plugin.PluginRepository
import dev.praxis.core.plugin.collectEntryPoints
import dev.praxis.feature.settings.home.SettingsUnfinished
import dev.praxis.ui.navigation.LocalNavigator
import dev.praxis.ui.navigation.PraxisRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 兼容插件入口的接线层。
 *
 * ## 一次扫描，不做监听
 *
 * 入口来自已装包的清单文件，只有「装包 / 卸包 / 启用」会改变它 ——
 * 而这三件事都在扩展包页发生。挂一个文件监听器只为这一个页面服务
 * 不划算，回到本页重新扫描即可（scanAll 是几十毫秒级的目录读取）。
 *
 * ## repository 为什么可空
 *
 * 与扩展包页同一套判断：它是 :app 注入的（依赖真实包目录）。
 * 没装配时明确告诉用户原因，而不是给一个空列表让他以为「没有插件」。
 */
@Composable
fun CompatPluginEntryRoute(
    repository: PluginRepository?,
    /**
     * 运行时捕获到的入口（包 id → 入口）。
     *
     * 目前恒为空：Praxis 还没有 PluginScriptRuntime 的 WebView 实现，
     * 所以包跑不起来，只有清单声明这一条来源。等运行时接上，
     * 这里传 host.capturedEntries() 即可 —— 合并规则已经在
     * collectEntryPoints 里定死了（运行时整体优先）。
     */
    capturedEntries: Map<String, List<PluginEntryPoint>> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    if (repository == null) {
        SettingsUnfinished(
            title = "兼容插件入口",
            reason = "扩展系统未装配仓储（需要包目录）。",
            modifier = modifier,
        )
        return
    }

    var points by remember(repository) { mutableStateOf(emptyList<InstalledEntryPoint>()) }

    LaunchedEffect(repository) {
        points = withContext(Dispatchers.IO) {
            // 读包目录失败不该让页面崩：显示空列表 + 用户可以去扩展包页看详情。
            runCatching { repository.scanAll() }.getOrDefault(emptyList())
        }.collectEntryPoints(capturedEntries)
    }

    val navigator = LocalNavigator.current

    CompatPluginEntryScreen(
        points = points,
        onOpen = { point ->
            navigator.push(
                PraxisRoute.PluginEntry(
                    pluginId = point.pluginId,
                    entryId = point.point.id,
                    title = point.title,
                ),
            )
        },
        modifier = modifier,
    )
}
