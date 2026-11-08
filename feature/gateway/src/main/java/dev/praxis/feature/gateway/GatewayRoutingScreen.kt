package dev.praxis.feature.gateway

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.core.gateway.GatewayQueue
import dev.praxis.core.gateway.GatewayQueueEntry
import dev.praxis.ui.widgets.SettingsSectionHeader
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 路由页（文档 9.4）。
 *
 * 本轮只做「候选队列」分区：它回答用户最常问的两个问题 —— 「这次会先用谁」
 * 「那些没参与的是为什么」。
 *
 * ## 为什么「策略」与「容错」两区还没做
 *
 * 9.4 让它们用 MiuixSelectableRow 与 Switch×3 + MiuixNumPickerWidget。但
 * 它们要改的是**选路策略与熔断参数**，而这两组配置在 Praxis 侧还没有落地的地方
 * （RoutePlanner 现在是 priority 升序 + 粘性 + 垫底，没有 order/roundRobin 的分支；
 * 熔断四参只有内存里的默认值，没有配置来源）。
 *
 * 摆一组控件却没有东西承接，就是「点了没反应」的假控件 —— 铁律 5 明确禁止。
 * 所以这一版**如实写着未实现**，等配置层落地再补。
 *
 * ## 队列行不做动画（9.4 原话）
 *
 * 这一块是「看一眼现状」，不是操作入口；加动画只会让它更难读。
 */
@Composable
fun GatewayRoutingScreen(
    /** 当前对外模型名；空 = 还没选模型。 */
    modelName: String,
    entries: List<GatewayQueueEntry>,
    /** 账号 id 到显示名的映射。 */
    accountNames: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    // 顶栏是浮层：内容要能滚到它下面（否则渐进模糊采不到内容，退化成一块实色）。
    val topInset = dev.praxis.ui.design.component.LocalTopBarInset.current
    // 底栏同理：滚动终点必须抬高，否则最后一项被压住看不见。
    val scrollLimit = dev.praxis.ui.design.component.LocalScrollBottomLimit.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(top = topInset, bottom = 24.dp + scrollLimit),
    ) {
        item(key = "__routing_strategy__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.AltRoute,
                title = "策略",
                subtitle = "还没做",
            )
        }
        item(key = "__routing_strategy_note__") {
            GatewayCard {
                Text(
                    text = "顺序 / 轮询与粘性三态尚未实现：它们要改的是选路策略，" +
                        "而对应配置项还没有落地的地方。现在的行为是" +
                        "「按优先级升序 → 会话粘性命中者提前 → 垫底排最后」。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        item(key = "__routing_queue_title__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.Rule,
                title = "候选队列",
                subtitle = if (modelName.isBlank()) "还没选模型" else "对外模型 " + modelName,
            )
        }

        item(key = "__routing_queue__") {
            GatewayCard {
                if (entries.isEmpty()) {
                    Text(
                        text = if (modelName.isBlank()) {
                            "先在模型页选一个模型，这里就能看出这次会走哪个账号。"
                        } else {
                            "这个模型没有可用候选：先导入账号，或在导入页粘贴一份 modelRules 配上映射。"
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else {
                    entries.forEachIndexed { index, entry ->
                        val name = accountNames[entry.accountId] ?: entry.accountId
                        val head = if (entry.usable) {
                            "第 " + entry.position + " 位"
                        } else {
                            "跳过"
                        }
                        Text(
                            text = head + " · " + name + " · " + entry.wireModel,
                            style = MiuixTheme.textStyles.body2,
                            color = if (entry.usable) {
                                MiuixTheme.colorScheme.onSurface
                            } else {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            },
                            modifier = Modifier.padding(top = if (index == 0) 0.dp else 6.dp),
                        )
                        // 先取局部变量：skipReason 是另一个模块里的属性，拿不到智能转换。
                        val reason = entry.skipReason
                        val detail = when {
                            reason != null -> GatewayQueue.reasonText(reason)
                            entry.sink -> "垫底：仍可用，但排在最后"
                            else -> null
                        }
                        if (detail != null) {
                            Text(
                                text = detail,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
        }
    }
}
