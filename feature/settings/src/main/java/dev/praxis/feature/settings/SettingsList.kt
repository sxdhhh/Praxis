package dev.praxis.feature.settings

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
import dev.praxis.ui.design.component.LocalScrollBottomLimit
import dev.praxis.ui.design.component.LocalTopBarInset

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
     * 外部持有的滚动状态。
     *
     * 日志页需要它：滚顶/滚底按钮必须能读 `canScrollBackward` 并驱动 `scrollToItem`。
     * 默认值用 [rememberLazyListState]，其余页面不用关心。
     */
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
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

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = state,
        contentPadding = PaddingValues(
            top = if (includeTopInset) topInset else 0.dp,
            bottom = bottomExtra + scrollLimit,
        ),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}