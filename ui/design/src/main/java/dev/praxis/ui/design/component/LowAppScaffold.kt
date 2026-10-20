package dev.praxis.ui.design.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.praxis.ui.design.glass.low.LowFrostedTopBar

/**
 * 低版本（API 24–32）的页面骨架 —— [AppScaffold] 的降级实现（无 miuix-blur）。
 *
 * ★★ 2026-10-05（用户：「顶部 scaffold 用毛玻璃不用 blur」）：
 *
 * 顶栏不再走「渐变模糊」链路（那需要背景采样 + 模糊管线），降级为
 * **静态半透明磨砂**（见 [LowFrostedTopBar]）——不采样、不模糊、无下撑段。
 * 本函数也不再录制内容层、不再挂采样泵（整条采样链路已从二级页删除）。
 *
 * | 高版本（AppScaffold） | 低版本（本函数，降级） |
 * |---|---|
 * | `rememberLayerBackdrop { drawRect(surface); drawContent() }` | **无**（不录制） |
 * | `progressiveTextureBlur(...)` 顶栏渐变模糊 | 半透明磨砂覆盖（无模糊） |
 * | `BlurOverhang` 多盖 28dp | 无 |
 */
@Composable
internal fun LowAppScaffold(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier,
    bottomInset: Dp,
    surfaceColor: Color,
    bottomBar: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    // ★★ 2026-10-05（降级）：顶栏改毛玻璃（不采样、不模糊）后，
    //   本函数**不再需要**任何采样源 —— 原来的 `rememberLowGlassBackdrop` +
    //   采样泵 + `lowLayerBackdrop` 录制已全部删除（少一条后台链路）。
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = surfaceColor,
        topBar = {
            // 毛玻璃顶栏（静态半透明磨砂）—— 见 [LowFrostedTopBar] 的对照表。
            LowFrostedTopBar(
                title = title,
                onBack = onBack,
                surfaceColor = surfaceColor,
            )
        },
    ) { innerPadding ->
        val barInset = LocalBottomBarInset.current
        val barExtra = if (barInset != Dp.Unspecified) barInset else 0.dp
        // 顶栏无 overhang 下撑段（毛玻璃是静态覆盖，不再多盖一条渐变带），
        // innerPadding.top 就是顶栏真实高度，直接下发。
        val topBarInset = innerPadding.calculateTopPadding()
        CompositionLocalProvider(
            LocalScrollBottomLimit provides barExtra,
            LocalTopBarInset provides topBarInset,
        ) {
            // 低版本没有采样源，底栏与内容同层即可（底栏用实色，不存在自采样问题）。
            Box(Modifier.fillMaxSize()) {
                content(
                    PaddingValues(
                        top = 0.dp,
                        bottom = innerPadding.calculateBottomPadding() + bottomInset,
                    )
                )
                bottomBar?.invoke(this)
            }
        }
    }
}
