package dev.praxis.core.model.settings

/**
 * 主题模式。System 跟随系统；显式亮/暗则强制。
 */
enum class ThemeMode {
    System,
    Light,
    Dark,
}

/**
 * 取色源。
 *
 * 这个区分不是为了好看，而是因为 miuix 的取色分支行为不同：
 * `ThemeController.currentColors()` 在 Monet 模式下是
 * `keyColor?.let { colorsFromSeed(...) } ?: platformDynamicColors(dark)`。
 * 也就是说：**只要传了 keyColor，就永远不会去读系统壁纸色**。
 * 所以「跟随壁纸」必须让 keyColor 为 null。
 */
enum class ColorSource {
    /** 跟随壁纸：keyColor = null。 */
    Wallpaper,

    /** 自定义品牌色：keyColor = seedColor。 */
    Custom,
}

/**
 * 调色板风格。
 *
 * 数量必须与 miuix 0.9.4 的 `ThemePaletteStyle` 一致（9 种，无 Cmf 系）。
 * 照搬 InstallerX 的 10 种会直接编译失败。
 */
enum class PaletteStyle {
    TonalSpot,
    Neutral,
    Vibrant,
    Expressive,
    Content,
    Fidelity,
    Monochrome,
    Rainbow,
    FruitSalad,
}

/**
 * 颜色规范。miuix 0.9.4 只有两代；Spec2025 仅前四种调色板支持，其余自动降级。
 */
enum class ColorSpecVersion {
    Spec2021,
    Spec2025,
}

/**
 * 主题设置快照（不可变，供 UI 直接读）。
 *
 * `seedColorArgb` 用 Long：DataStore 与 StateFlow 里不夹带 Compose 类型，
 * 避免 core:model 被 Compose 污染（core 模块禁止 androidx.compose 导入）。
 */
data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.System,
    val colorSource: ColorSource = ColorSource.Wallpaper,
    val seedColorArgb: Long = DEFAULT_SEED_ARGB,
    val paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    val colorSpec: ColorSpecVersion = ColorSpecVersion.Spec2021,
) {
    companion object {
        /** 默认品牌蓝。 */
        const val DEFAULT_SEED_ARGB: Long = 0xFF1B6EF3
    }
}
