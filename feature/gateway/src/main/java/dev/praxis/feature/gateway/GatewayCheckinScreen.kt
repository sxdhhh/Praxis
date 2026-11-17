package dev.praxis.feature.gateway

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.ui.widgets.MiuixNavigationItemWidget
import dev.praxis.ui.widgets.SettingsSectionHeader
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 签到 / 积分页（设置 → 网关 → 签到）。
 *
 * 数据全部来自网关（Praxis 不自己实现签到逻辑）；骨架与全站一致：
 * LazyColumn + LocalTopBarInset（顶部渐变模糊下滚）+ LocalScrollBottomLimit。
 */
@Composable
fun GatewayCheckinScreen(
    checkinMessage: String = "",
    checkinTakenToday: Boolean = false,
    creditsMessage: String = "",
    busy: Boolean = false,
    onClaim: () -> Unit = {},
    onRunAuto: () -> Unit = {},
    onRefreshCredits: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val topInset = dev.praxis.ui.design.component.LocalTopBarInset.current
    val scrollLimit = dev.praxis.ui.design.component.LocalScrollBottomLimit.current
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = topInset, bottom = 24.dp + scrollLimit),
    ) {
        item(key = "__checkin_header__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.Insights,
                title = "签到与积分",
                subtitle = "数据来自网关（签到逻辑在网关里，Praxis 只管显示与触发）",
            )
        }
        if (checkinMessage.isNotBlank()) {
            item(key = "__checkin_msg__") {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Text(
                        text = checkinMessage,
                        style = MiuixTheme.textStyles.body2,
                        color = if (checkinTakenToday) MiuixTheme.colorScheme.onSurfaceVariantSummary
                                else MiuixTheme.colorScheme.primary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        item(key = "__checkin_claim__") {
            MiuixNavigationItemWidget(
                title = "领取每日签到积分",
                description = if (busy) "正在签到…"
                    else "POST /api/checkin；今天已领过会提示，不算错误",
                onClick = { if (!busy) onClaim() },
            )
        }
        item(key = "__checkin_auto__") {
            MiuixNavigationItemWidget(
                title = "对全部账号跑一轮自动签到",
                description = "POST /api/auto-checkin/run（网关按各家插件逐个签）",
                onClick = { if (!busy) onRunAuto() },
            )
        }
        item(key = "__checkin_refresh__") {
            MiuixNavigationItemWidget(
                title = "刷新积分汇总",
                description = "GET /api/usage",
                onClick = { if (!busy) onRefreshCredits() },
            )
        }
        if (creditsMessage.isNotBlank()) {
            item(key = "__checkin_credits__") {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Text(
                        text = creditsMessage,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}