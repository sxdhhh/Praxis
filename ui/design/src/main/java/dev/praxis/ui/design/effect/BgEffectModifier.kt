package dev.praxis.ui.design.effect

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement

/**
 * 把背景着色器画进本节点的绘制阶段。**移植自 InstallerX `BgEffectModifier.kt`**，
 * 动画驱动方式与上游不同（见下）。
 *
 * ## 绘制顺序（这三行的次序是效果成立的全部）
 *
 * ```
 * drawRect(surface)                       // ① 永远先铺纯色底
 * if (effectBackground && alpha > 0) {
 *     drawRect(painter.brush, alpha)      // ② 渐变叠在上面，按 alpha 淡出
 * }
 * drawContent()                           // ③ 最后画内容
 * ```
 *
 * ① 不能省：着色器的 alpha 在边缘会趋近 0（四个光斑的 radius 未必覆盖全屏），
 * 没铺底色就会**透出下层页面的内容**（转场时会看到上一页）。
 *
 * ② 的 alpha 是整层淡出，不是逐像素 —— 所以关于页滚到底时渐变整体消失，
 * 露出 ① 的纯色底。这就是「往下滚背景变素」的机制。
 *
 * ③ 必须最后：内容要盖在渐变之上。
 *
 * ## 与上游的差异：动画时间由外部 State 驱动
 *
 * 上游在 node 内部用 `Modifier.Node.coroutineScope` 起
 * `while (isActive) { withFrameNanos; invalidateDraw() }` 循环。
 * 这里改成**外部持有 `animTime` / `colorStage` 两个 State，draw 阶段读它们**：
 *
 *  - 绘制阶段读取的状态会被 Compose 的 draw 快照观察捕获，值变只会
 *    **失效 draw**、不触发重组 —— 与手工 `invalidateDraw()` 效果等价；
 *  - 少一个 node 内部长跑协程，`onDetach` 不必再管 Job 生命周期；
 *  - `alpha` 用的 `scrollProgress` 本来就是这个机制（`derivedStateOf` 在
 *    draw 阶段读），整条链路只有一种失效来源，行为可预测。
 *
 * 这也是 Praxis 既有代码的写法（`LocalKyantBackdrop` / `FloatingBottomBar`
 * 都在 draw 阶段读状态），保持一致比逐字照抄上游更重要。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun Modifier.bgEffectDraw(
    painter: BgEffectPainter,
    preset: BgEffectConfig.Config,
    deviceType: DeviceType,
    isDarkTheme: Boolean,
    surface: Color,
    effectBackground: Boolean,
    isFullSize: Boolean,
    animTime: () -> Float,
    colorStage: () -> Float,
    alpha: () -> Float,
): Modifier = this then BgEffectElement(
    painter = painter,
    preset = preset,
    deviceType = deviceType,
    isDarkTheme = isDarkTheme,
    surface = surface,
    effectBackground = effectBackground,
    isFullSize = isFullSize,
    animTime = animTime,
    colorStage = colorStage,
    alpha = alpha,
)

/**
 * 元素形态。**不是 `data class`**。
 *
 * ## 为什么不能是 data class
 *
 * Kotlin 不允许 data class 显式实现 `equals` / `hashCode`（编译器要自己生成，
 * 手写会报 conflicting declarations）。而三个 lambda 字段
 * （`animTime` / `colorStage` / `alpha`）每次组合都是新实例，
 * data class 生成的 equals 会把它们算进去 → 每次重组都判定「元素变了」
 * → 白跑一次 `update()`。
 *
 * 所以手写成普通 class + 只比数据字段的 equals。lambda 的语义由外层保证：
 * 它们读的是 State，同一个 State 对象身份不变，内容变化由 draw 阶段的
 * 快照观察触发重绘 —— 不依赖 lambda 身份。
 */
private class BgEffectElement(
    val painter: BgEffectPainter,
    val preset: BgEffectConfig.Config,
    val deviceType: DeviceType,
    val isDarkTheme: Boolean,
    val surface: Color,
    val effectBackground: Boolean,
    val isFullSize: Boolean,
    val animTime: () -> Float,
    val colorStage: () -> Float,
    val alpha: () -> Float,
) : ModifierNodeElement<BgEffectNode>() {

    override fun create(): BgEffectNode = BgEffectNode(
        painter = painter,
        preset = preset,
        deviceType = deviceType,
        isDarkTheme = isDarkTheme,
        surface = surface,
        effectBackground = effectBackground,
        isFullSize = isFullSize,
        animTime = animTime,
        colorStage = colorStage,
        alpha = alpha,
    )

    override fun update(node: BgEffectNode) {
        node.update(
            painter = painter,
            preset = preset,
            deviceType = deviceType,
            isDarkTheme = isDarkTheme,
            surface = surface,
            effectBackground = effectBackground,
            isFullSize = isFullSize,
            animTime = animTime,
            colorStage = colorStage,
            alpha = alpha,
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BgEffectElement) return false
        return painter === other.painter &&
            preset === other.preset &&
            deviceType == other.deviceType &&
            isDarkTheme == other.isDarkTheme &&
            surface == other.surface &&
            effectBackground == other.effectBackground &&
            isFullSize == other.isFullSize
    }

    override fun hashCode(): Int {
        var result = painter.hashCode()
        result = 31 * result + preset.hashCode()
        result = 31 * result + deviceType.hashCode()
        result = 31 * result + isDarkTheme.hashCode()
        result = 31 * result + surface.hashCode()
        result = 31 * result + effectBackground.hashCode()
        result = 31 * result + isFullSize.hashCode()
        return result
    }
}

private class BgEffectNode(
    private var painter: BgEffectPainter,
    private var preset: BgEffectConfig.Config,
    private var deviceType: DeviceType,
    private var isDarkTheme: Boolean,
    private var surface: Color,
    private var effectBackground: Boolean,
    private var isFullSize: Boolean,
    private var animTime: () -> Float,
    private var colorStage: () -> Float,
    private var alpha: () -> Float,
) : Modifier.Node(), DrawModifierNode {

    fun update(
        painter: BgEffectPainter,
        preset: BgEffectConfig.Config,
        deviceType: DeviceType,
        isDarkTheme: Boolean,
        surface: Color,
        effectBackground: Boolean,
        isFullSize: Boolean,
        animTime: () -> Float,
        colorStage: () -> Float,
        alpha: () -> Float,
    ) {
        this.painter = painter
        this.preset = preset
        this.deviceType = deviceType
        this.isDarkTheme = isDarkTheme
        this.surface = surface
        this.effectBackground = effectBackground
        this.isFullSize = isFullSize
        this.animTime = animTime
        this.colorStage = colorStage
        this.alpha = alpha
    }

    override fun ContentDrawScope.draw() {
        drawRect(surface)
        if (effectBackground) {
            // 三个状态读取都发生在 draw 阶段 → 值变只失效 draw，不触发重组。
            val alphaValue = alpha()
            if (alphaValue > 0f) {
                val time = animTime()
                // 渐变只覆盖顶部一段：`isFullSize` 时 80%，否则 50%。
                //
                // ## 为什么不是整屏
                //
                // 光斑按内容区归一化布置，若铺满整屏，滚到列表区时
                // 用户会在「本该是纯色列表背景」的地方看到彩色流动 ——
                // 与「列表区要安静」相冲突。上游同样只覆盖上半。
                val drawHeight = if (isFullSize) size.height * 0.8f else size.height * 0.5f
                painter.updateResolution(size.width, size.height)
                painter.updateBoundIfNeeded(drawHeight, size.height, size.width)
                painter.updatePresetIfNeeded(deviceType, isDarkTheme)
                painter.updateColors(preset, colorStage())
                painter.updateAnimTime(time)
                painter.updatePointsAnim(time, preset)
                drawRect(painter.brush, alpha = alphaValue)
            }
        }
        drawContent()
    }
}