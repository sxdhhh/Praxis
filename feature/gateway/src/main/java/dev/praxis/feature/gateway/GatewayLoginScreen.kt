package dev.praxis.feature.gateway

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.core.gateway.adapters.LoginMethod
import dev.praxis.core.gateway.adapters.LoginPhase
import dev.praxis.ui.widgets.MiuixAnimatedDialog
import dev.praxis.ui.widgets.MiuixNavigationItemWidget
import dev.praxis.ui.widgets.PraxisWebViewSheet
import dev.praxis.ui.widgets.SettingsSectionHeader
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一家提供商可选的登录方式（方法清单进来之后显示这一屏）。
 *
 * 「点一下就走完」是这里的目标：选方式 → 网关给 authUrl → H5 容器打开 →
 * 用户在页面里登录 → 容器把回调 URL 交回来 → 自动提交给网关 → 轮询到成功。
 * 需要粘回调的家（浏览器与网关不同机）在 20 秒后自动亮出粘贴框。
 */
@Composable
fun GatewayLoginScreen(
    providerLabel: String = "",
    providerId: String = "",
    methods: List<LoginMethod> = emptyList(),
    instructions: String = "",
    phase: LoginPhase = LoginPhase.Idle,
    methodsLoading: Boolean = false,
    methodsError: String? = null,
    /** 用户在 H5 容器/浏览器里登录时，容器把回调 URL 交回来。 */
    onCallbackCaptured: (String) -> Unit = {},
    /** 手动粘回调（manualCallback 的家）。 */
    onSubmitCallback: (String) -> Unit = {},
    onPickMethod: (Int) -> Unit = {},
    onCancel: () -> Unit = {},
    onRetryMethods: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var manualInput by remember(providerId) { mutableStateOf("") }
    val waiting = phase as? LoginPhase.Waiting
    val needCallback = phase as? LoginPhase.NeedCallback
    // H5 容器：有 authUrl 就打开；成功/失败自动收起。
    var browserUrl by remember(providerId) { mutableStateOf<String?>(null) }
    if (phase is LoginPhase.Succeeded || phase is LoginPhase.Failed) browserUrl = null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        SettingsSectionHeader(
            title = "登录 " + providerLabel,
            subtitle = when {
                methodsLoading -> "正在取登录方式…"
                methodsError != null -> methodsError
                methods.isEmpty() -> "这家没有可用的登录助手"
                else -> instructions.ifBlank { "选一种登录方式" }
            },
        )

        when (val current = phase) {
            is LoginPhase.Succeeded -> {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("登录成功", style = MiuixTheme.textStyles.body1)
                        current.accountId?.let { id ->
                            Text(
                                text = "账号：" + id,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        MiuixNavigationItemWidget(title = "完成", description = "返回账号列表", onClick = onCancel)
                    }
                }
            }

            is LoginPhase.Failed -> {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = current.message,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        MiuixNavigationItemWidget(title = "重试", description = "重新取登录方式", onClick = onRetryMethods)
                    }
                }
            }

            is LoginPhase.Waiting -> {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "等待你在授权页面完成登录（已等 " + current.elapsedSeconds + " 秒）",
                            style = MiuixTheme.textStyles.body2,
                        )
                        current.hint?.let { hint ->
                            Text(
                                text = hint,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        current.url?.let { url ->
                            MiuixNavigationItemWidget(
                                title = "重新打开授权页",
                                description = "在当前 App 里打开 H5 容器",
                                onClick = { browserUrl = url },
                            )
                        }
                        MiuixNavigationItemWidget(title = "取消登录", description = "停掉这次等待", onClick = onCancel)
                    }
                }
            }

            is LoginPhase.NeedCallback -> {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = if (current.wantsCode)
                                "授权完成后把一次性 code 粘到这里"
                            else "把浏览器地址栏里的完整回调地址粘到这里",
                            style = MiuixTheme.textStyles.body2,
                        )
                        current.hint?.let { hint ->
                            Text(
                                text = hint,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        TextField(
                            value = manualInput,
                            onValueChange = { manualInput = it },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            label = if (current.wantsCode) "code" else "回调地址",
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                        )
                        MiuixNavigationItemWidget(
                            title = "提交",
                            description = if (manualInput.isBlank()) "先粘贴内容" else "交给网关换码",
                            onClick = { if (manualInput.isNotBlank()) onSubmitCallback(manualInput.trim()) },
                        )
                        MiuixNavigationItemWidget(title = "取消", description = "放弃这次登录", onClick = onCancel)
                    }
                }
            }

            LoginPhase.Idle, LoginPhase.Starting -> {
                methods.forEach { method ->
                    MiuixNavigationItemWidget(
                        title = method.label,
                        description = buildString {
                            append("方式索引 ").append(method.methodIndex)
                            if (method.manualCallback) append(" · 需要粘回调")
                            if (method.localOnly) append(" · 仅回环地址")
                            if (method.instructions.isNotBlank()) append(" · ").append(method.instructions);
                        },
                        onClick = { onPickMethod(method.methodIndex) },
                    )
                }
                if (methods.isEmpty() && !methodsLoading && methodsError == null) {
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Text(
                            text = "这家没有登录助手（可能需要先装插件，或在网关面板里登录）",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }

    // H5 容器：常驻挂载，url 非空才显示。
    MiuixAnimatedDialog(visible = browserUrl != null, onDismissRequest = { browserUrl = null }) {
        Card {
            Column(modifier = Modifier.fillMaxWidth().height(560.dp)) {
                PraxisWebViewSheet(
                    url = browserUrl ?: "",
                    onClose = { browserUrl = null },
                    onUrlChanged = { url ->
                        onCallbackCaptured(url);
                    },
                )
            }
        }
    }
}