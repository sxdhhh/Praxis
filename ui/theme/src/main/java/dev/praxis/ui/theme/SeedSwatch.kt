package dev.praxis.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 从 seed 派生一组「色卡预览」用色。
 *
 * ## 为什么这里不调 materialkolor / miuix 的取色器
 *
 * 上游 InstallerX 的做法是：色卡里每个 swatch 都跑一次完整的
 * `dynamicColorScheme(seed, style, specVersion)` 生成 scheme，再用
 * `primaryContainer / secondaryContainer / tertiaryContainer / primary`
 * 画扇形 + 中心圆；因为一次生成要几十毫秒，它额外加了
 * `ConcurrentHashMap` 缓存 + `produceState` + 后台线程 + 首帧降级 UI。
 *
 * Praxis 的场景不同：设置页一屏要显示 **18 个**色卡，进入页面即全量渲染。
 * 直接照搬会带来两个真实代价：
 *
 *  1. 首帧要算 18 份 scheme（后台线程也算），色卡会先闪一下降级态；
 *  2. 每次切换调色板 / 颜色规范，缓存 key 全变，18 份全部重算。
 *
 * 色卡的职责只是「让用户认出这个颜色、知道选中的是哪个」，
 * 不需要精确到 Material 的 container 色阶。所以这里用 **HCT 的近似**：
 * 保持 seed 的色相，按固定的亮度/饱和度档位派生三个容器色。
 *
 * ## 派生规则（与 Material 容器色的观感对齐）
 *
 * | 输出 | 亮度 | 饱和度 | 对应 Material 观感 |
 * |---|---|---|---|
 * | [containerMain] | seed 亮度向 0.90 靠 | 0.9× | `primaryContainer`（浅而饱和） |
 * | [containerAlt]  | 0.88 | 1.0× | `tertiaryContainer`（换色相 +40°） |
 * | [containerThird]| 0.92 | 0.7× | `secondaryContainer`（更淡） |
 * | [solid]         | seed 原样 | — | `primary`（中心圆） |
 *
 * 深色主题下把亮度整体压到 0.30~0.40 一档，否则白底亮色卡在黑底上会刺眼
 * —— 这也是上游 `isDarkForPreview` 恒为 `false` 的原因（它永远用亮色卡）。
 * Praxis 跟随主题：深色时用深色卡，与页面其它控件一致。
 */
data class SeedSwatch(
    /** 扇形①：上半圆。 */
    val containerMain: Color,
    /** 扇形②：右上 90°。 */
    val containerAlt: Color,
    /** 扇形③：右下 90°。 */
    val containerThird: Color,
    /** 中心实心圆（= seed 本身）。 */
    val solid: Color,
    /** 色卡外框（半透明主色）。 */
    val frame: Color,
)

/**
 * 生成色卡用色。
 *
 * @param seedArgb 0xAARRGGBB 形式的种子色。
 * @param dark 是否深色主题。
 */
fun seedSwatch(seedArgb: Long, dark: Boolean): SeedSwatch {
    val seed = Color(seedArgb)
    val (h, s, l) = rgbToHsl(seed)

    return if (dark) {
        SeedSwatch(
            containerMain = hslToColor(h, (s * 0.9f).coerceIn(0f, 1f), 0.38f),
            containerAlt = hslToColor((h + 40f) % 360f, (s * 0.8f).coerceIn(0f, 1f), 0.34f),
            containerThird = hslToColor(h, (s * 0.6f).coerceIn(0f, 1f), 0.44f),
            solid = seed,
            frame = seed.copy(alpha = 0.30f),
        )
    } else {
        SeedSwatch(
            containerMain = hslToColor(h, (s * 0.9f).coerceIn(0f, 1f), 0.80f),
            containerAlt = hslToColor((h + 40f) % 360f, (s * 0.8f).coerceIn(0f, 1f), 0.76f),
            containerThird = hslToColor(h, (s * 0.7f).coerceIn(0f, 1f), 0.88f),
            solid = seed,
            frame = seed.copy(alpha = 0.30f),
        )
    }
}

/** RGB → HSL。返回 `(hue 0..360, saturation 0..1, lightness 0..1)`。 */
private fun rgbToHsl(c: Color): Triple<Float, Float, Float> {
    val r = c.red
    val g = c.green
    val b = c.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    if (max == min) return Triple(0f, 0f, l)

    val d = max - min
    // 饱和度公式随亮度分两段：l > 0.5 时分母换成 2 - max - min，
    // 否则亮色（l 接近 1）会算出 > 1 的值导致颜色溢出。
    val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)

    val h = when (max) {
        r -> ((g - b) / d + if (g < b) 6f else 0f)
        g -> ((b - r) / d + 2f)
        else -> ((r - g) / d + 4f)
    } * 60f

    return Triple(h, s, l)
}

/** HSL → RGB。 */
private fun hslToColor(h: Float, s: Float, l: Float): Color {
    if (s == 0f) return Color(l, l, l)

    val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
    val p = 2f * l - q
    val hk = h / 360f

    fun hue(t0: Float): Float {
        var t = t0
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }

    return Color(
        red = hue(hk + 1f / 3f),
        green = hue(hk),
        blue = hue(hk - 1f / 3f),
    )
}

/**
 * 内置品牌色表。
 *
 * ## 为什么用 Long（ARGB）而不是 `Color`
 *
 * 这个表会被 `core:model` 的 `ThemeSettings` 语义共享（同一个 seed 要能
 * 存进 DataStore、进单测断言）。`Color` 是 Compose 类型，进不了 `core:model`。
 * 所以表本身用 Long，UI 侧再用 [seedSwatch] 转成可画的色。
 *
 * ## 色值来源
 *
 * 直接照抄 InstallerX `ui/theme/material/PresetColors.kt` 的 18 色
 * （Material 3 官方 tonal palette 的 primary40 档）。不自己调色：
 * 那 18 个值是被 M3 校准过的，自调会在深浅两种主题下都出偏色。
 *
 * ## `key` 为什么保留英文 id
 *
 * 与上游一致：翻译/改名时 key 不变，DataStore 里已存的选中值不会失效。
 * 展示名由 `seedColorLabel` 给出，两者分离。
 */
object PresetSeedColors {
    /**
     * 「默认」色 = `ThemeSettings` 的默认种子色。
     *
     * ## 为什么引用而不是再写一遍字面量
     *
     * 两处各写一个 0x…… 就一定会漂移：改了 `core:model` 的默认值之后，
     * 色卡里的「默认」还指着旧色，用户点「默认」得到的不是默认配色。
     * 这里直接引用，单一来源。
     */
    val DEFAULT_ARGB: Long = dev.praxis.core.model.settings.ThemeSettings.DEFAULT_SEED_ARGB

    /** `(key, argb)`，顺序即色卡显示顺序（与上游一致）。 */
    val all: List<Pair<String, Long>> = listOf(
        "default" to DEFAULT_ARGB,
        "pink" to 0xFFB94073,
        "red" to 0xFFBA1A1A,
        "orange" to 0xFF944A00,
        "amber" to 0xFF8C5300,
        "yellow" to 0xFF795900,
        "lime" to 0xFF5E6400,
        "green" to 0xFF006D39,
        "cyan" to 0xFF006A64,
        "teal" to 0xFF006874,
        "light_blue" to 0xFF00639B,
        "blue" to 0xFF335BBC,
        "indigo" to 0xFF5355A9,
        "purple" to 0xFF6750A4,
        "deep_purple" to 0xFF7E42A4,
        "blue_grey" to 0xFF575D7E,
        "brown" to 0xFF7D524A,
        "grey" to 0xFF5F6162,
    )

    /** key → 中文展示名。`default` 显示为「默认」，其余直译。 */
    fun labelOf(key: String): String = when (key) {
        "default" -> "默认"
        "pink" -> "粉"
        "red" -> "红"
        "orange" -> "橙"
        "amber" -> "琥珀"
        "yellow" -> "黄"
        "lime" -> "青柠"
        "green" -> "绿"
        "cyan" -> "青"
        "teal" -> "蓝绿"
        "light_blue" -> "浅蓝"
        "blue" -> "蓝"
        "indigo" -> "靛"
        "purple" -> "紫"
        "deep_purple" -> "深紫"
        "blue_grey" -> "蓝灰"
        "brown" -> "棕"
        "grey" -> "灰"
        else -> key
    }
}