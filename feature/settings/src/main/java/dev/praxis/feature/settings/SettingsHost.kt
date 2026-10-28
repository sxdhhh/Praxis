package dev.praxis.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.praxis.core.data.settings.SettingsRepository
import dev.praxis.core.model.settings.TerminalSettings
import dev.praxis.feature.settings.ai.FunctionModelRoute
import dev.praxis.feature.settings.ai.ModelRoute
import dev.praxis.feature.settings.ai.SystemPromptRoute
import dev.praxis.feature.settings.appearance.AppearanceRoute
import dev.praxis.feature.settings.capability.MemoryRoute
import dev.praxis.feature.settings.character.CharacterEditRoute
import dev.praxis.feature.settings.character.CharactersRoute
import dev.praxis.feature.settings.character.PromptTagsRoute
import dev.praxis.feature.settings.capability.WorkflowRoute
import dev.praxis.feature.settings.env.LinuxEnvPort
import dev.praxis.feature.settings.env.LinuxEnvRoute
import dev.praxis.feature.settings.env.TerminalSettingsScreen
import dev.praxis.feature.settings.env.rememberTerminalStorageState
import dev.praxis.feature.settings.home.SettingWrongRoute
import dev.praxis.feature.settings.home.SettingsUnfinished
import dev.praxis.feature.settings.info.AboutScreen
import dev.praxis.feature.settings.info.LogScreen
import dev.praxis.feature.settings.info.OpenSourceLicenseScreen
import dev.praxis.feature.settings.tools.PermissionScreen
import dev.praxis.feature.settings.tools.ToolsScreen
import dev.praxis.ui.design.component.PraxisPage
import dev.praxis.ui.navigation.LocalNavigator
import dev.praxis.ui.navigation.PraxisRoute
import dev.praxis.ui.navigation.title


/**
 * 设置子页的分发器。
 *
 * ## 重建原因（2026-10-22 导航重构）
 *
 * 旧签名是 `SettingsHost(entryKey: String?, ...)`，内部
 * `SettingsEntry.byKey(entryKey)` 查出枚举项再分发。这条链上有三个隐患，
 * 全部是「编得过、跑起来才发现」的类型：
 *
 * ```
 * ① entryKey 是字符串 → 拼错编得过
 * ② byKey 返回 null → 必须有一张兜底页
 * ③ 兜底页本身又是一份设置首页 → 点进去看起来像"没反应"（真实事故）
 * ```
 *
 * 现在签名是 `SettingsHost(route: PraxisRoute, ...)`，分发用 `when (route)`：
 *
 *  - **没有字符串参数**，目的地就是类型；
 *  - **没有兜底页**：`when` 覆盖全部设置 Route，其他 Route 由外层
 *    `FeatureHost` 的 `when` 拦住，根本不会传进来；
 *  - **没有第二份清单**：设置首页直接遍历 [SettingsEntry]，顺序与
 *    目的地一一对应（`SettingsEntry.route`）。
 *
 * ## 为什么参数这么多
 *
 * 这些是页面真正需要的依赖与回调，属于「接线」。它们不由本文件
 * `koinInject`，而是从 `:app` 显式注入 —— feature 模块不依赖 DI 框架，
 * 单测 / @Preview 可以直接传假实现，依赖关系在 `:app` 一处可见。
 */
@Composable
fun SettingsHost(
    route: PraxisRoute,
    repository: SettingsRepository,
    versionName: String,
    versionCode: Int,
    toolSpecs: List<dev.praxis.core.tool.ToolSpec>,
    currentTier: dev.praxis.core.tool.PermissionTier,
    isToolEnabled: (String) -> Boolean,
    onTierChange: (dev.praxis.core.tool.PermissionTier) -> Unit,
    onToolToggle: (String, Boolean) -> Unit,
    /**
     * 真实系统授权状态（`CapabilityDetector` 的检测结果）。
     *
     * 默认取 [dev.praxis.core.model.permission.CapabilitySnapshot.Unknown]：
     * 这样既有的、没有接入检测器的调用点仍能编译，且界面上显示的是
     * 「尚未检测」而不是假装已授权 —— 保守的缺省值比乐观的缺省值安全。
     */
    capabilities: dev.praxis.core.model.permission.CapabilitySnapshot =
        dev.praxis.core.model.permission.CapabilitySnapshot.Unknown,
    /** 点「去授予」。缺省不做事（仍要有可点反馈由 UI 层负责）。 */
    onRequestCapability: (dev.praxis.core.model.permission.PraxisCapability) -> Unit = {},
    /** 记忆存储。由 :app 注入，与 repository 同口径。 */
    memoryStore: dev.praxis.core.memory.MemoryStore,
    /**
     * 当前活动记忆空间 id（空串 = 默认空间）。
     *
     * 由 :app 从 SettingsRepository 的 Flow 收集后传入：这一层不自己读 DataStore，
     * 保证「对话页用的空间」与「记忆页显示的空间」始终是同一个值。
     */
    activeMemorySpaceId: String = "",
    /** 切换记忆空间。缺省 null 时不渲染选择器（测试/预览路径）。 */
    onSelectMemorySpace: ((String) -> Unit)? = null,
    /** 用户显式创建过的空间名（来自 SettingsRepository.memorySpaces）。 */
    memorySpaces: List<String> = emptyList(),
    /** 新建记忆空间；null = 不显示「+ 新建空间」。 */
    onCreateMemorySpace: ((String) -> Unit)? = null,
    /** 工作流引擎。由 :app 注入。 */
    workflowEngine: dev.praxis.core.workflow.WorkflowEngine,
    /**
     * Linux 环境的执行端口（proot / tar / 进程）。
     *
     * 用可空默认值而不是强制参数：它是新增能力，缺省时页面显示「未装配」
     * 而不是让所有既有调用点都改签名、或者让整页崩掉。
     */
    linuxEnvPort: LinuxEnvPort? = null,
    /**
     * 终端设置（第 12 项缺陷）。
     *
     * 默认值用 [TerminalSettings] 的默认构造：`SettingsHost` 在测试里会被
     * 单独调，缺省值让既有测试不必构造 DataStore。真机路径由 `:app` 传真实值。
     */
    terminalSettings: TerminalSettings = TerminalSettings(),
    /** 字号变更。缺省不做事（同上，为测试友好）。 */
    onTerminalFontSizeChange: (Float) -> Unit = {},
    /** 回滚行数变更。 */
    onTerminalScrollbackChange: (Int) -> Unit = {},
    /** 首屏字符画开关。 */
    onTerminalShowBannerChange: (Boolean) -> Unit = {},
    /** 终端设置恢复默认（三项一起写）。 */
    onTerminalReset: () -> Unit = {},
    /**
     * 模型列表拉取端口（`OkHttpModelCatalogFetcher`，由 `:app` 注入）。
     *
     * 默认 null：单测 / @Preview 不必构造 OkHttp。为 null 时模型页
     * 不显示「拉取」按钮（而不是显示一个点了没反应的按钮）。
     */
    modelCatalogFetcher: dev.praxis.core.ai.ModelCatalogFetcher? = null,
    /**
     * 角色卡仓储（`:app` 注入：依赖 Room + DataStore）。
     *
     * ## 为什么公开签名也要有（本轮修）
     *
     * `SettingsPageBody` 早已支持这组参数，但 `SettingsHost` 的公开签名漏了，
     * 于是 `:app` 传 `characterRepository = ...` 时编译报
     * 「No parameter with name 'characterRepository' found」——
     * 而这三页（角色卡 / 编辑 / 标签库）在路由与分发层都是通的。
     *
     * 也就是说：**接线在最外面一层断了**，里面全都好的。
     */
    characterRepository: dev.praxis.core.data.character.CharacterRepository? = null,
    /**
     * 插件仓库（已安装包的读写）。
     *
     * 与 [characterRepository] 同一纪律：可空，null 时该页显示「未装配」。
     * 理由见本文件的「依赖一律从 :app 注入」说明。
     */
    pluginRepository: dev.praxis.core.plugin.PluginRepository? = null,
    /** 在线市场；null 表示不提供（隐私模式或未配置）。 */
    pluginMarket: dev.praxis.core.plugin.MarketApi? = null,
    /** 进入角色卡编辑页（cardId 为 null 表示新建）。 */
    onOpenCharacterEdit: (String?) -> Unit = {},
    /** 编辑完成 / 取消后回到列表。 */
    onCloseCharacterEdit: () -> Unit = {},
    /** 进入提示词标签库。 */
    onOpenPromptTags: () -> Unit = {},
    /** 导出角色卡 JSON（为 null 时编辑页不显示导出按钮）。 */
    onExportCharacterJson: ((fileName: String, json: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // ## 每个设置页自带 Scaffold（2026-10-22 导航重构）
    //
    // 旧结构里 Scaffold 只有全应用一份（在 NavDisplay 外层），设置页只是它的
    // content 子组合。三个后果：
    //  1. 页面根容器不画背景 → NavDisplay 转场时透出下层内容；
    //  2. 页面没有自己的 PopupHost → miuix 下拉/弹窗类控件（模型页的服务商选择
    //     用了 SpinnerPreference）在 entry 里展开时会去开新 Window，与宿主抢焦点 → 崩；
    //  3. 返回按钮由外层统一画，判据是 `route !is Home`，加一层就需改判据。
    //
    // 现在每一页自己套一层 PraxisPage（内含 miuix Scaffold + PopupHost +
    // 自己的 layerBackdrop），上面三个问题在结构上不存在。
    //
    // 标题与返回动作都从路由/LocalNavigator 就地取，不再逐层透传回调。
    val navigator = LocalNavigator.current
    PraxisPage(
        title = route.title(),
        onBack = { navigator.pop() },
        modifier = modifier,
    ) { _ ->
        SettingsPageBody(
            route = route,
            repository = repository,
            versionName = versionName,
            versionCode = versionCode,
            toolSpecs = toolSpecs,
            currentTier = currentTier,
            isToolEnabled = isToolEnabled,
            onTierChange = onTierChange,
            onToolToggle = onToolToggle,
            capabilities = capabilities,
            onRequestCapability = onRequestCapability,
            memoryStore = memoryStore,
            activeMemorySpaceId = activeMemorySpaceId,
            onSelectMemorySpace = onSelectMemorySpace,
            memorySpaces = memorySpaces,
            onCreateMemorySpace = onCreateMemorySpace,
            workflowEngine = workflowEngine,
            linuxEnvPort = linuxEnvPort,
            terminalSettings = terminalSettings,
            onTerminalFontSizeChange = onTerminalFontSizeChange,
            onTerminalScrollbackChange = onTerminalScrollbackChange,
            onTerminalShowBannerChange = onTerminalShowBannerChange,
            onTerminalReset = onTerminalReset,
            modelCatalogFetcher = modelCatalogFetcher,
            characterRepository = characterRepository,
            pluginRepository = pluginRepository,
            pluginMarket = pluginMarket,
            onOpenCharacterEdit = onOpenCharacterEdit,
            onCloseCharacterEdit = onCloseCharacterEdit,
            onOpenPromptTags = onOpenPromptTags,
            onExportCharacterJson = onExportCharacterJson,
            modifier = Modifier,
        )
    }
}

/** 设置页的正文分发。与壳分开，壳只管栏与背景。 */
@Composable
private fun SettingsPageBody(
    route: PraxisRoute,
    repository: SettingsRepository,
    versionName: String,
    versionCode: Int,
    toolSpecs: List<dev.praxis.core.tool.ToolSpec>,
    currentTier: dev.praxis.core.tool.PermissionTier,
    isToolEnabled: (String) -> Boolean,
    onTierChange: (dev.praxis.core.tool.PermissionTier) -> Unit,
    onToolToggle: (String, Boolean) -> Unit,
    capabilities: dev.praxis.core.model.permission.CapabilitySnapshot,
    onRequestCapability: (dev.praxis.core.model.permission.PraxisCapability) -> Unit,
    memoryStore: dev.praxis.core.memory.MemoryStore,
    activeMemorySpaceId: String = "",
    onSelectMemorySpace: ((String) -> Unit)? = null,
    /** 用户显式创建过的空间名（来自 SettingsRepository.memorySpaces）。 */
    memorySpaces: List<String> = emptyList(),
    /** 新建记忆空间；null = 不显示「+ 新建空间」。 */
    onCreateMemorySpace: ((String) -> Unit)? = null,
    workflowEngine: dev.praxis.core.workflow.WorkflowEngine,
    /**
     * 角色卡仓储。
     *
     * `:app` 注入（依赖 Room + DataStore）。为 null 时角色卡三个页面
     * 显示「未装配」而不是崩 —— 与 [linuxEnvPort] 同一套判断。
     *
     * 放在必填参数之后并给默认值 null：既有的调用点（包括测试）
     * 不必因为新增能力而全改一遍。
     */
    characterRepository: dev.praxis.core.data.character.CharacterRepository? = null,
    /**
     * 插件仓库（已安装包的读写）。
     *
     * 与 [characterRepository] 同一纪律：可空，null 时该页显示「未装配」。
     * 理由见本文件的「依赖一律从 :app 注入」说明。
     */
    pluginRepository: dev.praxis.core.plugin.PluginRepository? = null,
    /** 在线市场；null 表示不提供（隐私模式或未配置）。 */
    pluginMarket: dev.praxis.core.plugin.MarketApi? = null,
    /** 进入角色卡编辑页（cardId 为 null 表示新建）。 */
    onOpenCharacterEdit: (String?) -> Unit = {},
    /** 编辑完成/取消后回到列表。 */
    onCloseCharacterEdit: () -> Unit = {},
    /** 进入标签库。 */
    onOpenPromptTags: () -> Unit = {},
    /**
     * 把导出的酒馆 JSON 落地。
     *
     * 为 null 时编辑页**不显示**导出按钮（而不是显示一个点了没反应的按钮）。
     */
    onExportCharacterJson: ((fileName: String, json: String) -> Unit)? = null,
    linuxEnvPort: LinuxEnvPort?,
    terminalSettings: TerminalSettings,
    onTerminalFontSizeChange: (Float) -> Unit,
    onTerminalScrollbackChange: (Int) -> Unit,
    onTerminalShowBannerChange: (Boolean) -> Unit,
    onTerminalReset: () -> Unit,
    /** 模型列表拉取端口（可为 null：未装配时模型页不给「拉取」按钮）。 */
    modelCatalogFetcher: dev.praxis.core.ai.ModelCatalogFetcher? = null,
    modifier: Modifier = Modifier,
) {
    when (route) {
        // ── 模型与提示词：决定「AI 怎么说话」 ──
        PraxisRoute.SettingsModel -> ModelRoute(
            repository = repository,
            modifier = modifier,
            modelCatalogFetcher = modelCatalogFetcher,
        )
        PraxisRoute.SettingsFunctionModel -> FunctionModelRoute(repository, modifier)
        PraxisRoute.SettingsSystemPrompt -> SystemPromptRoute(repository, modifier)

        // ── 角色卡系统 ──
        //
        // ## 为什么 characterRepository 是可空参数
        //
        // 它是 `:app` 注入的（依赖 Room + DataStore）。缺失时（单测、
        // @Preview、或 DI 未装配）不能让整页崩，而要明确告诉用户「没接上」——
        // 这与 [SettingsLinux] 的处理是同一套判断：能力没有装配时
        // 显示原因，而不是给一个点了没反应的按钮。
        PraxisRoute.SettingsCharacters -> if (characterRepository == null) {
            SettingsUnfinished(
                title = SettingsEntry.Characters.title,
                reason = "角色卡系统未装配仓储（需要 Room + DataStore）。",
                modifier = modifier,
            )
        } else {
            CharactersRoute(
                repository = characterRepository,
                onEdit = { cardId -> onOpenCharacterEdit(cardId) },
                onOpenTags = onOpenPromptTags,
                modifier = modifier,
            )
        }

        // 参数化 Route：`cardId == null` 表示新建。
        is PraxisRoute.SettingsCharacterEdit -> if (characterRepository == null) {
            SettingsUnfinished(
                title = SettingsEntry.Characters.title,
                reason = "角色卡系统未装配仓储（需要 Room + DataStore）。",
                modifier = modifier,
            )
        } else {
            CharacterEditRoute(
                repository = characterRepository,
                cardId = route.cardId,
                onDone = onCloseCharacterEdit,
                onExportJson = onExportCharacterJson,
                modifier = modifier,
            )
        }

        PraxisRoute.SettingsPromptTags -> if (characterRepository == null) {
            SettingsUnfinished(
                title = SettingsEntry.Characters.title,
                reason = "角色卡系统未装配仓储（需要 Room + DataStore）。",
                modifier = modifier,
            )
        } else {
            PromptTagsRoute(repository = characterRepository, modifier = modifier)
        }

        // ── 观感 ──
        PraxisRoute.SettingsAppearance -> AppearanceRoute(repository, modifier)

        // ── 能力配置 ──
        PraxisRoute.SettingsTools -> ToolsScreen(
            tools = toolSpecs,
            currentTier = currentTier,
            isEnabled = isToolEnabled,
            onToggle = onToolToggle,
            modifier = modifier,
        )
        PraxisRoute.SettingsPermission -> PermissionScreen(
            capabilities = capabilities,
            currentTier = currentTier,
            allTools = toolSpecs,
            // 传 isToolEnabled：状态卡要显示「**实际可用**工具数」，
            // 只按档位算会在工具被用户单独关掉时高报。
            isEnabled = isToolEnabled,
            onRequestCapability = onRequestCapability,
            onSelect = onTierChange,
            modifier = modifier,
        )
        PraxisRoute.SettingsMemory -> MemoryRoute(
            store = memoryStore,
            modifier = modifier,
            activeSpaceId = activeMemorySpaceId,
            onSelectSpace = onSelectMemorySpace,
            spaces = memorySpaces,
            onCreateSpace = onCreateMemorySpace,
        )
        PraxisRoute.SettingsWorkflow -> WorkflowRoute(workflowEngine, modifier)
        // 语音：功能已主动放弃，给明确原因而不是假装能用（见 SettingsUnfinished）。
        PraxisRoute.SettingsSpeech -> SettingsUnfinished(
            title = SettingsEntry.Speech.title,
            reason = SPEECH_REASON,
            modifier = modifier,
        )

        // ── 基础设施 ──
        // Linux 环境：端口未装配时给明确说明，而不是崩溃或假装能用。
        PraxisRoute.SettingsLinux -> if (linuxEnvPort == null) {
            SettingsUnfinished(
                title = SettingsEntry.Linux.title,
                reason = "Linux 环境未装配执行端口（proot / tar / 进程）。" +
                    "该能力需要 :app 层注入 LinuxEnvPort。",
                modifier = modifier,
            )
        } else {
            LinuxEnvRoute(linuxEnvPort, modifier)
        }
        // 终端设置：终端页齿轮的目的地（第 12 项缺陷）。
        // 与上面的 Linux 环境页是两个不同目的地 —— 这里只管终端长什么样。
        //
        // ## 2026-10-23：存储 / 临时目录 / 键盘 三块接进来
        //
        // 难点是**状态从哪来**。这三块的真实数据（占用、是否安装、
        // 容器跑没跑）全在 `LinuxEnvViewModel` 里，而它是 `LinuxEnvRoute`
        // 用 `viewModel(key = "linux-env")` 创建的。
        //
        // 这里用**同一个 key** 取同一个实例：
        //  - `SettingsHost` 只是 `when` 分发，没有 NavHost，所以整页共享
        //    同一个 `ViewModelStoreOwner`（Activity）；
        //  - 同一个 key → 同一个实例 → 在 Linux 环境页装的进度、跑着的容器，
        //    切到终端设置页看到的都是**同一份真实状态**。
        //
        // 换成新建一个 ViewModel 会得到这样一份假状态：设置页说「未安装」、
        // 而环境页明明装好了；点「刷新」也统计不到任何东西（另一个实例的
        // `installed` 是 false）。
        PraxisRoute.SettingsTerminal -> {
            val storageState = rememberTerminalStorageState(
                port = linuxEnvPort,
                repository = repository,
            )
            TerminalSettingsScreen(
                settings = terminalSettings,
                onFontSizeChange = onTerminalFontSizeChange,
                onScrollbackChange = onTerminalScrollbackChange,
                onShowBannerChange = onTerminalShowBannerChange,
                onReset = onTerminalReset,
                storage = storageState,
                modifier = modifier,
            )
        }
        PraxisRoute.SettingsPackages -> {
            // ## 为什么不再显示「暂不开放」（2026-10-29）
            //
            // 那条说明当时的依据是「需要沙箱内嵌 JS 运行时，所以要等」。
            // 但「装包 / 卸包 / 开关 / 浏览市场」这一层**完全不需要 JS 运行时** ——
            // 它是纯文件与网络操作，`core:plugin` 的 3560 行已经写好并在单测里跑通。
            // 把能做的和不能做的混在一句话里，结果是能做的那部分永远不开放。
            //
            // 现在：这一页只做管理与安装；工具能否被模型调用，取决于运行时加载
            // （`PluginRuntimeHost`），那部分仍按原计划推进，不在本页承诺。
            val repo = pluginRepository
            val market = pluginMarket
            if (repo == null) {
                SettingsUnfinished(
                    title = SettingsEntry.Packages.title,
                    reason = PACKAGES_REASON,
                    modifier = modifier,
                )
            } else {
                dev.praxis.feature.settings.plugin.PluginsRoute(
                    repository = repo,
                    // 市场为 null 时给一个「关闭」实现，避免 UI 里到处判空。
                    market = market ?: dev.praxis.core.plugin.MarketApi.disabled(),
                    modifier = modifier,
                )
            }
        }
        PraxisRoute.SettingsLog -> LogScreen(modifier)
        PraxisRoute.SettingsAbout -> AboutScreen(
            versionName = versionName,
            versionCode = versionCode,
            modifier = modifier,
        )
        // 开源许可明细：从关于页 push 进来，与上游层级一致
        // （`about/ossLicensePage/MiuixOpenSourceLicensePage`）。
        PraxisRoute.SettingsOpenSourceLicense -> OpenSourceLicenseScreen(modifier)

        // ## 这个分支为什么必须存在，以及为什么它不是兜底页
        //
        // 上面已经覆盖**全部** `SettingsXxx` 子类型，剩下的是 `Main` 与
        // `ChatSessions` —— 它们不是设置子页，只可能是上层分发写错了。
        //
        // 旧实现的兜底页是「再渲染一遍设置首页」，于是「点没反应」这个
        // 表象掩盖了真实的接线错误。这里改成**明确的错误页**：能一眼看出
        // 「传错了 Route」，而不是让用户以为按钮坏了。
        //
        // 没有这个 `else` 时 `when` 不穷举，编译不过 —— 这正是想要的：
        // 将来新增 Route 会强制你在这里做选择。
        else -> SettingWrongRoute(route = route, modifier = modifier)
    }
}

/**
 * 「语音」被主动放弃的原因。
 *
 * 抽成常量而不是写在页面里：设置首页的摘要、本页正文、以及将来的
 * 文档需要同一份措辞。三处各写一遍就会漂移成三种说法。
 */
internal val SPEECH_REASON: String =
    "语音（TTS / 语音输入）已列入主动放弃：要适配多个厂商引擎，" +
        "半成品只会出现「以为能说话、实际报错」。等有确定引擎再回来做。"

/** 「扩展包」暂不开放的原因。同上，单一措辞来源。 */
internal val PACKAGES_REASON: String =
    "扩展包需要沙箱内嵌 JS 运行时（ToolPkg），风险量级与普通工具完全不同。" +
        "当前工具注册表已覆盖内置能力，暂不开放第三方包。"
