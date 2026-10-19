// 移植自 InstallerX-Revived ui/theme/MiuixSheetColors.kt（GPL-3.0-only）。
// 唯一改动：暗色判定从 InstallerTheme.isDark 换成 Praxis 自己的 LocalPraxisIsDark。
package dev.praxis.ui.widgets.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.praxis.ui.theme.LocalPraxisIsDark
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.isDynamicColor

/** 非动态色时的暗色卡片底色（深灰，与 miuix 深色表面同调）。 */
private val SheetCardDark = Color(0xFF1C1C1E)

/**
 * 弹层/卡片底色。
 *
 * 三档：动态取色用 surfaceContainer（跟壁纸走）；否则按明暗给固定色。
 */
@Composable
fun miuixSheetCardColor(): Color = when {
    isDynamicColor -> MiuixTheme.colorScheme.surfaceContainer
    LocalPraxisIsDark.current -> SheetCardDark
    else -> Color.White
}

/** 卡片配色（底色 + 内容色）。 */
@Composable
fun miuixSheetCardColors(
    contentColor: Color = MiuixTheme.colorScheme.onSurface,
): CardColors = CardColors(
    color = miuixSheetCardColor(),
    contentColor = contentColor,
)
