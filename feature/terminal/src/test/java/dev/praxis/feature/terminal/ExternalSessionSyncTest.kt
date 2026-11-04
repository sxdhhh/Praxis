package dev.praxis.feature.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 终端页「外部会话同步」的行为契约。
 *
 * ## 它守的是什么（2026-11-05）
 *
 * 用户反馈：「终端页面还是看不到智能体创建的终端会话」。
 *
 * 根因是**两套会话表**：AI 走 `TerminalSessionPort.create()`，
 * 而终端页的 tab 是 UI 自建的（DataStore 快照恢复）——
 * 终端页从不问「现在有哪些会话」。
 *
 * 更麻烦的是底层那张表在**内存**里（`ProotTerminalSessionPort.live`），
 * App 进程一死就空。所以光问它还不够，还要有一份**持久化**的名字。
 *
 * 这个测试用假端口覆盖那三种来源的合并逻辑。真机上验证这些分支
 * 代价很高（要真开容器、真重启进程），而它们全是纯逻辑。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalSessionSyncTest {

    private val dispatcher = UnconfinedTestDispatcher()

    /** 假 PTY 端口：只回答「现在有哪些会话」，不真起容器。 */
    private class FakePtyPort(
        private val live: List<String> = emptyList(),
        private val throwOnList: Boolean = false,
    ) : PtySessionPort {
        /** 记录 open 被以哪些名字调用过（验证「按名幂等打开」）。 */
        val opened = mutableListOf<String>()

        override suspend fun open(name: String, rows: Int, cols: Int): String? {
            opened += name
            return "sid-" + name
        }

        override fun outputFlow(sessionId: String): Flow<ByteArray> = emptyFlow()

        override fun write(sessionId: String, bytes: ByteArray): Boolean = true

        override fun resize(sessionId: String, rows: Int, cols: Int): Boolean = true

        override fun close(sessionId: String): Boolean = true

        override fun screen(sessionId: String, maxLines: Int): String? = null

        override fun sessionNames(): List<String> =
            if (throwOnList) error("端口不可用") else live
    }

    private class FakeRunner : CommandRunner {
        override suspend fun run(command: String, onOutput: (String) -> Unit): Int = 0
        override fun cancel() = Unit
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(
        port: PtySessionPort?,
        remembered: List<String> = emptyList(),
    ): TerminalViewModel = TerminalViewModel(
        runner = FakeRunner(),
        ioDispatcher = dispatcher,
        ptyPort = port,
        externalSessionNames = { remembered },
    )

    private fun TerminalViewModel.titles(): List<String> =
        state.value.tabs.map { it.title }

    /** 端口里有会话、tab 里没有 -> 补成 tab。 */
    @Test
    fun `live sessions become tabs`() {
        val port = FakePtyPort(live = listOf("main"))
        val vm = vm(port)

        vm.syncExternalSessions()

        // 初始那个 Local tab 仍在，外部会话追加在后面。
        assertTrue("main 应出现", "main" in vm.titles())
        assertEquals(2, vm.titles().size)
        //
        // ## 为什么断言「包含」而不是「等于 listOf(\"main\")」（2026-11-05）
        //
        // 我第一版写的是 `assertEquals(listOf(\"main\"), port.opened)`，
        // CI #164 红了。原因是**构造 ViewModel 时就会建初始 tab**：
        // `init` 里的 `addTab()` 也走 `startPtySession` -> `port.open(\"Local\")`。
        //
        // 所以 `opened` 是 `[\"Local\", \"main\"]`。要验的是
        // 「外部会话被按名打开过」，不是「只有它被打开过」。
        assertTrue("main 应按名打开过", "main" in port.opened)
        // 且顺序在后：外部会话是追加进去的，不影响已有 tab。
        assertEquals("main", port.opened.last())
    }

    /**
     * 持久化名字也能补 tab。
     *
     * 这是**真机上最关键的那条**：App 重启后 `live` 表是空的，
     * 只有持久化的名字还在。
     */
    @Test
    fun `remembered names become tabs even when port is empty`() {
        val port = FakePtyPort(live = emptyList())
        val vm = vm(port, remembered = listOf("main", "nmap"))

        vm.syncExternalSessions()

        val t = vm.titles()
        assertTrue("main 应出现", "main" in t)
        assertTrue("nmap 应出现", "nmap" in t)
        assertEquals(3, t.size)
    }

    /** 两个来源合并去重：同一个名字不该出两个 tab。 */
    @Test
    fun `live and remembered are merged and deduped`() {
        val port = FakePtyPort(live = listOf("main"))
        val vm = vm(port, remembered = listOf("main", "nmap"))

        vm.syncExternalSessions()

        val t = vm.titles()
        assertEquals("main 只该有一个", 1, t.count { it == "main" })
        assertEquals(3, t.size)
    }

    /** 已经是 tab 的会话不再重复添加。 */
    @Test
    fun `already present names are not duplicated`() {
        val port = FakePtyPort(live = listOf("main"))
        val vm = vm(port, remembered = emptyList())

        vm.syncExternalSessions()
        val afterFirst = vm.titles()

        // 再调一次（用户在 tab 之间来回切会重复触发）。
        vm.syncExternalSessions()

        assertEquals("第二次调用不该新增", afterFirst, vm.titles())
    }

    /** 两个来源都空 -> 一个 tab 都不加（不能把 Local 弄丢）。 */
    @Test
    fun `empty sources leave tabs untouched`() {
        val port = FakePtyPort(live = emptyList())
        val vm = vm(port, remembered = emptyList())

        vm.syncExternalSessions()

        assertEquals(listOf("Local"), vm.titles())
    }

    /**
     * 端口枚举抛异常时静默跳过。
     *
     * 退化成「不同步」——也就是这个功能没做之前的状态，
     * 而不是让终端页整体挂掉。
     */
    @Test
    fun `port failure degrades to no sync`() {
        val port = FakePtyPort(throwOnList = true)
        val vm = vm(port, remembered = emptyList())

        vm.syncExternalSessions()

        assertEquals(listOf("Local"), vm.titles())
    }

    /** 没有 PTY 端口（假终端模式）时不做事。 */
    @Test
    fun `no pty port means no sync`() {
        val vm = vm(port = null, remembered = listOf("main"))

        vm.syncExternalSessions()

        assertEquals(listOf("Local"), vm.titles())
    }

    /** 外部会话追加在后面，不打乱用户已有的 tab 顺序。 */
    @Test
    fun `external sessions are appended after existing tabs`() {
        val port = FakePtyPort(live = listOf("main"))
        val vm = vm(port)

        vm.addTab()
        val before = vm.titles()
        vm.syncExternalSessions()

        val after = vm.titles()
        assertEquals("前 N 个应保持不变", before, after.take(before.size))
        assertEquals("main 应在末尾", "main", after.last())
    }
}
