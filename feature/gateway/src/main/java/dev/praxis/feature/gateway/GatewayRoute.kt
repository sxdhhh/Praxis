package dev.praxis.feature.gateway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.ui.navigation.LocalNavigator
import dev.praxis.ui.navigation.PraxisRoute
import dev.praxis.ui.widgets.MiuixNavigationItemWidget
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 网关概览（设置 → 网关）。
 *
 * ## 这一版只做入口与骨架（P0）
 *
 * 按 docs/PORTING-GATEWAY.md §11 的 P0 范围：设置入口 + 路由 + 概览页骨架。
 * 页面里的每一项都**如实标注未实现**，不做「点了没反应」的假入口：
 * 用户看到的是「还没做完」，而不是「功能坏了」。
 *
 * ## 为什么零状态也要真读出来
 *
 * 「还没有账号」「暂无请求」不是占位文案，而是当前真实状态：账号池与
 * 请求记录表要到 P1/P3 才建。等数据层就位后，这里换成真实计数即可，
 * 布局与分区不需要改动。
 */
@Composable
fun GatewayRoute(
    /** 账号池里的账号数（:app 从数据库读出来传进来）。 */
    accountCount: Int = 0,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalNavigator.current
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "__intro__") {
            SmallTitle("网关")
        }

        item(key = "__intro_card__") {
            GatewayCard {
                Text(
                    text = "把多个上游账号汇成一个入口：Praxis 只认「对外模型名」，" +
                        "由网关决定这次请求走哪个账号。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        // ── 账号（P1）──
        item(key = "__accounts_title__") {
            SmallTitle("账号")
        }
        item(key = "__accounts_card__") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                MiuixNavigationItemWidget(
                    title = "账号池",
                    description = if (accountCount == 0) {
                        "还没有账号 · 点这里查看"
                    } else {
                        accountCount.toString() + " 个账号 · 点这里管理"
                    },
                    onClick = { navigator.push(PraxisRoute.SettingsGatewayAccounts) },
                )
            }
        }

        // ── 今日流量（P3）──
        item(key = "__traffic_title__") {
            SmallTitle("今日流量")
        }
        item(key = "__traffic_card__") {
            GatewayCard {
                Text(
                    text = "暂无请求记录",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = "请求统计要等网关真正转发请求之后才有数据（P3）。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        // ── 进度：明确列出还没做的部分 ──
        item(key = "__plan_title__") {
            SmallTitle("后续阶段")
        }
        item(key = "__plan_card__") {
            GatewayCard {
                PLAN_LINES.forEachIndexed { index, line ->
                    Text(
                        text = line,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = if (index == 0) 0.dp else 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * 还没实现的部分。
 *
 * 写在页面上而不是只写在文档里：这是唯一能让用户分清
 * 「还没做」与「做了但坏了」的地方。
 */
private val PLAN_LINES = listOf(
    "P1 账号池 · 失败自动换号 · 添加账号的三级流程",
    "P2 模型条目结构化 · 模型管理 · 路由策略",
    "P3 请求统计与报表 · 日志并入现有日志页",
    "P4 超时重试 · 代理 · 云同步 · 备份",
    "P5 上游实现（Rust 内核 + JNI）· 签到框架",
)
