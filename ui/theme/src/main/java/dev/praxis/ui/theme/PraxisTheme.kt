package dev.praxis.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.praxis.core.model.settings.ColorSource
import dev.praxis.core.model.settings.ColorSpecVersion
import dev.praxis.core.model.settings.PaletteStyle
import dev.praxis.core.model.settings.ThemeMode
import dev.praxis.core.model.settings.ThemeSettings
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * 当前生效的主题设置快照。
 *
 * 供页面读取「用户选了什么」，而不是去猜颜色反推。
 */
val LocalThemeSettings = staticCompositionLocalOf { ThemeSettings() }

/** 当前是否暗色（已解析 System 之后的绝对值）。 */
val LocalPraxisIsDark = staticCompositionLocalOf { false }

/**
 * Praxis 主题门面（双引擎）。
 *
 * 引擎分工：
 *  - [ThemeSettings.useMiuixEngine] = true 走 miuix（`MiuixTheme` + `ThemeController`），
 *    负责控件外观；M3 语义色仍然提供，保证 Material3 组件不断色。
 *  - 为 false 时只走 M3（materialKolor 自己算调色板），miuix 控件用 miuix 的默认浅/深色兜底。
 *
 * 两个引擎共享同一份 seed 与同一份亮度判定，所以切引擎时观感不会突变。
 *
 * 莫奈取色的关键行为（源码 `ThemeController.currentColors()` 决定，不能想当然）：
 *  - `ColorSchemeMode.Monet*` 分支里是 `keyColor?.let { colorsFromSeed(...) } ?: platformDynamicColors(dark)`。
 *    也就是说：**只要传了 keyColor，就永远不会去读系统壁纸色**。
 *  - 因此「跟随壁纸」必须是 keyColor = null；「自定义取色」才传 seed。
 *    这正是 [ThemeSettings.keyColorOrNull] 存在的意义。
 */
@Composable
fun PraxisTheme(
    settings: ThemeSettings = ThemeSettings(),
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (settings.mode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    val miuixMode = when (settings.mode) {
        ThemeMode.System -> ColorSchemeMode.MonetSystem
        ThemeMode.Light -> ColorSchemeMode.MonetLight
        ThemeMode.Dark -> ColorSchemeMode.MonetDark
    }

    val controller = remember(settings, isDark) {
        ThemeController(
            colorSchemeMode = miuixMode,
            keyColor = settings.keyColorOrNull,
            colorSpec = settings.colorSpec.toMiuix(),
            paletteStyle = settings.paletteStyle.toMiuix(),
            isDark = isDark,
        )
    }

    val m3Scheme = rememberM3Scheme(settings, isDark)

    CompositionLocalProvider(
        LocalThemeSettings provides settings,
        LocalPraxisIsDark provides isDark,
    ) {
        MaterialTheme(colorScheme = m3Scheme) {
            MiuixTheme(controller = controller, content = content)
        }
    }
}

/**
 * 计算 M3 语义色。
 *
 * 优先用系统动态色（Android 12+，且用户选择「跟随壁纸」时），
 * 否则用 materialKolor 从 seed 生成，最后回落到静态蓝。
 */
@Composable
private fun rememberM3Scheme(settings: ThemeSettings, isDark: Boolean): ColorScheme {
    val context = LocalContext.current
    val followWallpaper = settings.colorSource == ColorSource.Wallpaper

    return remember(settings, isDark, context) {
        when {
            followWallpaper && PraxisCapabilities.dynamicColorSupported ->
                if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

            isDark -> darkColorScheme(primary = Color(0xFF7FA9FF))

            else -> lightColorScheme(primary = Color(0xFF1B6EF3))
        }
    }
}

/** Praxis 调色板 → miuix 调色板。 */
internal fun PaletteStyle.toMiuix(): ThemePaletteStyle = when (this) {
    PaletteStyle.TonalSpot -> ThemePaletteStyle.TonalSpot
    PaletteStyle.Neutral -> ThemePaletteStyle.Neutral
    PaletteStyle.Vibrant -> ThemePaletteStyle.Vibrant
    PaletteStyle.Expressive -> ThemePaletteStyle.Expressive
    PaletteStyle.Content -> ThemePaletteStyle.Content
    PaletteStyle.Fidelity -> ThemePaletteStyle.Fidelity
    PaletteStyle.Monochrome -> ThemePaletteStyle.Monochrome
    PaletteStyle.Rainbow -> ThemePaletteStyle.Rainbow
    PaletteStyle.FruitSalad -> ThemePaletteStyle.FruitSalad
}

/** 颜色规范映射：Praxis 只暴露 2021/2025 两代，与 miuix 0.9.4 对齐。 */
internal fun ColorSpecVersion.toMiuix(): ThemeColorSpec = when (this) {
    ColorSpecVersion.Spec2021 -> ThemeColorSpec.Spec2021
    ColorSpecVersion.Spec2025 -> ThemeColorSpec.Spec2025
}
