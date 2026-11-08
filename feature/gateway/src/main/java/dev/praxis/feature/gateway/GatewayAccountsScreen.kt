package dev.praxis.feature.gateway

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import dev.praxis.ui.widgets.MiuixAnimatedDialog
import top.yukonga.miuix.kmp.basic.TextField
import dev.praxis.core.gateway.GatewayAccountRow
import dev.praxis.core.gateway.GatewayAccountState
import dev.praxis.core.gateway.GatewayAccountStatus
import dev.praxis.core.gateway.GatewayStatusText
import dev.praxis.ui.widgets.MiuixBadge
import dev.praxis.ui.widgets.MiuixDraggableList
import dev.praxis.ui.widgets.MiuixNavigationItemWidget
import dev.praxis.ui.widgets.MiuixSelectableRow
import dev.praxis.ui.widgets.SettingsSectionHeader
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 账号页（文档 §9.3）。
 *
 * ## 控件用得对不对，看文档那一节的表
 *
 * 列表 + 拖动改优先级用 MiuixDraggableList（它对应网关的 priority，
 * 也就是 §7.3 排序的第一步）；状态灯与冷却倒计时用 MiuixBadge。
 *
 * ## 批量模式（§9.3）
 *
 * 多选用 MiuixSelectableRow —— 它原本是 ui:widgets 的 internal 件，
 * 为了照文档写这一页，已经把它提到公开面（MiuixSelectableRow.kt 的 KDoc 有说明）。
 *
 * 批量动作目前只接「清空运行状态」：这是当下唯一真实可做的操作。
 * §9.3 列的测试/导出/删除要等 P1 的请求通道与导入导出界面，**不放假按钮**。
 */
@Composable
fun GatewayAccountsScreen(
    rows: List<GatewayAccountRow>,
    onMove: (from: Int, to: Int) -> Unit,
    onOpenAccount: (String) -> Unit,
    /**
     * 批量「清空运行状态」。
     *
     * 这是目前**唯一真实可用**的批量动作：运行期状态是内存里的
     * （退避/垫底/限额），清掉就是真的清掉。文档 9.3 列出的另外三个
     * （测试 / 导出 / 删除）要等 P1 的请求通道与导入导出界面，暂不放假按钮。
     */
    onClearRuntime: (List<String>) -> Unit,
    /**
     * 手动添加一个账号（文档 §9.3）。
     *
     * 返回 Result：成功给一句「已添加 xxx」，失败给可读原因（校验/加密/落库）。
     * null = 没接线 —— 那时**不画**「添加账号」入口，而不是画一个点了没反应的按钮。
     */
    onAddAccount: (suspend (providerId: String, name: String, baseUrl: String, apiKey: String) -> Result<String>)? = null,
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis(),
) {
    var batchMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var addVisible by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (onAddAccount != null) {
            item(key = "__add_account__") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    MiuixNavigationItemWidget(
                        title = "添加账号",
                        description = "填地址与 Key；内置家清单要等 Rust 内核",
                        onClick = { addVisible = true },
                    )
                }
            }
        }
        item(key = "__accounts_title__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.AccountCircle,
                title = "账号",
                subtitle = if (batchMode) {
                    "已选 " + selected.size + " 个"
                } else {
                    "长按或点右上角进入多选"
                },
            )
        }

        if (rows.isNotEmpty()) {
            item(key = "__accounts_batch_toggle__") {
                GatewayCard {
                    MiuixNavigationItemWidget(
                        title = if (batchMode) "退出多选" else "多选",
                        description = if (batchMode) "退出并取消选择" else "选择多个账号一起操作",
                        onClick = {
                            batchMode = !batchMode
                            if (!batchMode) selected = emptySet()
                        },
                    )
                }
            }
        }

        if (rows.isEmpty()) {
            item(key = "__accounts_empty__") {
                GatewayCard {
                    Text(
                        text = "还没有账号",
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "账号池已就绪，但还没有界面可以添加账号（添加流程在下一阶段）。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        } else if (batchMode) {
            item(key = "__accounts_select_all__") {
                GatewayCard {
                    MiuixSelectableRow(
                        text = "全选",
                        isSelected = selected.size == rows.size,
                        onClick = {
                            selected = if (selected.size == rows.size) {
                                emptySet()
                            } else {
                                rows.map { it.id }.toSet()
                            }
                        },
                    )
                }
            }
            items(rows, key = { it.id }) { row ->
                GatewayCard {
                    MiuixSelectableRow(
                        text = row.name,
                        isSelected = row.id in selected,
                        onClick = {
                            selected = if (row.id in selected) {
                                selected - row.id
                            } else {
                                selected + row.id
                            }
                        },
                    )
                }
            }
            item(key = "__accounts_batch_action__") {
                GatewayCard {
                    MiuixNavigationItemWidget(
                        title = "清空运行状态（" + selected.size + "）",
                        description = "只清退避/垫底/限额，不动账号本身",
                        onClick = {
                            if (selected.isNotEmpty()) {
                                onClearRuntime(selected.toList())
                                selected = emptySet()
                            }
                        },
                    )
                }
            }
        } else {
            item(key = "__accounts_list__") {
                MiuixDraggableList(
                    items = rows,
                    itemKey = { it.id },
                    itemName = { it.name },
                    itemDescription = { describe(it, nowMillis) },
                    onMove = onMove,
                    onItemClick = { onOpenAccount(it.id) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                ) { row ->
                    StatusBadge(row.status)
                }
            }
        }
    }

    if (addVisible && onAddAccount != null) {
        AddAccountDialog(
            onDismiss = { addVisible = false },
            onSave = onAddAccount,
        )
    }
}

/**
 * 状态灯。
 *
 * 颜色随状态动画过渡（文档 §9 的「状态灯颜色（正常→冷却）」用 animateColor）：
 * 直接换色会闪，用户很难把「刚变红」与「一直是红」区分开。
 */
@Composable
private fun StatusBadge(status: GatewayAccountStatus) {
    val target = when (status.state) {
        GatewayAccountState.Normal -> MiuixTheme.colorScheme.primary
        GatewayAccountState.Cooling -> MiuixTheme.colorScheme.error
        // 停用是用户自己的选择，不是错误 —— 用中性色，别让他以为出问题了。
        GatewayAccountState.Disabled -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    val color by animateColorAsState(targetValue = target, label = "gateway-account-status")

    MiuixBadge(
        text = GatewayStatusText.label(status),
        textColor = color,
    )
}


/**
 * 添加账号的弹窗（文档 §9.3 的第三级：填完 → 保存）。
 *
 * ## 为什么没有「渠道下拉」这一级
 *
 * 内置家（21 家）的地址与协议实现要等 P5 的 Rust 内核，现在下拉里只会有「自定义」一项 ——
 * 一个只有一项的下拉是假交互。等内置家清单就位，这里加一行下拉即可。
 *
 * ## 保存失败必须原样显示原因
 *
 * 校验/加密/落库三处失败的原因不同（地址格式、密钥不可用、数据库），
 * 统一成「保存失败」会让用户完全不知道改哪里。
 */
@Composable
private fun AddAccountDialog(
    onDismiss: () -> Unit,
    onSave: suspend (String, String, String, String) -> Result<String>,
) {
    var provider by remember { mutableStateOf("custom") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    MiuixAnimatedDialog(visible = true, onDismissRequest = onDismiss) {
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "添加账号", style = MiuixTheme.textStyles.body1)
                Text(
                    text = "地址填到 /v1 那一级，例如 https://api.openai.com/v1",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(12.dp))
                TextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "服务地址",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "API Key",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "备注名（可留空）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = provider,
                    onValueChange = { provider = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "provider id（默认 custom）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
                message?.let { text ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = text,
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (failed) {
                            MiuixTheme.colorScheme.error
                        } else {
                            MiuixTheme.colorScheme.primary
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))
                MiuixNavigationItemWidget(
                    title = if (busy) "保存中…" else "保存",
                    description = "校验 → 加密 → 落库；任何一步失败都不写半条",
                    onClick = {
                        if (busy) return@MiuixNavigationItemWidget
                        busy = true
                        message = null
                        scope.launch {
                            val result = onSave(provider, name, baseUrl, apiKey)
                            busy = false
                            result.fold(
                                onSuccess = { text ->
                                    failed = false
                                    message = text
                                },
                                onFailure = { error ->
                                    failed = true
                                    message = error.message ?: "保存失败"
                                },
                            )
                        }
                    },
                )
            }
        }
    }
}

/** 副标题：家名 + 冷却原因 + 倒计时 + 受限模型 + 垫底。 */
private fun describe(row: GatewayAccountRow, nowMillis: Long): String? {
    val parts = listOfNotNull(
        row.providerLabel.takeIf { it.isNotBlank() },
        GatewayStatusText.reasonText(row.status),
        GatewayStatusText.countdownText(row.status.untilMillis, nowMillis),
        GatewayStatusText.limitedModelsText(row.status),
        GatewayStatusText.sinkText(row.status),
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
