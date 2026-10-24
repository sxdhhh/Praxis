package dev.praxis.ui.widgets

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.pow
import kotlinx.coroutines.channels.Channel
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Clear
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.Pin
import top.yukonga.miuix.kmp.icon.extended.Unpin
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 侧边栏（左侧抽屉）。
 *
 * ## 为什么自己写：miuix **没有** Drawer 组件
 *
 * 取证 `/root/repos/miuix` 的 `basic/` 与 `window/`：
 *
 * | 目录 | 内容 |
 * |---|---|
 * | `basic/` | Card / Surface / NavigationBar / NavigationRail / **没有 Drawer** |
 * | `window/` | WindowDialog / WindowBottomSheet / WindowListPopup / **没有 Drawer** |
 *
 * 所以用 miuix 的原子组件（`Surface` / `Text` / `Icon` / `IconButton` / `TextButton`）拼。
 * 视觉与手感参照 suchat 的 `WeKitDrawer.kt`（611 行，1:1 对齐 WeKit 上游
 * `HomeSidePanel.kt`），它同样是 miuix 单引擎、单 Activity + NavDisplay 结构。
 *
 * ## 本次重写修掉的五个真缺陷（2026-10-22）
 *
 * | 缺陷 | 旧实现 | 现在 |
 * |---|---|---|
 * | 状态栏压住标题与关闭键 | `SidebarHeader` 只给 `padding(top = 20.dp)`，而 `MainActivity` 是 `enableEdgeToEdge()` | header 吃 `WindowInsets.statusBars` |
 * | 底部被手势条压住 | `fillMaxHeight()` 但内容区无 `navigationBars` 让位 | 内容区吃 `navigationBars` |
 * | 右滑一碰就关 | 累计 `> 60f`（**裸像素**）直接关 → 3x 屏上等于 20dp | 比例阈值 + 速度投影，见 [SIDEBAR_SETTLE_THRESHOLD] |
 * | 抽屉不跟手 | `AnimatedVisibility(slideIn/slideOut)`，手指与面板无关联 | `Animatable` 进度跟手（`progress -= dx / width`） |
 * | 关闭动画期间吃掉点击 | 遮罩跟着 `AnimatedVisibility` 的 `visible` 拦点击，退出动画 180ms 内仍拦 | 遮罩只在 `open` 为真时 `clickable`，动画期间点得穿 |
 *
 * ## 为什么移除「左缘右滑打开」
 *
 * 左侧抽屉的直觉手势是「从左缘往右划」，那与**系统返回手势完全相同**。
 * suchat 的负一屏能用左滑打开，是因为它从屏幕左侧**向左划**（负一屏在主屏左边），
 * 方向与系统返回相反，所以不冲突。会话抽屉没有这个空间。
 *
 * 打开只有一个入口：顶栏的侧边栏按钮。这不是偷工 —— 强行加一个与返回手势
 * 抢地盘的手势，会让「返回」和「开抽屉」都变得不确定。
 *
 * ## 关闭的三个入口（缺一个用户就会觉得「卡住」）
 *
 * 1. 点遮罩（最直觉）；
 * 2. 面板上右滑（跟手，松手按速度与进度吸附）；
 * 3. 头部关闭键（外接键鼠 / 无障碍场景必需）。
 */
@Composable
fun MiuixSidebar(
    open: Boolean,
    /**
     * 开合进度：0 = 完全收起，1 = 完全展开。
     *
     * ## 为什么由调用方持有（2026-10-23，1:1 对齐 WeKit 的「主内容联动」）
     *
     * 这个值本来是本组件内部 `remember { Animatable(0f) }` 的私有状态。
     * 但 WeKit 的抽屉不是「一块板从左边推出来」——（`HomeSidePanel.kt:1311`
     * `applyProgress`）它同时把**主内容**缩放 5%、右移 7dp、下移 8dp、
     * 起 28dp 圆角，那三样才是「层级感」的来源。
     *
     * 主内容在抽屉的**兄弟层**（同一个 `Box` 里），本组件看不见它；
     * 只能把进度上提给共同的父级（`MainShellPage`），由父级拿着同一个
     * `Animatable` 同时驱动两边 —— 这样手指拖到 0.43 时，面板位移、
     * 遮罩浓淡、主内容缩放三者的中间态天然严格同步，不需要额外对表。
     *
     * ## 为什么传 `Animatable` 而不是 `Float`
     *
     * 本组件要**写**它：手势跟手 `snapTo`、松手 `animateTo`。
     * 若只传 `Float`，写回就得再加一个 `onProgressChange` 回调，
     * 而手势每帧都写 → 每帧回调一次父级重组（正是 `dragChannel` 当初
     * 要避开的那个问题）。
     */
    progress: Animatable<Float, AnimationVector1D>,
    onDismiss: () -> Unit,
    /** 抽屉标题（如「对话历史」）。 */
    title: String,
    /** 新建条目（新建对话）。 */
    onCreate: (() -> Unit)? = null,
    onCreateLabel: String = "新建",
    /** 底部提示文案。 */
    hint: String? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val currentDismiss by rememberUpdatedState(onDismiss)
    // `open` 是参数，若被 `remember { derivedStateOf { ... } }` 直接捕获，
    // 闭包里拿到的是**首次组合**的值 —— 之后 open 变化时 visible 永远不更新，
    // 表现为「点开关抽屉不出现 / 关了再也不开」。必须走 rememberUpdatedState。
    val currentOpen by rememberUpdatedState(open)

    // ── 开合进度由参数传入（见 [progress] 参数 KDoc）─────────────────
    //
    // 本组件仍然用 Animatable 而不是布尔：跟手拖拽需要中间态。旧实现的
    // `AnimatedVisibility` 只有「显示/隐藏」两态，手指与面板之间没有联系，
    // 拖动的观感是「手指在滑，面板自己走自己的动画」。
    //
    // 唯一的差别是**持有者**从本组件挪到了父级（`MainShellPage`），
    // 这样主内容联动（缩放 / 位移 / 圆角）与抽屉位移读的是同一个进度值。
    var dragging by remember { mutableStateOf(false) }

    // 跟手进度的**同步镜像**。
    //
    // `progress.snapTo()` 是挂起函数，手势回调里异步写入后立刻读
    // `progress.value` 会读到上一帧的旧值 —— 累加会丢步、松手判定会滞后。
    // 所以手势计算只读这个与手势同帧推进的镜像值。
    var dragProgress by remember { mutableFloatStateOf(0f) }

    // 镜像 → Animatable 的写入通道（CONFLATED）。
    //
    // 若每个手势事件都 `scope.launch { progress.snapTo() }`，一次拖动会创建
    // 上百个协程去抢 Animatable 的 MutatorMutex，拖拽明显卡顿。
    // 通道只保留最新值、由单个消费者串行写入 —— 零协程分配、零锁竞争。
    val dragChannel = remember { Channel<Float>(Channel.CONFLATED) }
    LaunchedEffect(Unit) {
        for (value in dragChannel) {
            // ## 只在拖拽中写
            //
            // 松手瞬间 `dragging` 归为 false，`LaunchedEffect(open, dragging)`
            // 同时启动 `animateTo` 做收尾。此时通道里可能还压着最后一帧的值，
            // 若不拦，那次 `snapTo` 会把刚起步的收尾动画直接跳到终点 ——
            // 观感是「松手后一瞬间闪一下」。
            if (dragging) progress.snapTo(value)
        }
    }

    // ## 只有这三个条件都假时才完全不渲染
    //
    // 用 `derivedStateOf` 而不是直接读 `progress.value`：直接读会让整个抽屉
    // 在**动画每一帧**都重组一次。derivedStateOf 只在布尔结果翻转时通知，
    // 于是开合动画全程只重组两次（开始渲染 / 停止渲染）。
    val visible by remember {
        derivedStateOf { currentOpen || dragging || progress.value > 0.001f }
    }

    // ── 开合动画（拖拽期间让位）──────────────────────────────────
    //
    // `dragging` 必须在 key 里：松手时它翻成 false，这个 effect 才会重跑，
    // 把「停在 0.3 的中间态」动画到目标值。少了它，松手后面板会僵在半开。
    LaunchedEffect(open, dragging) {
        if (dragging) return@LaunchedEffect
        val target = if (open) 1f else 0f
        val from = progress.value
        if (abs(from - target) < 0.001f) {
            progress.snapTo(target)
            return@LaunchedEffect
        }
        // 时长与距离相关（WeKit 同款 `120 + 120·|Δ|`）：从半开补到全开要快，
        // 从全开收到全关要慢一点，手感才一致。固定时长会让短程动画显得拖沓。
        val duration = (120 + 120 * abs(target - from)).toInt()
        progress.animateTo(target, tween(duration, easing = FastOutSlowInEasing))
    }

    if (!visible) return

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 抽屉宽 = 屏宽 × 0.84，上限 [SIDEBAR_MAX_WIDTH]。
        //
        // 旧实现固定 300dp：在小屏（320dp 宽）上几乎铺满，大屏（折叠屏展开 700dp+）
        // 上只占一小条、右侧大片被遮罩压暗的主页看起来很怪。比例能跟随屏宽。
        val drawerWidth: Dp = minOf(maxWidth * SIDEBAR_WIDTH_FRACTION, SIDEBAR_MAX_WIDTH)
        val drawerWidthPx = with(density) { drawerWidth.toPx() }
        val touchSlop = LocalViewConfiguration.current.touchSlop.toFloat()
        // ## 为什么宽度还要再包一层 rememberUpdatedState
        //
        // 下面的手势是 `pointerInput(Unit)` —— 它的闭包**只在首次组合时创建**。
        // 直接捕获 `drawerWidthPx` 会把这个值冻在首帧：旋转屏幕 / 折叠屏展开后
        // 抽屉实际宽度变了，手势换算（`delta.x / drawerWidthPx`）还在用旧宽度，
        // 表现为「转屏后拖拽进度与实际位移不匹配，甩一点就关」。
        //
        // 这也是不能用 `pointerInput(drawerWidthPx)` 的原因：换 key 会重建识别器，
        // 打断正在进行的拖拽（与本文件开头那条「key 必须是 Unit」同根）。
        val currentDrawerWidthPx by rememberUpdatedState(drawerWidthPx)

        // ── 遮罩 ────────────────────────────────────────────────
        //
        // 用 `background` + `graphicsLayer(alpha)` 而不是 Surface：
        // 这里要的是一层纯色压暗，Surface 自带阴影与内容色语义，当遮罩是误用。
        //
        // ## 为什么 clickable 的判据是 `open` 而不是 `visible`
        //
        // 关闭动画期间 `visible` 仍为真（progress 还没归零）。若按 `visible`
        // 拦点击，那 200ms 里所有点击都会被吃掉 —— 用户点「取消」后立刻点内容区
        // 没反应，得等动画走完。按 `open` 拦，动画期间点得穿。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // alpha 在 draw 阶段读 progress：值变只失效绘制，不触发重组。
                    //
                    // ## 为什么乘的是缓动后的值而不是原始 progress（2026-10-23）
                    //
                    // WeKit 的 `applyProgress`（`HomeSidePanel.kt:1345`）写的是
                    // `dimView.alpha = DIM_MAX_ALPHA * transform.easedProgress`，
                    // 也就是与主内容缩放**同一条缓动曲线**。
                    //
                    // 遮罩若走线性、主内容走 1-(1-p)^1.35，两者在中间段会错开：
                    // p=0.5 时遮罩已黑到 52%、主内容却只缩了 1.7%，
                    // 观感是「压暗先到了、层级还没起来」，像贴了块半透明板。
                    // 同曲线之后两者是同一件事的两个投影。
                    alpha = sidebarEasedProgress(progress.value)
                }
                .background(MiuixTheme.colorScheme.onSurface.copy(alpha = SIDEBAR_DIM_ALPHA))
                .then(
                    if (open && !dragging) {
                        Modifier.clickable(onClick = currentDismiss)
                    } else {
                        Modifier
                    },
                ),
        )

        // ── 面板 ────────────────────────────────────────────────
        //
        // 从左侧滑入：完全收起时整体移到屏幕左外侧。
        // 盖在遮罩之上（后序遍历，后画的在上），所以主页被压暗、面板本身不受影响。
        Surface(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(drawerWidth)
                .graphicsLayer {
                    translationX = -size.width * (1f - progress.value.coerceIn(0f, 1f))
                }
                .pointerInput(Unit) {
                    // ## key 必须是 Unit
                    //
                    // 用 `pointerInput(open)` 会让每次开关都重建手势识别器，
                    // 重建期间正在进行的拖拽被打断 —— 表现为「第一次能拖，
                    // 之后必须重新按一下才有反应」。状态一律用
                    // `rememberUpdatedState` / 镜像值读取。
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var last = down.position
                            var axisDecided = false
                            var horizontal = false
                            dragProgress = progress.value
                            var lastTime = System.currentTimeMillis().toFloat()
                            var velocity = 0f
                            var lastX = down.position.x

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val delta = change.position - last
                                last = change.position

                                if (!axisDecided) {
                                    // ## 方向优先，而不是「累计位移」
                                    //
                                    // 旧实现无脑累加 `delta.x`，而用户纵向滑动会话列表时
                                    // 手指不可能完全垂直，每次轻微右偏都累加 —— 滑一趟长列表
                                    // 累计超过 60px 很常见，于是「想翻会话，抽屉自己关了」。
                                    //
                                    // 正确做法是先判主轴：超过 touchSlop 后比较 x/y 绝对位移，
                                    // 纵向为主就整轮放弃、不 consume，让 LazyColumn 正常滚动。
                                    val dx = abs(change.position.x - down.position.x)
                                    val dy = abs(change.position.y - down.position.y)
                                    if (dx > touchSlop || dy > touchSlop) {
                                        axisDecided = true
                                        // ## 1.15 的余量（2026-10-23 对齐 WeKit）
                                        //
                                        // 旧写法是裸 `dx > dy`：手指斜着 46° 滑也算横向，
                                        // 拖一段长列表时每一点点右偏都会把抽屉拽出来，
                                        // 用户以为「想翻列表却弹出侧边栏」。
                                        //
                                        // WeKit `HomeSidePanelGestureState:10` 用
                                        // `horizontalDominance = 1.15f`，判据是
                                        // `|dy| > |dx| * 1.15 → 放弃`，等价于这里要求
                                        // `dx > dy * 1.15` 才算横向 —— 横向必须**明显**
                                        // 占优才接管，纯对角则让给列表滚动。
                                        horizontal = dx > dy * SIDEBAR_HORIZONTAL_DOMINANCE
                                        if (!horizontal) break
                                        dragging = true
                                        lastX = change.position.x
                                        lastTime = System.currentTimeMillis().toFloat()
                                    }
                                    continue
                                }

                                change.consume()
                                // 进度跟手：右滑 dx>0 → 进度减小 → 收起。
                                dragProgress = (dragProgress - delta.x / currentDrawerWidthPx)
                                    .coerceIn(0f, 1f)
                                dragChannel.trySend(dragProgress)

                                val now = System.currentTimeMillis().toFloat()
                                val dt = (now - lastTime).coerceAtLeast(1f)
                                velocity = (change.position.x - lastX) / dt
                                lastX = change.position.x
                                lastTime = now
                            }

                            if (dragging) {
                                dragging = false
                                // 速度投影：松手瞬间的速度按 160ms 折算成额外位移再判定。
                                // 只按位移判会让「快速甩一下」关不掉（位移很小、速度很大）。
                                //
                                // ## 这里是**减号**，与 suchat 原实现相反（必读）
                                //
                                // WeKit/suchat 写的是 `progress + velocity × 160ms / width`。
                                // 但它的 velocity 来自 `change.position.x - lastX`：
                                // **左滑打开**时 dx < 0 → velocity < 0 → projected 变小 →
                                // 越用力甩越达不到 `>= 0.38` 的打开阈值。也就是说上游那行
                                // 让「甩动」起了**反作用**（只能靠位移硬拖过去）。
                                //
                                // 本组件方向相反：右滑收起时 dx > 0 → velocity > 0，
                                // 要让「甩一下更容易收起」就必须**减**。符号错了会得到
                                // 「越甩越弹回去」，比不做投影更难用。
                                val projected =
                                    dragProgress - velocity * SIDEBAR_VELOCITY_PROJECTION_MS / currentDrawerWidthPx
                                if (projected < SIDEBAR_SETTLE_THRESHOLD) {
                                    currentDismiss()
                                }
                                // 反之：不改 open，`dragging` 翻转已让上面的
                                // LaunchedEffect 重跑并把进度动画回 1。
                            }
                        }
                    }
                },
            color = MiuixTheme.colorScheme.surface,
            // 只在右侧做圆角：面板贴左边缘滑出，左侧圆角会露出屏幕边缘、
            // 在 edge-to-edge + 圆角屏上看到「切角」。
            shape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
        ) {
            SidebarPanel(
                title = title,
                onCreate = onCreate,
                onCreateLabel = onCreateLabel,
                hint = hint,
                onDismiss = currentDismiss,
                content = content,
            )
        }
    }
}

@Composable
private fun SidebarPanel(
    title: String,
    onCreate: (() -> Unit)?,
    onCreateLabel: String,
    hint: String?,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    // ── Insets ──────────────────────────────────────────────────
    //
    // MainActivity 是 `enableEdgeToEdge()`：内容画到屏幕最顶端，状态栏浮在上面。
    // 旧实现 header 只给 `padding(top = 20.dp)`，在状态栏 24~32dp 的设备上
    // 标题与关闭键被状态栏图标压住 —— 这是侧边栏最刺眼的一条。
    //
    // 底部同理：不给 `navigationBars` 让位时，会话列表最后一项落在手势条下面，
    // 点不到也看不到。
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(modifier = Modifier.fillMaxSize()) {
        SidebarHeader(
            title = title,
            onCreate = onCreate,
            onCreateLabel = onCreateLabel,
            onDismiss = onDismiss,
            contentTopPadding = statusBarTop,
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        // 剩余高度全给内容区。
        //
        // 不给权重时，content 里的 LazyColumn 按 wrap-content 量高度，会话一多就
        // 顶出抽屉边界（Surface 是固定高度，超出的部分既不裁剪也不滚动），
        // 表现为「列表下半截看不到、也滑不动」。
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = navBarBottom),
        ) {
            content()
        }
    }
}

/** 抽屉头部：标题 + 新建 + 关闭。 */
@Composable
private fun SidebarHeader(
    title: String,
    onCreate: (() -> Unit)?,
    onCreateLabel: String,
    onDismiss: () -> Unit,
    /** 状态栏高度。调用方从 `WindowInsets.statusBars` 取，本组件不自己读顶层 inset。 */
    contentTopPadding: Dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = contentTopPadding + 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.title3,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        // 关闭键：手势之外必须有一个显式的键。
        // 只看手势的用户（尤其是外接键鼠）会觉得抽屉关不掉。
        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = MiuixIcons.Regular.Clear,
                contentDescription = "关闭侧边栏",
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                modifier = Modifier.size(18.dp),
            )
        }
    }
    if (onCreate != null) {
        TextButton(
            text = onCreateLabel,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            onClick = onCreate,
        )
    }
}

/**
 * 抽屉用的会话项。
 *
 * ## 为什么单列一个组件而不是复用 `MiuixNavigationItemWidget`
 *
 * 那个控件是「设置项」形态（标题 + 描述 + 右箭头），而会话项需要：
 * 置顶图钉、当前选中高亮、以及长按删除的入口。
 * 硬塞进设置项会让那个控件长出一堆只在这里用的参数。
 */
@Composable
fun SidebarSessionRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    pinned: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val container = if (selected) {
        MiuixTheme.colorScheme.primaryContainer
    } else {
        MiuixTheme.colorScheme.surface
    }
    val titleColor = if (selected) {
        MiuixTheme.colorScheme.onPrimaryContainer
    } else {
        MiuixTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            // 点击打开，长按进入删除确认态。
            // 不在 pointerInput 里自己量长按时长：系统长按时长、无障碍长按、
            // 触摸反馈都应该交给 Compose，自己量会与 LazyColumn 的滚动手势打架。
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body2,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 图钉：置顶的显示实心，未置顶的显示空心。
        // 两个图标都常驻（而不是 hover 才出），因为触屏没有 hover 状态。
        IconButton(onClick = onTogglePin, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = if (pinned) MiuixIcons.Regular.Unpin else MiuixIcons.Regular.Pin,
                contentDescription = if (pinned) "取消置顶" else "置顶",
                tint = if (pinned) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 抽屉内的分组标题（「未分组」这类）。 */
@Composable
fun SidebarSectionLabel(text: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            // 折叠指示用 chevron，**不能**借图钉。
            // 同一屏里图钉已经表示「置顶会话」，再用它表示「展开/收起」直接歧义。
            imageVector = if (expanded) {
                MiuixIcons.Regular.ExpandLess
            } else {
                MiuixIcons.Regular.ExpandMore
            },
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(14.dp),
        )
    }
}

/** 空态：没有会话时抽屉里给一句人话，而不是空白。 */
@Composable
fun SidebarEmptyState() {
    Text(
        text = "还没有对话。点上面的按钮开一个新对话。",
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
    )
}

/**
 * 抽屉宽度占屏宽的比例。**照抄 suchat `WeKitDrawer` 的 `0.84f`**
 * （源自 WeKit `HomeSidePanel.kt:1551` 的 `DRAWER_WIDTH_FRACTION`）。
 *
 * 为什么是 84% 而不是「铺满」：右侧必须留一条被遮罩压暗的主页，
 * 用户才能看出「这是浮在内容之上的一层，可以滑走」，而不是「切到了新页面」。
 */
private val SIDEBAR_WIDTH_FRACTION: Float = 0.84f

/** 抽屉宽度上限。平板上 84% 会过宽，胶囊式面板更合适。 */
private val SIDEBAR_MAX_WIDTH: Dp = 400.dp

/**
 * 遮罩最大不透明度。
 *
 * 0.52 是 WeKit 的 `DIM_MAX_ALPHA`。旧实现用 0.32，
 * 主页在这种压暗程度上仍然「抢眼」，抽屉看起来像半透明浮层而不是当前焦点。
 */
private const val SIDEBAR_DIM_ALPHA: Float = 0.52f

/**
 * 方向判定的横向占优倍率。
 *
 * WeKit `HomeSidePanelGestureState:10` 的 `horizontalDominance = 1.15f`。
 * 含义见手势处的注释：横向必须比纵向大 1.15 倍才接管，纯对角让给列表滚动。
 *
 * 为什么不是 1.0：`dx == dy` 时两个方向的可能性完全相等，
 * 此时抢走手势有一半概率是错的，而误抢的代价（把列表滚动变成抽屉拖拽）
 * 比让给列表（用户再滑一下就对了）大得多。
 */
private const val SIDEBAR_HORIZONTAL_DOMINANCE: Float = 1.15f

/**
 * 抽屉开合的**缓动曲线**：`1 - (1 - p)^1.35`。
 *
 * ## 为什么单独抽成公共函数（而不是各处内联）
 *
 * 同一个值现在有两个消费者：
 *  ① 遮罩浓度（本文件）；
 *  ② 主内容的缩放 / 位移 / 圆角（`MainShellPage`，因为主内容是抽屉的兄弟层）。
 *
 * 两处各写一遍 `1f - (1f - p).pow(1.35f)` 是「今天对、明天错」的典型：
 * 将来调曲线时改一处忘一处，表现是「面板和遮罩的节奏对不上」，
 * 而这类不一致在真机上极难定位（看不出来是哪一层错了）。
 *
 * ## 数值来源
 *
 * `HomeSidePanel.kt:83` 的 `eased = 1 - (1-p)^1.35`。
 * 指数 > 1 意味着**前段慢、后段快**：手指刚开始拖时几乎不动，
 * 拖过一半后迅速到位 —— 这是「重物被推动」的手感，比线性更有质感。
 *
 * `pow` 用 Double 重载再转回 Float：`Float.pow(Float)` 在 Kotlin 里
 * 精度更差，而这个值要参与像素级位移计算。
 */
fun sidebarEasedProgress(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    return (1f - (1f - p).toDouble().pow(1.35)).toFloat().coerceIn(0f, 1f)
}

/**
 * 松手吸附阈值（进度比例）。
 *
 * ## 为什么是 0.38（2026-10-23 修正，此前误写 0.5 与 0.62）
 *
 * 两边的 `progress` 语义**完全相同**：0 = 收起、1 = 展开。WeKit 在
 * `HomeSidePanelGestureState:12` 写的是 `projected >= 0.38 → open`，
 * 换成这里的「收起」判据就是 `projected < 0.38 → close` —— 同一个阈值，
 * 只是比较方向相反。
 *
 * 先前两版的错误各占一半：
 *  - `0.5f`：不是任何一边的值，纯粹是「对称看着舒服」的臆造，
 *    结果比 WeKit 更难收起（WeKit 拖掉 62% 就关，这里要拖掉 50%）；
 *  - 若按「方向相反所以要取反」推到 `0.62f`，会让抽屉**更难**被关闭，
 *    与 WeKit 表现恰好相反 —— 因为阈值比的是「还剩下多少」，不是
 *    「放弃了多少」。取反的是**比较符**，不是数值。
 *
 * 现在与上游同值：拖过 38% 的余量就收，配合 160ms 速度投影，
 * 「轻甩一下」也能关掉。
 */
private const val SIDEBAR_SETTLE_THRESHOLD: Float = 0.38f

/**
 * 速度投影窗口（毫秒）。
 *
 * `projected = 进度 + 速度 × 160ms / 宽度`：把「松手瞬间的甩动」折算成
 * 「按这个速度再走 160ms 会到哪」。WeKit 用同一个 160ms。
 * 没有它，快速甩一下（位移小、速度大）会被判成「没拖够」而弹回去。
 */
private const val SIDEBAR_VELOCITY_PROJECTION_MS: Float = 160f