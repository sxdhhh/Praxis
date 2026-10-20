package dev.praxis.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.ui.design.component.LocalScrollBottomLimit
import dev.praxis.ui.widgets.MiuixNavigationItemWidget
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话列表页。
 *
 * 排序已经由 DAO 的 `ORDER BY updatedAt DESC` 保证，这里不再排一次 ——
 * 两处排序规则不一致时会出现「列表顺序与点击行为对不上」。
 */
@Composable
fun ChatListScreen(
    sessions: List<SessionItem>,
    onCreate: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 底栏是浮层：内容要能从它下方穿过（玻璃才有东西折射），
    // 但滚动终点必须抬高，否则最后一条会话永远滚不到底栏上方。
    // 高度由 :app 通过 LocalBottomBarInset → LocalScrollBottomLimit 下发。
    val scrollLimit = LocalScrollBottomLimit.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp + scrollLimit),
    ) {
        item { Spacer(Modifier.height(12.dp)) }

        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                TextButton(
                    text = "新建对话",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onCreate,
                )
            }
        }

        item { SmallTitle("历史会话（${sessions.size}）") }

        if (sessions.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    Text(
                        text = "还没有历史会话。点上方「新建对话」开始。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        } else {
            items(sessions, key = { it.id }) { session ->
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    Column {
                        MiuixNavigationItemWidget(
                            title = session.title.ifBlank { "未命名对话" },
                            description = formatTime(session.updatedAt),
                            onClick = { onOpen(session.id) },
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                text = "删除",
                                onClick = { onDelete(session.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 时间格式：今天只显示时分，更早显示月日。 */
private fun formatTime(epochMillis: Long): String {
    val now = System.currentTimeMillis()
    val dayMs = 24L * 60 * 60 * 1000
    val sameDay = (now - epochMillis) < dayMs
    val pattern = if (sameDay) "HH:mm" else "MM-dd HH:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(epochMillis))
}
