package dev.praxis.feature.gateway

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.core.gateway.userMessage
import dev.praxis.ui.widgets.MiuixAnimatedDialog
import dev.praxis.ui.widgets.MiuixNavigationItemWidget
import dev.praxis.ui.widgets.PraxisWebViewSheet
import dev.praxis.ui.widgets.SettingsSectionHeader
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** ① 提供商列表里的一行。 */
data class GatewayProviderChoice(
    val id: String,
    val name: String,
    val baseUrl: String?,
)

/**
 * 添加账号 ①：提供商列表（文档 §9.9）。
 *
 * 数据来自「内置家 + 自定义家」：Praxis 没有网关那套插件家目录，
 * 所以这份清单就是它实际能连的东西 —— 不摆连不上的家。
 */
@Composable
fun GatewayAddProviderScreen(
    providers: List<GatewayProviderChoice> = emptyList(),
    onPick: (GatewayProviderChoice) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val topInset = dev.praxis.ui.design.component.LocalTopBarInset.current
    val scrollLimit = dev.praxis.ui.design.component.LocalScrollBottomLimit.current
    var query by remember { mutableStateOf("") }
    val filtered = remember(providers, query) {
        if (query.isBlank()) {
            providers
        } else {
            providers.filter {
                it.id.contains(query.trim(), ignoreCase = true) ||
                    it.name.contains(query.trim(), ignoreCase = true)
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = topInset,
            bottom = 24.dp + scrollLimit,
        ),
    ) {
        item(key = "__add_title__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.Add,
                title = "选择提供商",
                subtitle = "下一步选登录方式",
            )
        }
        item(key = "__add_search__") {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    label = "搜索（标识或名字）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
            }
        }
        if (filtered.isEmpty()) {
            item(key = "__add_empty__") {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Text(
                        text = "没有匹配的提供商。自定义家可以先在「自定义提供商」里加一个。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        } else {
            filtered.forEach { provider ->
                item(key = "add_provider_" + provider.id) {
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                        MiuixNavigationItemWidget(
                            title = provider.name + "（" + provider.id + "）",
                            description = provider.baseUrl ?: "还没有地址（下一步要填）",
                            onClick = { onPick(provider) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 添加账号 ②：登录方式（文档 §9.9）。
 *
 * 两种方式都是**真的能走完**的：
 * - 直填 Key：不依赖任何页面能否打开；
 * - 网页登录：在 H5 容器里打开上游地址，登录后复制 Key 粘回来（`manualCallback` 那条路）。
 *
 * 网关那套 `login/wait` 轮询与 `prompts` 表单**没有移植**：那些元数据在插件家里，
 * Praxis 面对的是用户自己填地址的家，编一份出来就成了假入口。
 */
@Composable
fun GatewayAddLoginScreen(
    providerId: String,
    providerLabel: String,
    baseUrl: String?,
    onSave: (dev.praxis.core.gateway.GatewayAccountDraft) -> Unit = {},
    /** 保存失败的原因（校验或加密失败）。 */
    saveError: String? = null,
    modifier: Modifier = Modifier,
) {
    val topInset = dev.praxis.ui.design.component.LocalTopBarInset.current
    val scrollLimit = dev.praxis.ui.design.component.LocalScrollBottomLimit.current
    val methods = remember(providerId, baseUrl) {
        dev.praxis.core.gateway.GatewayLoginMethods.forProvider(providerLabel, baseUrl)
    }
    var apiDialogVisible by remember { mutableStateOf(false) }
    var authDialogVisible by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = topInset,
            bottom = 24.dp + scrollLimit,
        ),
    ) {
        item(key = "__login_title__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.Rule,
                title = "登录方式",
                subtitle = providerLabel + "（" + providerId + "）",
            )
        }
        methods.forEach { method ->
            item(key = "login_method_" + method.type.name) {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    MiuixNavigationItemWidget(
                        title = method.label,
                        description = method.instructions ?: "",
                        onClick = {
                            when (method.type) {
                                dev.praxis.core.gateway.GatewayLoginType.Api -> apiDialogVisible = true
                                dev.praxis.core.gateway.GatewayLoginType.OAuth -> authDialogVisible = true
                            }
                        },
                    )
                }
            }
        }
        saveError?.let { text ->
            item(key = "__login_error__") {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Text(
                        text = text,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }

    // 两个弹窗都**常驻挂载**（文档 §9.0）：`visible` 控制显隐，退场动画才播得完。
    ApiKeyAccountDialog(
        visible = apiDialogVisible,
        providerId = providerId,
        providerLabel = providerLabel,
        initialBaseUrl = baseUrl.orEmpty(),
        onDismiss = { apiDialogVisible = false },
        onConfirm = { draft ->
            onSave(draft)
            apiDialogVisible = false
        },
    )
    WebAuthDialog(
        visible = authDialogVisible,
        providerId = providerId,
        providerLabel = providerLabel,
        initialBaseUrl = baseUrl.orEmpty(),
        onDismiss = { authDialogVisible = false },
        onConfirm = { draft ->
            onSave(draft)
            authDialogVisible = false
        },
    )
}

/** 直填 Key 的表单。 */
@Composable
private fun ApiKeyAccountDialog(
    // 常驻挂载 + visible 参数（文档 §9.0 禁止 `if (visible) { Dialog }`：
    // 那样退场动画来不及播）。下同。
    visible: Boolean = true,
    providerId: String,
    providerLabel: String,
    initialBaseUrl: String,
    onDismiss: () -> Unit,
    onConfirm: (dev.praxis.core.gateway.GatewayAccountDraft) -> Unit,
) {
    var name by remember { mutableStateOf(providerLabel) }
    var baseUrl by remember { mutableStateOf(initialBaseUrl) }
    var apiKey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    MiuixAnimatedDialog(visible = visible, onDismissRequest = onDismiss) {
        Card {
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                AddField("名字", name) { name = it }
                AddField("地址（https://…）", baseUrl) { baseUrl = it }
                AddField("API Key", apiKey, secret = true) { apiKey = it }
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
                    description = "凭据用设备绑定的密钥加密后入库",
                    onClick = {
                        val draft = dev.praxis.core.gateway.GatewayAccountDraft(
                            providerId = providerId,
                            name = name,
                            baseUrl = baseUrl,
                            apiKey = apiKey,
                        )
                        val invalid = dev.praxis.core.gateway.GatewayAccountDraftRules.validate(draft)
                        if (invalid != null) {
                            error = invalid.userMessage()
                        } else {
                            onConfirm(
                                dev.praxis.core.gateway.GatewayAccountDraftRules.normalize(draft),
                            )
                        }
                    },
                )
            }
        }
    }
}

/**
 * ③ 授权弹窗 + ④ H5 容器（文档 §9.9）。
 *
 * ## 一处刻意偏离：容器是**全屏弹窗**而不是路由
 *
 * 文档把 H5 容器列成 `PraxisRoute.SettingsGatewayAuth(url, providerId)`。
 * 这里没走路由，理由是状态：授权结束后用户要回到**这一层**继续粘贴 Key，
 * 走路由会把这一页推走，回来时输入的东西与信息都丢了。
 * 而文档 §9.9 开头就写着「授权用弹窗」—— 容器做成弹窗与那句话一致。
 */
@Composable
private fun WebAuthDialog(
    visible: Boolean = true,
    providerId: String,
    providerLabel: String,
    initialBaseUrl: String,
    onDismiss: () -> Unit,
    onConfirm: (dev.praxis.core.gateway.GatewayAccountDraft) -> Unit,
) {
    var landingUrl by remember { mutableStateOf(initialBaseUrl) }
    var pasted by remember { mutableStateOf("") }
    var containerVisible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    MiuixAnimatedDialog(visible = visible, onDismissRequest = onDismiss) {
        Card {
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                Text(
                    text = "授权 " + providerLabel,
                    style = MiuixTheme.textStyles.body1,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(
                    text = "在打开的页面里登录，然后复制 API Key（或回调地址）粘到下面。" +
                        "Praxis 不代管你的账号密码。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(10.dp))
                AddField("登录页地址（可改）", landingUrl) { landingUrl = it }
                MiuixNavigationItemWidget(
                    title = "打开登录页",
                    description = "在应用内的容器里打开（不跳系统浏览器）",
                    onClick = {
                        if (!dev.praxis.core.gateway.GatewayAccountDraftRules.isHttpUrl(landingUrl)) {
                            error = "登录页地址要以 http:// 或 https:// 开头"
                        } else {
                            error = null
                            containerVisible = true
                        }
                    },
                )
                AddField("粘贴回调地址或 API Key", pasted, secret = true) { pasted = it }
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
                    title = "完成授权",
                    description = "把上面粘贴的内容当作凭据保存",
                    onClick = {
                        val credential = dev.praxis.core.gateway.GatewayLoginMethods
                            .extractCredential(pasted)
                        if (credential.isEmpty()) {
                            error = "先粘贴 API Key 或回调地址"
                        } else {
                            val draft = dev.praxis.core.gateway.GatewayAccountDraft(
                                providerId = providerId,
                                name = providerLabel,
                                baseUrl = landingUrl,
                                apiKey = credential,
                            )
                            val invalid = dev.praxis.core.gateway.GatewayAccountDraftRules
                                .validate(draft)
                            if (invalid != null) {
                                error = invalid.userMessage()
                            } else {
                                onConfirm(draft)
                            }
                        }
                    },
                )
            }
        }
    }

    // 常驻挂载（文档 §9.0）：容器也要能播退场动画。
    MiuixAnimatedDialog(visible = containerVisible, onDismissRequest = { containerVisible = false }) {
        Card {
            Column(modifier = Modifier.fillMaxWidth().height(520.dp)) {
                PraxisWebViewSheet(
                    url = landingUrl,
                    onClose = { containerVisible = false },
                )
            }
        }
    }
}

@Composable
private fun AddField(
    label: String,
    value: String,
    secret: Boolean = false,
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
