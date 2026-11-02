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

/**
 * 多选操作条上的一个紧凑按钮（中性 / 危险两种语气）。
 *
 * ## 为什么不复用 [MiuixTextActionButton]（2026-11-03）
 *
 * 那个组件的定位是「单条删除的确认按钮」：
 *  - 内部 `fillMaxWidth()` 铺满；
 *  - `colors` 写死 errorContainer / onErrorContainer（危险操作语义）。
 *
 * 多选操作条拿它拼三个按钮时，结果是**三块铺满宽度的大红色块** ——
 * 真机截图证实了这点（全选/置顶/删除/完成 全是刺眼红块）。
 * 而 DeepSeek 那种是细线条文字操作条。
 *
 * 与其把一个「删除确认」专用组件改造成万能组件（会让原有调用点的语义变糊），
 * 不如新增这一个：语气由 [danger] 决定，宽度由调用方用 Modifier 控制。
 *
 * @param danger true = 破坏性操作（删除），用 error 色；false = 中性（全选/置顶/完成）。
 */
@Composable
fun SidebarActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    val container = if (danger) {
        MiuixTheme.colorScheme.errorContainer
    } else {
        MiuixTheme.colorScheme.secondaryContainer
    }
    val content = if (danger) {
        MiuixTheme.colorScheme.onErrorContainer
    } else {
        MiuixTheme.colorScheme.onSecondaryContainer
    }
    TextButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.textButtonColors(
            color = container,
            textColor = content,
        ),
        // 32dp 而不是默认高度：三条操作条加起来不能把抽屉底部的列表挤没。
        minHeight = 32.dp,
    )
}
