package dev.praxis.ui.widgets

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.praxis.ui.theme.PraxisMotion

/** 进入动画时长。240ms = [PraxisMotion.Durations.Base]，与项目其余入场一致。 */
private const val ENTER_DURATION_MS = PraxisMotion.Durations.Base

/**
 * 退出动画时长。**比进入短**（160ms）。
 *
 * ## 为什么刻意不对称
 *
 * 用户点「关闭」时已经做完决定，多等 100ms 就嫌慢；而进入时多看一帧
 * 会觉得「有东西长出来」。这与 `PraxisMotion.Expansion` 里
 * `FadeIn 300 / FadeOut 250` 是同一条判断，只是这里整体更快。
 */
private const val EXIT_DURATION_MS = 160

/** 进入起始缩放。0.94 而不是 0.8：弹窗不应该像从远处飞来，只该「微微长出」。 */
private const val ENTER_SCALE = 0.94f

/** 进入起始下滑距离（dp）。与 `PraxisMotion.Offsets.EnterSlide` 同值。 */
private const val ENTER_SLIDE_DP = 12f

/** 退出终态缩放。比 1 略小，收得干脆但不消失成一个点。 */
private const val EXIT_SCALE = 0.97f

/**
 * 向上抬起的比例（相对屏幕高度）。见 [LIFT_MAX_DP] 的说明。
 */
private const val LIFT_RATIO = 0.06f

/**
 * 抬起量上限（dp）。
 *
 * ## 为什么要设上限
 *
 * 纯比例在高屏上会把弹窗挪到接近顶部 —— 那时它既不在视觉中心，也不靠边，
 * 观感是「位置算错了」。48dp 大约是一个次级标题带的高度，抬到这里
 * 弹窗仍明显偏上，但不会跑到屏幕三分之一处。
 */
private const val LIFT_MAX_DP = 48f

/** 抬起量下限（dp）。太小的值在短屏上等于没抬。 */
private const val LIFT_MIN_DP = 16f

/**
 * 抬起量的合法区间。
 *
 * 提到顶层而不是写在 `BoxWithConstraints` 里：`BoxWithConstraints` 的
 * content 是一个 lambda，每次测量变化都会重新执行；在里面写
 * `LIFT_MIN_DP.dp..LIFT_MAX_DP.dp` 会每帧分配一个 range 对象。
 * 区间是编译期常量，没必要反复造。
 */
private val LIFT_RANGE = LIFT_MIN_DP.dp..LIFT_MAX_DP.dp

/**
 * 带进场/退场动画的居中弹窗，并把位置**整体上移一点**。
 *
 * ## 一、为什么要这个组件（而不是每个弹窗各写一遍动画）
 *
 * 原来的弹窗是 `Dialog { Card { ... } }`。`Dialog` 一旦挂上就**直接出现**，
 * 关闭时也是整块消失 —— 没有任何过渡。用户对这类弹窗的观感就是
 * 「硬邦邦地闪出来」。
 *
 * 逐个弹窗加动画的写法是重复的（`Animatable` + `graphicsLayer` +
 * 挂载状态机，约 40 行，12 个弹窗就是 480 行重复代码），而其中
 * 挂载状态机那段最容易写错（见下面第三节）。所以收成一个组件。
 *
 * ## 二、为什么「上移」用屏幕高度比例 + 上限
 *
 * 弹窗默认在窗口正中。用户在这一页打开它时，下半屏是列表与按钮，
 * 正中会压住「保存修改」—— 上移让弹窗与下方操作区分离。
 *
 * 但固定 dp 在短屏上过头、在高屏上不够；纯比例在高屏上又会把弹窗
 * 推到接近顶部。所以取 `min(高度 × 6%, 48dp)` 并设 16dp 下限。
 *
 * ## 三、⚠️ 调用点必须**常驻挂载**，不能 `if (visible) { ... }`
 *
 * 这是本组件唯一的使用约束，也是最容易踩错的地方：
 *
 * ```kotlin
 * // ✗ 错：visible 变 false 那一刻组件被移除，退场动画来不及播
 * if (visible) { MiuixAnimatedDialog(visible = visible, ...) { ... } }
 *
 * // ✓ 对：组件一直在，visible 只是一个参数
 * MiuixAnimatedDialog(visible = visible, ...) { ... }
 * ```
 *
 * 所以组件内部有三个状态而不是一个：
 *
 * | 状态 | 含义 |
 * |---|---|
 * | `visible` | 调用方想要的终态 |
 * | `mounted` | **当前是否还挂着 window**（退场动画播完后才置 false） |
 * | `progress` | 0…1 的动画进度，`alpha` / `scale` / `translationY` 都从它派生 |
 *
 * `mounted` 与 `visible` 分离，才能做到「先播完退出、再拆 window」。
 *
 * ## 四、为什么自己做全屏点击层
 *
 * `usePlatformDefaultWidth = false` 让 window 变成全屏，此时系统不再有
 * 「dialog 之外」的概念 —— 点空白处不会触发 `onDismissRequest`，
 * 用户会觉得「点外面关不掉」。
 *
 * 所以在最底层铺一个**透明**点击层来接手这个动作。刻意不画颜色：
 * 系统 dim（0.6）已经存在，再叠一层自制遮罩会黑得看不清内容。
 *
 * 也刻意用 `indication = null`：这是一层不可见的背景，出现涟漪会暴露它。
 *
 * ## 五、`onDismissRequest` 与 `visible` 的关系
 *
 * 组件**不改** `visible` —— 它只是个参数。点外部、按返回键时调用
 * `onDismissRequest`，由调用方把 `visible` 置 false；随后本组件播完
 * 退场动画再拆掉 window。这条单向数据流保证「退场一定播得出来」。
 *
 * @param visible 是否应该显示。**必须常驻调用**（见第三节）。
 * @param onDismissRequest 用户要求关闭（点击外部 / 返回键）。调用方负责把
 *   `visible` 置 false；这里不代改，避免出现两个真相。
 * @param modifier 应用在**卡片容器**上的修饰符（宽度、点击拦截等）。
 * @param maxWidth 卡片最大宽度。默认 560dp，与平台默认 dialog 宽度同量级；
 *   窗口已是全屏，不设上限的话平板/折叠屏上会拉成一条。
 * @param content 卡片内容。
 */
@Composable
fun MiuixAnimatedDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 560.dp,
    content: @Composable () -> Unit,
) {
    // 是否还挂着 window。
    //
    // ## 为什么初值取 visible 而不是 false
    //
    // 若初值写 false，那么「组合时 visible 就是 true」的调用方（页面一进去
    // 就打开弹窗的场景）会经历：第一帧不挂 window → `LaunchedEffect` 跑起来
    // 才置 true → 第二帧才出现。那一帧的空档在慢设备上看得见（像闪了一下）。
    //
    // 初值取 visible 后，第一帧就挂上 window，同时 `progress` 初值也是 1 ——
    // 直接以「完全显示」的状态出现。而 `LaunchedEffect` 首次运行会把它归零
    // 再动画到 1，于是进入动画仍然播得出来，只是起点不会经过空白帧。
    var mounted by remember { mutableStateOf(visible) }

    /**
     * 当前这段动画是「进入」还是「退出」。
     *
     * ## 为什么需要它（而不是直接看 `visible`）
     *
     * 缩放的两端基准不同：进入是 `0.94 → 1`，退出是 `1 → 0.97`。
     * 若退出时仍用进入的基准，弹窗会缩回 0.94 —— 那看起来像「被吸回去」，
     * 比消失更抢眼。
     *
     * `visible` 在退出动画期间已经是 false，因此只能靠这个标记区分方向：
     * 在 `LaunchedEffect(visible)` 里固定下来，动画全程不变。
     */
    var entering by remember { mutableStateOf(visible) }
    val progress = remember { Animatable(if (visible) 1f else 0f) }

    LaunchedEffect(visible) {
        entering = visible
        if (visible) {
            // ## `mounted` 已经是 true 时不要重播进入动画（2026-10-24 修）
            //
            // `mounted` 初值取 visible。所以「页面一进来弹窗就是打开的」这条
            // 路径上，第一帧 `mounted == true` 且 `progress == 1`（已完全显示）。
            // 此时若无条件 `snapTo(0f)`，用户会先看到弹窗**瞬间消失**、
            // 再从 0.94 重新长出来 —— 一个明显的一闪。
            //
            // 正确语义：只有「从关到开」才需要播进入动画；「本来就是开着的」
            // 直接保持在 1。用 `progress.value == 0f` 判断即可（它在
            // snapTo 之前必然是上次的终值：关闭态为 0、打开态为 1）。
            if (!mounted) {
                mounted = true
                // 从 0 起播：这是一次真正的「打开」，需要完整进入动画。
                progress.snapTo(0f)
            } else if (progress.value >= 1f) {
                // 已经是完全显示状态：什么都不用做。
                return@LaunchedEffect
            } else {
                // 处于中途（上一次退出被打断又立刻打开）：接着往上动画，
                // 从当前进度继续，比归零重播更连贯。
                mounted = true
            }
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = ENTER_DURATION_MS,
                    // QuartOut 起步快、尾段极缓：与「弹窗从中心长出」的物理感一致。
                    // 不用 Decelerate：它的加速段会让卡片先停顿一下再窜出来。
                    easing = PraxisMotion.Curves.QuartOut,
                ),
            )
        } else {
            if (!mounted) return@LaunchedEffect
            progress.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = EXIT_DURATION_MS,
                    // 退出用加速曲线：越收越快，符合「离开」的直觉。
                    easing = PraxisMotion.Curves.Accelerate,
                ),
            )
            // 动画播完才拆 window。放在这里而不是 animateTo 之前，
            // 就是为了让那 160ms 真的看得见。
            mounted = false
        }
    }

    if (!mounted) return

    Dialog(
        onDismissRequest = onDismissRequest,
        // 全屏 window：只有这样「按比例上移」才有正确的高度参照
        // （默认宽度模式下 window 是 wrap content，高度不是屏幕高度）。
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // 抬起量在这里算：`maxHeight` 此刻就是屏幕可用高度。
            val lift = (maxHeight * LIFT_RATIO).coerceIn(LIFT_RANGE)

            // 透明点击层：接手「点外面关闭」（见 KDoc 第四节）。
            val interaction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClickLabel = "关闭弹窗",
                        onClick = onDismissRequest,
                    ),
            )

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = modifier
                        // ## 修饰符顺序是有意的，不能调换（2026-10-24 核对）
                        //
                        // Compose 的 modifier 链按书写顺序**由外向内**包裹：
                        //
                        //   widthIn(max = 560dp)   ← 先限制最大宽度（外层）
                        //   .fillMaxWidth()        ← 在内层填满那个受限宽度
                        //   .padding(horizontal=24)← 最内层留边
                        //
                        // 若写成 `fillMaxWidth().widthIn(max = ...)`，外层已被强制
                        // 撑满屏幕宽度，内层的上限就不再生效 —— 平板/折叠屏上
                        // 弹窗会被拉成横贯整屏的一条。这是 Compose 里最经典的
                        // 「顺序写反、看起来却没错」的一类 bug。
                        //
                        // 也不把 `fillMaxWidth` 写在 `padding` 之后：那样内边距
                        // 会被算进「填满」之后的内层，实际可视宽度不变，
                        // 但测量过程多一轮，且与父级约束的交互更难推理。
                        .widthIn(max = maxWidth)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        // 静息位置上移（布局层）。
                        .offset(y = -lift)
                        .graphicsLayer {
                            val p = progress.value
                            // 淡入淡出
                            alpha = p
                            // 缩放：基准随方向切换（见 `entering` 的 KDoc）。
                            //   进入：0.94 → 1（p 从 0 到 1）
                            //   退出：1 → 0.97（p 从 1 到 0）
                            val base = if (entering) ENTER_SCALE else EXIT_SCALE
                            val scale = base + (1f - base) * p
                            scaleX = scale
                            scaleY = scale
                            // 下滑：进入时从 +12dp 落到 0；退出时反向回到 +12dp，
                            // 幅度一致（缩放已经做了不对称，位移再不对称会显得散）。
                            translationY = (1f - p) * ENTER_SLIDE_DP.dp.toPx()
                        },
                ) {
                    content()
                }
            }
        }
    }
}
