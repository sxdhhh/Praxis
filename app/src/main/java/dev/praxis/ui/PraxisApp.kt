package dev.praxis.ui

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
import dev.praxis.domain.model.blockOf
import dev.praxis.feature.settings.SettingsEntry
import dev.praxis.ui.design.component.AppScaffold
import dev.praxis.ui.design.component.LocalBottomBarInset
import dev.praxis.ui.features.FeatureHost
import dev.praxis.ui.navigation.PraxisBottomBar
import dev.praxis.ui.navigation.PraxisNavHost
import dev.praxis.ui.navigation.PraxisRoute
import dev.praxis.ui.navigation.PraxisRoutes
import dev.praxis.ui.navigation.currentRoute
import dev.praxis.ui.navigation.ensureSeeded
import dev.praxis.ui.navigation.pushRoute
import dev.praxis.ui.navigation.switchDomain
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack

/** 浮层底栏占位高度：底栏本体 + 底部呼吸 + 手势条间隙。 */
private val BottomBarSlot = 96.dp

/**
 * Praxis 主壳。
 *
 * ## 三层契约（照抄 cn.apixiaoyuan.app，改一处就会破坏观感）
 *
 * 1. **底栏是浮层**：内容区不加底部 padding，内容从底栏下方穿过，玻璃才有东西可折射。
 *    但滚动**终点**要抬高（[LocalBottomBarInset] → `LocalScrollBottomLimit` →
 *    `contentPadding.bottom`），否则最后一项藏在底栏后面看不见。
 * 2. **顶栏高度不进容器 padding**：交给滚动容器的 `contentPadding.top`
 *    （[AppScaffold] 内部通过 `LocalTopBarInset` 下发）。否则内容被永久裁在顶栏下方，
 *    `progressiveTextureBlur` 永远采不到内容，模糊看上去跟实色一样。
 * 3. **切主题不重建 UI 树**：主题在 [dev.praxis.ui.theme.PraxisTheme] 里已经
 *    用稳定的 controller 维持，这里不再重复包一层。
 *
 * ## 导航契约（本轮改动）
 *
 * 导航状态是真栈（[rememberNavBackStack]），不再是「一个 route 变量 + 手写 Saver」。
 * 带来的三个变化：
 *  - 栈可以任意深（设置子页里再进一层不会无处可去）；
 *  - 转场由 miuix-nav 驱动，含预测性返回（左滑到一半松手能回弹）；
 *  - 底栏切 Tab 用 [switchDomain]（收敛到该域首页），不是压新栈。
 */
@Composable
fun PraxisApp(
    settingsRepository: SettingsRepository,
    versionName: String,
    versionCode: Int,
    modifier: Modifier = Modifier,
) {
    val modelSettings by settingsRepository.modelSettings.collectAsState(
        initial = dev.praxis.core.model.settings.ModelSettings(),
    )
    val toolRegistry: dev.praxis.core.tool.ToolRegistry = org.koin.compose.koinInject()
    val toolExecutor: dev.praxis.core.tool.ToolExecutor = org.koin.compose.koinInject()
    val toolContext: dev.praxis.core.tool.ToolContext = org.koin.compose.koinInject()
    val permissionState: dev.praxis.core.tool.ToolPermissionState = org.koin.compose.koinInject()
    val chatPersistence: dev.praxis.feature.chat.ChatPersistence = org.koin.compose.koinInject()
    val memoryPort: dev.praxis.feature.chat.MemoryPort = org.koin.compose.koinInject()
    val memoryStore: dev.praxis.core.memory.MemoryStore = org.koin.compose.koinInject()
    val workflowEngine: dev.praxis.core.workflow.WorkflowEngine = org.koin.compose.koinInject()
    val skillRegistry: dev.praxis.core.plugin.SkillRegistry = org.koin.compose.koinInject()

    // 技能列表在启动时扫一次（之后由设置页的「重新扫描」触发）。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        skillRegistry.reload()
    }

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
                memoryPort = memoryPort,
                // 每轮现拼：用户在设置页刚关掉的技能，下一句就不该再生效。
                skillsPromptProvider = {
                    skillRegistry.composePrompt(skillRegistry.all().map { it.name }.toSet())
                },
            )
        }

    // 真栈。注意泛型要显式写 <PraxisRoute>：
    // rememberNavBackStack(PraxisRoutes.home(...)) 会把 T 推导成 Home 的具体类型，
    // 整个 sealed 层次进化不出来，存栈时直接抛 SerializationException。
    val backStack = rememberNavBackStack<PraxisRoute>(PraxisRoutes.home(PraxisDomain.CHAT))

    // 恢复兜底：seed 只在「首次创建」时生效，进程被回收后由 Saver 解出的栈
    // 若为空（序列化格式变更、栈内容不可反序列化而被跳过），`NavDisplay` 内部的
    // `require(backStack.isNotEmpty())` 会直接抛异常 —— 表现为启动白屏崩溃。
    // 这里在渲染前补一层，与 NavDisplay 之间不留空隙。
    // 幂等（isEmpty 才写），重组期反复执行不产生额外状态变更。
    backStack.ensureSeeded()

    val route: PraxisRoute = backStack.currentRoute() ?: PraxisRoutes.home(PraxisDomain.CHAT)
    val activeDomain = route.domain

    val navBarBottom = WindowInsets.navigationBars.asPaddingValues()
        .calculateBottomPadding()

    // 顶栏标题：子页优先用域内清单里的真实标题，路由自带的只是兜底。
    val detail = route as? PraxisRoute.Detail
    val title = detail?.let { d ->
        route.domain.blockOf(d.arg)?.title ?: d.title.ifBlank { d.arg }
    } ?: route.domain.title

    // 返回位：栈深 > 1 时退一层；否则对话页把它当「会话列表」入口
    // （AppScaffold 只有这一个槽位，且语义上确实是「回上一层」）。
    val onBack: (() -> Unit)? = when {
        backStack.size > 1 -> ({ backStack.removeAt(backStack.lastIndex) })
        route.domain == PraxisDomain.CHAT -> ({
            pushRoute(backStack, PraxisRoutes.detail(PraxisDomain.CHAT, "list", "会话列表"))
        })
        else -> null
    }

    // 圆角片段跟随设备屏幕角，避免滑入的页面在屏幕边角处露出直角。
    val effects = androidx.compose.runtime.remember {
        NavDisplayEffects(enableCornerClip = true)
    }

    CompositionLocalProvider(LocalBottomBarInset provides BottomBarSlot) {
        AppScaffold(
            title = title,
            onBack = onBack,
            modifier = modifier.fillMaxSize(),
            // 底栏走独立槽位，不塞进 content。
            //
            // content 在 AppScaffold 的 layerBackdrop 录制范围内，把底栏放进去
            // 会让它被录进采样源 —— 玻璃采到自己，边缘出现「玻璃折射玻璃」的重影。
            bottomBar = {
                PraxisBottomBar(
                    selected = activeDomain,
                    onSelect = { domain ->
                        if (domain != activeDomain) {
                            switchDomain(backStack, domain.id)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = navBarBottom),
                )
            },
        ) { _ ->
            Box(Modifier.fillMaxSize()) {
                PraxisNavHost(
                    backStack = backStack,
                    effects = effects,
                    modifier = Modifier.fillMaxSize(),
                ) { entry ->
                    FeatureHost(
                        route = entry.route,
                        settingsRepository = settingsRepository,
                        chatPersistence = chatPersistence,
                        memoryStore = memoryStore,
                        workflowEngine = workflowEngine,
                        onOpenSession = { newId ->
                            sessionId = newId
                            // 回对话首页（把会话列表弹掉），而不是压新栈。
                            switchDomain(backStack, PraxisDomain.CHAT.id)
                        },
                        toolSpecs = toolRegistry.all().map { it.spec },
                        currentTier = currentTier,
                        isToolEnabled = { name -> name !in disabledTools },
                        onTierChange = { permissionState.setTier(it) },
                        onToolToggle = { name, enabled -> permissionState.setToolEnabled(name, enabled) },
                        chatViewModel = chatViewModel,
                        versionName = versionName,
                        versionCode = versionCode,
                        onSettingsEntry = { entryItem ->
                            entry.push(
                                PraxisRoutes.detail(
                                    domain = PraxisDomain.SETTINGS,
                                    arg = entryItem.key,
                                    title = entryItem.title,
                                ),
                            )
                        },
                        onOpen = { block ->
                            entry.push(
                                PraxisRoutes.detail(
                                    domain = route.domain,
                                    arg = block.id,
                                    title = block.title,
                                ),
                            )
                        },
                        onBack = { entry.pop() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }


            }
        }
    }
}

/** 默认模型：设置页的模型配置落地前先固定一个常见值。 */
private const val DEFAULT_MODEL = "gpt-4o-mini"
