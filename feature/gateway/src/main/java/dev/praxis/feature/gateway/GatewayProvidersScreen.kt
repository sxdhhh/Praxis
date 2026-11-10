package dev.praxis.feature.gateway

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.ui.widgets.MiuixAnimatedDialog
import dev.praxis.core.gateway.userMessage
import dev.praxis.ui.widgets.MiuixNavigationItemWidget
import dev.praxis.ui.widgets.SettingsSectionHeader
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 自定义提供商页上的一行（由 :app 从 `gateway_provider` 表组装）。
 *
 * 用扁平字符串而不是 Room 实体：`:feature:gateway` 不该为了画一行去依赖 Room。
 */
data class GatewayProviderRowUi(
    val id: String,
    val name: String,
    val baseUrl: String,
    val modelCount: Int,
)

/**
 * 设置 → 网关 → 自定义提供商（docs/PORTING-GATEWAY.md §5.3）。
 *
 * ## 提供商与账号是两件事
 *
 * 提供商 = **地址与模型清单**（这家在哪、有什么模型）；账号 = **凭证**。
 * 同一家可以有多个账号，换地址也不该动凭证 —— 所以这一页不碰 key。
 *
 * ## 标识为什么重要（页面上必须说清）
 *
 * 标识（id）不只是个名字：**协议分派按它判定**（`ProviderProtocols.of`）。
 * 写成 `anthropic` 会走 Messages 协议、`responses` 走 Responses，其余按 OpenAI 兼容。
 * 不写这一句，用户会以为「随便起名都一样」。
 */
@Composable
fun GatewayProvidersScreen(
    providers: List<GatewayProviderRowUi> = emptyList(),
    onAdd: (dev.praxis.core.gateway.GatewayProviderDraft) -> Unit = {},
    onRemove: (String) -> Unit = {},
    /** 上次添加失败的原因（校验没过）；null = 没有错误。 */
    addError: String? = null,
    modifier: Modifier = Modifier,
) {
    val topInset = dev.praxis.ui.design.component.LocalTopBarInset.current
    val scrollLimit = dev.praxis.ui.design.component.LocalScrollBottomLimit.current
    var addDialogVisible by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = topInset,
            bottom = 24.dp + scrollLimit,
        ),
    ) {
        item(key = "__providers_title__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.Dns,
                title = "自定义提供商",
                subtitle = "填地址与模型清单；凭证在「账号池」里填",
            )
        }

        item(key = "__providers_note__") {
            FinalCard {
                Text(
                    text = "标识决定走哪种协议：anthropic → Messages、responses → Responses，" +
                        "其余按 OpenAI 兼容处理。内置家（openai / anthropic）已有地址，" +
                        "所以不能用这些标识建自定义家。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        providers.forEach { provider ->
            item(key = "provider_" + provider.id) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        MiuixNavigationItemWidget(
                            title = provider.name + "（" + provider.id + "）",
                            description = provider.baseUrl + " · " +
                                if (provider.modelCount == 0) {
                                    "没声明模型（请求只能靠映射）"
                                } else {
                                    "声明 " + provider.modelCount + " 个模型"
                                },
                            onClick = { onRemove(provider.id) },
                        )
                        Text(
                            text = "点这一行就是删除它（账号不会被删）",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }

        addError?.let { text ->
            item(key = "__providers_error__") {
                FinalCard {
                    Text(
                        text = text,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
            }
        }

        item(key = "__providers_add__") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                MiuixNavigationItemWidget(
                    title = "添加提供商",
                    description = "标识 / 名字 / 地址 / 模型清单",
                    onClick = { addDialogVisible = true },
                )
            }
        }
    }

    AddProviderDialog(
        visible = addDialogVisible,
            onDismiss = { addDialogVisible = false },
            onConfirm = { draft ->
                onAdd(draft)
                addDialogVisible = false
            },
    )
}

/** 与其它页一致的卡片外壳（自带横向 12dp / 底部 12dp 间距）。 */
@Composable
private fun FinalCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            content()
        }
    }
}

/**
 * 添加提供商的表单。
 *
 * 校验交给 core 的 `GatewayProviderDraftRules`（与存储层同一套），页面不重复判 ——
 * 两处各判一遍必然漂移，而漂移的表现是「界面说没问题、保存却失败」。
 */
@Composable
private fun AddProviderDialog(
    visible: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (dev.praxis.core.gateway.GatewayProviderDraft) -> Unit,
) {
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var models by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    MiuixAnimatedDialog(visible = visible, onDismissRequest = onDismiss) {
        Card {
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                Text(
                    text = "添加提供商",
                    style = MiuixTheme.textStyles.body1,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(
                    text = "模型清单每行一个；留空表示「这家有什么模型还不知道」。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(10.dp))
                ProviderField("标识（如 mygateway）", id) { id = it }
                ProviderField("名字（可留空，用标识）", name) { name = it }
                ProviderField("地址（如 https://api.example.com）", baseUrl) { baseUrl = it }
                ProviderField("模型清单（每行一个，可留空）", models, minLines = 3) { models = it }
                error?.let { text ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = text,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                MiuixNavigationItemWidget(
                    title = "保存",
                    description = "同一个标识再存一次就是修改它",
                    onClick = {
                        val draft = dev.praxis.core.gateway.GatewayProviderDraft(
                            id = id,
                            name = name,
                            baseUrl = baseUrl,
                            models = models.split("\n"),
                        )
                        val invalid = dev.praxis.core.gateway.GatewayProviderDraftRules
                            .validate(draft)
                        if (invalid != null) {
                            error = invalid.userMessage()
                        } else {
                            onConfirm(draft)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ProviderField(
    label: String,
    value: String,
    minLines: Int = 1,
    onChange: (String) -> Unit,
) {
    TextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        label = label,
        useLabelAsPlaceholder = true,
        singleLine = minLines == 1,
        minLines = minLines,
    )
    Spacer(Modifier.height(8.dp))
}
