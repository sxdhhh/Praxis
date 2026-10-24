package dev.praxis.ui.design.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.praxis.ui.theme.PraxisMotion

/**
 * 一个页面「底部被输入法顶起」所需的两个量。
 *
 * 两个量都从**同一个**动画值派生，因此任何时刻都满足
 *
 * ```
 * bottomPadding + imeLift == scrollLimit
 * ```
 *
 * 这条恒等式是本文件存在的唯一理由：只要它成立，页面底部那一块
 * 无论键盘动画进行到哪一帧，都不会多留也不会少留。
 */
@Stable
data class ImeLift(
    /** 输入法顶起的高度（动画值）。与 [bottomPadding] 同源。 */
    val imeLift: Dp,
    /** 还需要额外给浮层底栏让出的高度（`scrollLimit - imeLift`，永不为负）。 */
    val bottomPadding: Dp,
)

/**
 * 计算 [ImeLift]，并**对键盘高度本身做动画**。
 *
 * ## 为什么不再直接用 `Modifier.imePadding()`
 *
 * ### 第 1、2 次修法（都错）
 *
 * 第 1 次：`if (imeHeight > 0.dp) 0.dp else scrollLimit` → 一帧突变。
 *
 * 第 2 次：给上面结果套 `animateDpAsState(tween(200))` → 仍抖。因为
 * `if (imeHeight > 0.dp)` 判断的**本身就是一个正在动的量**：键盘收起时
 * `imeHeight` 从键盘高度衰减到 0，中途跨过阈值的那一帧目标值翻转，
 * 动画再从那一刻起追 200ms。两条曲线起点不同 → 中间必然有一段
 * `imePadding()` 已收回、`scrollLimit` 还没补上 → 用户说的
 * 「先收回到 tab 栏下面，然后突然瞬移到 tab 上面」。
 *
 * ### 第 3 次修法（不错位了，但丢掉了动画）
 *
 * 第 3 次：`(scrollLimit - imeHeight).coerceAtLeast(0.dp)` 配原始
 * `.imePadding()`。数学上完美，但当时的注释假设「平台会把
 * `WindowInsets.ime` 的动画逐帧传导过来」。**实测不成立**：
 * Praxis 用了 `enableEdgeToEdge()` 且没有注册
 * `WindowInsetsAnimationCallback`，Compose 只拿到 IME 的**起止两态**，
 * inset 是阶跃的。于是两个量一起跳：不错位了，但**抬升没有动画**。
 *
 * ## 现在的做法：只给「键盘高度」这一个量做动画，两个 padding 都从它派生
 *
 * ```
 * t = imeHeight 的动画值               （0 … 键盘高度，逐帧平滑）
 * imeLift       = t
 * bottomPadding = (scrollLimit - t).coerceAtLeast(0.dp)
 * ```
 *
 * 两者都是同一条 `t` 的线性函数，所以 `imeLift + bottomPadding` 在
 * `t <= scrollLimit` 时恒等于 `scrollLimit` —— **中间态不可能错位**；
 * 而 `t` 自己有缓动 —— **抬升与收起都有动画**。两个历史 bug 一起解决。
 *
 * ## 时长与曲线
 *
 * 取 [PraxisMotion.Durations.Fast]（120ms）配 [PraxisMotion.Curves.Decelerate]：
 *  - 不用 200ms：MIUI 键盘动画约 150~180ms，200ms 会拖尾，表现为
 *    「键盘已经没了、内容还在慢慢往下滑」；
 *  - 不用 `Linear`：系统键盘动画是减速的，线性显得机械；
 *  - 用 `Decelerate` 而非 `Standard`：`Standard` 带加速段（FastOut），
 *    键盘弹起时内容会「先慢后快」，与手指按下的即时感不匹配。
 *
 * ## 调用方必须做的事
 *
 * 用返回值**替换**原来的 `.imePadding()`，不要两者同时用：
 *
 * ```kotlin
 * val lift = rememberImeLift(scrollLimit)
 * Column(Modifier.fillMaxSize().imeLiftPadding(lift)) { ... }
 * ```
 *
 * 一边 `.imePadding()` 一边再用本函数会**叠加两份抬升**，键盘弹起时
 * 底部会空出一整个键盘的高度。
 *
 * @param scrollLimit 浮层底栏高度（`LocalScrollBottomLimit`）。
 */
@Composable
fun rememberImeLift(scrollLimit: Dp): ImeLift {
    val target = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    // 只给这一个量做动画。
    //
    // 用 `tween` 而不是 `spring`：键盘动画是有确定终点的时间动画，
    // 弹簧的过冲会让输入框越过目标位置再回弹一次，观感比没有动画更糟。
    val animated by animateDpAsState(
        targetValue = target,
        animationSpec = tween(
            durationMillis = PraxisMotion.Durations.Fast,
            easing = PraxisMotion.Curves.Decelerate,
        ),
        label = "imeLift",
    )
    return ImeLift(
        imeLift = animated,
        bottomPadding = (scrollLimit - animated).coerceAtLeast(0.dp),
    )
}

/**
 * 与 [rememberImeLift] 配套：替代 `.imePadding()`，让出 [ImeLift.imeLift]。
 *
 * ## 为什么让的是 `imeLift` 而不是 `bottomPadding`
 *
 * 这是两处**不同**的空隙，各自由不同层承担：
 *
 * ```
 * 根容器  imeLiftPadding  → 让出 imeLift       （键盘占掉的高度）
 * 内部    contentPadding / 输入条 bottomPadding → 让出 bottomPadding（底栏高度）
 * ```
 *
 * 键盘收起时 `imeLift = 0`、`bottomPadding = scrollLimit`：根不让，
 * 内部让满底栏 —— 内容正好停在玻璃底栏之上。
 *
 * 键盘弹出时 `imeLift = 键盘高度`、`bottomPadding = 0`：根让出整个键盘，
 * 内部不再让 —— 输入条正好贴在键盘上沿，中间不会多出「看不见的底栏高度」。
 *
 * 中间态两者是同一个 `t` 的互补量，所以「根让出的 + 内部让出的」
 * 在任何一帧都等于 `max(imeLift, scrollLimit)`，不会多一帧少一帧。
 *
 * 若让的是 `bottomPadding`，键盘弹出时值为 0 → 根容器完全不缩 →
 * **键盘会直接盖住输入条**。
 *
 * ## 为什么刻意不 `consumeWindowInsets`
 *
 * `.imePadding()` 会消费 IME inset，声明「这块已经处理过了」。这里不做：
 * 本 modifier 已经吃掉了等量空间，若再消费一次，子树里任何仍想读 IME
 * inset 的控件（例如输入框自己判「键盘是否可见」）会读到 0，更容易写错。
 *
 * 代价是：若某个祖先也调 `.imePadding()`，会叠加。所以约定本项目
 * **只有页面根容器**使用 [rememberImeLift]，中间层不得再调 `.imePadding()`。
 */
fun Modifier.imeLiftPadding(lift: ImeLift): Modifier =
    this.padding(bottom = lift.imeLift)