package dev.praxis.ui.design.effect

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import kotlin.math.floor
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 页面级流动渐变背景。**移植自 InstallerX `ui/library/effect/BgEffectBackground.kt`**。
 *
 * ## 这是「关于页背景」的完整答案
 *
 * 之前 Praxis 的关于页只有一个 `Box(fillMaxSize)`，背后是 `AppScaffold` 的
 * 纯色 `surfaceContainer`。上游不是纯色：它在本页最外层套了这个组件，
 * 用 AGSL 着色器画一片**会流动的四光斑渐变**，并随滚动淡出。
 *
 * 三层叠放（顺序不可换，见 [BgEffectModifier]）：
 *
 * ```
 * drawRect(surface)                     ← 纯色兜底，任何设备上都有底
 * drawRect(shaderBrush, alpha = 1 - p)  ← 渐变，p = 滚动进度
 * drawContent()                         ← hero + 列表
 * ```
 *
 * ## 为什么渐变只覆盖上半屏
 *
 * [BgEffectModifier] 里 `drawHeight = size.height * 0.5f`（`isFullSize` 时 0.8）。
 * 光斑按内容区归一化布置，所以渐变实际只覆盖上半屏，下半屏是纯色。
 * 这不是偷工：列表区需要安静，否则滚动时彩色背景会穿在卡片之间乱动。
 *
 * ## 低版本怎么办
 *
 * `RuntimeShader` 要求 API 33+。低版本直接透传成 `Box`，只有纯色底 ——
 * 功能不减，只是没有渐变。**不要**用 `Brush.radialGradient` 做近似品：
 * 点位 / 半径 / 噪声三者咬合，近似品看起来是「脏色块」而不是渐变。
 *
 * ## 为什么这里不暴露上游的 Texture Set 面板
 *
 * 上游在 `AppConfig.LEVEL != STABLE` 时允许长按 hero 打开开发者面板，
 * 可调 blur 半径、噪声系数、亮度/对比度/饱和度、混合模式、OS2/OS3 切换。
 * Praxis 没有「构建档位」概念（所有构建等价），给一个只有开发者知道长按的
 * 面板等于留一个用户永远打不开的入口。所以固定 OS3 默认值；
 * 需要时把 `isOs3Effect` 接出来即可，着色器与预设都已就位。
 *
 * @param dynamicBackground 颜色是否随时间插值（`false` 时只有光斑漂移）。
 * @param bgModifier 附加在**背景层**上的修饰符。默认空。
 *   上游在这里挂 `layerBackdrop` 让背景被录进玻璃采样源；Praxis 的
 *   `AppScaffold` 已在内容外层整体录了一份，本组件天然在里面，
 *   重复挂会让同一块内容被录两遍，白耗内存。
 * @param isFullSize 渐变是否覆盖 80%（`false` 时覆盖 50%）。
 * @param effectBackground 是否画渐变。`false` 时只有纯色底。
 * @param isOs3Effect 用 OS3 还是 OS2 着色器。
 * @param alpha 渐变层整体不透明度。关于页传 `{ 1f - scrollProgress }`：
 *   滚到底时渐变完全消失，只剩纯色底 —— 这是「往下滚背景变素」的机制。
 */
@Composable
fun BgEffectBackground(
    dynamicBackground: Boolean,
    modifier: Modifier = Modifier,
    bgModifier: Modifier = Modifier,
    isFullSize: Boolean = false,
    effectBackground: Boolean = true,
    isOs3Effect: Boolean = true,
    alpha: () -> Float = { 1f },
    content: @Composable (BoxScope.() -> Unit),
) {
    // 低版本：直接透传，**绝不触碰 miuix-shader 的任何符号**。
    //
    // ## 为什么拆成两个独立 if 而不是一个 `||` 组合
    //
    // lint 的 `NewApi` 靠控制流推断：它需要一个能证明「走到下面时
    // SDK_INT >= 33」的守卫。单独的 `if (SDK_INT < 33) return` 是它认得的
    // 标准形态；`if (SDK_INT < 33 || !supported) return` 多了一个与版本
    // 无关的条件，部分 lint 版本会推断失败，于是下面所有 `@RequiresApi`
    // 的调用都被判成 error。
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Box(modifier = modifier, content = content)
        return
    }
    val shaderSupported = remember { isRuntimeShaderSupported() }
    if (!shaderSupported) {
        // 极少数 API 33+ 但 RuntimeShader 不可用的环境（自编译 ROM / 裁剪过的运行时）。
        Box(modifier = modifier, content = content)
        return
    }

    Box(modifier = modifier) {
        val surface = MiuixTheme.colorScheme.surface
        val deviceType = rememberDeviceType()
        // 暗色判定用背景亮度而不是 `isSystemInDarkTheme()`：后者只反映系统，
        // 而 Praxis 允许应用内强制浅色/深色（外观页）。与 FloatingBottomBar 同口径。
        val isDarkTheme = MiuixTheme.colorScheme.onSurface.luminance() > 0.5f

        val painter = remember(isOs3Effect) { BgEffectPainter(isOs3Effect) }
        val preset = remember(deviceType, isDarkTheme, isOs3Effect) {
            BgEffectConfig.get(deviceType, isDarkTheme, isOs3Effect)
        }

        // ── 动画时间（驱动光斑漂移 + 噪声滚动）──────────────────────
        //
        // ## 为什么用 State 而不是 node 内部协程
        //
        // 绘制阶段读 State 会被 draw 快照捕获 → 只在 draw 失效、不触发重组，
        // 与手工 `invalidateDraw()` 等价，但少一个长跑协程要管生命周期。
        // `alpha` 走的 `scrollProgress`（derivedStateOf）本来就是这个机制。
        var animTime by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(dynamicBackground) {
            if (!dynamicBackground) return@LaunchedEffect
            val minDeltaNanos = 1_000_000_000L / 60L
            // `origin` 是相位基准，`lastEmit` 只用于节流 —— 两者不能混用：
            // 用 lastEmit 当基准会让时间每次都从 0 重新开始（渐变永远停在起始相位）。
            val origin = withFrameNanos { it }
            var lastEmit = origin
            while (isActive) {
                val now = withFrameNanos { it }
                // 手动限到 60fps：120Hz 屏上每帧算一次 AGSL 对中端机是纯浪费，
                // 而这个渐变本身很慢（周期秒级），60fps 完全够。
                if (now - lastEmit < minDeltaNanos) continue
                lastEmit = now
                animTime = (now - origin) / 1_000_000_000f
            }
        }

        // ── 颜色循环（只在三套色不同时启动）─────────────────────────
        val colorStage = remember { Animatable(0f) }
        LaunchedEffect(dynamicBackground, preset) {
            if (!dynamicBackground) return@LaunchedEffect
            // OS2 的三套色完全相同 → 插值毫无视觉变化，白跑动画。早退。
            val animatesColors = preset.colors1 !== preset.colors2 ||
                preset.colors2 !== preset.colors3
            if (!animatesColors) return@LaunchedEffect
            // `floor(value) + 1` 而不是 `value + 1`：Animatable 可能停在 3.7，
            // 直接 +1 得 4.7，跨过整整一档颜色（跳色）。floor 后 +1 保证每次
            // 只前进一个整数档。
            var targetStage = floor(colorStage.value) + 1f
            while (isActive) {
                delay((preset.colorInterpPeriod * 500).toLong())
                colorStage.animateTo(
                    targetValue = targetStage,
                    // stiffness 35 很软（默认 1500），单档过渡约 1 秒级，
                    // 观感是「颜色缓慢渗过来」而不是「渐变动画」。
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = 35f),
                )
                targetStage += 1f
            }
        }

        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .then(bgModifier)
                .bgEffectDraw(
                    painter = painter,
                    preset = preset,
                    deviceType = deviceType,
                    isDarkTheme = isDarkTheme,
                    surface = surface,
                    effectBackground = effectBackground,
                    isFullSize = isFullSize,
                    animTime = { animTime },
                    colorStage = { colorStage.value },
                    alpha = alpha,
                ),
        )
        content()
    }
}

/**
 * 当前是手机还是平板。
 *
 * 判据取**宽度 600dp**，与 Android 官方 `sw600dp` 资源限定符同一口径。
 *
 * ## 为什么不用 `LocalWindowLayoutInfo`
 *
 * 上游用它 `showNavigationRail` 判：那是它自己的窗口逻辑层（支持折叠屏
 * 半折叠、自由窗口分屏）。Praxis 没有那一层，为判一个布尔值引入不值得。
 *
 * `LocalConfiguration.screenWidthDp` 在配置变化时本来就会触发重组，
 * 所以折叠屏展开、旋转、自由窗格拉宽都能正确切到 PAD 预设 ——
 * 这比读设备型号（`smallestScreenWidthDp`）更贴近「当前该用哪一套点位」。
 */
@Composable
private fun rememberDeviceType(): DeviceType {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return remember(widthDp) {
        if (widthDp >= 600) DeviceType.PAD else DeviceType.PHONE
    }
}