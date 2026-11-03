package dev.praxis.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 接线护栏：守住「参数声明了但调用点没传」这类静默失效。
 *
 * ## 为什么不用 Compose UI 测试
 *
 * 这一层要防的不是「渲染错了」，而是「数据根本没传下去」。
 * 一个空列表传进去，界面照常渲染、不报错、只是显示「还没有模型」——
 * 这正是 `ModelPill` 在 2026-10 之前的状态：120 行完整实现，
 * 因为 `ChatTab` 没传 `models` 而永远显示空态。
 *
 * UI 测试跑得慢、要模拟器，且必须构造完整 DI 图；而缺陷本身是
 * **源码层面的遗漏**。所以这里直接读源码做断言：快、零依赖、
 * 在 CI 的纯 JVM 单元测试阶段（不需要 device）就能拦住。
 *
 * ## 代价
 *
 * 依赖文本匹配，重命名即失效。这是可接受的：一旦有人改了参数名，
 * 这个测试会变红，提醒他把断言一起改 —— 而不是让缺陷悄悄回来。
 */
class WiringGuardTest {

    /**
     * 从工程根定位源文件。
     *
     * Gradle 跑模块的单元测试时工作目录是**模块目录**（`app/`），
     * 但 IDE 有时是仓库根。两个位置都试，避免测试环境差异导致假失败。
     */
    private fun source(relative: String): File {
        val candidates = listOf(
            File(relative),
            File("..", relative),
            File("../..", relative),
        )
        for (c in candidates) {
            if (c.isFile) return c
        }
        throw AssertionError(
            "找不到源文件: $relative（尝试过 " + candidates.joinToString { it.absolutePath } + "）",
        )
    }


    private fun read(relative: String): String = source(relative).readText()

    /**
     * `ChatTab` 必须把模型池四个参数传进 `ChatScreen`。
     *
     * 少任何一个的后果：
     *  - 缺 `models` → 底栏模型弹窗永远显示「还没有模型」；
     *  - 缺 `activeModelIndex` → 选中态永远停在第 0 个；
     *  - 缺 `onSelectModelIndex` → 点模型没反应（回调为 null 时列表退化为展示）；
     *  - 缺 `onRemoveAttachment` → 待发图片角标删不掉。
     */
    @Test
    fun chatTab_passesModelPoolToChatScreen() {
        val text = read("app/src/main/java/dev/praxis/ui/tab/ChatTab.kt")
        for (name in listOf(
            "models =",
            "activeModelIndex =",
            "onSelectModelIndex =",
            "onRemoveAttachment =",
        )) {
            assertTrue("ChatTab 未向 ChatScreen 传 $name", text.contains(name))
        }
    }


    /**
     * `ChatScreen` 必须把模型池继续透传给 `ChatSettingsBar`。
     *
     * 只在 `ChatTab` 传是**不够的**：中间层漏传的话，缺陷完全相同，
     * 但排查时容易只看一端就下结论。这条就是那种「两头都查过才发现
     * 断在中间」的护栏。
     */
    @Test
    fun chatScreen_forwardsModelPoolToSettingsBar() {
        val text = read("feature/chat/src/main/java/dev/praxis/feature/chat/ChatScreen.kt")
        for (name in listOf(
            "activeModelIndex = activeModelIndex",
            "onSelectModel = onSelectModelIndex",
            "onManageModels = onOpenModelSettings",
        )) {
            assertTrue("ChatScreen 未向 ChatSettingsBar 传 $name", text.contains(name))
        }
    }


    /**
     * 附件面板必须接 `onDismiss`（浮层版的「点外部关闭」）。
     *
     * 内联版不需要它（点加号即收）；改成 `Popup` 后它是唯一的退出路径之一，
     * 漏传会让用户只能再点一次加号 —— 而加号可能已被浮层盖住。
     */
    @Test
    fun chatScreen_passesOnDismissToAttachmentPanel() {
        val text = read("feature/chat/src/main/java/dev/praxis/feature/chat/ChatScreen.kt")
        assertTrue("ChatScreen 未向 AttachmentPanel 传 onDismiss", text.contains("onDismiss = { if (attachmentPanelOpen)"))
    }


    /**
     * 记忆空间：仓库、路由、页面、`:app` 四层必须都接上 `spaces` / 创建入口。
     *
     * 空间列表从「记忆表反推」改成「独立存储」之后，只要有任何一层漏传，
     * 新建的空间就看不见 —— 表现与旧的死锁完全一样，很难区分是没存还是没传。
     */
    @Test
    fun memorySpaces_areWiredAcrossAllLayers() {
        val repo = read("core/data/src/main/kotlin/dev/praxis/core/data/settings/SettingsRepository.kt")
        assertTrue("SettingsRepository 缺 memorySpaces Flow", repo.contains("val memorySpaces: Flow<List<String>>"))
        assertTrue("SettingsRepository 缺 addMemorySpace", repo.contains("suspend fun addMemorySpace("))

        val route = read("feature/settings/src/main/java/dev/praxis/feature/settings/capability/MemoryRoute.kt")
        assertTrue("MemoryRoute 未接收 spaces", route.contains("spaces: List<String> = emptyList()"))
        assertTrue("MemoryRoute 未透传 spaces", route.contains("spaces = spaces,"))

        val screen = read("feature/settings/src/main/java/dev/praxis/feature/settings/capability/MemoryScreen.kt")
        assertTrue("MemoryScreen 缺新建入口", screen.contains("text = \"+ 新建空间\""))

        val host = read("feature/settings/src/main/java/dev/praxis/feature/settings/SettingsHost.kt")
        assertTrue("SettingsHost 未向 MemoryRoute 传 spaces", host.contains("spaces = memorySpaces,"))

        val app = read("app/src/main/java/dev/praxis/ui/PraxisApp.kt")
        assertTrue("PraxisApp 未收集 memorySpaces", app.contains("settingsRepository.memorySpaces.collectAsState"))
    }


    /**
     * `spaceId` 列不得带 `DEFAULT`。
     *
     * ## 为什么只锚这一个列，而不是禁掉所有 DEFAULT
     *
     * `ALTER TABLE ... ADD COLUMN ... NOT NULL` 在 SQLite 里**必须**给
     * DEFAULT（已有行无法填值），所以 `pinned` / `displayOrder` 那两处
     * `DEFAULT 0` 是正确的、也是唯一的写法。一刀切禁掉会误报。
     *
     * 而 `spaceId` 是 `CREATE TABLE` 里的字段：Room 的实体没有声明
     * `@ColumnInfo(defaultValue = ...)`，导出 schema 里这一列的
     * `defaultValue` 就是 `undefined`。若 SQL 里多写一个 `DEFAULT ''`，
     * 建出来的表与 schema 不一致，升级时会抛
     * `IllegalStateException: Migration didn't properly handle ...`。
     * 这类错误编译期完全看不出来，只有老用户升级才炸。
     */
    @Test
    fun spaceIdColumn_hasNoDefaultValue() {
        val text = read("app/src/main/java/dev/praxis/data/db/PraxisDatabase.kt")
        val hits = text.lines().filter { line ->
            val l = line.lowercase()
            l.contains("spaceid") && l.contains("default")
        }.filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        assertTrue(
            "spaceId 列带 DEFAULT，会与导出 schema 不一致导致升级崩溃：\n" +
                hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }


    /**
     * 真终端接线：终端页必须拿到 `ptyPort`，且 `addTab` 要触发开会话。
     *
     * ## 这条护栏防的是什么
     *
     * 第 4 批之前的状态：`ProotTerminalSessionPort` 完整具备 forkpty、
     * `sendInput`、`resize`、屏幕快照，但在 `AppModule` 里只接给了 AI 的
     * 6 个工具，**终端页一条都没用**。于是用户看到的是一个「假终端」——
     * 每条命令起一个新进程，`cd` 不生效、`vim` 打不开、Ctrl+C 只能杀进程。
     *
     * 这种缺陷编译期、lint 期、运行时都不会报错：页面照常显示、命令照常
     * 执行，只是「不像终端」。所以用源码断言把它钉住：
     *
     *  - `PraxisApp` 构造 VM 时传了 `ptyPort`；
     *  - `TerminalViewModel.addTab` 里调了 `startPtySession`；
     *  - `submit` 里有把命令写进 PTY 的分支（否则命令还是走 runner）。
     */
    @Test
    fun terminalScreen_isWiredToRealPty() {
        val app = read("app/src/main/java/dev/praxis/ui/PraxisApp.kt")
        assertTrue(
            "PraxisApp 没有把 ptyPort 传给 TerminalViewModel，终端页会退回假终端",
            app.contains("ptyPort = terminalPtyPort"),
        )
        assertTrue(
            "PraxisApp 没有给 TerminalScreen 接 onRawKey（TAB/ESC 无法直达 shell）",
            app.contains("onRawKey = terminalViewModel::writeKey"),
        )
        val vm = read(
            "feature/terminal/src/main/java/dev/praxis/feature/terminal/TerminalViewModel.kt",
        )
        assertTrue(
            "addTab 没有启动 PTY 会话，新开的 tab 会是空壳",
            vm.contains("startPtySession(tab)"),
        )
        assertTrue(
            "submit 里没有 PTY 分支，命令仍然走一次性 runner（cd 不生效）",
            vm.contains("ptyPort?.write(ptyId"),
        )
    }


    /**
     * 恢复路径必须还原工具身份，否则重启后工具卡片退化为纯文本。
     *
     * ## 这条护栏防的是什么（2026-10-25 报过、2026-10-29 重新出现）
     *
     * `ChatScreen` 决定画 `ToolCallCard` 还是画纯文本，判据**只有**
     * `bubble.tool != null`。而会话恢复时构造 `ChatBubble` 的那处代码
     * 与「工具执行完立刻追加气泡」的那处是**两套完全独立的构造点**：
     *
     *  - 实时路径（`runToolCalls` 里）：传了 `tool = ToolCallInfo(...)`；
     *  - 恢复路径（`observeSession` 里）：只传了 `role` / `text` / 时间戳。
     *
     * 于是「刚跑完的工具是卡片，重启后同一批工具变成文字」。库侧字段
     * （`toolName` / `toolArgs` / `isError`）一直是齐的，所以这件事
     * 编译期、lint、单元测试、运行时全都不报错 —— 只能靠源码断言。
     *
     * 断言只看「恢复路径那一段里有 tool =」，不匹配具体写法：
     * 换个构造方式（比如抽个 `row.toBubble()`）也照样要求带上工具身份。
     */
    @Test
    fun chatRestore_restoresToolIdentity() {
        val vm = read(
            "feature/chat/src/main/java/dev/praxis/feature/chat/ChatViewModel.kt",
        )
        assertTrue(
            "恢复路径没有还原 tool，重启后工具卡片会退化成纯文本",
            vm.contains("tool = if (role == BubbleRole.Tool)"),
        )
        assertTrue(
            "工具结果落盘时用了 buildToolSummary 加前缀，恢复时必须剥掉",
            vm.contains("unwrapToolSummary("),
        )
    }



    /**
     * 底栏三个浮层必须**共用同一套组件**，而不是三处各自硬编码尺寸去对齐。
     *
     * ## 它防的是什么（2026-11-03 重写）
     *
     * 模型选择 / 功能 / 附件（图片·拍照·记忆…）是同一块板的三种内容，
     * 用户点不同按钮时卡片应该原地换内容，而不是换位置、换宽窄。
     *
     * 这三处曾经各写各的 padding：功能 bottom=96/end=8/width=280，
     * 模型 bottom=44/end=12/width=260，附件干脆 fillMaxWidth + 九宫格。
     * 第一版护栏的做法是**断言三处都出现同一组魔法数字**
     * （`.padding(bottom = 96.dp, end = 8.dp)` + `.width(280.dp)`）。
     *
     * **那个做法是错的**：它把「靠硬编码常量互相猜着对齐」固化成了契约。
     * 只要有人改了其中一处，就得同时改三处 + 改测试；而真正的解法是
     * 让三处**根本不存在各自的尺寸**——都用 miuix 的同一套浮层组件，
     * 宽度/圆角/阴影/边距由组件自己保证。
     *
     * 所以现在断言的是「三者都走同一组件」，并**禁止**再出现那组魔法数字。
     * 这类偏差编译器与 lint 都不报，只能靠源码断言钉住。
     */
    @Test
    fun bottomSheets_shareSameComponent() {
        val attachment = read("feature/chat/src/main/java/dev/praxis/feature/chat/AttachmentPanel.kt")

        // 附件菜单必须走 miuix 下拉（与模型/功能同源）。
        assertTrue(
            "AttachmentPanel 没有用 OverlayDropdownPopup —— 又变成自建浮层了",
            attachment.contains("OverlayDropdownPopup("),
        )
        // 它自建的那套必须已彻底移除。
        assertTrue(
            "AttachmentPanel 里还有自建的 MiuixAnimatedPopup / Card 九宫格",
            !attachment.contains("MiuixAnimatedPopup(") &&
                !attachment.contains("AttachmentCellView("),
        )
        // 附件面板不许再出现「靠魔法数字对齐」的写法。
        //
        // ## 为什么只查附件，不查 ChatSettingsBar（2026-11-03 收窄）
        //
        // 第一版把 ChatSettingsBar 也一起查了，CI 立刻红 —— 因为「功能」与
        // 「模型选择」两块面板内容复杂（记忆/工具/权限等分区、可折叠、需滚动），
        // 它们**必须**用自建 Card + 各自的分区布局，不适合塞进纯列表形态的下拉。
        //
        // 而用户这条诉求的原话是「**加号的菜单**应该和模型和功能菜单的样式一致」，
        // 指向的是加号那一个。把范围扩到 ChatSettingsBar 属于我擅自加戏，
        // 而且会让一条本来正确的断言变成「挡路」。
        //
        // 所以收窄到：附件面板自己不许写死尺寸（它已交给 miuix）。
        listOf(".padding(bottom = 96.dp, end = 8.dp)", ".width(280.dp)").forEach { needle ->
            assertTrue(
                "AttachmentPanel 又出现了硬编码对齐尺寸 $needle",
                !attachment.contains(needle),
            )
        }
    }

    /**
     * 功能面板的五个分区必须默认全部折叠。
     *
     * ## 它防的是什么
     *
     * 模型分区曾经默认 **true**。面板一打开就是“模型池全展开”：
     * 里面还有管理入口和思考强度，全摊开后顶满整张卡片，
     * 用户第一眼看到的就是一堆行 —— 原话「内容太乱了」。
     *
     * Operit 五个分区全部默认折叠（`ClassicChatSettingsBar.kt:140-144`
     * 全是 mutableStateOf(false)），折叠时右侧显示当前值，
     * 即「折叠隐藏的是选项，不是信息」。
     */
    @Test
    fun chatSettingsBar_allSectionsCollapsedByDefault() {
        val bar = read("feature/chat/src/main/java/dev/praxis/feature/chat/ChatSettingsBar.kt")
        listOf(
            "showMemorySection",
            "showModelSection",
            "showToolsSection",
            "showBehaviorSection",
            "showPluginsSection",
        ).forEach { name ->
            assertTrue(
                "$name 必须默认折叠（mutableStateOf(false)）",
                bar.contains("var $name by remember { mutableStateOf(false) }"),
            )
        }
    }


    /**
     * 模型池必须是**二级折叠**，不能直接平铺在分区里。
     *
     * 平铺 20 个模型会占掉大半屏，把「管理模型」「思考强度」挤出去。
     * 这就是用户说的「太乱」的主因。
     */
    @Test
    fun chatSettingsBar_modelListIsSecondLevelFold() {
        val bar = read("feature/chat/src/main/java/dev/praxis/feature/chat/ChatSettingsBar.kt")
        assertTrue(
            "模型池必须有独立的展开状态 showModelList",
            bar.contains("var showModelList by remember { mutableStateOf(false) }"),
        )
        assertTrue(
            "模型池必须由 FoldableContent 包住（二级折叠）",
            bar.contains("FoldableContent(visible = showModelList)"),
        )
    }



    /**
     * 模型页每个分区都必须用 SettingsSectionHeader。
     *
     * ## 1:1 对齐 Operit
     *
     * Operit 的 `SettingsSectionHeader`（`ModelApiSettingsSection.kt:1520`）
     * 是「图标 24dp + 标题 + 副标题」，六个分区一个不少。
     *
     * 而 Praxis 原来七个分区全用 miuix 的 `SmallTitle` —— 一行加粗小字，
     * **没有图标、没有副标题**。后果：
     *
     *  - 一整页分区全靠文字区分，扫视时没有视觉锚点；
     *  - 「参数」「上下文」「思考」不自解释，得点进去才知道管什么。
     *
     * 这类退化编译器与 lint 都不报（`SmallTitle` 是合法组件），
     * 只能靠源码断言。
     */
    @Test
    fun modelScreen_usesSettingsSectionHeader() {
        val screen = read("feature/settings/src/main/java/dev/praxis/feature/settings/ai/ModelScreen.kt")
        assertTrue(
            "模型页必须使用 SettingsSectionHeader 作为分区头",
            screen.contains("SettingsSectionHeader("),
        )
        assertTrue(
            "模型页不应再用 miuix 的 SmallTitle（它没有图标与副标题）",
            !screen.contains("SmallTitle("),
        )
        // 七个分区：服务商 / 接口地址 / API Key / 模型 / 参数 / 上下文 / 思考
        val headerCount = screen.split("SettingsSectionHeader(").size - 1
        assertTrue(
            "应有 7 个分区头（实际 $headerCount）",
            headerCount >= 7,
        )
    }



    /**
     * 主对话必须吃 `FunctionSlot.CHAT` 的覆盖。
     *
     * ## 它防的是什么（2026-10-29）
     *
     * `FunctionSlot.CHAT` 一直存在于枚举里，「功能模型」页也遍历展示、
     * 能编辑、能保存 —— 但全仓**没有任何地方 resolve 它**。
     * 于是用户给「对话」配了模型与地址，存进 DataStore 后再也没被读过：
     * 主对话永远用全局那一套。这是典型的「有 UI 无接线」假控件。
     *
     * 修法：`SettingsBackedProvider` 在每次请求时
     * `resolve(FunctionSlot.CHAT, settings)`，用它的 model / baseUrl /
     * temperature；连「是否已配置」的判据也换成 resolve 后的值 ——
     * 否则只给功能位填了地址的用户会被误判成「未配置」。
     */
    @Test
    fun chatProvider_honoursFunctionSlotChatOverride() {
        val provider = read("app/src/main/java/dev/praxis/data/ai/SettingsBackedProvider.kt")
        assertTrue(
            "SettingsBackedProvider 必须 resolve FunctionSlot.CHAT",
            provider.contains("resolve(FunctionSlot.CHAT, settings)"),
        )
        assertTrue(
            "baseUrl 必须用 resolve 结果（否则功能位的地址是假控件）",
            provider.contains("baseUrl = chatOverride.baseUrl"),
        )
        assertTrue(
            "model 必须用 resolve 结果",
            provider.contains("model = chatOverride.model"),
        )
        assertTrue(
            "未配置判据必须看 resolve 后的值",
            provider.contains("chatOverride.baseUrl.isBlank()"),
        )
    }


    /**
     * 底栏胶囊显示的模型名必须与运行时用的是同一个。
     *
     * 运行时走 `resolve(CHAT)`，显示若仍读 `modelSettings.activeModel`，
     * 功能位一覆盖就会「显示 A、实际用 B」—— 用户据此判断切换失效。
     */
    @Test
    fun chatTab_modelNameMatchesRuntimeResolution() {
        val tab = read("app/src/main/java/dev/praxis/ui/tab/ChatTab.kt")
        assertTrue(
            "底栏模型名必须来自 resolve(CHAT) 的结果",
            tab.contains("modelName = chatDisplayModel"),
        )
        assertTrue(
            "必须存在 chatDisplayModel 的解析",
            tab.contains("FunctionSlot.CHAT"),
        )
        assertTrue(
            "不应再直接用 modelSettings.activeModel 作为显示名",
            !tab.contains("modelName = modelSettings.activeModel"),
        )
    }


    /**
     * 摘要模型的回落链必须跟随主对话（CHAT 覆盖）。
     */
    @Test
    fun summaryModel_followsChatOverride() {
        val app = read("app/src/main/java/dev/praxis/ui/PraxisApp.kt")
        assertTrue(
            "summaryModelProvider 必须先算 CHAT 覆盖再 resolve 摘要位",
            app.contains("val forSummary = global.copy("),
        )
        assertTrue(
            "摘要回落必须用 forSummary 而不是原始 global",
            app.contains("forSummary,"),
        )
    }



    /**
     * 自定义请求头必须真的写进 HTTP 请求。
     *
     * ## 它防的是什么
     *
     * 设置页有个「自定义请求头」分区，能填能存 —— 但如果 provider 不读它，
     * 那就是个纯装饰：用户填了中转站要求的 `X-Api-Key`，请求照样 401，
     * 而且**没有任何错误提示**指向「头没发出去」。
     *
     * 这类「存了不用」的假控件看不出来：编译过、lint 过、单测过、
     * 运行时也不报错，只是不生效。
     */
    @Test
    fun customHeaders_are_actually_sent() {
        val provider = read("app/src/main/java/dev/praxis/data/ai/OpenAiCompatProvider.kt")
        assertTrue(
            "OpenAiCompatProvider 必须接收 customHeaders",
            provider.contains("private val customHeaders"),
        )
        assertTrue(
            "必须把 customHeaders 逐条 addHeader 到请求上",
            provider.contains("customHeaders.forEach"),
        )
        assertTrue(
            "必须有头名合法性校验（避免 OkHttp 抛 IllegalArgumentException）",
            provider.contains("isValidHeaderName"),
        )
        // SettingsBackedProvider 必须把读到的头传下去 —— 少这一步，
        // provider 支持了也白搭。
        val settings = read("app/src/main/java/dev/praxis/data/ai/SettingsBackedProvider.kt")
        assertTrue(
            "SettingsBackedProvider 必须把 advanced.customHeaders 传给 provider",
            settings.contains("customHeaders = advanced.customHeaders"),
        )
    }


    /**
     * 请求队列（限流 + 并发）必须真的在发请求前生效。
     *
     * ## 为什么这条特别重要
     *
     * 限流器写了但没接进请求路径，是「看起来做了、其实完全没做」的典型。
     * 用户填了「每分钟 10 次」，程序照样一秒发 20 个 —— 然后 429。
     */
    @Test
    fun requestThrottle_isWiredIntoRequestPath() {
        val settings = read("app/src/main/java/dev/praxis/data/ai/SettingsBackedProvider.kt")
        assertTrue(
            "必须从 modelAdvancedSettings 读取限流配置",
            settings.contains("repository.modelAdvancedSettings.first()"),
        )
        assertTrue(
            "必须在发请求前 acquire 名额",
            settings.contains("throttle.acquire()"),
        )
        assertTrue(
            "必须在 finally 里归还名额（否则异常路径会泄漏并发位）",
            settings.contains("permit?.close()"),
        )
        // 节流器必须跨请求复用（否则「每分钟 N 次」变成「每个请求各自 N 次」），
        // 但换配置时必须丢掉旧实例 —— 否则用户「10 → 30 → 10」改回来时
        // 会拿到仍带着旧窗口时间戳的实例，额度比配置更紧。
        assertTrue(
            "节流器必须有缓存（每请求新建等于窗口清零、限流失效）",
            settings.contains("private val throttles"),
        )
        assertTrue(
            "换配置时必须重建节流器（丢掉旧窗口）",
            settings.contains("throttles.clear()"),
        )
    }


    /**
     * 进阶配置必须能存也能读回来。
     *
     * ## 存的三个字段缺一不可
     *
     * 少任何一个，那个设置项就是「改了没反应」。
     */
    @Test
    fun advancedSettings_roundTrip() {
        val repo = read("core/data/src/main/kotlin/dev/praxis/core/data/settings/SettingsRepository.kt")
        assertTrue("必须有读取流", repo.contains("val modelAdvancedSettings: Flow<ModelAdvancedSettings>"))
        assertTrue("必须能写请求头", repo.contains("suspend fun setCustomHeaders"))
        assertTrue("必须能写 RPM", repo.contains("suspend fun setRequestLimitPerMinute"))
        assertTrue("必须能写并发上限", repo.contains("suspend fun setMaxConcurrentRequests"))
        // 请求头里可能含令牌，必须走加密路径（与 apiKey 同一口径）。
        assertTrue(
            "请求头必须加密存储（值里可能是中转站令牌）",
            repo.contains("val encrypted = cipher?.encrypt(serialized)"),
        )
        // 路由必须把 UI 草稿写进仓库。
        val route = read("feature/settings/src/main/java/dev/praxis/feature/settings/ai/ModelRoute.kt")
        assertTrue("路由必须保存请求头", route.contains("repository.setCustomHeaders(customHeaders)"))
        assertTrue("路由必须保存 RPM", route.contains("repository.setRequestLimitPerMinute(requestLimitPerMinute)"))
        assertTrue("路由必须保存并发上限", route.contains("repository.setMaxConcurrentRequests(maxConcurrentRequests)"))
    }


    /**
     * 模型管理页必须真的画出这两个分区。
     */
    @Test
    fun modelScreen_hasCustomHeadersAndQueueSections() {
        val screen = read("feature/settings/src/main/java/dev/praxis/feature/settings/ai/ModelScreen.kt")
        assertTrue("必须有「自定义请求头」分区", screen.contains("title = \"自定义请求头\""))
        assertTrue("必须有「请求队列」分区", screen.contains("title = \"请求队列\""))
        // `onSave` 是函数类型，Kotlin 禁止具名实参，所以这三项只能按位置传。
        // 断言跟着检查位置实参的写法：`rpmDraft` / `concurrencyDraft` 必须
        // 出现在 onSave(...) 的参数列表里。
        assertTrue(
            "请求队列的两个参数必须进 onSave 回调",
            screen.contains("rpmDraft.toIntOrNull() ?: 0") &&
                screen.contains("concurrencyDraft.toIntOrNull() ?: 0"),
        )
        assertTrue(
            "dirty 判据必须包含进阶配置（否则按钮一直显示「已保存」）",
            screen.contains("!= advanced.requestLimitPerMinute"),
        )
    }



    /**
     * 模型弹窗必须能一键全选。
     *
     * ## 作用域必须是「当前筛选结果」
     *
     * 如果全选作用在 `options`（全部模型）上，用户搜「gpt」再点全选，
     * 会悄悄选中一堆他看不见的模型 —— 确认后模型池里多出几十个名字。
     * 所以断言的是 `filtered`。
     */
    @Test
    fun modelPicker_hasSelectAllScopedToFiltered() {
        val dlg = read("feature/settings/src/main/java/dev/praxis/feature/settings/ai/ModelPickerDialog.kt")
        assertTrue("必须有全选/取消全选", dlg.contains("allFilteredSelected"))
        assertTrue(
            "全选必须作用在当前筛选结果上（不能是 options）",
            dlg.contains("draftIds + filtered.map { it.id }"),
        )
        // 取消全选也要只从可见项里移除，不能清空整个草稿。
        assertTrue(
            "取消全选必须只移除可见项",
            dlg.contains("draftIds - filtered.map { it.id }.toSet()"),
        )
    }


    /**
     * 功能位必须能用模型选择器，而不是只能手打模型 ID。
     */
    @Test
    fun functionModelScreen_hasModelPicker() {
        val screen = read("feature/settings/src/main/java/dev/praxis/feature/settings/ai/FunctionModelScreen.kt")
        assertTrue("功能位 Route 必须接收 fetcher", screen.contains("modelCatalogFetcher"))
        assertTrue("必须有拉取入口", screen.contains("fun fetchFor("))
        assertTrue("必须挂单值选择弹窗", screen.contains("ModelSelectDialog("))
        assertTrue(
            "弹窗必须常驻、用 visible 控制（不能包 if，否则退场动画播不了）",
            screen.contains("visible = pickerSlot != null"),
        )
        // 选择后必须写回 functional 覆盖（单值），而不是池子。
        assertTrue(
            "选中后必须写回功能位模型",
            screen.contains("prev.copy(model = picked)"),
        )
        // 地址与服务商要按功能位自己的覆盖解析，否则拉到的是主服务商的列表。
        assertTrue(
            "拉取必须用功能位覆盖的 baseUrl",
            screen.contains("override.baseUrl ?: global.baseUrl"),
        )
    }


    /**
     * 单值选择弹窗必须存在且点击即选中。
     */
    @Test
    fun modelSelectDialog_isSingleValueAndClickToPick() {
        val dlg = read("feature/settings/src/main/java/dev/praxis/feature/settings/ai/ModelSelectDialog.kt")
        assertTrue("必须有单选弹窗", dlg.contains("fun ModelSelectDialog("))
        assertTrue("点击即回调选中", dlg.contains("onClick = { onPick(option.id) }"))
        // 与多选弹窗同一套退场处理：常驻 + visible。
        assertTrue("必须用带动画的 Dialog", dlg.contains("MiuixAnimatedDialog("))
    }


    /**
     * 折叠/弹出必须有动画，不能直接 if 硬切。
     */
    @Test
    fun modelScreen_headerSectionUsesFoldableContent() {
        val screen = read("feature/settings/src/main/java/dev/praxis/feature/settings/ai/ModelScreen.kt")
        assertTrue(
            "自定义请求头分区必须用 FoldableContent（不能 if (headersExpanded) 硬切）",
            screen.contains("FoldableContent(visible = headersExpanded)"),
        )
        assertTrue(
            "不得再出现硬切的 if (headersExpanded) {",
            !screen.contains("if (headersExpanded) {"),
        )
    }


    /**
     * 附件菜单必须有进出场动画 —— 由 miuix 下拉组件提供，不是自建 Popup。
     *
     * ## 2026-11-03 重写
     *
     * 原断言是「必须用 MiuixAnimatedPopup（自建的带动画 Popup）」。
     * 那时附件面板是自建的一整套，为了保证它至少有动画才立的这条。
     *
     * 现在附件面板改成了 miuix 的 `OverlayDropdownPopup` —— 动画由组件自带，
     * 比自建的那套更完整（逐项出现 + onDismissFinished）。
     * 断言因此改成「走这个组件」，而不是钉住被删掉的旧实现。
     */
    @Test
    fun attachmentPanel_usesAnimatedDropdown() {
        val panel = read("feature/chat/src/main/java/dev/praxis/feature/chat/AttachmentPanel.kt")
        assertTrue(
            "附件面板没有用 OverlayDropdownPopup —— 会退化成无动画的自建浮层",
            panel.contains("OverlayDropdownPopup("),
        )
        assertTrue("必须用 visible 控制显隐", panel.contains("show = visible,"))
        // 动画结束回调必须在位，否则「关完再允许重开」无从判断。
        assertTrue(
            "缺少 onDismissFinished —— 关闭动画真正结束的时刻拿不到",
            panel.contains("onDismissFinished ="),
        )
    }

    /**
     * 「容器是否在跑」必须能从端口层问出来，而不是只靠 ViewModel 字段。
     *
     * ## 它防的是什么（2026-10-30）
     *
     * `running` 原本只读 ViewModel 的私有 `shellSession`。ViewModel 随页面
     * 销毁后字段就没了，而 proot 进程还在跑 —— 用户「启动 → 退出环境页
     * → 再进来」看到的是「未启动」。界面与事实相反，用户会再点一次启动，
     * 于是同一个 rootfs 上跑两个 proot，apt 锁与 /tmp 开始互相踩。
     */
    @Test
    fun linuxEnv_runningStateSurvivesViewModelRecreation() {
        val port = read("feature/settings/src/main/java/dev/praxis/feature/settings/env/LinuxEnvViewModel.kt")
        assertTrue(
            "端口必须提供「查已有活会话」的能力",
            port.contains("fun liveSession(env: LinuxEnvironment)"),
        )
        assertTrue(
            "refresh 必须从端口层接管已有会话",
            port.contains("shellSession = runCatching { port.liveSession(env) }.getOrNull()"),
        )
        assertTrue(
            "start 必须先去重，不能无条件起第二个 proot",
            port.contains("runCatching { port.liveSession(env) }.getOrNull()?.let { existing ->"),
        )
        // 实现侧：会话要能区分「交互 shell」与「sshd/frpc/vsftpd」。
        val impl = read("app/src/main/java/dev/praxis/data/linux/ProotEnvPort.kt")
        assertTrue("会话必须带「是否交互 shell」标记", impl.contains("isShellSession"))
        assertTrue("必须实现 liveSession", impl.contains("override fun liveSession("))
    }


    /**
     * 终端开会话失败时必须说出来，不能静默降级成宿主 shell。
     *
     * ## 这个缺陷的表现最有欺骗性
     *
     * 降级到 `ShellCommandRunner` 后终端**完全可用**：能敲 `ls`、有输出、
     * `uname` 也正常。但它跑的是宿主自己的 sh，不在下载好的 Linux 里。
     * 用户只能说「终端接的是 shell」，而看不到任何报错。
     */
    @Test
    fun terminal_reportsContainerSessionFailure() {
        val port = read("feature/terminal/src/main/java/dev/praxis/feature/terminal/PtySessionPort.kt")
        assertTrue("端口必须暴露失败原因", port.contains("val lastError: String?"))

        val adapter = read("app/src/main/java/dev/praxis/data/linux/ProotPtySessionAdapter.kt")
        assertTrue(
            "适配器必须留住 create 抛出的异常原因",
            adapter.contains("lastError = created.exceptionOrNull()?.let"),
        )

        val vm = read("feature/terminal/src/main/java/dev/praxis/feature/terminal/TerminalViewModel.kt")
        assertTrue(
            "会话没起来时必须把原因写到终端屏幕",
            vm.contains("[当前不是 Linux 容器]"),
        )
        assertTrue(
            "必须明确告知命令会在宿主环境执行",
            vm.contains("宿主环境"),
        )
    }


    /**
     * 回底按钮与消息导航浮条不许抢同一条竖轴，且轨道不许回来。
     *
     * ## 它防的是什么（2026-10-31）
     *
     * 这俩组件原本都用 `Alignment.CenterEnd`，靠
     * `ScrollThumbRail(... padding(bottom = 96.dp))` 往下挪来错开。
     * 96dp 是拍出来的数：屏幕一矮、或导航胶囊展开，回底按钮就压在
     * 胶囊上，底部还露出半截半透明残影 —— 截图里那根「诡异的滑条」。
     *
     * 于是 10-31 那版把整块贴到 `BottomEnd`。
     *
     * ## 2026-11-01 为什么断言又改了
     *
     * 用户拿标注截图要求：**删掉右侧那根带两个圆点的竖直进度轨道**，
     * 并把圆形回底按钮**上移**到轨道中段偏上的位置。
     *
     * 删轨道之后「贴右下角」这条断言的依据也就没了 —— 它当初的存在
     * 理由只是「让轨道 + 按钮这一整列不和导航浮条共用右缘中点」。
     * 现在只剩一个 30dp 的圆按钮，位置改由「右缘居中 + 向下偏移父容器
     * 高度的 12%」决定，按钮中心落在 62%，天然离开导航浮条所在的 50%。
     *
     * 所以这条护栏改成守**三件今天仍然成立的事**：
     *  1. 组件里不许再有轨道（`RAIL_WIDTH` / `DOT_SIZE` / 圆点包围盒）；
     *  2. 按钮位置必须是**相对容器比例的偏移**，不许写死 dp
     *     （写死 dp 就是「设备专属硬编码」，矮屏会压输入栏）；
     *  3. 那个 96dp 的拍脑袋错位值不许回来。
     */
    @Test
    fun scrollRail_doesNotShareAxisWithNavigator() {
        val screen = read("feature/chat/src/main/java/dev/praxis/feature/chat/ChatScreen.kt")
        // 取调用点之后的一整段（按行）而不是 `substringBefore(")")`：
        // `Modifier.offset(y = maxHeight * 0.12f)` 里那个 `)` 是个合法的
        // 实参收尾，按第一个 `)` 截会把断言要检查的字符串整段截掉。
        val railCall = screen.substringAfter("ScrollThumbRail(").lines().take(6).joinToString("\n")
        assertTrue(
            "回底按钮必须用 offset(y = maxHeight * …) 相对容器比例下移，" +
                "不许写死 dp 高度（矮屏会压输入栏、高屏会偏高）",
            railCall.contains("offset(y = maxHeight * 0.12f)"),
        )
        assertTrue(
            "不许再用 padding(bottom = 96.dp) 这种拍出来的错位值",
            !railCall.contains("padding(bottom = 96.dp)"),
        )
        assertTrue(
            "按钮必须由 BoxWithConstraints 提供 maxHeight 才能算比例",
            screen.contains("androidx.compose.foundation.layout.BoxWithConstraints"),
        )

        // 组件侧：进度轨道已于 2026-11-01 按用户要求删除，不许再长回来。
        val rail = read("ui/design/src/main/java/dev/praxis/ui/design/component/ScrollThumbRail.kt")
        assertTrue(
            "进度轨道（RAIL_WIDTH / DOT_SIZE）已于 2026-11-01 删除，" +
                "当前只保留圆形回底按钮；要加回来先确认用户需求变了",
            !rail.contains("RAIL_WIDTH") && !rail.contains("DOT_SIZE"),
        )
        assertTrue(
            "回底按钮必须仍然可用：scrollToItem 到底（不能用会和弹簧打架的动画版）",
            rail.contains("lazyState.scrollToItem(last)"),
        )
    }
    /**
     * `DistroFamily` 必须真的被读，且 apt 硬编码必须走它。
     *
     * ## 为什么加这条（2026-11-01）
     *
     * `DistroFamily` 枚举 2026-10 就定义了，还带了个 `packageManager` 字段，
     * 注释写着「保留枚举是为了不把 apt 写死」。但全仓搜下来只有 4 处引用，
     * 全是 `LinuxDistro` 内部的声明与赋值，**没有任何一处拿它做分支**。
     * 而 `apt-get` 实际硬编码在四个地方。
     *
     * 这就是 README「接线护栏」说的那种形态：枚举在、编译通过、运行时也不报错，
     * 只是加发行版时才发现根本没人读它。和当年 `FunctionSlot.CHAT` 一模一样。
     *
     * ## 两段式设计
     *
     * 现在还没接分支，直接断言「四处都必须用 packageManager」会立刻变红、没法提交。
     * 所以拆成两段：
     *
     *  - 第一段（现在就成立）：`DistroFamily` 必须带 `packageManager`，
     *    且 `LinuxDistro` 的每个成员都必须显式填 `family`。防止有人删了枚举、
     *    或新增发行版时忘了填族。
     *
     *  - 第二段（接线后才成立）：只要 `DistroFamily` 出现第二种成员
     *    （说明开始做多发行版了），四处 apt 硬编码就必须已经改成分支，
     *    否则立刻变红。这是「接线时强制同步」的那道闸。
     *
     * 加新发行版的人会先让第二段变红，然后被迫去改那四处 —— 正是想要的效果。
     */
    @Test
    fun distroFamily_isActuallyConsumedByInstallScripts() {
        val distro = read("core/linux/src/main/kotlin/dev/praxis/core/linux/LinuxDistro.kt")

        // ── 第一段：枚举与成员的完整性 ──
        assertTrue(
            "DistroFamily 必须保留 packageManager 字段（否则等于把 apt 写死）",
            distro.contains("enum class DistroFamily(val packageManager: String)"),
        )
        assertTrue(
            "LinuxDistro 的每个成员都必须显式填 family",
            distro.contains("family = DistroFamily."),
        )

        // ── 第二段：一旦出现第二种发行版族，四处硬编码必须已改成分支 ──
        val enumBody = distro.substringAfter("enum class DistroFamily").substringBefore("}")
        // 用普通字符串 + 转义：原始字符串以 `\(` 收尾时贴近 `"""` 会被当成转义序列，
        // 是最容易踩的三引号坑之一。
        val memberPattern = Regex("^\\s{4}(\\w+)\\(", RegexOption.MULTILINE)
        val families = memberPattern
            .findAll(enumBody)
            .map { it.groupValues[1] }
            .toSet()
        if (families.size <= 1) return // 还没开始做多发行版，第二段不生效

        val aptSites = listOf(
            "core/linux/src/main/kotlin/dev/praxis/core/linux/FtpServer.kt" to "vsftpd 安装",
            "core/linux/src/main/kotlin/dev/praxis/core/linux/RemoteAccess.kt" to "openssh-server 安装",
            "core/linux/src/main/kotlin/dev/praxis/core/linux/ProotCommand.kt" to "换源后的 update",
        )
        for ((path, what) in aptSites) {
            val text = read(path)
            assertTrue(
                "新增了 " + families.size + " 个发行版族，但 " + path + " 里的「" + what +
                    "」仍写着 apt-get。必须改成用 DistroFamily.packageManager 拼命令，" +
                    "否则新发行版装上就跑不起来。",
                !text.contains("apt-get"),
            )
        }

        // 这一处按字符串字面量过滤，换成 apk/pacman 后会静默失效
        assertTrue(
            "ProotCommand 里的 filterNot 用 `apt-get update` 字面量过滤，" +
                "多发行版下必须改成按 packageManager 判断",
            !read("core/linux/src/main/kotlin/dev/praxis/core/linux/ProotCommand.kt")
                .contains("it.trim() == \"apt-get update\""),
        )
    }


    /**
     * AI 生成会话标题：**能力、接线、触发点**三者必须同时在位。
     *
     * ## 为什么单独立一条护栏（2026-11-02）
     *
     * 用户报告「对话没有 AI 生成题目」。排查发现整条链路的每一块都单独存在、
     * 单独能跑、单独有测试：
     *
     *  - `SessionTitle`（清洗/兜底/提示词）—— 有 `SessionTitleTest`；
     *  - `SettingsBackedTitleGenerator` —— 在 `:app`，构造好、也注入了；
     *  - `ChatViewModel.generateTitleIfPossible` —— 写好了，还带三条防御。
     *
     * 断的是**触发点**：那个调用被写在 `launchPersist { … }` 里面，
     * 而它内部是一次真实网络请求 —— 于是它要等「三次写库都成功」才轮到执行，
     * 实际结果是**一个标题都没生成过**。零件全绿，链路是断的。
     *
     * 单测测的都是零件，没有一条能拦住这种断法。所以这里直接对源码断言：
     * 触发点必须长成「独立协程 + 首条消息闸门」那个形状。
     *
     * ## 断言为什么写成整段文本而不是单个标识符
     *
     * 只断言「文件里有 generateTitleIfPossible」是拦不住回归的 —— 旧代码里也有，
     * 只是位置错了。所以这里断言归一化空白后的**结构片段**：
     * `if (isFirstUserMessage) { persistScope.launch {`。
     * 换回 `launchPersist {` 就会红。
     */
    @Test
    fun chatTitleGenerator_isWiredAndActuallyTriggered() {
        val vm = read("feature/chat/src/main/java/dev/praxis/feature/chat/ChatViewModel.kt")
        val flat = vm.replace(Regex("\\s+"), " ")

        // 1) 端口在（ViewModel 能拿到生成器）。
        assertTrue(
            "ChatViewModel 缺 titleGenerator 端口",
            vm.contains("private val titleGenerator: ChatTitleGenerator? = null"),
        )

        // 2) 触发点：必须是自己起协程的独立路径，且只在首条用户消息时触发。
        assertTrue(
            "标题生成的触发点结构不对 —— 它必须是 `if (isFirstUserMessage) { " +
                "persistScope.launch {`。若这张断言红了，多半是有人又把它挪回 " +
                "`launchPersist { … }`：那样它会等写库全部成功才执行，" +
                "表现就是「一个标题都不生成」。",
            flat.contains("if (isFirstUserMessage) { persistScope.launch {"),
        )
        assertTrue(
            "触发点没有真正调用生成器",
            flat.contains("repo.generateTitleIfPossible(fallbackTitle, text)"),
        )

        // 3) 接线：`:app` 必须真的把实现传进来（传 null 等于没接）。
        val app = read("app/src/main/java/dev/praxis/ui/PraxisApp.kt")
        assertTrue(
            "PraxisApp 没有给 ChatViewModel 传 titleGenerator —— 端口为 null 时 " +
                "generateTitleIfPossible 第一行就 return，标题永远不会生成。",
            app.contains("titleGenerator = dev.praxis.data.ai.SettingsBackedTitleGenerator("),
        )

        // 4) 实现本身必须还在（别只留个空壳引用）。
        val impl = read("app/src/main/java/dev/praxis/data/ai/SettingsBackedTitleGenerator.kt")
        assertTrue(
            "SettingsBackedTitleGenerator 缺 generate 实现",
            impl.contains("override suspend fun generate("),
        )
    }


    /**
     * 终端复制粘贴：必须走**系统原生选择**（2026-11-03 改造后）。
     *
     * ## 为什么整条护栏换掉
     *
     * 第一版（2026-11-02）补的是自研 cell 坐标选择模型：
     * `TerminalSelection` + `detectDragGesturesAfterLongPress` + 选择态操作条
     * 「已选中文本/复制/全选/完成」。用户实测后反馈「自写就太难用」，
     * 要求改用 Android 自带的复制粘贴 —— 于是整条模型被删掉，换成
     * `SelectionContainer` 包整屏单块文本。
     *
     * 护栏因此必须跟着反向：**断言自研模型确实没了、系统选择确实接上了**。
     * 留着旧断言会变成「测一个已删除的函数」，比没有更糟（假绿）。
     *
     * ## 为什么锚 SelectionContainer + buildAnnotatedScreen 这两个点
     *
     * 只断言「文件里有 SelectionContainer」太弱：它可能被套在 LazyColumn 外层，
     * 那样跨 item 选不中（这正是改造要解决的问题）。
     * 所以同时锚「整屏拼接函数」—— 两者同时存在才说明是真的整块渲染。
     */
    @Test
    fun terminalCopy_usesSystemSelectionOverWholeBuffer() {
        val screen = read("feature/terminal/src/main/java/dev/praxis/feature/terminal/TerminalScreen.kt")
        val flat = screen.replace(Regex("\\s+"), " ")

        // 1) 用系统选择容器。
        assertTrue(
            "输出区没有用 SelectionContainer —— 长按不会出现系统选择手柄",
            flat.contains("SelectionContainer("),
        )
        // 2) 整屏拼成一条 AnnotatedString（否则跨行选不中）。
        assertTrue(
            "没有把整屏拼成单块文本 —— SelectionContainer 包逐行 Text 时跨行选不中",
            flat.contains("buildAnnotatedScreen(state.active.lines, defaultFg)"),
        )
        // 3) 输出区不再是 LazyColumn（逐行 item 会让系统选择跨不了行）。
        assertTrue(
            "输出区又变回 LazyColumn 了 —— 系统选择无法跨 item",
            !flat.contains("items( count = state.active.lines.size,"),
        )
        // 4) 自研选择模型必须已彻底移除（防「两套并存」）。
        assertTrue(
            "自研的 TerminalSelection 模型又回来了 —— 会与系统选择打架",
            !flat.contains("TerminalSelection(") &&
                !flat.contains("TerminalSelectionBar(") &&
                !flat.contains("extractSelectionText("),
        )
    }
    /**
     * proot 启动：可执行文件只能来自 `nativeLibraryDir`，loader 只能靠 PROOT_LOADER 指路。
     *
     * ## 为什么必须有这条（2026-11-02，真机 Permission denied）
     *
     * 曾经为了「让 proot 在 /proc/self/exe 的目录里找到 loader」，把 libproot.so
     * 拷一份到 `filesDir/proot/bin/proot` 并以它为 argv[0] 起跑。真机直接报：
     *
     * ```
     * Cannot run program ".../files/proot/bin/proot": error=13, Permission denied
     * ```
     *
     * 原因是 **Android 10 起禁止从应用可写数据目录执行文件**（SELinux 策略），
     * `setExecutable(true, false)` 改不了。可执行的只有 `nativeLibraryDir`，
     * 而它只读 —— 所以「把 proot 拷到别处执行」这个方向**永远**不成立。
     *
     * 这条护栏把结论钉住：一旦有人再把可执行路径指向 filesDir，CI 立刻红，
     * 不必等他装到真机上才发现。
     */
    @Test
    fun prootExecutable_neverComesFromAppDataDir() {
        val cmd = read("core/linux/src/main/kotlin/dev/praxis/core/linux/ProotCommand.kt")
        val cmdFlat = cmd.replace(Regex("\\s+"), " ")

        // 1) 核心层不再构造任何「别名」可执行路径。
        assertTrue(
            "ProotCommand 又在构造别名可执行路径了 —— Android 10+ 上必然 " +
                "Permission denied，proot 会连启动都做不到",
            !cmdFlat.contains("val alias = File("),
        )
        // 2) 仍然只有「一个候选」：多候选意味着第一次注定失败。
        assertTrue(
            "launchArgs 的签名变了 —— 现在只允许 (executable, arguments) 两个入参，" +
                "多出来的候选若指向 filesDir，每次启动都会白等一次超时",
            cmdFlat.contains("fun launchArgs( executable: File, arguments: List<String>, )"),
        )

        // 3) 两条启动器都必须设 PROOT_LOADER（历史上一条设了、一条忘了，
        //    表现是「终端能用、设置页里的命令全崩」）。
        for (path in listOf(
            "app/src/main/java/dev/praxis/data/linux/ProotEnvPort.kt",
            "app/src/main/java/dev/praxis/data/linux/ProotTerminalSessionPort.kt",
        )) {
            assertTrue(
                path + " 没有设置 PROOT_LOADER —— proot 找不到 loader 时会报 " +
                    "execve(\"/bin/sh\"): No such file or directory，极具误导性",
                read(path).contains("PROOT_LOADER"),
            )
        }

        // 4) 铺文件逻辑只铺 loader，不再铺可执行的别名。
        val staging = read("app/src/main/java/dev/praxis/data/linux/ProotLoaderStaging.kt")
        assertTrue(
            "ProotLoaderStaging 又在铺 proot 别名了 —— 那是 Android 10+ 上" +
                "不能执行的文件，铺了也没用",
            !staging.contains("PROOT_ALIAS_NAME"),
        )

        // 5) 可执行文件来源必须是 nativeLibraryDir。
        assertTrue(
            "ProotEnvPort 没有从 nativeLibraryDir 取 proot 可执行文件",
            read("app/src/main/java/dev/praxis/data/linux/ProotEnvPort.kt")
                .contains("nativeLibraryDir"),
        )
    }


    /**
     * ⑦ 删当前对话：`justDeletedId` 必须挡住「补行」，且必须能自清。
     *
     * ## 背景（2026-11-02，模拟器 + 直读数据库实测）
     *
     * 用户报「处于当前对话时无法删除该对话」。真因是侧边栏里那段「补齐当前
     * 会话的行」的守卫：删除是异步的（先删库、再让上层切 sessionId），
     * 中间有个窗口 sessionId 仍等于已删的 id；而删除后 reloadTick++ 会重跑
     * 那段守卫，它看到「列表里没有 currentSessionId」就 ensureSession 把刚删
     * 的行**又插回去**。实测证据：删完当前会话后 sessions 表里仍有那条。
     *
     * ## 这条护栏守两件事
     *
     * 1. **挡住**：补行条件里必须有 `currentSessionId != justDeletedId`。
     *    少了它，「当前对话删不掉」立刻复发。
     * 2. **能自清**：标记不能永远留着。若上层那次切换失败（协程取消 /
     *    写库异常被 runCatching 吃掉），sessionId 会一直等于 justDeletedId，
     *    之后这个 id 即使被重新创建，侧边栏也不会为它补行 ——
     *    表现变成「列表里莫名少一条」。
     *
     * 这两条是一对：只做第 1 条会留下永久标记，只做第 2 条则删不掉。
     */
    @Test
    fun chatSidebar_deleteCurrentSessionGuardIsCorrect() {
        val sidebar = read("app/src/main/java/dev/praxis/ui/ChatSidebar.kt")
        val flat = sidebar.replace(Regex("\\s+"), " ")

        // 1) 补行守卫必须排除刚删掉的 id。
        assertTrue(
            "补行条件里缺 `currentSessionId != justDeletedId` —— " +
                "这正是「当前对话删不掉」的复发点：删除后列表刷新会把刚删的行插回去",
            flat.contains("sessions.none { it.id == currentSessionId } && currentSessionId != justDeletedId"),
        )

        // 2) 删除处必须打标记。
        assertTrue(
            "删除处没有记 justDeletedId，补行守卫就无从判断",
            flat.contains("justDeletedId = session.id"),
        )

        // 3) 标记必须能自清（否则永久挡住该 id 的补行）。
        assertTrue(
            "justDeletedId 缺少自清逻辑 —— 上层切换失败时它会永久留着，" +
                "之后该 id 被重新创建也不会补行，表现是「列表里少一条」",
            flat.contains("if (justDeletedId != null) {") &&
                flat.contains("if (!stillListed && currentSessionId != deleted) justDeletedId = null"),
        )

        // 4) LaunchedEffect 的 key 必须带 currentSessionId，否则切换后不会重跑。
        assertTrue(
            "LaunchedEffect 的 key 缺 currentSessionId —— 上层切走后这个 effect 不会重跑，" +
                "标记永远清不掉",
            flat.contains("LaunchedEffect(open, reloadTick, currentSessionId) {"),
        )
    }

    /**
     * ③ 硬链接解析必须**被真的调用**，不是只定义在那。
     *
     * ## 为什么这条必要（2026-11-02）
     *
     * `parseHardLinkLine` 有 13 条单测，覆盖 GNU 与 toybox 两种措辞。
     * 但如果 `listHardLinkEntries` 改回内联解析（或干脆不调它），
     * 那 13 条测试依然全绿 —— 它们测的是函数本身，不是「函数有没有被用」。
     *
     * 这正是「能力在、接线不在」的形态，本项目已多次踩过。
     */
    @Test
    fun rootfsExtract_actuallyUsesHardLinkParser() {
        val text = read("app/src/main/java/dev/praxis/data/linux/ProotEnvPort.kt")
        assertTrue(
            "listHardLinkEntries 没有调用 parseHardLinkLine —— 硬链接识别会静默失效，" +
                "tar 会继续报 can't link ... Permission denied",
            text.contains("parseHardLinkLine(line)?.let"),
        )
    }

    /**
     * ⑤ 两条启动分支必须**共用**同一个预期收尾判定。
     *
     * ## 为什么这条必要（2026-11-02）
     *
     * 判据原先在 PTY 分支与管道分支各内联了一遍。历史上这种「两处各写一份」
     * 分叉过（一条改了一条没改，表现是「终端能用、设置页里的命令全崩」）。
     * 抽成 `isExpectedShutdown` 就是为了让两条分支不可能再分叉。
     */
    @Test
    fun sessionReadInterruption_sharesOnePredicate() {
        val text = read("app/src/main/java/dev/praxis/data/linux/ProotTerminalSessionPort.kt")
        assertTrue(
            "PTY 分支没有走 isExpectedShutdown",
            text.contains("isExpectedShutdown(closedByOwner.isFailure, closing.contains(id))"),
        )
        assertTrue(
            "管道分支没有走 isExpectedShutdown",
            text.contains("isExpectedShutdown(read.isFailure, closing.contains(id))"),
        )
        assertTrue(
            "又出现了内联的旧判据 —— 两条分支会再次分叉",
            !text.contains(".isFailure && !closing.contains("),
        )
    }

    /**
     * ⑥ 回底按钮的可见性必须走 `shouldShowScrollToBottom`，不能又内联成 `||`。
     *
     * ## 为什么这条必要（2026-11-02）
     *
     * 原实现 `visible = showWhenIdle || showButton` 让按钮**永远显示**，
     * `isAtBottom` 算得再准也被短路。这个错误很容易在重构时回来 ——
     * 因为「保持显示」听起来就该用 `||`。
     */
    @Test
    fun scrollToBottom_visibilityGoesThroughPredicate() {
        val text = read("ui/design/src/main/java/dev/praxis/ui/design/component/ScrollThumbRail.kt")
        assertTrue(
            "可见性没有走 shouldShowScrollToBottom",
            text.contains("shouldShowScrollToBottom(atBottom)"),
        )
        // ⚠️ 只看**代码行**，不看注释。
        //
        // 那段错法的原文被写进了 KDoc 作为反面教材（「原实现是 visible = showWhenIdle
        // || showButton」），所以整文件搜字符串会命中注释、造成假红。
        // 这与本项目其它检查（strip_noncode）同一个口径：注释不是代码。
        val code = text.lines()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertTrue(
            "可见性又变成了 `visible = showWhenIdle ...` —— 只要让 showWhenIdle 参与，" +
                "按钮就会在到底时依然亮着（按下去什么都不会发生）",
            !code.contains("visible = showWhenIdle"),
        )
        assertTrue(
            "isAtBottom 没有被调用 —— 到底判定失效，按钮该灭时不灭",
            code.contains("isAtBottom("),
        )
    }

    /**
     * ② 的两条启动路径都必须把 `LD_LIBRARY_PATH` 指向依赖目录。
     *
     * ## 为什么这条必要（2026-11-02 实测）
     *
     * proot 的 ELF 要 `libtalloc.so.2` 与 `libandroid-shmem.so`，而**执行**
     * `nativeLibraryDir` 里的二进制时 Android 链接器不搜那个目录（实测：
     * 库就在同目录，仍报 `library "libtalloc.so.2" not found`）。
     *
     * 不指路 = proot 起不来 = 秒退 = 终端回退宿主 shell = `can't access tty`。
     * 而代码原先只**清掉** `LD_LIBRARY_PATH`，从没设过它 —— 这正是被漏掉的一环。
     */
    @Test
    fun prootChild_mustGetLibraryPath() {
        val term = read("app/src/main/java/dev/praxis/data/linux/ProotTerminalSessionPort.kt")
        val envPort = read("app/src/main/java/dev/praxis/data/linux/ProotEnvPort.kt")
        // 管道分支
        assertTrue(
            "applyEnv 没设 LD_LIBRARY_PATH —— 管道分支的 proot 会链接失败",
            term.contains("prootLibDirFor()?.let { e[\"LD_LIBRARY_PATH\"] = it.absolutePath }"),
        )
        // PTY 分支
        assertTrue(
            "ptyEnvironment 没设 LD_LIBRARY_PATH —— PTY 分支的 proot 会链接失败",
            term.contains("prootLibDirFor()?.let { put(\"LD_LIBRARY_PATH\", it.absolutePath) }"),
        )
        // 设置页 / AI 工具那条路径
        assertTrue(
            "ProotEnvPort.prootEnv 没设 LD_LIBRARY_PATH",
            envPort.contains("ProotLoaderStaging.stageLibs(context)") &&
                envPort.contains("put(\"LD_LIBRARY_PATH\", it.absolutePath)"),
        )
    }
    /**
     * 圆角必须走 [PraxisRadius] 令牌，不得内联 `RoundedCornerShape(12.dp)`。
     *
     * ## 它防的是什么（2026-11-03）
     *
     * 改造前全仓 67 处 `RoundedCornerShape(12.dp)` 这类硬编码，取值有
     * 1/2/3/4/6/8/10/12/16/18/20/24.dp 十二种。同一个「卡片」语义在不同页面
     * 用了 10、12、16 三种 —— 用户说不出哪错了，但整体观感是散的。
     *
     * 这和 `PraxisMotion` 是同一类问题（那边是时长、这边是圆角）。
     * 令牌一旦建立而不加护栏，下一次改动又会顺手写回 `16.dp`。
     *
     * ## 为什么只查这几个文件，不是全仓
     *
     * 全仓 67 处一次性迁完风险太大（改错了编译器不报，只是「看着不对」）。
     * 先迁最高频的几处 + 护栏守住**这几个文件**不再回退。
     * 其余文件的迁移分批做，每批都靠这条护栏的同一套判据。
     */
    @Test
    fun cornerRadius_goesThroughToken() {
        // ## 全仓扫描（2026-11-03：迁移完成后收紧）
        //
        // 迁移刚起步时这条护栏只查 3 个文件 —— 那时全仓还有 67 处硬编码，
        // 一次全查会红一片，没有意义。
        //
        // 现在 67 处已全部迁完，护栏必须覆盖全仓：
        // 否则第 4~67 处可以随便被改回去而不报警。
        val roots = listOf("app", "core", "feature", "ui", "preview")
        val violations = mutableListOf<String>()
        roots.forEach { root ->
            val dir = File(root)
            if (!dir.isDirectory) return@forEach
            dir.walkTopDown()
                // 跳过 build 产物与测试源码：测试里可以有构造用的假数据。
                .onEnter { it.name != "build" }
                .filter { it.isFile && it.extension == "kt" }
                .filterNot { it.path.contains("/src/test/") }
                .forEach { file ->
                    val code = file.readText().lines()
                        .filterNot {
                            val t = it.trimStart()
                            t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
                        }
                        .joinToString("\n")
                    Regex("RoundedCornerShape\\(\\s*\\d+\\.dp")
                        .find(code)
                        ?.let { violations += file.path + " -> " + it.value }
                }
        }
        assertTrue(
            "以下文件仍有内联圆角，应改用 PraxisRadius 令牌：" + violations.joinToString("; "),
            violations.isEmpty(),
        )
    }

    /**
     * 顶栏返回键必须是「有底」的悬浮按钮，不能是裸 IconButton。
     *
     * ## 它防的是什么（2026-11-03）
     *
     * 改造前 `AppScaffold` 的返回键是裸 `IconButton` —— 只有一个图标。
     * 它悬浮在会滚动的页面内容之上，滚动时图标直接压在文字/图片上，
     * 既看不清、也看不出这是个可点的东西。
     *
     * 改成 `FloatingIconButton`（圆形底 + 描边 + 内阴影）之后，
     * 这条护栏防止有人图省事改回裸 IconButton。
     */
    @Test
    fun appScaffold_backButtonHasBackground() {
        val scaffold = read("ui/design/src/main/java/dev/praxis/ui/design/component/AppScaffold.kt")
        // 只看代码行，不看注释（注释里会提到旧写法作说明）。
        val code = scaffold.lines()
            .filterNot {
                val t = it.trimStart()
                t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
            }
            .joinToString("\n")
        assertTrue(
            "AppScaffold 的返回键没有用 FloatingIconButton —— 又变回裸 IconButton 了",
            code.contains("FloatingIconButton("),
        )
        // 悬浮按钮本身必须真的有底色与描边，否则「有底」是假的。
        val btn = read("ui/design/src/main/java/dev/praxis/ui/design/component/FloatingIconButton.kt")
        assertTrue("FloatingIconButton 缺少 background", btn.contains(".background("))
        assertTrue("FloatingIconButton 缺少 border 描边", btn.contains(".border("))
        assertTrue(
            "FloatingIconButton 缺少内阴影（drawWithContent）",
            btn.contains(".drawWithContent"),
        )
    }

    /**
     * 侧边栏遮罩必须是「压暗」，不能用 onSurface —— 那是提亮。
     *
     * ## 它防的是什么（2026-11-03 真机实测）
     *
     * 用户原话：「侧边栏是压暗背景而不是提亮背景」。
     *
     * 旧实现写的是：
     *
     * ```kotlin
     * .background(MiuixTheme.colorScheme.onSurface.copy(alpha = SIDEBAR_DIM_ALPHA))
     * ```
     *
     * `onSurface` 的语义是「表面上的**前景**色」—— **深色主题下它是浅色**。
     * 浅色半透明叠上去就是提亮，与「弹出层压暗背景」的层级语义正好相反。
     *
     * 真机像素采样（同一位置、同一条会话）：
     *
     * ```
     *                 抽屉关闭    抽屉打开
     * 用 onSurface      25.1        130.7     ← 变亮 5.2 倍（bug）
     * 用 Color.Black    25.1         12.3     ← 变暗 51%（正确）
     * ```
     *
     * 这条护栏钉住「必须用 Color.Black」：
     * 换个主题色、重构布局都可能顺手把它改回语义色，而编译器不会报。
     */
    @Test
    fun sidebarScrim_darkensInsteadOfLightening() {
        val sidebar = read("ui/widgets/src/main/java/dev/praxis/ui/widgets/MiuixSidebar.kt")
        // 只看代码行，不看注释（注释里会引用旧写法作说明）。
        val code = sidebar.lines()
            .filterNot {
                val t = it.trimStart()
                t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
            }
            .joinToString("\n")
        assertTrue(
            "侧边栏遮罩没有用 Color.Black —— 压暗必须与主题明暗无关",
            code.contains("Color.Black.copy(alpha = SIDEBAR_DIM_ALPHA)"),
        )
        // 明确禁止退回语义色：onSurface 在深色主题下是浅色，会变成提亮。
        assertTrue(
            "侧边栏遮罩又用回 onSurface 了 —— 那是提亮不是压暗",
            !code.contains("onSurface.copy(alpha = SIDEBAR_DIM_ALPHA)"),
        )
        // 遮罩必须真的挂在整屏 Box 上，且 alpha 随开合进度变化；
        // 写死一个常量的 alpha 会让开合动画失去「由浅入深」的过程。
        assertTrue(
            "遮罩的 alpha 没有跟随 progress —— 开合时不会有浓淡变化",
            code.contains("alpha = sidebarEasedProgress(progress.value)"),
        )
    }

}
