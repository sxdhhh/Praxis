package dev.praxis.feature.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `TerminalViewModel` 的多会话与中断行为。
 *
 * ## 为什么必须 `Dispatchers.setMain`
 *
 * `viewModelScope` 绑定 `Dispatchers.Main.immediate`。纯 JVM 单测里没有主线程实现，
 * 不替换会直接抛 `Module with the Main dispatcher had failed to initialize`。
 * 用 `UnconfinedTestDispatcher` 让协程立即执行，断言不需要 `advanceUntilIdle`。
 *
 * ## 为什么还要把 `ioDispatcher` 注入进 ViewModel
 *
 * 只替换 Main 不够：VM 内部用 `withContext(Dispatchers.IO)` 跑命令，
 * IO 是**真线程池**，`withContext` 会真跨线程。测试在命令回写状态之前就断言，
 * 表现是「退出码那行时有时无」。传入同一个 `UnconfinedTestDispatcher` 后全链路同步。
 *
 * ## 为什么按「格子」而不是按字符取文本
 *
 * 终端缓冲按**单元格**存：一个中文占两格（左格是字，右格是 continuation 占位）。
 * 直接把 `char` 拼起来会得到「A I 智 能」这种带空格的乱串 —— 一开始我就是这么写的，
 * 结果断言 `Ctrl+C` 永远匹配不到。这里按格子取，continuation 格跳过。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    /** 假 Runner：不真起进程，只按脚本回放输出。 */
    private class FakeRunner(
        private val exitCode: Int = 0,
        private val output: String = "",
        private val delayForever: Boolean = false,
    ) : CommandRunner {
        var cancelCount = 0
            private set
        var runCount = 0
            private set

        override suspend fun run(command: String, onOutput: (String) -> Unit): Int {
            runCount++
            if (output.isNotEmpty()) onOutput(output)
            if (delayForever) {
                // 模拟长命令：一直挂着，只有被取消才会离开。
                awaitCancellation()
            }
            return exitCode
        }

        override fun cancel() {
            cancelCount++
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vmWith(
        runner: CommandRunner,
    ): TerminalViewModel = TerminalViewModel(runner = runner, ioDispatcher = dispatcher)

    /** 按格子取可见文本（continuation 格跳过，避免中文被拆成「字 + 空格」）。 */
    private fun TerminalTab.text(): String = lines.joinToString("\n") { line ->
        line.cells.filterNot { it.isContinuation }
            .joinToString("") { it.char.toString() }
            .trimEnd()
    }

    /** 首屏必须有一个会话，且带字符画。 */
    @Test
    fun `startup creates one tab with banner`() {
        val state = vmWith(FakeRunner()).state.value
        assertEquals(1, state.tabs.size)
        assertEquals(0, state.activeIndex)
        assertEquals("Local", state.tabs[0].title)

        val text = state.active.text()
        // 字符画第一行的开头四个下划线：证明 banner 真进了缓冲。
        assertTrue("banner 没进缓冲：\n$text", text.contains("____"))
        assertTrue("缺少提示语：\n$text", text.contains("Ctrl+C"))
    }

    /** 新建会话：数量 +1，且切到新会话。 */
    @Test
    fun `addTab appends and selects`() {
        val vm = vmWith(FakeRunner())
        vm.addTab()
        val state = vm.state.value
        assertEquals(2, state.tabs.size)
        assertEquals(1, state.activeIndex)
        assertEquals("Local 2", state.tabs[1].title)
        assertNotEquals(state.tabs[0].id, state.tabs[1].id)
    }

    /** 最后一个会话不允许关闭：否则整页没有输入框。 */
    @Test
    fun `closeTab refuses to remove the last one`() {
        val vm = vmWith(FakeRunner())
        val onlyId = vm.state.value.tabs.single().id
        vm.closeTab(onlyId)
        assertEquals(1, vm.state.value.tabs.size)
    }

    /** 关闭后活动下标必须收回到合法范围。 */
    @Test
    fun `closeTab keeps activeIndex in range`() {
        val vm = vmWith(FakeRunner())
        vm.addTab()
        vm.addTab()
        val lastId = vm.state.value.tabs.last().id
        assertEquals(2, vm.state.value.activeIndex)

        vm.closeTab(lastId)
        val state = vm.state.value
        assertEquals(2, state.tabs.size)
        assertEquals(1, state.activeIndex)
    }

    /** 输入框内容按会话隔离：切走再切回，各自保留。 */
    @Test
    fun `input is per tab`() {
        val vm = vmWith(FakeRunner())
        vm.onInputChange("echo a")
        vm.addTab()
        vm.onInputChange("echo b")

        assertEquals("echo b", vm.state.value.active.input)
        vm.selectTab(0)
        assertEquals("echo a", vm.state.value.active.input)
        vm.selectTab(1)
        assertEquals("echo b", vm.state.value.active.input)
    }

    /** 提交后命令回显进当前会话的缓冲，退出码 0 不打印退出行。 */
    @Test
    fun `submit echoes command and output`() {
        val vm = vmWith(FakeRunner(exitCode = 0, output = "hello"))
        vm.submit("echo hello")
        val text = vm.state.value.active.text()
        assertTrue("缺少命令回显：\n$text", text.contains("$ echo hello"))
        assertTrue("缺少输出：\n$text", text.contains("hello"))
        assertFalse("退出码 0 不应打印：\n$text", text.contains("[退出码"))
    }

    /** 非 0 退出码要显示出来，否则用户不知道命令失败了。 */
    @Test
    fun `non zero exit prints code`() {
        val vm = vmWith(FakeRunner(exitCode = 2))
        vm.submit("false")
        val text = vm.state.value.active.text()
        assertTrue("缺少退出码：\n$text", text.contains("[退出码 2]"))
    }

    /** Ctrl+C：命令在跑时必须真的调到 runner.cancel()。 */
    @Test
    fun `cancel stops running command via runner`() {
        val runner = FakeRunner(delayForever = true)
        val vm = vmWith(runner)
        vm.submit("sleep 999")
        assertTrue("提交后 running 应为 true", vm.state.value.running)

        vm.cancel()
        assertEquals("必须调用 runner.cancel()：只取消协程会留僵尸进程", 1, runner.cancelCount)
    }

    /** 没有命令在跑时点 Ctrl+C：不调 runner，但仍回显 `^C`。 */
    @Test
    fun `cancel when idle only echoes`() {
        val runner = FakeRunner()
        val vm = vmWith(runner)
        vm.cancel()
        assertEquals(0, runner.cancelCount)
        assertTrue("应回显 ^C：\n${vm.state.value.active.text()}", vm.state.value.active.text().contains("^C"))
    }

    /** 清屏：旧输出消失，但 banner 会被重新写上。 */
    @Test
    fun `clear resets buffer and rewrites banner`() {
        val vm = vmWith(FakeRunner(output = "SOMETHING_OLD"))
        vm.submit("echo x")
        vm.clear()
        val text = vm.state.value.active.text()
        assertFalse("旧输出应被清掉：\n$text", text.contains("SOMETHING_OLD"))
        assertTrue("banner 应重写：\n$text", text.contains("____"))
    }

    /** 字符画每行等宽：不等宽会让字形错位（手工排版最容易踩的坑）。 */
    @Test
    fun `banner art lines have equal width`() {
        val widths = TerminalViewModel.PRAXIS_ART.map { it.length }.toSet()
        assertEquals("字符画各行宽度不一致：$widths", 1, widths.size)
        // 80 列终端下不能折行。
        assertTrue("字符画过宽：$widths", widths.single() < TerminalViewModel.DEFAULT_COLUMNS)
    }

    /** 一个会话在跑时，另一个会话提交要被拒绝（底层只有一条 shell 通道）。 */
    @Test
    fun `running is global across tabs`() {
        val runner = FakeRunner(delayForever = true)
        val vm = vmWith(runner)
        vm.submit("sleep 999")
        vm.addTab()
        vm.submit("echo b")

        // 第二次提交不该真的执行。
        assertEquals(1, runner.runCount)
        val text = vm.state.value.active.text()
        assertTrue("应提示上一条仍在执行：\n$text", text.contains("仍在执行"))
    }
}