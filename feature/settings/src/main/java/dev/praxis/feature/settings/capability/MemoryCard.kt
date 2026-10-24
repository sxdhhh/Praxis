package dev.praxis.feature.settings.capability

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 记忆卡片：内容 + 标签 + 时间。
 *
 * ## 为什么内容默认折叠到 3 行
 *
 * 记忆条目是模型自己写的「事实陈述」，长度差异极大（十几个字到几段）。
 * 全展开时一屏只放得下两三条，用户没法快速扫。折到 3 行 + 点击展开，
 * 是「能扫完又能看全」的折中。
 *
 * ## 标签为什么用 FlowRow
 *
 * 标签数量不定。用 `Row` 会溢出（末尾被裁掉），`FlowRow` 才会换行。
 *
 * ## 时间为什么只显示相对时间
 *
 * 记忆的绝对时间对用户没意义（「什么时候记的」不重要），
 * 有意义的是「多久没碰过了」—— 久未命中的记忆可以考虑清掉。
 *
 * @param content 记忆正文
 * @param tags 标签（可为空）
 * @param updatedAt 最后更新时间（毫秒时间戳）
 * @param score 检索得分；null 表示不是检索结果（普通列表）
 */
@Composable
fun MemoryCard(
    content: String,
    modifier: Modifier = Modifier,
    tags: List<String> = emptyList(),
    updatedAt: Long? = null,
    score: Float? = null,
    expanded: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        onClick = onClick ?: {},
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = content,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )

            if (tags.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    tags.forEach { tag ->
                        TagChip(tag)
                    }
                }
            }

            // 底部元信息：只在有内容时占一行，避免空行浪费高度。
            val meta = buildList {
                updatedAt?.let { add(relativeTime(it)) }
                score?.let { add("相关度 ${(it * 100).toInt()}%") }
            }
            if (meta.isNotEmpty()) {
                Text(
                    text = meta.joinToString(" · "),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun TagChip(tag: String) {
    Text(
        text = "#$tag",
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.primary,
    )
}

/**
 * 相对时间。
 *
 * 只做到「天」这一级：小时以下的精度对记忆没用，
 * 反而让界面显得吵（一堆「3 分钟前」在跳动）。
 */
internal fun relativeTime(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    val diff = now - epochMillis
    if (diff < 0) return "刚刚"
    val minutes = diff / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes} 分钟前"
        hours < 24 -> "${hours} 小时前"
        days < 30 -> "${days} 天前"
        days < 365 -> "${days / 30} 个月前"
        else -> "${days / 365} 年前"
    }
}