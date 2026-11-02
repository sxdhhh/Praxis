package dev.praxis.ui.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 悬浮图标按钮的「有底」版本：圆形/胶囊底 + 描边 + 内阴影。
 *
 * ## 为什么需要它（2026-11-03）
 *
 * 顶栏的返回键原本是裸 `IconButton` —— 只有图标、没有任何底色。
 * 它悬浮在会滚动的页面内容之上，滚动时图标直接压在文字/图片上，
 * 既看不清、也看不出「这是个可点的东西」。
 *
 * ## 为什么不用已有的 LiquidGlassButton
 *
 * `glass/kyant/LiquidButton.kt` 的 `LiquidGlassButton` 需要 `backdrop: Backdrop`
 * 采样源，内部跑 vibrancy + blur + lens 三段效果，是给「大块玻璃浮层」用的。
 * 一个 40dp 的返回键挂这套，每帧都要做背景采样与模糊 ——
 * 和 `ToolCallCard` 那次 squircle 掉帧是同一类代价，不值当。
 *
 * 这里只做**纯绘制**：`background` + `border` + 一层内阴影，零采样、零离屏。
 *
 * ## 为什么内阴影是「画」出来而不是真的 shadow
 *
 * Compose 的 `shadow` 只能做外投影，做不出「玻璃凹下去」的内阴影。
 * 这里用 `drawWithContent` 在内容之上叠一个半透明描边环，
 * 视觉上就是「边缘压暗」= 内阴影。成本是一次边框绘制。
 *
 * @param contentDescription 无障碍描述。
 */
@Composable
fun FloatingIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = MiuixIcons.Regular.Back,
    contentDescription: String = "返回",
    iconTint: Color = MiuixTheme.colorScheme.onSurface,
) {
    val shape = CircleShape
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(40.dp)
            .clip(shape)
            // 半透明表面：压住底下的内容但还能透出一点，
            // 与顶栏的模糊/实色都能协调。
            .background(MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f))
            .border(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.28f),
                shape = shape,
            )
            // 内阴影：沿边缘压暗一圈，做出「玻璃凹进去」的厚度感。
            .drawWithContent {
                drawContent()
                drawCircle(
                    color = Color.Black.copy(alpha = 0.06f),
                    radius = size.minDimension / 2f,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()),
                )
            },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(20.dp),
        )
    }
}
