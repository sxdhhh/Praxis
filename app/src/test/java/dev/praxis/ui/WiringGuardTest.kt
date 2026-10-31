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
     * 底栏三个浮层必须共用同一套尺寸与抬起量。
     *
     * ## 它防的是什么
     *
     * 模型选择 / 功能 / 附件（图片·拍照·记忆…）是**同一块板的三种内容**：
     * 用户点不同按钮时，卡片应该原地换内容，而不是换位置、换宽窄。
     *
     * 这三处曾经各写各的：
     *   - 功能弹窗  bottom=96 / end=8  / width=280 / max=420
     *   - 模型选择  bottom=44 / end=12 / width=260 / max=380
     *   - 附件面板  bottom=44 / end=12 / widthIn(max=420)+fillMaxWidth
     *
     * 结果就是截图上看到的：点开附件面板，卡片**明显更宽、更矮**，底边
     * 与功能面板不在一条线上。
     *
     * 这类偏差**编译器与 lint 都不报**，页面上也“能看”，只是不像同一套 UI。
     * 只能靠源码断言钉住。
     */
    @Test
    fun bottomSheets_shareSameSizeAndLift() {
        val settings = read("feature/chat/src/main/java/dev/praxis/feature/chat/ChatSettingsBar.kt")
        val attachment = read("feature/chat/src/main/java/dev/praxis/feature/chat/AttachmentPanel.kt")

        // 三处都必须出现这一组值（功能弹窗 + 模型选择 → settings；附件 → attachment）。
        val expected = listOf(".padding(bottom = 96.dp, end = 8.dp)", ".width(280.dp)")
        expected.forEach { needle ->
            assertTrue(
                "ChatSettingsBar 缺少统一尺寸 $needle（两个弹窗都要有，出现次数=${countOf(settings, needle)}）",
                countOf(settings, needle) >= 2,
            )
            assertTrue(
                "AttachmentPanel 缺少统一尺寸 $needle",
                attachment.contains(needle),
            )
        }
        // 附件面板不允许再出现 fillMaxWidth（会把宽度撑回满屏）。
        assertTrue(
            "AttachmentPanel 的 Card 不应使用 fillMaxWidth（宽度由 width(280.dp) 决定）",
            !attachment.contains(".fillMaxWidth()"),
        )
    }


    private fun countOf(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1


    /**
     * 底栏三个浮层必须共用同一套尺寸与抬起量。
     *
     * ## 它防的是什么
     *
     * 模型选择 / 功能 / 附件（图片·拍照·记忆…）是**同一块板的三种内容**：
     * 用户点不同按钮时，卡片应该原地换内容，而不是换位置、换宽窄。
     *
     * 这三处曾经各写各的：
     *   - 功能弹窗  bottom=96 / end=8  / width=280 / max=420



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
        assertTrue(
            "三个新参数必须进 onSave 回调",
            screen.contains("requestLimitPerMinute = rpmDraft.toIntOrNull() ?: 0"),
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
     * 贴边浮层（AttachmentsPanel）必须有进出场动画。
     */
    @Test
    fun attachmentPanel_usesAnimatedPopup() {
        val panel = read("feature/chat/src/main/java/dev/praxis/feature/chat/AttachmentPanel.kt")
        assertTrue("附件面板必须用带动画的 Popup", panel.contains("MiuixAnimatedPopup("))
        assertTrue("必须用 visible 控制", panel.contains("visible = visible,"))
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
}
