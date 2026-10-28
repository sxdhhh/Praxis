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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.praxis.core.data.settings.SettingsRepository
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
    val memoryStore: dev.praxis.core.memory.MemoryStore = org.koin.compose.koinInject()
    val workflowEngine: dev.praxis.core.workflow.WorkflowEngine = org.koin.compose.koinInject()
    val skillRegistry: dev.praxis.core.plugin.SkillRegistry = org.koin.compose.koinInject()
    /**
     * 终端会话快照端口。
     *
     * 放在这里而不是在 `TerminalViewModel` 里自己 `get()`：VM 在
     * `feature:terminal` 模块，不该依赖 Koin（它的单测直接 `TerminalViewModel(...)`）。
     * 由 `:app` 注入是既有纪律，与 `chatPersistence` 完全一致。
     */
    val terminalSnapshotPort: dev.praxis.feature.terminal.TerminalSnapshotPort =
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
                provider = dev.praxis.data.ai.SettingsBackedProvider(settingsRepository),
                model = DEFAULT_MODEL,
                toolRegistry = toolRegistry,
                toolExecutor = toolExecutor,
                toolContext = toolContext,
                permissionSnapshotProvider = { permissionState.snapshot() },
                chatRepository = chatPersistence,
                sessionId = sessionId,
                memoryPort = memoryPort,
                // 每轮现拼：设置页刚关掉的技能，下一句就不该再生效。
                skillsPromptProvider = {
                    skillRegistry.composePrompt(skillRegistry.all().map { it.name }.toSet())
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
                    val userRules = settingsRepository.systemPrompt.first().trim()
                    val characterPrompt = characterRepository.activePrompt().trim()
                    when {
                        userRules.isEmpty() -> characterPrompt
                        characterPrompt.isEmpty() -> userRules
                        else -> userRules + "\n\n" + characterPrompt
                    }
                },
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
                    fm.resolve(
                        dev.praxis.core.model.settings.FunctionSlot.CONTEXT_SUMMARY,
                        global,
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
                    provider = dev.praxis.data.ai.SettingsBackedProvider(settingsRepository),
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
                        currentSessionId = sessionId,
                        onOpenSession = { newId ->
                            sessionId = newId
                            sidebarOpen = false
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
                terminalTab = {
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
            )

            // ── 设置域：全部交给 feature:settings 自己的分发 ──
            //
            // 这个 `else` 不是兜底：`PraxisRoute` 是 sealed，上面两个分支之后
            // 剩下的**只可能**是 SettingsXxx。`SettingsHost` 内部再用穷举 `when`
            // 分成具体页面（缺一个编不过）。
            else -> dev.praxis.feature.settings.SettingsHost(
                route = route,
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
