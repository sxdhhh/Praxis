package dev.praxis.feature.chat.queue
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.praxis.feature.chat.QueuedMessage
import dev.praxis.ui.theme.PraxisMotion
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
) {
    // 空队列不占屏幕（与上游一致）：否则输入框上方永远挂着一条空标题。
    if (messages.isEmpty()) return

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
                Text(
                    text = "待发送 ${messages.size} 条",
                    style = MaterialTheme.typography.labelLarge,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
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
                    LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 220dp ≈ 3-4 条。再多就盖住大半个聊天区 ——
                        // 用户会看不见 AI 正在说什么，却看着自己的排队消息。
                        .heightIn(max = 220.dp),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(
                        items = messages,
                        key = { _, item -> item.id },
                    ) { index, item ->
                        val isFirst = index <= 0
                        val isLast = index >= messages.lastIndex
                        QueueRow(
                            item = item,
                            actionIconTint = actionIconTint,
                            itemColor = itemColor,
                            showOrdering = showOrdering,
                            isFirst = isFirst,
                            isLast = isLast,
                            onEdit = onEdit,
                            onSendNow = onSendNow,
                            onDelete = onDelete,
                            onMoveUp = onMoveUp,
                            onMoveDown = onMoveDown,
                            onMoveToTop = onMoveToTop,
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
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = itemColor),
        shape = RoundedCornerShape(12.dp),
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
            .clip(RoundedCornerShape(6.dp))
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
