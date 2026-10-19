package dev.praxis.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.core.data.settings.SettingsRepository
import dev.praxis.domain.model.PraxisDomain
import dev.praxis.feature.settings.SettingsEntry
import dev.praxis.domain.model.blockOf
import dev.praxis.ui.design.component.AppScaffold
import dev.praxis.ui.design.component.LocalBottomBarInset
import dev.praxis.ui.features.FeatureHost
import dev.praxis.ui.navigation.PraxisBottomBar
import dev.praxis.ui.navigation.PraxisRoute
import dev.praxis.ui.navigation.PraxisRouteSaver

/** 浮层底栏占位高度：底栏本体 + 底部呼吸 + 手势条间隙。 */
private val BottomBarSlot = 96.dp

/**
 * Praxis 主壳。
 *
 * ## 三层契约（照抄 cn.apixiaoyuan.app，改一处就会破坏观感）
 *
 * 1. **底栏是浮层**：内容区不加底部 padding，内容从底栏下方穿过，玻璃才有东西可折射。
 *    但滚动**终点**要抬高（[LocalBottomBarInset] → `LocalScrollBottomLimit` →
 *    `LazyColumn.contentPadding.bottom`），否则最后一项藏在底栏后面看不见。
 * 2. **顶栏高度不进容器 padding**：交给滚动容器的 `contentPadding.top`
 *    （[AppScaffold] 内部通过 `LocalTopBarInset` 下发）。否则内容被永久裁在顶栏下方，
 *    `progressiveTextureBlur` 永远采不到内容，模糊看上去跟实色一样。
 * 3. **切主题不重建 UI 树**：主题在 [dev.praxis.ui.theme.PraxisTheme] 里已经
 *    用稳定的 controller 维持，这里不再重复包一层。
 *
 * ## 状态契约
 *
 * 导航状态收敛成一个 [PraxisRoute]，用 [PraxisRouteSaver] 存。
 * 不用「currentTabId + detailId」两条平行状态 —— 一旦分叉就会出现
 * 「底栏高亮在对话、内容却是设置子页」的自相矛盾界面。
 */
@Composable
fun PraxisApp(
    settingsRepository: SettingsRepository,
    versionName: String,
    versionCode: Int,
    modifier: Modifier = Modifier,
) {
    // provider 由 Koin 注入，model 暂时用固定值（模型选择页落地后改为读设置）。
    // provider 的 baseUrl/apiKey 必须来自设置，否则用户在设置页改了什么都不会生效。
    // 用一个工厂：每轮对话重建 provider，保证用的是最新配置。
    val settingsRepository: dev.praxis.core.data.settings.SettingsRepository = org.koin.compose.koinInject()
    val modelSettings by settingsRepository.modelSettings.collectAsState(
        initial = dev.praxis.core.model.settings.ModelSettings(),
    )
    val toolRegistry: dev.praxis.core.tool.ToolRegistry = org.koin.compose.koinInject()
    val toolExecutor: dev.praxis.core.tool.ToolExecutor = org.koin.compose.koinInject()
    val toolContext: dev.praxis.core.tool.ToolContext = org.koin.compose.koinInject()
    val permissionState: dev.praxis.core.tool.ToolPermissionState = org.koin.compose.koinInject()
    val chatPersistence: dev.praxis.feature.chat.ChatPersistence = org.koin.compose.koinInject()

    // 当前会话 id 必须跨进程重建保留：否则重启后回到旧会话，用户以为刚才的对话丢了。
    var sessionId by rememberSaveable { mutableStateOf("main") }
    val currentTier by permissionState.tier.collectAsState()
    val disabledTools by permissionState.disabledTools.collectAsState()
    // key = sessionId：换会话时必须拿到**新的** ViewModel，否则气泡与 history
    // 还是上一个会话的，表现为「切了会话内容没变」。
    val chatViewModel: dev.praxis.feature.chat.ChatViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel(key = sessionId) {
        dev.praxis.feature.chat.ChatViewModel(
            provider = dev.praxis.data.ai.SettingsBackedProvider(settingsRepository),
            model = DEFAULT_MODEL,
            toolRegistry = toolRegistry,
            toolExecutor = toolExecutor,
            toolContext = toolContext,
            permissionSnapshotProvider = { permissionState.snapshot() },
            chatRepository = chatPersistence,
            sessionId = sessionId,
        )
    }

    var route by rememberSaveable(stateSaver = PraxisRouteSaver) {
        mutableStateOf<PraxisRoute>(PraxisRoute.Home(PraxisDomain.CHAT))
    }

    val activeDomain = route.domain
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues()
        .calculateBottomPadding()

    // 子页状态下返回键只退一层，不直接退出应用。
    BackHandler(enabled = route is PraxisRoute.Detail) {
        route = PraxisRoute.Home(route.domain)
    }

    // route 是 `var` + 自定义 Saver 的类型，`when` 分支里读属性会丢失智能转换，
    // 所以显式转一次，比在每处写 (route as PraxisRoute.Detail) 干净。
    val detail = route as? PraxisRoute.Detail
    val title = detail?.let { d ->
        route.domain.blockOf(d.arg)?.title ?: d.title.ifBlank { d.arg }
    } ?: route.domain.title
    val onBack: (() -> Unit)? = when {
        detail != null -> ({ route = PraxisRoute.Home(route.domain) })
        // 主对话页：返回位当作「会话列表」入口 —— AppScaffold 只有这一个槽位，
        // 与其自己改骨架加 actions，不如复用它，且语义上是「回到上一层（会话列表）」。
        route.domain == PraxisDomain.CHAT -> ({
            route = PraxisRoute.Detail(
                domain = PraxisDomain.CHAT,
                arg = "list",
                title = "会话列表",
            )
        })
        else -> null
    }

    CompositionLocalProvider(LocalBottomBarInset provides BottomBarSlot) {
        AppScaffold(
            title = title,
            onBack = onBack,
            modifier = modifier.fillMaxSize(),
        ) { _ ->
            Box(Modifier.fillMaxSize()) {
                FeatureHost(
                    route = route,
                    settingsRepository = settingsRepository,
                    chatPersistence = chatPersistence,
                    onOpenSession = { newId ->
                        sessionId = newId
                        route = PraxisRoute.Home(PraxisDomain.CHAT)
                    },
                    toolSpecs = toolRegistry.all().map { it.spec },
                    currentTier = currentTier,
                    isToolEnabled = { name -> name !in disabledTools },
                    onTierChange = { permissionState.setTier(it) },
                    onToolToggle = { name, enabled -> permissionState.setToolEnabled(name, enabled) },
                    chatViewModel = chatViewModel,
                    versionName = versionName,
                    versionCode = versionCode,
                    onSettingsEntry = { entry ->
                        route = PraxisRoute.Detail(
                            domain = PraxisDomain.SETTINGS,
                            arg = entry.key,
                            title = entry.title,
                        )
                    },
                    onOpen = { block ->
                        route = PraxisRoute.Detail(
                            domain = route.domain,
                            arg = block.id,
                            title = block.title,
                        )
                    },
                    onBack = { route = PraxisRoute.Home(route.domain) },
                    modifier = Modifier.fillMaxSize(),
                )

                PraxisBottomBar(
                    selected = activeDomain,
                    onSelect = { domain ->
                        if (domain != activeDomain) {
                            route = PraxisRoute.Home(domain)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = navBarBottom),
                )
            }
        }
    }
}

/** 默认模型：设置页的模型配置落地前先固定一个常见值。 */
private const val DEFAULT_MODEL = "gpt-4o-mini"
