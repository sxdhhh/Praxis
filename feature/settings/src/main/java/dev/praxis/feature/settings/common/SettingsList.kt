package dev.praxis.feature.settings.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.praxis.ui.design.component.AutoFollowScrollLazy
import dev.praxis.ui.design.component.LocalScrollBottomLimit
import dev.praxis.ui.design.component.LocalTopBarInset
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置类页面的统一滚动容器。
 *
 * ## 为什么不让每个页面自己写 `LazyColumn(contentPadding = ...)`
 *
 * 因为漏写过的代价很具体：底栏是**浮层**（浮在内容之上，内容从它下方穿过，
 * 玻璃才有东西可折射），所以内容区不能靠 padding 给它让位 ——
 * 但**滚动终点**必须抬高，否则最后一项永远滚不到底栏上方，被压住看不见。
 *
 * 这个坑真实发生过：8 个设置页全都硬编码 `bottom = 24.dp`，
 * 而底栏占位是 96dp（`BottomBarSlot`），于是每个设置页的最后一项都被遮住约 72dp。
 * 聊天页与终端页当时写对了（读了 `LocalScrollBottomLimit`），设置页全漏了。
 *
 * 收敛成一个容器后，「以后新增设置页」只要用 [SettingsList] 就不会再漏。
 *
 * ## 为什么不是 `AppListScaffold`
 *
 * `AppListScaffold` 内部会再套一层 `AppScaffold`（含顶栏与 Scaffold）。
 * 设置页的顶栏已经由 `:app` 的 `PraxisApp` 提供了，再套一层会出现双顶栏。
 * 所以这里只做「滚动容器 + 底部让位」，不含任何栏。
 *
 * @param bottomExtra 底栏之上再留的呼吸间距。默认 24dp，与改造前各页一致。
 * @param includeTopInset 是否自动加顶部内边距（顶栏高度）。
 *   默认 true —— 这些页面现在既可能作为设置子页、也可能作为**域首页**（记忆/工作流），
 *   域首页时顶栏由 `:app` 的 `AppScaffold` 提供，不加 top 会让分组标题被顶栏压住。
 */
@Composable
fun SettingsList(
    modifier: Modifier = Modifier,
    bottomExtra: Dp = 24.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    includeTopInset: Boolean = true,
    /**
     * 丝滑跟随是否生效。
     *
     * 默认 true。只有「自己接管了跟随引擎」的页面才传 false（如日志页），
     * 否则两个引擎会同时向同一个 `LazyListState` 推进，表现为抖动。
     */
    autoFollow: Boolean = true,
    /**
     * 外部持有的滚动状态。
     *
     * 日志页需要它：滚顶/滚底按钮必须能读 `canScrollBackward` 并驱动 `scrollToItem`。
     * 默认值用 [rememberLazyListState]，其余页面不用关心。
     */
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    // ★ 2026-10-21：丝滑滚动统一接入（apixiaoyuan `AutoFollowScroll`，k=130 / c=24 / m=1）。
    //
    // ## 为什么设置页也要接，但 `initialAutoFollow = false`
    //
    // 用户要求「所有需要滚动的地方都接上」。设置页确实需要 —— 手指一拨的惯性、
    // 到达底部时的贴边，两处都由这个引擎接管，与聊天页/日志页手感一致。
    //
    // 但**不能**默认跟随：设置页的内容是一次性给的，没有「新增」，
    // 跟随只会让一进页就自己滑到底，那是副作用而不是特性。所以：
    //  - `initialAutoFollow = false` → 打开时停在顶部；
    //  - `enabled = true`           → 引擎仍在，用户滚到底后继续享受贴边跟随。
    //
    // 日志页是例外：它有自己的滚顶/滚底按钮且内容持续追加，
    // 需要的是「打开就贴最新」，由 [LogScreen] 自己调 `AutoFollowScrollLazy` 接管，
    // 这里传 false 把引擎让给它，避免两个引擎同时推进同一个 listState。
    AutoFollowScrollLazy(
        lazyState = state,
        // ## 为什么这里不能传 `state.layoutInfo.totalItemsCount`（2026-10-22 修）
        //
        // 那个值是**布局测量的产物**，不是「内容版本号」。它的变化时机是
        // 「Compose 什么时候完成了测量」，而不是「内容什么时候变多」：
        //  - 首帧是 0，测量后变成实际条数 → 从 0 到 N 被视为「新增」；
        //  - 滚动中视口外的条目会被回收，`totalItemsCount` 仍然稳定，
        //    但转场动画/尺寸变化时它会短暂抖动；
        //  - 页面重建时又从 0 开始。
        //
        // 把它当作「新内容」的判据，就会出现「一进设置页就被判定为新增、
        // 于是滚到底」——这正是用户报的第 10 项。
        //
        // 设置页的内容**一次性给全**，没有任何「新增」语义，
        // 所以这里根本不参与新增判定：`itemCount` 传一个恒定值 0，
        // 引擎的②号 effect 直接返回（`itemCount <= 0` 早退）。
        // 跟随开关仍然工作（用户滚到底后继续贴边），只是永不主动滚动。
        itemCount = 0,
        enabled = autoFollow,
        initialAutoFollow = false,
    )
    // 底栏高度由 :app 通过 LocalBottomBarInset → LocalScrollBottomLimit 下发。
    // 没装底栏的场景（如将来把某页做成独立 Activity）该值是 0.dp，行为不变。
    val scrollLimit = LocalScrollBottomLimit.current
    // 顶栏高度由 AppScaffold 通过 LocalTopBarInset 下发（含 statusBars）。
    //
    // ## 为什么必须是 contentPadding.top 而不是容器 padding
    //
    // 放进容器 padding 会把内容**永久裁在**顶栏下方，滚动时内容永远到不了顶栏底下；
    // 顶栏的 progressiveTextureBlur 采不到内容，渐变模糊就退化成一块实色。
    // 作为 contentPadding 时，内容初始落在顶栏下方，滚动时能穿过顶栏底面。
    val topInset = LocalTopBarInset.current

    // ## 为什么根容器必须自己铺底色（真机缺陷，2026-10-22 修）
    //
    // 用户报的：「几乎所有控件你都没有正规进行，而且二级页面背景竟然还是透明的」。
    //
    // 「透明」不是错觉。Praxis 的 Scaffold 只有**最外层一份**（`AppScaffold`），
    // 二级页是直接在 `NavDisplay` 的 entry 里渲染的 —— 每个页面根容器
    // （`LazyColumn` / `Column`）默认**不画背景**，空白区域就透出下面那一层。
    //
    // 对照 InstallerX（本项目既定的 UI 正源）：它**每一页都自己套一层
    // `Scaffold`**（`MiuixPreferredPage.kt:209`、`MiuixHomePage.kt:117`、
    // `MiuixHistoryPage.kt:144` …），所以每页天然不透明，转场时也不会
    // 看到下层内容。Praxis 只学了「SmallTitle + Card」的排版，
    // 漏掉了「每页自己铺底」这一条，于是所有二级页都是透的。
    //
    // 修法：在这里铺 `MiuixTheme.colorScheme.surface`。
    //  - 用 surface 而不是 surfaceContainer：与外层 Scaffold 同色阶，
    //    滚动时内容从顶栏下方经过，不会在交界处闪出色差；
    //  - 铺在 `modifier` **之后**：调用方若显式传了背景（极少见），
    //    后应用的那个生效，不会与这里的硬编码打架。
    //
    // ## `fillMaxSize()` 必须在 `modifier` 之前（2026-10-22 修）
    //
    // 之前的写法是 `modifier.fillMaxSize().background(...)`。这看起来无害，
    // 实际会**覆盖调用方的尺寸约束**：Compose 的 modifier 是链式的，
    // 后加的 `fillMaxSize()` 会把父级传来的约束固定成「填满父容器」，
    // 于是 `SettingsList(modifier = Modifier.height(300.dp))` 这类调用
    // 会被静默拉满整屏 —— 表现为「写在卡片里的列表把卡片撑爆 / 撑出屏幕」。
    //
    // 正确顺序是 `fillMaxSize()` 在前：先给一个「最多填满」的默认尺寸，
    // 调用方传进来的 `height` / `padding` 等约束在后面收窄它。
    // 这也是 Compose 官方 example 的顺序（`Modifier.fillMaxSize().then(...)`）。
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .then(modifier)
            .background(MiuixTheme.colorScheme.surface),
        state = state,
        contentPadding = PaddingValues(
            top = if (includeTopInset) topInset else 0.dp,
            bottom = bottomExtra + scrollLimit,
        ),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}