package dev.praxis.ui.features

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.domain.model.FeatureBlock
import dev.praxis.domain.model.PraxisDomain
import dev.praxis.domain.model.blockOf
import dev.praxis.domain.model.blocks
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.praxis.core.data.settings.SettingsRepository
import dev.praxis.feature.chat.ChatListScreen
import dev.praxis.feature.chat.ChatPersistence
import dev.praxis.feature.chat.ChatScreen
import dev.praxis.feature.chat.SessionItem
import dev.praxis.feature.chat.ChatViewModel
import dev.praxis.feature.settings.SettingsEntry
import dev.praxis.feature.settings.SettingsHost
import dev.praxis.ui.design.component.LocalScrollBottomLimit
import dev.praxis.ui.design.component.LocalTopBarInset
import dev.praxis.ui.navigation.PraxisRoute
import top.yukonga.miuix.kmp.basic.Card

/**
 * 功能宿主：渲染当前路由。
 *
 * Phase 1 只做「域首页 → 功能块列表 → 占位子页」这一层。每个功能块的实现在 Phase 2
 * 按 Operit 的 features 逐块移植；这里不写假数据、不堆空壳页面。
 *
 * 入参用 [PraxisRoute] 而不是 domain+detailId 两条：子页标题直接取自路由，
 * 进程重建后标题与内容由同一份状态决定，不会出现标题和内容对不上的情况。
 */
@Composable
fun FeatureHost(
    route: PraxisRoute,
    settingsRepository: SettingsRepository,
    chatViewModel: ChatViewModel,
    chatPersistence: ChatPersistence,
    /** 记忆存储。设置域的记忆页要用；由 :app 注入。 */
    memoryStore: dev.praxis.core.memory.MemoryStore,
    /** 工作流引擎。设置域的工作流页要用；由 :app 注入。 */
    workflowEngine: dev.praxis.core.workflow.WorkflowEngine,
    onOpenSession: (String) -> Unit,
    toolSpecs: List<dev.praxis.core.tool.ToolSpec>,
    currentTier: dev.praxis.core.tool.PermissionTier,
    isToolEnabled: (String) -> Boolean,
    onTierChange: (dev.praxis.core.tool.PermissionTier) -> Unit,
    onToolToggle: (String, Boolean) -> Unit,
    versionName: String,
    versionCode: Int,
    onOpen: (FeatureBlock) -> Unit,
    onSettingsEntry: (SettingsEntry) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (route) {
        is PraxisRoute.Home -> when (route.domain) {
            // 对话域首页就是聊天页：先选「会话/输入/历史」三个功能块再进子页，
            // 多一层无意义的点击。与设置域一样按域分发。
            PraxisDomain.CHAT -> ChatRoute(chatViewModel, modifier)

            // 终端域首页就是终端页本身（与对话域同理：先列功能块再进子页是多一层点击）。
            PraxisDomain.TERMINAL -> TerminalRoute(modifier)

            else -> DomainHome(
                domain = route.domain,
                onOpen = onOpen,
                modifier = modifier,
            )
        }

        is PraxisRoute.Detail -> when (route.domain) {
            // 设置域已有多页结构，交给 feature:settings 自己的入口分发；
            // 其余域仍是「域首页 → 功能块占位」这一层。
            PraxisDomain.SETTINGS -> SettingsHost(
                entryKey = route.arg,
                repository = settingsRepository,
                versionName = versionName,
                versionCode = versionCode,
                toolSpecs = toolSpecs,
                currentTier = currentTier,
                isToolEnabled = isToolEnabled,
                onTierChange = onTierChange,
                onToolToggle = onToolToggle,
                onOpen = onSettingsEntry,
                memoryStore = memoryStore,
                workflowEngine = workflowEngine,
                modifier = modifier,
            )

            // 对话域的子页：list = 会话列表，其余交给占位页。
            PraxisDomain.CHAT -> if (route.arg == "list") {
                ChatListRoute(
                    persistence = chatPersistence,
                    onOpenSession = onOpenSession,
                    modifier = modifier,
                )
            } else {
                FeatureDetail(
                    domain = route.domain,
                    blockId = route.arg,
                    title = route.title,
                    onBack = onBack,
                    modifier = modifier,
                )
            }

            else -> FeatureDetail(
                domain = route.domain,
                blockId = route.arg,
                title = route.title,
                onBack = onBack,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun DomainHome(
    domain: PraxisDomain,
    onOpen: (FeatureBlock) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 顶栏高度进 contentPadding.top（不是容器 padding），内容才能滚到顶栏下方，
    // 让 AppScaffold 的 progressiveTextureBlur 采到真实内容，渐变模糊才生效。
    // 底栏高度进 contentPadding.bottom，内容仍可滑到底栏下方（折射成立），
    // 但最后一项能滚到不被底栏遮挡的位置。
    val topInset = LocalTopBarInset.current
    val scrollLimit = LocalScrollBottomLimit.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = topInset + 16.dp,
            bottom = scrollLimit + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(bottom = 8.dp)) {
                Text(text = domain.title, style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = domain.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        items(domain.blocks, key = { it.id }) { block ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onOpen(block) },
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(text = block.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = block.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureDetail(
    domain: PraxisDomain,
    blockId: String,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 标题优先取域内清单里的定义；路由里带来的 title 只是兜底（清单被改名时仍可显示）。
    val block = domain.blockOf(blockId)

    val topInset = LocalTopBarInset.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .padding(top = topInset + 20.dp, bottom = 40.dp),
    ) {
        TextButton(onClick = onBack) {
            Text(text = "← 返回${domain.title}", style = MaterialTheme.typography.labelLarge)
        }
        Text(
            text = block?.title ?: title.ifBlank { blockId },
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "${domain.title} / ${blockId}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = block?.description ?: "Phase 2 按 Operit 对应 features 移植此块实现。",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}

/** 聊天域入口：把 ViewModel 的状态与回调接到纯 UI 上。 */
@Composable
private fun ChatRoute(viewModel: ChatViewModel, modifier: Modifier) {
    // 首次进入聊天页时从数据库恢复历史（幂等，重复调用不会重复加载）。
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.restoreIfNeeded() }

    val state by viewModel.state.collectAsStateWithLifecycle()
    ChatScreen(
        state = state,
        onInputChange = viewModel::onInputChange,
        onSend = viewModel::send,
        onRetry = viewModel::retry,
        modifier = modifier,
    )
}

/** 会话列表路由：加载 / 新建 / 打开 / 删除。 */
@Composable
private fun ChatListRoute(
    persistence: ChatPersistence,
    onOpenSession: (String) -> Unit,
    modifier: Modifier,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val sessions = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<List<SessionItem>>(emptyList())
    }
    val reloadTick = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableIntStateOf(0)
    }

    androidx.compose.runtime.LaunchedEffect(reloadTick.intValue) {
        sessions.value = runCatching { persistence.listSessions() }.getOrDefault(emptyList())
    }

    ChatListScreen(
        sessions = sessions.value,
        onCreate = { onOpenSession(newSessionId()) },
        onOpen = onOpenSession,
        onDelete = { id ->
            scope.launch {
                runCatching { persistence.deleteSession(id) }
                reloadTick.intValue++
            }
        },
        modifier = modifier,
    )
}

/** 终端域入口：把 ViewModel 接到纯 UI 上。 */
@Composable
private fun TerminalRoute(modifier: Modifier) {
    // 换域回来时必须拿到同一个 VM，否则已输出的内容全丢；
    // 用固定 key 让它在设置页/聊天页之间来回切也不重建。
    val viewModel: dev.praxis.feature.terminal.TerminalViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel(key = "terminal") {
            dev.praxis.feature.terminal.TerminalViewModel(
                runner = dev.praxis.data.terminal.ShellCommandRunner(),
            )
        }
    val state by viewModel.state.collectAsStateWithLifecycle()

    dev.praxis.feature.terminal.TerminalScreen(
        state = state,
        onSubmit = viewModel::submit,
        onInputChange = viewModel::onInputChange,
        modifier = modifier,
    )
}

/** 新会话 id：时间戳足够唯一，且天然有序，便于排查。 */
private fun newSessionId(): String = "s${System.currentTimeMillis()}"
