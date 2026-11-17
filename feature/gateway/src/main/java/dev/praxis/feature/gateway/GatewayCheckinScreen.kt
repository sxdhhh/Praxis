package dev.praxis.feature.gateway

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.ui.widgets.MiuixNavigationItemWidget
import dev.praxis.ui.widgets.SettingsSectionHeader
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 签到 / 积分页（设置 → 网关 → 签到）.
 *
 * 数据全部来自网关（Praxis 不自己实现签到逻辑）：
 * - POST /api/checkin —— 领取每日积分（409=今天已领过，正常结果）
 * - POST /api/checkin/claim-and-report —— 签到+余额一把拿
 * - GET /api/usage —— 积分汇总
 * - POST /api/auto-checkin/run —— 对全部目标账号跑一轮自动签到
 */
@Composable
fun GatewayCheckinScreen(
    /** 最近一次签到的结果文案（:app 从网关拉回来传进来）。 */
    checkinMessage: String = "",
    /** 最近一次签到的性质：true=今天已领过（提示色，不是红色错误）。 */
    checkinTakenToday: Boolean = false,
    /** 余额/积分文案。 */
    creditsMessage: String = "",
    /** 忙碌状态（防重复点击）。 */
    busy: Boolean = false,
    onClaim: () -> Unit = {},
    onRunAuto: () -> Unit = {},
    onRefreshCredits: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        SettingsSectionHeader(
            title = "签到与积分",
            subtitle = "数据来自网关（签到逻辑在网关里，Praxis 只管显示与触发）",
        )

        if (checkinMessage.isNotBlank()) {
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

        MiuixNavigationItemWidget(
            title = "领取每日签到积分",
            description = when {
                busy -> "正在签到…"
                else -> "POST /api/checkin；今天已领过会提示，不算错误"
            },
            onClick = { if (!busy) onClaim() },
        )
        MiuixNavigationItemWidget(
            title = "对全部账号跑一轮自动签到",
            description = "POST /api/auto-checkin/run（网关按各家插件逐个签）",
            onClick = { if (!busy) onRunAuto() },
        )
        MiuixNavigationItemWidget(
            title = "刷新积分汇总",
            description = "GET /api/usage",
            onClick = { if (!busy) onRefreshCredits() },
        )

        if (creditsMessage.isNotBlank()) {
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