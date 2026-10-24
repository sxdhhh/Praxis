package dev.praxis.ui.design.effect

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.blur.RuntimeShader
import top.yukonga.miuix.kmp.blur.asBrush
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported

/**
 * HyperCeiler 开屏光晕 —— **在 Compose 里重跑原版 AGSL shader**。
 *
 * ## 逐字程度
 *
 * | 部分 | 来源 | 改动 |
 * |---|---|---|
 * | GLSL 源码 | `res/raw/glow.glsl` | 见 [GLOW_AGSL] 的 KDoc（只删注释块） |
 * | 40 个 uniform 取值 | `GlowPainter.java` 的字段初值 | **逐字段照抄，一个数都没改** |
 * | 时间推进 | `GlowController.tickPingPong()` | `uTime` 在 2.0 ↔ 120.0 之间往返，方向翻转逻辑一致 |
 * | 圆心 Y 偏移 | `GlowController.setCircleYOffsetWithView()` | 同一公式 `((h/2) - centerY) / h` |
 *
 * ## 为什么 `uTime` 要 ping-pong 而不是一直递增
 *
 * 原版 `tickPingPong()`：
 *
 * ```java
 * mTime += mDeltaTime * mTimeDirection;
 * if (mTimeDirection > 0) { if (mTime >= 120) mTimeDirection = -1; }
 * else                    { if (mTime <= 2)   mTimeDirection =  1; }
 * ```
 *
 * 因为 shader 里 `radius = uTime * 0.9 - uMaskDelay`（圆形遮罩半径）与
 * `animatedTime = uTime * uCircleSpeed`（光晕阶段）都**依赖 uTime 单调**。
 * 无限递增会让遮罩半径无限大（数学上没问题，但 float 精度会在几分钟后丢光，
 * 画面开始抖动）。往返到 120 再回落，等于循环一段 118 秒的动画。
 *
 * 我们不能像原版那样「回到 2 就重放光晕」—— 因为 Praxis 的开屏只停留
 * 一两秒，用户看不到第二轮。照抄区间是为了**同一段 0~2 秒的画面完全一致**。
 *
 * ## API 门槛
 *
 * `RuntimeShader` 是 API 33。低于 33 时本组件退化为 [fallbackGradient]
 * （径向渐变近似），保证开屏不白屏。降级**不是**「差不多」，
 * 而是明确知道做不到环形光晕时的可用兜底。
 *
 * @param circleYOffset 圆心垂直偏移（0.1 = 略高于屏幕中心）。
 *   与上游默认值一致；上游还会在 `onWindowFocusChanged` 里按
 *   `logo_image_wrapper` 的实际位置重算，见调用方。
 * @param showCircle 是否画环形光晕。上游首帧传 `true`（`mGlowController.start(true)`）。
 */
@Composable
fun HyperCeilerStartupGlow(
    modifier: Modifier = Modifier,
    circleYOffset: Float = 0.1f,
    showCircle: Boolean = true,
    fallbackGradient: Brush? = null,
) {
    // ## 门控为什么要写成 `SDK_INT < 33 || !isRuntimeShaderSupported()`
    //
    // `isRuntimeShaderSupported()` 内部是 miuix 的 `coreIsRuntimeShaderSupported()`，
    // 它在运行时判断，但 **lint 不认识它**。若只写
    //
    // ```
    // if (!isRuntimeShaderSupported()) { ...降级...; return }
    // StartupGlowShaderImpl(...)   // @RequiresApi(33)
    // ```
    //
    // 编译能过（Kotlin 不校验 API level），但 `lintDebug` 的 NewApi 检查
    // 会把这个调用点判为 error（「Call requires API level 33」），直接让
    // CI 的 lint 任务红掉。
    //
    // 把 `Build.VERSION.SDK_INT < TIRAMISU` 放在**同一个布尔表达式的最前面**，
    // lint 的流程分析就能从这个 `||` 左侧推导出「走到 return 之后的
    // 分支必定 SDK >= 33」，于是不再报错。运行时语义完全不变
    // （SDK < 33 时 `isRuntimeShaderSupported()` 本来也返回 false，
    // 多一次短路判断没有任何代价）。
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !isRuntimeShaderSupported()) {
        // 低版本兜底：一层静态径向渐变。用 drawBehind 直接画，
        // 不引入任何额外依赖。
        Box(
            modifier = modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(
                        brush = fallbackGradient ?: Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF4D54D7),
                                Color(0xFF9AA8F5),
                                Color.Black,
                            ),
                            center = center,
                            radius = size.maxDimension * 0.75f,
                        ),
                    )
                },
        )
        return
    }

    StartupGlowShaderImpl(
        modifier = modifier,
        circleYOffset = circleYOffset,
        showCircle = showCircle,
    )
}

/**
 * 真正跑 shader 的实现。单独抽出来是为了让 `@RequiresApi(33)` 只落在这里 ——
 * 上层 `HyperCeilerStartupGlow` 保持无注解，调用方不必到处写 opt-in。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun StartupGlowShaderImpl(
    modifier: Modifier,
    circleYOffset: Float,
    showCircle: Boolean,
) {
    // ── shader 实例：按「本页只建一次」缓存 ────────────────────
    //
    // 不能放进 drawBehind 的 lambda 里每次重建：`RuntimeShader` 的构造
    // 会触发一次 AGSL 编译（几十毫秒），每帧重建等于每帧卡一次。
    val shader = remember {
        RuntimeShader(GLOW_AGSL).also { applyAllUniforms(it, circleYOffset, showCircle) }
    }

    // `uTime` 用 Compose 状态驱动重绘。
    //
    // ## 为什么用 `withFrameNanos` 而不是无限动画
    //
    // 原版是 `postDelayed(16ms)` 的独立循环，时间从 `System.nanoTime()` 取
    // 真实增量。用 Compose 的 `infiniteRepeatable` 动画也能动，但它的
    // 0→1 进度与真实时间**没有固定换算**，无法复现「uTime 在 2~120 之间
    // 以真实秒数往返」这条精确行为。
    //
    // `withFrameNanos` 给的是真实帧时间戳，累计后与上游的
    // `(nanoTime - lastGlobalTime) * 1e-9` 完全等价。
    var time by remember { mutableFloatStateOf(0f) }
    var direction by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val delta = (now - last) * 1.0e-9f
                    var next = time + delta * direction
                    // ping-pong，区间与上游一致（2..120）。
                    if (direction > 0f) {
                        if (next >= 120f) direction = -1f
                    } else {
                        if (next <= 2f) direction = 1f
                    }
                    // 夹一次：跨阈值那一帧可能越界，让 uTime 停在边界上，
                    // 与上游「先加再翻方向」的观感一致（上游也不回退这一帧）。
                    next = next.coerceIn(2f, 120f)
                    time = next
                }
                last = now
            }
        }
    }

    // 画法：把 `uTime` 写进 shader 后，用 miuix 的 `asBrush()` 取一个 Brush，
    // 再用普通的 `drawRect(brush = …)`。
    //
    // ## 为什么不用 `RenderEffect`
    //
    // 原版是 `view.setRenderEffect(createShaderEffect(shader))` —— 那是把
    // 整棵视图树的结果当输入再跑 shader。它要求这个 View 有内容可渲染，
    // 但光晕是「凭空生色」，没有输入纹理。上游的做法是给一个空 View
    // （`mGlowEffectView = new View(ctx)`）套 RenderEffect，靠 RenderEffect
    // 的输出尺寸 = View 尺寸来出图。
    //
    // Compose 里更直接：`asBrush()` 把 AGSL 当成 Shader 填进
    // Paint.shader，`drawRect` 时 shader 按 fragCoord 求值 ——
    // 与 `RuntimeShaderService` 的语义完全一致，且不需要额外的空 View。
    // Praxis 的 `BgEffectPainter` 已经是这个用法，此处沿用同一套。
    //
    // 注意 `asBrush()` 每次调用会 new 一个 ShaderBrush，所以放在
    // drawBehind 外面 remember 一次；shader 的 uniform 是引用共享的，
    // 改 uniform 后同一个 Brush 下次 draw 就会用新值。
    val brush = remember(shader) { shader.asBrush() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                shader.setFloatUniform("uTime", time)
                shader.setFloatUniform("uResolution", size.width, size.height)
                drawRect(brush = brush)
            },
    )
}

/**
 * 把 `GlowPainter.java` 的 40 个字段初值逐个写进 uniform。**顺序与数值都照抄**。
 *
 * ## 为什么不做成 data class 参数
 *
 * 这 40 个值在原版里就是硬编码字段（`float uScale2 = 0.82f;`），
 * 没有任何调用方会改它们。做成参数会让调用点凭空多出 40 个「可以调」
 * 的东西，而实际没人调 —— 反而模糊了「这是原版固定风格」这个事实。
 *
 * 唯一真正会变的两个是 `uCircleYOffset`（按 logo 位置算）与 `uShowCircle`
 * （首帧 true、返回时为 false），所以它们留成参数。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun applyAllUniforms(shader: RuntimeShader, circleYOffset: Float, showCircle: Boolean) {
    // ── Base Layer (Color) ──────────────────────────────────
    shader.setFloatUniform("uScale2", 0.82f)
    shader.setFloatUniform("uSpeed2", 0.49f)
    shader.setFloatUniform("uColorInMin", 0.3f)
    shader.setFloatUniform("uColorInMax", 1.0f)
    shader.setFloatUniform("uColorOutMin", 0.3f)
    shader.setFloatUniform("uColorOutMax", 0.86f)
    shader.setFloatUniform("uColorMidPoint", 0.47f)
    shader.setFloatUniform("uUseOklab", 1.0f)
    // 三个颜色是 RGB 三元组。原版注释里第一组叫 Black 但值是红色系
    // (0.961, 0.157, 0.157)，这是设计给的三色循环起点，照抄。
    shader.setFloatUniform("uColorBlack", 0.961f, 0.157f, 0.157f)
    shader.setFloatUniform("uColorMid", 0.604f, 0.659f, 0.961f)
    shader.setFloatUniform("uColorWhite", 0.302f, 0.29f, 0.843f)

    // ── Second Layer (Grayscale) ────────────────────────────
    shader.setFloatUniform("uScale", 1.3f)
    shader.setFloatUniform("uSpeed", 0.4f)
    shader.setFloatUniform("uBrightnessInMin", 0.25f)
    shader.setFloatUniform("uBrightnessInMax", 1.0f)
    shader.setFloatUniform("uBrightnessOutMin", 0.25f)
    shader.setFloatUniform("uBrightnessOutMax", 1.0f)

    // ── Circle ──────────────────────────────────────────────
    shader.setFloatUniform("uShowCircle", if (showCircle) 1.0f else 0.0f)
    shader.setFloatUniform("uCircleThickness", 0.4f)
    shader.setFloatUniform("uCircleFinalRadius", 1.0f)
    shader.setFloatUniform("uCircleYOffset", circleYOffset)
    shader.setFloatUniform("uCircleSpeed", 0.9f)
    shader.setFloatUniform("uCircleColorFreq", 1.0f)
    shader.setFloatUniform("uCircleColorSpeed", 0.0f)
    shader.setFloatUniform("uCircleEasing", 1.4f)
    shader.setFloatUniform("uCircleAnimationOffset", 0.0f)
    shader.setFloatUniform("uMaskDelay", 0.3f)
    shader.setFloatUniform("uMaskThickness", 0.3f)
    shader.setFloatUniform("uCircleScreenBlend", 1.0f)
    shader.setFloatUniform("uCircleAddBlend", 0.04f)
    shader.setFloatUniform("uCircleColorOffset", 0.25f)
    shader.setFloatUniform("uCircleUVDistort", 0.0f)
    shader.setFloatUniform("uColorToDistortWidthRatio", 0.6f)
    shader.setFloatUniform("uDistortStartTime", 0.2f)
    shader.setFloatUniform("uDistortEndTime", 0.3f)
    shader.setFloatUniform("uDistortStart", 0.0f)
    shader.setFloatUniform("uDistortEnd", 1.0f)

    // ── Stripe（原版全 0 = 不启用条纹）──────────────────────
    shader.setFloatUniform("uStripeFrequency", 0.0f)
    shader.setFloatUniform("uStripeStrengthX", 0.0f)
    shader.setFloatUniform("uStripeStrengthY", 0.0f)
    shader.setFloatUniform("uStripeUVDistort", 0.0f)
}