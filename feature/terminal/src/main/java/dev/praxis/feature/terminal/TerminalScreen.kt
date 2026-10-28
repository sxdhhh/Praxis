package dev.praxis.feature.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.praxis.core.terminal.CellStyle
import dev.praxis.core.terminal.TerminalBuffer
import dev.praxis.core.terminal.TerminalSession
import dev.praxis.feature.terminal.keyboard.DefaultTerminalKeyLayout
import dev.praxis.feature.terminal.keyboard.TerminalKeyId
import dev.praxis.feature.terminal.keyboard.TerminalKeyInput
import dev.praxis.feature.terminal.keyboard.TerminalKeyLayout
import dev.praxis.feature.terminal.keyboard.TerminalModifiers
import dev.praxis.feature.terminal.keyboard.TerminalVirtualKeyboard
import dev.praxis.feature.terminal.keyboard.applyTerminalKey
import dev.praxis.ui.design.component.AutoFollowScrollLazy
import dev.praxis.ui.design.component.LocalScrollBottomLimit
import dev.praxis.ui.design.component.LocalTopBarInset
import dev.praxis.ui.design.component.imeLiftPadding
import dev.praxis.ui.design.component.rememberImeLift
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 终端页。
 *
 * ## 为什么用 LazyColumn 逐行渲染，而不是 Canvas 自绘整屏
 *
 * 自绘整屏需要自己管行高、测量、裁剪、命中测试，代码量大且容易在各异于常规的
 * 字体度量上错位；而终端每一行本质上就是一段等宽文本，直接用 Compose 的
 * 文本渲染就能拿到字体回退、行高、选中、无障碍这些能力。
 *
 * 代价是每个格子不能单独设背景色。折中方案：把一段相同背景色的连续格子合成一个
 * span —— 这正是 SGR 输出的天然结构（同色连续字符本来就属于同一 span）。
 *
 * ## 丝滑滚动直接复用 AutoFollowScrollLazy
 *
 * 与聊天页/日志页同一个组件（apixiaoyuan 的弹簧跟随引擎，k=130 / c=24 / m=1）：
 * 到底自动跟随，上滑关跟随，滑回底部再恢复，滚动由每帧弹簧推进而非一次性动画。
 * 不另写一份，否则四处行为会各自漂移。
 *
 * ## 布局照 Operit 终端，控件一律 miuix
 *
 * 从上到下四段（参照 Operit 终端截图）：
 *
 * ```
 * [Local ×] [Local 2 ×] [＋]        <- 会话标签条（横向可滚）
 * --------------------------------
 *   字符画 + 输出                    <- 内容区
 * --------------------------------
 * [Ctrl+C 中断]      [环境配置] [齿轮] <- 操作条
 * $ 输入…                            <- 输入条
 * ```
 *
 * 没有引入 M3 的任何东西：标签条是 `Row(horizontalScroll)` + `clip` + `background`，
 * 操作条是 miuix `IconButton` + 自绘胶囊。Operit 那套用的是 M3 Chip 与
 * `Icons.Rounded.*`，照抄会破坏 miuix 的色阶（scrim / 容器色对不上）。
 */
@Composable
fun TerminalScreen(
    state: TerminalUiState,
    onSubmit: (String) -> Unit,
    onInputChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onAddTab: () -> Unit = {},
    onCloseTab: (String) -> Unit = {},
    onSelectTab: (Int) -> Unit = {},
    onCancel: () -> Unit = {},
    onClear: () -> Unit = {},
    onOpenEnvConfig: (() -> Unit)? = null,
    /**
     * 齿轮：终端自己的设置页（第 12 项缺陷）。
     *
     * 之前齿轮与「环境配置」胶囊共用 [onOpenEnvConfig]，两个按钮跳同一页；
     * 没传回调时齿轮还 fallback 到清屏。现在两者彻底分开：
     * 胶囊 → Linux 环境（容器 / SSH / FRP），齿轮 → 终端设置（字号 / 回滚 / banner）。
     */
    onOpenTerminalSettings: (() -> Unit)? = null,
    /**
     * 终端字号（sp）。由设置页写入、`:app` 读出后传进来。
     *
     * 默认 13sp 与 [TerminalTypography] 的默认值一致：没接线时观感不变。
     */
    fontSizeSp: Float = 13f,
    /**
     * 键盘布局。由设置页写入、`:app` 读出后传进来。
     *
     * 默认 [DefaultTerminalKeyLayout]（2×7，键位照抄 Operit）：
     * 没接线时观感与行为都完整，不会出现「空键盘」。
     */
    keyLayout: TerminalKeyLayout = DefaultTerminalKeyLayout,
    /**
     * 窗口尺寸变化（行列数）。
     *
     * ## 为什么由 UI 算而不是 ViewModel 自己取
     *
     * 行列数取决于「输出区实际占多少像素」与「一个字多宽」——
     * 这两件事只有排版层知道（字号可调、有没有虚拟键盘、底栏占位多少）。
     * ViewModel 拿 `LocalConfiguration.screenHeightDp` 算出来的会是整个屏幕，
     * 而不是终端可视区，`top` 依然会错位。
     *
     * 不接线时（默认空实现）行为不变：管道模式没有窗口概念。
     */
    onResize: (rows: Int, cols: Int) -> Unit = { _, _ -> },
    /**
     * 逐键原始字节（真终端模式）。
     *
     * 返回 true 表示已经由 PTY 消费（此时调用方**不应**再把它当作
     * 「编辑输入框」处理）。返回 false 走原来的本地编辑逻辑。
     *
     * 现阶段只用于 `Ctrl+C` 这类「不经过输入框」的键；普通字符仍由
     * `BasicTextField` 收（中文、系统 IME 才能正常工作）。
     */
    onRawKey: (ByteArray) -> Boolean = { false },
) {
    val topInset = LocalTopBarInset.current
    val scrollLimit = LocalScrollBottomLimit.current
    // ## 列表滚动状态按会话重建（2026-10-24 修）
    //
    // 旧写法是裸 `rememberLazyListState()`：切会话时它被复用，于是
    // 「会话 A 停在 30 行处」的状态会带到只有 8 行的会话 B 上 ——
    // LazyColumn 会把它夹到末尾，表现就是「一切会话就自动到底」。
    //
    // ## 为什么不是 `rememberLazyListState(key)`
    //
    // 那个函数的第一个参数是 `initialFirstVisibleItemIndex: Int`（要 Int），
    // 传会话 id（String）是类型错误。要按 key 重建只能自己 `remember(key)`。
    // 与 `ChatScreen` 里对 `sessionKey` 的处理逐字一致。
    val listState = remember(state.active.id) { LazyListState() }

    // ── 丝滑滚动触发源（2026-10-24 修）──────────────────────────
    //
    // ## `itemCount` 以前恒等于 200，所以它从来没生效过
    //
    // 旧写法只传 `itemCount = state.active.lines.size`。而 `linesOf` 返回的是
    // 「scrollback + 整个屏幕的 rows」，行数**固定**为 200 → 这个值永不变化 →
    // `AutoFollowScrollLazy` 判定「永远没有新内容」→ 真正的输出**不滚**。
    //
    // 裁剪尾部空行之后行数会随输出增长，此时 `itemCount` 已经能反映新增行。
    // 但还差一类：**同一行内续写**（进度条、`read -p` 提示、逐字输出）。
    // 那时行数不变、只有内容变长，必须靠 `contentVersion` 捕捉。
    //
    // ## 为什么版本号是「字符总数」
    //
    // 它是一个随输出单调递增的量，且能覆盖「行数不变但内容变了」的情况。
    // 更精确的做法是「最后一行内容长度」，但那在**中间某行被重写**时
    // （进度条回到行首重画）捕捉不到 —— 而字符总数只看总量，
    // 重画会让总量变化，同样能触发。
    //
    // 代价：字符总数在**清屏**时会变小。变小不会触发滚动（引擎只认「变多」），
    // 正是需要的：清屏后不该自己滚到底。
    val contentVersion = state.active.lines.sumOf { line ->
        // 只数非空格的格子：一行末尾的填充空格不代表「内容」。
        // 不加这个过滤的话，光标所在行永远有 80 个格子，
        // 版本号会跟着光标列位置抖动（用户移动光标也会触发滚动）。
        line.cells.count { it.char != ' ' }
    }
    val resetKey = state.active.id

    AutoFollowScrollLazy(
        lazyState = listState,
        itemCount = state.active.lines.size,
        contentVersion = contentVersion,
        // ## `initialAutoFollow = false`（切页不再自动到底）
        //
        // 旧值 true 的语义是「首次拿到已测量列表就定位到末尾」。
        // 对聊天页/日志页那是正确的（最新内容在底部），但对终端页
        // 会造成用户报的「切到终端页就自己向下滚」—— 他什么都没输入。
        //
        // 终端的阅读习惯与聊天相反：进来先看 banner 与历史开头，
        // 而不是跳到最底下。**有新输出时**才跟随（那才是丝滑滚动的本意）。
        initialAutoFollow = false,
        resetKey = resetKey,
    )

    // ── 虚拟键盘与输入行编辑状态（2026-10-23 新增）──────────────────
    //
    // ## 为什么编辑状态（光标 / 历史游标）在这里而不是 ViewModel
    //
    // 它是**纯 UI 层**的编辑态：光标停在哪、正在翻第几条历史，
    // 与「命令怎么执行」无关。放进 ViewModel 会让 `onInputChange`
    // 的语义从「文本变了」变成「编辑态变了」，而 ViewModel 的
    // `input` 字段类型是 `String` —— 两套模型打架。
    //
    // 这里用 `rememberSaveable` 保住「切走再回来键盘还是展开的」。
    // 但**不跨进程**：杀掉重启输入框本就该是空的、键盘也该收起。
    //
    // 只给「展开/收起」用 `rememberSaveable`，不给输入内容用 ——
    // 输入内容由 ViewModel 持有（见下方 `fieldValue` 的说明），
    // 两处都存会出现「谁是权威」的二义性。
    var keyboardVisible by rememberSaveable { mutableStateOf(false) }
    var modifiers by remember { mutableStateOf(TerminalModifiers()) }

    // ## 输入框受控值的 key 为什么是**会话 id** 而不是文本
    //
    // 这里踩过一个真实的坑，写下来防止改回去：
    //
    // 第一版写的是 `remember(state.active.input) { mutableStateOf(TextFieldValue(...)) }`
    // —— 看起来「文本变了就同步」很合理，实际会**把光标打回行首**：
    //
    // ```
    // 键盘按 "/" → fieldValue 更新（光标在 1）
    //            → onInputChange("/") → VM 的 input 变成 "/"
    //            → remember 的 key 变了 → fieldValue 重建为 TextFieldValue("/")
    //            → TextFieldValue(String) 的 selection 默认是 TextRange(0)
    //            → 光标跳回行首
    // ```
    //
    // 表现是「在输入框末尾按键，字符插到了最前面」。按 `←` 却不触发
    // （文本没变、只有 selection 变），于是这个 bug 只在插入类按键上出现，
    // 极容易被误判成「插入位置算错了」。
    //
    // 换成会话 id 作 key：切会话才重建（正是需要的），同一会话内的编辑
    // 交给 `fieldValue` 自己维护（它是这段编辑的**唯一真源**）。
    var fieldValue by remember(state.active.id) {
        mutableStateOf(TextFieldValue(state.active.input))
    }
    val inputFocusRequester = remember { FocusRequester() }

    // ## 这里曾经有第二次 `AutoFollowScrollLazy` 调用 —— 已删（2026-10-24）
    //
    // 旧代码在本行位置还有一句：
    //
    // ```kotlin
    // AutoFollowScrollLazy(lazyState = listState, itemCount = state.active.lines.size)
    // ```
    //
    // 它与上面那次调用是**同一个组件**，但丢掉了 `contentVersion` 与
    // `resetKey`。两个实例同时挂在同一个 `listState` 上，各自维护一份
    // 跟随状态与弹簧循环 —— 一处判定「该滚」、另一处判定「不该滚」，
    // 每帧互相覆盖 `scrollBy`，表现为滚动速度忽快忽慢、偶发顶回原位。
    //
    // 它看起来像「切会话时补一次跟随」的补丁，但上面那次调用已经带了
    // `resetKey = state.active.id`，正是为这件事准备的。删掉即可。

    // ## 底部避让量：虚拟键盘与输入条共用同一个值（2026-10-24 修）
    //
    // 用户报：「终端的键盘应该也要做抬升」。
    //
    // ## 根因：输入条避让了浮层底栏，虚拟键盘没有
    //
    // 旧实现里 `TerminalInputBar(paddingBottom = scrollLimit)` 让输入条躲开
    // 玻璃底栏，但 `TerminalVirtualKeyboard` 的 modifier 是默认的 ——
    // 键盘打开后它的**最后一行键**正好落在浮层底栏下面，被压住。
    // 截图里 ESC/TAB 那两行卡在 tab 栏后面就是这个。
    //
    // ## 为什么 IME 可见时要变成 0
    //
    // 系统输入法与虚拟键盘是**两段独立高度**，都在 Column 里顺序排：
    //
    // ```
    // [输出区] [输入条] [虚拟键盘] ← Column 底边
    // ```
    //
    // `.imePadding()` 已经把 Column 底边顶到 IME 之上，此时底栏已被键盘
    // 完全盖住。若再留一份 `scrollLimit`，键盘与 IME 之间会空出一条
    // 「看不见的底栏高度」—— 键盘浮在屏幕中间，很难看。
    //
    // 这与聊天页 `effectiveScrollLimit` 的口径完全一致（见 `ChatScreen`），
    // 同一个问题在两处用同一个解法。
    //
    // ## 为什么也做 200ms 动画
    //
    // 同一个理由：`.imePadding()` 是动画的，而这个值若瞬间翻转，
    // 键盘会先跳一下再归位。取与 IME 相同的 200ms，两个量同时收敛。
    //
    // ## 2026-10-24 第四次修：交给 `rememberImeLift`（与 ChatScreen 同一实现）
    //
    // 上面第 1、2、3 次修法的完整教训见 `ui/design/.../component/ImeLift.kt`
    // 的 KDoc，这里只留结论：
    //
    //  - 第 1 次 `if (imeHeight > 0.dp)` 翻转 → 一帧突变；
    //  - 第 2 次给结果套 `animateDpAsState` → 判断的是正在动的量，仍抖；
    //  - 第 3 次改成 `(scrollLimit - imeHeight).coerceAtLeast(0.dp)` 配
    //    原始 `.imePadding()` → 不错位了，但 `WindowInsets.ime` 在真机上是
    //    **阶跃的**（Praxis 没注册 `WindowInsetsAnimationCallback`），
    //    两个量一起跳 → **抬升完全没有动画**。
    //
    // 现在只给「键盘高度」这一个量做动画，`keyboardBottomInset` 是它的
    // 线性补数，所以 `keyboardBottomInset + imeLift == scrollLimit` 恒成立：
    // 既不会中间态错位，又保留了缓动。终端页**不再调 `.imePadding()`**。
    val imeLift = rememberImeLift(scrollLimit)
    val keyboardBottomInset = imeLift.bottomPadding

    // 页面自铺底色（终端页根 Column 原本透明）。
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
            // ## 为什么整个 Column 吃键盘抬升
            //
            // 终端页是 `AppFullscreen` 形态，输入条贴在页面底部。
            // 不处理 IME 时，输入法弹出会**直接盖住输入条**：用户点输入框、
            // 键盘升起、正在打字的那一行看不见了。旧实现整个文件没有一处
            // IME 处理（grep 零命中），这是终端页最影响可用性的一处缺失。
            //
            // 加在 Column 上而不是单独给输入条：列表也要跟着缩短，
            // 否则键盘会盖住最后几行输出，看不到刚执行的结果。
            //
            // ## 虚拟键盘与系统 IME 的叠加关系
            //
            // 虚拟键盘是 Column 里的**一段**（不是浮层），所以它天然把列表挤短；
            // 而 `imeLiftPadding` 处理的是系统输入法。两者同时出现时高度叠加 ——
            // 这正是需要的：虚拟键盘补的是「输入法打不出的键」，字母仍要靠系统 IME。
            .imeLiftPadding(imeLift),
    ) {
        TerminalTabStrip(
            tabs = state.tabs,
            activeIndex = state.activeIndex,
            topInset = topInset,
            onSelect = onSelectTab,
            onClose = onCloseTab,
            onAdd = onAddTab,
        )

        // ## 真实行列数：量输出区，不猜屏幕（2026-10-29）
        //
        // PTY 的窗口尺寸必须是「用户真正看得见的那块区域」：
        //  - 用 `LocalConfiguration.screenHeightDp` 会把状态栏、标签条、
        //    操作条、输入条、虚拟键盘、底栏全算进去 → 多出十几行，
        //    `top` / `vim` 在底部画到屏幕外；
        //  - 用固定 24×80 会在折叠屏、分屏、旋转后全错。
        //
        // 所以量 `LazyColumn` 自己的像素尺寸，再按字体度量换算：
        // 行高 = 字号 × 1.46（与 [TerminalTypography.lineStyle] 同一口径），
        // 字宽 = 字号 × 0.6（等宽字体的 advance 近似值，Res 实测 0.6 偏大一点点，
        // 宁可少算一列也不要多算 —— 多算会让 `ls` 多栏排版后被截断）。
        val density = LocalDensity.current
        var measured by remember { mutableStateOf(IntSize.Zero) }
        LaunchedEffect(measured, fontSizeSp) {
            if (measured.width <= 0 || measured.height <= 0) return@LaunchedEffect
            val lineHeightPx = with(density) {
                (TerminalTypography.lineStyle(fontSizeSp).lineHeight.value * density.density)
            }
            val charWidthPx = with(density) {
                (TerminalTypography.lineStyle(fontSizeSp).fontSize.value * density.density * 0.6f)
            }
            if (lineHeightPx <= 0f || charWidthPx <= 0f) return@LaunchedEffect
            val rows = (measured.height / lineHeightPx).toInt().coerceIn(4, 400)
            val cols = (measured.width / charWidthPx).toInt().coerceIn(20, 300)
            onResize(rows, cols)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { measured = it }
                .background(TerminalPalette.defaultBackground(MiuixTheme.colorScheme.surfaceContainer)),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 10.dp,
                // 操作条与输入条都在 Column 里各占一段，不是浮层，
                // 所以这里不放它们的高度，只留呼吸间距 + 全局底栏限位。
                // 限位用 `keyboardBottomInset` 而不是裸 `scrollLimit`：
                // 键盘弹出时那个值已归零，否则列表底部会多出一条看不见的空白。
                bottom = 10.dp + keyboardBottomInset,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(
                count = state.active.lines.size,
                // ★ 这里为什么给下标 key（2026-10-22 核查后定案）
                //
                // 我最初想给「内容哈希」做 key，核查 `linesOf` 后否掉了：
                // `linesOf` 的行数是**固定**的（`scrollback + buffer.rows`），
                // 格子列表永远等长，行不会「整体前移」。既然位置与内容一一对应，
                // 下标就是这组数据最准确的稳定身份。
                //
                // 反过来，给内容哈希会**制造崩溃**：
                //  - 终端里大量空行、重复的提示行内容完全相同；
                //  - LazyColumn 要求 key 唯一，撞 key 直接抛
                //    `IllegalArgumentException: Key "..." was already used`。
                // 这是真机可复现的崩溃，比它想修的「错行」严重得多。
                //
                // 显式写 `key = { it }` 而不是省略参数：省略时框架内部也是用下标，
                // 但写出来能防止后来者「顺手加个内容 key」把崩溃引回来。
                key = { index -> index },
            ) { index ->
                TerminalRow(
                    line = state.active.lines[index],
                    textStyle = TerminalTypography.lineStyle(fontSizeSp),
                )
            }
        }

        TerminalActionBar(
            running = state.running,
            keyboardVisible = keyboardVisible,
            onToggleKeyboard = { keyboardVisible = !keyboardVisible },
            onCancel = onCancel,
            onClear = onClear,
            onOpenEnvConfig = onOpenEnvConfig,
            onOpenSettings = onOpenTerminalSettings,
        )

        TerminalInputBar(
            value = fieldValue,
            onValueChange = { next ->
                fieldValue = next
                // 只在文本真的变了才通知 ViewModel：
                // 光标移动也会走 `onValueChange`，每次都回调会让 VM 做一次
                // 无意义的 `publish()`（整份状态快照重建 + 全 UI 重组）。
                if (next.text != state.active.input) {
                    onInputChange(next.text)
                }
            },
            onSubmit = { text ->
                // ## 提交前先清编辑态
                //
                // 不清的话 `fieldValue` 还留着刚提交的那句话，而 VM 已经把
                // `input` 置空了 —— `remember(state.active.input)` 会因 key 变化
                // 重建一次（正确），但两件事的先后顺序会造出一帧「输入框里
                // 还显示旧命令、缓冲里已经回显了它」。显式清掉最稳。
                fieldValue = TextFieldValue("")
                modifiers = TerminalModifiers()
                onSubmit(text)
            },
            // ## 底栏占位只让一次：虚拟键盘展开时由**它**让，输入条让 0（2026-10-24 修）
            //
            // 布局顺序是 `[输出区] [操作条] [输入条] [虚拟键盘]`，底栏
            // （`LocalScrollBottomLimit`）的占位**必须由最底下那一段让出**：
            //
            //  - 虚拟键盘收起 → 最后一段是输入条，由输入条让 `scrollLimit`；
            //  - 虚拟键盘展开 → 最后一段是虚拟键盘，由它让，输入条让 0。
            //
            // 修之前两处都在让 `keyboardBottomInset`（= `scrollLimit - imeLift`，
            // 键盘收起时正好等于整个 `scrollLimit`），于是底栏高度被让了两次，
            // 屏幕上就是「输入框和虚拟键盘之间空出一大块黑带」。
            paddingBottom = if (keyboardVisible) 0.dp else keyboardBottomInset,
            textStyle = TerminalTypography.lineStyle(fontSizeSp),
            focusRequester = inputFocusRequester,
        )

        // ── 虚拟键盘 ────────────────────────────────────────────
        //
        // 放在输入条**之后**（下方），与 Operit 一致：键盘从底部升起。
        // 放在 Column 里而不是浮层：它会真的压缩输出区高度，
        // 那样用户能同时看到「键」和「最后几行输出」——
        // 浮层会把输出盖住，正是 Operit 要避免的。
        if (keyboardVisible) {
            TerminalVirtualKeyboard(
                // 抬到底栏之上：这里让的是 `keyboardBottomInset`（= 底栏高度
                // 扣掉键盘占用的部分），与根容器让出的 `imeLift` 是互补量，
                // 两处相加恒等于 `max(键盘高度, 底栏高度)`，不会叠加。
                // 详见 `ImeLift.imeLiftPadding` 的 KDoc。
                modifier = Modifier.padding(bottom = keyboardBottomInset),
                layout = keyLayout,
                modifiers = modifiers,
                onToggleCtrl = { modifiers = modifiers.toggleCtrl() },
                onToggleAlt = { modifiers = modifiers.toggleAlt() },
                onKey = key@{ keyId ->
                    // ## 真终端优先：TAB / ESC 必须发给 shell（2026-10-29）
                    //
                    // 这两个键在管道模式下被本地模拟成「插两个空格」与「清空输入」，
                    // 因为它们没有后端可发。接上 PTY 之后它们有真正的语义：
                    //
                    //  - TAB → 0x09，让 shell 自己补全路径 / 命令；
                    //  - ESC → 0x1B，让 `vim` 退出插入模式、让 `less` 关掉提示。
                    //
                    // 本地模拟若还在，用户按 TAB 会得到两个空格而**看不到补全**，
                    // 按 ESC 会把自己刚打的半条命令清掉 —— 真终端里这两件事都是错的。
                    //
                    // 只在 `onRawKey` 返回 true 时吃掉；管道模式它恒定 false，
                    // 行为与改动前完全一致。
                    val raw: ByteArray? = when (keyId) {
                        TerminalKeyId.Tab -> byteArrayOf(0x09)
                        TerminalKeyId.Escape -> byteArrayOf(0x1B)
                        else -> null
                    }
                    if (raw != null && onRawKey(raw)) {
                        inputFocusRequester.requestFocus()
                        return@key
                    }
                    // ## 按键 → 编辑态的完整链路
                    //
                    // 用 `TerminalKeyInput` 承接光标/历史游标/修饰符，
                    // 因为它同时需要这三样，而 UI 侧只有 `TextFieldValue`（缺历史游标）。
                    //
                    // 每次按键都从当前 `fieldValue` 重建 `TerminalKeyInput`：
                    // 光标取 `fieldValue.selection.end`（用户可能刚点了文本中间）。
                    val before = TerminalKeyInput(
                        text = fieldValue.text,
                        cursor = fieldValue.selection.end,
                        modifiers = modifiers,
                        // ## `historyIndex` 为什么每次从 -1 起
                        //
                        // 它代表「正在浏览历史」的游标，必须跨按键保持。
                        // 但这里没有独立 state 存它 —— 用一个人为约定解决：
                        // 若当前文本恰好等于历史里某一条，就认为在浏览那一条。
                        //
                        // 这个近似在真实使用里成立（历史内容就是那几条命令），
                        // 而不引入额外 state 的好处是：用户手动编辑文本后
                        // 游标会**自动**失效（文本不再等于任何历史项），
                        // 正是想要的语义。
                        historyIndex = historyIndexOf(fieldValue.text, state.active.history),
                        draft = "",
                    )
                    val after = applyTerminalKey(keyId, before, state.active.history)

                    // 写回两处：本地受控值 + ViewModel（提交时要用）。
                    fieldValue = TextFieldValue(
                        text = after.text,
                        selection = TextRange(after.cursor),
                    )
                    modifiers = after.modifiers
                    if (after.text != state.active.input) {
                        onInputChange(after.text)
                    }

                    // ## 按键后把焦点交回输入框
                    //
                    // 键帽是 `clickable`，点它会把焦点从输入框抢走。
                    // 不交回的话：按一次 `←` 之后系统输入法收起，
                    // 用户想接着打字必须先点一下输入框 —— 每按一个键都要多点一次。
                    //
                    // `requestFocus` 是同步的，不需要 `delay`：
                    // 这里的调用发生在点击回调内，焦点系统当帧就会处理。
                    inputFocusRequester.requestFocus()
                },
                // 键帽字号：Operit 传 `fontSize * 0.7f`（见其 `TerminalHome`）。
                // 这里用「终端行样式字号 × 0.7」，与输出区字号联动 ——
                // 用户把终端字号调到 18sp 时键盘也跟着变大，不会出现
                // 「输出很大、键盘很小」的失衡。
                fontSize = (TerminalTypography.lineStyle(fontSizeSp).fontSize.value * 0.7f).sp,
                // 面板内边距基准：Operit 的 `padding * 0.5f`。
                // 8dp 是 Operit 的 `basePadding`，× 0.5 = 4dp。
                spacing = 8.dp * 0.5f,
            )
        }
    }
}

/**
 * 找出「当前文本是历史里的第几条」，没匹配则 -1。
 *
 * ## 为什么用「文本反查」而不是维护一个独立游标
 *
 * 见调用点注释：不引额外 state，用户手动编辑文本后游标自动失效。
 *
 * **从后往前找**（`indexOfLast`）而不是从前往后：历史里可能有重复命令
 * （虽然 `submit` 做了连续去重，但「ls / cd / ls」是合法的）。
 * 用户当前看到的若是那条 `ls`，语义上应当对应**最近**的那次，
 * 这样再按 `↑` 会继续往更旧的方向走，符合直觉。
 */
private fun historyIndexOf(text: String, history: List<String>): Int {
    if (text.isEmpty()) return -1
    return history.indexOfLast { it == text }
}

/**
 * 会话标签条（Operit 终端顶部那一条）。
 *
 * ## 为什么用 `Row(horizontalScroll)` 而不是 miuix `TabRow`
 *
 * `TabRow` 的语义是「一组互斥视图」，它会自己做居中滚动与选中放大；
 * 而这里需要的是**可关闭的标签**（每个带 ×）且数量无上限。
 * 标签被关掉之后 `TabRow` 的选中态还得额外维护一轮，反而更绕。
 */
@Composable
private fun TerminalTabStrip(
    tabs: List<TerminalTab>,
    activeIndex: Int,
    topInset: Dp,
    onSelect: (Int) -> Unit,
    onClose: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 8.dp, end = 4.dp, top = topInset + 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                TerminalTabItem(
                    title = tab.title,
                    selected = index == activeIndex,
                    // 只剩一个会话时不给关：关掉就没有可选会话了，
                    // 与 ViewModel 里 `closeTab` 的保护是同一道约束。
                    closable = tabs.size > 1,
                    onClick = { onSelect(index) },
                    onClose = { onClose(tab.id) },
                )
            }
        }
        IconButton(onClick = onAdd, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = MiuixIcons.Regular.Add,
                contentDescription = "新建会话",
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 单个会话标签。 */
@Composable
private fun TerminalTabItem(
    title: String,
    selected: Boolean,
    closable: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val container = if (selected) MiuixTheme.colorScheme.surface else Color.Transparent
    val contentColor = if (selected) {
        MiuixTheme.colorScheme.onSurface
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(container)
            .clickable(onClick = onClick)
            .padding(
                start = 12.dp,
                end = if (closable) 4.dp else 12.dp,
                top = 6.dp,
                bottom = 6.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.footnote1,
            color = contentColor,
            maxLines = 1,
        )
        if (closable) {
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onClose, modifier = Modifier.size(20.dp)) {
                Icon(
                    imageVector = MiuixIcons.Regular.Close,
                    contentDescription = "关闭会话",
                    tint = contentColor,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

/**
 * 终端操作条：`Ctrl+C 中断` + `清屏` + `环境配置` + `设置`。
 *
 * 对应 Operit 截图里那一行（`Ctrl+C 中断` 在左，`环境配置` / 齿轮在右）。
 *
 * 「环境配置」指向 Linux 环境页（`SettingsEntry.Linux`）；
 * 传 null 时整条不显示该按钮 —— 终端本身不依赖 Linux 环境，是可选入口。
 */
@Composable
private fun TerminalActionBar(
    running: Boolean,
    /** 虚拟键盘是否展开（决定键盘键的高亮态）。 */
    keyboardVisible: Boolean,
    onToggleKeyboard: () -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
    onOpenEnvConfig: (() -> Unit)?,
    /** 齿轮：终端自己的设置页。与 [onOpenEnvConfig] 是两个不同目的地。 */
    onOpenSettings: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TerminalActionChip(label = "Ctrl+C 中断", enabled = running, onClick = onCancel)
        Spacer(Modifier.weight(1f))
        // ── 虚拟键盘开关（2026-10-23 新增，对齐 Operit 的 `⌨` 键）──
        //
        // ## 为什么放在「清屏」左边而不是最右
        //
        // Operit 把 `⌨` 放在输入行右侧而不是工具栏。Praxis 没有单独的
        // 「输入行右侧按钮列」（输入条只有 `$` + 输入框），所以挪到操作条。
        // 位置选在这里的理由：它与「清屏 / 环境配置 / 设置」同属**工具类**动作，
        // 而「Ctrl+C 中断」是**运行控制**——两者之间用 `weight(1f)` 隔开，
        // 视觉分组与语义分组一致。
        //
        // ## 为什么用文字「⌨」而不是 miuix 图标
        //
        // miuix-icons 里没有键盘图标（实测 extended 集合无 Keyboard）。
        // `⌨` 是 Unicode 键盘字符（U+2328），在等宽字体下有稳定字形；
        // 与 Operit 用同一个字符，观感一致。
        TerminalIconChip(
            label = "⌨",
            active = keyboardVisible,
            onClick = onToggleKeyboard,
            description = if (keyboardVisible) "收起虚拟键盘" else "展开虚拟键盘",
        )
        Spacer(Modifier.padding(horizontal = 2.dp))
        IconButton(onClick = onClear, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = MiuixIcons.Regular.Delete,
                contentDescription = "清屏",
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                modifier = Modifier.size(18.dp),
            )
        }
        if (onOpenEnvConfig != null) {
            TerminalActionChip(label = "环境配置", enabled = true, onClick = onOpenEnvConfig)
        }
        // ## 齿轮不能 fallback 到清屏（第 12 项缺陷）
        //
        // 旧写法是 `onClick = onOpenEnvConfig ?: onClear`：
        //  1. 两个按钮指向同一页（齿轮看起来是假按钮）；
        //  2. 没传回调时点齿轮会**清屏** —— 一个叫「设置」的图标做出
        //     破坏性动作，用户点一次就丢光历史，且没有任何撤销。
        //
        // 现在：有回调才画这个按钮，没有就不画。宁缺毋滥 ——
        // 画一个点了清屏的齿轮，比没有齿轮糟糕得多。
        if (onOpenSettings != null) {
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = MiuixIcons.Regular.Settings,
                    contentDescription = "终端设置",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * 操作条上的**方形图标胶囊**（文字型图标）。
 *
 * 与 [TerminalActionChip] 的区别：那个是「文字 + 可禁用」，
 * 这个是「单字符图标 + 可高亮」。分开两个组件而不是塞参数：
 * 两处的 `enabled` / `active` 语义不同（前者是「能不能点」，
 * 后者是「当前是否开启」），合并会让参数组合变成 2×2 的四种含义。
 */
@Composable
private fun TerminalIconChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    /** 无障碍描述：`label` 是单字符（⌨），读屏念不出含义。 */
    description: String,
) {
    val container = if (active) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.surfaceContainerHigh
    }
    val content = if (active) {
        MiuixTheme.colorScheme.onPrimary
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantActions
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(container)
            .clickable(onClickLabel = description, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = content,
            maxLines = 1,
        )
    }
}

/**
 * 操作条上的文字胶囊。
 *
 * `enabled = false` 时仍然**可见**但降透明度：`Ctrl+C` 在没有命令执行时点了没用，
 * 但按钮不能消失 —— 消失会让操作条在运行/空闲之间跳动，用户下次找不到它。
 */
@Composable
private fun TerminalActionChip(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = if (enabled) {
        MiuixTheme.colorScheme.onSecondaryContainer
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (enabled) {
                    MiuixTheme.colorScheme.secondaryContainer
                } else {
                    MiuixTheme.colorScheme.surfaceContainer
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = contentColor,
            maxLines = 1,
        )
    }
}

/** 一行终端输出。 */
@Composable
private fun TerminalRow(line: TerminalLine, textStyle: TextStyle) {
    // 默认前景色在这里**读一次**（Composable 上下文），往下全是纯函数。
    //
    // 不这样传的话，`TerminalPalette.foregroundOf` 就必须是 `@Composable`，
    // 而它被 `remember(line) { buildAnnotatedLine(...) }` 调用 ——
    // `remember` 的 calculation lambda 不是 Composable 上下文，标不上。
    // 详见 `TerminalPalette.defaultBackground` 的 KDoc。
    val defaultColor = MiuixTheme.colorScheme.onSurfaceContainer

    // 行尾空白不渲染：否则每行都会撑满宽度，且复制时带一堆空格。
    val cells = line.cells
    var lastNonBlank = -1
    for (i in cells.indices) if (!cells[i].char.isWhitespace()) lastNonBlank = i

    val visible = if (lastNonBlank >= 0) cells.subList(0, lastNonBlank + 1) else emptyList()

    // ## 为什么要把同款式的格子合并成一段，而不是一格一个 Text
    //
    // 旧实现一格一个 `Text`，两个后果：
    //  1. **样式全丢**：`CellStyle` 里的 bold / italic / underline 三个字段
    //     从头到尾没人读，SGR `1;3;4` 解析出来了却不显示。
    //  2. **性能**：一行 80 格就是 80 个 `Text` 组合，`ls` 一屏几十行会堆出
    //     几千个节点；而且每格单独排版，行内字距/字宽都会与整行排版有微差，
    //     等宽字体看着也就不齐了。
    //
    // 合并成 `AnnotatedString` 后：一段同色同款的连续字符只出一个 span，
    // 整行一次排版，等宽对齐与样式都正确。
    Row(Modifier.fillMaxWidth()) {
        if (visible.isEmpty()) {
            // 空行也要占一行高度（终端里空行是有意义的输出）。
            Text(
                text = " ",
                style = textStyle,
                color = TerminalPalette.defaultForeground(MiuixTheme.colorScheme.onSurfaceContainer),
            )
        } else {
            // ## key 用 `line` 而不是 `visible`（2026-10-24 性能修）
            //
            // `visible` 是 `cells.subList(...)` 新建出来的**视图对象**，每次组合都不同。
            // 用它作 key 时 `remember` 的判等要逐格比较 80 个 `RenderCell` ——
            // 一行 80 次比较，一屏 20 行就是 1600 次，而 `publish()` 在每次按键都会触发。
            //
            // `line` 是 `TerminalLine`（data class）：当 `linesOf` 复用缓存对象时，
            // `remember` 的 `==` 先比引用 —— 同一个引用直接短路，零成本命中。
            // 内容真变了时引用必不同（每行都是新建的），仍会正确重建。
            //
            // ## 为什么不用「行号」作 key
            //
            // 行号在滚屏时不变、内容却整体上移了 —— 用行号会让 `buildAnnotatedLine`
            // 拿到旧内容而不重算，画面变成「上滚后文字停在原位」。
            // ## key 里必须带 `defaultColor`
            //
            // 主题切换（浅色 ↔ 深色、换莫奈取色）时 `line` 本身没变，
            // 但默认前景色变了 —— 只按 `line` 缓存会让这一行保持旧颜色，
            // 直到文本变化才更新。带上 `defaultColor` 才能正确失效。
            //
            // 代价是多一次 `Color.equals` 比较（一个 ULong 比较），可忽略。
            val annotated = remember(line, defaultColor) {
                buildAnnotatedLine(visible, defaultColor)
            }
            Text(
                text = annotated,
                style = textStyle,
            )
        }
    }
}

/**
 * 把一行格子拼成带样式的 [AnnotatedString]。
 *
 * ## 合并规则
 *
 * 连续且**前景色 + 粗体 + 斜体 + 下划线完全相同**的格子合并成一个 span。
 * 背景色不参与合并判据：单元格背景需要单独画底色矩形，`SpanStyle` 只影响
 * 字形不影响背景块，混在一起做会画出参差的色块。背景色目前只在
 * 反显（inverse）时通过前景/背景互换体现，与旧实现一致。
 *
 * ## 为什么宽字符右半格要跳过
 *
 * 宽字符占两格：左半格存真字符、右半格是 `isContinuation` 占位。
 * 若把右半格也拼进去，屏幕上会出现「汉字 + 一个多余空格」，
 * 整行因此比真实宽度长，换行位置全错。
 */
private fun buildAnnotatedLine(cells: List<RenderCell>, defaultColor: Color): AnnotatedString =
    buildAnnotatedString {
        var index = 0
        while (index < cells.size) {
            val cell = cells[index]
            if (cell.isContinuation) {
                index++
                continue
            }
            val key = StyleKey.of(cell.style, defaultColor)
            // 往后找同款式的连续格子（跳过宽字符右半格）。
            val runStart = index
            val text = StringBuilder()
            while (index < cells.size) {
                val next = cells[index]
                if (next.isContinuation) {
                    index++
                    continue
                }
                if (StyleKey.of(next.style, defaultColor) != key) break
                text.append(next.char)
                index++
            }
            if (text.isEmpty()) {
                // 极端情况：连续的单格都被判为不同款式（不该发生）。
                // 仍然推进一步，避免死循环。
                if (index == runStart) index++
                continue
            }
            withStyle(key.toSpanStyle()) {
                append(text.toString())
            }
        }
    }

/**
 * 参与合并判据的样式键。
 *
 * 只含「影响字形而不影响背景块」的四项：颜色、粗体、斜体、下划线。
 * 单独抽成一个 data class 而不是每次比较 `CellStyle`：
 * `CellStyle` 还带 `background` 与 `inverse`，把它们算进判据会让
 * 本该合并的相邻格子被拆散（背景相同的两段字被拆成两个 span，无收益）。
 */
private data class StyleKey(
    val color: Color,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
) {
    fun toSpanStyle(): SpanStyle = SpanStyle(
        color = color,
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = if (underline) TextDecoration.Underline else null,
    )

    companion object {
        /**
         * 把一格样式压成「可比较的样式键」（用于把同款连续格子合并成一个 span）。
         *
         * ## 为什么 `defaultColor` 是参数而不是函数内部读主题
         *
         * `TerminalPalette.foregroundOf` 要读 `MiuixTheme.colorScheme`（默认前景
         * 跟随主题），因此它是 `@Composable`。而这里的调用方
         * `buildAnnotatedLine` 处在 `remember(...) { }` 的 calculation lambda 里
         * —— **那个 lambda 不是 Composable 上下文**，不能调用 Composable。
         *
         * 所以把「默认前景色」在 Composable 里读好、作为参数传下来：
         *  `@Composable` 只在最外层（`TerminalRow`）出现一次，
         *  `buildAnnotatedLine` / `StyleKey` 保持纯函数，`remember` 缓存照常生效。
         *
         * 之前给这几个函数狂加 `@Composable` 是走错了方向 —— 会一路传导到
         * `remember` 的 lambda，那里根本标不上。
         */
        fun of(style: CellStyle, defaultColor: Color): StyleKey = StyleKey(
            color = TerminalPalette.foregroundOf(style, defaultColor),
            bold = style.bold,
            italic = style.italic,
            underline = style.underline,
        )
    }
}

/**
 * 底部输入条。
 *
 * `$` 提示符 + 单行输入。**不用 miuix `TextField`**：
 * 那个控件的圆角容器、label 浮动、内边距是按「表单字段」设计的，
 * 而终端输入条是一条与输出区同宽的裸行；套进去会出现
 * 「输入框比输出区窄一圈、背景色也不连续」的割裂感。
 * `BasicTextField` 只画文本，与上面 `TerminalRow` 的渲染方式完全一致。
 *
 * ## 为什么受控值是 `TextFieldValue` 而不是 `String`（2026-10-23 改）
 *
 * 虚拟键盘的 `←` `→` `HOME` `END` 要改**光标位置**，而 `String` 里根本没有
 * 光标这个概念 —— 只传文本时 `BasicTextField` 每次重组都会把光标收到末尾，
 * 「左移一个字符」在视觉上等于没发生。
 *
 * `TextFieldValue` 带 `selection`，光标才可控。它同时带 `composition`
 * （输入法组合态），这也是**不能用「自己拼 AnnotatedString」替代**的原因：
 * 拼音输入中途的组合下划线由这个字段维护，自绘会直接把中文输入打断。
 *
 * @param focusRequester 键盘按键后要把焦点交回输入框（见调用点说明）。
 */
@Composable
private fun TerminalInputBar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSubmit: (String) -> Unit,
    paddingBottom: Dp,
    textStyle: TextStyle,
    focusRequester: FocusRequester,
) {
    val currentOnSubmit by rememberUpdatedState(onSubmit)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            // 全局底栏（浮层）的占位：输入条要让开底栏，避免被遮住。
            .padding(bottom = paddingBottom),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$",
            style = textStyle,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 8.dp),
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            textStyle = textStyle.copy(
                color = MiuixTheme.colorScheme.onSurface,
            ),
            // 光标色用 primary：与提示符同色，形成「这一行是活动的」的统一暗示。
            // 不指定时会跟随 textStyle 的颜色（可能与背景相近，深色主题下几乎看不见）。
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { currentOnSubmit(value.text) }),
        )
    }
}

/** 一个会话（顶部标签）。 */
data class TerminalTab(
    val id: String,
    val title: String,
    val lines: List<TerminalLine>,
    val input: String,
    /**
     * 本会话的命令历史（旧的在前、新的在后）。
     *
     * ## 为什么它是 UI 状态的一部分而不是 ViewModel 私有字段
     *
     * 虚拟键盘的 `↑` / `↓` / `PGUP` / `PGDN` 要读它。若留在 ViewModel 私有，
     * 键盘就得通过一个新回调去问 ViewModel「上一条是什么」——
     * 那会让键盘组件多一条**异步**路径（问一次、等一下、再改输入框），
     * 而按键反馈必须是同步的（按下去就得立刻看到内容变化）。
     *
     * 放进状态快照后，按键是**纯函数 + 本地状态**，零异步、零延迟。
     * 代价是每次 `publish()` 要多拷一份列表（最多 200 条短字符串），
     * 这个代价远小于「按了方向键要等一下」。
     *
     * 默认空列表：`TerminalTab` 有其它调用点（测试）时不必全部改。
     */
    val history: List<String> = emptyList(),
)

/**
 * 终端页状态。
 *
 * 多会话形态：`tabs` 是标签列表，`activeIndex` 指向当前会话。
 * `running` 是**全局**执行状态（底层一条 shell 通道，见 `TerminalViewModel`）。
 */
data class TerminalUiState(
    val tabs: List<TerminalTab> = emptyList(),
    val activeIndex: Int = 0,
    val running: Boolean = false,
    /**
     * 待执行命令数（不含正在跑的那条）。
     *
     * ## 为什么要暴露到 UI 状态
     *
     * 串行排队后，「敲了但还没跑」是**正常且有代价**的状态：
     * 用户需要知道还有几条在等，否则会以为命令丢了而重复敲。
     * 对齐 Operit 的做法 —— 它在输入条上方显示排队提示。
     *
     * 默认 0：`TerminalUiState` 的其它构造点（测试/预览）不必全改。
     */
    val pendingCount: Int = 0,
) {
    /** 当前活动会话；标签列表为空时给一个空会话，避免 UI 到处判空。 */
    val active: TerminalTab
        get() = tabs.getOrNull(activeIndex)
            ?: TerminalTab(id = "", title = "", lines = emptyList(), input = "")

    companion object {
        /**
         * 从会话快照生成行列表（含历史）。**无缓存版本**，供测试与一次性读取用。
         *
         * 生产路径走 [TerminalViewModel] 里的带缓存版本（`linesOfCached`），
         * 因为这里是纯函数、拿不到跨帧的缓存。
         */
        fun linesOf(session: TerminalSession): List<TerminalLine> =
            linesOf(session, cache = null)

        /**
         * 带**逐行复用**的行列表构建（2026-10-24 新增，终端打字卡顿的修法）。
         *
         * ## 三个必须一起成立的前提
         *
         * 1. **行数只取「已用到」的部分** —— 见下面「为什么必须裁尾部空行」；
         * 2. **没变的行复用上一次的 `TerminalLine` 对象** —— 靠 `rowVersion` 判定；
         * 3. **缓存生命周期与 session 一致** —— 由调用方（`Tab`）持有。
         *
         * 三者缺一就会退化成原来的「每次全量重建」：只做 1 会仍然分配全部行；
         * 只做 2 而没有 1 会缓存 200 行空白（占内存且行数不变 → 滚动仍坏）。
         *
         * ## 为什么必须裁尾部空行
         *
         * `TerminalBuffer` 是**固定 rows 的格子屏**（默认 200 行），
         * 原始行为是「把 200 行全交出去」。这带来三个互相独立又同源的问题：
         *
         * 1. **无限向下滚** —— 屏幕上只有 banner 十来行，列表却有 200 个条目，
         *    用户能把一屏空白一路往下滚。
         * 2. **什么命令都没输也能滚** —— 同上，与是否执行过命令无关。
         * 3. **丝滑滚动彻底失效** —— `AutoFollowScrollLazy` 靠 `itemCount`
         *    变化判断「有新内容」。固定 200 行时它**永远不变**。
         *
         * 真实终端是「内容到哪一行，屏幕就到哪一行」，所以裁到
         * `max(写到的最深行, 光标行)`。
         *
         * @param cache 逐行缓存。null 表示不缓存（测试/一次性读取）。
         *   缓存的数组长度必须等于 `session.buffer.rows`。
         */
        fun linesOf(
            session: TerminalSession,
            cache: Array<VersionedLine?>?,
        ): List<TerminalLine> {
            val buffer = session.buffer
            val scrollbackCount = buffer.scrollbackSize()

            // ── 屏幕区实际用到的行数 ────────────────────────────────
            //
            // 两个都要取：
            //  - `deepestUsedRow`：保证「写过的内容」不被裁掉。ANSI 光标可以
            //    往回跳（`moveTo`），只看光标会把下面写好的行整段裁掉。
            //  - `cursorRow`：保证「光标所在行」一定在列表里。有些输出只移动
            //    光标不写字符（`ESC[5;1H` 之后等待输入），少这一行会让
            //    提示符「凭空出现在上一行」。
            val usedRows = maxOf(buffer.deepestUsedRow, buffer.cursorRow) + 1
            val visibleRows = usedRows.coerceIn(1, buffer.rows)

            val lines = ArrayList<TerminalLine>(scrollbackCount + visibleRows)

            // 历史行是整行搬走的，不涉及宽字符跨行，右半格一律不标记。
            // 它们不进缓存：scrollback 可达上千行，而每行只会被搬进屏幕区一次。
            for (i in 0 until scrollbackCount) {
                val row = buffer.scrollbackLine(i) ?: continue
                lines += TerminalLine(row.map { cell -> RenderCell(cell.char, cell.style, false) })
            }

            for (r in 0 until visibleRows) {
                lines += screenLine(buffer, r, cache)
            }

            return lines
        }

        /**
         * 取一行屏幕快照：缓存命中就复用对象，否则重建并写回。
         *
         * `cache` 的类型是 `Array<VersionedLine?>?` 而不是更宽的 `Array<Any?>?`：
         * Kotlin 的 `Array` **不支持协变**（`Array<VersionedLine?>` 不能当
         * `Array<Any?>` 传），所以这里必须写具体类型。这也顺带省掉一次 `as?` 下行。
         */
        private fun screenLine(
            buffer: TerminalBuffer,
            row: Int,
            cache: Array<VersionedLine?>?,
        ): TerminalLine {
            val version = buffer.rowVersion(row)
            if (cache != null && row in cache.indices) {
                val hit = cache[row]
                // 版本一致 ⇒ 这一行自上次生成后没被改写过 ⇒ 直接复用。
                if (hit != null && hit.version == version) return hit.line
            }

            val line = TerminalLine(
                buffer.line(row).mapIndexed { index, cell ->
                    RenderCell(cell.char, cell.style, buffer.isContinuation(row, index))
                },
            )
            if (cache != null && row in cache.indices) {
                cache[row] = VersionedLine(version, line)
            }
            return line
        }
    }
}

/** 一行的单元格。[cells] 里已经包含空白的格子。 */
data class TerminalLine(val cells: List<RenderCell>)

/**
 * 一行快照 + 生成它时的 [TerminalBuffer.rowVersion]。
 *
 * ## 为什么带版本号
 *
 * 版本号相同 ⇒ 这一行自上次生成后没被改写过 ⇒ 可以直接复用 [line] 对象。
 * 复用对象让下游 `remember(visible)` 的 key 引用相等，跳过
 * `buildAnnotatedLine`（它要把几十个格子合并成 span、建 `AnnotatedString`）。
 *
 * `version` 与 `line` 必须**成对**存放：只存 line 就无法判断「这行后来改过没」，
 * 而只存 version 又没东西可复用。
 */
data class VersionedLine(val version: Int, val line: TerminalLine)

/** 渲染用的格子：把 `Cell` 与「是否是宽字符右半格」合并，避免渲染层再查缓冲。 */
data class RenderCell(val char: Char, val style: CellStyle, val isContinuation: Boolean)

/**
 * 终端配色。
 *
 * ## 2026-10-23：默认底色/前景改为跟随莫奈取色
 *
 * 用户报「终端的背景没有跟着莫奈取色变化」。
 *
 * 旧实现把 `background` / `foreground` 写死成 `Color(0xFF1B1B1F)` /
 * `Color(0xFFE3E3E6)`，注释还写着「不跟随主题的强调色：终端前景色由 ANSI
 * 决定，主题只决定默认色」—— 那条纪律只对 **ANSI 索引色** 成立
 * （`palette` 那 16 色必须保持 xterm 标准值，否则 `ls` 的蓝目录、
 * `git diff` 的红绿会和所有工具文档的截图对不上）。
 *
 * 但**默认底色 / 默认前景**（即 `CellStyle.DEFAULT_COLOR` 那两个）不属于
 * ANSI 语义，它们就是「终端这块画布什么颜色」，理应跟主题走。
 *
 * 所以拆成两类：
 *  - [xtermPalette] / [xterm256]：**不跟随主题**，xterm 标准值，纯函数；
 *  - [defaultBackground] / [defaultForeground]：**跟随主题**，从
 *    `MiuixTheme.colorScheme` 取，由调用方在 composable 里读好传进来。
 *
 * 为什么默认前景不用 `onSurface` 而是 `onSurfaceContainer`：
 * 终端底用的是 `surfaceContainer`（比页面底色深一档，像「一块面板」），
 * 前景就该配它的对应色，否则在深色主题下对比度不够。
 */
object TerminalPalette {
    /**
     * 默认底色（未指定 ANSI 背景色时）。
     *
     * ## 为什么接收参数而不是自己读 `MiuixTheme.colorScheme`
     *
     * `MiuixTheme.colorScheme` 是 `@Composable` 属性。若在这里直接读，
     * 这个函数就必须标 `@Composable`，而 `@Composable` 会**沿调用链向上传导**：
     *
     * ```
     * defaultBackground() ← @Composable
     *   ← backgroundOf()   ← 也得标
     *   ← resolve()        ← 也得标
     *   ← StyleKey.of()    ← 也得标
     *   ← buildAnnotatedLine() ← 也得标
     *   ← remember(line) { ... }  ← ★ 这里不是 Composable 上下文，标不上
     * ```
     *
     * 最后一步决定了「一路加 @Composable」这条路走不通 —— `remember` 的
     * calculation lambda 是普通 lambda，在其中调用 Composable 直接编译失败。
     *
     * 所以改成**由调用方读好传进来**：`@Composable` 只在最外层
     * （`TerminalRow`）出现一次，往下全是纯函数，`remember` 缓存照常生效。
     * 这也是本类 KDoc 一开始就写明的做法（之前实现与文档不一致）。
     */
    fun defaultBackground(defaultColor: Color): Color = defaultColor

    /** [CellStyle.DEFAULT_COLOR] 前景：直接用传入的主题前景色。 */
    fun defaultForeground(defaultColor: Color): Color = defaultColor

    /**
     * 反显时贴的底：与默认前景互换。
     *
     * 反显的语义是「前景变背景」，所以这里返回的就是「默认前景那种颜色」。
     */
    fun inverseBackground(defaultColor: Color): Color = defaultColor

    /**
     * 16 色基础调色板（0-15）。
     *
     * ## 0-7 与 8-15 必须是两组不同的色
     *
     * 旧实现只有这一张 16 色表，`256 色按取模近似` 到它。取模把
     * 16-231 的色立方与 232-255 的灰度**全部压到 16 个颜色上**，
     * 于是 `htop`、`neofetch`、`ls --color` 里的 256 色输出会糊成
     * 一片相近色 —— 用户看到的「终端颜色不对」就是这个。
     *
     * 现在改为两级：
     *  - 0-15 走这张表（暗色 0-7 / 亮色 8-15，与 ANSI 语义一致）；
     *  - 16-255 走 [xterm256]，按 6×6×6 色立方与 24 级灰度真实换算。
     */
    private val palette = listOf(
        // 0-7：标准色（照 xterm 默认，不是自定配色 —— 自定会让
        // `git diff` 的红绿、`ls` 的蓝目录与所有工具文档里的截图对不上）
        Color(0xFF000000), Color(0xFFCD0000), Color(0xFF00CD00), Color(0xFFCDCD00),
        Color(0xFF0000EE), Color(0xFFCD00CD), Color(0xFF00CDCD), Color(0xFFE5E5E5),
        // 8-15：明亮色。与 0-7 是**不同颜色**，不是同一色的高亮版。
        Color(0xFF7F7F7F), Color(0xFFFF0000), Color(0xFF00FF00), Color(0xFFFFFF00),
        Color(0xFF5C5CFF), Color(0xFFFF00FF), Color(0xFF00FFFF), Color(0xFFFFFFFF),
    )

    /**
     * xterm 256 色（16-255）。
     *
     * ## 换算规则（照 xterm 标准，不是近似）
     *
     * - **16-231**：6×6×6 RGB 色立方。`i = n - 16`，
     *   `r = (i / 36) * 51`、`g = ((i % 36) / 6) * 51`、`b = (i % 6) * 51`。
     *   步长 51 = 255/5，因为每轴只有 6 档（0/51/102/153/204/255）。
     * - **232-255**：24 级灰度，`gray = (n - 232) * 10 + 8`
     *   （起点 8 而不是 0：xterm 的灰度从 #080808 起）。
     *
     * 16-231 与 232-255 的取值都超过 16 个，取模会让它们互相撞色，
     * 所以这里必须真实换算，不能取模。
     */
    fun xterm256(index: Int): Color {
        if (index < 16) return palette[index.coerceIn(0, 15)]
        if (index < 232) {
            val i = index - 16
            val r = (i / 36) * 51
            val g = ((i % 36) / 6) * 51
            val b = (i % 6) * 51
            return Color(0xFF000000.toInt() or (r shl 16) or (g shl 8) or b)
        }
        val gray = (index - 232).coerceIn(0, 23) * 10 + 8
        return Color(0xFF000000.toInt() or (gray shl 16) or (gray shl 8) or gray)
    }

    /**
     * 按 [CellStyle] 取前景色，并处理反显与真彩。
     *
     * `defaultColor` 由调用方在 Composable 里读好传入（理由见
     * [defaultBackground] 的 KDoc：`@Composable` 传导会撞上 `remember`）。
     */
    fun foregroundOf(style: CellStyle, defaultColor: Color): Color {
        // 反显：前景与背景互换。默认背景（DEFAULT_COLOR）时用 background 常量，
        // 而不是把 -1 交给 resolve（那会返回 foreground，反显就白做了）。
        if (style.inverse) {
            return if (style.background == CellStyle.DEFAULT_COLOR) inverseBackground(defaultColor)
            else resolve(style.background, defaultColor)
        }
        return resolve(style.foreground, defaultColor)
    }

    /** 按 [CellStyle] 取背景色。`defaultColor` 同上。 */
    fun backgroundOf(style: CellStyle, defaultColor: Color): Color {
        if (style.inverse) return resolve(style.foreground, defaultColor)
        if (style.background == CellStyle.DEFAULT_COLOR) return defaultBackground(defaultColor)
        return resolve(style.background, defaultColor)
    }

    /** 把 ANSI 色号解析成 `Color`。`defaultColor` 同上。 */
    private fun resolve(value: Int, defaultColor: Color): Color {
        if (value == CellStyle.DEFAULT_COLOR) return defaultForeground(defaultColor)
        // 真彩：高位标记 + RGB。
        if (value >= 0x1000000) {
            val rgb = value and 0xFFFFFF
            return Color(0xFF000000.toInt() or rgb)
        }
        return xterm256(value)
    }
}

/** 终端字体：等宽是硬要求，行高另算以免中文被裁。 */
object TerminalTypography {
    /**
     * 按字号生成行样式（第 12 项缺陷：字号要能在设置页调）。
     *
     * 行高按 1.46 倍字号算，而不是固定 19sp：
     * 固定行高在字号调大后会让中文/带发音符号的字符**被裁掉下半截**
     * （Compose 的行高是硬裁剪，不会自动撑开）；
     * 而 1.46 是等宽字体（含 CJK 回退）在安卓上的经验值 ——
     * 13sp→19sp 与改造前完全一致，所以默认观感不变。
     *
     * 用 `coerceAtLeast` 兜住 0/负数：设置页已夹过取值域，
     * 但这里再兜一次，避免别的调用点传入非法值导致整屏文字消失。
     */
    fun lineStyle(fontSizeSp: Float): TextStyle {
        val size = fontSizeSp.coerceAtLeast(1f)
        return TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = size.sp,
            lineHeight = (size * 1.46f).sp,
        )
    }
}