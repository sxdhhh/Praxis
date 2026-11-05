package dev.praxis.feature.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 接线护栏：守住「参数声明了但调用点没传」这类静默失效。
 *
 * ## 为什么需要它（在本目标里尤其需要）
 *
 * 本轮六项修复中有四项（2 思考、3 工具执行中、5 模型分组、6 终端会话同步）
 * 的**真机验证被设备离线挡住**，只能靠单测。而单测覆盖的是 ViewModel 与
 * 存储层，**盖不到「Composable 之间有没有把参数传下去」**。
 *
 * 这一层恰好是我在本会话里**真犯过两次**的错：
 *
 * ```
 *   第一次：modelGroups 加到外层 ChatScreen，但使用处在内层 ChatInputBar
 *           -> Unresolved reference
 *   第二次：改到内层 ChatInputBar，但透传那行在外层 ChatScreen
 *           -> Unresolved reference
 * ```
 *
 * 那两次侥幸被编译器拦住了（Kotlin 里用到了不存在的名字会报错）。
 * 但**更危险的那一半编译器拦不住**：参数在两边都声明了、默认值是空集，
 * 只是**中间那一行漏了** —— 那种情况编译通过、界面正常渲染、
 * 功能静默失效。仓库里 `ModelPill` 就曾这样空转过（见 app 模块
 * `WiringGuardTest` 的说明）。
 *
 * ## 为什么放在 `:feature:chat` 而不是 `:app`
 *
 * `:app` 的测试任务在 CI 上会卡住（另一件独立的事），护栏放在那里
 * 等于没有信号。`:feature:chat` 的测试在当前 CI 上稳定跑完。
 *
 * ## 代价
 *
 * 依赖文本匹配，重命名即失效。这是可接受的：一旦有人改了参数名，
 * 这个测试会变红，提醒他把断言一起改 —— 而不是让缺陷悄悄回来。
 * 与 app 模块那条护栏同一取舍。
 */
class ChatWiringGuardTest {

    /**
     * 从工程根定位源文件。
     *
     * Gradle 跑 `:feature:chat` 的单元测试时工作目录是**模块目录**
     * （`feature/chat`），所以仓库根要往上两级。IDE 有时是仓库根。
     * 多个位置都试，避免环境差异导致假失败。
     */
    private fun source(relative: String): File {
        val candidates = listOf(
            File(relative),
            File("../..", relative),
            File("..", relative),
            File("../../..", relative),
        )
        for (c in candidates) if (c.isFile) return c
        throw AssertionError(
            "找不到源文件: " + relative + "（尝试过 " +
                candidates.joinToString { it.absolutePath } + "）",
        )
    }

    private fun read(relative: String): String = source(relative).readText()

    /** 去掉空白后再匹配：换行与缩进会在重排时变化，不该让它变红。 */
    private fun flat(text: String): String = text.replace(Regex("\\s+"), " ")

    private val chatTab = "app/src/main/java/dev/praxis/ui/tab/ChatTab.kt"
    private val chatScreen = "feature/chat/src/main/java/dev/praxis/feature/chat/ChatScreen.kt"
    private val settingsBar = "feature/chat/src/main/java/dev/praxis/feature/chat/ChatSettingsBar.kt"

    // ── 第 5 项：模型配置分组 ──────────────────────────────────────

    /**
     * `ChatTab` 必须把分组数据与分组点击回调传给 `ChatScreen`。
     *
     * 缺 `modelGroups` -> 模型胶囊退回单组渲染，用户看不到「另一家的模型」，
     * 而这正是本次要修的核心诉求；
     * 缺 `onSelectGroupedModel` -> 点另一家的模型没反应。
     */
    @Test
    fun chatTab_passesModelGroupsToChatScreen() {
        val text = flat(read(chatTab))
        for (name in listOf("modelGroups =", "onSelectGroupedModel =")) {
            assertTrue("ChatTab 未向 ChatScreen 传 $name", text.contains(name))
        }
    }

    /**
     * `ChatScreen`（外层）必须把这两个参数**透传**给内层 `ChatInputBar`。
     *
     * ## 这条正是我踩过的坑
     *
     * `ChatScreen.kt` 里有**两个** Composable：外层 `ChatScreen`（公开入口）
     * 与内层 `ChatInputBar`（私有子组件）。参数必须在**两边都声明**，
     * 且外层调用内层时**透传**。
     *
     * 我之前两次都只做对了其中一处，靠编译器报错才发现。
     * 但如果两边都声明了、只是中间漏了透传，编译器不会报 ——
     * 这条断言就是拦那种情况。
     */
    @Test
    fun chatScreen_forwardsModelGroupsToInputBar() {
        val text = flat(read(chatScreen))
        val declarations = Regex("modelGroups\\s*:\\s*List<ModelGroup>").findAll(text).count()
        assertTrue(
            "modelGroups 应在 ChatScreen 与 ChatInputBar 两处声明，实际 $declarations 处",
            declarations >= 2,
        )
        assertTrue(
            "外层调用内层时没有透传 modelGroups —— 分组数据到不了模型胶囊",
            Regex("modelGroups\\s*=\\s*modelGroups").containsMatchIn(text),
        )
        assertTrue(
            "外层调用内层时没有透传 onSelectGroupedModel",
            Regex("onSelectGroupedModel\\s*=\\s*onSelectGroupedModel").containsMatchIn(text),
        )
    }

    /** `ChatScreen` 渲染 `ModelPill` 时必须把分组数据交进去。 */
    @Test
    fun modelPill_receivesGroups() {
        val text = flat(read(chatScreen))
        assertTrue("ModelPill 没收到 groups —— 分组列表不会显示", text.contains("groups = modelGroups,"))
        assertTrue(
            "ModelPill 没收到 onClickGroupedModel —— 点另一家的模型没反应",
            text.contains("onClickGroupedModel = onSelectGroupedModel,"),
        )
    }

    /** `ModelPill` 内部要真的按分组渲染（而不是收了参数不用）。 */
    @Test
    fun modelPill_rendersGroupsWhenPresent() {
        val text = flat(read(settingsBar))
        assertTrue("ModelPill 没有渲染 groups —— 收了参数却平铺，等于没做", text.contains("groups.forEach"))
        assertTrue("分组渲染缺少「是否当前配置」的高亮判据", text.contains("group.isActive"))
        assertTrue("分组里的模型没有逐项渲染", text.contains("group.models.forEachIndexed"))
    }

    // ── 第 2 项：思考过程 ──────────────────────────────────────────

    /**
     * `ChatScreen` 必须把思考内容交给气泡。
     *
     * 流式期间取 `state.streamingReasoning`（还没定稿），
     * 定稿后取 `bubble.reasoning`（已落库）。两者都要有来源，
     * 否则思考块要么空白、要么重启后消失。
     */
    @Test
    fun chatScreen_passesReasoningToBubble() {
        val text = flat(read(chatScreen))
        assertTrue(
            "没有把 reasoning 传给气泡 —— 思考块永远不会显示",
            Regex("reasoning\\s*=").containsMatchIn(text),
        )
        assertTrue(
            "流式期间没有取 streamingReasoning —— 思考会在生成过程中缺一段",
            text.contains("state.streamingReasoning"),
        )
    }

    /** 气泡组件必须真的有 `reasoning` 参数与折叠块。 */
    @Test
    fun bubble_hasReasoningBlock() {
        val text = flat(
            read("feature/chat/src/main/java/dev/praxis/feature/chat/widget/MessageBubble.kt"),
        )
        assertTrue("气泡没有 reasoning 参数", Regex("reasoning\\s*:\\s*String\\?").containsMatchIn(text))
        assertTrue("气泡没有思考折叠块", text.contains("ReasoningBlock("))
        // ## 默认展开（用户原话：「思考应该默认展开」）
        //
        // 实际写法是 `var expanded by remember { mutableStateOf(true) }`。
        // **不能断言 `expanded = true`** —— 我第一版就是这么写的，
        // 实测源码里没有这个字面量（`expanded` 的赋值出现在别处）。
        // 断言必须对着**真实写法**，否则测试红的是我的想象，不是代码。
        assertTrue(
            "思考块默认应是展开的",
            Regex("var expanded by remember \\{ mutableStateOf\\(true\\) \\}")
                .containsMatchIn(text),
        )
        // ## 流结束自动折叠（用户原话：「思考完后自动折叠」）
        //
        // `expanded = streaming`：流式期间展开、流一停就收起。
        assertTrue(
            "没有把展开状态绑定到 streaming —— 思考完不会自动折叠",
            text.contains("expanded = streaming"),
        )
        // ## 用户手动点过就不再自动收放
        //
        // 不设这道闸，用户展开看完再被自动收起 —— 与他抢控制权。
        assertTrue(
            "缺少「用户手动点过」标记 —— 会与用户抢控制权",
            text.contains("manuallyToggled"),
        )
    }

    // ── 第 3 项：工具「执行中」 ────────────────────────────────────

    /**
     * `ChatScreen` 必须把 `argumentsChars` 交给工具卡。
     *
     * 这是「模型正在写参数」那一段的唯一数据源；不传时卡片在参数收齐前
     * 只能显示工具名，用户看不出它在动。
     */
    @Test
    fun toolCard_receivesArgumentsChars() {
        val text = flat(read(chatScreen))
        assertTrue(
            "没有把 argumentsChars 传给工具卡 —— 「接收参数」状态永远不显示",
            text.contains("argumentsChars ="),
        )
    }

    /** 工具卡要真的用这个字段画出「接收参数」。 */
    @Test
    fun toolCard_rendersArgumentsChars() {
        val text = flat(
            read("feature/chat/src/main/java/dev/praxis/feature/chat/widget/ToolCallCard.kt"),
        )
        assertTrue("工具卡没有 argumentsChars 参数", text.contains("argumentsChars"))
        assertTrue("工具卡没有画「接收参数」状态", text.contains("接收参数"))
    }

    // ── 第 6 项：终端会话同步 ──────────────────────────────────────

    /**
     * `PraxisApp` 必须把持久化的会话名来源接进终端 ViewModel。
     *
     * 不接时 `externalSessionNames` 为 null，终端页只认「当前进程活着的会话」——
     * 而 AI 建的会话在 App 重启后**必然**不在那张内存表里，
     * 于是「终端页看不到智能体建的会话」这个缺陷原样复现。
     */
    @Test
    fun praxisApp_wiresExternalSessionNames() {
        val text = flat(read("app/src/main/java/dev/praxis/ui/PraxisApp.kt"))
        assertTrue(
            "没有把 externalSessionNames 接进 TerminalViewModel —— 重启后 AI 会话消失",
            text.contains("externalSessionNames ="),
        )
        assertTrue(
            "没有注入 TerminalSessionRegistry —— 没有持久化的会话名可用",
            text.contains("terminalSessionRegistry"),
        )
    }

    /**
     * 终端页必须按「本页是否可见」触发同步，而不是 `LaunchedEffect(Unit)`。
     *
     * Pager 设了 `beyondViewportPageCount = 1`（预组合相邻页），
     * `Unit` 会在**还没切过去**时就跑掉，真正切过去又不会重跑。
     */
    @Test
    fun terminalTab_syncsOnVisibility() {
        val shell = flat(read("app/src/main/java/dev/praxis/ui/shell/MainShellPage.kt"))
        assertTrue(
            "MainShellPage 没有把可见性传给 terminalTab",
            shell.contains("terminalTab(currentPage == page)"),
        )
        assertTrue(
            "terminalTab 形参不是布尔可见性",
            Regex("terminalTab\\s*:\\s*@Composable\\s*\\(\\s*Boolean\\s*\\)").containsMatchIn(shell),
        )

        val app = flat(read("app/src/main/java/dev/praxis/ui/PraxisApp.kt"))
        assertTrue(
            "PraxisApp 没有在可见时调用 syncExternalSessions",
            app.contains("terminalViewModel.syncExternalSessions()"),
        )
        assertTrue(
            "同步没有以可见性为 key —— 用 LaunchedEffect(Unit) 会在未切过去时就跑掉",
            app.contains("LaunchedEffect(terminalVisible)"),
        )
    }

    /**
     * 队列面板的三个批量动作必须真的接到 ViewModel。
     *
     * ## 它防的是什么
     *
     * `ChatScreen` 的两个 Composable 都有 `onQueueXxx` 参数且默认 null。
     * 只加参数、忘了在 `ChatTab` 传，编译**不会报错**，界面按钮照常渲染
     * （面板按「回调非空」决定是否画），表现是「点了没反应」。
     *
     * 这正是我犯过两次的同类错误（`modelGroups` 内外层漏传），所以用源码级断言钉住。
     */
    @Test
    fun chatTab_wiresQueueBulkActions() {
        val tab = read("app/src/main/java/dev/praxis/ui/tab/ChatTab.kt")
        for (name in listOf(
            "onQueueReorder =",
            "onQueueSendAll =",
            "onQueueClearAll =",
        )) {
            assertTrue("ChatTab 没有向 ChatScreen 传 $name", tab.contains(name))
        }
    }

    /**
     * 队列面板必须真的把这三个动作画出来，且**动画不能省**。
     *
     * 用户明确要求「一定要有动画」：拖拽时其余条目要让位（animateItemPlacement），
     * 新条目入队要高亮淡出（Animatable）。删掉任何一个都算功能回退。
     */
    @Test
    fun queuePanel_rendersBulkActions() {
        val panel = read("feature/chat/src/main/java/dev/praxis/feature/chat/queue/PendingQueuePanel.kt")
        assertTrue("面板没有 onSendAll 形参", panel.contains("onSendAll"))
        assertTrue("面板没有 onClearAll 形参", panel.contains("onClearAll"))
        assertTrue("面板没有拖拽排序（onReorder）", panel.contains("onReorder"))
        assertTrue("拖拽让位没有动画", panel.contains("animateItemPlacement"))
        assertTrue("入队没有高亮动画", panel.contains("Animatable"))
    }

    /**
     * 发送按钮必须是**四态**且带图标/颜色动画。
     *
     * ## 这条护栏防的是「悄悄退回旧行为」
     *
     * 旧实现生成中把按钮直接变「停止」，用户打完字点它结果是中断生成 ——
     * 界面上显示的动作与实际做的事不一致。把 `Enqueue` 这一态删掉，
     * 编译不会报错、测试也不会红（除非有这条断言）。
     */
    @Test
    fun sendAction_hasFourStatesWithAnimation() {
        val screen = read("feature/chat/src/main/java/dev/praxis/feature/chat/ChatScreen.kt")
        assertTrue("缺少 Enqueue 态", screen.contains("SendActionMode.Enqueue"))
        assertTrue("缺少 Cancel 态", screen.contains("SendActionMode.Cancel"))
        assertTrue("缺少 Disabled 态", screen.contains("SendActionMode.Disabled"))
        // 入队态必须有独立图标（加号），否则用户看不出这次按会排队。
        assertTrue("入队态没有独立图标", screen.contains("MiuixIcons.Regular.Add"))
        // 动画：底色补间 + 图标交叉淡入。
        assertTrue("按钮底色没有补间", screen.contains("animateColorAsState"))
        assertTrue("图标没有交叉淡入", screen.contains("Crossfade"))
    }

    /**
     * `onSend` 与 `onEnqueue` 必须是两条独立通路。
     *
     * 旧写法 `onSend = onSendOrEnqueue ?: onSend` 让按钮永远画成「发送」，
     * 由 ViewModel 内部判 busy 再决定发还是排队 —— 那正是问题的根源。
     */
    @Test
    fun chatInputBar_separatesSendAndEnqueue() {
        val screen = read("feature/chat/src/main/java/dev/praxis/feature/chat/ChatScreen.kt")
        assertTrue(
            "onSend 又被 onSendOrEnqueue 覆盖了",
            !screen.contains("onSend = onSendOrEnqueue ?: onSend"),
        )
        assertTrue("ChatInputBar 没有 onEnqueue 形参", screen.contains("onEnqueue: (() -> Unit)? = null"))
    }

    /**
     * `/` 命令必须真的接通：解析 → 候选面板 → 执行 → 不进模型历史。
     *
     * ## 这条护栏防的是什么
     *
     * 命令功能最容易「看起来做了但没通」：面板能弹、点了没反应，
     * 或者打了 `/compact` 回车后把它当普通消息发给模型。
     */
    @Test
    fun slashCommand_isFullyWired() {
        val screen = read("feature/chat/src/main/java/dev/praxis/feature/chat/ChatScreen.kt")
        val vm = read("feature/chat/src/main/java/dev/praxis/feature/chat/ChatViewModel.kt")
        val tab = read("app/src/main/java/dev/praxis/ui/tab/ChatTab.kt")
        assertTrue("屏幕没有渲染命令面板", screen.contains("SlashCommandPanel("))
        assertTrue("ChatTab 没有接 onCommand", tab.contains("onCommand = viewModel::runCommand"))
        assertTrue("send 没有拦截命令", vm.contains("interceptCommand(_state.value.input)"))
        // 命令结果只进 notice，绝不进 history。
        assertTrue(
            "runCommand 里出现了向 history 写入的调用（命令不该进模型历史）",
            !vm.substringAfter("fun runCommand").substringBefore("fun interceptCommand")
                .contains("history.add"),
        )
    }

    /** 命令候选面板必须有展开动画（不能硬切，否则输入区高度突变）。 */
    @Test
    fun slashCommandPanel_isAnimated() {
        val panel = read("feature/chat/src/main/java/dev/praxis/feature/chat/command/SlashCommandPanel.kt")
        assertTrue("命令面板没有出现动画", panel.contains("AnimatedVisibility"))
        assertTrue("命令面板没有高度展开动画", panel.contains("expandVertically"))
        assertTrue("命令面板没有淡入淡出", panel.contains("fadeIn"))
    }

    /**
     * 技能双开关必须真的**生效**在注入路径上。
     *
     * ## 这条护栏防的是什么
     *
     * 解析出开关但没在 `composePrompt` 里过滤，是最容易犯的错 ——
     * 用户写了「不要让 AI 自己用」，界面看不出问题，模型却照样拿到它。
     * 编译不报错、单测（如果只测解析）也不会红。
     */
    @Test
    fun skillSwitches_areEnforcedOnInjection() {
        val registry = read("core/plugin/src/main/kotlin/dev/praxis/core/plugin/SkillRegistry.kt")
        assertTrue(
            "composePrompt 没有走 modelVisible 过滤",
            registry.contains("val selected = modelVisible(enabledNames)"),
        )
        assertTrue("缺少 modelInvocable 过滤", registry.contains("it.modelInvocable"))
        assertTrue("缺少 userInvocable 过滤", registry.contains("it.userInvocable"))
    }

    /** `/技能名` 必须接到注册表，且注入是**一次性**的。 */
    @Test
    fun skillInvocation_isWiredAndOneShot() {
        val vm = read("feature/chat/src/main/java/dev/praxis/feature/chat/ChatViewModel.kt")
        val app = read("app/src/main/java/dev/praxis/ui/PraxisApp.kt")
        assertTrue("ChatTab/PraxisApp 没有接 skillLookup", app.contains("skillLookup ="))
        assertTrue("ViewModel 没有技能分支", vm.contains("pendingSkillInjection = skill.body"))
        // 一次性：拼装后必须清空，否则每轮都在烧上下文预算。
        assertTrue("技能注入后没有清空（会每轮重复注入）", vm.contains("pendingSkillInjection = null"))
    }

    /**
     * FTP 必须走**进程内服务**，而不是在容器里起 vsftpd。
     *
     * ## 这条护栏防的是「不知不觉退回旧方案」
     *
     * 真机实测：容器里 `apt-get install vsftpd` 会在 dpkg 的 maintainer
     * script 阶段失败（`update-rc.d: Permission denied`，而文件权限是
     * `rwxr-xr-x` → Android SELinux 禁止从 app 数据目录执行脚本）。
     * vsftpd 依赖 procps，两者一起停在 unconfigured，二进制根本没落地。
     *
     * 若有人把实现换回「容器里装 vsftpd」，编译不会报错、单测也不会红 ——
     * 只有真机点「启用 FTP」才会发现起不来。所以在这里钉住。
     */
    @Test
    fun ftp_runsInProcessNotInContainer() {
        val port = read("app/src/main/java/dev/praxis/data/linux/ProotEnvPort.kt")
        val server = read("app/src/main/java/dev/praxis/data/linux/PraxisFtpServer.kt")
        assertTrue("端口层没有接进程内 FTP 服务", port.contains("PraxisFtpServer"))
        assertTrue("没有调用 fs.start()", server.contains("created.start()"))
        // 旧方案的痕迹必须彻底消失（不留死代码）。
        assertTrue(
            "仍残留 vsftpd 安装脚本调用（旧方案未清理干净）",
            !port.contains("FtpServerScript"),
        )
    }

    /** FTP 的被动端口段必须真的配到监听器上（漏了会「能登录看不到文件」）。 */
    @Test
    fun ftp_setsPassivePorts() {
        val server = read("app/src/main/java/dev/praxis/data/linux/PraxisFtpServer.kt")
        assertTrue("没有设置被动端口段", server.contains("setPassivePorts"))
        assertTrue("没有设置被动外部地址", server.contains("setPassiveExternalAddress"))
    }

    /** 端口必须真的提供枚举能力，且适配器按名字透出。 */
    @Test
    fun ptyPort_exposesSessionNames() {
        val port = flat(read("feature/terminal/src/main/java/dev/praxis/feature/terminal/PtySessionPort.kt"))
        assertTrue("PtySessionPort 没有 sessionNames()", port.contains("fun sessionNames()"))

        val adapter = flat(read("app/src/main/java/dev/praxis/data/linux/ProotPtySessionAdapter.kt"))
        assertTrue("适配器没有实现 sessionNames()", adapter.contains("override fun sessionNames()"))
        assertTrue("适配器没有过滤已关闭的会话", adapter.contains("filterNot"))
    }
}
