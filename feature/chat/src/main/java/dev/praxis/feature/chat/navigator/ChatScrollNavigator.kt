package dev.praxis.feature.chat.navigator

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 导航浮条的自动隐藏延时（与上游 `NAVIGATOR_HIDE_DELAY_MS` 一致）。 */
private const val NAVIGATOR_HIDE_DELAY_MS = 1200L

/** 搜索防抖（与上游 `delay(180)` 一致）。 */
private const val LOCATOR_SEARCH_DEBOUNCE_MS = 180L

/** 一次视口探测快照（避免在 `snapshotFlow` 里 read 多次不相关字段）。 */
private data class NavigatorSnapshot(
    val centeredMessageIndex: Int?,
    val isScrollInProgress: Boolean,
)

/**
 * 消息导航浮条 + 定位器（1:1 复刻 Operit `ChatScrollNavigator`，1295 行）。
 *
 * ## 为什么这一块必须「破例不用 miuix」
 *
 * 它是**手绘控件**：一个 20dp 宽 58dp 高的胶囊，里面用 `Canvas` 画一条
 * 1.5dp 的竖线 + 一个 3dp 圆点（圆点在整段对话中的相对位置），右侧用 `Path`
 * 画一个三角形指向聊天区。miuix 没有对应控件，用 miuix 的 Icon 拼不出
 * 「竖线 + 随进度移动的圆点」，硬拼出来的观感与上游差很远。
 *
 * ## 什么时候出现（这是最容易做错的一点）
 *
 * 上游只在**用户自己拖拽/滚动**时浮出，停止后 `delay(1200)` 再隐藏。
 * 两个常见错误实现：
 *  1. 「一直显示」 —— 它贴着屏幕右缘，会长期遮住消息正文；
 *  2. 「只认 `isScrollInProgress`」 —— 手指按住不动时 `isScrollInProgress`
 *     仍为 true，但松手后立即变 false，浮条会在松手瞬间消失，用户来不及点。
 *
 * 所以要用 `interactionSource.collectIsDraggedAsState()`（手指真按着）
 * 与 `isScrollInProgress`（还在惯性滑动）两个条件分别判，都停了才计时。
 *
 * ## 三个 `rememberUpdatedState` 是干什么的
 *
 * `LaunchedEffect` 里读到的外部变量是「启动时的快照」。若直接读
 * `isDragged` 的委托值，协程会一直看到旧值 —— 表现为「拖完了浮条不消失」。
 * 用 `rememberUpdatedState` 取最新值。上游同样处理。
 *
 * @param minVisibleIndex 消息项之前的固定 item 数（有 header 时为 1）
 * @param onJumpToMessage 点击定位器某条后回调（参数是**消息下标**）
 */
@Composable
fun ChatScrollNavigator(
    previews: List<ChatMessageLocatorPreview>,
    listState: LazyListState,
    minVisibleIndex: Int,
    onJumpToMessage: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 见下方 [listIndexToPreviewIndex] 的说明。默认 null = 恒等映射（旧行为）。 */
    listIndexToPreviewIndex: ((Int) -> Int?)? = null,
) {
    // 只有一条消息时不显示：没有任何东西可跳。
    if (previews.size <= 1) return

    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    val currentIsDragged by rememberUpdatedState(isDragged)
    var showNavigatorChip by remember { mutableStateOf(false) }
    var userScrollSessionActive by remember { mutableStateOf(false) }
    var showLocatorDialog by remember { mutableStateOf(false) }
    var currentMessageIndex by remember(previews.size) {
        mutableStateOf(previews.lastIndex.takeIf { it >= 0 })
    }

    // 手指按下：立刻浮出，并标记「这一轮是用户主动滚动」。
    LaunchedEffect(isDragged) {
        if (isDragged) {
            userScrollSessionActive = true
            showNavigatorChip = true
        }
    }

    LaunchedEffect(listState, previews.size, minVisibleIndex, listIndexToPreviewIndex) {
        snapshotFlow {
            // 列表真实 item 数（含 header、含工具气泡）。
            //
            // ## 为什么要从 layoutInfo 取而不是 `previews.size + 常量`
            //
            // 组件不知道「有几个工具气泡」—— 那属于调用方的数据。
            // `layoutInfo.totalItemsCount` 是 LazyColumn 自己算出来的真实值，
            // 拿它最直接，也不会因为调用方漏传参数而漂移。
            //
            // 首帧布局前 `totalItemsCount` 是 0，此时 `resolveCentered*`
            // 会因 `totalMessageCount <= 0` 直接返回 null —— 正是想要的行为。
            val listItemCount = listState.layoutInfo.totalItemsCount
            NavigatorSnapshot(
                centeredMessageIndex = resolveCenteredFromListState(
                    listState = listState,
                    minVisibleIndex = minVisibleIndex,
                    // ## 这两个计数的语义差别（2026-10-24 修）
                    //
                    // `resolveCenteredFromListState` 内部拿 `LazyListItemInfo.index`
                    // 直接和 `visibleMessageCount` 比较（见
                    // `ChatMessageLocator.kt:160` 的 `filter { it.index in 0 until visibleMessageCount }`），
                    // 所以这两个参数**必须填「列表里真实 item 数」**，不是 previews 数。
                    //
                    // 定位器现在排除了工具气泡，`previews.size < 列表 item 数`。
                    // 若这里继续传 `previews.size`，会有两个错：
                    //  1. 靠后的可见项被 `filter` 掉（下标超出 previews 长度）；
                    //  2. 返回的 index 是列表下标，却被当成 previews 下标用。
                    //
                    // 传列表 item 数 → 得到「以列表下标计的居中项」，
                    // 再用 `listIndexToPreviewIndex` 翻译成预览下标，两步都不含糊。
                    visibleMessageCount = listItemCount,
                    totalMessageCount = listItemCount,
                ),
                isScrollInProgress = listState.isScrollInProgress,
            )
        }.collectLatest { snapshot ->
            // 列表下标 → 预览下标。落在工具气泡上（映射返回 null）时
            // **不动** `currentMessageIndex`：工具不参与计数，
            // 让浮条继续显示上一个真正的消息序号，比跳成空白合理。
            snapshot.centeredMessageIndex?.let { listIndex ->
                val mapped = listIndexToPreviewIndex?.invoke(listIndex) ?: listIndex
                if (mapped != null) currentMessageIndex = mapped
            }
            // 用户没滚动过：浮条不该因为「程序滚动/首次布局」而冒出来。
            if (!userScrollSessionActive) return@collectLatest
            if (snapshot.isScrollInProgress || currentIsDragged) {
                showNavigatorChip = true
                return@collectLatest
            }
            delay(NAVIGATOR_HIDE_DELAY_MS)
            // 延时结束后再确认一次：若这期间又开始滑了就不隐藏。
            if (!listState.isScrollInProgress && !currentIsDragged) {
                showNavigatorChip = false
                userScrollSessionActive = false
            }
        }
    }

    val activeMessageIndex = currentMessageIndex

    AnimatedVisibility(
        visible = showNavigatorChip && activeMessageIndex != null,
        modifier = modifier,
        // 入场向右滑出 + 淡入（上游 180ms）；退场 120ms 更快，
        // 因为退场时用户已经开始读内容，慢动画会拖视线。
        enter = fadeIn(animationSpec = tween(180)) +
            slideInHorizontally(initialOffsetX = { it / 2 }),
        exit = fadeOut(animationSpec = tween(120)) +
            slideOutHorizontally(targetOffsetX = { it / 2 }),
    ) {
        val bubbleColor = MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
        val anchorLineColor = MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.55f)
        val anchorDotColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.92f)
        val navigatorBorderColor = MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.12f)
        // 左圆右方的胶囊：贴在屏幕右缘，左侧圆角与内容区过渡更自然。
        val navigatorShape = RoundedCornerShape(
            topStart = 14.dp,
            bottomStart = 14.dp,
            topEnd = 10.dp,
            bottomEnd = 10.dp,
        )
        val progressTotalCount = previews.size
        val progressIndex = activeMessageIndex ?: 0
        val progress = if (progressTotalCount <= 1) {
            1f
        } else {
            (progressIndex.toFloat() / (progressTotalCount - 1).toFloat()).coerceIn(0f, 1f)
        }

        Row(
            modifier = Modifier.clickable {
                showLocatorDialog = true
                // 打开面板就收起浮条：两者同时在场会互相遮挡。
                showNavigatorChip = false
                userScrollSessionActive = false
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .clip(navigatorShape)
                    .background(bubbleColor)
                    .border(1.dp, navigatorBorderColor, navigatorShape),
            ) {
                Box(
                    modifier = Modifier
                        .width(20.dp)
                        .height(58.dp)
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.size(width = 8.dp, height = 34.dp)) {
                        val centerX = size.width / 2f
                        val topY = 2.dp.toPx()
                        val bottomY = size.height - 2.dp.toPx()
                        val dotCenterY = topY + (bottomY - topY) * progress
                        drawLine(
                            color = anchorLineColor,
                            start = Offset(centerX, topY),
                            end = Offset(centerX, bottomY),
                            strokeWidth = 1.5.dp.toPx(),
                        )
                        drawCircle(
                            color = anchorDotColor,
                            radius = 3.dp.toPx(),
                            center = Offset(centerX, dotCenterY),
                        )
                    }
                }
            }

            // 指向聊天区的三角：颜色与胶囊一致，左移 1dp 消除接缝。
            Canvas(
                modifier = Modifier
                    .offset(x = (-1).dp)
                    .size(width = 9.dp, height = 18.dp),
            ) {
                val arrowPath = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, size.height / 2f)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(path = arrowPath, color = bubbleColor)
            }
        }
    }

    if (showLocatorDialog && activeMessageIndex != null) {
        ChatMessageLocatorDialog(
            previews = previews,
            currentMessageIndex = activeMessageIndex,
            onDismiss = { showLocatorDialog = false },
            onJumpToMessage = { targetIndex ->
                showLocatorDialog = false
                onJumpToMessage(targetIndex)
            },
        )
    }
}

/**
 * 从 [LazyListState] 求「屏幕中心那条」的消息下标。
 *
 * 把 `LazyListItemInfo` 投影成 [VisibleItem] 再交给纯函数：
 * 这样「取最近项」的逻辑可以脱离 Compose 单测。
 */
private fun resolveCenteredFromListState(
    listState: LazyListState,
    minVisibleIndex: Int,
    visibleMessageCount: Int,
    totalMessageCount: Int,
): Int? {
    val info = listState.layoutInfo
    if (info.visibleItemsInfo.isEmpty()) return null
    return resolveCenteredMessageIndex(
        visibleItems = info.visibleItemsInfo.map {
            VisibleItem(index = it.index, offset = it.offset, size = it.size)
        },
        viewportStartOffset = info.viewportStartOffset,
        viewportEndOffset = info.viewportEndOffset,
        minVisibleIndex = minVisibleIndex,
        visibleMessageCount = visibleMessageCount,
        totalMessageCount = totalMessageCount,
    )
}

/**
 * 消息定位器对话框（1:1 复刻上游 `ChatMessageLocatorDialog` + `ChatMessageLocatorRow`）。
 *
 * ## 尺寸照抄上游
 *
 * 宽 `0.92f`、高上限 560dp、列表高上限 420dp、圆角 24dp、
 * 容器 `surface.copy(alpha = 0.95f)` + `tonalElevation 6dp` + `shadowElevation 8dp`。
 *
 * 这几项都不是随手写的：宽 0.92 保证左右留出可点外部关闭的余量；
 * 560dp 让「搜索框 + 提示 + 列表」在常见手机上不超出屏幕；
 * 列表 420dp 是「手机上能一屏扫到约 8 条」的高度。
 *
 * ## 初始滚动位置是 `(currentIndex - 2)` 而不是 `currentIndex`
 *
 * 直接把当前消息顶到第一条，用户看不到它前面的上下文，也就无法判断
 * 「我是不是跳对了地方」。往上留 2 条，当前条落在第三行 ——
 * 有上下文、但也没被挤到屏幕下方。
 *
 * ## 为什么搜索框只在「有入口」时才能用
 *
 * Praxis 的消息历史是内存里的 `state.bubbles`（可能已被 `HISTORY_WINDOW`
 * 裁剪），没有 Operit 那种「按关键词回库查」的端口。所以这里的搜索
 * **只在当前已载入的气泡里过滤**，并在文案上写清楚范围。
 * 假装能搜全库而实际只搜到一部分，比不提供搜索更糟。
 */
@Composable
private fun ChatMessageLocatorDialog(
    previews: List<ChatMessageLocatorPreview>,
    currentMessageIndex: Int,
    onDismiss: () -> Unit,
    onJumpToMessage: (Int) -> Unit,
) {
    // `(current - 2).coerceAtLeast(0)`：见上方 KDoc。
    val initialIndex = (currentMessageIndex - 2).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    var searchQuery by remember { mutableStateOf("") }

    // 过滤只按「归一化后的 substring」，与预览用的是同一套归一化，
    // 否则「预览里看着有这几个字，搜不到」会很莫名其妙。
    val normalizedQuery = normalizeMessageSearchText(searchQuery)
    val indexed = previews.mapIndexed { index, preview -> index to preview }
    val filtered = if (normalizedQuery.isEmpty()) {
        indexed
    } else {
        indexed.filter { (_, preview) ->
            preview.previewContent.contains(normalizedQuery, ignoreCase = true)
        }
    }
    val maxMessageLength = remember(previews) {
        previews.maxOfOrNull { it.contentLength }?.coerceAtLeast(1) ?: 1
    }

    // 搜索词变化后重新定位：命中项的第一条应滚进视野。
    LaunchedEffect(normalizedQuery, filtered.size) {
        if (normalizedQuery.isEmpty() || filtered.isEmpty()) return@LaunchedEffect
        delay(LOCATOR_SEARCH_DEBOUNCE_MS)
        listState.scrollToItem(0)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 560.dp),
            shape = RoundedCornerShape(24.dp),
            color = MiuixTheme.colorScheme.surface.copy(alpha = 0.95f),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ── 标题 + 当前进度 ────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "跳转到消息",
                            style = MaterialTheme.typography.titleMedium,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "当前第 ${currentMessageIndex + 1} / ${previews.size} 条",
                            style = MaterialTheme.typography.bodySmall,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text(text = "关闭")
                    }
                }

                // ── 搜索 ──────────────────────────────────────────
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(text = "在当前对话里搜索") },
                    placeholder = { Text(text = "输入关键词") },
                )

                if (normalizedQuery.isEmpty()) {
                    Text(
                        text = "点任意一条跳过去",
                        style = MaterialTheme.typography.bodySmall,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else if (filtered.isNotEmpty()) {
                    Text(
                        text = "找到 ${filtered.size} 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }

                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "没有匹配的消息",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(
                            items = filtered,
                            key = { _, entry ->
                                // 时间戳理论上唯一（见 `nextTimestamp`），
                                // 但仍拼上下标：哪怕是异常数据也不该让 LazyColumn 崩。
                                "${entry.first}_${entry.second.timestamp}"
                            },
                        ) { _, entry ->
                            val (index, preview) = entry
                            ChatMessageLocatorRow(
                                index = index,
                                preview = preview,
                                isCurrent = index == currentMessageIndex,
                                maxMessageLength = maxMessageLength,
                                searchQuery = searchQuery,
                                onClick = { onJumpToMessage(index) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 定位器里的一行（1:1 复刻上游 `ChatMessageLocatorRow`）。
 *
 * 版式：左侧「序号 + 发送者」90dp 固定宽 → 中间条形预览 → 右侧字符数。
 * 左侧固定宽是为了让所有行的条形起点对齐 —— 不固定的话序号位数不同
 * （9 vs 10）会让条形左右错位，扫读时很难受。
 */
@Composable
private fun ChatMessageLocatorRow(
    index: Int,
    preview: ChatMessageLocatorPreview,
    isCurrent: Boolean,
    maxMessageLength: Int,
    searchQuery: String,
    onClick: () -> Unit,
) {
    val fillColor = when (preview.sender) {
        "user" -> MiuixTheme.colorScheme.primaryContainer
        "ai" -> MiuixTheme.colorScheme.tertiaryContainer
        "tool" -> MiuixTheme.colorScheme.secondaryContainer
        else -> MiuixTheme.colorScheme.surfaceVariant
    }
    val containerColor = if (isCurrent) {
        // 当前条用 secondaryContainer + 0.72 alpha：比选中态淡，比普通行重，
        // 既能认出来又不会盖掉正文。
        MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
    } else {
        MiuixTheme.colorScheme.surfaceContainer
    }
    val borderColor = if (isCurrent) {
        MiuixTheme.colorScheme.primary.copy(alpha = 0.4f)
    } else {
        MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.22f)
    }

    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = BorderStroke(width = 1.dp, color = borderColor),
        tonalElevation = if (isCurrent) 2.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左：序号 + 发送者
            Column(
                modifier = Modifier.width(90.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = senderLabel(preview.sender),
                    style = MaterialTheme.typography.labelSmall,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            // 中：条形预览
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.42f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(messageBarFraction(preview.contentLength, maxMessageLength))
                        .clip(RoundedCornerShape(12.dp))
                        .background(fillColor.copy(alpha = 0.9f)),
                )
                Text(
                    text = buildMessagePreview(preview, searchQuery),
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(horizontal = 10.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                )
            }

            // 右：原文字符数
            Text(
                text = preview.contentLength.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** 发送者可读名（Praxis 只有四种角色）。 */
private fun senderLabel(sender: String): String = when (sender) {
    "user" -> "我"
    "ai" -> "AI"
    "tool" -> "工具"
    "error" -> "错误"
    else -> "其他"
}
