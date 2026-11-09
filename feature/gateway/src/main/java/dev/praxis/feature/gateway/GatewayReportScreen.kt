package dev.praxis.feature.gateway

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import dev.praxis.ui.widgets.MiuixAnimatedDialog
import top.yukonga.miuix.kmp.basic.TextField
import dev.praxis.ui.widgets.MiuixIntNumberPickerWidget
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.core.gateway.GatewayReportFormat
import dev.praxis.core.gateway.GatewayUsageBucket
import dev.praxis.core.gateway.GatewayUsageSummary
import dev.praxis.ui.widgets.MiuixNavigationItemWidget
import dev.praxis.ui.widgets.SettingsSectionHeader
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 报表里的一行（一段时间 + 它的汇总）。 */
data class GatewayReportWindowUi(val label: String, val summary: GatewayUsageSummary)

/**
 * 网关 → 报表（docs/PORTING-GATEWAY.md §9.6）。
 *
 * ## 为什么没有图表
 *
 * 文档明确写着「不做图表，要趋势去报表页」。而报表页现在只做**数字与列表**：
 * 请求数 / 失败数 / 成功率 / token，加一张按模型的排行。
 * 手机上一屏能读完，比一张要横屏才看得清的折线图有用。
 *
 * ## 为什么成功率可能是「—」
 *
 * 没有请求时它不是 0%：那会让用户以为「网关了全挂了」，而实际是「还没用过」。
 * 这个区分在 [GatewayReportFormat.successRateText] 里（有单测）。
 */
@Composable
fun GatewayReportScreen(
    windows: List<GatewayReportWindowUi> = emptyList(),
    /** 按模型排行（取最长那个窗口的分组）。 */
    topModels: List<GatewayUsageBucket> = emptyList(),
    onRefresh: () -> Unit = {},
    /** 记录保留天数（文档 §5.7）。 */
    retentionDays: Int = dev.praxis.core.gateway.GatewayRetention.DEFAULT_DAYS,
    onRetentionDaysChange: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 顶栏与底栏都是浮层：内容要能滚到它们下面，否则置顶/置底会被糊住或被压住。
    val topInset = dev.praxis.ui.design.component.LocalTopBarInset.current
    val scrollLimit = dev.praxis.ui.design.component.LocalScrollBottomLimit.current
    var retentionDialogVisible by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = topInset,
            bottom = 24.dp + scrollLimit,
        ),
    ) {
        item(key = "title") {
            SettingsSectionHeader(
                icon = Icons.Outlined.Insights,
                title = "报表",
                subtitle = "只统计真实请求；连通性测试不计入",
            )
        }

        if (windows.isEmpty()) {
            item(key = "empty") {
                GatewayCard {
                    Text(
                        text = "还没有数据。把模型档案切到「网关」并发几条消息，这里就会有记录。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        } else {
            windows.forEach { window ->
                item(key = "window_" + window.label) {
                    GatewayCard {
                        Column {
                            Text(
                                text = window.label,
                                style = MiuixTheme.textStyles.body2,
                            )
                            Text(
                                text = GatewayReportFormat.requestsText(window.summary),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }

        if (topModels.isNotEmpty()) {
            item(key = "models_title") {
                SettingsSectionHeader(
                    icon = Icons.Outlined.Rule,
                    title = "按模型",
                    subtitle = "近 30 天，按请求数排序",
                )
            }
            topModels.forEach { bucket ->
                item(key = "model_" + bucket.name) {
                    GatewayCard {
                        Column(modifier = Modifier.padding(bottom = 2.dp)) {
                            Text(text = bucket.name, style = MiuixTheme.textStyles.body2)
                            Text(
                                text = bucket.requests.toString() + " 次 · 失败 " +
                                    bucket.failures + " · " +
                                    GatewayReportFormat.tokenText(bucket.tokens) + " tokens",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        // 保留期：记录会一直累积，这里给用户一个可调的出口。
        item(key = "retention") {
            GatewayCard {
                MiuixNavigationItemWidget(
                    title = "记录保留",
                    description = retentionDays.toString() + " 天（更早的记录在下次启动时清掉）",
                    onClick = { retentionDialogVisible = true },
                )
            }
        }

        item(key = "refresh") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                MiuixNavigationItemWidget(
                    title = "刷新",
                    description = "重新统计（数据是本地库里的，不联网）",
                    onClick = onRefresh,
                )
            }
        }
    }

    if (retentionDialogVisible) {
        RetentionDialog(
            current = retentionDays,
            onDismiss = { retentionDialogVisible = false },
            onConfirm = { days ->
                onRetentionDaysChange(days)
                retentionDialogVisible = false
            },
        )
    }
}

/**
 * 保留天数弹窗。校验与夹取都用 core 的 `GatewayRetention`（与清理侧同一套边界）。
 *
 * 为什么下限是 1 而不是 0：0 天等于「什么都不留」—— 那不是「少留一点」，
 * 而且报表会立刻变空，用户会以为数据丢了。
 */
@Composable
private fun RetentionDialog(
    current: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    // 滑块：1~365 天，非法值不可表达（此前用文本框 + 解析，还得处理"填了中文"）。
    var days by remember { mutableIntStateOf(current.coerceIn(1, 365)) }

    MiuixAnimatedDialog(visible = true, onDismissRequest = onDismiss) {
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "记录保留", style = MiuixTheme.textStyles.body2)
                Text(
                    text = "早于这么多天的请求记录会在下次启动时清掉（1~365 天）。" +
                        "报表统计的就是这些记录，清掉后对应时间段会变空。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                MiuixIntNumberPickerWidget(
                    title = "保留天数",
                    description = "越早的记录在下次启动时被清掉",
                    value = days,
                    startInt = 1,
                    endInt = 365,
                    valueSuffix = " 天",
                    onValueChange = { days = it },
                )
                Spacer(Modifier.height(10.dp))
                MiuixNavigationItemWidget(
                    title = "保存",
                    description = "下次启动生效",
                    onClick = {
                        onConfirm(dev.praxis.core.gateway.GatewayRetention.parseDays(days))
                    },
                )
            }
        }
    }
}
