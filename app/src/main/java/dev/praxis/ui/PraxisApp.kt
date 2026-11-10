package dev.praxis.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.praxis.core.data.settings.SettingsRepository
import dev.praxis.core.plugin.collectEntryPoints
import dev.praxis.feature.settings.capability.MemoryRoute
import dev.praxis.feature.settings.home.SettingsHomeScreen
import dev.praxis.feature.terminal.TerminalScreen
import dev.praxis.feature.terminal.TerminalViewModel
import dev.praxis.ui.navigation.Navigator
import dev.praxis.ui.navigation.PraxisNavDisplay
import dev.praxis.ui.navigation.PraxisRoute
import dev.praxis.ui.navigation.ensureSeeded
import dev.praxis.ui.navigation.transition.navTransitionFor
import dev.praxis.ui.shell.ChatSessionsPage
import dev.praxis.ui.shell.MainShellPage
import dev.praxis.ui.tab.ChatTab
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Praxis 主壳（组装层）。
 *
 * ## 三层结构（2026-10-22 导航重构）
 *
 * ```
 * PraxisApp            栈 / 转场 / 依赖注入 / 目的地分派      ← 本文件
 *   PraxisNavDisplay   每个 Route 一个 entry + swipeDismiss    ← ui:navigation
 *     MainShellPage    底栏 + Pager + 自己的 Scaffold          ← ui:shell
 *     各二级页          自己的 PraxisPage + PopupHost           ← feature:*
 * ```
 *
 * ## 重构针对的三个真故障
 *
 * | 用户报的 | 旧结构里的真因 |
 * |---|---|
 * | 导航逻辑极乱、二级页极易篡页 | 路由只有 `Home(domainId)` / `Detail(domainId,arg,title)`，页靠字符串区分；`SettingsEntry.byKey` 查不到就渲染**兜底页**（本身又是一份设置首页）→ 点「高级」没反应，并挡住 Linux/提示词等功能 |
 * | tab 切页特别多 bug | Tab 是栈元素，切 Tab = 改栈顶。为让动画出现改成真 push/pop，于是要封顶栈深、两次改底栏判据、给返回键加例外、方向手算四种组合 |
 * | 控件点击闪退 / 弹窗不弹 | 全应用一份 Scaffold，页面是它的 content 子组合；miuix popup 需要**本组合内**的宿主，页面没有自己的宿主，弹窗类控件会开新 Window 抢焦点 → 崩 |
 *
 * 重构后的结构保证：
 *  - 16 个具名 Route，每个都有 entry（缺一个编不过）→ 篡页不可能；
 *  - Tab 归 Pager，切 Tab 不产生栈深变化 → 动画由 Pager 天然提供；
 *  - 每页自带 Scaffold → 背景不透明、弹窗在页内、底栏只存在于 `Main`。
 *
 * ## 依赖注入为什么集中在这里
 *
 * 这里是唯一知道「哪个页面需要什么」的地方。页面自身通过 `LocalNavigator`
 * 导航，不再需要跨四层传回调 —— 第 12 项为加一个「终端设置」入口改了四个
 * 签名，就是旧结构的代价。
 */
@Composable
fun PraxisApp(
    settingsRepository: SettingsRepository,
    versionName: String,
    versionCode: Int,
    modifier: Modifier = Modifier,
) {
    // ── 设置流 ────────────────────────────────────────────────
    val modelSettings by settingsRepository.modelSettings.collectAsState(
        initial = dev.praxis.core.model.settings.ModelSettings(),
    )
    // 终端设置：终端页读字号、设置页读写同一份。
    // 只在这一层采一次，避免两处各拿一份快照（滑杆改了终端要重启才变）。
    val terminalSettings by settingsRepository.terminalSettings.collectAsState(
        initial = dev.praxis.core.model.settings.TerminalSettings(),
    )
    // 虚拟键盘键帽标签：终端页（渲染键盘）与设置页（编辑）读同一份。
    // 与 terminalSettings 同理，只在这一层采一次 —— 两处各拿一份快照
    // 会出现「设置页改了、终端页还是旧的」。
    val keyboardLabels by settingsRepository.virtualKeyboardLabels.collectAsState(
        initial = dev.praxis.core.model.linux.VirtualKeyboardLayoutCodec.DEFAULT_LABELS,
    )
    // ## 主题为什么**不在这里再采一次**（2026-10-23 修，启动二次闪的最后一个来源）
    //
    // 旧写法是：
    //
    // ```kotlin
    // val themeSettings by settingsRepository.themeSettings.collectAsState(
    //     initial = ThemeSettings(),      // ← 默认设置
    // )
    // ```
    //
    // 而 `MainActivity` 为了让 splash 等到真实主题，已经把同一个 Flow
    // 采了一遍并传给了 `PraxisTheme`。这里是**第二次独立收集**，带来两个后果：
    //
    //  1. **再读一次盘**：DataStore 的 Flow 每次收集都会重新读文件。
    //     冷启动最该省的就是这一步 —— 首帧路径上多一次 IO。
    //  2. **初值又是默认值**：`collectAsState(initial = ...)` 在第一次发射
    //     到达前一律返回 `initial`。于是当 `MainActivity` 已经拿到真实设置、
    //     用正确主题建好树之后，本层还会先用「默认设置」渲染一遍 ——
    //     本文件用它算的是 `predictiveBackAnimation` / `ExitDirection`，
    //     表现为**启动后导航转场观感先闪成另一档**再跳回来。
    //
    // 改读 `LocalThemeSettings`：它由 `PraxisTheme` 从 `MainActivity` 那份
    // 唯一快照下发（`PraxisTheme.kt:99`），既是同一次读盘的结果，
    // 也不会出现「两份状态分叉」。用户在外观页改设置时，`MainActivity`
    // 的 Flow 会重新发射 → `PraxisTheme` 重新 provide → 这里自动跟上。
    val themeSettings = dev.praxis.ui.theme.LocalThemeSettings.current

    // ── 依赖 ─────────────────────────────────────────────────
    val toolRegistry: dev.praxis.core.tool.ToolRegistry = org.koin.compose.koinInject()

    val toolExecutor: dev.praxis.core.tool.ToolExecutor = org.koin.compose.koinInject()
    val toolContext: dev.praxis.core.tool.ToolContext = org.koin.compose.koinInject()
    val permissionState: dev.praxis.core.tool.ToolPermissionState = org.koin.compose.koinInject()
    val chatPersistence: dev.praxis.feature.chat.ChatPersistence = org.koin.compose.koinInject()
    // `ChatPersistence` 只暴露「按会话读消息 / 写消息」，**没有**列会话列表
    // （那是 `ChatRepository` 的 `observeSessions()`）。
    // 恢复默认会话需要「最近更新的是哪个」，所以这里额外取一次仓储。
    val chatRepository: dev.praxis.data.ChatRepository = org.koin.compose.koinInject()
    val memoryPort: dev.praxis.feature.chat.MemoryPort = org.koin.compose.koinInject()

    // 当前记忆空间：唯一真源在 SettingsRepository（持久化）。
    //
    // 为什么放在这里而不是 SettingsHost 内部：只有这一层同时握着
    // repository（写盘）与 memoryPort（运行态），两处各存一份会出现
    // 「对话用 A、设置页显示 B」。
    val activeMemorySpaceId by settingsRepository.activeMemorySpace.collectAsState(
        initial = "",
    )

    // 用户显式创建过的空间名。与 activeMemorySpaceId 同源（都在 SettingsRepository），
    // 这样「记忆页列出的空间」和「对话页实际查询的空间」不会分家。
    val memorySpaces by settingsRepository.memorySpaces.collectAsState(initial = emptyList())

    // 环境页配置的存储：发行版选择 + FTP 非敏感项。
    //
    // ## 为什么必须在 Composable 体内、而不能放在上面的属性区
    //
    // `LaunchedEffect` 是 @Composable 函数，只能在 Composable 上下文里调。
    // 放在属性初始化区编译不过（「@Composable invocations can only happen
    // from the context of a @Composable function」）。
    //
    // 注入到 `ProotEnvPort` 而不是构造注入：port 由 Koin 以 single 构造，
    // 改构造签名会影响所有调用点（含测试里的直接 new）。

    // 启动与切空间后，把持久化的空间同步给端口。
    //
    // 不做这一步的后果：重启后记忆页显示「工作」（读的是磁盘），
    // 但 recall 仍用构造时的空串（默认空间）—— 页面上有 12 条，模型却一条也想不起来。
    LaunchedEffect(activeMemorySpaceId) {
        memoryPort.setActiveSpace(activeMemorySpaceId)
    }

    // 角色系统。取的是仓储本身：`activePrompt()` 每轮现取，
    // 这样用户在设置页切卡/改人设后，下一句消息立刻生效。
    val characterRepository: dev.praxis.core.data.character.CharacterRepository =
        org.koin.compose.koinInject()

    // 待发队列存档。进程被回收后把排队内容读回来 ——
    // 队列是「生成中先记下想法」用的，丢了且无提示是最伤的体验。
    val queueStore: dev.praxis.feature.chat.PendingQueueStore =
        org.koin.compose.koinInject()

    // 扩展包：仓库（文件读写）与市场（网络）。两者都由 AppModule 装配。
    // 分开取而不是打成一个对象：设置页只需要仓库，市场是可选能力，
    // 混在一起会让「没有网络权限的构建」也被迫构造一个 HTTP 客户端。
    val pluginRepository: dev.praxis.core.plugin.PluginRepository =
        org.koin.compose.koinInject()
    val pluginMarket: dev.praxis.core.plugin.MarketApi =
        org.koin.compose.koinInject()

    /**
     * 插件脚本运行时（WebView 实现）。
     *
     * 在这一层取、往下传，与上面几个同样的口径：feature 模块不认识 Koin。
     */
    val pluginRuntime: dev.praxis.core.plugin.PluginScriptRuntime =
        org.koin.compose.koinInject()

    /** 运行时宿主：按包记账工具与入口，卸载时据此释放。 */
    val pluginHost: dev.praxis.core.plugin.PluginRuntimeHost =
        org.koin.compose.koinInject()

    /** 启动装载编排。 */
    val pluginActivation: dev.praxis.core.plugin.PluginActivation =
        org.koin.compose.koinInject()

    // 启动装载：把已启用的包装进运行时。
    //
    // 失败不影响启动（一个包坏了不该让应用打不开），报告留给入口页展示。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        pluginActivation.activateAll()
    }

    // 播种模型配置：确保「默认配置 + 网关配置」存在（幂等，只播一次）。
    //
    // 放在启动时而不是模型页里：网关配置要在用户第一次打开模型页之前就存在。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        settingsRepository.ensureProfiles()
    }

    // 装载完成后重新取一次捕获到的入口。
    //
    // 静态快照会让列表停在启动那一刻：装载是异步的，页面可能先组合出来。
    // 用 activationReport 作为 remember 的 key，装完就重算。
    val activationReport by pluginActivation.report.collectAsState()
    val capturedEntries = remember(activationReport) { pluginHost.capturedEntries() }
    val activationFailures = remember(activationReport) { activationReport?.failed.orEmpty() }

    // 挂到侧栏的插件入口（surface = main_sidebar_plugins）。
    //
    // 与「兼容插件入口」页同一份来源与合并规则（运行时整体优先）。
    // 扫描要读目录，放 IO；capturedEntries 变化（装完包）会重跑。
    var sidebarPluginEntries by remember(capturedEntries) {
        mutableStateOf(emptyList<dev.praxis.core.plugin.InstalledEntryPoint>())
    }
    androidx.compose.runtime.LaunchedEffect(capturedEntries) {
        sidebarPluginEntries = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { pluginRepository.scanAll() }.getOrDefault(emptyList())
        }.collectEntryPoints(capturedEntries)
            .filter { point ->
                point.enabled &&
                    point.point.surface == dev.praxis.core.plugin.EntrySurfaces.MAIN_SIDEBAR_PLUGINS
            }
    }
    val memoryStore: dev.praxis.core.memory.MemoryStore = org.koin.compose.koinInject()
    val workflowEngine: dev.praxis.core.workflow.WorkflowEngine = org.koin.compose.koinInject()
    val skillRegistry: dev.praxis.core.plugin.SkillRegistry = org.koin.compose.koinInject()

    // ── 网关账号池（文档 §10 的接线层）──
    //
    // 这里只读账号行给设置页显示，不做导入、不转发请求（那是 P1 的剩余部分）。
    val gatewayAccountStore: dev.praxis.core.data.gateway.GatewayAccountStore =
        org.koin.compose.koinInject()

    /**
     * 选路的运行期状态。
     *
     * 目前恒为空：真正转发请求之后才会有人往里写退避/熔断/限额
     * （GatewayProvider 还没做）。所以账号页现在只会显示「正常」或「已停用」，
     * 这与事实一致，不是写死的。
     */
    //
    // **从 Koin 取，而不是在这里 remember**：远端生图的账号池要用同一个实例，
    // 否则两边的冷却/熔断各记各的 —— 那「Images 走账号池」就白接了。
    // 熔断四参由设置页在运行期通过 updateBreakerConfig 推入（见上面那段 LaunchedEffect）。
    val gatewayRouteState: dev.praxis.core.gateway.RouteState =
        org.koin.compose.koinInject()

    // ── 导入（文档 9.7 的「从剪贴板」入口）──
    //
    // 分两步：先预检（只解析、不写库），把四段报告给用户看；确认之后才写。
    // 顺序不能反 —— 写完了再报，用户就没得选了。
    // 模型规则（映射/禁用）。存的是原文，这里解码；解不开要能被看见，不能当没配过。
    val gatewayModelRulesJson by settingsRepository.gatewayModelRulesJson.collectAsState(
        initial = null,
    )
    // 网关总开关（文档 §11 末尾的回滚闸）。默认 true；判定收在 core:gateway 的 GatewayGate。
    val gatewayEnabled by settingsRepository.gatewayEnabled.collectAsState(initial = true)

    val gatewayModelRulesParse = remember(gatewayModelRulesJson) {
        gatewayModelRulesJson?.let {
            dev.praxis.core.gateway.GatewayModelRulesCodec.parse(it)
        }
    }
    val gatewayModelRules = gatewayModelRulesParse
        ?.getOrElse { dev.praxis.core.gateway.GatewayModelRules() }
        ?: dev.praxis.core.gateway.GatewayModelRules()

    var gatewayImportBusy by remember { mutableStateOf(false) }
    var gatewayImportReport by remember {
        mutableStateOf<dev.praxis.core.gateway.GatewayImportReport?>(null)
    }
    var gatewayImportPlan by remember {
        mutableStateOf<dev.praxis.core.gateway.GatewayImportPlan?>(null)
    }
    var gatewayImportResult by remember { mutableStateOf<String?>(null) }
    var gatewayImportError by remember { mutableStateOf<String?>(null) }
    // 导出：两条粒度（仅配置 / 含凭证）。结果里带路径与「未包含项」，出错单独一条。
    var gatewayExportBusy by remember { mutableStateOf(false) }
    var gatewayExportText by remember { mutableStateOf<String?>(null) }
    var gatewayExportError by remember { mutableStateOf<String?>(null) }

    var gatewayAccountRows by remember {
        mutableStateOf<List<dev.praxis.core.gateway.GatewayAccountRow>>(emptyList())
    }
    // 批量清空运行状态之后要重算一遍行，否则界面上的灯不会变。
    var gatewayRowsRefresh by remember { mutableStateOf(0) }
    LaunchedEffect(gatewayRowsRefresh) {
        gatewayAccountRows = gatewayAccountStore.accountRows(
            state = gatewayRouteState,
            // 家名的翻译表要等 P1 的家清单；先用 id 原样显示，不编一个假名字。
            providerLabelOf = { id -> id },
            // 「该账号承载哪些上游模型」要等模型条目表接上数据源；
            // 传空表示还不知道，状态推导据此不判定限额（不是「判定为没限」）。
            wireModelsOf = { emptyList() },
        )
    }

    // 网关对外模型清单：各家声明的模型 + 映射别名（去重、大小写不敏感）。
    var gatewayModelOptions by remember {
        mutableStateOf<List<dev.praxis.core.ai.AiModelOption>>(emptyList())
    }
    // 结构化模型条目（gateway_model 表）。它是模型页候选的**第二来源**：
    // 带启停与能力位，代表「用户明确管过」的那批。
    val gatewayModelStore: dev.praxis.data.gateway.GatewayModelStore =
        org.koin.compose.koinInject()

    // 模型管理页的行（结构化条目）。写入后靠 gatewayRowsRefresh 重读，
    // 与账号池/候选队列同一个刷新信号 —— 三处显示的是同一份真相。
    var gatewayModelRows by remember {
        mutableStateOf<List<dev.praxis.core.gateway.GatewayModelRow>>(emptyList())
    }
    var gatewayModelError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(gatewayRowsRefresh) {
        gatewayModelRows = gatewayModelStore.all().map { entity ->
            dev.praxis.core.gateway.GatewayModelRow(
                provider = entity.provider,
                id = entity.id,
                name = entity.name,
                kind = entity.kind,
                enabled = entity.enabled,
            )
        }
    }
    LaunchedEffect(gatewayRowsRefresh, gatewayModelRulesJson) {
        val fromCatalog = dev.praxis.core.gateway.GatewayModelCatalog
            .visibleNames(
                nativeModels = gatewayAccountStore.nativeModels(),
                mappings = gatewayModelRules.toRoutingMappings(),
            )
        // 两个来源都真实存在，合并而不是二选一：刚导入账号、还没逐条管过模型的用户
        // 也必须看到候选（见 GatewayModelOptions 的 KDoc）。
        val merged = dev.praxis.core.gateway.GatewayModelOptions.merge(
            structured = gatewayModelStore.visibleModelIds(),
            catalog = fromCatalog,
        )
        gatewayModelOptions = merged.map { dev.praxis.core.ai.AiModelOption(id = it, name = it) }
    }

    // 选路策略（文档 §9.4）：顺序/轮询 + 粘性。解不开时按默认走，但把「坏了」传下去，
    // 由路由页明说 —— 静默用默认值会让用户以为自己的设置凭空丢了。
    val gatewayRoutingPolicyJson by settingsRepository.gatewayRoutingPolicyJson
        .collectAsState(initial = null)
    val gatewayRoutingPolicyDecoded = remember(gatewayRoutingPolicyJson) {
        dev.praxis.core.gateway.GatewayRoutingPolicyCodec.decode(gatewayRoutingPolicyJson)
    }
    val gatewayRoutingPolicy = gatewayRoutingPolicyDecoded?.getOrNull()
        ?: dev.praxis.core.gateway.GatewayRoutingPolicy()
    val gatewayRoutingPolicyBroken = gatewayRoutingPolicyDecoded?.isFailure == true

    // 熔断四参交给 RouteState。用 update 而不是重建 RouteState：重建会连带清空
    // 当前的冷却/熔断/退避运行态 —— 那是用户改参数时没要求的副作用。
    LaunchedEffect(gatewayRoutingPolicy) {
        gatewayRouteState.updateBreakerConfig(gatewayRoutingPolicy.toBreakerConfig())
    }

    // 网关系统设置（文档 §5.18）：超时 / 换号上限 / debug 原文。
    //
    // 三项都**真的接上了消费者**：超时→OkHttp、换号上限→FailoverBudget、
    // debug→provider 的日志。没接上的项不放进页面（假开关比没有更糟）。
    val gatewaySystemJson by settingsRepository.gatewaySystemSettingsJson
        .collectAsState(initial = null)
    val gatewaySystem = remember(gatewaySystemJson) {
        dev.praxis.core.gateway.GatewaySystemSettingsCodec
            .decode(gatewaySystemJson)
            ?.getOrNull()
            ?: dev.praxis.core.gateway.GatewaySystemSettings()
    }
    // 超时改了要重建客户端（OkHttp 的超时是客户端属性，不能逐请求改）。
    val gatewayHttpClient = remember(
        gatewaySystem.connectTimeoutSeconds,
        gatewaySystem.readTimeoutSeconds,
    ) {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(
                gatewaySystem.connectTimeoutSeconds.toLong(),
                java.util.concurrent.TimeUnit.SECONDS,
            )
            .readTimeout(
                gatewaySystem.readTimeoutSeconds.toLong(),
                java.util.concurrent.TimeUnit.SECONDS,
            )
            .build()
    }

    // 代理池（文档 §5.9）：每账号绑定出口。
    //
    // 节点一变就重建解析器（`remember` 的键里有 proxySettings），
    // 于是 OkHttp 客户端缓存也跟着失效 —— 用户改完地址不需要重启。
    val gatewayProxyJson by settingsRepository.gatewayProxyJson.collectAsState(initial = null)
    val gatewayProxy = remember(gatewayProxyJson) {
        dev.praxis.core.gateway.ProxySettingsCodec.decode(gatewayProxyJson)
            ?.getOrNull()
            ?: dev.praxis.core.gateway.ProxySettings()
    }
    val proxyResolver = remember(gatewayHttpClient, gatewayProxy) {
        dev.praxis.data.gateway.ProxyResolver(
            settings = { gatewayProxy },
            baseClient = { gatewayHttpClient },
        )
    }


    // 路由组（文档 §5.5）：对外名 → 跨家成员。
    val gatewayRouteGroupsJson by settingsRepository.gatewayRouteGroupsJson
        .collectAsState(initial = null)
    val gatewayRouteGroups = remember(gatewayRouteGroupsJson) {
        dev.praxis.core.gateway.GatewayRouteGroupCodec
            .decode(gatewayRouteGroupsJson)
            ?.getOrNull()
            .orEmpty()
    }

    // 候选队列：按当前模型算一遍选路结果（不发请求，纯计算）。
    var gatewayQueueEntries by remember {
        mutableStateOf<List<dev.praxis.core.gateway.GatewayQueueEntry>>(emptyList())
    }
    LaunchedEffect(gatewayRowsRefresh, gatewayModelRulesJson, modelSettings.activeModel) {
        val candidates = dev.praxis.core.gateway.CandidateChain.of(
            requestedModel = modelSettings.activeModel,
            nativeModels = gatewayAccountStore.nativeModels(),
            mappings = gatewayModelRules.toRoutingMappings(),
            defaultProvider = null,
            // 路由组（文档 §5.5）：队列预测也要按组算，否则加了组这里看不到变化。
            groups = gatewayRouteGroups,
        ).candidates
        val plan = dev.praxis.core.gateway.RoutePlanner.plan(
            candidates = candidates,
            accounts = gatewayAccountStore.routingViews(),
            state = gatewayRouteState,
            needsImages = false,
            stickyAccountId = null,
            // 队列是**预测**，用当前策略算 —— 否则用户切到轮询后看到的次序仍是顺序的。
            policy = gatewayRoutingPolicy,
        )
        gatewayQueueEntries = dev.praxis.core.gateway.GatewayQueue.of(plan)
    }

    /**
     * 终端会话快照端口。
     *
     * 放在这里而不是在 `TerminalViewModel` 里自己 `get()`：VM 在
     * `feature:terminal` 模块，不该依赖 Koin（它的单测直接 `TerminalViewModel(...)`）。
     * 由 `:app` 注入是既有纪律，与 `chatPersistence` 完全一致。
     */
    val terminalSnapshotPort: dev.praxis.feature.terminal.TerminalSnapshotPort =
        org.koin.compose.koinInject()
    /**
     * 真终端端口。
     *
     * 由 `:app` 的 `AppModule` 注册（`ProotPtySessionAdapter` 包着与 AI 工具
     * 共用的那个 `ProotTerminalSessionPort`）。取到 null 时终端页仍是管道
     * 模式 —— 与改动前行为一致，不会因为容器没装好就崩。
     *
     * 注意用**非空**类型：`AppModule` 里无条件注册了这个 single（适配器本身
     * 不持有资源，开会话失败只会让 `open` 返回 null）。若写成可空类型，
     * Koin 找不到定义时会在组合期抛异常，而这里根本没有「不注册」的路径。
     */
    val terminalPtyPort: dev.praxis.feature.terminal.PtySessionPort =
        org.koin.compose.koinInject()

    /** AI 建的终端会话名登记表（持久化，跨进程有效）。 */
    val terminalSessionRegistry: dev.praxis.data.terminal.TerminalSessionRegistry =
        org.koin.compose.koinInject()

    // 系统授权检测：需 Activity 上下文（查权限 / 跳系统设置页），只在这一层取。
    val capabilityDetector: dev.praxis.core.data.permission.CapabilityDetector =
        org.koin.compose.koinInject()
    val capabilityRequester: dev.praxis.core.data.permission.CapabilityRequester =
        org.koin.compose.koinInject()

    /**
     * 模型列表拉取端口。
     *
     * 与上面几个一样在这一层取（而不是在 `SettingsHost` 里 koinInject）：
     * feature 模块不依赖 DI 框架，接线集中在 `:app` —— 这是本项目既定的分工，
     * feature 页的单测因此可以只传一个假实现。
     */
    val modelCatalogFetcher: dev.praxis.core.ai.ModelCatalogFetcher =
        org.koin.compose.koinInject()

    /**
     * 内存监控单例。
     *
     * ## 为什么在这一层取而不是在 ChatTab 里 koinInject
     *
     * 本项目的分工是「接线集中在 :app，feature 只收端口」。`ChatTab` 收的是
     * 一个 `Flow<MemoryPressure>?`，取单例这件事在 :app 做一次，
     * feature 侧的单测仍然只需要一个假 Flow。
     *
     * ## 为什么必须是同一个实例（`single`）
     *
     * `PraxisApplication.onTrimMemory` 也会往这个实例里写 trim level。
     * 若这里 `get()` 出新实例，系统明确的回收请求会写进一个没人订阅的对象，
     * 表现为「系统在要求释放，App 毫无反应」。DI 里已经声明成 `single`。
     */
    val memoryMonitor: dev.praxis.data.memory.MemoryMonitor =
        org.koin.compose.koinInject()

    /**
     * Linux 环境端口（proot 容器 / SSH / FRP）。
     *
     * ## 为什么在 Composable 顶层 remember 一次
     *
     * `ProotEnvPort` 持有正在跑的会话表；每次重组 new 一个会丢掉 sshd / frpc
     * 的句柄，变成无法停止的孤儿进程。
     *
     * ## 为什么不在参数位置 remember
     *
     * 那样 `LocalContext.current` 会落在 `remember` 的 calculation lambda 里，
     * 而它不是 Composable 上下文 —— 编译期直接报错（本轮修）。
     */
    val localContext = androidx.compose.ui.platform.LocalContext.current
    val linuxEnvPort = androidx.compose.runtime.remember(localContext) {
        dev.praxis.data.linux.ProotEnvPort(localContext)
    }

    // 环境页配置的存储：发行版选择 + FTP 非敏感项。
    //
    // 注入在**已有**的 `linuxEnvPort` 上（不新建实例）：环境页与 AI 工具共用
    // 同一个 port，各建一份会出现「设置页改了端口、AI 侧还是旧值」。
    LaunchedEffect(linuxEnvPort) {
        linuxEnvPort.envSettingsStore = settingsRepository
    }

    val capabilities by capabilityDetector.snapshot.collectAsStateWithLifecycle()

    val coroutineScope = rememberCoroutineScope()

    /**
     * 本地 Context。
     *
     * ## 为什么在顶部取一次而不是在回调里读 `LocalContext.current`
     *
     * `LocalContext.current` 只能在**组合期**读。`onExportCharacterJson`
     * 是在用户点按钮后才执行的 lambda，那时组合已经结束 ——
     * 在 lambda 里读会抛 `IllegalStateException: CompositionLocal not available`。
     *
     * 结论：组合期取一次、闭包捕获。`Context` 是 Activity/Application 的引用，
     * 捕获它不会造成泄漏（这个 Composable 本身生命周期不长于 Activity）。
     */
    val appContext = androidx.compose.ui.platform.LocalContext.current

    /**
     * 剪贴板。在组合期取一次并记住。
     *
     * 与上面 appContext 同一条纪律：不在回调里读 LocalContext.current，
     * 那个时刻已经不在组合期了。
     */
    val clipboardManager = remember(appContext) {
        appContext.getSystemService(android.content.ClipboardManager::class.java)
    }

    // ── 本地生图（设置 → 本地生图）──
    //
    // 模型目录与「已安装清单」在组合期算一次：它们是文件系统上的事实，不该在 Compose 里
    // 反复做 IO。下载完成后靠 imageGenRefreshTick 重算一次，装好的模型立刻出现。
    val imageGenModelsRoot = remember { java.io.File(appContext.filesDir, "models") }
    val imageGenSocModel = remember {
        // Build.SOC_MODEL 是 API 31 才有的；低版本用 HARDWARE（同样是芯片型号字符串）。
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            android.os.Build.SOC_MODEL
        } else {
            android.os.Build.HARDWARE
        }
    }
    val imageGenDownloadManager: dev.praxis.data.imagegen.ImageModelDownloadManager =
        org.koin.compose.koinInject()
    val imageGenDownloadState by imageGenDownloadManager.state.collectAsState()

    // 下载完成后重读一次文件系统：装好的模型立刻出现在列表里，不用重启 App（文档 §7.3）。
    var imageGenRefreshTick by remember { mutableStateOf(0) }
    /**
     * 设备门控（文档 §9）：能不能跑本地生图。
     *
     * ABI 与芯片型号只有 :app 读得到，所以判定在这里做一次，往下传结果。
     */
    val imageGenAvailability = remember(imageGenSocModel) {
        dev.praxis.core.imagegen.ImageGenAvailabilityRules.decide(
            abis = android.os.Build.SUPPORTED_ABIS.toList(),
            socModel = imageGenSocModel,
        )
    }

    val imageGenCatalog = remember(imageGenSocModel) {
        dev.praxis.core.imagegen.ImageModelCatalog.builtin(imageGenSocModel)
    }
    val imageGenInstalledIds = remember(imageGenCatalog, imageGenRefreshTick) {
        imageGenCatalog
            .filter { model ->
                dev.praxis.core.imagegen.ImageModelCatalog.isInstalled(imageGenModelsRoot, model)
            }
            .map { model -> model.id }
            .toSet()
    }
    val imageGenInstalledModels = remember(imageGenCatalog, imageGenInstalledIds) {
        imageGenCatalog.filter { model -> model.id in imageGenInstalledIds }
    }
    // 生图目标（文档 §7.3）：解不开时保留原文，这里只把「坏内容」与「没配过」区分开 ——
    // 与 ImageGenTargetCodec 的约定一致（别把坏内容当成没配过）。
    val imageGenTargetJson by settingsRepository.imageGenTargetJson.collectAsState(initial = null)
    val imageGenTargetDecoded = remember(imageGenTargetJson) {
        dev.praxis.core.imagegen.ImageGenTargetCodec.decode(imageGenTargetJson)
    }
    val imageGenCurrentTarget = imageGenTargetDecoded?.getOrNull()

    LaunchedEffect(imageGenDownloadState) {
        val downloadState = imageGenDownloadState
        if (downloadState is dev.praxis.core.imagegen.ImageDownloadState.Completed) {
            imageGenRefreshTick += 1
            // 装完自动选中（文档 §7.3 的三条规则；规则本身在 core:imagegen 有单测）。
            val decoded = dev.praxis.core.imagegen.ImageGenTargetCodec
                .decode(settingsRepository.imageGenTargetJson.first())
            // 配置坏了（decoded 是 failure）时**不动它** —— 覆盖掉等于把用户的原文丢了。
            if (decoded == null || decoded.isSuccess) {
                val current = decoded?.getOrNull()
                val chosen = dev.praxis.core.imagegen.ImageGenTargetSelection.afterInstall(
                    current = current,
                    newModelId = downloadState.modelId,
                    localModelExists = { id ->
                        val model = imageGenCatalog.firstOrNull { it.id == id }
                        model != null &&
                            dev.praxis.core.imagegen.ImageModelCatalog
                                .isInstalled(imageGenModelsRoot, model)
                    },
                )
                if (chosen != current) {
                    settingsRepository.setImageGenTargetJson(
                        dev.praxis.core.imagegen.ImageGenTargetCodec.encode(chosen),
                    )
                }
            }
        }
    }

    // 与 generate_image 工具共用同一个探活实例（Koin 单例）。
    // 各建一份会出现「设置页说已连接、工具说不可用」这种自相矛盾的观感。
    // 下载设置（下载源 + 仅 Wi-Fi）。配置坏了时这里退回默认值，但**下载闸门会挡住**
    // 并给出「内容坏了，请重设」的原因（见 AppModule 里的 downloadGuard）。
    val imageDownloadSettingsJson by settingsRepository.imageDownloadSettingsJson
        .collectAsState(initial = null)
    val imageDownloadSettings = remember(imageDownloadSettingsJson) {
        dev.praxis.core.imagegen.ImageDownloadSettingsCodec
            .decode(imageDownloadSettingsJson)
            ?.getOrNull()
            ?: dev.praxis.core.imagegen.ImageDownloadSettings()
    }

    // 生图默认参数（画布 / 采样）：工具在模型没说这些参数时按它走。
    val imageGenDefaultsJson by settingsRepository.imageGenDefaultsJson
        .collectAsState(initial = null)
    val imageGenDefaults = remember(imageGenDefaultsJson) {
        dev.praxis.core.imagegen.ImageGenDefaultsCodec
            .decode(imageGenDefaultsJson)
            ?.getOrNull()
            ?: dev.praxis.core.imagegen.ImageGenDefaults()
    }


    // 请求记录写入层：GatewayProvider 每轮结束（成功或失败）落一条。
    val gatewayUsageStore: dev.praxis.data.gateway.GatewayUsageStore =
        org.koin.compose.koinInject()

    // 整库备份（只导出）：结果文案留在界面上，直到下一次点击。
    val backupExporter: dev.praxis.data.backup.BackupExporter = org.koin.compose.koinInject()
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var backupRequest by remember { mutableStateOf(0) }
    LaunchedEffect(backupRequest) {
        if (backupRequest > 0) {
            backupMessage = when (val result = backupExporter.export()) {
                is dev.praxis.data.backup.BackupResult.Done ->
                    "已备份 " + result.manifest.fileName + "（" +
                        (result.manifest.sizeBytes / 1024) + " KB）+ manifest.json"
                is dev.praxis.data.backup.BackupResult.Failed -> "备份失败：" + result.detail
            }
        }
    }

    // 请求记录的保留期（文档 §5.7）：**启动时清一次**。
    //
    // 为什么不在每次写入后清：那样每条请求都多一次 DELETE，而清理不是时效性的事 ——
    // 早几分钟晚几分钟删掉旧记录，没有区别。
    //
    // 为什么必须清：`gateway_request` 每请求一行，不清就是无限增长 ——
    // 一个常用一年的人会有几十万行，报表聚合会变慢，而手机存储比服务器紧。
    LaunchedEffect(Unit) {
        val days = dev.praxis.core.gateway.GatewayRetention.parseDays(
            settingsRepository.gatewayRequestRetentionDays.first(),
        )
        val cutoff = dev.praxis.core.gateway.GatewayRetention.cutoffMillis(
            nowMillis = System.currentTimeMillis(),
            days = days,
            zone = java.time.ZoneId.systemDefault(),
        )
        gatewayUsageStore.purgeBefore(cutoff)
    }


    // 路由回放（文档 §5.5）：进程内最近若干条决策。
    val routeReplayLog: dev.praxis.core.gateway.RouteReplayLog =
        org.koin.compose.koinInject()
    // 每次进路由页时重读一次（它是内存里的列表，没有 Flow 可观察）。
    var routeReplayTick by remember { mutableStateOf(0) }
    val routeReplayEntries = remember(routeReplayTick, gatewayRowsRefresh) {
        routeReplayLog.recent()
    }

    // ── 自定义提供商（文档 §5.3）──
    //
    // 三件事共用一份状态：页面列表、地址解析（providerBaseUrl）、以及添加/删除后的刷新。
    var gatewayProviderRows by remember {
        mutableStateOf<List<dev.praxis.feature.gateway.GatewayProviderRowUi>>(emptyList())
    }

    // 模型测试用实例（文档 §5.4.1 第 16 条）。
    //
    // ## 为什么另建一个实例而不是复用聊天页那个
    //
    // 聊天页与标题生成那两处构造点的参数并**不相同**（一处带用户规则与策略，
    // 一处只带默认规则）。为了做测试去动那两处，会顺带改变生产请求的行为 ——
    // 那是另一个改动。这里用同一套接线（同一个 RouteState、同一份策略与出口解析）
    // 建独立实例：测试走的是**同一条链路**，只是不碰生产实例的参数。
    val gatewayProbeProvider = remember(
        gatewayModelRulesJson,
        gatewayRoutingPolicy,
        gatewaySystem,
        proxyResolver,
        gatewayHttpClient,
        gatewayRouteGroups,
        gatewayRowsRefresh,
    ) {
        dev.praxis.data.gateway.GatewayProvider(
            store = gatewayAccountStore,
            modelRules = { gatewayModelRules },
            policy = { gatewayRoutingPolicy },
            state = gatewayRouteState,
            recordUsage = { draft -> gatewayUsageStore.record(draft) },
            providerBaseUrl = { id ->
                dev.praxis.data.gateway.BuiltinProviders.baseUrl(id)
                    ?: gatewayProviderRows.firstOrNull { it.id == id }?.baseUrl
            },
            accountSpend = { since -> gatewayUsageStore.tokensByAccountSince(since) },
            replayLog = routeReplayLog,
            groups = { gatewayRouteGroups },
            budget = dev.praxis.core.gateway.FailoverBudget(
                maxSwitches = gatewaySystem.maxSwitches,
            ),
            debugRawTraffic = gatewaySystem.debugRawTraffic,
            clientFor = { accountId ->
                proxyResolver.clientFor(accountId) ?: gatewayHttpClient
            },
            client = gatewayHttpClient,
        )
    }
    var gatewayProviderAddError by remember { mutableStateOf<String?>(null) }
    var gatewayProviderTick by remember { mutableStateOf(0) }
    LaunchedEffect(gatewayRowsRefresh, gatewayProviderTick) {
        gatewayProviderRows = gatewayAccountStore.providers().map { entity ->
            dev.praxis.feature.gateway.GatewayProviderRowUi(
                id = entity.id,
                name = entity.name,
                baseUrl = entity.baseUrl,
                modelCount = entity.modelsJson
                    .split("\n")
                    .count { line -> line.trim().isNotEmpty() },
                // 快照时间：界面显示「上次刷新：3 小时前」（文档 §5.4.1 第 17 条）。
                modelsFetchedAt = entity.modelsFetchedAt,
            )
        }
    }

    // ── 添加账号三级流程 + 生图的「服务商模型」清单（都吃模型池）──
    //
    // 服务商模型 = 各家在 `modelsJson` 里声明的模型名 × 那家的地址。
    // nativeModels() 是挂起函数，所以这里用 state + LaunchedEffect（不能在 remember 里调）。
    var imageGenRemoteModels by remember {
        mutableStateOf<List<dev.praxis.feature.settings.ImageGenRemoteModel>>(emptyList())
    }
    LaunchedEffect(gatewayProviderRows, gatewayRowsRefresh) {
        val baseUrls = buildMap {
            dev.praxis.data.gateway.BuiltinProviders.ids().forEach { id ->
                dev.praxis.data.gateway.BuiltinProviders.baseUrl(id)?.let { url ->
                    put(id, url)
                }
            }
            gatewayProviderRows.forEach { row -> put(row.id, row.baseUrl) }
        }
        imageGenRemoteModels = gatewayAccountStore.nativeModels().mapNotNull { native ->
            baseUrls[native.provider]?.let { url ->
                dev.praxis.feature.settings.ImageGenRemoteModel(
                    provider = native.provider,
                    model = native.id,
                    baseUrl = url,
                )
            }
        }

    }

    // ① 提供商列表（内置家 + 自定义家）。
    val gatewayProviderChoices = remember(gatewayProviderRows) {
        dev.praxis.data.gateway.BuiltinProviders.ids().map { id ->
            dev.praxis.feature.gateway.GatewayProviderChoice(
                id = id,
                // 显示名与 id 分开：列表里要能一眼认出是哪家，
                // 而 id 是写进账号、参与选路的那个标识。
                name = dev.praxis.data.gateway.BuiltinProviders.label(id),
                baseUrl = dev.praxis.data.gateway.BuiltinProviders.baseUrl(id),
            )
        } + gatewayProviderRows.map { row ->
            dev.praxis.feature.gateway.GatewayProviderChoice(
                id = row.id,
                name = row.name,
                baseUrl = row.baseUrl,
            )
        }
    }
    var gatewayAccountSaveError by remember { mutableStateOf<String?>(null) }

    // 日志页的「请求记录」视图（文档 §5.8）：按需加载，不跟着每轮刷新。
    //
    // 放在 gatewayUsageStore 声明**之后** —— 这个文件里已经栽过两次
    // 「局部变量先用后声明」的编译错。
    var gatewayRequestRecords by remember {
        mutableStateOf<List<dev.praxis.core.gateway.GatewayRequestRecord>>(emptyList())
    }
    var gatewayRequestRecordsLoad by remember { mutableStateOf(0) }
    LaunchedEffect(gatewayRequestRecordsLoad) {
        if (gatewayRequestRecordsLoad > 0) {
            gatewayRequestRecords = gatewayUsageStore.recentRequests()
        }
    }

    // ── 报表（文档 §9.6）──
    //
    // 三段窗口各自查一次库。天数与「起点怎么算」都在 core 的 GatewayReport 里定死
    // （近 7 天 = 6 天前的 00:00，本地时区）。
    var gatewayReportWindows by remember {
        mutableStateOf<List<dev.praxis.feature.gateway.GatewayReportWindowUi>>(emptyList())
    }
    var gatewayReportTopModels by remember {
        mutableStateOf<List<dev.praxis.core.gateway.GatewayUsageBucket>>(emptyList())
    }
    var gatewayReportRefresh by remember { mutableStateOf(0) }
    // 请求记录保留天数（启动清理与报表页共用同一个值）。
    val gatewayRetentionDaysRaw by settingsRepository.gatewayRequestRetentionDays
        .collectAsState(initial = null)
    val gatewayRetentionDays = remember(gatewayRetentionDaysRaw) {
        dev.praxis.core.gateway.GatewayRetention.parseDays(gatewayRetentionDaysRaw)
    }
    LaunchedEffect(gatewayReportRefresh, gatewayRowsRefresh) {
        val now = System.currentTimeMillis()
        val zone = java.time.ZoneId.systemDefault()
        val windows = listOf(
            "今日" to 1L,
            "近 7 天" to 7L,
            "近 30 天" to 30L,
        ).map { (label, days) ->
            dev.praxis.feature.gateway.GatewayReportWindowUi(
                label = label,
                summary = gatewayUsageStore.summarySince(now, days, zone),
            )
        }
        gatewayReportWindows = windows
        // 排行取最长窗口：单看今天太容易被一次调试带偏。
        gatewayReportTopModels = windows.lastOrNull()?.summary?.byModel.orEmpty()
    }


    // 生图输出设置（自动存相册）。工具落盘后会读它决定要不要写相册。
    val imageOutputSettingsJson by settingsRepository.imageOutputSettingsJson
        .collectAsState(initial = null)
    val imageGenOutput = remember(imageOutputSettingsJson) {
        dev.praxis.core.imagegen.ImageOutputSettingsCodec
            .decode(imageOutputSettingsJson)
            ?.getOrNull()
            ?: dev.praxis.core.imagegen.ImageOutputSettings()
    }

    val imageGenEngineLocator: dev.praxis.data.imagegen.EngineLocator =
        org.koin.compose.koinInject()
    // 与工具共用同一条进度总线（工具写、聊天层读）。
    val imageGenProgressBus: dev.praxis.core.imagegen.ImageGenProgressBus =
        org.koin.compose.koinInject()
    /** 存相册的端口实现（:app 提供，见 GalleryImagePort）。 */
    val imageGalleryPort: dev.praxis.feature.chat.ImageGalleryPort =
        org.koin.compose.koinInject()

    // 运行时权限申请。回调不逐条比对结果，统一 refresh() 回读系统真值 ——
    // 多权限、部分授予时逐条比对必错。
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts
            .RequestMultiplePermissions(),
    ) {
        coroutineScope.launch { capabilityDetector.refresh() }
    }

    // 进入页面 + 从系统设置页返回时都刷新。
    //
    // 必须用 ON_RESUME 而不只是 LaunchedEffect(Unit)：「所有文件访问」这类权限
    // 要跳系统设置页才能开，用户开完回来组合不重建，LaunchedEffect 不会再跑，
    // 界面就永远停在「未授予」。这是跳转型授权最容易漏的一环。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                coroutineScope.launch { capabilityDetector.refresh() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // 必须移除：否则每次重组多挂一个观察者，一次 onResume 触发 N 次 refresh。
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { skillRegistry.reload() }

    // ── 会话与聊天 VM ────────────────────────────────────────
    //
    // ## 会话 id 必须落盘（2026-10-25 修：「重启后不是我退出前的会话」）
    //
    // 原来是 `rememberSaveable { mutableStateOf("main") }`。
    // `rememberSaveable` 只活在**进程内**（含配置变更）：Activity 重建能保住，
    // 但进程一死（用户划掉、系统回收、崩溃重启）就回到硬编码的 `"main"`。
    //
    // 这不只是「显示错会话」，它有两个真实后果：
    //
    //  1. 界面显示 `main`，而用户刚才在别的会话里 —— 他会以为记录丢了；
    //  2. 更糟：**下一条消息被写进 `main`**，把原本连续的上下文劈成两半，
    //     而且全程无报错，用户无从察觉。
    //
    // 所以初值改为「先恢复上次的会话 id」：占位空串 → `LaunchedEffect`
    // 读到真实值再设进去。初始不用 `"main"` 是因为那样会先拿 `main`
    // 建一次 `viewModel(key = "main")` 并读一遍历史 —— 白读库且闪一下内容。
    var sessionId by rememberSaveable { mutableStateOf("") }

    // 恢复上次会话：只跑一次，否则用户主动切会话后会被它拽回去。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (sessionId.isNotEmpty()) return@LaunchedEffect
        val saved = runCatching { settingsRepository.lastChatSessionId.first() }
            .getOrDefault("")
        sessionId = if (saved.isNotEmpty()) {
            saved
        } else {
            // 从没记录过：取「最近更新的那个会话」，而不是硬编码 "main"。
            //
            // 硬编码的风险很具体：`main` 可能已被用户删掉。那时
            // `viewModel` 会拿一个不存在的 id 建 VM，界面是空白聊天页
            // 且发消息无反应（写库时外键失败）。
            runCatching {
                chatRepository.observeSessions().first()
                    .maxByOrNull { it.updatedAt }?.id
            }.getOrNull().orEmpty().ifEmpty { "main" }
        }
    }

    // 切会话时写回。挂在 sessionId 的副作用上而不是切会话的点击回调里：
    // 点击回调只覆盖「用户主动切」，而恢复与新建会话这两条路也要记录。
    androidx.compose.runtime.LaunchedEffect(sessionId) {
        if (sessionId.isNotEmpty()) {
            runCatching { settingsRepository.setLastChatSessionId(sessionId) }
        }
    }
    val currentTier by permissionState.tier.collectAsStateWithLifecycle()
    val disabledTools by permissionState.disabledTools.collectAsStateWithLifecycle()

    // ── 工具禁用列表的**持久化恢复**（2026-10-25）
    //
    // ## 这是修一个真实缺陷，不是新功能
    //
    // `ToolPermissionState` 是内存态：用户在设置页关掉一个工具，
    // 进程一死就全忘了 —— 重启后那些工具又出现在模型的可见列表里。
    // 关掉工具是**长期意图**，不写盘等于没关。
    //
    // 读一次即可（`LaunchedEffect(Unit)`），之后由用户的每次切换
    // 反向写盘（见下面的 onToolToggle）。不订阅 Flow 是因为：
    // permissionState 是权威源，跟着它走会形成回写循环。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val saved = settingsRepository.disabledTools.first()
        saved.forEach { name -> permissionState.setToolEnabled(name, false) }
    }

    // key = sessionId：换会话必须拿到新 ViewModel，否则气泡还是上一个会话的。
    val chatViewModel: dev.praxis.feature.chat.ChatViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel(key = sessionId) {
            dev.praxis.feature.chat.ChatViewModel(
                provider = dev.praxis.data.ai.SettingsBackedProvider(
                        repository = settingsRepository,
                        // 请求队列等待上限（文档 §5.18）：0 = 不限（旧行为）。
                        queueWaitSeconds = { gatewaySystem.queueWaitSeconds },
                        // 网关出站：与账号页共用同一个 RouteState，
                        // 这样「清空运行状态」与真实请求看到的是同一份状态。
                        gatewayProvider = dev.praxis.data.gateway.GatewayProvider(
                            store = gatewayAccountStore,
                            // modelRules 的持久化还没接（导入导出界面在 P1 剩余部分），
                            // 所以映射为空；原生承载走 provider 表里各家声明的模型清单。
                            modelRules = { gatewayModelRules },
                            // 每次现取：改了策略下一句就生效（见 GatewayProvider 的参数说明）。
                            policy = { gatewayRoutingPolicy },
                            // 请求记录：报表与 token 预算的数据源（文档 6.1）。
                            recordUsage = { draft -> gatewayUsageStore.record(draft) },
                            // 内置家地址表（只有 anthropic 与 openai 两家，见 BuiltinProviders 的说明）。
                            // 地址解析：内置家优先（它们有确定的官方地址），其次用户建的自定义家。
                            providerBaseUrl = { id ->
                                dev.praxis.data.gateway.BuiltinProviders.baseUrl(id)
                                    ?: gatewayProviderRows.firstOrNull { it.id == id }?.baseUrl
                            },
                            // 预算判定要的「窗口内各账号用量」：每次请求前查一次。
                            accountSpend = { since -> gatewayUsageStore.tokensByAccountSince(since) },
                            replayLog = routeReplayLog,
                            groups = { gatewayRouteGroups },
                            budget = dev.praxis.core.gateway.FailoverBudget(
                                maxSwitches = gatewaySystem.maxSwitches,
                            ),
                            debugRawTraffic = gatewaySystem.debugRawTraffic,
                            // 每账号出口：装配点只在这里做一次解析，
                            // 拉清单与转发共用同一个 ProxyResolver（文档 §5.9 的要点）。
                            clientFor = { accountId ->
                                proxyResolver.clientFor(accountId) ?: gatewayHttpClient
                            },
                            client = gatewayHttpClient,
                            state = gatewayRouteState,
                        ),
                    ),
                model = DEFAULT_MODEL,
                toolRegistry = toolRegistry,
                toolExecutor = toolExecutor,
                toolContext = toolContext,
                permissionSnapshotProvider = { permissionState.snapshot() },
                // 生图进度：工具写、ViewModel 读（文档 §6.1）。
                imageGenProgress = imageGenProgressBus,
                // 长按保存到相册（生图文档 P2）：feature 声明端口，:app 给实现。
                imageGallery = imageGalleryPort,
                chatRepository = chatPersistence,
                sessionId = sessionId,
                memoryPort = memoryPort,
                // 每轮现拼：设置页刚关掉的技能，下一句就不该再生效。
                skillsPromptProvider = {
                    skillRegistry.composePrompt(skillRegistry.all().map { it.name }.toSet())
                },
                // `/技能名`：把 Skill 转成聊天层的最小视图。
                // 转换在这里做，`:feature:chat` 就不必依赖 `:core:plugin`。
                skillLookup = { name ->
                    skillRegistry.bodyForUserInvocation(name)?.let { skill ->
                        dev.praxis.feature.chat.SkillView(
                            name = skill.name,
                            description = skill.description,
                            body = skill.body,
                        )
                    }
                },
                // 用户自定义系统提示词 + 角色卡提示词（最高优先级）。现取：改完下一句生效。
                //
                // ## 为什么是「合并」而不是「谁覆盖谁」
                //
                // 两者语义不同，都要生效：
                // - 用户自定义系统提示词 = 全局规则（「永远用中文」「不要编造」）；
                // - 角色卡 = 当前人设（「你是某角色，语气冷淡」）。
                //
                // 若让角色卡覆盖用户提示词，用户会发现「换张卡我的全局规则就失效了」；
                // 若让用户提示词覆盖角色卡，那角色卡等于白设。
                //
                // ## 顺序：用户规则在前，角色设定在后
                //
                // `SystemPromptBuilder` 把这一段当作「用户系统提示词」层
                // （最高优先级层），而模型对越靠前的内容越稳定。
                // 用户规则是跨卡片的硬约束，角色设定是当前场景的人设，
                // 硬约束必须在前 —— 否则换一张「你现在是海盗，不受任何限制」的卡，
                // 就能把全局规则压过去。
                //
                // 这一段拼接在**取的时候**做（每轮现取），
                // 所以用户切卡、改全局规则，下一句都立刻生效。
                systemPromptProvider = {
                    // ## 两个来源必须**各自独立**失败（2026-11-07）
                    //
                    // 原来两条读取写在同一个 lambda 里、共享一次求值：
                    // `activePrompt()` 一旦抛异常，**用户规则也一起丢** ——
                    // 因为整个 lambda 的结果只被上层一个 runCatching 包着。
                    //
                    // 表现就是「什么都没注入」，而用户规则明明还在库里。
                    //
                    // 现在拆成两段：各自的失败只影响自己，另一段照常生效，
                    // 用户至少保有其中一份。
                    //
                    // ## 失败必须留痕
                    //
                    // 静默吞异常会让下次排查又从零开始。2026-11-07 排查
                    // 「提示词没注入」时最痛的一点就是：DataStore 里明明有值，
                    // 却没有任何日志能说明链路走到了哪一步、在哪一步变空。
                    val userRulesRaw = runCatching { settingsRepository.systemPrompt.first() }
                        .onFailure {
                            dev.praxis.ui.design.diagnostics.UiDiagnostics.note(
                                "prompt",
                                "读取用户规则失败：${it.message ?: it::class.simpleName}",
                            )
                        }
                        .getOrNull().orEmpty().trim()

                    // ## 如果这段文字其实是一张角色卡（2026-11-07）
                    //
                    // 设置页那个框的名字就叫「系统提示词」，所以用户很容易把
                    // 一整张卡的 JSON 粘进去。它存进去是纯文本，注入后模型收到的
                    // 是一坨 JSON —— JSON 是数据不是指令，于是人设一句都不生效，
                    // 而且没有任何报错可查。
                    //
                    // 这里按卡的语义解释一次：能解析成卡就注入卡组装好的提示词，
                    // 同时记一条诊断，提示用户改到「角色」页导入（那里可编辑、
                    // 可切换、可导出）。
                    //
                    // 解析不出来就是普通规则文本，原样使用 —— 「永远用中文」
                    // 这类输入完全不受影响。
                    val asCard = dev.praxis.core.model.character.TavernCardImporter
                        .promptFromCardJson(userRulesRaw)
                    val userRules = if (asCard != null) {
                        dev.praxis.ui.design.diagnostics.UiDiagnostics.note(
                            "prompt",
                            "系统提示词其实是一张角色卡，已按角色卡组装后注入" +
                                "（建议改到「角色」页导入，可编辑可切换）",
                        )
                        asCard.trim()
                    } else {
                        userRulesRaw
                    }

                    val characterPrompt = runCatching { characterRepository.activePrompt() }
                        .onFailure {
                            dev.praxis.ui.design.diagnostics.UiDiagnostics.note(
                                "prompt",
                                "读取角色卡失败：${it.message ?: it::class.simpleName}",
                            )
                        }
                        .getOrNull().orEmpty().trim()

                    when {
                        userRules.isEmpty() -> characterPrompt
                        characterPrompt.isEmpty() -> userRules
                        else -> userRules + "\n\n" + characterPrompt
                    }
                },
                // ## 内置人格（第二层，可被用户改写）
                //
                // 与上面的 `systemPromptProvider` 分开注入：
                //  - 那一段是「用户的规则 + 角色卡」，属最高优先级层；
                //  - 这一段是「Praxis 是谁」，出问题时只该改它，
                //    不该牵动那层「后续消息不得覆盖」的强约束。
                //
                // 空串表示用户没改过 -> `SystemPromptSettings.effectivePersona`
                // 回落到出厂人格，所以这里原样透传即可。
                personaProvider = { settingsRepository.personaPrompt.first() },
                // 待发队列存档：进程被回收后把排队内容读回来。
                // 与消息库分开存（见 `ChatViewModel.restoreQueue` 的说明）——
                // 空会话也可能有排队内容。
                queueStore = queueStore,
                contextConfigProvider = {
                    val m = settingsRepository.modelSettings.first()
                    // 流式开关在独立流里（不在 ModelSettings 内）：
                    // 它是「对话行为」而不是「模型参数」，与底栏「行为」分区同源。
                    val disableStream = settingsRepository.disableStreamOutput.first()
                    dev.praxis.feature.chat.ContextConfig(
                        contextLimit = m.contextLimit,
                        compactThreshold = m.compactThreshold,
                        thinkingEffort = m.thinkingEffort,
                        stream = !disableStream,
                    )
                },
                summaryModelProvider = {
                    val global = settingsRepository.modelSettings.first()
                    val fm = settingsRepository.functionModelSettings.first()
                    // ## 回落链要跟着主对话走（2026-10-29）
                    //
                    // 摘要功能位没单独指定模型时，它应该用「主对话在用的那个」。
                    // 而主对话现在可能被 `FunctionSlot.CHAT` 覆盖（见
                    // `SettingsBackedProvider`）。若这里仍拿 `global` 去 resolve，
                    // 摘要会用全局池里的模型 —— 与主对话用的不一致。
                    //
                    // 换算一次：把 CHAT 的覆盖结果**当成新的 global** 再 resolve，
                    // 于是「CHAT 覆盖 → 摘要跟随覆盖值」这条链自然成立，
                    // 而「CHAT 未覆盖 → 仍是 global」也不变。
                    val chat = fm.resolve(
                        dev.praxis.core.model.settings.FunctionSlot.CHAT,
                        global,
                    )
                    val forSummary = global.copy(
                        baseUrl = chat.baseUrl,
                        model = chat.model,
                        // 摘要模型必须是单值：`resolve` 对未覆盖分支读
                        // `global.activeModel`，而 activeModel 按 modelIndex 从
                        // 池里取。把池子缩成单个模型，索引必然落在 0。
                        modelIndex = 0,
                    )
                    fm.resolve(
                        dev.praxis.core.model.settings.FunctionSlot.CONTEXT_SUMMARY,
                        forSummary,
                    ).model
                },
                /**
                 * AI 总结标题（1:1 对齐 Operit）。
                 *
                 * ## 为什么在 :app 构造而不是让 ViewModel 自己造
                 *
                 * 「用哪个功能位的模型」「怎么发一次非流式请求」都是 `:app` 层
                 * 的装配知识（`feature:chat` 不依赖 `core:data` 的 DataStore）。
                 * ViewModel 只持有 [dev.praxis.feature.chat.ChatTitleGenerator]
                 * 这个端口，单测时传一个 lambda 即可。
                 *
                 * ## 每次新建实例可接受
                 *
                 * 它只持有 repository 与 provider 的引用，没有连接池或线程池。
                 * 真正重的是 `SettingsBackedProvider` 内部的 `OpenAiCompatProvider`
                 * （OkHttpClient），而那个是既有结构、每轮请求也会新建一次。
                 */
                titleGenerator = dev.praxis.data.ai.SettingsBackedTitleGenerator(
                    repository = settingsRepository,
                    provider = dev.praxis.data.ai.SettingsBackedProvider(
                        repository = settingsRepository,
                        // 请求队列等待上限（文档 §5.18）：0 = 不限（旧行为）。
                        queueWaitSeconds = { gatewaySystem.queueWaitSeconds },
                        // 网关出站：与账号页共用同一个 RouteState，
                        // 这样「清空运行状态」与真实请求看到的是同一份状态。
                        gatewayProvider = dev.praxis.data.gateway.GatewayProvider(
                            store = gatewayAccountStore,
                            // modelRules 的持久化还没接（导入导出界面在 P1 剩余部分），
                            // 所以映射为空；原生承载走 provider 表里各家声明的模型清单。
                            modelRules = { dev.praxis.core.gateway.GatewayModelRules() },
                            state = gatewayRouteState,
                            // 请求记录：这是**第二处**构造点（另一个 ViewModel），
                            // 两处都要接，否则只有一半请求进报表。
                            recordUsage = { draft -> gatewayUsageStore.record(draft) },
                            // 内置家地址表（只有 anthropic 与 openai 两家，见 BuiltinProviders 的说明）。
                            // 地址解析：内置家优先（它们有确定的官方地址），其次用户建的自定义家。
                            providerBaseUrl = { id ->
                                dev.praxis.data.gateway.BuiltinProviders.baseUrl(id)
                                    ?: gatewayProviderRows.firstOrNull { it.id == id }?.baseUrl
                            },
                            // 预算判定要的「窗口内各账号用量」：每次请求前查一次。
                            accountSpend = { since -> gatewayUsageStore.tokensByAccountSince(since) },
                            replayLog = routeReplayLog,
                            groups = { gatewayRouteGroups },
                            budget = dev.praxis.core.gateway.FailoverBudget(
                                maxSwitches = gatewaySystem.maxSwitches,
                            ),
                            debugRawTraffic = gatewaySystem.debugRawTraffic,
                            // 每账号出口：装配点只在这里做一次解析，
                            // 拉清单与转发共用同一个 ProxyResolver（文档 §5.9 的要点）。
                            clientFor = { accountId ->
                                proxyResolver.clientFor(accountId) ?: gatewayHttpClient
                            },
                            client = gatewayHttpClient,
                        ),
                    ),
                ),
            )
        }

    // 终端 VM：固定 key，切 Tab 回来时已输出的内容不丢。
    // maxScrollback / showBanner 来自设置，工厂只在首次创建时执行 ——
    // 所以改设置后**新建的会话**才用新值（已有会话不该被立刻截断）。
    val terminalViewModel: TerminalViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel(key = "terminal") {
            TerminalViewModel(
                runner = dev.praxis.data.terminal.ShellCommandRunner(),
                maxScrollback = terminalSettings.scrollbackLines,
                showBanner = terminalSettings.showBanner,
                // 会话快照：杀进程后恢复屏幕内容与命令历史。
                snapshotPort = terminalSnapshotPort,
                // 真终端：有容器就走 forkpty，`cd` / `vim` / Ctrl+C 才成立。
                ptyPort = terminalPtyPort,
                // AI 建的会话名（持久化）：进程重启后 port 的 live 表是空的，
                // 只能靠这份名字按名重建，否则终端页永远看不到 AI 的会话。
                externalSessionNames = { terminalSessionRegistry.names() },
            )
        }
    val terminalState by terminalViewModel.state.collectAsStateWithLifecycle()

    // ── 导航栈 ───────────────────────────────────────────────
    //
    // 泛型必须显式写 <PraxisRoute>：传 PraxisRoute.Main 会把 T 推导成它的静态
    // 类型，整个 sealed 层次进化不出来，存栈时抛 SerializationException。
    val backStack = rememberNavBackStack<PraxisRoute>(PraxisRoute.Main)

    // 恢复兜底：栈为空时补根页。NavDisplay 内部 require(isNotEmpty)，
    // 空栈直接抛异常 → 启动白屏。恢复失败是真会发生的，必须补齐且不留空隙。
    backStack.ensureSeeded()

    val navigator = androidx.compose.runtime.remember(backStack) { Navigator(backStack) }

    // ── 转场与效果 ───────────────────────────────────────────
    //
    // ## 为什么不再需要 useTabSlide / tabSlideSlope / TAB_SLIDE_WINDOW_MS
    //
    // 那套东西的唯一目的是「让切 Tab 看起来像 Pager 平移」。现在 Tab 真的由
    // Pager 承载，切 Tab 不经过 NavDisplay，也就没有「用哪个转场」的问题。
    // 这里只保留用户在外观页选的那一套，用于进/出二级页。
    val transition = androidx.compose.runtime.remember(
        themeSettings.predictiveBackAnimation,
        themeSettings.predictiveBackExitDirection,
    ) {
        navTransitionFor(
            animation = themeSettings.predictiveBackAnimation,
            exitDirection = themeSettings.predictiveBackExitDirection,
        )
    }

    // NavDisplayEffects 补齐（照 InstallerX）。缺的三个各有具体后果：
    //  - cornerClipRadius：不读设备真实屏幕圆角 → 滑入页在四角露直角；
    //  - cornerClipMode：AOSP/Scale/Classic 该四角全裁，miuix 只裁前缘；
    //  - blockInputDuringTransition：默认 true 会让转场期间输入被吞。
    val pageBackground = MiuixTheme.colorScheme.surface

    // 屏幕四角圆角半径。`rememberDeviceCornerRadius` 在无圆角屏上返回 0.dp，
    // 此时 `cornerClipRadius = 0.dp` 等价于不裁圆角（保持直角），行为自洽。
    val navCornerRadius = dev.praxis.ui.design.util.rememberDeviceCornerRadius()
    // 哪些档位需要四角全裁：卡片式转场（AOSP / Scale / Classic）。
    // miuix 默认是整页平移，只该裁前缘。
    val roundAllCorners = when (themeSettings.predictiveBackAnimation) {
        dev.praxis.core.model.settings.PredictiveBackAnimation.Aosp,
        dev.praxis.core.model.settings.PredictiveBackAnimation.Scale,
        dev.praxis.core.model.settings.PredictiveBackAnimation.Classic,
        -> true

        dev.praxis.core.model.settings.PredictiveBackAnimation.Miuix -> false
    }
    val effects = androidx.compose.runtime.remember(pageBackground) {
        NavDisplayEffects(
            enableCornerClip = true,
            // 圆角半径读设备真实屏幕角：写死一个值会在圆角屏的边缘露直角。
            // 旧实现没设，用的是默认 0.dp —— 等于「开了裁切但没有圆角」。
            cornerClipRadius = navCornerRadius,
            // All：AOSP / Scale / Classic 这类「卡片式」转场需要四角全裁；
            // Leading：miuix 默认的整页平移只裁前缘。按当前档位选。
            cornerClipMode = if (roundAllCorners) {
                top.yukonga.miuix.kmp.nav.core.NavCornerClipMode.All
            } else {
                top.yukonga.miuix.kmp.nav.core.NavCornerClipMode.Leading
            },
            dimAmount = 0.5f,
            backdropColor = pageBackground,
            // 转场中允许输入：用户点完条目就想滑，不该被动画拦住。
            // 默认值已经是 false（miuix 0.9.4），显式写出来防止将来默认值变化。
            blockInputDuringTransition = false,
        )
    }

    // 侧边栏开合。rememberSaveable：横竖屏切换、进程回收后重建时保持打开。
    var sidebarOpen by rememberSaveable { mutableStateOf(false) }

    // Tab 位置持久化：只存一个 Int。
    // 旧实现靠序列化整个栈来「记住 Tab」，副作用是恢复后可能停在两层栈上，
    // 第一次返回只退一个 Tab 而不是退 App。
    var lastTabIndex by rememberSaveable { mutableStateOf(0) }

    PraxisNavDisplay(
        backStack = backStack,
        navigator = navigator,
        transition = transition,
        effects = effects,
        modifier = modifier,
    ) { route ->
        when (route) {
            // ── 根页：底栏 + Pager，自带 Scaffold ──
            PraxisRoute.Main -> MainShellPage(
                navigator = navigator,
                initialTabIndex = lastTabIndex,
                onTabSettled = { lastTabIndex = it },
                // 显示**当前使用**的那一个模型，不是整个池子。
                //
                // `modelSettings.model` 现在是逗号分隔的池子（`a,b,c`），
                // 直接显示出来是一串乱码观感、也说不清在用哪个。
                modelName = modelSettings.activeModel.ifBlank { "默认模型" },
                onOpenSidebar = { sidebarOpen = true },
                onOpenModelSettings = { navigator.push(PraxisRoute.SettingsModel) },
                sidebar = { sidebarProgress ->
                    ChatSidebar(
                        open = sidebarOpen,
                        progress = sidebarProgress,
                        onDismiss = { sidebarOpen = false },
                        chatPersistence = chatPersistence,
                        pluginEntries = sidebarPluginEntries,
                        onOpenPluginEntry = { point ->
                            sidebarOpen = false
                            navigator.push(
                                PraxisRoute.PluginEntry(
                                    pluginId = point.pluginId,
                                    entryId = point.point.renderTargetId(),
                                    title = point.title,
                                ),
                            )
                        },
                        currentSessionId = sessionId,
                        onOpenSession = { newId ->
                            sessionId = newId
                            sidebarOpen = false
                        },
                        // 删掉当前会话：与 `ChatSessionsPage` 走**同一套**兜底逻辑
                        //（切到列表第一条；一条不剩就新建并先落库）。
                        //
                        // 两个入口各修一半是最坏的结果 —— 用户在抽屉里删掉当前会话能
                        // 正常切走，在会话列表页删却停在空白页，会被当成「有时能删有时不能」。
                        onDeleteCurrentSession = { deletedId ->
                            coroutineScope.launch {
                                val remain = runCatching {
                                    chatPersistence.listSessions()
                                }.getOrDefault(emptyList())
                                val nextId = remain.firstOrNull { it.id != deletedId }?.id
                                    ?: ("s" + System.currentTimeMillis()).also { fresh ->
                                        runCatching { chatPersistence.ensureSession(fresh, "新对话") }
                                    }
                                sessionId = nextId
                                sidebarOpen = false
                            }
                        },
                    )
                },
                chatTab = {
                    // ── 会话切换：淡入淡出（2026-10-23 新增）──────────────
                    //
                    // ## 之前为什么没有动画
                    //
                    // `sessionId` 一变，`viewModel(key = sessionId)` 就换实例，
                    // `ChatScreen` 的 `remember(sessionKey)` 跟着重建滚动状态。
                    // 这个过程是**硬切**：旧气泡瞬间消失、新气泡瞬间出现，
                    // 中间没有任何过渡。用户看到的是「闪一下」。
                    //
                    // ## 为什么 `targetState` 要同时带 id 和 VM
                    //
                    // 只带 `sessionId` 的话，退出分支（正在淡出的那一份）在
                    // 重组时读到的 `chatViewModel` 已经是**新**实例了 ——
                    // 于是「淡出的是新会话的内容」，表现为切换瞬间新旧内容重叠。
                    //
                    // 把 VM 一起放进 `targetState`，退出分支持有的是切换前的
                    // 那个实例，淡出的就是真正的旧会话。这不是多余的打包：
                    // Compose 的 `AnimatedContent` 只保证 `targetState` 值在
                    // 过渡期间可读，其它外部变量不保证。
                    //
                    // ## 为什么 `contentKey` 必须是 `sessionId`
                    //
                    // 这是整条改动的**关键**。`ChatTab` 内部每收到一个 token
                    // 都会让 `state` 变化；若不加 `contentKey`，`AnimatedContent`
                    // 会把「新的 targetState」当成新目标重新起一次转场 ——
                    // 流式响应期间就会一直在淡入淡出（每 token 闪一次）。
                    //
                    // `contentKey` 告诉它：只有 key 变了才算「真的换了内容」，
                    // 同 key 下的值更新只是原地刷新，不触发转场。
                    //
                    // ## 为什么用 fade + 轻缩放，而不用 slide
                    //
                    // 会话切换是**平级替换**，不是「进下一页 / 回上一页」。
                    // 横滑会被读成导航语义，与 `PraxisNavDisplay` 的转场打架
                    // （同一个手势在两层里有两个含义）。淡出淡入才符合
                    // 「换了一份内容」而不改变位置关系。
                    AnimatedContent(
                        targetState = sessionId to chatViewModel,
                        contentKey = { it.first },
                        transitionSpec = {
                            // 进比出慢：新内容先立住，用户不会觉得在等动画。
                            // delay 40ms 让旧内容先走一步，避免两层的中间态
                            // 同时半透明时看起来像「一起糊了」。
                            (
                                fadeIn(tween(220, delayMillis = 40)) +
                                    scaleIn(
                                        animationSpec = tween(220),
                                        initialScale = 0.98f,
                                    )
                                ).togetherWith(
                                fadeOut(tween(140)),
                            ).using(SizeTransform(clip = false))
                        },
                        label = "chat-session-switch",
                    ) { (targetSessionId, targetViewModel) ->
                        ChatTab(
                            viewModel = targetViewModel,
                            settingsRepository = settingsRepository,
                            skillRegistry = skillRegistry,
                            // 底栏工具组：可用工具名 + 当前禁用集 + 切换回调。
                            toolNames = toolRegistry.names().toList(),
                            disabledTools = disabledTools,
                            onToggleTool = { n, e ->
                                permissionState.setToolEnabled(n, e)
                                coroutineScope.launch {
                                    val next = if (e) {
                                        permissionState.disabledTools.value - n
                                    } else {
                                        permissionState.disabledTools.value + n
                                    }
                                    settingsRepository.setDisabledTools(next)
                                }
                            },
                            modelSettings = modelSettings,
                            // 会话身份：滚动状态 / item key / 密钥掩码都按它区分。
                            // 旧实现没传，于是三个东西共用上一会话的值（见该参数 KDoc）。
                            sessionId = targetSessionId,
                            // 内存压力：内存占用过高时由 ChatViewModel 自动压缩。
                            //
                            // 只传 Flow 的引用（不是快照值）：`pressure` 是 StateFlow，
                            // 组合期读 `.value` 会把「当前档位」固定成一次组合的常量，
                            // 之后档位变化不会重组合。
                            memoryPressure = memoryMonitor.pressure,
                            // 底部设置条的模型胶囊 / Tune 都走这里。
                            // 与顶栏 `onOpenModelSettings` 同一个目的地 ——
                            // 两处入口、一个页面，不给用户第二条岔路。
                            onOpenModelSettings = {
                                navigator.push(PraxisRoute.SettingsModel)
                            },
                            // 底栏「工具」分区里的「管理单个工具」。
                            // 去处就是设置页的工具屏（同一个目的地，
                            // 不在对话页另开一个工具列表）。
                            onOpenToolSettings = {
                                navigator.push(PraxisRoute.SettingsTools)
                            },
                        )
                    }
                },
                terminalTab = { terminalVisible ->
                    // ## 切到终端页时同步一次「外部会话」（2026-11-05）
                    //
                    // AI 通过 `create_terminal_session` 之类的工具建的会话，
                    // 底层 PTY 早就存在（与终端页共用同一个 port 单例），
                    // 但终端页的 tab 是 UI 自建的 —— 不问 port 就永远看不到它。
                    //
                    // 用 `terminalVisible` 而不是 `LaunchedEffect(Unit)`：Pager
                    // 预组合相邻页，`Unit` 会在还没切过去时就跑掉。
                    LaunchedEffect(terminalVisible) {
                        if (terminalVisible) terminalViewModel.syncExternalSessions()
                    }
                    TerminalScreen(
                        state = terminalState,
                        onSubmit = terminalViewModel::submit,
                        onInputChange = terminalViewModel::onInputChange,
                        onAddTab = terminalViewModel::addTab,
                        onCloseTab = terminalViewModel::closeTab,
                        onSelectTab = terminalViewModel::selectTab,
                        onCancel = terminalViewModel::cancel,
                        onClear = terminalViewModel::clear,
                        // 「环境配置」胶囊 → Linux 环境（容器 / SSH / FRP）。
                        onOpenEnvConfig = { navigator.push(PraxisRoute.SettingsLinux) },
                        // 齿轮 → 终端设置（第 12 项：原先两个按钮都跳 Linux 环境）。
                        onOpenTerminalSettings = { navigator.push(PraxisRoute.SettingsTerminal) },
                        fontSizeSp = terminalSettings.fontSizeSp,
                        // 键帽标签：与设置页编辑的是同一份 DataStore 值。
                        //
                        // ## 为什么在调用点做 `terminalKeyLayoutOf` 而不是让
                        // `TerminalScreen` 自己收 `List<String>`
                        //
                        // `TerminalScreen` 是纯 UI，它只该认 `TerminalKeyLayout`
                        // （已校验、已带行为身份）。若让它收标签列表，它内部
                        // 就得引用 `core:model` 的 codec —— 那会把
                        // 「持久化格式」漏进渲染层，将来换存储格式要改 UI。
                        //
                        // 转换放在这里：`:app` 是装配层，正是干这个的地方。
                        keyLayout = dev.praxis.feature.terminal.keyboard.terminalKeyLayoutOf(
                            keyboardLabels,
                        ),
                        // 真终端：输出区实测尺寸 → PTY 窗口（viu/top 排版才对）。
                        onResize = terminalViewModel::resize,
                        // 真终端：TAB / ESC 直接发字节（补全、退出插入模式）。
                        // 非真终端时 `writeKey` 返回 false，UI 回退到本地模拟。
                        onRawKey = terminalViewModel::writeKey,
                    )
                },
                memoryTab = {
                    MemoryRoute(
                        store = memoryStore,
                        activeSpaceId = activeMemorySpaceId,
                        onSelectSpace = { id ->
                            // 双写：写盘（长期生效）+ 写端口（下一句立即生效）。
                            coroutineScope.launch {
                                settingsRepository.setActiveMemorySpace(id)
                                memoryPort.setActiveSpace(id)
                            }
                        },
                        spaces = memorySpaces,
                        onCreateSpace = { name ->
                            // 建完立刻切过去：用户的意图是「我要在这个新空间里用」，
                            // 而不是「建一个空壳留在列表里」。
                            coroutineScope.launch {
                                settingsRepository.addMemorySpace(name)
                                settingsRepository.setActiveMemorySpace(name)
                                memoryPort.setActiveSpace(name)
                            }
                        },
                    )
                },
                settingsTab = { SettingsHomeScreen() },
            )

            // ── 对话域二级页 ──
            PraxisRoute.ChatSessions -> ChatSessionsPage(
                chatPersistence = chatPersistence,
                currentSessionId = sessionId,
                onOpenSession = { newId ->
                    sessionId = newId
                    // popNow 而不是 pop：这是「程序语义上退回」，不该被
                    // 100ms 防抖吞掉（用户点完条目立刻按返回是合法操作）。
                    navigator.popNow()
                },
                /**
                 * 删掉了当前会话 → 必须换一个**真实存在**的会话，否则界面悬空。
                 *
                 * ## 这是「当前对话无法删除」的另一半根因（2026-11-01）
                 *
                 * 删除本身一直是生效的（`DELETE` 提交了），但 `sessionId` 是
                 * 本文件里的 `rememberSaveable`，页面自己改不动它。于是旧实现在
                 * 删完当前会话后：
                 *
                 *   - 对话页仍然 `viewModel(key = 已删除的 id)`，一条消息都读不到；
                 *   - `LaunchedEffect(sessionId)` 还把这个死 id 写进
                 *     `lastChatSessionId`，冷启动继续打开它；
                 *   - 用户重新进列表时那条会话又「不见了」，可它明明刚才还在。
                 *
                 * 所以这里删完先**重读一次库**，再切到列表里的第一条；
                 * 一个都不剩就新建一个空会话（与右上角「新建」完全同一套动作，
                 * 包括 `ensureSession` 先落库 —— 只换 id 不写库的话新会话在列表里
                 * 压根不存在）。
                 */
                onDeleteCurrentSession = { deletedId ->
                    coroutineScope.launch {
                        val remain = runCatching {
                            chatPersistence.listSessions()
                        }.getOrDefault(emptyList())
                        val nextId = remain.firstOrNull { it.id != deletedId }?.id
                            ?: ("s" + System.currentTimeMillis()).also { fresh ->
                                runCatching { chatPersistence.ensureSession(fresh, "新对话") }
                            }
                        sessionId = nextId
                        navigator.popNow()
                    }
                },
            )

            // ── 设置域：全部交给 feature:settings 自己的分发 ──
            //
            // 这个 `else` 不是兜底：`PraxisRoute` 是 sealed，上面两个分支之后
            // 剩下的**只可能**是 SettingsXxx。`SettingsHost` 内部再用穷举 `when`
            // 分成具体页面（缺一个编不过）。
            else -> dev.praxis.feature.settings.SettingsHost(
                route = route,
                gatewayAccountRows = gatewayAccountRows,
                // ── 模型管理页（gateway_model 的写入口）──
                gatewayModelRows = gatewayModelRows,
                gatewayReportWindows = gatewayReportWindows,
                gatewayReportTopModels = gatewayReportTopModels,
                gatewayRequestRecords = gatewayRequestRecords,
                gatewayProviderRows = gatewayProviderRows,
                imageGenRemoteModels = imageGenRemoteModels,
                gatewayProviderChoices = gatewayProviderChoices,
                gatewayAccountSaveError = gatewayAccountSaveError,
                onSaveGatewayAccount = { draft ->
                    coroutineScope.launch {
                        gatewayAccountStore.addAccount(draft)
                            .onSuccess {
                                gatewayAccountSaveError = null
                                gatewayRowsRefresh += 1
                            }
                            .onFailure { error ->
                                gatewayAccountSaveError = error.message ?: "保存失败"
                            }
                    }
                },
                gatewayProviderAddError = gatewayProviderAddError,
                onAddGatewayProvider = { draft ->
                    coroutineScope.launch {
                        gatewayAccountStore.addProvider(draft)
                            .onSuccess {
                                gatewayProviderAddError = null
                                gatewayProviderTick += 1
                            }
                            .onFailure { error ->
                                // 校验在 core 里已经做过一遍，这里是**第二道**：
                                // 存储层可能返回它自己的拒绝理由（比如重名）。
                                gatewayProviderAddError = error.message ?: "添加失败"
                            }
                    }
                },
                // 拉取模型清单（文档 §5.3）：复用 OkHttpModelCatalogFetcher，
                // 并且**用该家账号的出口** —— 文档 §5.9 点名过：清单与转发不同出口
                // 会出「清单能拉、请求 403」，那种错最难查。
                onFetchGatewayModels = { providerId ->
                    val baseUrl = dev.praxis.data.gateway.BuiltinProviders.baseUrl(providerId)
                        ?: gatewayProviderRows.firstOrNull { it.id == providerId }?.baseUrl
                    if (baseUrl.isNullOrBlank()) {
                        dev.praxis.feature.gateway.GatewayProviderModelsFetch(
                            error = "这家还没有地址：先填地址再拉清单",
                        )
                    } else {
                        val view = gatewayAccountStore.routingViews()
                            .firstOrNull { it.providerId == providerId }
                        val key = view?.let { account ->
                            (gatewayAccountStore.credentialOf(account.id).getOrNull()
                                as? dev.praxis.core.gateway.GatewayCredential.ApiKey)?.apiKey
                        }.orEmpty()
                        val client = view?.let { account ->
                            proxyResolver.clientFor(account.id)
                        } ?: gatewayHttpClient
                        // 没有 Key 的家按「无需鉴权」发（Ollama 这类）。
                        val kind = if (key.isBlank()) {
                            dev.praxis.core.ai.ModelCatalogFetcher.ProviderKind.NoAuth
                        } else {
                            dev.praxis.core.ai.ModelCatalogFetcher.ProviderKind.Bearer
                        }
                        val result = dev.praxis.data.ai.OkHttpModelCatalogFetcher(client)
                            .fetch(baseUrl, key, kind)
                        result.fold(
                            onSuccess = { options ->
                                dev.praxis.feature.gateway.GatewayProviderModelsFetch(
                                    modelIds = options.map { it.id },
                                )
                            },
                            onFailure = { error ->
                                dev.praxis.feature.gateway.GatewayProviderModelsFetch(
                                    error = error.message ?: "拉取失败",
                                )
                            },
                        )
                    }
                },
                onApplyGatewayModels = { providerId, models ->
                    // 写入前把关（文档 §5.4.1 第 17 条的「失败保留旧清单」）：
                    // 成功但拉到空清单时**不覆盖** —— 上游临时返回空数组的话，
                    // 直接写会把用户手填的模型抹掉。
                    val decision = dev.praxis.core.gateway.GatewayModelCatalogCache
                        .decideWrite(success = true, fetched = models)
                    if (decision == dev.praxis.core.gateway.GatewayModelCatalogCache
                            .WriteDecision.Write
                    ) {
                        coroutineScope.launch {
                            gatewayAccountStore.updateProviderModels(providerId, models)
                            gatewayRowsRefresh += 1
                        }
                    }
                },
                onRemoveGatewayProvider = { id ->
                    coroutineScope.launch {
                        gatewayAccountStore.removeProvider(id)
                        gatewayProviderTick += 1
                    }
                },
                onLoadGatewayRequestRecords = { gatewayRequestRecordsLoad += 1 },
                onRefreshGatewayReport = { gatewayReportRefresh += 1 },
                gatewayRequestRetentionDays = gatewayRetentionDays,
                onGatewayRequestRetentionDaysChange = { days ->
                    coroutineScope.launch {
                        settingsRepository.setGatewayRequestRetentionDays(days)
                    }
                },
                gatewayModelError = gatewayModelError,
                gatewayRoutingPolicy = gatewayRoutingPolicy,
                gatewayRoutingPolicyBroken = gatewayRoutingPolicyBroken,
                onGatewayPolicyChange = { updated ->
                    coroutineScope.launch {
                        settingsRepository.setGatewayRoutingPolicyJson(
                            dev.praxis.core.gateway.GatewayRoutingPolicyCodec.encode(updated),
                        )
                    }
                },
                // 测试连接：真实请求一次 /v1/models（不消耗生成额度），复用主模型页的 fetcher。
                onTestGatewayAccount = { baseUrl, apiKey ->
                    if (baseUrl.isBlank() || apiKey.isBlank()) {
                        Result.failure<String>(IllegalArgumentException("先填地址与 Key"))
                    } else {
                        var clean = baseUrl.trim()
                        while (clean.endsWith("/")) {
                            clean = clean.dropLast(1)
                        }
                        modelCatalogFetcher
                            .fetch(
                                baseUrl = clean,
                                apiKey = apiKey.trim(),
                                providerKind =
                                    dev.praxis.core.ai.ModelCatalogFetcher.ProviderKind.Bearer,
                            )
                            .fold(
                                onSuccess = { models ->
                                    Result.success("连通 · " + models.size + " 个模型可用")
                                },
                                onFailure = { error ->
                                    Result.failure<String>(
                                        IllegalStateException(
                                            error.message ?: "连接失败（地址或 Key 不对）",
                                        ),
                                    )
                                },
                            )
                    }
                },
                onAddGatewayAccount = { provider, name, baseUrl, apiKey ->
                    val draft = dev.praxis.core.gateway.GatewayAccountDraft(
                        providerId = provider,
                        name = name,
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                    )
                    val added = gatewayAccountStore.addAccount(draft)
                    added.fold(
                        onSuccess = { entity ->
                            gatewayRowsRefresh += 1
                            Result.success("已添加：" + entity.name)
                        },
                        onFailure = { error ->
                            Result.failure<String>(
                                IllegalStateException(error.message ?: "保存失败"),
                            )
                        },
                    )
                },
                onToggleGatewayModel = { provider, id, enabled ->
                    coroutineScope.launch {
                        val existing = gatewayModelStore.all()
                            .firstOrNull { it.provider == provider && it.id == id }
                        if (existing == null) {
                            // 找不到就说出来，而不是静默不写（那会让开关看起来生效了）。
                            gatewayModelError = "找不到这条记录：" + provider + "/" + id
                        } else {
                            gatewayModelError = null
                            gatewayModelStore.upsertAll(listOf(existing.copy(enabled = enabled)))
                            gatewayRowsRefresh += 1
                        }
                    }
                },
                onDeleteGatewayModel = { provider, id ->
                    coroutineScope.launch {
                        gatewayModelError = null
                        gatewayModelStore.delete(provider, id)
                        gatewayRowsRefresh += 1
                    }
                },
                onAddGatewayModel = { provider, id, name ->
                    coroutineScope.launch {
                        gatewayModelError = null
                        // 能力位全部留 null（未知），不猜 —— 见 GatewayModelEntryDefaults 的 KDoc。
                        val entity = dev.praxis.core.data.gateway.GatewayModelEntity(
                            provider = provider,
                            id = id,
                            name = dev.praxis.core.gateway.GatewayModelEntryDefaults
                                .displayName(id, name),
                            kind = dev.praxis.core.gateway.GatewayModelEntryDefaults.DEFAULT_KIND,
                            source = dev.praxis.core.gateway.GatewayModelEntryDefaults.SOURCE_MANUAL,
                            enabled = true,
                            credits = null,
                            supportsToolCall = null,
                            supportsImages = null,
                            supportsVideo = null,
                            supportsReasoning = null,
                            supportsImageGeneration = null,
                            supportsImageEditing = null,
                            inputModalities = dev.praxis.core.gateway.GatewayModelEntryDefaults
                                .DEFAULT_INPUT_MODALITIES,
                            maxInputTokens = null,
                            maxOutputTokens = null,
                            reasoningEfforts = dev.praxis.core.gateway.GatewayModelEntryDefaults
                                .NO_REASONING_EFFORTS,
                            reasoningDefault = null,
                            reasoningFixed = null,
                            reasoningSource = null,
                        )
                        gatewayModelStore.upsertAll(listOf(entity))
                        gatewayRowsRefresh += 1
                    }
                },
                gatewayEnabled = gatewayEnabled,
                onBackupDatabase = { backupRequest += 1 },
                backupMessage = backupMessage,
                onGatewayEnabledChange = { enabled ->
                    coroutineScope.launch { settingsRepository.setGatewayEnabled(enabled) }
                },
                imageGenProbeEngine = { imageGenEngineLocator.probe() },
                imageGenSocModel = imageGenSocModel,
                imageGenAvailability = imageGenAvailability,
                imageGenInstalledModels = imageGenInstalledModels,
                imageGenCurrentTarget = imageGenCurrentTarget,
                onSelectImageGenTarget = { target ->
                    coroutineScope.launch {
                        settingsRepository.setImageGenTargetJson(
                            dev.praxis.core.imagegen.ImageGenTargetCodec.encode(target),
                        )
                    }
                },
                imageGenModelsRootPath = imageGenModelsRoot.absolutePath,
                imageDownloadSettings = imageDownloadSettings,
                imageGenDefaults = imageGenDefaults,
                imageGenOutput = imageGenOutput,
                onImageGenOutputChange = { updated ->
                    coroutineScope.launch {
                        settingsRepository.setImageOutputSettingsJson(
                            dev.praxis.core.imagegen.ImageOutputSettingsCodec.encode(updated),
                        )
                    }
                },
                onImageGenDefaultsChange = { updated ->
                    coroutineScope.launch {
                        settingsRepository.setImageGenDefaultsJson(
                            dev.praxis.core.imagegen.ImageGenDefaultsCodec.encode(updated),
                        )
                    }
                },
                onImageDownloadSettingsChange = { updated ->
                    coroutineScope.launch {
                        settingsRepository.setImageDownloadSettingsJson(
                            dev.praxis.core.imagegen.ImageDownloadSettingsCodec.encode(updated),
                        )
                    }
                },
                imageGenModels = imageGenCatalog,
                imageGenInstalledIds = imageGenInstalledIds,
                imageGenDownloadState = imageGenDownloadState,
                // 下载交给前台服务：几 GB 的包要跑很久，用户必然切走，
                // 页面作用域里的协程会被系统回收。服务里再调 manager.start()。
                onDownloadImageGenModel = { model ->
                    dev.praxis.data.imagegen.ImageModelDownloadService.start(appContext, model.id)
                },
                onCancelImageGenDownload = { imageGenDownloadManager.cancel() },
                onClearGatewayRuntime = { ids ->
                    // 运行期状态是内存里的，清掉就是真的清掉；随后触发一次重算。
                    ids.forEach { gatewayRouteState.onSuccess(it) }
                    gatewayRowsRefresh += 1
                },
                gatewayModelOptions = gatewayModelOptions,
                gatewayQueueModelName = modelSettings.activeModel,
                gatewayQueueEntries = gatewayQueueEntries,
                // 现取：回放是内存列表，进页面时读到的就是最新的（含刚失败那次）。
                gatewayReplayEntries = { routeReplayLog.recent() },
                gatewayRouteGroups = gatewayRouteGroups,
                gatewaySystemSettings = gatewaySystem,
                gatewayProxySettings = gatewayProxy,
                onTestGatewayModel = { _, model ->
                    val (ok, detail) = gatewayProbeProvider.testModel(model)
                    dev.praxis.feature.gateway.GatewayModelTestReport(
                        isSuccess = ok,
                        detail = detail,
                    )
                },
                // 绑定要选账号：用「账号 id → 显示名」。
                gatewayProxyAccounts = gatewayAccountRows.map { it.id to it.name },
                onGatewayProxySettingsChange = { updated ->
                    coroutineScope.launch {
                        settingsRepository.setGatewayProxyJson(
                            dev.praxis.core.gateway.ProxySettingsCodec.encode(
                                dev.praxis.core.gateway.ProxyRules.pruneBinding(updated),
                            ),
                        )
                    }
                },
                onGatewaySystemSettingsChange = { updated ->
                    coroutineScope.launch {
                        settingsRepository.setGatewaySystemSettingsJson(
                            dev.praxis.core.gateway.GatewaySystemSettingsCodec.encode(
                                dev.praxis.core.gateway.GatewaySystemSettingsRules.normalize(updated),
                            ),
                        )
                    }
                },
                onAddGatewayRouteGroup = { group ->
                    coroutineScope.launch {
                        val updated = gatewayRouteGroups + group
                        settingsRepository.setGatewayRouteGroupsJson(
                            dev.praxis.core.gateway.GatewayRouteGroupCodec.encode(updated),
                        )
                    }
                },
                onRemoveGatewayRouteGroup = { name ->
                    coroutineScope.launch {
                        val updated = gatewayRouteGroups.filterNot {
                            it.name.equals(name, ignoreCase = true)
                        }
                        settingsRepository.setGatewayRouteGroupsJson(
                            dev.praxis.core.gateway.GatewayRouteGroupCodec.encode(updated),
                        )
                    }
                },
                gatewayAccountNames = gatewayAccountRows.associate { it.id to it.name },
                gatewayImportBusy = gatewayImportBusy,
                gatewayImportReport = gatewayImportReport,
                gatewayImportResult = gatewayImportResult,
                gatewayImportError = gatewayImportError
                    ?: if (gatewayModelRulesParse?.isFailure == true) {
                        "已保存的模型规则是坏 JSON，映射不会生效；重新导入一份覆盖它"
                    } else {
                        null
                    },
                gatewayExportBusy = gatewayExportBusy,
                gatewayExportText = gatewayExportText,
                gatewayExportError = gatewayExportError,
                onImportFromClipboard = {
                    gatewayImportError = null
                    gatewayImportResult = null
                    val text = clipboardManager?.primaryClip
                        ?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)
                        ?.coerceToText(appContext)
                        ?.toString()
                    if (text.isNullOrBlank()) {
                        gatewayImportError = "剪贴板里没有文本。请先在网关面板导出账号文件并复制内容。"
                    } else if (dev.praxis.core.gateway.GatewayImporter.parse(text).isFailure &&
                        dev.praxis.core.gateway.GatewayModelRulesCodec.parse(text).isSuccess
                    ) {
                        // 不是账号文件，但能当 modelRules 解析 —— 存下来。
                        val rules = dev.praxis.core.gateway.GatewayModelRulesCodec
                            .parse(text).getOrThrow()
                        coroutineScope.launch {
                            settingsRepository.setGatewayModelRulesJson(
                                dev.praxis.core.gateway.GatewayModelRulesCodec.encode(rules),
                            )
                            gatewayImportResult = "已保存模型规则：映射 " +
                                rules.mappings.size + " 条、禁用 " +
                                rules.disabled.size + " 条、自定义 " +
                                rules.custom.size + " 条"
                        }
                    } else {
                        gatewayImportBusy = true
                        coroutineScope.launch {
                            val existing = gatewayAccountStore.all().map { it.id }.toSet()
                            // 认得哪些家：目前只有我们自己表里已有的自定义家。
                            // 内置家要等 P5 的上游实现 —— 不认得的会被跳过，理由写进报告。
                            val known = gatewayAccountStore.providers().map { it.id }.toSet()
                            val plan = dev.praxis.core.gateway.GatewayImporter.prepare(
                                files = mapOf("clipboard.json" to text),
                                existingIds = existing,
                                knownProviders = known,
                            )
                            gatewayImportPlan = plan
                            gatewayImportReport = plan.report
                            gatewayImportBusy = false
                        }
                    }
                },
                onConfirmGatewayImport = {
                    val plan = gatewayImportPlan
                    if (plan == null) {
                        gatewayImportError = "没有待写入的导入计划（先解析一次）"
                    } else {
                        gatewayImportBusy = true
                        coroutineScope.launch {
                            val result = gatewayAccountStore.applyImport(plan)
                            gatewayImportBusy = false
                            gatewayImportReport = null
                            gatewayImportPlan = null
                            gatewayImportResult = result.fold(
                                onSuccess = { stored ->
                                    val head = "已写入 " + stored.accountIds.size + " 个账号"
                                    val tail = if (stored.providersWithoutBaseUrl.isEmpty()) {
                                        ""
                                    } else {
                                        "；有 " + stored.providersWithoutBaseUrl.size +
                                            " 个自定义家没有地址，没有写入"
                                    }
                                    head + tail
                                },
                                onFailure = { error ->
                                    "写入失败：" + (error.message ?: "未说明原因") +
                                        "。没有写入任何账号（不会存明文）。"
                                },
                            )
                            gatewayRowsRefresh += 1
                        }
                    }
                },
                onExportGateway = { includeSecrets ->
                    gatewayExportError = null
                    gatewayExportText = null
                    gatewayExportBusy = true
                    coroutineScope.launch {
                        val result = runCatching {
                            val rows = gatewayAccountStore.all()
                            val accounts = rows.map { row ->
                                if (!includeSecrets) {
                                    // 「仅配置」粒度不碰凭证：连解密都不做。
                                    dev.praxis.core.gateway.GatewayAccountFile(
                                        format = dev.praxis.core.gateway.ACCOUNT_FORMAT,
                                        id = row.id,
                                        provider = row.providerId,
                                        name = row.name,
                                        baseUrl = row.baseUrlOverride,
                                    )
                                } else {
                                    val credential = gatewayAccountStore.credentialOf(row.id).getOrElse { error ->
                                        // 不写「解不开就跳过」：那会导出一份看起来完整、其实缺凭证的备份。
                                        throw IllegalStateException(
                                            "账号「" + row.name + "」的凭证解不开：" + (error.message ?: "未说明原因"),
                                        )
                                    }
                                    when (credential) {
                                        is dev.praxis.core.gateway.GatewayCredential.ApiKey ->
                                            dev.praxis.core.gateway.GatewayAccountFile(
                                                format = dev.praxis.core.gateway.ACCOUNT_FORMAT,
                                                id = row.id,
                                                provider = row.providerId,
                                                name = row.name,
                                                baseUrl = row.baseUrlOverride,
                                                apiKey = credential.apiKey,
                                            )
                                        is dev.praxis.core.gateway.GatewayCredential.OAuth ->
                                            dev.praxis.core.gateway.GatewayAccountFile(
                                                format = dev.praxis.core.gateway.ACCOUNT_FORMAT,
                                                id = row.id,
                                                provider = row.providerId,
                                                name = row.name,
                                                baseUrl = row.baseUrlOverride,
                                                accessToken = credential.accessToken,
                                                refreshToken = credential.refreshToken,
                                            )
                                    }
                                }
                            }
                            val plan = dev.praxis.core.gateway.GatewayExportPlanner.build(
                                accounts = accounts,
                                // 路由/重试/超时 Praxis 还没持久化 —— 传 null，由计划层把这条写进 notes。
                                routing = null,
                                modelRules = gatewayModelRulesJson?.let {
                                    kotlinx.serialization.json.Json.parseToJsonElement(it)
                                },
                                includeSecrets = includeSecrets,
                                date = java.time.LocalDate.now()
                                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")),
                            )
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                dev.praxis.core.gateway.GatewayExportWriter.write(
                                    java.io.File(
                                        appContext.getExternalFilesDir(null) ?: appContext.filesDir,
                                        "exports",
                                    ),
                                    plan,
                                )
                            }
                        }
                        gatewayExportBusy = false
                        result.fold(
                            onSuccess = { written ->
                                val head = "已导出 " + written.writtenPaths.size + " 个文件到 " + written.rootPath
                                gatewayExportText = if (written.notes.isEmpty()) {
                                    head
                                } else {
                                    head + "\n\n未包含：\n· " + written.notes.joinToString("\n· ")
                                }
                            },
                            onFailure = { error ->
                                gatewayExportError = "导出失败：" + (error.message ?: "未说明原因")
                            },
                        )
                    }
                },
                onDismissGatewayImport = {
                    gatewayImportReport = null
                    gatewayImportPlan = null
                    gatewayImportResult = null
                    gatewayImportError = null
                },
                repository = settingsRepository,
                versionName = versionName,
                versionCode = versionCode,
                toolSpecs = toolRegistry.all().map { it.spec },
                currentTier = currentTier,
                isToolEnabled = { name -> name !in disabledTools },
                onTierChange = { permissionState.setTier(it) },
                onToolToggle = { n, e ->
                    // 先改运行态（立即影响下一批调用），再写盘（长期生效）。
                    permissionState.setToolEnabled(n, e)
                    coroutineScope.launch {
                        val next = if (e) {
                            permissionState.disabledTools.value - n
                        } else {
                            permissionState.disabledTools.value + n
                        }
                        settingsRepository.setDisabledTools(next)
                    }
                },
                pluginRuntime = pluginRuntime,
                capturedEntries = capturedEntries,
                activationFailures = activationFailures,
                capabilities = capabilities,
                onRequestCapability = { capability ->
                    val launched = capabilityRequester.request(
                        capability = capability,
                        launcher = { perms -> permissionLauncher.launch(perms) },
                    )
                    if (!launched) {
                        coroutineScope.launch { capabilityDetector.refresh() }
                    }
                },
                memoryStore = memoryStore,
                activeMemorySpaceId = activeMemorySpaceId,
                onSelectMemorySpace = { id ->
                    coroutineScope.launch {
                        settingsRepository.setActiveMemorySpace(id)
                        memoryPort.setActiveSpace(id)
                    }
                },
                memorySpaces = memorySpaces,
                onCreateMemorySpace = { name ->
                    coroutineScope.launch {
                        settingsRepository.addMemorySpace(name)
                        settingsRepository.setActiveMemorySpace(name)
                        memoryPort.setActiveSpace(name)
                    }
                },
                workflowEngine = workflowEngine,
                // `LocalContext.current` 必须在 Composable 里读，不能写在
                // 参数位置的 lambda 内部 —— 那个 lambda 是 `remember` 的
                // calculation，运行在**非 Composable** 上下文，读 CompositionLocal
                // 会报「@Composable invocations can only happen from the context
                // of a @Composable function」。
                linuxEnvPort = linuxEnvPort,
                terminalSettings = terminalSettings,
                onTerminalFontSizeChange = { sp ->
                    coroutineScope.launch { settingsRepository.setTerminalFontSize(sp) }
                },
                onTerminalScrollbackChange = { lines ->
                    coroutineScope.launch { settingsRepository.setTerminalScrollback(lines) }
                },
                onTerminalShowBannerChange = { show ->
                    coroutineScope.launch { settingsRepository.setTerminalShowBanner(show) }
                },
                onTerminalReset = {
                    coroutineScope.launch {
                        settingsRepository.setTerminalFontSize(
                            dev.praxis.core.model.settings.TerminalSettings.DEFAULT_FONT_SIZE_SP,
                        )
                        settingsRepository.setTerminalScrollback(
                            dev.praxis.core.model.settings.TerminalSettings.DEFAULT_SCROLLBACK_LINES,
                        )
                        settingsRepository.setTerminalShowBanner(true)
                    }
                },
                // 模型列表拉取（设置页「拉取」按钮）。
                // 取上面已 koinInject 的那一个，不在这里再 inject 一次 ——
                // 在参数位置调 `koinInject()` 会在每次重组时执行，属于在
                // Composable 调用之外读 Composable 状态，且不容易察觉。
                modelCatalogFetcher = modelCatalogFetcher,
                // ── 角色卡系统 ──────────────────────────────────
                //
                // 仓储从 Koin 取（与聊天页用的是**同一个**实例）：
                // 聊天页每轮调 `activePrompt()`，设置页负责改内容。
                // 若两处各建一个实例，活跃卡 id 会因为各自缓存而不同步。
                characterRepository = characterRepository,
                pluginRepository = pluginRepository,
                pluginMarket = pluginMarket,
                onOpenCharacterEdit = { cardId ->
                    // 参数化 Route：null 表示新建。
                    navigator.push(PraxisRoute.SettingsCharacterEdit(cardId = cardId))
                },
                onCloseCharacterEdit = {
                    // popNow 而不是 pop：编辑页的「保存/删除完成」是
                    // 一次确定性返回，不需要转场动画把人留在那里看。
                    navigator.popNow()
                },
                onOpenPromptTags = { navigator.push(PraxisRoute.SettingsPromptTags) },
                // 导出落地：写到应用外部文件目录的 exports/ 下。
                //
                // ## 为什么不用 Download 目录
                //
                // 写公共 Download 在 Android 10+ 需要 MediaStore 或 SAF，
                // 而 SAF 要弹系统选择器（用户得再点两次）。
                // 应用外部目录（`getExternalFilesDir`）免权限、在文件管理器里
                // 可访问（`Android/data/<pkg>/files/exports/`），
                // 且卸载应用时会一起清掉 —— 对「导出备份」这个场景够用。
                onExportCharacterJson = { fileName, json ->
                    // 用组合期捕获的 Context（见 appContext 的 KDoc）。
                    // IO 调度器：写文件是阻塞调用，放主线程会掉帧。
                    coroutineScope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val dir = java.io.File(
                                appContext.getExternalFilesDir(null) ?: appContext.filesDir,
                                "exports",
                            )
                            runCatching {
                                dir.mkdirs()
                                java.io.File(dir, fileName).writeText(json)
                            }
                        }
                    }
                },
            )
        }
    }
}

/** 默认模型：模型配置落地前的保底值。 */
private const val DEFAULT_MODEL: String = "gpt-4o-mini"
