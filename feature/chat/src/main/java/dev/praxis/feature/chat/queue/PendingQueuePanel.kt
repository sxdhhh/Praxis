package dev.praxis.feature.chat.queue
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.Image
import kotlinx.coroutines.CoroutineScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.praxis.feature.chat.QueueItemKind
import dev.praxis.feature.chat.QueuedMessage
import dev.praxis.ui.theme.PraxisMotion
import dev.praxis.ui.theme.PraxisRadius
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 待发送队列面板 —— 1:1 复刻 Operit `PendingMessageQueuePanel`（176 行）。
 *
 * ## 这一处**破例不用 miuix**（用户 2026-10-22 明确要求）
 *
 * 用户原话：「对话框和待发送队列以及跳转到消息直接抄袭 operit 吧，
 * 他实现更加完整，你直接 1 比 1 复刻控件的话就破例不用 miuix 吧」。
 *
 * 所以版式逐项对齐上游：
 *
 * ```
 * Card(containerColor = surfaceVariant, elevation 0)
 *  └ Column
 *     ├ Row(clickable → 折叠) 标题 labelLarge weight(1f) + 箭头图标
 *     └ if (expanded) LazyColumn(heightIn max 220dp,
 *                               padding(start 8, end 8, bottom 8),
 *                               spacedBy 8dp)
 *        └ 每条 Card(shape 12dp, containerColor = surface, elevation 0)
 *           └ Row(padding h6 v2)：左侧编辑 / 正文 / 右侧操作组
 * ```
 *
 * 换 miuix 会得到完全不同的行高与内边距（miuix 的 Card 自带一套内边距、
 * 圆角与阴影策略），「1:1」就不成立了。
 *
 * 代价说清楚：它和 Praxis 其它页面（miuix）观感不同。这是用户点名的例外，
 * 不是遗漏 —— 后续若要统一，应当整体重做版式，而不是在这里偷偷换控件。
 *
 * ## 与上游的唯一差异：排序组
 *
 * 上游是纯 FIFO（`PendingQueueMessageItem` 只有 id + text），改顺序只能
 * 删掉重打。Praxis 的 [dev.praxis.feature.chat.PendingQueueOps] 支持
 * 上移 / 下移 / 置顶，用户也明确要求过「可以调整发送顺序」。
 *
 * 这三个图标**只在队列多于一条时出现**：只有一条时排序没有意义，
 * 显示出来就是三个点了没反应的假按钮。于是「单条队列」的观感与上游
 * 完全一致，「多条队列」多三个 26dp 图标。
 */
@Composable
fun PendingQueuePanel(
    messages: List<QueuedMessage>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onEdit: (Long) -> Unit,
    onSendNow: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onMoveUp: (Long) -> Unit,
    onMoveDown: (Long) -> Unit,
    onMoveToTop: (Long) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MiuixTheme.colorScheme.surfaceVariant,
    itemColor: Color = MiuixTheme.colorScheme.surface,
    /** 拖拽结束后的完整 id 顺序（null = 未发生拖拽）。 */
    onReorder: ((List<Long>) -> Unit)? = null,
    /** 「全部发送」；null 表示不支持（例如正在生成中，不允许一次清空队列）。 */
    onSendAll: (() -> Unit)? = null,
    /** 「清空队列」；null 表示不支持。 */
    onClearAll: (() -> Unit)? = null,
    /** 是否正在生成（用于标题副文案与拖拽可用性）。 */
    busy: Boolean = false,
) {
    // 空队列不占屏幕（与上游一致）：否则输入框上方永远挂着一条空标题。
    if (messages.isEmpty()) return

    // 触觉反馈：入队、拖拽落位、清空这类「东西被移动了」的动作要给手感。
    // 没有它时用户只能靠眼睛确认，而排队面板是在输入区上方的次要区域。
    val haptics = LocalHapticFeedback.current

    val actionIconTint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.9f)
    // 排序只在多条时有意义。
    val showOrdering = messages.size > 1

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── 标题行：整行可点，切换展开 ─────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "待发送 ${messages.size} 条",
                        style = MaterialTheme.typography.labelLarge,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    // ## 为什么写「前一轮生成中」而不是「预计还需 30 秒」
                    //
                    // 没有可靠依据估算剩余时间：模型响应速度取决于网关、模型、
                    // 上下文长度与当前排队情况。乱估一个数字，用户会在它不准时
                    // 更不信任这个面板。只说明「为什么现在发不出去」就够了。
                    if (busy) {
                        Text(
                            text = "前一轮生成中，结束后自动发送",
                            style = MaterialTheme.typography.labelSmall,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f),
                        )
                    }
                }
                // 头部操作：全部发送 / 清空。只在展开时出现 ——
                // 收起状态下用户看不到队列，一次「全部发送」会像凭空发出几条消息。
                if (expanded && onSendAll != null) {
                    QueueIconAction(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSendAll()
                        },
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "全部发送",
                        tint = MiuixTheme.colorScheme.primary,
                    )
                }
                if (expanded && onClearAll != null) {
                    QueueIconAction(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onClearAll()
                        },
                        imageVector = Icons.Default.Delete,
                        contentDescription = "清空队列",
                        tint = MiuixTheme.colorScheme.error,
                    )
                }
                Icon(
                    imageVector = if (expanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
// ## 展开/收起要有过渡（2026-10-24 修）
            //
            // 旧写法 `if (expanded) { LazyColumn(...) }`：点一下，排队区
            // 在一帧内被塞进布局，把聊天区往上顶一大截 —— 观感是「闪了一下」。
            //
            // ## 为什么这里不用 `FoldableContent`（AnimatedVisibility）
            //
            // 它的内容是个 `LazyColumn`，高度**依赖测量结果**（`heightIn(max)`），
            // 不是事先可知的定值。`AnimatedVisibility` 的 `expandVertically`
            // 需要按「展开后的完整高度」算比例动画，对惰性列表会在展开过程中
            // 反复触发测量 → 高度抖动，而且首帧可能按 0 高度起算。
            //
            // `animateContentSize` 走的是另一条路：它比较**测量完成后**的前后
            // 尺寸，对尺寸变化做补间。这与「内容自己决定多高」的场景天然契合，
            // 也是 `MessageEditor` 处理同类问题（展开编辑区）时用的方案。
            Column(
                modifier = Modifier.animateContentSize(
                    animationSpec = tween(
                        durationMillis = PraxisMotion.Expansion.Expand,
                        easing = PraxisMotion.Curves.Decelerate,
                    ),
                ),
            ) {
                if (expanded) {
                    // ## 拖拽排序（2026-11-06）
                    //
                    // 保留上/下/置顶按钮（可访问性好、可单测），额外加长按拖拽 ——
                    // 队列超过 3 条时，逐次点「上移」很累。
                    //
                    // ## 为什么拖拽期间要 `userScrollEnabled = false`
                    //
                    // 长按拖拽的手势起点与列表滚动的手势起点完全重合。不禁用滚动时
                    // 手指一竖向移动，列表会先滚一段再开始拖，表现为「条目乱跳」。
                    // 禁用后拖拽期间列表不动，只有条目在让位。
                    val dragState = rememberDragReorderState(
                        ids = messages.map { it.id },
                        onReorder = onReorder,
                        onHaptic = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                    )
                    LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 220dp ≈ 3-4 条。再多就盖住大半个聊天区 ——
                        // 用户会看不见 AI 正在说什么，却看着自己的排队消息。
                        .heightIn(max = 220.dp),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    // 拖拽中锁住滚动：见上方说明。
                    userScrollEnabled = dragState.draggingId == null,
                    state = dragState.listState,
                ) {
                    itemsIndexed(
                        items = messages,
                        key = { _, item -> item.id },
                    ) { index, item ->
                        // 让位动画：拖动某条时，其余条目用位移补间挪到新位置。
                        // 没有它，换位是「瞬间跳过去」，用户看不清谁换了谁。
                        // 注意：被拖那条自己不做位移动画（否则会与手指位置打架）。
                        // ## 用 `Modifier.animateItem()` 而不是 `animateItemPlacement`
                        //
                        // 后者在 Compose Foundation 1.7 起已被移除（本项目 BOM 2026.08）。
                        // `animateItem()` 是 `LazyItemScope` 的成员，同样只做位置/淡入补间，
                        // 但它让「新条目出现」「条目消失」也有动画，语义更完整。
                        val placementModifier = if (dragState.draggingId == item.id) {
                            Modifier
                        } else {
                            Modifier.animateItem(
                                placementSpec = tween(
                                    durationMillis = PraxisMotion.Durations.Fast,
                                    easing = PraxisMotion.Curves.Decelerate,
                                ),
                            )
                        }
                        // 入队高亮：新条目出现时底色从 primary 淡入再淡出，
                        // 让「刚才那条真的排进去了」有一帧可看的反馈。
                        // `animateColorAsState` 负责补间，不是硬切。
                        val highlight = remember { Animatable(1f) }
                        LaunchedEffect(item.id) {
                            highlight.snapTo(1f)
                            highlight.animateTo(
                                targetValue = 0f,
                                animationSpec = tween(
                                    durationMillis = 600,
                                    easing = PraxisMotion.Curves.Decelerate,
                                ),
                            )
                        }
                        val rowColor = lerp(
                            itemColor,
                            MiuixTheme.colorScheme.primary.copy(alpha = 0.18f),
                            highlight.value,
                        )
                        val isFirst = index <= 0
                        val isLast = index >= messages.lastIndex
                        QueueRow(
                            modifier = placementModifier,
                            item = item,
                            actionIconTint = actionIconTint,
                            itemColor = rowColor,
                            showOrdering = showOrdering,
                            isFirst = isFirst,
                            isLast = isLast,
                            onEdit = onEdit,
                            onSendNow = onSendNow,
                            onDelete = onDelete,
                            onMoveUp = onMoveUp,
                            onMoveDown = onMoveDown,
                            onMoveToTop = onMoveToTop,
                            // 拖拽态：只有被拖的那条抬起，其余靠 animateItemPlacement 让位。
                            dragging = dragState.draggingId == item.id,
                            dragOffsetY = if (dragState.draggingId == item.id) dragState.offsetY else 0f,
                            dragModifier = if (onReorder != null) {
                                dragState.modifierFor(item.id, index)
                            } else {
                                Modifier
                            },
                        )
                    }
                }
                }
            }
        }
    }
}

/**
 * 队列中的一条（版式与上游一致：左侧编辑 / 正文 / 右侧操作组）。
 */
@Composable
private fun QueueRow(
    modifier: Modifier = Modifier,
    item: QueuedMessage,
    actionIconTint: Color,
    itemColor: Color,
    showOrdering: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onEdit: (Long) -> Unit,
    onSendNow: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onMoveUp: (Long) -> Unit,
    onMoveDown: (Long) -> Unit,
    onMoveToTop: (Long) -> Unit,
    /** 本条是否正在被拖。 */
    dragging: Boolean = false,
    /** 拖拽中的垂直位移（px），只对被拖那条非零。 */
    dragOffsetY: Float = 0f,
    /** 拖拽手势修饰符（无拖拽能力时为空）。 */
    dragModifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            // ## 拖拽观感：抬起 + 位移
            //
            // `graphicsLayer` 只触发 draw 阶段，不触发 measure/layout ——
            // 拖拽期间每帧改位移也不会引起重排风暴。
            // 阴影加深是「这张卡被拿起来了」的唯一视觉信号；
            // 不加的话条目移动到别处时看起来像是「列表自己变了」。
            .graphicsLayer {
                if (dragging) {
                    translationY = dragOffsetY
                    shadowElevation = 8.dp.toPx()
                    scaleX = 1.02f
                    scaleY = 1.02f
                }
            }
            .then(dragModifier),
        colors = CardDefaults.cardColors(containerColor = itemColor),
        shape = PraxisRadius.MediumShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QueueIconAction(
                onClick = { onEdit(item.id) },
                imageVector = Icons.Default.Edit,
                contentDescription = "编辑",
                tint = actionIconTint,
            )
            // ## 类型图标（仅带图条目）
            //
            // 不带图的条目不加占位图标：队列里绝大多数是纯文本，
            // 每行都画一个「文本」图标只是噪音；而带图的条目必须能一眼认出 ——
            // 否则用户以为那张图没排上。
            if (item.kind == QueueItemKind.WithImages) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = "含 ${item.images.size} 张图片",
                    tint = actionIconTint,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = item.text,
                style = MaterialTheme.typography.bodySmall,
                color = MiuixTheme.colorScheme.onSurface,
                // 单行省略（与上游一致）：队列是用来「认出是哪条」的，
                // 不是阅读全文。全文点编辑看。
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                if (showOrdering) {
                    // 边界禁用而不是隐藏：隐藏会让每行的图标数不一致，
                    // 正文宽度跟着变，滚动时文字左右跳。
                    QueueIconAction(
                        onClick = { onMoveToTop(item.id) },
                        imageVector = Icons.Default.VerticalAlignTop,
                        contentDescription = "置顶",
                        tint = actionIconTint,
                        enabled = !isFirst,
                    )
                    QueueIconAction(
                        onClick = { onMoveUp(item.id) },
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "上移",
                        tint = actionIconTint,
                        enabled = !isFirst,
                    )
                    QueueIconAction(
                        onClick = { onMoveDown(item.id) },
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "下移",
                        tint = actionIconTint,
                        enabled = !isLast,
                    )
                }
                QueueIconAction(
                    onClick = { onSendNow(item.id) },
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "立即发送",
                    tint = actionIconTint,
                )
                QueueIconAction(
                    onClick = { onDelete(item.id) },
                    imageVector = Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = actionIconTint,
                )
            }
        }
    }
}

/**
 * 图标按钮（26dp 触区 / 16dp 图形，与上游完全一致）。
 *
 * ## 为什么 26dp 小于 Material 建议的 48dp
 *
 * 上游就是 26dp。这一行最多有 6 个图标，48dp 会把正文压没。
 * 判定依据是「这些操作都能补救」：发错顺序能再调、删错了能重打。
 * 真正不可逆的操作（删除会话）不在这个面板里。
 *
 * ## 为什么禁用要连 `clickable` 一起摘掉
 *
 * 只降透明度、保留 `clickable`，会出现「看着是灰的、点下去却真的动了」——
 * 或者反过来（看着正常、点了没反应）。两种都会让用户不信任这个面板。
 * 这里两者同源：`enabled` 决定透明度，也决定是否挂 `clickable`。
 */
@Composable
private fun QueueIconAction(
    onClick: () -> Unit,
    imageVector: ImageVector,
    contentDescription: String,
    tint: Color,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(PraxisRadius.TinyShape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            // 禁用态降透明度而不是换色：换色在不同主题下会出现
            // 「灰得几乎看不见」或「看起来仍像可用」。
            tint = if (enabled) tint else tint.copy(alpha = 0.3f),
            modifier = Modifier.size(16.dp),
        )
    }
}
/**
 * 队列拖拽排序的状态机。
 *
 * ## 为什么单独抽一个类，而不是全写在 Composable 里
 *
 * 「拖到哪一项、换成什么顺序」是纯计算（给定位移 → 目标下标），
 * 抽出来就能纯 JVM 单测；写在 Composable 里只能靠 Compose 测试，
 * 而那需要 instrumentation。
 *
 * ## 判定方式：按「越过哪一行」而不是「固定像素阈值」
 *
 * 每行高度可能不同（带图片的行更高）。用固定像素阈值判断「是否换位」时，
 * 高行要拖很远才换、矮行轻轻一动就换，手感不一致。
 * 这里用 `LazyListState.layoutInfo.visibleItemsInfo` 的**真实行偏移**
 * 找「手指当前落在第几行」，换位判定与视觉位置完全对齐。
 */
@Composable
internal fun rememberDragReorderState(
    ids: List<Long>,
    onReorder: ((List<Long>) -> Unit)?,
    onHaptic: () -> Unit,
): DragReorderState {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val state = remember { DragReorderState() }
    state.listState = listState
    state.latestIds = ids
    state.onCommit = onReorder
    state.onHaptic = onHaptic
    state.scope = scope
    return state
}

/**
 * 拖拽排序的可变状态。
 *
 * ## 为什么用普通 class + 字段级 MutableState
 *
 * 拖拽期间每帧都在改 `offsetY`。若把整个状态做成不可变对象并每帧重建，
 * 所有读取它的行会一起重组；字段级 `mutableStateOf` 只让真正读该字段的行重组。
 */
@Stable
internal class DragReorderState {

    lateinit var listState: LazyListState

    /** 当前条目 id 序列（调用方每次重组刷新，提交时用最新的）。 */
    var latestIds: List<Long> = emptyList()

    var onCommit: ((List<Long>) -> Unit)? = null
    var onHaptic: () -> Unit = {}
    lateinit var scope: CoroutineScope

    /** 正在拖的条目 id；null = 没在拖。 */
    var draggingId by mutableStateOf<Long?>(null)
        private set

    /** 相对按下点的垂直位移（px）。 */
    var offsetY by mutableFloatStateOf(0f)
        private set

    private var startIndex = -1
    private var targetIndex = -1

    /**
     * 给某一行生成拖拽手势修饰符。
     *
     * ## 为什么用 `detectDragGesturesAfterLongPress`
     *
     * 行内有 5 个图标按钮与「点击编辑」。短按拖拽会与「点编辑」抢手势；
     * 长按是移动端「拿起可排序项」的既有惯例，也与侧边栏多选、
     * 消息长按菜单同一套交互语言。
     */
    fun modifierFor(id: Long, index: Int): Modifier = Modifier.pointerInput(id, index) {
        detectDragGesturesAfterLongPress(
            onDragStart = {
                if (latestIds.size <= 1) return@detectDragGesturesAfterLongPress
                draggingId = id
                startIndex = index
                targetIndex = index
                offsetY = 0f
                onHaptic()
            },
            onDrag = { change, dragAmount ->
                change.consume()
                offsetY += dragAmount.y
                val rows = listState.layoutInfo.visibleItemsInfo
                if (rows.isEmpty()) return@detectDragGesturesAfterLongPress
                val anchor = rows.firstOrNull { it.index == startIndex }
                    ?: return@detectDragGesturesAfterLongPress
                val fingerY = anchor.offset + offsetY
                val hit = rows.lastOrNull { fingerY >= it.offset } ?: rows.first()
                if (hit.index != targetIndex) {
                    targetIndex = hit.index
                    onHaptic()
                }
            },
            onDragEnd = { commit() },
            onDragCancel = {
                draggingId = null
                offsetY = 0f
                startIndex = -1
                targetIndex = -1
            },
        )
    }

    /** 落位：把 id 从 [startIndex] 移到 [targetIndex] 并提交。 */
    fun commit() {
        val from = startIndex
        val to = targetIndex
        val ids = latestIds
        draggingId = null
        offsetY = 0f
        startIndex = -1
        targetIndex = -1
        if (from < 0 || to < 0 || from == to) return
        if (from !in ids.indices || to !in ids.indices) return
        val mutable = ids.toMutableList()
        val moved = mutable.removeAt(from)
        mutable.add(to, moved)
        onCommit?.invoke(mutable)
    }
}

