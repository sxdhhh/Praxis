// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
//
// 直接移植自 InstallerX-Revived:
//   app/src/main/java/com/rosan/installer/ui/library/FloatingBottomBar.kt (583 行)
// Adapted from compose-miuix-ui example (IosLiquidGlassNavigationBar) — Apache 2.0.
//
// ## 为什么整段照抄而不是自己写
//
// 之前自己写的那版有两个致命问题（装机后立刻暴露）：
//   1. 把 miuix `FloatingNavigationBar` 的 `color` 设成全透明、指望玻璃撑背景，
//      结果**整条底栏消失**。InstallerX 的做法是 `onDrawSurface = { drawRect(containerColor) }`
//      —— 底色由玻璃层自己画，永远有兜底。
//   2. 没有独立的「选中指示器」层。InstallerX 是三层结构：
//      底层（未选中内容）→ 选中层（alpha=0f 只用来录 backdrop）→ 指示器（可拖拽滑动）。
//      自己写的那版只有一层静态高亮，所以看着「死板」。
//
// 这版是上游逐行移植：包括拖拽切换 tab、按压放大、重力感应高光、弹性回弹。
// 只改了包名、主题色来源（换成 Praxis 的 miuix 色）与背景采样源注入方式。
package dev.praxis.ui.design.glass

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import dev.praxis.ui.design.glass.animation.DampedDragAnimation
import dev.praxis.ui.design.glass.animation.InteractiveHighlight
import dev.praxis.ui.design.glass.liquid.InnerShadow
import dev.praxis.ui.design.glass.liquid.innerShadow
import dev.praxis.ui.design.glass.liquid.lens
import dev.praxis.ui.design.glass.liquid.rememberCombinedBackdrop
import dev.praxis.ui.design.glass.liquid.vibrancy
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.LightPosition
import top.yukonga.miuix.kmp.blur.highlight.LightSource
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.sensor.rememberDeviceTilt
import top.yukonga.miuix.kmp.theme.LocalContentColor as MiuixLocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 底栏内容色的 CompositionLocal（上游同款）。 */
private val LocalFloatingBottomBarContentColor = staticCompositionLocalOf { Color.Unspecified }

/** 单个 tab 的缩放函数，液体玻璃模式下按压会放大到 1.2。 */
private val LocalFloatingBottomBarTabScale = staticCompositionLocalOf { { 1f } }

/** 底栏配色。 */
@Immutable
class FloatingBottomBarColors(
    val containerColor: Color,
    val indicatorColor: Color,
    val contentColor: Color,
    val activeContentColor: Color,
)

/** 配色默认值：全部取自 miuix 主题，不硬编码颜色。 */
object FloatingBottomBarDefaults {
    @Composable
    fun colors(
        containerColor: Color = MiuixTheme.colorScheme.surfaceContainer,
        indicatorColor: Color = MiuixTheme.colorScheme.primary,
        contentColor: Color = MiuixTheme.colorScheme.onSurface,
        activeContentColor: Color = indicatorColor,
    ): FloatingBottomBarColors = FloatingBottomBarColors(
        containerColor = containerColor,
        indicatorColor = indicatorColor,
        contentColor = contentColor,
        activeContentColor = activeContentColor,
    )
}

/** 渲染模式。 */
enum class FloatingBottomBarMode {
    LiquidGlass,
    Blur,
    None,
}

/** 选中指示器的镜面高光（上游原值，勿随意调）。 */
private val iosIndicatorSpecular: Highlight = Highlight(
    width = 1.dp,
    alpha = 1f,
    style = BloomStroke(
        color = Color.White.copy(alpha = 0.12f),
        innerBlurRadius = 2.0.dp,
        primaryLight = LightSource(
            position = LightPosition(0.5f, -0.3f, -0.05f),
            color = Color.White,
            intensity = 1f,
        ),
        secondaryLight = LightSource(
            position = LightPosition(0.5f, 0.8f, -0.5f),
            color = Color.White,
            intensity = 0.4f,
        ),
        dualPeak = true,
    ),
)

// 与 miuix-blur HighlightStyle 的 LIGHT_REF 保持一致，勿改。
private const val LIGHT_REF_X = 0.5f
private const val LIGHT_REF_Y = 0.7f
private const val GRAVITY_DIR_THRESHOLD_SQ = 0.01f // |g_xy| > 0.1，约 6° 倾斜

/**
 * 让高光的主光源跟随重力方向旋转（手机倾斜时高光会动）。
 *
 * `extraDegrees` 是额外叠加的 UV 顺时针偏移：底栏与指示器需要不同角度，
 * 否则两块高光会在同一位置叠成一条亮线。
 */
@Composable
private fun rememberGravityRotatedHighlight(base: Highlight, extraDegrees: Float = 0f): Highlight {
    val baseStyle = base.style as BloomStroke
    val tilt by rememberDeviceTilt()
    val rotatedPrimary = remember(tilt, baseStyle.primaryLight, extraDegrees) {
        val basePrimary = baseStyle.primaryLight
        val gx = tilt.gravityX
        val gy = tilt.gravityY
        val gMagSq = gx * gx + gy * gy
        val (lx0, ly0) = if (gMagSq > GRAVITY_DIR_THRESHOLD_SQ) {
            val invMag = 1f / sqrt(gMagSq)
            (gx * invMag) to (gy * invMag)
        } else {
            0f to -1f
        }
        val rad = extraDegrees * PI / 180.0
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val lx = c * lx0 - s * ly0
        val ly = s * lx0 + c * ly0
        basePrimary.copy(
            position = LightPosition(
                x = LIGHT_REF_X + lx,
                y = LIGHT_REF_Y + ly,
                z = basePrimary.position.z,
            ),
        )
    }
    return remember(base, rotatedPrimary) {
        base.copy(style = baseStyle.copy(primaryLight = rotatedPrimary))
    }
}
/**
 * 悬浮底栏（液体玻璃）。
 *
 * ## 三层结构（关键，不要简化）
 *
 *  1. **底层 Row**：画玻璃底 + 全部 tab 的未选中态内容；
 *  2. **选中层 Row**（`alpha = 0f` + `layerBackdrop(tabsBackdrop)`）：
 *     视觉上不可见，只用来把「选中态内容」录进一个独立的 backdrop，
 *     供第 3 层做折射取样 —— 这样指示器滑动时能折射出底下的选中态文字；
 *  3. **指示器 Box**：随拖拽滑动、按压形变、双光源镜面高光。
 *
 * 少任何一层都会退化：没有第 2 层 → 指示器里看不到内容；
 * 没有第 3 层 → 高亮是静态的，看着「死板」。
 *
 * ## 背景采样源从哪来
 *
 * `backdrop` 必须由**页面内容层**提供（`LocalPageBackdrop`），
 * 底栏自己在内容之上，自建 backdrop 采到的是自己。
 *
 * @param items 任意类型的条目列表
 * @param selectedIndex 当前选中下标（每次重组重新读取，支持外部状态变化）
 * @param onSelected 选中回调
 * @param backdrop 页面内容层的采样源
 * @param mode 渲染模式；低版本传 [FloatingBottomBarMode.None]
 * @param iconContent 图标槽
 * @param labelContent 文字槽
 */
@Composable
fun <T> FloatingBottomBar(
    items: List<T>,
    selectedIndex: () -> Int,
    onSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    mode: FloatingBottomBarMode = FloatingBottomBarMode.LiquidGlass,
    colors: FloatingBottomBarColors = FloatingBottomBarDefaults.colors(),
    iconContent: @Composable (item: T, index: Int) -> Unit,
    labelContent: @Composable (item: T, index: Int) -> Unit,
) {
    // 深色判断：上游从自定义主题拿，这里直接问 miuix 当前 scheme。
    val isInDark = MiuixTheme.colorScheme.onSurface.luminance() > 0.5f
    val pillShape = remember { CircleShape }
    val isLiquidGlassMode = mode == FloatingBottomBarMode.LiquidGlass
    val isBlurMode = mode == FloatingBottomBarMode.Blur
    val containerColor =
        if (isLiquidGlassMode) colors.containerColor.copy(alpha = 0.4f) else colors.containerColor

    // 录「选中态内容」的独立采样层（第 2 层用）。
    val tabsBackdrop = rememberLayerBackdrop()

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()
    val tabsCount = items.size

    // 布局测量：单个 tab 宽度与总宽，拖拽与指示器定位全靠这两个值。
    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var totalWidthPx by remember { mutableFloatStateOf(0f) }

    // 拖到边界时的橡皮筋位移。
    val offsetAnimation = remember { Animatable(0f) }
    val rubberBandPx = with(density) { 4.dp.toPx() }
    val panelOffset by remember(rubberBandPx) {
        derivedStateOf {
            if (totalWidthPx == 0f) {
                0f
            } else {
                val fraction = (offsetAnimation.value / totalWidthPx).fastCoerceIn(-1f, 1f)
                rubberBandPx * fraction.sign * EaseOut.transform(abs(fraction))
            }
        }
    }

    var currentIndex by remember { mutableIntStateOf(selectedIndex()) }
    val selectedIndexUpdated by rememberUpdatedState(selectedIndex)
    val onSelectedUpdated by rememberUpdatedState(onSelected)

    /** 手指位置（像素）→ tab 下标。RTL 时水平镜像。 */
    fun indexAt(positionX: Float): Int {
        if (tabWidthPx == 0f) return currentIndex
        val horizontalPaddingPx = with(density) { 4.dp.toPx() }
        val logicalX = if (isLtr) positionX else totalWidthPx - positionX
        return ((logicalX - horizontalPaddingPx) / tabWidthPx)
            .toInt()
            .fastCoerceIn(0, tabsCount - 1)
    }

    val dampedDragAnimation = remember(animationScope, tabsCount, density, isLtr) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = currentIndex.toFloat(),
            valueRange = 0f..(tabsCount - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            // 78/56：按压时指示器高度相对 56dp 的放大比（上游原值）。
            pressedScale = 78f / 56f,
            canDrag = { position -> position.x in 0f..totalWidthPx },
            onDragStarted = { position -> updateValue(indexAt(position.x).toFloat()) },
            onDragStopped = {
                val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                if (currentIndex != targetIndex) {
                    currentIndex = targetIndex
                    onSelectedUpdated(targetIndex)
                }
                updateValue(targetIndex.toFloat())
                animationScope.launch {
                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                }
            },
            onDragCancelled = {
                updateValue(currentIndex.toFloat())
                animationScope.launch {
                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                }
            },
            onDrag = { _, dragAmount ->
                if (tabWidthPx > 0f && dragAmount.x != 0f) {
                    updateValue(
                        (targetValue + dragAmount.x / tabWidthPx * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat()),
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            },
        )
    }

    // 外部改选中项 → 指示器滑过去（不是瞬移）。
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { selectedIndexUpdated() }.collectLatest { index ->
            if (currentIndex != index) {
                currentIndex = index
                dampedDragAnimation.animateToValue(index.toFloat())
            }
        }
    }

    val activateTab = remember(dampedDragAnimation) {
        { index: Int ->
            if (currentIndex != index) {
                currentIndex = index
                onSelectedUpdated(index)
            }
            dampedDragAnimation.animateToValue(index.toFloat())
        }
    }

    // 一个 tab = 图标 + 文字，垂直居中；最小宽 76dp（四个才排得下）。
    val tabsContent: @Composable RowScope.() -> Unit = {
        val scale = LocalFloatingBottomBarTabScale.current
        val contentColor = LocalFloatingBottomBarContentColor.current
        items.forEachIndexed { index, item ->
            Column(
                modifier = Modifier
                    .defaultMinSize(minWidth = 76.dp)
                    .semantics(mergeDescendants = true) {
                        selected = index == currentIndex
                        role = Role.Tab
                        onClick {
                            activateTab(index)
                            true
                        }
                    }
                    .onKeyEvent { event ->
                        val isActivationKey = event.key == Key.Enter ||
                            event.key == Key.NumPadEnter ||
                            event.key == Key.Spacebar
                        if (isActivationKey) {
                            if (event.type == KeyEventType.KeyUp) activateTab(index)
                            true
                        } else {
                            false
                        }
                    }
                    .focusable()
                    .fillMaxHeight()
                    .weight(1f)
                    .graphicsLayer {
                        val itemScale = scale()
                        scaleX = itemScale
                        scaleY = itemScale
                    },
                verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CompositionLocalProvider(
                    MiuixLocalContentColor provides contentColor,
                ) {
                    iconContent(item, index)
                    labelContent(item, index)
                }
            }
        }
    }

    // 按压光斑：只有液体玻璃模式 + API 33+ 才建（InteractiveHighlight 依赖 RuntimeShader）。
    val interactiveHighlight =
        if (isLiquidGlassMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            remember(animationScope, tabWidthPx) {
                InteractiveHighlight(
                    animationScope = animationScope,
                    position = { size, _ ->
                        Offset(
                            if (isLtr) {
                                (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset
                            } else {
                                size.width - (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset
                            },
                            size.height / 2f,
                        )
                    },
                )
            }
        } else {
            null
        }

    val baseHighlight = rememberGravityRotatedHighlight(iosIndicatorSpecular, extraDegrees = -45f)
    val pillHighlight = rememberGravityRotatedHighlight(iosIndicatorSpecular, extraDegrees = 90f)

    // 底栏本体（底层）叠加「选中态内容」的采样层，指示器才能折射出文字。
    val combinedBackdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop)

    Box(
        modifier = modifier.width(IntrinsicSize.Min),
        contentAlignment = Alignment.CenterStart,
    ) {
        // ── 第 1 层：底层（未选中态）────────────────────────────────────
        CompositionLocalProvider(LocalFloatingBottomBarContentColor provides colors.contentColor) {
            Row(
                Modifier
                    .onGloballyPositioned { coords ->
                        totalWidthPx = coords.size.width.toFloat()
                        val contentWidthPx = totalWidthPx - with(density) { 8.dp.toPx() }
                        tabWidthPx = (contentWidthPx / tabsCount).coerceAtLeast(0f)
                    }
                    .graphicsLayer { translationX = panelOffset }
                    .dropShadow(
                        shape = pillShape,
                        shadow = Shadow(
                            radius = 10.dp,
                            color = Color.Black,
                            alpha = if (isInDark) 0.2f else 0.1f,
                        ),
                    )
                    .then(
                        if (isLiquidGlassMode) {
                            Modifier.drawBackdrop(
                                backdrop = backdrop,
                                shape = { pillShape },
                                effects = {
                                    vibrancy()
                                    blur(4.dp.toPx(), 4.dp.toPx())
                                    lens(
                                        refractionHeight = 24.dp.toPx(),
                                        refractionAmount = 24.dp.toPx(),
                                    )
                                },
                                highlight = { baseHighlight.copy(alpha = 0.75f) },
                                layerBlock = {
                                    val width = size.width.coerceAtLeast(1f)
                                    val s = lerp(
                                        1f,
                                        1f + 16.dp.toPx() / width,
                                        dampedDragAnimation.pressProgress,
                                    )
                                    scaleX = s
                                    scaleY = s
                                },
                                // ★ 关键：底色由玻璃层自己画，不是把 color 设透明。
                                // 之前就是这里搞反了，导致整条底栏消失。
                                onDrawSurface = { drawRect(containerColor) },
                            )
                        } else if (isBlurMode) {
                            Modifier.drawBackdrop(
                                backdrop = backdrop,
                                shape = { pillShape },
                                effects = { blur(25.dp.toPx(), 25.dp.toPx()) },
                                onDrawSurface = { drawRect(containerColor.copy(alpha = 0.65f)) },
                            )
                        } else {
                            Modifier.background(containerColor, pillShape)
                        },
                    )
                    .then(
                        if (isLiquidGlassMode && interactiveHighlight != null) {
                            interactiveHighlight.modifier.then(interactiveHighlight.gestureModifier)
                        } else {
                            Modifier
                        },
                    )
                    .then(dampedDragAnimation.modifier)
                    .height(64.dp)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = tabsContent,
            )
        }

        // ── 第 2 层：选中态内容（不可见，只录进 tabsBackdrop）──────────
        if (isLiquidGlassMode) {
            CompositionLocalProvider(
                LocalFloatingBottomBarTabScale provides {
                    lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
                },
                LocalFloatingBottomBarContentColor provides colors.activeContentColor,
            ) {
                Row(
                    Modifier
                        .clearAndSetSemantics {}
                        .alpha(0f)
                        .layerBackdrop(tabsBackdrop)
                        .graphicsLayer { translationX = panelOffset }
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { pillShape },
                            effects = {
                                vibrancy()
                                blur(4.dp.toPx(), 4.dp.toPx())
                                lens(
                                    refractionHeight = 24.dp.toPx(),
                                    refractionAmount = 24.dp.toPx(),
                                )
                            },
                            onDrawSurface = { drawRect(containerColor) },
                        )
                        .then(interactiveHighlight?.modifier ?: Modifier)
                        .height(56.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabsContent()
                }
            }
        }

        // ── 第 3 层：滑动指示器 ────────────────────────────────────────
        if (tabWidthPx > 0f) {
            val tabWidthDp = with(density) { tabWidthPx.toDp() }
            if (isLiquidGlassMode) {
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .graphicsLayer {
                            val progressOffset = dampedDragAnimation.value * tabWidthPx
                            translationX = if (isLtr) {
                                progressOffset + panelOffset
                            } else {
                                -progressOffset + panelOffset
                            }
                        }
                        .drawBackdrop(
                            backdrop = combinedBackdrop,
                            shape = { pillShape },
                            effects = {
                                val progress = dampedDragAnimation.pressProgress
                                lens(
                                    refractionHeight = 10.dp.toPx() * progress,
                                    refractionAmount = 14.dp.toPx() * progress,
                                    depthEffect = true,
                                    chromaticAberration = 0.5f,
                                )
                            },
                            highlight = {
                                pillHighlight.copy(alpha = dampedDragAnimation.pressProgress)
                            },
                            layerBlock = {
                                scaleX = dampedDragAnimation.scaleX
                                scaleY = dampedDragAnimation.scaleY
                                // 速度越大越扁（沿运动方向拉伸），像真实液体。
                                val velocity = dampedDragAnimation.velocity / 10f
                                scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                                scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                            },
                            onDrawSurface = {
                                val progress = dampedDragAnimation.pressProgress
                                drawRect(
                                    color = if (!isInDark) {
                                        Color.Black.copy(alpha = 0.1f)
                                    } else {
                                        Color.White.copy(alpha = 0.1f)
                                    },
                                    alpha = 1f - progress,
                                )
                                drawRect(Color.Black.copy(alpha = 0.03f * progress))
                            },
                        )
                        .innerShadow(shape = pillShape) {
                            InnerShadow(
                                radius = 8.dp * dampedDragAnimation.pressProgress,
                                color = Color.Black.copy(alpha = 0.15f),
                                alpha = dampedDragAnimation.pressProgress,
                            )
                        }
                        .height(56.dp)
                        .width(tabWidthDp),
                )
            } else {
                // 非玻璃：实色指示器 + 叠加一份选中态内容（因为无法折射）。
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .graphicsLayer {
                            val progressOffset = dampedDragAnimation.value * tabWidthPx
                            translationX = if (isLtr) {
                                progressOffset + panelOffset
                            } else {
                                -progressOffset + panelOffset
                            }
                        }
                        .clip(pillShape)
                        .background(colors.indicatorColor.copy(alpha = 0.15f), pillShape)
                        .height(56.dp)
                        .width(tabWidthDp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    CompositionLocalProvider(
                        LocalFloatingBottomBarContentColor provides colors.activeContentColor,
                    ) {
                        Row(
                            Modifier
                                .clearAndSetSemantics {}
                                .wrapContentWidth(align = Alignment.Start, unbounded = true)
                                .requiredWidth(
                                    with(density) { (totalWidthPx - 8.dp.toPx()).toDp() },
                                )
                                .height(56.dp)
                                .graphicsLayer {
                                    val progressOffset = dampedDragAnimation.value * tabWidthPx
                                    translationX = if (isLtr) -progressOffset else progressOffset
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            content = tabsContent,
                        )
                    }
                }
            }
        }
    }
}
