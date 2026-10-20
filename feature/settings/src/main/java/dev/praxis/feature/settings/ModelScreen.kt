package dev.praxis.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.praxis.core.model.settings.ModelSettings
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 模型配置页。
 *
 * ## 为什么每个字段都要「显式保存」而不是自动落盘
 *
 * baseUrl 是**边输边改**的字符串（`https://api.x.` 这种半截状态）。
 * 自动落盘会在用户敲到一半时触发请求，或把半截地址存进库。
 * 所以用一个草稿态 + 保存按钮，一次提交。
 *
 * ## 密钥不显示原文
 *
 * 用 [PasswordVisualTransformation] 而不是明文展示：旁边有人看一眼就泄露了。
 */
@Composable
fun ModelScreen(
    settings: ModelSettings,
    apiKeyMasked: String,
    onSave: (baseUrl: String, model: String, apiKey: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    var baseUrl by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var model by remember(settings.model) { mutableStateOf(settings.model) }
    var apiKey by remember { mutableStateOf("") }

    val dirty = baseUrl != settings.baseUrl || model != settings.model || apiKey.isNotEmpty()

    SettingsList(
        modifier = modifier.fillMaxSize(),
    ) {
        item { Spacer(Modifier.height(12.dp)) }

        item { SmallTitle("服务商") }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    TextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = "接口地址",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "可填 https://api.openai.com 或带 /v1 的完整前缀；" +
                            "会自动补 /v1/chat/completions。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        item { SmallTitle("模型") }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    TextField(
                        value = model,
                        onValueChange = { model = it },
                        label = "模型 ID",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "例如 gpt-4o-mini / deepseek-chat / qwen-plus。" +
                            "必须是服务商支持的模型名，写错会在对话时报错。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        item { SmallTitle("密钥") }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    TextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = "API Key",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (apiKeyMasked.isEmpty()) {
                            "尚未设置密钥。"
                        } else {
                            "当前已保存：$apiKeyMasked（留空则不修改）"
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    TextButton(
                        text = if (dirty) "保存修改" else "已保存",
                        enabled = dirty,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                onSave(baseUrl, model, apiKey)
                                // 保存后清空密钥草稿，避免它一直留在内存里。
                                apiKey = ""
                            }
                        },
                    )
                    if (!settings.isConfigured) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "尚未完成配置。地址与模型都填好之后才能开始对话。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/** 密钥打码：只露头尾各 3 个字符。 */
fun maskApiKey(key: String): String = when {
    key.isEmpty() -> ""
    key.length <= 8 -> "****"
    else -> key.take(3) + "****" + key.takeLast(3)
}
