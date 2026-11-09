package dev.praxis.feature.gateway

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
    modifier: Modifier = Modifier,
) {
    // 顶栏与底栏都是浮层：内容要能滚到它们下面，否则置顶/置底会被糊住或被压住。
    val topInset = dev.praxis.ui.design.component.LocalTopBarInset.current
    val scrollLimit = dev.praxis.ui.design.component.LocalScrollBottomLimit.current

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
}
