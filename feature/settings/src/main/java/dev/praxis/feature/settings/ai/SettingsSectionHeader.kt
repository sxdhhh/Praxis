package dev.praxis.feature.settings.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 分区头部：图标 + 标题 + 可选副标题。
 *
 * ## 1:1 对齐 Operit `SettingsSectionHeader`
 *
 * 上游定义在 `ModelApiSettingsSection.kt:1520`：
 *
 * ```
 * [Icon 24dp, primary] [Spacer 8dp] [Column: 标题 titleMedium Bold
 *                                         副标题 bodySmall onSurfaceVariant]
 * ```
 *
 * ## 为什么要替换 miuix 的 `SmallTitle`
 *
 * `SmallTitle` 只是一行加粗小字，**没有图标、没有副标题**。于是：
 *
 *  - 一整页七个分区全靠文字区分，扫视时没有任何视觉锚点；
 *  - 「参数」「上下文」「思考」这些词本身不自解释，用户得点进去才知
 *    道里面是什么（比如「上下文」到底是长度还是压缩阈值？）。
 *
 * Operit 每个分区都带图标与一句副标题，正是为了解决这两点。
 *
 * ## 为什么图标用 24dp 而不是更常见的 20/16
 *
 * 跟着上游取 24dp。它是**分区级**标题（比行内的 16dp 图标高一级），
 * 尺寸差本身就是层级语言：24 说的是“这是一组的开始”，16 说的是“这是一行”。
 *
 * ## 为什么允许 subtitle 为 null
 *
 * 有的分区标题已经说清了一切（如「接口地址」），硬凑一句副标题
 * 反而是噪音。上游也是 `subtitle: String? = null`。
 *
 * @param icon 分区图标（与内容语义对应，不要拿同一个图标敷了事）
 * @param title 分区名
 * @param subtitle 一句话说明这个分区管什么（可选）
 */
@Composable
internal fun SettingsSectionHeader(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // 与 miuix 的 SmallTitle 同一套外边距：左右 16dp、上 12dp、下 4dp。
            // 不加这个就会与上下卡片贴死，分区之间失去呼吸感。
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                // 对齐 Material3 titleMedium：16sp Medium。miuix 的 textStyles
                // 里没有完全对应的一档，所以直接给 fontSize + weight。
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}
