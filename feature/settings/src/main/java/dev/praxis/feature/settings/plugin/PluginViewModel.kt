package dev.praxis.feature.settings.plugin

import androidx.lifecycle.ViewModel
import dev.praxis.core.plugin.ExtensionEntry
import dev.praxis.core.plugin.InstalledPlugin
import dev.praxis.core.plugin.MarketApi
import dev.praxis.core.plugin.MarketAsset
import dev.praxis.core.plugin.MarketEntrySummary
import dev.praxis.core.plugin.MarketSort
import dev.praxis.core.plugin.PluginOrigin
import dev.praxis.core.plugin.PluginRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 扩展中心的一屏状态。 */
data class PluginUiState(
    val installed: List<InstalledPlugin> = emptyList(),
    val market: List<MarketEntrySummary> = emptyList(),
    val installingId: String? = null,
    val loadingMarket: Boolean = false,
    val message: String? = null,
    /** 市场是否可用（关闭时页面只显示本地部分）。 */
    val marketEnabled: Boolean = true,
)

/**
 * 扩展中心 ViewModel。
 *
 * ## 为什么「已安装」与「市场」在同一个 VM 里
 *
 * 两件事共用一个关键状态：**这条市场条目是否已安装**。分两个 VM 的话，
 * 各自持有一半真相，装完之后列表页不会刷新，用户看到「已安装」按钮还是
 * 灰的。放一起意味着每次安装成功都能同时更新两边。
 *
 * ## 安装流程为什么放在 VM 而不是 Composable
 *
 * 它是「下载 → 落盘 → 解压 → 读清单 → 写状态」五步、可能几十秒的长事务。
 * 放在 Composable 里会因为重组/旋转屏被打断，而中断点正好在「已下载未落盘」
 * 这种最尴尬的位置。VM 的 `viewModelScope` 活到页面真的离开为止。
 *
 * ## 下载目录
 *
 * `packagesRoot/../downloads`：与包目录同级但不在其内，这样
 * `listInstalledIds()` 不会把下载中的 zip 当成已安装包。
 */
class PluginViewModel(
    private val repository: PluginRepository,
    private val market: MarketApi,
    private val scope: kotlinx.coroutines.CoroutineScope,
) : ViewModel() {

    private val _state = MutableStateFlow(PluginUiState(marketEnabled = market.isEnabled))
    val state: StateFlow<PluginUiState> = _state.asStateFlow()

    private var marketPage = 0

    fun start() {
        reloadInstalled()
        if (market.isEnabled) refreshMarket(reset = true)
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    /** 重新扫描已安装包。安装/卸载/切换后都要调。 */
    fun reloadInstalled() {
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { repository.scanAll() }.getOrDefault(emptyList())
            }
            _state.update { it.copy(installed = list) }
        }
    }

    fun setEnabled(packageId: String, enabled: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { repository.setEnabled(packageId, enabled) }
            }
            reloadInstalled()
        }
    }

    fun uninstall(packageId: String) {
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { repository.uninstall(packageId) }.getOrNull()
            }
            if (error != null) {
                _state.update { it.copy(message = error) }
            }
            reloadInstalled()
        }
    }

    /** 拉市场列表。`reset` 为 true 时从第一页重拉。 */
    fun refreshMarket(reset: Boolean = false, sort: MarketSort = MarketSort.Updated) {
        if (!market.isEnabled) return
        scope.launch {
            _state.update { it.copy(loadingMarket = true) }
            val nextPage = if (reset) 1 else marketPage + 1
            val result = market.listPage(sort = sort, page = nextPage)
            result.onSuccess { page ->
                marketPage = nextPage
                _state.update { current ->
                    current.copy(
                        market = if (reset) page.entries else current.market + page.entries,
                        loadingMarket = false,
                        message = if (page.entries.isEmpty() && reset) "市场暂时没有内容" else current.message,
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        loadingMarket = false,
                        message = "市场拉取失败：${error.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    /**
     * 从市场条目安装。
     *
     * ## 为什么按「条目」而不是「asset」暴露给 UI
     *
     * UI 只知道「用户点了这一条」，选哪个 asset 是策略问题（优先 .toolpkg）。
     * 让 UI 自己挑会把「优先 toolpkg」这个规则复制到每个调用点。
     */
    fun installFromMarket(entry: MarketEntrySummary, asset: MarketAsset?) {
        if (asset == null) {
            _state.update { it.copy(message = "这条没有可下载的文件") }
            return
        }
        scope.launch {
            _state.update { it.copy(installingId = entry.marketId, message = null) }
            // ## 下载目录从哪儿来
            //
            // `PluginRepository` 不暴露 `packagesRoot`（那是 `PluginStorage` 的
            // 属性）。VM 手里只有 repository，所以走「已装包列表里任取一个
            // 的 installPath 反推父目录」是不可靠的（一个包都没装时就没有）。
            //
            // 改成直接问系统临时目录：market 下载物是**一次性中间产物**，
            // 装完立刻删。放 cacheDir 还有个额外好处 —— 进程被杀留下的
            // 残包会被系统在磁盘紧张时清掉。
            val downloadDir = File(System.getProperty("java.io.tmpdir") ?: ".")
            downloadDir.mkdirs()
            val target = File(downloadDir, "praxis-${entry.marketId.replace('/', '_')}.toolpkg")

            val downloaded = market.download(asset, target.absolutePath)
            downloaded.onFailure { error ->
                _state.update {
                    it.copy(installingId = null, message = "下载失败：${error.message ?: "未知错误"}")
                }
                return@launch
            }

            // ## 不要再包一层 runCatching
            //
            // `PluginRepository.install` 的签名已经是 `Result<InstalledPlugin>`。
            // 外面再套一个 `runCatching { }` 得到的是 `Result<Result<InstalledPlugin>>`，
            // 于是 `getOrNull()` 返回的还是 Result，`title` 自然解析不到 ——
            // 这正是连续几轮 CI 都报「Unresolved reference 'title' on receiver of
            // type Result<InstalledPlugin>」的真正原因。
            //
            // 它自己用 runCatching 包好了异常，不需要我们再兜。
            val installed = withContext(Dispatchers.IO) {
                repository.install(
                    sourcePath = target.absolutePath,
                    origin = PluginOrigin.Market(
                        marketId = entry.marketId,
                        version = entry.version,
                        installedFrom = asset.downloadUrl,
                    ),
                )
            }
            // 装完删掉下载的 zip：留着重装时会被误当成「本地导入」源，
            // 而且一个包几十 MB，堆着很快就占满。
            target.delete()

            // 用 `getOrNull()` + 显式判空而不是 `onSuccess { }`：
            // 上面已把多余的 runCatching 去掉，这里的类型就是干净的
            // `Result<InstalledPlugin>`；显式判空读起来也比链式回调清楚。
            val installedPlugin = installed.getOrNull()
            if (installedPlugin != null) {
                _state.update { state ->
                    state.copy(installingId = null, message = "已安装：${installedPlugin.title}")
                }
                reloadInstalled()
            } else {
                val error = installed.exceptionOrNull()
                _state.update {
                    it.copy(installingId = null, message = "安装失败：${error?.message ?: "未知错误"}")
                }
            }
        }
    }

    /** 从本地文件安装（zip / 目录路径）。 */
    fun installFromFile(path: String) {
        scope.launch {
            _state.update { it.copy(installingId = path, message = null) }
            // 同上：install 已经返回 Result，不要再包 runCatching。
            val result = withContext(Dispatchers.IO) {
                repository.install(sourcePath = path)
            }
            val installedPlugin = result.getOrNull()
            if (installedPlugin != null) {
                _state.update { state ->
                    state.copy(installingId = null, message = "已安装：${installedPlugin.title}")
                }
                reloadInstalled()
            } else {
                val error = result.exceptionOrNull()
                _state.update {
                    it.copy(installingId = null, message = "安装失败：${error?.message ?: "未知错误"}")
                }
            }
        }
    }

    /** 已安装包的标题集合（用于市场行显示「已安装」）。 */
    fun installedTitles(): Set<String> = _state.value.installed.map { it.title }.toSet()

    /** 未使用但保留：让调用方拿到四类扩展的统一视图。 */
    fun installedEntries(): List<ExtensionEntry> = emptyList()
}
