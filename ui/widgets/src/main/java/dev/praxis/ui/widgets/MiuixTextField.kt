package dev.praxis.ui.widgets

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.praxis.ui.theme.PraxisMotion
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MiuixHintTextField(value: String, onValueChange: (String) -> Unit, labelText: String, modifier: Modifier = Modifier) {
    // Setup InteractionSource to detect focus changes.
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    // Define colors and calculate animated border properties based on focus state.
    val cornerRadius = 16.dp
    val backgroundColor = MiuixTheme.colorScheme.surfaceContainer // Color when not focused
    val focusedBorderColor = MiuixTheme.colorScheme.primary // Color when focused

    // 聚焦态用 Fast 令牌而不是默认 spring：聚焦是即时反馈，
    // 默认 spring 会拖出一段肉眼可见的「边框慢慢长出来」。
    // 时长与曲线统一走 PraxisMotion，改节奏只改一处。
    val focusSpec = tween<Dp>(durationMillis = PraxisMotion.Durations.Fast, easing = PraxisMotion.Curves.Standard)
    val focusColorSpec = tween<Color>(durationMillis = PraxisMotion.Durations.Fast, easing = PraxisMotion.Curves.Standard)

    // Animate border width: 2dp when focused, 0dp when not focused.
    val borderWidth by animateDpAsState(targetValue = if (isFocused) 2.dp else 0.dp, animationSpec = focusSpec)

    // Animate border color: primary when focused, transparent when not (to match 0 width).
    val animatedBorderColor by animateColorAsState(
        targetValue = if (isFocused) focusedBorderColor else Color.Transparent,
        animationSpec = focusColorSpec,
    )

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(cornerRadius),
            )
            .border(
                width = borderWidth,
                color = animatedBorderColor,
                shape = RoundedCornerShape(cornerRadius),
            ),
        interactionSource = interactionSource,
        textStyle = TextStyle.Default.copy(
            textAlign = TextAlign.End, // Text inside innerTextField aligns right
            color = MiuixTheme.colorScheme.onBackground,
        ),
        cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
        singleLine = true,
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = labelText,
                    color = MiuixTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(end = 8.dp),
                )

                // Add contentAlignment = Alignment.CenterEnd to the Box wrapper.
                // This forces innerTextField to be placed at the end of the available space.
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterEnd, // Force alignment to the right edge
                ) {
                    innerTextField()
                }
            }
        },
    )
}
