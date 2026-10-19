package dev.praxis.ui.design.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 顶栏渐变模糊**向下额外覆盖**的高度（2026-09-27，待办 7）。
 *
 * ## 为什么需要它
 *
 * 模糊层用 `matchParentSize` 撑满顶栏 Box，所以**模糊带有多高取决于顶栏 Box 多高**。
 * 此前 Box 里只有 `SmallTopAppBar` 自己，模糊层高度 = 顶栏高度（statusBars +
 * miuix `SmallTopAppBar` 中心高 50dp），顶栏下沿一到就立刻从「满强度模糊」跳到
 * 「完全清晰」——「往下覆盖多一点」要抹平的正是这道硬边。
 *
 * 做法：在顶栏下方撑一段本值的 `Spacer`，让 Box 变高、模糊层随之向下延伸，
 * `ProgressiveBlur.Top.copy(curve = 2.2f)` 的渐变带整体拉长，过渡更柔。
 *
 * ## 纪律：这 **不是** 内容下移
 *
 * 顶栏 Box 变高会让 `Scaffold` 的 `innerPadding.top` 跟着变大；若直接拿它当
 * [LocalTopBarInset]，内容会被整体顶下去 `BlurOverhang` —— 那是错的。
 * [AppScaffold] 里**显式减掉**这一段，使内容初始位置与改动前**完全一致**：
 * 内容不动，只是顶部**多被模糊盖住**一段（对齐底部 tab 的局部处理思路）。
 */
private val BlurOverhang = 28.dp

/**
 * 悬浮底栏的**滚动限位**高度。
 *
 * 由 `MainActivity.AppShell` 提供：底栏**显示时**为「底栏高 + 12dp 呼吸 + 手势条」，
 * 二级页（底栏淡出）时为 `0.dp`。
 *
 * ## 语义纪律（2026-09-25 修正 —— 此前实现是错的）
 *
 * 这个值**只用于滚动容器的底部限位**（`LazyColumn.contentPadding.bottom` /
 * Column 底部 spacer），**绝不进内容区的 `PaddingValues(bottom =)`**：
 *
 *  - 底栏是**浮层**（不占布局空间），内容本就该能滑到它**下面** ——
 *    内容从玻璃下方穿过并产生折射，这正是液态玻璃观感成立的前提；
 *  - 若把它加进内容区 padding，等于把整个页面顶上去，底栏下方永远空着，
 *    玻璃没有东西可折射，设计意图完全落空；
 *  - 但滚动**终点**必须抬高它，否则最后一项会停在底栏后面看不见。
 *
 * 用 CompositionLocal 而不是给每个页面加参数：页面不该知道「当前是不是 Tab 根页」，
 * 那是导航层的事实。这样所有页面（现有的与以后新增的）都自动拿到正确限位。
 */
val LocalBottomBarInset = staticCompositionLocalOf { androidx.compose.ui.unit.Dp.Unspecified }

/**
 * 全应用统一页面骨架。
 *
 * 顶栏已切换为 miuix 规范：
 *  - 顶栏本体是 miuix 的 [SmallTopAppBar]（读 `MiuixTheme` 配色 / 字号 / 高度），
 *    不再用 Material3 的 CenterAlignedTopAppBar；
 *  - 顶栏背景是 miuix 的**渐变模糊**（[progressiveTextureBlur]）：贴顶一侧模糊最强、
 *    向下逐渐过渡到清晰，页面内容从模糊层下方滑过时形成柔和渐隐；
 *  - 采样源是本页内容区自己的 [LayerBackdrop]（`rememberLayerBackdrop` + 内容层
 *    `.layerBackdrop(backdrop)`），**只记录内容、不含顶栏自身**，避免自采样；
 *  - 悬浮液态玻璃底栏不参与本文件的任何改动（它是浮层，由 MainActivity 单独叠加）。
 *
 * 留白逻辑保持不变：
 *  - 顶栏自己吃 statusBars inset（[SmallTopAppBar] 的 defaultWindowInsetsPadding）；
 *  - 内容区底部只吃 navigationBars（手势条），不给悬浮玻璃底栏留占位 ——
 *    底栏是浮层，内容可以滑到底部被它遮住，这正是玻璃透明感成立的前提。
 *
 * 两种形态：
 *  - [AppScaffold]：普通滚动内容（Column 由调用方给），适合表单/详情页；
 *  - [AppListScaffold]：LazyColumn 列表页，分组内容用 LazyListScope。
 *
 * @param title 顶栏标题
 * @param onBack 返回键动作；null 表示不显示返回键（主 Tab 根页）
 * @param bottomInset 内容区底部额外留白，默认 24dp；底栏不再需要 96dp 占位
 */
@Composable
fun AppScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 24.dp,
    content: @Composable (PaddingValues) -> Unit,
) {
    // 渐变模糊的采样层：先铺一层**与 Scaffold 容器同色**的打底，再记录页面内容。
    //
    // ## 为什么打底色必须与 containerColor 一致（2026-09-27 修正，待办 8）
    //
    // 此前打底用的是 `MiuixTheme.colorScheme.surface`，而 Scaffold 的
    // `containerColor` 是 `MaterialTheme.colorScheme.surfaceContainer` ——
    // 两者是**不同的色阶**（surface 是卡片层底色，surfaceContainer 是页面层底色）。
    // 顶栏区域正好只采到打底那一层（内容还没滚上去时），于是顶栏看起来是
    // 「另一块背景」，深色主题下尤其像一块黑底。
    //
    // 改成与 containerColor 同值后：顶栏模糊区的底色 == 页面底色，
    // 视觉上只剩「磨砂过渡」，不再有分块感。
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainer

    // ★★ 2026-10-04：**高低版本分流**（miuix-blur 的 minSdk 是 33）。
    //
    // 高版本（API 33+）：原样走 miuix 的 `rememberLayerBackdrop` + `progressiveTextureBlur`
    //   —— 下面这段代码**一字未改**。
    // 低版本（API 24–32）：miuix-blur 加载即崩（NoClassDefFoundError），
    //   且 `progressiveTextureBlur` 在 < 33 上也只会直接 return。
    //   所以换成**对应的低版本实现**：顶栏改用 miuix 官方在 blur 不可用时的
    //   降级路径（实色顶栏，那个分支本来就在 BlurredTopBar 里），
    //   内容层的 backdrop 录制改走 Compose 自带 GraphicsLayer（lowLayerBackdrop）。
    if (dev.praxis.ui.design.glass.GlassBackend.useLowPipeline) {
        LowAppScaffold(
            title = title,
            onBack = onBack,
            modifier = modifier,
            bottomInset = bottomInset,
            surfaceColor = surfaceColor,
            content = content,
        )
        return
    }
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = surfaceColor,
        topBar = {
            BlurredTopBar(
                backdrop = backdrop,
                title = title,
                onBack = onBack,
            )
        },
    ) { innerPadding ->
        // innerPadding 已含顶栏高度（顶栏自带 statusBars）与 navigationBars（底部手势条）。
        //
        // ## 顶栏渐变模糊为何此前只在部分页面生效（2026-09-25 修正）
        //
        // 此前把 `innerPadding`（含顶栏高度）当成**容器 padding** 传给内容区，
        // 页面又普遍写成 `Modifier.padding(pad)` —— 内容被整块下移了一个顶栏高度，
        // 永远到不了顶栏下方。而 backdrop 记录的是内容层，
        // 顶栏区域采到的就只有 `drawRect(surfaceColor)` 那层纯色底 ——
        // 模糊看起来跟实色顶栏没差别，于是「只有内容够长、滚动时顶部恰好有
        // 内容经过的页面」才偶尔看得出模糊。
        //
        // 正确做法（对齐 miuix 官方 example：`Scaffold` topBar 用 BlurredBar，
        // 内容层挂 layerBackdrop，顶栏高度交给**滚动容器**的 contentPadding）：
        // 容器 Box 只吃 top=0，把顶栏高度作为 `LocalTopBarInset` 下发，
        // 由滚动容器（LazyColumn contentPadding / Column 的第一个 spacer）消费。
        // 这样内容滚动时会**经过顶栏下方**，模糊层才真正有东西可采。
        //
        // 同理底栏高度也不进容器 padding（见 [LocalScrollBottomLimit]）。
        val barInset = LocalBottomBarInset.current
        val barExtra = if (barInset.isSpecified) barInset else 0.dp
        // 顶栏 Box 因为 [BlurOverhang] 变高了，Scaffold 的 innerPadding.top 也随之
        // 变大；这里把它减回去，内容初始位置与改动前一致 —— **只有模糊多盖一段，
        // 内容一点不下移**。模糊不可用时顶栏 Box 里没有那段 Spacer，不减。
        val topBarInset = (innerPadding.calculateTopPadding() - if (isRuntimeShaderSupported()) BlurOverhang else 0.dp)
            .coerceAtLeast(0.dp)
        CompositionLocalProvider(
            LocalScrollBottomLimit provides barExtra,
            LocalTopBarInset provides topBarInset,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    // 内容层被记录进 backdrop，供顶栏采样做渐变模糊。
                    .layerBackdrop(backdrop),
            ) {
                content(
                    PaddingValues(
                        // 容器不再吃顶栏高度 —— 交给滚动容器，内容才能滚到顶栏下方。
                        top = 0.dp,
                        bottom = innerPadding.calculateBottomPadding() + bottomInset,
                    )
                )
            }
        }
    }
}

/**
 * 顶栏高度（含 statusBars）。
 *
 * 由 `AppScaffold` 下发，供滚动容器作为**顶部 contentPadding** 消费 ——
 * 目的是让内容的初始位置落在顶栏下方（不被遮），但滚动时能穿过顶栏下方，
 * 使 `progressiveTextureBlur` 采到真实内容形成渐变模糊。
 *
 * 注意与「容器 padding」的区别：放进容器 padding 会把内容**永久裁在**顶栏下方，
 * backdrop 永远采不到东西，模糊等于没有。这是本次修正的核心。
 */
val LocalTopBarInset = staticCompositionLocalOf { 0.dp }

/**
 * 滚动容器应追加的**底部限位**。
 *
 * 由 `AppScaffold` 从 [LocalBottomBarInset] 换算后提供，供 `LazyColumn` 的
 * `contentPadding.bottom` 或 `Column` 的底部 spacer 消费。
 *
 * 与 [LocalBottomBarInset] 的区别（两者数值相同，语义不同）：
 *  - [LocalBottomBarInset] 是「导航层事实」—— 当前是否在 Tab 根页、底栏多高；
 *  - 本值是「页面滚动终点该抬多少」—— 由 scaffold 换算后下发给滚动容器。
 *
 * 分成两个是为了让滚动容器不必自己判断 `isSpecified`，也避免页面直接依赖
 * 导航层的 Local。
 */
val LocalScrollBottomLimit = staticCompositionLocalOf { 0.dp }

/**
 * miuix 渐变模糊顶栏。
 *
 * 结构：Box 内先铺一层 `matchParentSize` 的 [progressiveTextureBlur]（纯背景，无内容），
 * 再把 [SmallTopAppBar] 叠在上面并设 `color = Color.Transparent`，
 * 这样顶栏自身的实心背景不会盖住模糊层。
 *
 * 模糊参数对齐 miuix 官方 example 的 `BlurredBar`：
 *  - `ProgressiveBlur.Top.copy(curve = 2.2f)`：上强下清的渐变，曲线把过渡压向上缘；
 *  - `blurRadius = 10f`：渐进模糊的满强度半径；
 *  - blendColors 用主题 surface 以 30% 透明度提亮，避免深色内容把顶栏压得发灰。
 */
@Composable
private fun BlurredTopBar(
    backdrop: LayerBackdrop,
    title: String,
    onBack: (() -> Unit)?,
) {
    // 运行时 shader 不可用时（理论上 minSdk 33 已支持，此处仅作兜底），
    // 渐变模糊会整体跳过，若顶栏仍设透明会出现标题与内容叠字，
    // 因此降级为主题 surface 实色背景。
    val blurSupported = isRuntimeShaderSupported()
    val colors = BlurDefaults.blurColors(
        blendColors = listOf(
            BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(alpha = 0.30f)),
        ),
    )
    Box {
        if (blurSupported) {
            Box(
                Modifier
                    .matchParentSize()
                    .progressiveTextureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        blurRadius = 10f,
                        gradient = ProgressiveBlur.Top.copy(curve = 2.2f),
                        colors = colors,
                    )
            )
        }
        // 下撑一段 [BlurOverhang]：模糊层是 matchParentSize，Box 变高它才跟着向下多盖一段。
        // 这一段**只在模糊生效时**加；降级成实色顶栏时加它只会白占一条。
        Column {
            SmallTopAppBar(
                title = title,
                modifier = Modifier.fillMaxWidth(),
                // 支持模糊时透明，让下层的渐变模糊透出来；不支持时退回实色。
                color = if (blurSupported) Color.Transparent else MiuixTheme.colorScheme.surface,
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Regular.Back,
                                contentDescription = "返回",
                            )
                        }
                    }
                },
            )
            if (blurSupported) {
                Spacer(Modifier.height(BlurOverhang))
            }
        }
    }
}

/**
 * 列表形态骨架：LazyColumn + 统一 contentPadding。
 * 分组用 [LazyListScope] 直接给，或调用方自行包 SegmentedColumn。
 */
@Composable
fun AppListScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 24.dp,
    content: LazyListScope.() -> Unit,
) {
    AppScaffold(title = title, onBack = onBack, modifier = modifier, bottomInset = bottomInset) { pad ->
        // 顶栏高度作为 contentPadding.top（不是容器 padding）——
        // 内容初始落在顶栏下方，滚动时穿过顶栏下面，模糊层才有内容可采。
        val topInset = LocalTopBarInset.current
        // 滚动终点抬高底栏高度：内容仍可滑到底栏**下方**（玻璃折射成立），
        // 但最后一项能滚到「不被底栏遮挡」的位置。
        val scrollLimit = LocalScrollBottomLimit.current
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = topInset,
                bottom = pad.calculateBottomPadding() + scrollLimit,
            ),
            content = content,
        )
    }
}

/**
 * 滚动形态骨架：`Column` + `verticalScroll`，内容由调用方给。
 *
 * 与 [AppListScaffold] 的区别只是容器（Column 而非 LazyColumn），
 * **滚动限位 / 顶栏 inset 的处理完全一致** —— 这是本次修正的重点：
 *
 * 此前各滚动页面自己写 `Modifier.fillMaxSize().verticalScroll().padding(pad)`，
 * 而 `pad` 含顶栏高度，等于把内容永久裁在顶栏下方，顶栏的
 * `progressiveTextureBlur` 永远采不到内容 → 模糊看着跟实色没区别。
 * 统一用本骨架后，顶栏高度进 Column 的**首个 spacer**，内容滚动时会
 * 经过顶栏下方，所有滚动页面的渐变模糊才真正生效。
 *
 * @param bottomInset 内容底部额外留白
 */
@Composable
fun AppScrollScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 24.dp,
    content: @Composable (PaddingValues) -> Unit,
) {
    AppScaffold(title = title, onBack = onBack, modifier = modifier, bottomInset = bottomInset) { pad ->
        val topInset = LocalTopBarInset.current
        val scrollLimit = LocalScrollBottomLimit.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // 顶栏占位：让内容初始位置在顶栏下方，但它是**滚动内容的一部分**，
            // 不是容器裁剪 —— 内容滚动时会经过顶栏下方，模糊即可采到。
            Spacer(Modifier.height(topInset))
            content(pad)
            Spacer(Modifier.height(pad.calculateBottomPadding() + scrollLimit + bottomInset))
        }
    }
}

/**
 * 主 Tab 根页骨架：有标题但没有返回键（返回由 MainActivity 的 BackHandler 处理）。
 */
@Composable
fun AppTabScaffold(
    title: String,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 24.dp,
    content: @Composable (PaddingValues) -> Unit,
) = AppScaffold(title = title, onBack = null, modifier = modifier, bottomInset = bottomInset, content = content)