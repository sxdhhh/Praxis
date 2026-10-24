package dev.praxis.ui.tab

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.praxis.core.data.settings.SettingsRepository
import dev.praxis.core.model.settings.ModelSettings
import dev.praxis.feature.chat.ChatScreen
import dev.praxis.feature.chat.ChatViewModel
import dev.praxis.feature.chat.widget.ModelConfigCard
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import dev.praxis.ui.widgets.ChatDisplayStyle
import dev.praxis.feature.settings.ai.maskApiKey

/**
 * 对话 Tab 的容器。
 *
 * ## 为什么从 `PraxisApp.kt` 搬出来
 *
 * 上游 InstallerX 的 `ui/page/miuix/` 下每个 Tab 有自己的一目录
 * （`installer/` 23 文件、`settings/` 21 文件），Tab 的内部细节不写在
 * 「页面容器 / 导航壳」里。Praxis 原来把这些放在 `PraxisApp.kt` 尾部，
 * 那个文件于是同时承担：DI、栈管理、转场、四个 Tab 的分派、以及对话 Tab
 * 自己的 `LaunchedEffect` 与密钥读取 —— 451 行里读不出主线。
 *
 * 搬到 `ui/tab/` 后 `PraxisApp.kt` 只保留「依赖装配 + 目的地分派」。
 *
 * ## `LaunchedEffect` 的 key 必须是 `viewModel`
 *
 * 会话切换的实现是「换实例」（`viewModel(key = sessionId)`）。
 * `LaunchedEffect(Unit)` 只比较 key，`Unit` 永远等于 `Unit`，所以换实例后
 * 它**不会重跑** → 新会话的 `restoreIfNeeded()` 没人调 → 气泡永远空
 * （这是第 9 项缺陷的真因）。
 *
 * ## 密钥为什么只读一次
 *
 * 这里只用它判断「有没有配 key」，不保留原文。所以读一次掩码值放进
 * `mutableStateOf` 即可；每次组合都读会白跑一次加密解密。
 * 依赖 `modelSettings` 作 key：用户在设置页改完模型回来时能刷新。
 */
@Composable
fun ChatTab(
    viewModel: ChatViewModel,
    settingsRepository: SettingsRepository,
    modelSettings: ModelSettings,
    /**
     * 当前会话 id。
     *
     * ## 为什么 ChatTab 需要它（而不只是 PraxisApp 内部用）
     *
     * 两处必须按会话区分：
     * 1. `ChatScreen` 的滚动状态与 item key —— 气泡 id 跨会话会重叠
     *    （`nextId` 每实例从 0 开始），不带会话身份就会把上一会话的
     *    滚动位置/已组合节点复用到新会话（详见 `ChatScreen.sessionKey` 的 KDoc）；
     * 2. 密钥掩码的重读 —— `modelSettings` 是全局流，切会话不发射，
     *    导致模型卡的「已连接/未配置」停留在上一个会话的值。
     */
    sessionId: String = "default",
    /**
     * 内存压力流（来自 `MemoryMonitor`）。
     *
     * ## 为什么是 Flow 而不是在 ViewModel 里直接注入 MemoryMonitor
     *
     * `feature:chat` 不该知道 `:app` 的数据源实现 —— 那样 `ChatViewModel`
     * 的单测就要造一个 `ActivityManager`。这里只传一个 `Flow<MemoryPressure>`，
     * ViewModel 只暴露 `onMemoryPressureChanged(level)` 一个接收口。
     *
     * 默认 `null`：不传时聊天页完全不参与内存联动，既有调用点与测试
     * 不需要改签名。
     */
    memoryPressure: kotlinx.coroutines.flow.Flow<dev.praxis.core.ai.MemoryPressure>? = null,
    /**
     * 进模型设置页。
     *
     * ## 为什么由外部传入而不是在 ChatTab 里 `navigator.push`
     *
     * `ChatTab` 是「一个 Tab 的内容」，它不知道自己在哪条导航栈上
     * （`PraxisApp` 的 `MainShellPage` 里，根页是 Main、模型设置是它的
     * 二级页）。让一个 Tab 直接持有 Navigator 会把「页面内容」和
     * 「导航结构」耦合起来，也会让预览环境必须造一个假 Navigator。
     *
     * null 表示不提供入口（预览环境），此时底部设置条上的模型胶囊与
     * Tune 都不渲染 —— 不画点了没反应的控件。
     */
    onOpenModelSettings: (() -> Unit)? = null,
    /**
     * 进工具管理页（逐项禁用）。
     *
     * null 表示不提供 —— 此时底栏「工具」分区里少一行「管理单个工具」，
     * 但三档切换仍然可用（它不依赖这个入口）。
     */
    onOpenToolSettings: (() -> Unit)? = null,
) {
    // ## 为什么还要在 ON_RESUME 时再试一次（「消息不显示」的兜底）
    //
    // `restoreIfNeeded()` 内部现在只在**成功读到数据**后才置 `restored`，
    // 所以它天然是幂等的、可以反复调用。但唯一的调用点是下面这个
    // `LaunchedEffect(viewModel)`，而它只在**换 ViewModel 实例**时重跑。
    //
    // 漏掉的场景：同一个会话里从设置页返回、从终端 Tab 切回 ——
    // ViewModel 没换，effect 不重跑。如果进入 App 那一刻读库失败过一次
    // （DataStore 未就绪 / 数据库刚迁移完 / 低内存下 Room 打不开），
    // 用户会看到**永久空白的会话**，除非杀进程。
    //
    // 用 lifecycle 的 ON_RESUME 补一次：回到前台时若仍没恢复成功，
    // `restoreIfNeeded` 内部会重新尝试（`restored` 是 false），
    // 成功过一次之后就是一次廉价的早退。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, viewModel) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.restoreIfNeeded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // 必须移除：否则每次重组多挂一个观察者，一次 onResume 触发 N 次恢复。
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(viewModel) { viewModel.restoreIfNeeded() }

    // ── 内存压力 → 自动压缩（本轮新增）─────────────────────────────
    //
    // ## 为什么要在这里转发而不是在 ViewModel 里直接 collect
    //
    // `MemoryMonitor` 在 `:app`（要 ActivityManager），`ChatViewModel` 在
    // `feature:chat`。让 feature 直接依赖 app 层的数据源会把单测绑上
    // Android 上下文。这里只做「把 Flow 交给 ViewModel」这一件事。
    //
    // key 用 `(memoryPressure, viewModel)`：
    //  - `viewModel` 换了（切会话）必须重订阅 —— 否则新会话收不到压力；
    //  - `memoryPressure` 换了（null → 非 null）也要重订阅。
    //
    // 用 `collectLatest` 而不是 `collect`：如果压力在极短时间内连续变化
    // （High → Critical），只需要处理最后一次。用 `collect` 会把中间的
    // High 也走一遍，触发一次不必要的压缩（每次压缩都是一次模型调用）。
    if (memoryPressure != null) {
        LaunchedEffect(memoryPressure, viewModel) {
            memoryPressure.collectLatest { level ->
                viewModel.onMemoryPressureChanged(level)
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()

    // 写设置的短命协程（只在底部设置条的「思考强度」里用一次）。
    //
    // 用 `rememberCoroutineScope` 而不是 `viewModelScope`：ViewModel 不持
    // `SettingsRepository`（见 `ChatScreen.modelName` 的 KDoc），而这条写入
    // 是纯 UI 动作 —— 页面离开时取消即可，不需要跨配置变更存活。
    val settingsScope = rememberCoroutineScope()

    /**
     * 对话显示风格：直接在本组件读取，不从 `PraxisApp` 一路传参。
     *
     * ## 为什么在这里读而不是在 PraxisApp 统一收集
     *
     * `PraxisApp` 里已经收了 `currentTier` / `disabledTools` / `modelSettings` 等
     * 六七个 Flow。风格是**只有这一个 Tab 用**的状态，放上层会让「App 级状态」
     * 与「Tab 局部状态」混在一起，读代码时分不清哪些是全局。而 `ChatTab`
     * 本来就持有 `settingsRepository`，直接收自己需要的那一条即可。
     *
     * ## 为什么用 collectAsStateWithLifecycle 而不是 collectAsState
     *
     * 后退到后台时不必继续读 DataStore。虽然这条 Flow 变更频率极低，
     * 但同一个文件里其它收集点都用 lifecycle 版本，混用会让人以为有理由。
     */
    val styleRaw by settingsRepository.chatDisplayStyle
        .collectAsStateWithLifecycle(initialValue = "cursor")
    val displayStyle = remember(styleRaw) { ChatDisplayStyle.fromStorage(styleRaw) }

    // ── 工具权限档位（底栏「工具」分区，1:1 对齐 Operit）───────────
    //
    // ## 为什么从 DI 取 ToolPermissionState 而不是从 ChatViewModel
    //
    // `ToolPermissionState` 是**全局单例**（`AppModule` 里 `single { }`），
    // 因为一批工具调用要共用同一个档位快照。它不是对话状态的一部分 ——
    // 换个会话不该让档位重置。放在 ChatTab 里读，与 `chatDisplayStyle`
    // 同一口径：这一个页面要用、上层不必知道。
    val toolPermissionState: dev.praxis.core.tool.ToolPermissionState = org.koin.compose.koinInject()
    val currentTier by toolPermissionState.tier.collectAsStateWithLifecycle()

    // ── 流式输出开关（底栏「行为」分区）────────────────────────────
    //
    // 收 DataStore 流而不是自己 remember：这个开关是「持久化偏好」，
    // 杀掉应用再进来必须还是上次的选择。
    val disableStreamOutputRaw by settingsRepository.disableStreamOutput
        .collectAsStateWithLifecycle(initialValue = false)

    // 密钥只读一次用于「有没有配」这一个判断，不保留原文。
    //
    // ## 为什么 key 里必须有 `sessionId`（「切换对话后模型参数不对」的真因之一）
    //
    // 旧写法是 `LaunchedEffect(modelSettings) { apiKeyMasked = maskApiKey(...) }`。
    // `modelSettings` 是**全局** DataStore 流（与具体是哪个会话无关），
    // 所以从会话 A 切到会话 B 时它**不会重新发射** —— 于是：
    //
    // ```
    // 会话 A：配了 key → masked = "sk-…abc"
    // 用户在设置里清空 key → 回聊天（modelSettings 变了，这里刷新）
    // 会话 B：masked 仍是上一次的值 …… 或反过来（B 明明没配却显示已连接）
    // ```
    //
    // 更常见的一种：首次进入 App 时 DataStore 还没读完，`apiKey()` 返回空串，
    // 卡片显示「未完成配置」并且**永远不再刷新**（因为 modelSettings 之后
    // 只在与模型相关的字段变化时才发射）。
    //
    // 把 `sessionId` 放进 key：切会话必重新读一次（密钥本身是全局的，
    // 但「重新对齐一次显示」是必要的，代价只有一次解密）。
    //
    // ## 为什么不用 `collectAsState` 收 apiKey
    //
    // `SettingsRepository` 没有暴露 apiKey 的 Flow（只有挂起函数 + 加密存储）。
    // 加一个 Flow 会把「明文密钥」变成可被任意层订阅的东西，
    // 与 `apiKeyMasked` 只用于「是否已配」这一个判断的定位冲突。
    var apiKeyMasked by remember { mutableStateOf("") }
    LaunchedEffect(modelSettings, sessionId) {
        apiKeyMasked = maskApiKey(settingsRepository.apiKey())
    }

    ChatScreen(
        state = state,
        onInputChange = viewModel::onInputChange,
        onSend = viewModel::send,
        onRetry = viewModel::retry,
        // 变体切换：接到 ViewModel 的 `switchVariant`。
        // 不传的话气泡上不会出现 `‹ 2 / 4 ›` 切换条（见 ChatScreen 的
        // `onSwitchVariant` KDoc），变体数据就白存了。
        onSwitchVariant = viewModel::switchVariant,
        // 停止键接 ViewModel 的 cancel()，不是取消协程 ——
        // 后者不肯收尾（isStreaming 会永久停在 true）。
        onCancel = viewModel::cancel,
        onSendOrEnqueue = viewModel::sendOrEnqueue,
        onQueueMoveUp = viewModel::queueMoveUp,
        onQueueMoveDown = viewModel::queueMoveDown,
        onQueueMoveToTop = viewModel::queueMoveToTop,
        onQueueRemove = viewModel::queueRemove,
        onQueueEdit = viewModel::queueEdit,
        // Operit 面板的「立即发送」与「展开/收起」两项能力。
        // 不传的话面板会整个不显示（见 ChatScreen 里的必需项判断）——
        // 这样可以保证「不传回调就没有假按钮」。
        onQueueSendNow = viewModel::queueSendNow,
        onQueueExpandedChange = viewModel::setQueueExpanded,
        onMessageAction = viewModel::onMessageAction,
        // 一次性提示（如「继续」不适用）由输入栏 4 秒后清掉。
        onNoticeConsumed = viewModel::clearNotice,
        // 消息编辑器（1:1 复刻 Operit `MessageEditor`）。
        //
        // 这四个回调**必须一起传**：`ChatScreen` 里有一道「缺一个就不渲染」
        // 的判断，避免出现「编辑器能打开但保存没反应」的半接线状态。
        onEditingTextChange = viewModel::onEditingTextChange,
        onCancelEditingMessage = viewModel::cancelEditingMessage,
        onSaveEditingMessage = viewModel::saveEditingMessage,
        onResendEditingMessage = viewModel::resendEditingMessage,
        onDismissMessageInfo = viewModel::dismissMessageInfo,
        // 压缩摘要：点分割线看全文（1:1 对齐 Operit 的「历史对话摘要」）。
        onOpenSummaryDetail = viewModel::openSummaryDetail,
        onDismissSummaryDetail = viewModel::dismissSummaryDetail,
        // 复制预览表 / 删除确认 / 清回复目标（1:1 对齐 Operit 的
        // `MessageCopyPreviewBottomSheet`、`showDeleteMessageConfirmDialog`、
        // `clearReplyToMessage`）。
        //
        // 三者都不给默认实现：`ChatScreen` 里全是「回调缺失就不渲染」——
        // 这样「有 UI 但按钮没接线」的半成品状态不可能出现。
        onDismissCopyPreview = viewModel::dismissCopyPreview,
        onConfirmDeleteMessage = viewModel::confirmDeleteMessage,
        onDismissDeleteMessage = viewModel::dismissDeleteMessage,
        onClearReplyTo = viewModel::clearReplyTo,
        // ── 底部设置条（1:1 对齐 Operit `ClassicChatSettingsBar`）────
        //
        // 模型名直接取 `modelSettings`（它的来源是 DataStore 流，改完
        // 立刻回流到这里，不需要额外刷新机制）。
        //
        // ## 为什么是 `activeModel` 而不是 `model`（2026-10-24）
        //
        // `model` 现在是**模型池**（逗号分隔多个，对齐 Operit 的
        // `modelName`）。底部设置条是「当前在用哪个模型」的状态显示，
        // 显示 `a,b,c` 既读不懂也不准确 —— 该显示按 `modelIndex`
        // 取出来的那一个。
        modelName = modelSettings.activeModel,
        // 思考强度写入走 `SettingsRepository.setThinkingEffort` ——
        // 它写的是**全局设置**（`ModelSettings.thinkingEffort`），
        // 下一轮 `readRuntimeConfig()` 就会读到新值，不必重启会话。
        //
        // ## 为什么用 `rememberCoroutineScope` 而不是 ViewModel 里的方法
        //
        // `ChatViewModel` 刻意不持 `SettingsRepository`（见 ChatScreen
        // `modelName` 参数的 KDoc）。写设置是 UI 层的一次性动作，
        // 挂在页面作用域上即可：页面离开时协程自动取消，不会有
        // 「退出页面后还在写设置」的悬挂任务。
        //
        // 失败不额外提示：DataStore 写入失败是磁盘级问题，
        // 下一轮读到的还是旧值，界面自然保持旧档位 —— 不骗人。
        onPickThinkingEffort = { effort ->
            settingsScope.launch {
                settingsRepository.setThinkingEffort(effort)
            }
        },
        // 当前档位：取 DataStore 回流值（改完立刻生效，不等下一轮发送）。
        currentThinkingEffort = modelSettings.thinkingEffort,
        onOpenModelSettings = onOpenModelSettings,
        // ── 底栏「工具」分区 ────────────────────────────────────────
        //
        // 档位直接写全局单例：`ToolPermissionState.setTier` 是同步的，
        // 下一批工具调用取快照时就能读到新值（执行中的那批不受影响，
        // 这正是它用「快照」而非「实时读」的原因）。
        toolTier = currentTier,
        onSetToolTier = { tier -> toolPermissionState.setTier(tier) },
        onOpenToolSettings = onOpenToolSettings,
        // ── 底栏「行为」分区 ────────────────────────────────────────
        //
        // 写盘走 settingsScope（与思考强度同口径：纯 UI 动作，页面离开
        // 即取消，不跨配置变更存活）。
        disableStreamOutput = disableStreamOutputRaw,
        onToggleDisableStreamOutput = { disabled ->
            settingsScope.launch {
                settingsRepository.setDisableStreamOutput(disabled)
            }
        },
        displayStyle = displayStyle,
        // 会话身份：让滚动状态与 item key 能按会话区分（见该参数 KDoc）。
        sessionKey = sessionId,
        header = {
            ModelConfigCard(
                baseUrl = modelSettings.baseUrl,
                model = modelSettings.model,
                apiKeyMasked = apiKeyMasked,
                // 会话列表走侧边栏，模型卡点击进模型设置。
                onClick = {},
            )
        },
    )
}