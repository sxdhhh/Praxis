package dev.praxis.ui.widgets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.praxis.ui.theme.PraxisMotion

/**
 * 折叠面板的内容区：展开/收起时高度与透明度一起过渡。
 *
 * ## 一、为什么需要它（而不是各处写 `if (expanded) { ... }`）
 *
 * 仓库里原有三处折叠面板都是直接 `if (expanded) { 内容 }`：
 *
 * | 位置 | 现象 |
 * |---|---|
 * | `ChatSettingsBar` 的 `FoldSection` | 点一下，整个分区的内容**瞬间跳出/消失** |
 * | `PendingQueuePanel` 的排队列表 | 同上，且它是个 `LazyColumn`，跳出来时高度很大 |
 * | `PraxisOnboardingPage` 的下拉选项 | 同上 |
 *
 * 直接增删在数据上没错，但**没有任何过渡** —— 用户点一下，屏幕下半部分
 * 在一帧内被撑开或压平，观感是「界面闪了一下」。这是「没做动画」里
 * 最容易被感知的一类，因为它改变的是**布局高度**，而不只是一个属性。
 *
 * ## 二、为什么用 `expandVertically` + `fadeIn` 两个组合，而不是只做一个
 *
 * 只做高度（`expandVertically`）：内容在展开过程中被**裁切**显示 ——
 * 一个字的高度还没长够时，文字只露出上半截，像被刀切过。
 * 只做透明度（`fadeIn`）：高度是瞬间到位的，元素位置仍然会跳。
 *
 * 两者叠加后：高度先长出来（内容被裁切的部分很快变少），
 * 同时透明度补上「渐显」的柔和感。这正是 Operit 与 Material 的通行做法。
 *
 * ## 三、时长为什么取 `PraxisMotion.Expansion` 的四个值
 *
 * 那四个值（FadeIn 300 / Expand 400 / FadeOut 250 / Shrink 350）是
 * 上游 Operit 的原值，而且**刻意不对称**：退出比进入快。
 * 理由见 `PraxisMotion.Expansion` 的 KDoc —— 收起时用户已经做完决定。
 *
 * 这里不另挑数字。项目规矩是「照抄不重写」，多一套刻度只会让
 * 全应用的折叠手感不一致。
 *
 * ## 四、为什么 `@Composable ColumnScope.()` 而不是 `() -> Unit`
 *
 * 折叠内容几乎总是「若干纵向排列的条目」。给 `ColumnScope` 接收者之后，
 * 调用方可以直接在内容里用 `Modifier.weight(1f)`（同一 Row 内常见）
 * 或 `align(Alignment.CenterHorizontally)` —— 这正是 `Spacer` / `Row`
 * 等官方容器都提供 scope 的原因。
 *
 * 内部自己套一层 `Column`：`expandVertically` 需要一个可测量的容器，
 * 而且直接给 `Column` 保证内容纵向堆叠的语义与调用方直觉一致。
 *
 * @param visible 是否展开。**调用方始终调用本函数**，只翻这个布尔 ——
 *   包一层 `if (visible)` 会让退场动画来不及播（与 `MiuixAnimatedDialog`
 *   同一条约束）。
 * @param modifier 应用在内容容器上的修饰符。
 * @param content 折叠内容。
 */
@Composable
fun FoldableContent(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(
            animationSpec = tween(
                durationMillis = PraxisMotion.Expansion.Expand,
                // Decelerate：高度增长由快变慢，收尾柔和。
                // 用 Standard 会带加速段，展开时像「被弹出来」。
                easing = PraxisMotion.Curves.Decelerate,
            ),
            // `expandFrom = Alignment.Top` 是默认值，写出来是为了明确：
            // 面板从**上边缘**往下长，而不是从中心向两头长。
            // 后者在「点第 1 个分区、下方还有 4 个分区」时会把它下方的内容
            // 往上顶一下再往下推，观感是整块抖了一下。
            expandFrom = androidx.compose.ui.Alignment.Top,
        ) + fadeIn(
            animationSpec = tween(
                durationMillis = PraxisMotion.Expansion.FadeIn,
                easing = PraxisMotion.Curves.Decelerate,
            ),
        ),
        exit = shrinkVertically(
            animationSpec = tween(
                durationMillis = PraxisMotion.Expansion.Shrink,
                easing = PraxisMotion.Curves.Accelerate,
            ),
            shrinkTowards = androidx.compose.ui.Alignment.Top,
        ) + fadeOut(
            animationSpec = tween(
                durationMillis = PraxisMotion.Expansion.FadeOut,
                easing = PraxisMotion.Curves.Accelerate,
            ),
        ),
    ) {
        Column(modifier = modifier, content = content)
    }
}