package dev.praxis.ui.design.effect

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Brush
import kotlin.math.cos
import kotlin.math.sin
import top.yukonga.miuix.kmp.blur.RuntimeShader
import top.yukonga.miuix.kmp.blur.asBrush

/**
 * 背景着色器的参数装配器。**逐行移植 InstallerX `BgEffectPainter.kt`**。
 *
 * ## 这个类只做一件事：把 Kotlin 数据塞进 AGSL 的 uniform
 *
 * 着色器本身是纯函数（输入 uniform，输出颜色）。所有「状态」都在这里：
 * 分辨率、动画时间、光斑位置、四组颜色、内容区边界、预设参数。
 *
 * ## 为什么每个 setter 都带缓存比对
 *
 * `ContentDrawScope.draw()` **每帧**都会调用这一串 setter。若每次都
 * `runtimeShader.setFloatUniform(...)`，等于每帧重建 7 个 uniform 缓冲 ——
 * 中低端机上这会让整页掉到 30fps。所以每个 setter 先比上一次的值，
 * 未变就直接返回。
 *
 * 注意 `updateAnimTime` **没有**「按帧率节流」：`animTime` 每帧都在变，
 * 比对天然失败，必然下发。真正的节流在 `BgEffectModifier` 里
 * （用 `withFrameNanos` 限到 60fps）。上游把节流放在 Modifier 而非这里，
 * 是因为 Painter 不该知道「谁在驱动它」。
 *
 * ## `bound` 的两种算法（竖屏 vs 横屏）
 *
 * `uBound` 把归一化坐标重映射到「内容区起点 + 内容区尺寸」：
 *
 *  - **竖屏**（`totalWidth <= totalHeight`）：内容区从 `1 - heightRatio` 开始，
 *    高度就是 `heightRatio`。`heightRatio = logoHeight / totalHeight`，
 *    也就是「hero 高度占整页的比例」。
 *  - **横屏**：内容区宽高比大于 1，此时改用 `aspectRatio` 作高度基准，
 *    并把内容区垂直居中（`contentCenterY - aspectRatio / 2`）。
 *
 * 为什么横屏不能沿用竖屏公式：横屏下 `heightRatio` 很小（hero 只占顶部一小条），
 * 直接当高度用会让光斑挤成一条水平线。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class BgEffectPainter(private val isOs3: Boolean = true) {

    val runtimeShader by lazy {
        val shaderCode = if (isOs3) OS3_BG_FRAG else OS2_BG_FRAG
        RuntimeShader(shaderCode).also { initStaticUniforms(it) }
    }

    val brush: Brush get() = runtimeShader.asBrush()

    // ── 复用缓冲：避免每帧 new FloatArray ──────────────────────
    private val resolution = FloatArray(2)
    private val bound = FloatArray(4)
    private val colorsBuffer = FloatArray(16)
    private val pointsAnimBuffer = FloatArray(8)

    // ── 缓存：用于跳过无变化的 uniform 下发 ────────────────────
    private var animTime = Float.NaN
    private var isDarkCached: Boolean? = null
    private var deviceTypeCached: DeviceType? = null
    private var presetApplied = false
    private var cachedLogoHeight = Float.NaN
    private var cachedTotalHeight = Float.NaN
    private var cachedTotalWidth = Float.NaN
    private var cachedColorStage = Float.NaN
    private var cachedColorsPreset: BgEffectConfig.Config? = null
    private var cachedPointsAnimTime = Float.NaN
    private var cachedPointsAnimPreset: BgEffectConfig.Config? = null

    private companion object {
        /**
         * ── 四个静态 uniform ──────────────────────────────────────
         *
         * `uTranslateY`：整体上下平移（0 = 不平移）。上游留这个口子做
         *   「背景跟着滚动位移」，但 About 页改用 `alpha` 淡出，所以恒为 0。
         * `uNoiseScale`：噪声采样频率。1.5 是「能看出流动但不花」的折中值，
         *   更大会碎成噪点、更小会变成大块色斑。
         * `uPointRadiusMulti`：光斑半径倍率。1.0 即按预设原值。
         * `uAlphaMulti`：整体透明度。1.0 即不透明。
         */
        const val U_TRANSLATE_Y = 0f
        const val U_ALPHA_MULTI = 1f
        const val U_NOISE_SCALE = 1.5f
        const val U_POINT_RADIUS_MULTI = 1f
    }

    private fun initStaticUniforms(shader: RuntimeShader) {
        shader.setFloatUniform("uTranslateY", U_TRANSLATE_Y)
        shader.setFloatUniform("uNoiseScale", U_NOISE_SCALE)
        shader.setFloatUniform("uPointRadiusMulti", U_POINT_RADIUS_MULTI)
        shader.setFloatUniform("uAlphaMulti", U_ALPHA_MULTI)
    }

    fun updateResolution(width: Float, height: Float) {
        if (resolution[0] == width && resolution[1] == height) return
        resolution[0] = width
        resolution[1] = height
        runtimeShader.setFloatUniform("uResolution", resolution)
    }

    fun updateAnimTime(time: Float) {
        if (animTime == time) return
        animTime = time
        runtimeShader.setFloatUniform("uAnimTime", animTime)
    }

    fun updatePointsAnim(time: Float, preset: BgEffectConfig.Config) {
        if (cachedPointsAnimTime == time && cachedPointsAnimPreset === preset) return
        val offset = preset.pointOffset
        var i = 0
        while (i < 4) {
            val srcX = preset.points[i * 3]
            val srcY = preset.points[i * 3 + 1]
            // 注意 animX 参与 animY 的计算（`cos(time + animX)`）——
            // 这不是笔误，是让两个轴的漂移不同频，否则光斑会沿 45° 直线来回。
            val animX = srcX + sin(time + srcY) * offset
            val animY = srcY + cos(time + animX) * offset
            pointsAnimBuffer[i * 2] = animX
            pointsAnimBuffer[i * 2 + 1] = animY
            i++
        }
        runtimeShader.setFloatUniform("uPointsAnim", pointsAnimBuffer)
        cachedPointsAnimTime = time
        cachedPointsAnimPreset = preset
    }

    fun updateColors(preset: BgEffectConfig.Config, stage: Float) {
        if (cachedColorsPreset === preset && cachedColorStage == stage) return
        val base = stage.toInt()
        val fraction = stage - base
        val start = colorsForCycleIndex(preset, base)
        val end = colorsForCycleIndex(preset, base + 1)
        for (i in 0 until 16) {
            colorsBuffer[i] = start[i] + (end[i] - start[i]) * fraction
        }
        runtimeShader.setFloatUniform("uColors", colorsBuffer)
        cachedColorsPreset = preset
        cachedColorStage = stage
    }

    /**
     * 三套颜色按周期轮转。
     *
     * `index % 4` 映射：0→colors2、1→colors1、2→colors2、3→colors3。
     *
     * 为什么中间要回到 colors2：这样三条曲线在**同一个集合内首尾相接**，
     * 动画循环一整圈后回到起始色。若按 1→2→3→1 顺序，从 colors3 切回
     * colors1 时会跳色（`colors3` 与 `colors1` 差得最远）。
     *
     * 用 `%` 而不是 `Int.mod()`：`mod` 在 Kotlin 2.x 已 deprecated，
     * 而这里的 `index` 由 `stage.toInt()` 得到、恒为非负，`%` 语义相同。
     */
    private fun colorsForCycleIndex(preset: BgEffectConfig.Config, index: Int): FloatArray =
        when (index % 4) {
            1 -> preset.colors1
            3 -> preset.colors3
            else -> preset.colors2
        }

    fun updateBoundIfNeeded(logoHeight: Float, totalHeight: Float, totalWidth: Float) {
        if (cachedLogoHeight == logoHeight &&
            cachedTotalHeight == totalHeight &&
            cachedTotalWidth == totalWidth
        ) {
            return
        }
        updateBound(logoHeight, totalHeight, totalWidth)
        runtimeShader.setFloatUniform("uBound", bound)
        cachedLogoHeight = logoHeight
        cachedTotalHeight = totalHeight
        cachedTotalWidth = totalWidth
    }

    fun updatePresetIfNeeded(deviceType: DeviceType, isDark: Boolean) {
        if (presetApplied && isDarkCached == isDark && deviceTypeCached == deviceType) return
        applyPreset(deviceType, isDark)
        isDarkCached = isDark
        deviceTypeCached = deviceType
        presetApplied = true
    }

    private fun applyPreset(deviceType: DeviceType, isDark: Boolean) {
        val preset = BgEffectConfig.get(deviceType, isDark, isOs3)
        runtimeShader.setFloatUniform("uPoints", preset.points)
        runtimeShader.setFloatUniform("uLightOffset", preset.lightOffset)
        runtimeShader.setFloatUniform("uSaturateOffset", preset.saturateOffset)
    }

    private fun updateBound(logoHeight: Float, totalHeight: Float, totalWidth: Float) {
        // 防空：首帧 totalHeight 可能还是 0，除零会得到 NaN → 整屏变黑。
        if (totalHeight <= 0f) return
        val heightRatio = logoHeight / totalHeight
        if (totalWidth <= totalHeight) {
            bound[0] = 0f
            bound[1] = 1f - heightRatio
            bound[2] = 1f
            bound[3] = heightRatio
        } else {
            val aspectRatio = totalWidth / totalHeight
            val contentCenterY = 1f - heightRatio / 2f
            bound[0] = 0f
            bound[1] = contentCenterY - aspectRatio / 2f
            bound[2] = 1f
            bound[3] = aspectRatio
        }
    }
}