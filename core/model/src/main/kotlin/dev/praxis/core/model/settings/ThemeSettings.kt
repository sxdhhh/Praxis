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
 * 预测性返回动画档位。
 *
 * 照抄 InstallerX `PredictiveBackAnimation`（五档），但**去掉 None**：
 * 上游给 None 是「关掉预测性返回」，而 Praxis 的入口只有侧滑返回，
 * 关掉它等于「手势划到一半没有任何跟手反馈」，用户会以为卡了。
 * 所以 Praxis 只保留四种真正能看的动画，默认 [Aosp]。
 *
 * 为什么默认 AOSP 而不是 miuix：AOSP 那套是系统返回的观感
 * （跟手缩放 + 96dp 漂移 + 松手弹跳），用户对它有肌肉记忆；
 * miuix 自带的 MiuixDefault 是平台风格，在这里反而更像「第三方 App」。
 */
enum class PredictiveBackAnimation {
    /** 系统 AOSP 观感：跟手缩放 + 漂移 + 松手弹跳。 */
    Aosp,

    /** miuix 自带转场。 */
    Miuix,

    /** 缩小 + 按手势方向移出屏幕。 */
    Scale,

    /** 缩放 + 侧移的轻量版（KernelSU 经典）。 */
    Classic,
    ;

    companion object {
        /**
         * 从持久化值还原。
         *
         * 用「按名查找 + 兜底」而不是 `valueOf`：降级安装或枚举改名时
         * `valueOf` 会抛异常把设置页打挂（这是本项目已确认过的坑）。
         */
        fun fromNameOrDefault(name: String?): PredictiveBackAnimation =
            entries.firstOrNull { it.name == name } ?: Aosp
    }
}

/**
 * Scale / Classic 档位下，返回时**往哪一侧移出**。
 *
 * 只有这两个档位会用到；AOSP 与 miuix 由手势方向自己决定。
 */
enum class PredictiveBackExitDirection {
    /** 跟随手势：从左边缘划就从左出，从右边缘划就从右出。 */
    FollowGesture,

    /** 固定往右出。 */
    AlwaysRight,

    /** 固定往左出。 */
    AlwaysLeft,
    ;

    companion object {
        fun fromNameOrDefault(name: String?): PredictiveBackExitDirection =
            entries.firstOrNull { it.name == name } ?: FollowGesture
    }
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
    /** 预测性返回动画档位。 */
    val predictiveBackAnimation: PredictiveBackAnimation = PredictiveBackAnimation.Aosp,
    /** Scale / Classic 档位下的移出方向。 */
    val predictiveBackExitDirection: PredictiveBackExitDirection =
        PredictiveBackExitDirection.FollowGesture,
) {
    companion object {
        /** 默认品牌蓝。 */
        const val DEFAULT_SEED_ARGB: Long = 0xFF1B6EF3
    }
}
