package dev.praxis.ui.design.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import top.yukonga.miuix.kmp.basic.Scaffold
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
 * 本组件只提供**栏层**（顶栏渐变模糊 + 内容采样源 + inset 下发），
 * 不提供滚动容器：页面各自的滚动容器读 [LocalTopBarInset] 与
 * [LocalScrollBottomLimit] 自行组装。
 *
 * 早期这里还有 `AppListScaffold` / `AppScrollScaffold` / `AppTabScaffold`
 * 三个「带顶栏的滚动骨架」，已删除：它们内部都再套一层 [AppScaffold]，
 * 而顶栏由 `:app` 的 `PraxisApp` 统一提供 —— 套用会出现双顶栏。
 * 保留下只会诱使后来者踩坑，不如删掉。
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
    bottomBar: (@Composable BoxScope.() -> Unit)? = null,
    topBarActions: (@Composable RowScope.() -> Unit)? = null,
    /**
     * 左上角主操作（打开侧边栏）。
     *
     * 与 `onBack` 是**两个不同的槽**，不复用同一个位置：
     * 返回 = 回上一层，抽屉 = 打开面板，语义不同。
     * 两者都给时 `leadingAction` 优先（见 `BlurredTopBar` 的说明）。
     */
    leadingAction: (@Composable () -> Unit)? = null,
    /**
     * 覆盖层（左侧抽屉这类「盖住整页」的面板）。
     *
     * ## 为什么必须单独一个槽，而不能写在 content 里（2026-10-21）
     *
     * 写在 content 里有**两个**问题，且不能靠调顺序同时解决：
     *
     * 1. **被后画的兄弟盖住**。content 里普遍写法是
     *    `Box { ChatSidebar(...); PraxisNavHost(...) }`，同级子项后画者在上，
     *    抽屉写在前面就会被导航内容整片盖掉 —— 表现是「点汉堡没反应」。
     * 2. **被录进玻璃采样源**。content 在 `layerBackdrop(backdrop)` 里面，
     *    抽屉展开后会被 `drawContent()` 录进去，顶栏模糊与底栏玻璃采到的
     *    就是「带抽屉的画面」，抽屉透过玻璃被折射一遍，边缘发灰重影。
     *
     * 放在这里：[overlay] 在采样 Box 之外、底栏之上，
     * 既不会被内容盖住，也不污染采样。
     */
    overlay: (@Composable BoxScope.() -> Unit)? = null,
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
    //
    // ## 2026-10-23：改回 miuix 色（莫奈统一的根因就在这一行）
    //
    // 上面那条「同值」纪律是对的，但**取色引擎取错了**。全项目只有
    // `MiuixTheme.colorScheme` 真正跟着用户的莫奈种子走；M3 那套在
    // `rememberM3Scheme` 里对「自定义种子色」是硬编码蓝（见该函数）。
    //
    // 于是这一行让**页面底色**（Scaffold containerColor + 顶栏模糊打底 +
    // `LocalPageBackdrop` 采样源底面）来自 M3 的蓝系色阶，而全站控件来自
    // miuix 的莫奈色阶 —— 两套色阶不同族，深色下页面像换了一层底。
    //
    // 取 miuix 的 `surface`：它是「页面层」语义，与控件用的 surface 同族，
    // 也和 `PageSurface` / 各页根容器铺的 `MiuixTheme.colorScheme.surface`
    // 完全同值（否则又会回到「顶栏一块、内容一块」）。
    val surfaceColor = MiuixTheme.colorScheme.surface

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
            bottomBar = bottomBar,
            topBarActions = topBarActions,
            leadingAction = leadingAction,
            overlay = overlay,
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
                actions = topBarActions,
                leadingAction = leadingAction,
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
            // 把内容层的采样源下发给浮层（底栏、悬浮按钮）：
            // 浮层自己在页面之上，它自己建的 backdrop 采到的是「它自己」，
            // 折射出来只是一块半透明实色 —— 看着像玻璃，实际没有内容穿过。
            dev.praxis.ui.design.glass.LocalPageBackdrop provides backdrop,
        ) {
            // 外层 Box 不在 layerBackdrop 里：底栏挂在这里，就不会被录进采样源。
            //
            // ## 为什么要分两层（2026-10-20）
            //
            // 底栏是玻璃，它要采样的是**页面内容**。如果底栏和内容在同一个
            // `layerBackdrop(backdrop)` 的 Box 里，`drawContent()` 会把底栏自己也录进去 ——
            // 玻璃就会采到自己（自采样），边缘出现一圈「玻璃折射玻璃」的重影。
            //
            // 内容层单独包一层并挂 layerBackdrop，底栏放在外层：
            // 采样源里只有内容，正是玻璃需要的。
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        // 内容层被记录进 backdrop，供顶栏渐变模糊与底栏玻璃采样。
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
                // 底栏画在内容之上、采样源之外。
                bottomBar?.invoke(this)

                // 覆盖层画在底栏**之上**：抽屉展开时要连底栏一起盖住，
                // 否则底栏会从抽屉下沿漏出一条。
                // 同样在采样源之外，不会被玻璃采到。
                overlay?.invoke(this)
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
    actions: (@Composable RowScope.() -> Unit)?,
    /**
     * 左上角的**主操作**（目前只有「打开侧边栏」用）。
     *
     * ## 为什么要与 onBack 分开而不是复用它
     *
     * 语义完全不同：返回是「回到上一层」，抽屉是「在当前层打开一个面板」。
     * 上一轮已经因为「把返回位复用成进入下一页」被用户指出过 ——
     * 同一个位置承载两种语义，用户永远猜不准按下去会发生什么。
     *
     * 优先级：有 leadingAction 时**不显示**返回键。原因是两者同时出现的场景
     * 不存在（对话首页没有返回键；二级页没有对话框入口），
     * 而同时显示会把标题挤到中间一小条。
     */
    leadingAction: (@Composable () -> Unit)? = null,
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
                    when {
                        leadingAction != null -> leadingAction()
                        onBack != null -> IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Regular.Back,
                                contentDescription = "返回",
                            )
                        }
                        else -> Unit
                    }
                },
                // 右侧动作槽：会话列表这类「明确的操作」放这里，
                // 不再借用返回位（借用会让返回 = 进下一页，语义颠倒）。
                actions = {
                    if (actions != null) actions()
                },
            )
            if (blurSupported) {
                Spacer(Modifier.height(BlurOverhang))
            }
        }
    }
}
