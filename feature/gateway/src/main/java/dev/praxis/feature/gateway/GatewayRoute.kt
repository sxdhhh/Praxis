package dev.praxis.feature.gateway

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Insights
import dev.praxis.ui.navigation.LocalNavigator
import dev.praxis.ui.navigation.PraxisRoute
import dev.praxis.ui.widgets.MiuixNavigationItemWidget
import dev.praxis.ui.widgets.MiuixSwitchWidget
import dev.praxis.ui.widgets.SettingsSectionHeader
import top.yukonga.miuix.kmp.basic.Card
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
    /** 网关总开关（文档 §11 末尾的回滚闸）。 */
    gatewayEnabled: Boolean = true,
    onGatewayEnabledChange: (Boolean) -> Unit = {},
    /**
     * 整库备份（文档 组十六）：**只导出**。
     *
     * 恢复没做 —— 覆盖当前库需要先处理 WAL、Room 关闭时机与断电中断，
     * 而那三样只能真机验证。见 BackupExporter 的 KDoc。
     */
    onBackupDatabase: () -> Unit = {},
    /** 上次备份的结果文案（null = 还没点过）。 */
    backupMessage: String? = null,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalNavigator.current
    // 顶栏是浮层：内容要能滚到它下面（否则渐进模糊采不到内容，退化成一块实色）。
    val topInset = dev.praxis.ui.design.component.LocalTopBarInset.current
    // 底栏同理：滚动终点必须抬高，否则最后一项被压住看不见。
    val scrollLimit = dev.praxis.ui.design.component.LocalScrollBottomLimit.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(top = topInset, bottom = 24.dp + scrollLimit),
    ) {
        item(key = "__intro__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.Hub,
                title = "网关",
                subtitle = "多个上游账号汇成一个入口",
            )
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

        // ── 总开关（文档 §11 末尾的回滚闸）──
        item(key = "__master_title__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.Hub,
                title = "总开关",
                subtitle = "关掉 = 回到没有网关时的行为",
            )
        }
        item(key = "__master_switch__") {
            GatewayCard {
                MiuixSwitchWidget(
                    title = "启用网关",
                    description = "关闭后，模型档案切到「网关」会明确提示已停用，" +
                        "不会改走别的账号或别的地址",
                    checked = gatewayEnabled,
                    onCheckedChange = onGatewayEnabledChange,
                )
            }
        }

        // ── 账号（P1）──
        item(key = "__accounts_title__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.AccountCircle,
                title = "账号",
                subtitle = "谁参与选路、谁在冷却",
            )
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

        // ── 导入导出（文档 9.7）──
        item(key = "__import_title__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.Restore,
                title = "导入导出",
                subtitle = "把网关那边配好的账号搬过来",
            )
        }
        item(key = "__import_card__") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                MiuixNavigationItemWidget(
                    title = "导入导出",
                    description = "从剪贴板导入账号文件",
                    onClick = { navigator.push(PraxisRoute.SettingsGatewayImport) },
                )
            }
        }

        // ── 路由（文档 9.4）──
        item(key = "__routing_title__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.AltRoute,
                title = "路由",
                subtitle = "这次会先用谁、谁被跳过",
            )
        }
        item(key = "__models_card__") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                MiuixNavigationItemWidget(
                    title = "模型管理",
                    description = "逐条启停、看能力位；候选清单按这里的开关过滤",
                    onClick = { navigator.push(PraxisRoute.SettingsGatewayModels) },
                )
            }
        }

        item(key = "__providers_card__") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                MiuixNavigationItemWidget(
                    title = "自定义提供商",
                    description = "填地址与模型清单；标识决定走哪种协议",
                    onClick = { navigator.push(PraxisRoute.SettingsGatewayProviders) },
                )
            }
        }

        item(key = "__backup_card__") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                MiuixNavigationItemWidget(
                    title = "备份整库",
                    description = backupMessage
                        ?: "把数据库复制到 exports/backups（恢复未实现，见下方说明）",
                    onClick = onBackupDatabase,
                )
            }
        }

        item(key = "__routing_card__") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                MiuixNavigationItemWidget(
                    title = "候选队列",
                    description = "按当前模型展示位次与跳过原因",
                    onClick = { navigator.push(PraxisRoute.SettingsGatewayRouting) },
                )
            }
        }

        // ── 今日流量（P3）──
        item(key = "__traffic_title__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.Insights,
                title = "今日流量",
                subtitle = "请求数与失败数",
            )
        }
        item(key = "__traffic_card__") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                MiuixNavigationItemWidget(
                    title = "报表",
                    description = "今日 / 近 7 天 / 近 30 天的请求数与 token",
                    onClick = { navigator.push(PraxisRoute.SettingsGatewayReport) },
                )
            }
        }

        // ── 进度：明确列出还没做的部分 ──
        item(key = "__plan_title__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.Construction,
                title = "后续阶段",
                subtitle = "还没做的部分，如实列出",
            )
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
    "已落地：账号池 · 失败换号 · 添加账号三级流程 · 模型条目 · 模型管理 · 路由策略" +
        " · 熔断四参 · token 预算 · 请求记录与报表",
    "P3 剩余：日志页的来源筛选（请求明细本身已在库里）",
    "P4 超时重试 · 代理池 · 云同步 · 备份",
    "P5 上游实现（Rust 内核 + JNI）· 签到框架 —— 内置 21 家要等它",
)
