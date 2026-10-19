// 结构与数值移植自 InstallerX-Revived ui/page/miuix/widgets/MiuixDialog.kt 内的 SelectableRow（GPL-3.0-only）。
// 原文是 private；Praxis 的选择类弹层（模型切换、调色板选择）会复用，故提为 internal。
package dev.praxis.ui.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 可选中行：左侧文案 + 右侧勾选标记。
 *
 * 用 [BasicComponent] 而不是裸 Row：它自带 miuix 的行高、水波纹半径与
 * 按压缩放反馈，裸 Row 需自己补齐这些才能和列表其余行对齐。
 */
@Composable
internal fun MiuixSelectableRow(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicComponent(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (isSelected) {
                Icon(
                    modifier = Modifier.size(20.dp),
                    imageVector = MiuixIcons.Basic.Check,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                )
            }
        }
    }
}
