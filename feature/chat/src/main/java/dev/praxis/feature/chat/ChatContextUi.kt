package dev.praxis.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 上下文用量条（输入栏上方那一行）。
 *
 * ## 布局按 user 的描述逐字对应
 *
 * > 「左边显示当前上下文为几 k 右边显示最大上下文为几 k 或几 m」
 *
 * ```
 * 3.2k  [━━━━━━━━━━━━━━━━━━━┃──]  128k
 *        ↑ 进度         ↑ 压缩阈值刻度
 * ```
 *
 * ## 为什么用 miuix 的 [LinearProgressIndicator] 而不是自己画
 *
 * user 的要求是「图表如果没有 miuix 的那就用 operit 的吧」，同时又要求
 * 「不到万不得已不得自写」。miuix 提供了 `LinearProgressIndicator`
 * （`basic/ProgressIndicator.kt:49`），正好是这条需求要的形态：
 * 支持 `progress: Float?`（具值 / 不确定态）、可指定颜色与高度。
 * 所以**不需要**去抄 Operit 的自绘图表，直接用现有组件。
 *
 * 阈值刻度是额外叠的一小段竖线：miuix 的进度条不提供「阈值标记」参数，
 * 但用一个 `Box` 叠上去就够（不是自写进度条，只是加一个装饰层）。
 *
 * ## 为什么未知上限时不显示进度
 *
 * `limit = 0` 时画一条 0% 或 100% 的条都是错的（前者暗示「没有上下文」，
 * 后者暗示「已满」）。此时只显示已用量与「?」，让用户知道「我没填上限」。
 */
@Composable
internal fun ContextUsageBar(
    usage: ContextUsage,
    modifier: Modifier = Modifier,
) {
    val known = usage.limit > 0
    // 达到阈值时整条变色：这是用户唯一能在「压缩即将发生」前察觉的信号。
    val barColor = if (usage.atThreshold) {
        MiuixTheme.colorScheme.error
    } else {
        MiuixTheme.colorScheme.primary
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧：已用量
        Text(
            text = usage.usedLabel,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.width(8.dp))

        // 中间：进度条（未知上限时只占位，不画比例）
        if (known) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp),
            ) {
                LinearProgressIndicator(
                    progress = usage.ratio,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    colors = top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
                        .progressIndicatorColors(
                            foregroundColor = barColor,
                        ),
                    height = 6.dp,
                )
                // 阈值刻度：一根 2dp 宽的细线，标出「压到这里就会开始压缩」。
                //
                // 只在阈值明显落在中间时才画：贴边（<5% 或 >95%）会让它和
                // 圆角端点糊在一起，看起来像渲染瑕疵。
                if (usage.threshold in 0.05f..0.95f) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxWidth(usage.threshold)
                            .height(6.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(6.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(MiuixTheme.colorScheme.onSurfaceVariantActions),
                        )
                    }
                }
            }
        } else {
            // 未知上限：一条静态底槽，表达「这里本该有进度」但不撒谎。
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh),
            )
        }

        Spacer(Modifier.width(8.dp))
        // 右侧：上限
        Text(
            text = usage.limitLabel,
            style = MiuixTheme.textStyles.footnote1,
            color = if (usage.atThreshold) {
                MiuixTheme.colorScheme.error
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
        )
    }
}

/**
 * 待发队列面板已迁到 `queue/PendingQueuePanel.kt`（Material3 1:1 复刻 Operit）。
 *
 * ## 为什么从这里搬走
 *
 * 旧实现是 miuix 版式（`Text` 一行 + 一排 28dp 图标按钮），与 Operit 的
 * 「外层 Card + 可点标题行 + 内层可滚动小 Card 列表」结构差得很远。
 * 用户 2026-10-22 明确要求这三块（对话框 / 待发队列 / 跳转消息）
 * **破例不用 miuix**，直接 1:1 复刻 Operit 控件 —— 于是整块换掉，
 * 而不是在 miuix 版上叠加功能。
 *
 * 同时不再保留旧函数：两份同名面板会让人不知道该改哪个，
 * 且旧版不支持「立即发送」「展开/收起」，功能上已被新版完全覆盖。
 */

/**
 * 给队列行加点击。
 *
 * `clickable` 是 `Modifier` 的**扩展函数**（`Modifier.clickable(...)`），
 * 不是顶层函数 —— 写成 `androidx.compose.foundation.clickable(onClick = ...)`
 * 会编译失败（找不到该函数）。这里包成自己的扩展，顺便让调用点短一点。
 */
private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
