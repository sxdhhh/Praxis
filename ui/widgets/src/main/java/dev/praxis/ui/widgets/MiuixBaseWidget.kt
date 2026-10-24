package dev.praxis.ui.widgets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.praxis.ui.design.glass.kyant.LiquidToggle
import dev.praxis.ui.design.glass.kyant.rememberPageBackdropOrFallback
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CheckboxDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 开关行 —— **全项目开关的唯一实现**。
 *
 * ## 为什么把 miuix Switch 换成 LiquidToggle
 *
 * 用户要求「所有开关的设计对齐 apixiaoyuan，以后添加的所有开关都一样」。
 * apixiaoyuan 的开关是移植自 Kyant0/AndroidLiquidGlass 的**液态玻璃开关**
 * （[dev.praxis.ui.design.glass.kyant.LiquidToggle]，296 行），
 * 而不是 miuix 的 `Switch`。
 *
 * 视觉差别：miuix Switch 是扁平的 Material 滑块；LiquidToggle 的滑块是
 * **真玻璃**——静止时透亮、按下时出现模糊/折射/高光/内阴影，并且可以**拖拽**
 * （带弹簧与速度挤压）。
 *
 * ## 为什么改在这里而不是逐个调用点
 *
 * `MiuixSwitchWidget` 已经是全项目开关的收敛点（12 处调用），
 * 改这一个函数就让所有现有开关升级，也保证「以后新增的开关自动一样」。
 * 逐个改调用点必然会漏，且新写的页面又会用回 miuix Switch。
 *
 * ## 采样源
 *
 * 用 `rememberPageBackdropOrFallback`：页面 provide 了 `LocalKyantBackdrop`
 * 就折射真实内容，没有就回退纯色（不会变黑块）。调用点零改动。
 */
@Composable
fun MiuixSwitchWidget(
    icon: ImageVector? = null,
    title: String,
    description: String? = null,
    enabled: Boolean = true,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val toggleAction = {
        if (enabled) {
            onCheckedChange(!checked)
        }
    }

    // 开关的玻璃采样源：回退色用 surface（页面实底色），
    // 这样未 provide 采样源的页面仍呈现「半透明表层」的合理外观。
    val toggleBackdrop = rememberPageBackdropOrFallback(MiuixTheme.colorScheme.surface)

    BasicComponent(
        title = title,
        summary = description,
        enabled = enabled,
        onClick = toggleAction,
        endActions = {
            LiquidToggle(
                selected = { checked },
                onSelect = onCheckedChange,
                backdrop = toggleBackdrop,
                enabled = enabled,
            )
        },
    )
}

/**
 * A BasicComponent variant that displays a Checkbox as its right action.
 * The entire row is clickable to toggle the checked state.
 *
 * @param icon Optional icon for the component (Note: not passed to BasicComponent, matching MiuixSwitchWidget's provided structure).
 * @param title The main text title.
 * @param description The supporting text (summary).
 * @param enabled Controls the enabled state of the component and the Checkbox.
 * @param checked The current checked state of the Checkbox.
 * @param onCheckedChange A lambda called when the checked state changes.
 */
@Composable
fun MiuixCheckboxWidget(
    icon: ImageVector? = null,
    title: String? = null,
    description: String? = null,
    enabled: Boolean = true,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val toggleAction = {
        if (enabled) {
            onCheckedChange(!checked)
        }
    }

    BasicComponent(
        title = title,
        summary = description,
        enabled = enabled,
        onClick = toggleAction,
        endActions = {
            Checkbox(
                state = ToggleableState(value = checked),
                onClick = run {
                    { onCheckedChange(!checked) }
                },
                modifier = Modifier,
                colors = CheckboxDefaults.checkboxColors(),
                enabled = enabled,
            )
        },
    )
}

@Composable
fun MiuixMultiApkCheckboxWidget(
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val minHeight = 64.dp
    val horizontalPadding = 16.dp
    val verticalPadding = 16.dp
    val textToCheckboxPadding = 16.dp
    val textSpacing = 2.dp

    val toggleAction = {
        if (enabled) {
            onCheckedChange(!checked)
        }
    }

    val contentAlpha = if (enabled) 1f else 0.4f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = minHeight)
            .clickable(
                enabled = enabled,
                onClick = toggleAction,
                role = Role.Checkbox,
            )
            .padding(
                start = horizontalPadding,
                end = horizontalPadding,
                top = verticalPadding,
                bottom = verticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(textSpacing),
        ) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            summary?.let {
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.subtitle,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Box(
            modifier = Modifier.padding(start = textToCheckboxPadding),
        ) {
            Checkbox(
                state = ToggleableState(value = checked),
                onClick = run { { onCheckedChange(!checked) } },
                modifier = Modifier,
                colors = CheckboxDefaults.checkboxColors(),
                enabled = enabled,
            )
        }
    }
}
