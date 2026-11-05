package dev.praxis.feature.chat.command

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.ui.theme.PraxisMotion
import dev.praxis.ui.theme.PraxisRadius
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * `/` 命令候选面板。
 *
 * ## 什么时候出现
 *
 * [query] 非 null 且至少匹配到一条命令时。`query` 是「命令名部分」
 * （斜杠之后、第一个空白之前），由调用方从输入框现算 ——
 * 本组件不解析输入，只负责展示与回调。
 *
 * ## 为什么按前缀过滤而不是模糊匹配
 *
 * 命令都很短（2-7 字符），前缀足够。模糊匹配会让「/c」同时匹配
 * compact、clear、export(c 在中间)，用户得读完才知道要哪个 ——
 * 反而不如直接按顺序看列表快。
 *
 * ## 动画
 *
 * 用 `AnimatedVisibility` + `PraxisMotion` 的展开时长（与队列面板、
 * 摘要折叠同一套手感），而不是 `if (visible)` 硬切 —— 硬切会在输入时
 * 让整个输入区高度突变，用户正在看的字会往上跳。
 */
@Composable
fun SlashCommandPanel(
    query: String?,
    commands: List<CommandDefinition>,
    onPick: (CommandDefinition) -> Unit,
    modifier: Modifier = Modifier,
) {
    val matches = remember(query, commands) {
        val q = query ?: return@remember emptyList()
        commands
            .filter { it.name.startsWith(q, ignoreCase = false) }
            .sortedBy { it.name }
    }
    AnimatedVisibility(
        visible = query != null && matches.isNotEmpty(),
        enter = expandVertically(
            animationSpec = tween(
                durationMillis = PraxisMotion.Expansion.Expand,
                easing = PraxisMotion.Curves.Decelerate,
            ),
            expandFrom = Alignment.Top,
        ) + fadeIn(
            animationSpec = tween(
                durationMillis = PraxisMotion.Expansion.FadeIn,
                easing = PraxisMotion.Curves.Decelerate,
            ),
        ),
        exit = shrinkVertically(
            animationSpec = tween(
                durationMillis = PraxisMotion.Expansion.Shrink,
                easing = PraxisMotion.Curves.Decelerate,
            ),
        ) + fadeOut(
            animationSpec = tween(
                durationMillis = PraxisMotion.Expansion.FadeIn,
            ),
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MiuixTheme.colorScheme.surfaceVariant,
            ),
            shape = PraxisRadius.MediumShape,
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "命令",
                    style = MaterialTheme.typography.labelSmall,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp),
                )
                // 限高：命令多了也不该盖住大半个聊天区。
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp),
                    contentPadding = PaddingValues(bottom = 6.dp),
                ) {
                    items(matches, key = { it.name }) { cmd ->
                        CommandRow(cmd = cmd, onPick = { onPick(cmd) })
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandRow(cmd: CommandDefinition, onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "/" + cmd.name,
            style = MaterialTheme.typography.bodySmall,
            color = MiuixTheme.colorScheme.primary,
        )
        Text(
            text = cmd.description,
            style = MaterialTheme.typography.labelSmall,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f),
        )
    }
}
