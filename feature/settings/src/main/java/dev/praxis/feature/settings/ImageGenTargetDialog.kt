package dev.praxis.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.core.imagegen.ImageGenTarget
import dev.praxis.core.imagegen.ImageModel
import dev.praxis.ui.widgets.MiuixAnimatedDialog
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 生图目标的选择弹窗（docs/PORTING-IMAGEGEN.md §7.3）。
 *
 * ## 为什么是「两个分组」而不是一个列表
 *
 * 本地模型 id 与服务商的 (provider, model) 是两种语义：一个叫 flux 的本地模型和
 * 一条远端 flux-schnell 放在同一个列表里，用户分不清自己选的是哪条路。
 * 分组的标题本身就是那层说明。
 *
 * ## 「服务商」这一组现在是**真实清单**
 *
 * `/v1/images/generations` 的客户端早已实现（`RemoteImageProtocol` + 工具分派）。
 * 这一组列出模型池里各家的模型名（来自 `gateway_provider.modelsJson`），
 * 选中即写 `ImageGenTarget.Remote(provider, baseUrl, model)`；清单里没有的仍可手填。
 *
 * ## 点击即生效
 *
 * 与功能模型页的单选弹窗同一取舍：用户心里的动作是「选它」，不是「标记它」。
 * 多一步确认反而让人以为没选中。
 */
/**
 * 服务商生图模型（模型池里的一项）：家 + 模型 + 该家的地址。
 *
 * 清单来自各家 `modelsJson` 里声明的模型名（用户自己填的），所以这里**不做能力位过滤**
 * —— 那份能力位只有网关的插件家才有，Praxis 面对的是自定义家。
 */
data class ImageGenRemoteModel(
    val provider: String,
    val model: String,
    val baseUrl: String,
)

@Composable
fun ImageGenTargetDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    localModels: List<ImageModel>,
    /**
     * 服务商（模型池）里可选的生图模型（文档 §7.1 的「服务商模型」分组）。
     *
     * 由 :app 从「内置家 + 自定义家」的模型清单拼出来：那些名字就是
     * `gateway_provider.modelsJson` 里声明的，选中的组合会被原样发给上游。
     */
    remoteModels: List<ImageGenRemoteModel> = emptyList(),
    current: ImageGenTarget?,
    onSelect: (ImageGenTarget) -> Unit,
) {
    // 自定义远端表单的开关。放在这里而不是外面：它是这个对话框的子流程。
    var remoteDialogVisible by remember { mutableStateOf(false) }

    MiuixAnimatedDialog(visible = visible, onDismissRequest = onDismiss) {
        Card {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(text = "选择生图目标", style = MiuixTheme.textStyles.body1)
                Text(
                    text = "点击即生效；生成时按这里选的走",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )

                Spacer(Modifier.height(12.dp))
                Text(
                    text = "本地模型",
                    style = MiuixTheme.textStyles.body2,
                )
                if (localModels.isEmpty()) {
                    Text(
                        text = "还没有已下载的模型 —— 先去「模型库」下一个",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    localModels.forEach { model ->
                        val selected = (current as? ImageGenTarget.Local)?.modelId == model.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelect(ImageGenTarget.Local(model.id))
                                    onDismiss()
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Image,
                                contentDescription = null,
                                tint = if (selected) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                },
                                modifier = Modifier.padding(end = 10.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = model.name, style = MiuixTheme.textStyles.body2)
                                Text(
                                    text = model.approximateSize + " · " + if (model.runOnCpu) "CPU" else "NPU",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            if (selected) {
                                Text(
                                    text = "当前",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MiuixTheme.colorScheme.dividerLine)
                Spacer(Modifier.height(8.dp))
                Text(text = "服务商生图模型", style = MiuixTheme.textStyles.body2)
                if (remoteModels.isEmpty()) {
                    Text(
                        text = "模型池里还没有模型。先在「网关 → 自定义提供商」里加一家" +
                            "并在模型清单里写上它的模型名，这里就会列出来。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    remoteModels.forEach { remote ->
                        val selected = (current as? ImageGenTarget.Remote)?.let { target ->
                            target.provider == remote.provider && target.model == remote.model
                        } == true
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelect(
                                        ImageGenTarget.Remote(
                                            provider = remote.provider,
                                            baseUrl = remote.baseUrl,
                                            model = remote.model,
                                        ),
                                    )
                                    onDismiss()
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Image,
                                contentDescription = null,
                                tint = if (selected) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                },
                            )
                            Column {
                                Text(
                                    text = remote.provider + " / " + remote.model,
                                    style = MiuixTheme.textStyles.body2,
                                )
                                Text(
                                    text = remote.baseUrl,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            if (selected) {
                                Text(
                                    text = "当前",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
                Text(
                    text = "这些名字来自各家的模型清单。远端生图**优先用网关账号池**" +
                        "（换号、退避、熔断、token 预算都对它生效）；" +
                        "该家不在池里、或池里此刻没有可用账号时才用「Praxis 全局 API Key」。" +
                        "清单里没有想要的？在下面自己填一个。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    text = "自定义远端…",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { remoteDialogVisible = true }
                        .padding(vertical = 10.dp),
                )
                (current as? ImageGenTarget.Remote)?.let { remote ->
                    Text(
                        text = "当前：remote:" + remote.provider + "/" + remote.model,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            }
        }
    }

    // 自定义远端表单：与主对话框并列（主对话框在它后面不消失）。
    if (remoteDialogVisible) {
        RemoteTargetDialog(
            onDismiss = { remoteDialogVisible = false },
            onConfirm = { target ->
                onSelect(target)
                remoteDialogVisible = false
                onDismiss()
            },
        )
    }
}
/**
 * 自定义远端生图目标（`/v1/images/generations`）。
 *
 * ## 为什么让用户自己填，而不是给一份内置清单
 *
 * 各家的生图模型名与地址一直在变，写一份内置清单很快就会过期 ——
 * 而过期的默认值比没有更糟（用户选了它，得到 404，却以为是自己配置错了）。
 * 三个字段都让用户填，界面只负责校验「不为空」。
 *
 * ## 用的是哪个 key
 *
 * `Praxis 全局 API Key`（设置页那一个），**不是**网关的账号池。
 * 走账号池要先把「生图请求」接进 RouteSession —— 那是另一件事。
 */
@Composable
private fun RemoteTargetDialog(
    onDismiss: () -> Unit,
    onConfirm: (ImageGenTarget) -> Unit,
) {
    var provider by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    MiuixAnimatedDialog(visible = true, onDismissRequest = onDismiss) {
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "自定义远端", style = MiuixTheme.textStyles.body2)
                Text(
                    text = "填上游的地址与模型名；地址带不带 /v1 都可以（会自动补 /v1/images/generations）。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(10.dp))
                RemoteField("服务商标识（随意起名）", provider) { provider = it }
                RemoteField("地址（如 https://api.openai.com）", baseUrl) { baseUrl = it }
                RemoteField("模型名（如 gpt-image-1）", model) { model = it }
                error?.let { text ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = text,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "确定",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (provider.isBlank() || baseUrl.isBlank() || model.isBlank()) {
                                error = "三个字段都要填"
                            } else {
                                onConfirm(
                                    ImageGenTarget.Remote(
                                        provider = provider.trim(),
                                        baseUrl = baseUrl.trim(),
                                        model = model.trim(),
                                    ),
                                )
                            }
                        }
                        .padding(vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun RemoteField(label: String, value: String, onChange: (String) -> Unit) {
    top.yukonga.miuix.kmp.basic.TextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = label,
        useLabelAsPlaceholder = true,
        singleLine = true,
    )
    Spacer(Modifier.height(8.dp))
}
