package dev.praxis.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.praxis.ui.design.component.LocalBottomBarInset
import dev.praxis.ui.design.component.PraxisPage
import dev.praxis.ui.navigation.MainScreenBackHandler
import dev.praxis.ui.navigation.MainTab
import dev.praxis.ui.navigation.Navigator
import dev.praxis.ui.navigation.PraxisBottomBar
import dev.praxis.ui.navigation.rememberMainPagerState
import dev.praxis.ui.widgets.sidebarEasedProgress
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Sidebar

/**
 * 底栏本体高度（dp）。
 *
 * 与 `FloatingBottomBar` 内部的 `.height(64.dp)` 一致（见
 * `ui/design/.../glass/FloatingBottomBar.kt`）。两边都是常量而不是测量值：
 * 底栏在四个 Tab 之间高度恒定，测量会引入首帧 0 → 内容跳一下的抖动。
 *
 * 改这里必须同步改 `FloatingBottomBar` 的那个 `.height(64.dp)`，
 * 否则内容限位与实际底栏高度对不上（差多少就压多少）。
 */
private val BottomBarHeight: Dp = 64.dp

/**
 * 底栏与屏幕底部之间的「呼吸」间距。
 *
 * 两处叠加：`PraxisBottomBar` 内部的 `.padding(bottom = 12.dp)` 与这里算限位时
 * 计入的同一份 12dp。它不是装饰 —— 玻璃底栏需要与手势条留出间隙才有浮起感。
 */
private val BottomBarBreath: Dp = 12.dp

/**
 * 抽屉展开时主内容的缩放降幅（1 - 0.05 = 缩到 95%）。
 *
 * 来源：`HomeSidePanel.kt:86` 的 `scale = 1f - 0.05f * eased`。
 *
 * ## 为什么只有 5%
 *
 * 这个值不是「动画幅度」而是「距离感」：抽屉是浮在内容之上的**上一层**，
 * 内容要看起来「退后了一点」。缩太多（>10%）会让内容显得像换了页面，
 * 与「同层浮出」的语义冲突；缩太少（<3%）在视觉上等于没有。
 * 5% 是「能感觉到、但说不出具体变了什么」的临界值。
 */
private const val SIDEBAR_CONTENT_SCALE_DROP: Float = 0.05f

/**
 * 主内容右移距离。
 *
 * 来源：`HomeSidePanel.kt:87` 的 `translationX = 7dp * eased`。
 *
 * ## 为什么是「右移」而不是「跟着抽屉往左让」
 *
 * 抽屉从左边推出来，直觉上内容该往右让 —— 但位移量只有 7dp，
 * 远小于抽屉宽度（84% 屏宽）。所以它不是「让位」，而是**视差**：
 * 内容轻微右偏 + 缩小，视觉上形成「内容被推到后面一层，
 * 而抽屉在更靠近镜头的位置滑入」的深度暗示。
 */
private val SIDEBAR_CONTENT_SHIFT_X: Dp = 7.dp

/**
 * 主内容下移距离。
 *
 * 来源：`HomeSidePanel.kt:88` 的 `translationY = 8dp * eased`。
 *
 * 下移比右移多 1dp，是因为人的眼睛对垂直位移更敏感 —— 相等数值时
 * 水平方向看起来「几乎没动」。8/7 的比例是让两个方向的感知位移一致。
 */
private val SIDEBAR_CONTENT_SHIFT_Y: Dp = 8.dp

/**
 * 主内容四角圆角半径（展开到最大时）。
 *
 * 来源：`HomeSidePanel.kt:1331` 的 `28.dpToPx() * easedProgress`。
 *
 * ## 圆角是「层级感」最有效的一环
 *
 * 直角矩形等于「铺满屏幕的页面」，圆角矩形等于「一张卡片」。抽屉展开时
 * 内容缩到 95% 后四周会露出底色，如果四角仍是直角，那圈露出的底色
 * 看起来像「渲染漏了一块」；起 28dp 圆角后同一个现象被读成
 * 「卡片浮起、后面还有一层」—— 同样的像素，两种完全不同的解释。
 */
private val SIDEBAR_CONTENT_CORNER_RADIUS: Dp = 28.dp

/**
 * `Main` 目的地：底栏 + 四个 Tab 的 Pager。
 *
 * ## 这一层为什么自带 `PraxisPage`（而不是由 `PraxisApp` 包）
 *
 * 底栏要出现在四个 Tab 之上，但**不能**出现在二级页。把 `PraxisPage` 放在
 * 本组件内部，底栏就只属于 `Main` 这一个目的地的组合：
 *
 *  - 进二级页 → `NavDisplay` 不再组合 `Main` → 底栏连同 Scaffold 一起消失，
 *    **不需要** `showBottomBar` 这种判据（旧实现改过两次判据仍有边缘情况）；
 *  - 回 `Main` → 组合重建 → 底栏回来。
 *
 * 这就是「结构保证」与「判据保证」的区别：判据会漏，结构不会。
 *
 * ## 顶栏动作为什么在这里定义
 *
 * 左上角（侧边栏）与右上角（模型切换）只在**对话 Tab** 有意义。
 * 主壳知道当前选中哪个 Tab（`mainPagerState.selectedPage`），
 * 所以在这一层决定画不画最直接 —— 若放到 `PraxisApp`，就必须把
 * Pager 的选中态反向传出去，形成循环依赖。
 */
@Composable
fun MainShellPage(
    navigator: Navigator,
    initialTabIndex: Int,
    onTabSettled: (Int) -> Unit,
    /** 顶栏右侧按钮显示的模型名。 */
    modelName: String,
    /** 左上角：打开会话侧边栏。 */
    onOpenSidebar: () -> Unit,
    /** 右上角：进模型设置。 */
    onOpenModelSettings: () -> Unit,

    /**
     * 侧边栏内容（抽屉，与主内容是兄弟层，见下方的层级说明）。
     *
     * ## 为什么是带参数的 lambda（2026-10-23）
     *
     * 抽屉的开合进度由**本组件**持有（理由见下方 `sidebarProgress` 的注释），
     * 但抽屉的内容（`ChatSidebar`）在 `:app` 层构造。所以把进度作为参数
     * 传给这个槽 —— 抽屉内部需要它来算面板位移与遮罩浓度。
     *
     * 换成 `@Composable () -> Unit` 的话，`:app` 那侧只能自己再 `remember`
     * 一个进度，与这里驱动主内容的那个是两个不同的实例 —— 手指拖到 0.43 时
     * 面板停在 0.43、内容却按另一个值缩放，中间态会错位。
     */
    sidebar: @Composable (Animatable<Float, AnimationVector1D>) -> Unit,
    chatTab: @Composable () -> Unit,
    terminalTab: @Composable () -> Unit,
    memoryTab: @Composable () -> Unit,
    settingsTab: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(
        initialPage = initialTabIndex.coerceIn(0, MainTab.entries.lastIndex),
        pageCount = { MainTab.entries.size },
    )
    val mainPagerState = rememberMainPagerState(pagerState)
    val currentPage = pagerState.currentPage
    val settledPage = pagerState.settledPage
    val selectedTab = MainTab.of(mainPagerState.selectedPage)

    // 手势横滑后同步选中态（isNavigating 期间同步会被拽回去，见 MainPagerState）。
    LaunchedEffect(currentPage) { mainPagerState.syncPage() }

    // 停稳才回写持久化：横滑经过的中间页不该被逐个记录。
    LaunchedEffect(settledPage) { onTabSettled(settledPage) }

    // 返回键第二级：非第一个 Tab 时回到第一个 Tab。
    MainScreenBackHandler(mainPagerState = mainPagerState, navigator = navigator)

    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // ── 底栏高度：**唯一数值来源**（2026-10-23 修，用户第 1/2/3/7 项）──────
    //
    // ## 为什么必须有这一段
    //
    // `LocalBottomBarInset` 此前**全仓没有任何一处 provides**（只有读），
    // 于是 `AppScaffold` 里 `barInset.isSpecified` 恒为 false →
    // `LocalScrollBottomLimit` 恒为 `0.dp` →
    // 所有页面的 `contentPadding.bottom = xxx + scrollLimit` 都少了一整条底栏高度。
    //
    // 表现就是用户报的那几条：
    //  - 聊天/会话列表最后一条永远被玻璃底栏压住（「留位没给聊天栏留」）；
    //  - 设置页滚到底还差一截（「所有页面都应该可以上下滚动」的观感来源）；
    //  - 输入栏贴太下（它自己也吃了这个 0）。
    //
    // 这个值在 git 基线 `63507cb` 里是有的（`PraxisApp.kt:127` 的
    // `CompositionLocalProvider(LocalBottomBarInset provides BottomBarSlot)`），
    // 「每页自带 Scaffold」重构时那一层包裹被删掉，赋值没有搬到新位置。
    //
    // ## 数值怎么算
    //
    // 三部分相加，缺一不可：
    //  1. 底栏本体高度：`FloatingBottomBar` 实高 64dp（见 `FloatingBottomBar.kt`
    //     的 `.height(64.dp)`）。写常量而不是测量值：底栏是浮层、
    //     在同类页面里高度恒定，测量要引入 `onSizeChanged` + 首帧 0 的抖动。
    //  2. 底栏自身的外边距：`PraxisBottomBar` 内部 `.padding(bottom = 12.dp)`
    //     与 `.padding(bottom = navBarBottom)`（见下面 bottomBar 槽）。
    //  3. 手势条：`navBarBottom`。
    //
    // ## 为什么这是「抬升被挡住的控件」而不是「整片内容下移」
    //
    // 这个值只经 `LocalScrollBottomLimit` 进入**滚动容器的 contentPadding.bottom**
    // （见 `AppScaffold` 的「语义纪律」注释），不进内容区的
    // `PaddingValues(bottom =)`。所以内容仍能从玻璃下方穿过（折射成立），
    // 只是「能滚到的最后一屏」被抬高了 —— 正好是「抬升被挡住的那部分」。
    val bottomBarSlot: Dp = BottomBarHeight + BottomBarBreath + navBarBottom

    // 把底栏高度下发给本页的 Scaffold：它是滚动限位的**唯一来源**。
    // 位置必须在 PraxisPage **外层** —— `AppScaffold` 在组合内部读这个值，
    // 同层（写在 PraxisPage 的 content 里）已经晚了，那些页面读到的还是 0。
    //
    // ## 侧边栏进度为什么也在这里持有（2026-10-23，1:1 对齐 WeKit）
    //
    // WeKit 的抽屉开合（`HomeSidePanel.kt:1311` `applyProgress`）不只是把面板
    // 从左边推出来，它同时把**主内容**做四件事：
    //
    // ```
    // eased  = 1 - (1 - p)^1.35
    // scale  = 1 - 0.05 * eased          ← 缩 5%
    // transX = 7dp  * eased              ← 右移
    // transY = 8dp  * eased              ← 下移
    // radius = 28dp * eased              ← 起圆角
    // ```
    //
    // 那四样才是「层级感」的全部来源。没有它们，抽屉就是「贴了块半透明板」。
    //
    // 而它们必须作用在**主内容**上，主内容与抽屉是兄弟层 —— 只有本组件
    // 同时看得见两边。所以进度状态放在这里，一份值同时喂给：
    //  ① `MiuixSidebar`（面板位移 + 遮罩浓度）；
    //  ② 主内容层的 `graphicsLayer`（缩放 / 位移 / 圆角）。
    //
    // ## 为什么不挂在 `AppScaffold` 的 content 上（两个原因，都会做错）
    //
    // 1. 那一层在 `AppScaffold` **内部**，外部拿不到 —— 只能给 `AppScaffold`
    //    加参数，于是**所有页面**的签名都要动；
    // 2. 更要紧：抽屉原来就在 `AppScaffold` 的 `overlay` 槽里。在外面套
    //    `graphicsLayer`，抽屉自己也会被缩放平移 —— 面板跟着内容一起缩小，
    //    直接错了。
    //
    // 所以这里把抽屉从 `overlay` 槽拿出来，改成与 `PraxisPage` **同级**的
    // 兄弟层（下面 `Box` 里的第 ② 项）。
    val sidebarProgress = remember { Animatable(0f) }

    CompositionLocalProvider(LocalBottomBarInset provides bottomBarSlot) {
        Box(modifier = modifier.fillMaxSize()) {

            // ① 主内容层：抽屉开合时被缩放 / 位移 / 起圆角。
            //
            // `Modifier.then(if ...)` 而不是无条件挂 `graphicsLayer`：
            // 关着的时候不该常驻一个 RenderNode。全屏页常驻离屏层
            // 在每个 Tab 页都白给一次合成开销，且会与玻璃采样源互相影响。
            //
            // 判据用 `derivedStateOf`（与 `MiuixSidebar` 内部同一写法）：
            // 直接读 `sidebarProgress.value` 会让本组件在动画**每一帧**重组，
            // 而 derivedStateOf 只在布尔翻转时通知 —— 一次开合只重组两次。
            val contentTransformed by remember {
                derivedStateOf { sidebarProgress.value > 0.001f }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (contentTransformed) {
                            Modifier.graphicsLayer {
                                // 在 draw 阶段算缓动：读 State 只失效绘制，不触发重组。
                                val eased = sidebarEasedProgress(sidebarProgress.value)
                                scaleX = 1f - SIDEBAR_CONTENT_SCALE_DROP * eased
                                scaleY = scaleX
                                // 位移用 dp → px 现算，不写死像素：像素值在不同
                                // density 上差一个数量级（3x 屏 7px 只是 2.3dp）。
                                translationX = SIDEBAR_CONTENT_SHIFT_X.toPx() * eased
                                translationY = SIDEBAR_CONTENT_SHIFT_Y.toPx() * eased
                                // ## 圆角必须走 shape + clip，不能只设 outline
                                //
                                // `clip` 为 false 时 shape 只影响阴影/轮廓绘制，
                                // 内容不会被裁 —— 缩小时四角仍是直角，看不到「卡片化」。
                                // 这也是 `radius * eased` 要夹到 0..1 的原因：
                                // eased 是 0 时半径为 0，clip 开不开都一样，所以
                                // 上面用 `> 0.001f` 门控不会有跳变。
                                clip = true
                                shape = RoundedCornerShape(
                                    SIDEBAR_CONTENT_CORNER_RADIUS *
                                        eased.coerceIn(0f, 1f),
                                )
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                PraxisPage(
                    // 标题跟着 Tab 走：四个 Tab 是四件不同的事，顶栏写死 "Praxis" 等于没信息。
                    title = selectedTab.titleZh(),
                    // 一级页没有返回箭头：它是栈底，退无可退。
                    onBack = null,
                    // `Modifier` 而不是 `modifier`：`modifier` 已经被最外层的
                    // `Box` 消费（padding / size 等由调用方传入的修饰都该作用在
                    // 整个壳上，包括抽屉）。若这里再挂一次，同一个 `padding`
                    // 会被施加两遍 —— 表现为四周留白翻倍。
                    modifier = Modifier,
                    // 左上角只在对话 Tab 出现。其他 Tab 没有「会话」这个概念，
                    // 给了按钮就是死入口。
                    leadingAction = if (selectedTab == MainTab.Chat) {
                        { SidebarButton(onClick = onOpenSidebar) }
                    } else {
                        null
                    },
                    // 右上角同理：模型切换只对对话有意义。
                    actions = if (selectedTab == MainTab.Chat) {
                        { ModelSwitcher(name = modelName, onClick = onOpenModelSettings) }
                    } else {
                        null
                    },
                    // 底栏放独立槽位而不是塞进 content：content 在 layerBackdrop 的录制范围内，
                    // 底栏放进去会被录进采样源 → 玻璃采到自己（边缘出现「玻璃折射玻璃」的重影）。
                    bottomBar = {
                        PraxisBottomBar(
                            selected = selectedTab,
                            onSelect = { tab -> mainPagerState.animateToPage(MainTab.indexOf(tab)) },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = navBarBottom),
                        )
                    },
                    // ## overlay 槽现在留空（2026-10-23）
                    //
                    // 旧实现是 `overlay = { sidebar() }`，靠 `AppScaffold` 保证
                    // 「抽屉在采样源之外、画在底栏之上」。那两条约束仍然成立，
                    // 但**主内容联动**要求抽屉与内容共处一层，且外面不能套
                    // 那个会缩放的 `graphicsLayer`。
                    //
                    // 现在的层级（本组件最外层 `Box`）：
                    //
                    // ```
                    // Box
                    //  ├─ ① Box(graphicsLayer: 缩放/位移/圆角) { PraxisPage(...) }
                    //  └─ ② sidebar()                                ← 兄弟层，后画 = 在上
                    // ```
                    //
                    // 抽屉仍是「全屏层」，所以它依然盖住底栏（① 里的底栏在它下面），
                    // 也因为不在 ① 的 `layerBackdrop` 采样范围内而不污染玻璃。
                    overlay = null,
                ) { _ ->
                    HorizontalPager(
                        state = pagerState,
                        // 允许手势横滑 —— 这是 Pager 相对「栈式切 Tab」最直接的收益。
                        userScrollEnabled = true,
                        // 关掉 Pager 自带的 overscroll 光效：它在页面边缘画一道高光，
                        // 与液态玻璃底栏叠起来很脏。
                        overscrollEffect = null,
                        // 预组合相邻一页：切过去时不出现「先白一帧再渲染」。
                        beyondViewportPageCount = 1,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        when (MainTab.of(page)) {
                            MainTab.Chat -> chatTab()
                            MainTab.Terminal -> terminalTab()
                            MainTab.Memory -> memoryTab()
                            MainTab.Settings -> settingsTab()
                        }
                    }
                }
            }

            // ② 抽屉层：兄弟层，画在最后 → 盖住 ①（含底栏）。
            //
            // 它在 ① 之外，所以不会被 `graphicsLayer` 的缩放/圆角影响；
            // 也因为它自己内部就是 `BoxWithConstraints(fillMaxSize())`，
            // 直接铺在本层即可，不需要额外定位修饰符。
            sidebar(sidebarProgress)
        }
    }
}

/** Tab 的中文标题。穷举无 `else`：新增 Tab 忘了文案会编译失败。 */
private fun MainTab.titleZh(): String = when (this) {
    MainTab.Chat -> "对话"
    MainTab.Terminal -> "终端"
    MainTab.Memory -> "记忆"
    MainTab.Settings -> "设置"
}

/**
 * 左上角的侧边栏按钮。
 *
 * 拆成独立组件而不是内联 lambda：`PraxisPage` 的 `leadingAction` 是
 * `@Composable () -> Unit`，内联写法里读 `MiuixTheme` 会让整个
 * `MainShellPage` 多一片重组范围；独立组件能把重组限制在按钮自身。
 */
@Composable
private fun SidebarButton(onClick: () -> Unit) {
    top.yukonga.miuix.kmp.basic.IconButton(onClick = onClick) {
        top.yukonga.miuix.kmp.basic.Icon(
            imageVector = top.yukonga.miuix.kmp.icon.MiuixIcons.Regular.Sidebar,
            contentDescription = "会话历史",
        )
    }
}

/** 右上角的模型/角色切换按钮。 */
@Composable
private fun ModelSwitcher(name: String, onClick: () -> Unit) {
    dev.praxis.ui.widgets.MiuixCharacterSwitcher(
        name = name,
        onClick = onClick,
    )
}