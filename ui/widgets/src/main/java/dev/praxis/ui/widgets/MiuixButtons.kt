package dev.praxis.ui.widgets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * A standardized back button for the application, styled for MIUIX.
 *
 * @param onClick The lambda to be executed when the button is clicked.
 * @param modifier The Modifier to be applied to this button.
 * @param icon The vector asset to be displayed inside the button. Defaults to a back arrow.
 * @param iconTint The tint to be applied to the icon. Defaults to the current content color.
 * @param contentDescription The content description for accessibility.
 */
@Composable
fun MiuixBackButton(
    modifier: Modifier = Modifier,
    icon: ImageVector = MiuixIcons.Regular.Back,
    iconTint: Color = LocalContentColor.current,
    contentDescription: String = "返回",
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconTint,
        )
    }
}

/**
 * 行内确认按钮：只在 [visible] 为真时出现，用于「按下危险操作 → 原地变成确认态」。
 *
 * ## 为什么不用对话框
 *
 * 侧边栏本身就是一层覆盖层。在覆盖层上再叠 Modal 会让返回键的归属变得不可预测
 * （先关弹窗还是先关抽屉？），而且 miuix 的对话框走独立 Window，
 * 在抽屉的 slide 动画期间弹出会看到「抽屉动、弹窗不动」的错位。
 *
 * 行内展开则是同一个布局里长出来的，没有层级与窗口问题。
 *
 * ## 视觉用 error 语义色
 *
 * 删除是不可撤销操作（消息由外键级联清除），按 MIUI 习惯用 `error` 系配色，
 * 与「普通文本按钮」在颜色上就能区分，避免连点两下误删。
 *
 * @param visible 是否展开；为 false 时整个按钮不占位（不是透明占位）。
 * @param text 按钮文案。
 * @param onClick 确认回调。
 */
@Composable
fun MiuixTextActionButton(
    visible: Boolean,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // AnimatedVisibility 而不是 `if`：删除确认是原地长出来的，直接 if 会「跳一下」，
    // 用默认的 expandVertically + fadeIn 让高度平滑过渡。
    AnimatedVisibility(visible = visible) {
        TextButton(
            text = text,
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            // 危险操作色：container 用 errorContainer，文字用 onErrorContainer，
            // 保证在浅色/深色与莫奈取色下都有对的对比度（不写死颜色）。
            colors = ButtonDefaults.textButtonColors(
                color = MiuixTheme.colorScheme.errorContainer,
                textColor = MiuixTheme.colorScheme.onErrorContainer,
            ),
            minHeight = 32.dp,
        )
    }
}
