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
}
