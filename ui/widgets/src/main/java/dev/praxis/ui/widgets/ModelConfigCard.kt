package dev.praxis.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 模型配置摘要卡片：**只读**地回答「我现在在跟谁说话、配置齐不齐」。
 *
 * ## 与 `ModelScreen` 的分工
 *
 * `ModelScreen`（设置域）是可编辑表单：字段多、要保存、密钥要打码。
 * 它是「配置的地方」。本卡片是「看的地方」—— 聊天页需要一个快速事实来源，
 * 把可编辑表单塞进主流程只会让人分心。
 *
 * ## 为什么必须显式显示「未完成配置」
 *
 * 未配置时聊天必然失败。界面若只显示空白的模型名，用户会归因为
 * 「AI 不聪明」或「网络坏了」，而不是「我没填地址」。
 * 所以未配置时整张卡变警示色，并直接给出下一步。
 *
 * ## 为什么收字符串而不是 `ModelSettings`
 *
 * 与 [WorkflowNodeCard]、[ToolCallCard] 同一取舍：`ui:widgets` 不依赖 `core:model`。
 * 而且这里真正要显示的只有三项，其中密钥本来就不在 `ModelSettings` 里
 * （它单独加密存取），硬套数据类反而要额外拼参数。
 *
 * @param baseUrl 服务商地址
 * @param model 模型 ID
 * @param apiKeyMasked 打码后的密钥；空串表示未设置
 * @param onClick 点击跳到模型设置页；null 表示不可点
 */
@Composable
fun ModelConfigCard(
    baseUrl: String,
    model: String,
    apiKeyMasked: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val hasModel = model.isNotBlank()
    val hasUrl = baseUrl.isNotBlank()
    val hasKey = apiKeyMasked.isNotBlank()
    val ready = hasModel && hasUrl && hasKey

    val accent = if (ready) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error
    // 用 Card 自己的 colors 表达状态色，而不是在内容里再套一层背景：
    // 套一层会让圆角对不齐，且点击涟漪只在内层生效。
    val colors = if (ready) {
        CardDefaults.defaultColors()
    } else {
        CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        )
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = colors,
        onClick = onClick,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 状态点：读文字要花时间，圆点颜色一眼就能分辨。
                Spacer(
                    Modifier
                        .size(8.dp)
                        .background(accent, CircleShape),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (ready) "已连接" else "未完成配置",
                    style = MiuixTheme.textStyles.body2,
                    color = accent,
                )
            }

            Spacer(Modifier.width(6.dp))

            // 三项事实全部列出，缺哪项就把哪项标成警示色并写「未填」。
            // 不隐藏缺失项 —— 隐藏会让人以为那一项不需要配。
            FactLine("模型", model.ifBlank { "未填" }, if (hasModel) null else accent)
            FactLine("地址", baseUrl.ifBlank { "未填" }, if (hasUrl) null else accent)
            FactLine("密钥", apiKeyMasked.ifBlank { "未设置" }, if (hasKey) null else accent)

            if (!ready) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "完成配置后才能开始对话；点这张卡去设置。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun FactLine(label: String, value: String, warnColor: Color?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(48.dp),
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.footnote1,
            color = warnColor ?: MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}