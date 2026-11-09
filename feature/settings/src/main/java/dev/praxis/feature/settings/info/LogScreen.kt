package dev.praxis.feature.settings.info

import dev.praxis.feature.settings.common.SettingsList
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.praxis.core.data.log.LogEntry
import dev.praxis.core.data.log.LogParser
import dev.praxis.core.data.log.PraxisLogger
import dev.praxis.ui.design.component.AutoFollowScrollLazy
import dev.praxis.ui.design.component.LocalScrollBottomLimit
import dev.praxis.ui.design.component.LocalTopBarInset
import dev.praxis.ui.widgets.MiuixDropdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import dev.praxis.ui.theme.PraxisRadius

/**
 * 日志页。
 *
 * ## 结构参照 apixiaoyuan `feature/log/LogScreen.kt`（481 行）
 *
 * | 上游元素 | 本页处理 |
 * |---|---|
 * | 顶栏 + TabRow（运行/崩溃） | **去掉**：Praxis 还没有崩溃收集器，单 Tab 的 TabRow 是纯装饰 |
 * | 文件下拉（按天轮转） | 保留，用项目已有的 `MiuixDropdown`（同款 SmallTitle 样式下拉） |
 * | 级别色块 + tag + 时间 + 正文 | 保留 |
 * | 长消息折叠（前 5 行 + 展开） | 保留，`RUN_LOG_COLLAPSE_LINES = 5` |
 * | **`AutoFollowScrollLazy`（弹簧跟随）** | **R36 起已接上**，见下方说明 |
 * | 下拉刷新 | 保留，用 miuix `PullToRefresh`（不是 M3 的） |
 * | 保存到文件（CreateDocument） | **不做**：需要 ActivityResult 注册，而设置页跑在 `:app` 的导航容器里，注册时机不稳定；内容可长按选中复制 |
 * | 崩溃 Tab | **不做**：没有崩溃收集器，做空 Tab 等于骗人 |
 *
 * ## ★ 丝滑滚动：这里的来源必须记清（R36 纠正）
 *
 * 本页最初的滚动策略抄的是 **`cn.nizou.sxd` 的 `LogsScreen.kt`**——
 * `LOG_SCROLL_ANIMATION_THRESHOLD = 500`，距离超 500 就 `scrollToItem` 瞬跳，
 * 否则 `animateScrollToItem`。那是**另一个项目**的实现。
 *
 * apixiaoyuan 的做法完全不同：它在 `LogScreen.kt:153` 直接调
 * `AutoFollowScrollLazy(lazyState = listState, itemCount = entries.size)`，
 * 即**弹簧跟随引擎**（`core/design/component/AutoFollowScroll.kt`，k=130 / c=24 / m=1，
 * 内容高度每帧连续推进，而不是「一次滚动动画」）。两者不是同一套东西：
 *
 *  - 阈值方案解决的是「**跳转**多远才不卡」，滚到底之后新内容来了**不会自动跟**；
 *  - 弹簧方案解决的是「**持续跟随**」，新内容不断涌入时速度连贯，不闪帧。
 *
 * 所以 R36 把 `AutoFollowScrollLazy` 接上（自动跟随），同时**保留**滚顶/滚底按钮
 * —— 那两个按钮是「用户主动跳转」，与「自动跟随」不冲突：跟随只管
 * 「用户没干预时是否跟到底」，按钮管「用户想立刻跳过去」。
 *
 * ## 为什么「清空」要单独一个按钮且要二次确认
 *
 * 日志是排障的唯一证据。误触清空 = 这次崩溃再也查不出来。
 * 所以放在最上方独立卡里，且必须先点一次进入确认态再点一次才执行（不用弹窗，
 * 弹窗在这里过重，而二次点击的成本足够拦住误触）。
 */
@Composable
fun LogScreen(
    modifier: Modifier = Modifier,
    /**
     * 网关请求记录（文档 §5.8 的「来源筛选」）。
     *
     * 空列表不是「没有日志」，是「还没加载」或「真的没有请求过」——
     * 界面要按后者说话（见下面的空态文案）。
     */
    gatewayRequests: List<dev.praxis.core.gateway.GatewayRequestRecord> = emptyList(),
    onLoadGatewayRequests: () -> Unit = {},
) {
    val topInset = LocalTopBarInset.current
    val bottomLimit = LocalScrollBottomLimit.current
    val listState = rememberLazyListState()

    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // 每次 +1 触发一次「重新列目录 + 重读文件」。下拉刷新和清空后都改它。
    var generation by remember { mutableIntStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }
    // 一次性提示（清空结果 / 读取失败）。不用 Snackbar：这一页没有 Scaffold 宿主。
    var notice by remember { mutableStateOf<String?>(null) }
    // 来源：0 = App 日志（文本文件），1 = 请求记录（Room 里的结构化记录）。
    //
    // 文档 §5.8 的原话是「全部 / App / 网关 / 请求记录」四档。Praxis 里
    // **「网关」那两档其实是同一份数据**（网关日志就是请求记录的结构化视图，
    // 网关自己不写文本日志），所以这里如实只做两档 —— 摆四档里有两档永远空，
    // 比少两档更让人困惑。
    var source by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(generation) {
        loading = true
        var ok = false
        try {
            // 列目录会先 flush（阻塞等 writer 排空），必须在 IO 线程。
            val result = withContext(Dispatchers.IO) { PraxisLogger.allLogFiles }
            files = result
            val index = if (result.isEmpty()) 0 else selectedIndex.coerceIn(result.indices)
            if (index != selectedIndex) selectedIndex = index

            val file = result.getOrNull(index)
            entries = if (file == null) {
                emptyList()
            } else {
                val text = withContext(Dispatchers.IO) {
                    runCatching { file.readText() }.getOrElse { "读取日志失败：${it.message}" }
                }
                // 解析可能涉及几万行，放 Default（CPU 密集）。
                withContext(Dispatchers.Default) { LogParser.parse(text) }
            }
            ok = true
        } finally {
            loading = false
            if (ok) isRefreshing = false
        }
    }

    val pullState = rememberPullToRefreshState()

    // 自动跟随滚动（对齐 apixiaoyuan `feature/log/LogScreen.kt:153`）。
    //
    // ## 为什么必须放在 Box 外、且传 entries.size
    //
    // 上游原样就是 `AutoFollowScrollLazy(lazyState = listState, itemCount = entries.size)`。
    // 语义：到底自动跟随 / 用户上滑即暂停 / 滑回底部再接管 / 全程弹簧动画。
    //
    // 日志是**追加写**的，打开这一页时人要看尾部 —— 没有自动跟随时，
    // 新日志进来只能靠手动点「回到底部」一个个追，这正是这一页此前的实际体验。
    AutoFollowScrollLazy(lazyState = listState, itemCount = entries.size)

    Box(modifier = modifier.fillMaxSize()) {
        PullToRefresh(
            isRefreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true
                generation++
            },
            pullToRefreshState = pullState,
            // 指示器要落在顶栏下方，否则拉出来的圆圈被顶栏盖住看不见。
            contentPadding = PaddingValues(top = topInset),
            modifier = Modifier.fillMaxSize(),
        ) {
            SettingsList(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                // 日志页自己调了 `AutoFollowScrollLazy`（打开即贴最新），
                // SettingsList 不能同时推进同一个 listState。
                autoFollow = false,
            ) {
                item { Spacer(Modifier.height(12.dp)) }

                item {
                    SmallTitle("来源")
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            MiuixDropdown(
                                items = listOf("App 日志", "请求记录"),
                                selectedIndex = source,
                                onSelectedIndexChange = { index ->
                                    source = index
                                    // 切到请求记录时拉一次：它是 Room 里的结构化数据，
                                    // 不像文本日志那样能直接读文件。
                                    if (index == 1) onLoadGatewayRequests()
                                },
                            )
                            Text(
                                text = if (source == 0) {
                                    "App 自己的运行日志（按天轮转，保留 3 天）"
                                } else {
                                    "网关每次请求一条：成功记用量、失败记原因、取消不记"
                                },
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                }

                if (source == 0) {

                item {
                    SmallTitle("日志文件")
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            if (files.isEmpty()) {
                                Text(
                                    text = if (loading) "正在读取…" else "暂无日志",
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                )
                            } else {
                                MiuixDropdown(
                                    items = files.map { labelOf(it) },
                                    selectedIndex = selectedIndex.coerceIn(files.indices),
                                    onSelectedIndexChange = {
                                        selectedIndex = it
                                        generation++
                                    },
                                )
                            }
                            Text(
                                text = PraxisLogger.logsDir?.absolutePath ?: "日志目录不可用",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                modifier = Modifier.padding(horizontal = 16.dp),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            notice?.let { text ->
                                Text(
                                    text = text,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                            ClearLogsRow(
                                onCleared = { deleted ->
                                    notice = "已清空 $deleted 个日志文件"
                                    // 清空后必须重列：旧的文件路径已经不存在了。
                                    generation++
                                },
                            )
                        }
                    }
                }

                item {
                    SmallTitle("记录")
                    Text(
                        // 这句话是产品承诺，也是排障前提：用户要知道日志里没有什么。
                        text = "共 ${entries.size} 条。日志含时间、级别、来源类与消息正文；" +
                            "不含对话内容与密钥。按天轮转，保留 3 天。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 28.dp),
                    )
                }

                if (entries.isEmpty() && !loading) {
                    // 空态 key：与 `items(entries.size, key = { "entry-$it" })` 同容器，
                    // 无 key 时会与 `entry-0` 抢同一个下标身份（详见
                    // `ChatSessionsPage` 里同名注释的完整分析）。
                    item(key = "__empty_log__") {
                        Text(
                            text = "文件为空",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 24.dp),
                        )
                    }
                }

                items(entries.size, key = { "entry-$it" }) { i ->
                    LogEntryCard(
                        entry = entries[i],
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                    )
                }
                } else {
                    item {
                        SmallTitle("请求记录")
                        Text(
                            text = if (gatewayRequests.isEmpty()) {
                                "还没有请求记录。把模型档案切到「网关」并发一条消息，这里就会出现。"
                            } else {
                                "共 " + gatewayRequests.size + " 条（含连通性测试）。报表页不含测试请求。"
                            },
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 28.dp),
                        )
                    }

                    items(gatewayRequests.size, key = { "req-" + gatewayRequests[it].id }) { i ->
                        GatewayRequestCard(
                            record = gatewayRequests[i],
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }

        LogScrollButtons(
            listState = listState,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                // 浮层按钮不能压在玻璃底栏上，所以要抬到 scrollLimit 之上。
                .padding(end = 16.dp, bottom = bottomLimit + 16.dp),
        )
    }
}

// ---------------------------------------------------------------------------
//  单个日志卡片
// ---------------------------------------------------------------------------

/** 正文超过该行数就折叠为预览 + 展开按钮（上游 `RUN_LOG_COLLAPSE_LINES`）。 */
private const val COLLAPSE_LINES = 5

@Composable
private fun LogEntryCard(entry: LogEntry, modifier: Modifier = Modifier) {
    val lines = remember(entry.message) { entry.message.split('\n') }
    val collapsible = lines.size > COLLAPSE_LINES
    val head = remember(lines) { lines.take(COLLAPSE_LINES).joinToString("\n") }
    val rest = remember(lines) { lines.drop(COLLAPSE_LINES).joinToString("\n") }
    var expanded by remember(entry) { mutableStateOf(false) }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                entry.level?.let { level ->
                    Box(
                        modifier = Modifier
                            .clip(PraxisRadius.TinyShape)
                            .background(levelColor(level))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = level.toString(),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    entry.tag?.let {
                        Text(
                            text = it,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    entry.time?.let {
                        Text(
                            text = it,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                if (collapsible) {
                    // 用 FAB 而不是 IconButton：IconButton 的默认 60dp 高度会把
                    // 卡片头行撑高，而这里只需要一个小箭头。
                    FloatingActionButton(
                        onClick = { expanded = !expanded },
                        containerColor = Color.Transparent,
                        shadowElevation = 0.dp,
                        minWidth = 28.dp,
                        minHeight = 28.dp,
                    ) {
                        Icon(
                            imageVector = if (expanded) {
                                MiuixIcons.Regular.ExpandLess
                            } else {
                                MiuixIcons.Regular.ExpandMore
                            },
                            contentDescription = if (expanded) "收起" else "展开",
                            modifier = Modifier.size(20.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        )
                    }
                }
            }

            if (entry.message.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                // 可长按选中复制：这是「不做保存到文件」的替代出口。
                SelectionContainer {
                    Column {
                        MonoText(if (collapsible) head else entry.message)
                        if (collapsible) {
                            AnimatedVisibility(
                                visible = expanded,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut(),
                            ) {
                                MonoText(rest)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonoText(text: String) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurface,
    )
}

/**
 * 级别颜色。
 *
 * 这里是**唯一**允许硬编码颜色的地方：级别配色是「交通灯语义」
 * （错误红、警告橙、信息绿），不随主题变化 —— 换了主题仍然必须是红/橙/绿，
 * 否则「一眼找到错误」这件事就不成立了。上游同样硬编码。
 */
private fun levelColor(level: Char): Color = when (level) {
    'E', 'F', 'A' -> Color(0xFFD32F2F)
    'W' -> Color(0xFFF57C00)
    'I' -> Color(0xFF388E3C)
    'D' -> Color(0xFF1976D2)
    'V' -> Color(0xFF757575)
    else -> Color(0xFF9E9E9E)
}

// ---------------------------------------------------------------------------
//  清空（二次确认，不弹窗）
// ---------------------------------------------------------------------------

@Composable
private fun ClearLogsRow(onCleared: (Int) -> Unit) {
    var armed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(1f))
        TextButton(
            text = if (armed) "再点一次确认清空" else "清空日志",
            onClick = {
                if (!armed) {
                    armed = true
                    return@TextButton
                }
                armed = false
                // 删文件是阻塞 IO（要 flush 队列），不能在主线程。
                scope.launch {
                    val deleted = withContext(Dispatchers.IO) { PraxisLogger.clearLogs() }
                    onCleared(deleted)
                }
            },
        )
    }
    // 离开这一页时解除待确认状态由组合销毁自动完成（remember 不跨页保留）。
    if (armed) {
        LaunchedEffect(armed) {
            kotlinx.coroutines.delay(4000)
            armed = false
        }
    }
}

// ---------------------------------------------------------------------------
//  滚顶 / 滚底
// ---------------------------------------------------------------------------

/**
 * 滚动距离超过该条数时用瞬时跳转而非动画（长列表动画会卡顿）。
 *
 * ## 出处必须写清：这是 `cn.nizou.sxd` 的值，不是 apixiaoyuan 的
 *
 * 值 500 来自 **`cn.nizou.sxd/ui/settings/LogsScreen.kt:689`**（老叟那份日志页）。
 * apixiaoyuan 的日志页里**没有**这个常量，也没有这对滚顶/滚底 FAB ——
 * 它靠 `AutoFollowScrollLazy` 的弹簧引擎跟到底。
 *
 * 这里仍保留 500：它解决的是「**手动跳转**多远的距离该瞬跳」，与自动跟随无关。
 * 两个按钮是用户主动操作，长距离动画（几千项）会转很久且中途卡顿，
 * 瞬跳反而是对的。**不要把这两个概念混成一套**——上一版就是这么错的。
 */
private const val LOG_SCROLL_ANIMATION_THRESHOLD = 500

@Composable
private fun LogScrollButtons(
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val showScrollToTop by remember {
        derivedStateOf { listState.canScrollBackward }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        AnimatedVisibility(
            visible = showScrollToTop,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ScrollFab(
                up = true,
                onClick = {
                    scope.launch {
                        if (listState.firstVisibleItemIndex > LOG_SCROLL_ANIMATION_THRESHOLD) {
                            listState.scrollToItem(0)
                        } else {
                            listState.animateScrollToItem(0)
                        }
                    }
                },
            )
        }
        ScrollFab(
            up = false,
            onClick = {
                scope.launch {
                    val end = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                    if (end - listState.firstVisibleItemIndex > LOG_SCROLL_ANIMATION_THRESHOLD) {
                        listState.scrollToItem(end)
                    } else {
                        listState.animateScrollToItem(end)
                    }
                }
            },
        )
    }
}

@Composable
private fun ScrollFab(up: Boolean, onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = MiuixTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 4.dp,
        minWidth = 40.dp,
        minHeight = 40.dp,
    ) {
        Icon(
            // ExpandLess/ExpandMore 是上下箭头，语义上就是「滚到顶/滚到底」；
            // miuix 图标集里没有双箭头。
            imageVector = if (up) MiuixIcons.Regular.ExpandLess else MiuixIcons.Regular.ExpandMore,
            contentDescription = if (up) "回到顶部" else "回到底部",
            modifier = Modifier.size(20.dp),
            tint = MiuixTheme.colorScheme.onSurface,
        )
    }
}

// ---------------------------------------------------------------------------
//  工具
// ---------------------------------------------------------------------------

/** 下拉项标签：`2026-10-21 · 128.4 KiB`。 */
private fun labelOf(file: File): String {
    val name = file.name.removePrefix(dev.praxis.core.data.log.LogLine.FILE_PREFIX)
        .removeSuffix(".log")
    return "$name  ·  ${formatBytes(file.length())}"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format("%.1f %s", value, units[unit])
}

/**
 * 一条请求记录（日志页「请求记录」视图，文档 §5.8）。
 *
 * ## 为什么不显示原始报文
 *
 * 文档 839 行提到「debug 模式（原始报文）」，但 Praxis 没保存请求体/响应体 ——
 * 那要另存一份（而且可能很大、含用户内容）。这里只显示结构化字段：
 * 时间 / 模型 / 家 / 状态 / 耗时 / 用量 / 尝试次数。
 */
@Composable
private fun GatewayRequestCard(
    record: dev.praxis.core.gateway.GatewayRequestRecord,
    modifier: Modifier = Modifier,
) {
    val time = remember(record.ts) {
        java.time.Instant.ofEpochMilli(record.ts)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
    }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = record.model,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (record.success) "成功" else "失败",
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (record.success) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.error
                    },
                )
            }
            Text(
                text = buildString {
                    append(time)
                    record.providerId?.let { append(" · ").append(it) }
                    append(" · ").append(record.durationMs).append("ms")
                    if (record.totalTokens > 0) {
                        append(" · ").append(record.totalTokens).append(" tokens")
                    }
                    if (record.attempts > 1) append(" · 试了 ").append(record.attempts).append(" 次")
                    if (record.isTest) append(" · 连通性测试")
                },
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp),
            )
            record.error?.let { message ->
                Text(
                    text = message,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
