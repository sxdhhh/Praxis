package dev.praxis.ui.widgets

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 流式文本：打字机式追加 + 末尾闪烁光标。
 *
 * ## 为什么不用 `AnimatedContent` 逐字动画
 *
 * 流式场景下文本每 100ms 追加一次。给每次追加做进入动画会导致
 * 「每个字都弹一下」，反而比直接改文本更抖。这里只做**光标闪烁**，
 * 文本本身直接替换 —— 这也是 ChatGPT / Claude 的实际做法。
 *
 * ## 光标为什么是全高方块而不是下划线
 *
 * 中文字体行高不均匀，下划线在不同字号下会忽高忽低；
 * 全高方块锚定在行首基线，换字号也不跳。
 *
 * @param text 当前已收到的完整文本
 * @param streaming 是否仍在写入；false 时不显示光标
 * @param style 文本样式
 */
@Composable
fun StreamingText(
    text: String,
    streaming: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = MiuixTheme.textStyles.body2,
    color: Color = MiuixTheme.colorScheme.onSurface,
) {
    // 还没收到任何内容时给一个占位，避免气泡是一个空壳看着像卡死。
    val display = if (streaming && text.isEmpty()) "…" else text

    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        Text(text = display, style = style, color = color)
        if (streaming) {
            Spacer(Modifier.width(2.dp))
            BlinkingCursor(color = color)
        }
    }
}

/** 闪烁光标：600ms 一次呼吸，用 alpha 而不是切换可见性（避免布局跳动）。 */
@Composable
private fun BlinkingCursor(color: Color) {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cursorAlpha",
    )
    Box(
        Modifier
            .alpha(alpha)
            .size(width = 2.dp, height = 14.dp)
            .background(color, RoundedCornerShape(1.dp)),
    )
}