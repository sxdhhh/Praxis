package dev.praxis.feature.gateway

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.core.gateway.ProxyNode
import dev.praxis.core.gateway.ProxyNodeKind
import dev.praxis.core.gateway.ProxyRules
import dev.praxis.core.gateway.ProxySettings
import dev.praxis.core.gateway.userMessage
import dev.praxis.ui.widgets.MiuixAnimatedDialog
import dev.praxis.ui.widgets.MiuixNavigationItemWidget
import dev.praxis.ui.widgets.MiuixSwitchWidget
import dev.praxis.ui.widgets.SettingsSectionHeader
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置 → 网关 → 代理（文档 §5.9）。
 *
 * ## 粒度是「每账号」
 *
 * 与网关一致：`base_url_override` 与 proxy 都是**账号级**（§5.9 原话）。
 * 所以这里不提供「全局代理」开关 —— 那会与账号级绑定打架（谁优先？），
 * 而网关的选择已经证明够用。
 *
 * ## 出口只解析一次
 *
 * 页面只负责写设置；**谁走哪个出口由 `ProxyRules.egressFor` 一处决定**，
 * 拉清单与转发都调它（见 ProxyResolver 的说明）。
 */
@Composable
fun GatewayProxyScreen(
    settings: ProxySettings = ProxySettings(),
    /** 账号列表（id 到显示名），用于绑定。 */
    accounts: List<Pair<String, String>> = emptyList(),
    onChange: (ProxySettings) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val topInset = dev.praxis.ui.design.component.LocalTopBarInset.current
    val scrollLimit = dev.praxis.ui.design.component.LocalScrollBottomLimit.current
    var addDialogVisible by remember { mutableStateOf(false) }
    var bindingFor by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = topInset,
            bottom = 24.dp + scrollLimit,
        ),
    ) {
        item(key = "__proxy_title__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.Language,
                title = "代理",
                subtitle = "命名出口 + 每账号绑定",
            )
        }
        item(key = "__proxy_switch__") {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                MiuixSwitchWidget(
                    title = "启用代理",
                    description = "关掉 = 全部直连（绑定关系保留，不丢）",
                    checked = settings.enabled,
                    onCheckedChange = { enabled -> onChange(settings.copy(enabled = enabled)) },
                )
            }
        }
        item(key = "__proxy_nodes_title__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.Language,
                title = "出口节点",
                subtitle = if (settings.nodes.isEmpty()) "还没有出口" else settings.nodes.size.toString() + " 个",
            )
        }
        settings.nodes.forEach { node ->
            item(key = "proxy_node_" + node.id) {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                    MiuixNavigationItemWidget(
                        title = node.label,
                        description = node.kind.name.lowercase() + "://" + node.host + ":" + node.port +
                            "　（点这里删除）",
                        onClick = {
                            // 删节点时顺手清掉悬空绑定：留着会让界面显示一个不存在的出口。
                            onChange(
                                ProxyRules.pruneBinding(
                                    settings.copy(
                                        nodes = settings.nodes.filterNot { it.id == node.id },
                                        binding = settings.binding.filterValues { it != node.id },
                                    ),
                                ),
                            )
                        },
                    )
                }
            }
        }
        item(key = "__proxy_add__") {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                MiuixNavigationItemWidget(
                    title = "添加出口",
                    description = "http 或 socks，带可选账号密码",
                    onClick = { addDialogVisible = true },
                )
            }
        }
        item(key = "__proxy_binding_title__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.Language,
                title = "按账号绑定",
                subtitle = "没绑定的账号直连",
            )
        }
        if (accounts.isEmpty()) {
            item(key = "__proxy_no_accounts__") {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Text(
                        text = "还没有账号。先在「账号」页加一个，再回来绑定出口。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        } else {
            accounts.forEach { (accountId, accountName) ->
                val bound = settings.binding[accountId]
                val boundLabel = settings.nodes.firstOrNull { it.id == bound }?.label
                item(key = "proxy_bind_" + accountId) {
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                        MiuixNavigationItemWidget(
                            title = accountName,
                            description = if (boundLabel != null) "出口：" + boundLabel else "直连",
                            onClick = { bindingFor = accountId },
                        )
                    }
                }
            }
        }
    }

    // 两个弹窗都常驻挂载（文档 §9.0）。
    AddProxyNodeDialog(
        visible = addDialogVisible,
        existing = settings.nodes,
        onDismiss = { addDialogVisible = false },
        onConfirm = { node ->
            onChange(settings.copy(nodes = settings.nodes + node))
            addDialogVisible = false
        },
    )
    BindProxyDialog(
        visible = bindingFor != null,
        nodes = settings.nodes,
        onDismiss = { bindingFor = null },
        onPick = { nodeId ->
            val accountId = bindingFor
            if (accountId != null) {
                val next = settings.binding.toMutableMap()
                if (nodeId == null) next.remove(accountId) else next[accountId] = nodeId
                onChange(settings.copy(binding = next))
            }
            bindingFor = null
        },
    )
}

/** 添加出口。 */
@Composable
private fun AddProxyNodeDialog(
    visible: Boolean = true,
    existing: List<ProxyNode> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (ProxyNode) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8080") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var socks by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    MiuixAnimatedDialog(visible = visible, onDismissRequest = onDismiss) {
        Card {
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                Text(
                    text = "添加出口",
                    style = MiuixTheme.textStyles.body1,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
                ProxyField("名字", label) { label = it }
                ProxyField("主机或 IP", host) { host = it }
                ProxyField("端口", port) { port = it }
                ProxyField("用户名（可空）", username) { username = it }
                ProxyField("密码（可空）", password) { password = it }
                MiuixSwitchWidget(
                    title = "SOCKS 代理",
                    description = "关 = http 代理",
                    checked = socks,
                    onCheckedChange = { socks = it },
                )
                error?.let { text ->
                    Text(
                        text = text,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                MiuixNavigationItemWidget(
                    title = "保存",
                    description = "保存后可在下面按账号绑定",
                    onClick = {
                        val draft = ProxyNode(
                            id = "proxy-" + System.currentTimeMillis().toString(16),
                            label = label,
                            kind = if (socks) ProxyNodeKind.Socks else ProxyNodeKind.Http,
                            host = host,
                            port = port.trim().toIntOrNull() ?: 0,
                            username = username,
                            password = password,
                        )
                        val invalid = ProxyRules.validateNode(draft, existing)
                        if (invalid != null) {
                            error = invalid.userMessage()
                        } else {
                            onConfirm(ProxyRules.normalizeNode(draft))
                        }
                    },
                )
            }
        }
    }
}

/** 选出口（含「不绑定」）。 */
@Composable
private fun BindProxyDialog(
    visible: Boolean = true,
    nodes: List<ProxyNode> = emptyList(),
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    MiuixAnimatedDialog(visible = visible, onDismissRequest = onDismiss) {
        Card {
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                Text(
                    text = "选择出口",
                    style = MiuixTheme.textStyles.body1,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                MiuixNavigationItemWidget(
                    title = "不绑定（直连）",
                    description = "这个账号不走代理",
                    onClick = { onPick(null) },
                )
                nodes.forEach { node ->
                    MiuixNavigationItemWidget(
                        title = node.label,
                        description = node.host + ":" + node.port,
                        onClick = { onPick(node.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProxyField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    TextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        label = label,
        useLabelAsPlaceholder = true,
        singleLine = true,
    )
    Spacer(Modifier.height(8.dp))
}
