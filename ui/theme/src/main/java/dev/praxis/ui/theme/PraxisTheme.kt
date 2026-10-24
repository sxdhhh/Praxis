package dev.praxis.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
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
    // ★ 切主题不重建 UI 树（照抄 InstallerX `InstallerTheme.kt:92-96`）。
    //
    // ## 为什么需要它
    //
    // 主题切换会让 `MaterialTheme` / `MiuixTheme` 的 CompositionLocal 变化，
    // 正常情况下子树的 `remember` 全部失效 —— 表现为：切主题时滚动位置回到顶部、
    // 输入框失焦、正在播放的动画重头开始。
    //
    // `movableContentOf` 把内容包成一个**可搬移的组合单元**：只要它还是
    // 被同一处调用，即使上层 provider 变了，Compose 也会复用原有的 slot 与状态，
    // 而不是销毁重建。这是「切主题不闪」的全部原理。
    //
    // 注意它必须 `remember` 一次，不能每次重组新建 —— 新建就等于换了个单元，
    // 状态照样丢。
    val preservedContent = remember {
        movableContentOf<@Composable () -> Unit> { targetContent ->
            targetContent()
        }
    }

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

    CompositionLocalProvider(
        LocalThemeSettings provides settings,
        LocalPraxisIsDark provides isDark,
    ) {
        // ── 嵌套顺序：MiuixTheme 在外，MaterialTheme 在内 ──────────────
        //
        // 顺序是有语义的，不能随便调：
        //  - `MiuixTheme` 在外 → 它内部才能读 `MiuixTheme.colorScheme`，
        //    把同一份颜色映射成 M3 色板（见 [rememberM3FromMiuix]）；
        //  - 全站控件优先读 miuix，因此 miuix 必须是色彩的唯一来源；
        //  - `MaterialTheme` 只在「确实用了 M3 组件」的子树里被读到，
        //    放在内层不影响任何 miuix 控件。
        //
        // 旧顺序是 `MaterialTheme { MiuixTheme { ... } }`，M3 在外层，
        // 于是它拿不到 miuix 的颜色，只能自己算（当年是硬编码蓝）。
        MiuixTheme(controller = controller) {
            val m3Scheme = rememberM3FromMiuix(isDark)
            MaterialTheme(colorScheme = m3Scheme) {
                // 走 preservedContent 而不是直接传 content：
                // 这样切主题（settings 变化 → 新 controller）不会销毁子树状态。
                preservedContent(content)
            }
        }
    }
}

/**
 * 计算 M3 语义色 —— **直接取 miuix 的颜色**（2026-10-23 重构）。
 *
 * ## 为什么不能各算各的
 *
 * 旧实现里两条路各用一套生成器：
 *
 * ```
 * seedColor → miuix ThemeController   → MiuixTheme.colorScheme   （跟种子）
 * seedColor → materialKolor / 硬编码蓝 → MaterialTheme.colorScheme（不跟种子）
 * ```
 *
 * 即便让 materialKolor 也用同一个 seed，两家算法不同，**色阶仍然不同族**：
 * 同一个 seed 下 miuix 的 `surface` 与 materialKolor 的 `surface` 不是同一个色，
 * 于是「页面底色」和「控件底色」永远差一层。
 *
 * 用户的要求是「把 m3 取色 miuix」—— 即 M3 不再自己算，**照抄 miuix 的值**。
 * 这样全站只有一个色彩来源：`MiuixTheme.colorScheme`。
 *
 * ## 实现方式：把嵌套顺序反过来
 *
 * 之前是 `MaterialTheme { MiuixTheme { content } }`，M3 在外层，读不到 miuix 的色。
 * 现在改成 `MiuixTheme { MaterialTheme(m3 = 由 miuix 映射) { content } }`：
 * `MiuixTheme` 在外，函数体内就能读 `MiuixTheme.colorScheme`，再拿它构造 M3 色板。
 *
 * 这不影响任何现有控件：miuix 组件读自己的 theme，M3 组件读 MaterialTheme，
 * 两者都在 content 之上。
 *
 * ## 映射原则（不是机械 1:1，缺字段要挑语义最近的）
 *
 * | M3 角色 | 取 miuix 的 | 理由 |
 * |---|---|---|
 * | `primary` / `onPrimary` | 同名 | 直接对应 |
 * | `primaryContainer` 系 | 同名 | 直接对应 |
 * | `inversePrimary` | `primaryVariant` | M3 无对应，取同族亮/暗变体 |
 * | `secondary` 系 | 同名 | 直接对应 |
 * | **`tertiary`** | **`tertiaryContainer`** | miuix 没有 `tertiary`（只有 container 系），取容器色当基色 |
 * | `onTertiary` | `onTertiaryContainer` | 同上 |
 * | `surfaceVariant` | 同名 | 直接对应 |
 * | **`onSurfaceVariant`** | **`onSurfaceVariantSummary`** | miuix 把 variant 文字拆成 Summary/Actions 两档，它承担「次要正文」 |
 * | `outlineVariant` | `dividerLine` | M3 的 outlineVariant 就是分割线色 |
 * | `scrim` | `windowDimming` | 遮罩层，语义一致 |
 * | `inverseSurface` | `onSurface` | M3 的 inverse 是「互换明暗」，miuix 无此概念，用互换表达 |
 * | `inverseOnSurface` | `surface` | 同上 |
 * | `surfaceContainer(Low/…/Highest)` | miuix 的四级容器 | miuix 只有 4 级，M3 有 5 级 → 最低两级都取 surface |
 */
@Composable
private fun rememberM3FromMiuix(isDark: Boolean): ColorScheme {
    val c = MiuixTheme.colorScheme
    // key 只用 isDark：颜色值本身来自 MiuixTheme.colorScheme，它变了自己会重组。
    return remember(isDark, c.primary, c.surface, c.background, c.tertiaryContainer) {
        val base = if (isDark) darkColorScheme() else lightColorScheme()
        base.copy(
            primary = c.primary,
            onPrimary = c.onPrimary,
            primaryContainer = c.primaryContainer,
            onPrimaryContainer = c.onPrimaryContainer,
            // M3 的 inversePrimary 用于「反色底上的主色」，取同族的 variant。
            inversePrimary = c.primaryVariant,

            secondary = c.secondary,
            onSecondary = c.onSecondary,
            secondaryContainer = c.secondaryContainer,
            onSecondaryContainer = c.onSecondaryContainer,

            // ★ miuix 没有 tertiary 基色，只有 container 系。
            tertiary = c.tertiaryContainer,
            onTertiary = c.onTertiaryContainer,
            tertiaryContainer = c.tertiaryContainer,
            onTertiaryContainer = c.onTertiaryContainer,

            background = c.background,
            onBackground = c.onBackground,

            surface = c.surface,
            onSurface = c.onSurface,
            surfaceVariant = c.surfaceVariant,
            onSurfaceVariant = c.onSurfaceVariantSummary,
            // surfaceTint 用于 elevation 叠加，M3 惯例取 primary。
            surfaceTint = c.primary,

            inverseSurface = c.onSurface,
            inverseOnSurface = c.surface,

            error = c.error,
            onError = c.onError,
            errorContainer = c.errorContainer,
            onErrorContainer = c.onErrorContainer,

            outline = c.outline,
            outlineVariant = c.dividerLine,
            scrim = c.windowDimming,

            // 容器色阶：miuix 4 级 → M3 5 级，最低两级都取 surface。
            surfaceContainerLowest = c.surface,
            surfaceContainerLow = c.surface,
            surfaceContainer = c.surfaceContainer,
            surfaceContainerHigh = c.surfaceContainerHigh,
            surfaceContainerHighest = c.surfaceContainerHighest,
            // 亮/暗极值：M3 用来做层次对比，miuix 无对应档，取最近的两级。
            surfaceBright = c.surfaceContainerHighest,
            surfaceDim = c.surface,
        )
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
