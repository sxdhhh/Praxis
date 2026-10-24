package dev.praxis.ui.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 单页骨架：每个目的地页面自己调用的那一层。
 *
 * ## 为什么每页都要自己调（2026-10-22 导航重构的核心决定）
 *
 * 旧结构是**全应用一份 `AppScaffold`**，包在 `NavDisplay` 外面，页面只是它的
 * `content`。用户这次报的三类故障，根子都在这个结构上：
 *
 * | 现象 | 真因 |
 * |---|---|
 * | 二级页背景透明、能看到下层 | Scaffold 的 `containerColor` 在**外层**，`NavDisplay` 的每个 entry 在它之上做转场，页面根容器（LazyColumn/Column）默认不画背景 → 转场中直接透出下层 |
 * | 弹窗 / 下拉点了不弹或闪退 | miuix 的 popup 需要 `Scaffold` 内部的 `PopupHost` 提供宿主。页面在外层 Scaffold 的 content 里，属于**外层子组合**的更深一层，但没有自己的宿主；而 miuix 的 `WindowSpinnerPreference` 之类会尝试开新 Window → 与 NavDisplay 的 entry 抢焦点 → 崩 |
 * | 底栏在二级页也显示 / 忘了隐藏 | 底栏挂在全局 Scaffold 的 `bottomBar` 槽，靠 `showBottomBar` 判据决定画不画；判据改过两次仍有边缘情况 |
 *
 * 每页自带 Scaffold 之后，这三个问题**在结构上不可能发生**：
 *  - 每页自己有一层 `Surface(containerColor)` → 天然不透明；
 *  - 每页自己有一个 `PopupHost` → 弹窗在**本页组合内**，不开新 Window；
 *  - 底栏只属于 `Main` 目的地的组合 → 进二级页它压根不存在。
 *
 * 这也是 InstallerX 的做法（41 个页面里 38 个自己套 `Scaffold`），
 * 本轮照它改。
 *
 * ## 与 [AppScaffold] 的关系
 *
 * [AppScaffold] 仍是**唯一**实现（顶栏渐变模糊、backdrop 采样、inset 下发、
 * 高低版本分流都在里面）。本组件只是它的「页面级」薄封装：
 *
 *  - 收一个 `title`（页面自己给，不从路由反查）；
 *  - `onBack` 非空就画返回箭头；
 *  - 把 `bottomInset` 默认为 0 —— 二级页没有底栏，滚动终点不该抬高；
 *  - 透传 `bottomBar` / `overlay` / `actions` 三个槽给 `Main` 这种需要底栏的页面。
 *
 * ## 为什么 `bottomInset` 默认 0 而不是 24
 *
 * `AppScaffold` 的默认值是 24dp（给普通页面留呼吸）。但二级页的滚动容器
 * 自己已经有 `SettingsList` 的 `bottomExtra = 24dp`，两处相加会变成 48dp，
 * 末项与屏幕底之间空出一大块。这里给 0，让「留白」只由滚动容器一处决定。
 */
@Composable
fun PraxisPage(
    title: String,
    modifier: Modifier = Modifier,
    /** 非空则画左上角返回箭头。二级页传 `navigator::pop`，一级页传 null。 */
    onBack: (() -> Unit)? = null,
    /** 左上角主操作（如打开侧边栏）。与 [onBack] 是不同槽位，见 [AppScaffold]。 */
    leadingAction: (@Composable () -> Unit)? = null,
    /** 右上角动作槽。 */
    actions: (@Composable RowScope.() -> Unit)? = null,
    /**
     * 底栏槽。只有 `Main` 目的地会传 —— 二级页不传，
     * 于是底栏在二级页**不存在**，不需要任何判据。
     */
    bottomBar: (@Composable BoxScope.() -> Unit)? = null,
    /** 覆盖层（抽屉等），盖在内容与底栏之上、采样源之外。 */
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    bottomInset: Dp = 0.dp,
    content: @Composable (PaddingValues) -> Unit,
) {
    AppScaffold(
        title = title,
        onBack = onBack,
        modifier = modifier.fillMaxSize(),
        bottomInset = bottomInset,
        bottomBar = bottomBar,
        topBarActions = actions,
        leadingAction = leadingAction,
        overlay = overlay,
        content = content,
    )
}

/**
 * 页面根容器：铺底色 + 吃掉顶栏内边距之外的安全区。
 *
 * ## 为什么还要这一层
 *
 * [PraxisPage] 的 `AppScaffold` 已经在最外层铺了 `containerColor`，但
 * `NavDisplay` 的 entry 是**子组合**，转场时上层 entry 会带上 alpha / 圆角，
 * 一旦页面根容器（`LazyColumn`）自己不画背景，转场中就会透出下层。
 *
 * 这一层就是「每个页面的不透明底」。它同时把 [LocalTopBarInset] 之外的
 * 内容区完全铺满，避免出现「页面比屏幕小一圈」的白边。
 *
 * ## 为什么不用 `Modifier.background` 直接写在调用处
 *
 * 因为容易漏。旧实现里 8 个设置页有 7 个漏了，最后靠改 `SettingsList`
 * 一个函数兜住。这一层把这个常识固化下来：凡是页面的根 Box，都用它。
 */
@Composable
fun PageSurface(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        // 铺 surface 而不是 surfaceContainer：与 AppScaffold 的 containerColor 同色阶，
        // 转场交接处不会闪出一道色差。
        modifier = modifier
            .fillMaxSize()
            .background(top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.surface),
        content = content,
    )
}