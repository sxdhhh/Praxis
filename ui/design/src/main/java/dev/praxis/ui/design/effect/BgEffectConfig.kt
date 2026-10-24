package dev.praxis.ui.design.effect

/**
 * 背景渐变预设。**数值逐字照抄 InstallerX `ui/library/effect/BgEffectConfig.kt`**。
 *
 * ## 这些 floatArray 是什么
 *
 * 着色器（[OS3_BG_FRAG] / [OS2_BG_FRAG]）用 4 个「高斯光斑」混合出背景色：
 *
 * | 字段 | 长度 | 含义 |
 * |---|---|---|
 * | [Config.points] | 12 | 4 个光斑，每个 `(x, y, radius)` 归一化到视口 |
 * | [Config.colors1..3] | 各 16 | 4 个光斑的 RGBA；三套色按 [Config.colorInterpPeriod] 循环插值 |
 * | [Config.lightOffset] | — | 噪声驱动提亮/压暗的幅度（暗色主题给负值） |
 * | [Config.saturateOffset] | — | 噪声驱动去饱和的幅度 |
 * | [Config.pointOffset] | — | 光斑上下左右漂移的幅度（`sin/cos(animTime)`） |
 *
 * ## 为什么数值不能自己调
 *
 * 这 8 组值是上游按 OS2（MIUI 12）与 OS3（MIUI 14+）的系统级壁纸效果**反向标定**的。
 * 手调任意一个数都会让整体色相偏掉：光斑的 radius 与 points 互相咬合（相邻光斑必须
 * 有重叠才能混成连续渐变），改一个 radius 就会出现一圈硬边。
 *
 * 所以这里逐字保留。要改观感请改 [Config.pointOffset]（漂移幅度）或
 * `BgEffectPainter.U_ALPHA_MULTI`（整体透明度），不要碰 points / colors。
 *
 * ## 与上游的差异
 *
 * 上游把 `OS3_PAD_DARK` 写成顶层 `private val`（缩进也因此不同于其它几个），
 * 这是它的笔误，不影响行为。这里统一放进 object 内。
 */
object BgEffectConfig {

    class Config(
        val points: FloatArray,
        val colors1: FloatArray,
        val colors2: FloatArray,
        val colors3: FloatArray,
        val colorInterpPeriod: Float,
        val lightOffset: Float,
        val saturateOffset: Float,
        val pointOffset: Float,
    )

    // ── OS2（MIUI 12）────────────────────────────────────────────

    private val OS2_PHONE_LIGHT_COLORS = floatArrayOf(
        0.57f, 0.76f, 0.98f, 1.0f, 0.98f, 0.85f, 0.68f, 1.0f,
        0.98f, 0.75f, 0.93f, 1.0f, 0.73f, 0.70f, 0.98f, 1.0f,
    )

    private val OS2_PHONE_LIGHT = Config(
        points = floatArrayOf(0.67f, 0.42f, 1.0f, 0.69f, 0.75f, 1.0f, 0.14f, 0.71f, 0.95f, 0.14f, 0.27f, 0.8f),
        colors1 = OS2_PHONE_LIGHT_COLORS,
        colors2 = OS2_PHONE_LIGHT_COLORS,
        colors3 = OS2_PHONE_LIGHT_COLORS,
        colorInterpPeriod = 100f,
        lightOffset = 0.1f,
        saturateOffset = 0.2f,
        pointOffset = 0.1f,
    )

    private val OS2_PHONE_DARK_COLORS = floatArrayOf(
        0.0f, 0.31f, 0.58f, 1.0f, 0.53f, 0.29f, 0.15f, 1.0f,
        0.46f, 0.06f, 0.27f, 1.0f, 0.16f, 0.12f, 0.45f, 1.0f,
    )

    private val OS2_PHONE_DARK = Config(
        points = floatArrayOf(0.63f, 0.50f, 0.88f, 0.69f, 0.75f, 0.80f, 0.17f, 0.66f, 0.81f, 0.14f, 0.24f, 0.72f),
        colors1 = OS2_PHONE_DARK_COLORS,
        colors2 = OS2_PHONE_DARK_COLORS,
        colors3 = OS2_PHONE_DARK_COLORS,
        colorInterpPeriod = 100f,
        lightOffset = -0.1f,
        saturateOffset = 0.2f,
        pointOffset = 0.1f,
    )

    private val OS2_PAD_LIGHT_COLORS = floatArrayOf(
        0.57f, 0.76f, 0.98f, 1.0f, 0.98f, 0.85f, 0.68f, 1.0f,
        0.98f, 0.75f, 0.93f, 0.95f, 0.73f, 0.70f, 0.98f, 0.90f,
    )

    private val OS2_PAD_LIGHT = Config(
        points = floatArrayOf(0.67f, 0.37f, 0.88f, 0.54f, 0.66f, 1.0f, 0.37f, 0.71f, 0.68f, 0.28f, 0.26f, 0.62f),
        colors1 = OS2_PAD_LIGHT_COLORS,
        colors2 = OS2_PAD_LIGHT_COLORS,
        colors3 = OS2_PAD_LIGHT_COLORS,
        colorInterpPeriod = 100f,
        lightOffset = 0.1f,
        saturateOffset = 0f,
        pointOffset = 0.1f,
    )

    private val OS2_PAD_DARK_COLORS = floatArrayOf(
        0.0f, 0.31f, 0.58f, 1.0f, 0.53f, 0.29f, 0.15f, 1.0f,
        0.46f, 0.06f, 0.27f, 1.0f, 0.16f, 0.12f, 0.45f, 1.0f,
    )

    private val OS2_PAD_DARK = Config(
        points = floatArrayOf(0.55f, 0.42f, 1.0f, 0.56f, 0.75f, 1.0f, 0.40f, 0.59f, 0.71f, 0.43f, 0.09f, 0.75f),
        colors1 = OS2_PAD_DARK_COLORS,
        colors2 = OS2_PAD_DARK_COLORS,
        colors3 = OS2_PAD_DARK_COLORS,
        colorInterpPeriod = 100f,
        lightOffset = -0.1f,
        saturateOffset = 0.2f,
        pointOffset = 0.1f,
    )

    // ── OS3（MIUI 14+）───────────────────────────────────────────

    private val OS3_PHONE_LIGHT = Config(
        points = floatArrayOf(0.8f, 0.2f, 1.0f, 0.8f, 0.9f, 1.0f, 0.2f, 0.9f, 1.0f, 0.2f, 0.2f, 1.0f),
        colors1 = floatArrayOf(
            1.0f, 0.9f, 0.94f, 1.0f, 1.0f, 0.84f, 0.89f, 1.0f,
            0.97f, 0.73f, 0.82f, 1.0f, 0.64f, 0.65f, 0.98f, 1.0f,
        ),
        colors2 = floatArrayOf(
            0.58f, 0.74f, 1.0f, 1.0f, 1.0f, 0.9f, 0.93f, 1.0f,
            0.74f, 0.76f, 1.0f, 1.0f, 0.97f, 0.77f, 0.84f, 1.0f,
        ),
        colors3 = floatArrayOf(
            0.98f, 0.86f, 0.9f, 1.0f, 0.6f, 0.73f, 0.98f, 1.0f,
            0.92f, 0.93f, 1.0f, 1.0f, 0.56f, 0.69f, 1.0f, 1.0f,
        ),
        colorInterpPeriod = 5.0f,
        lightOffset = 0.1f,
        saturateOffset = 0.2f,
        pointOffset = 0.2f,
    )

    private val OS3_PHONE_DARK = Config(
        points = floatArrayOf(0.8f, 0.2f, 1.0f, 0.8f, 0.9f, 1.0f, 0.2f, 0.9f, 1.0f, 0.2f, 0.2f, 1.0f),
        colors1 = floatArrayOf(
            0.2f, 0.06f, 0.88f, 0.4f, 0.3f, 0.14f, 0.55f, 0.5f,
            0.0f, 0.64f, 0.96f, 0.5f, 0.11f, 0.16f, 0.83f, 0.4f,
        ),
        colors2 = floatArrayOf(
            0.07f, 0.15f, 0.79f, 0.5f, 0.62f, 0.21f, 0.67f, 0.5f,
            0.06f, 0.25f, 0.84f, 0.5f, 0.0f, 0.2f, 0.78f, 0.5f,
        ),
        colors3 = floatArrayOf(
            0.58f, 0.3f, 0.74f, 0.4f, 0.27f, 0.18f, 0.6f, 0.5f,
            0.66f, 0.26f, 0.62f, 0.5f, 0.12f, 0.16f, 0.7f, 0.6f,
        ),
        colorInterpPeriod = 8.0f,
        lightOffset = 0.0f,
        saturateOffset = 0.17f,
        pointOffset = 0.4f,
    )

    private val OS3_PAD_LIGHT = Config(
        points = floatArrayOf(0.8f, 0.2f, 1.0f, 0.8f, 0.9f, 1.0f, 0.2f, 0.9f, 1.0f, 0.2f, 0.2f, 1.0f),
        colors1 = floatArrayOf(
            0.99f, 0.77f, 0.86f, 1.0f, 0.74f, 0.76f, 1.0f, 1.0f,
            0.72f, 0.74f, 1.0f, 1.0f, 0.98f, 0.76f, 0.8f, 1.0f,
        ),
        colors2 = floatArrayOf(
            0.66f, 0.75f, 1.0f, 1.0f, 1.0f, 0.86f, 0.91f, 1.0f,
            0.74f, 0.76f, 1.0f, 1.0f, 0.97f, 0.77f, 0.84f, 1.0f,
        ),
        colors3 = floatArrayOf(
            0.97f, 0.79f, 0.85f, 1.0f, 0.65f, 0.68f, 0.98f, 1.0f,
            0.66f, 0.77f, 1.0f, 1.0f, 0.72f, 0.73f, 0.98f, 1.0f,
        ),
        colorInterpPeriod = 7.0f,
        lightOffset = 0.1f,
        saturateOffset = 0.2f,
        pointOffset = 0.2f,
    )

    private val OS3_PAD_DARK = Config(
        points = floatArrayOf(0.8f, 0.2f, 1.0f, 0.8f, 0.9f, 1.0f, 0.2f, 0.9f, 1.0f, 0.2f, 0.2f, 1.0f),
        colors1 = floatArrayOf(
            0.66f, 0.26f, 0.62f, 0.4f, 0.06f, 0.25f, 0.84f, 0.5f,
            0.0f, 0.64f, 0.96f, 0.5f, 0.14f, 0.18f, 0.55f, 0.5f,
        ),
        colors2 = floatArrayOf(
            0.07f, 0.15f, 0.79f, 0.5f, 0.11f, 0.16f, 0.83f, 0.5f,
            0.06f, 0.25f, 0.84f, 0.5f, 0.66f, 0.26f, 0.62f, 0.5f,
        ),
        colors3 = floatArrayOf(
            0.58f, 0.3f, 0.74f, 0.5f, 0.11f, 0.16f, 0.83f, 0.5f,
            0.66f, 0.26f, 0.62f, 0.5f, 0.27f, 0.18f, 0.6f, 0.6f,
        ),
        colorInterpPeriod = 7.0f,
        lightOffset = 0.0f,
        saturateOffset = 0.0f,
        pointOffset = 0.2f,
    )

    /** 按形态 / 明暗 / 代数选预设。 */
    fun get(
        deviceType: DeviceType,
        isDark: Boolean,
        isOs3: Boolean,
    ): Config = if (!isOs3) {
        when (deviceType) {
            DeviceType.PHONE -> if (!isDark) OS2_PHONE_LIGHT else OS2_PHONE_DARK
            DeviceType.PAD -> if (!isDark) OS2_PAD_LIGHT else OS2_PAD_DARK
        }
    } else {
        when (deviceType) {
            DeviceType.PHONE -> if (!isDark) OS3_PHONE_LIGHT else OS3_PHONE_DARK
            DeviceType.PAD -> if (!isDark) OS3_PAD_LIGHT else OS3_PAD_DARK
        }
    }
}
