package dev.praxis.core.linux

import dev.praxis.core.tool.PermissionTier
import dev.praxis.core.tool.Tool
import dev.praxis.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 7 个终端会话工具的行为锁定。
 *
 * ## 为什么要用假端口而不是真机
 *
 * 这 7 个工具加起来约 20 个分支（缺参、会话不存在、control 未知、
 * 交互模式提示、超时、非零退出码……）。在真机上一个一个点，
 * 覆盖不到一半，而且每次都要等环境安装。用假 [TerminalSessionPort]
 * 就能把分支全部走一遍：**工具层是纯逻辑，唯一的副作用是端口调用**。
 *
 * 真实 `ProotEnvPort` 的职责是「按这些调用去起 proot 进程」，
 * 那部分由它自己的测试与真机验证负责。
 */
class TerminalSessionToolsTest {

    /** 极简假上下文：工具层只读 `tier`，其余不用。 */
    private val context = object : ToolContext {
        override val tier: PermissionTier = PermissionTier.STANDARD
    }

    /**
     * 假会话端口。
     *
     * 记录所有调用，供断言「工具到底有没有真的下发」——
     * 只看返回值不检查副作用，会出现「工具返回成功但压根没调端口」的假通过。
     */
    private class FakePort : TerminalSessionPort {
        val created = mutableListOf<String>()
        val closed = mutableListOf<String>()
        val executed = mutableListOf<TerminalSessionCommand>()
        val inputs = mutableListOf<Pair<String?, TerminalControl?>>()

        private val all = mutableListOf<TerminalSessionInfo>()

        /** 下一次 execute 的返回。 */
        var nextResult = LinuxExecResult(exitCode = 0, output = "ok")

        /** execute 时是否回调片段。 */
        var emitChunks: List<String> = emptyList()

        /** screen() 的返回。 */
        var screenText: String? = "hello"

        override fun sessions(): List<TerminalSessionInfo> = all.toList()

        override fun find(sessionId: String): TerminalSessionInfo? =
            all.firstOrNull { it.id == sessionId }

        override suspend fun create(name: String): TerminalSessionInfo {
            // 幂等：同名直接返回既有（照 Operit createOrGetSession）。
            all.firstOrNull { it.name == name }?.let { return it }
            created += name
            val info = TerminalSessionInfo(id = "s${all.size + 1}", name = name)
            all += info
            return info
        }

        override fun close(sessionId: String): Boolean {
            val idx = all.indexOfFirst { it.id == sessionId }
            if (idx < 0) return false
            all[idx] = all[idx].copy(closed = true)
            closed += sessionId
            return true
        }

        override suspend fun execute(
            command: TerminalSessionCommand,
            onChunk: ((TerminalChunk) -> Unit)?,
        ): LinuxExecResult {
            executed += command
            emitChunks.forEach { onChunk?.invoke(TerminalChunk(command.sessionId, command.commandId, it)) }
            return nextResult
        }

        override fun sendInput(
            sessionId: String,
            input: String?,
            control: TerminalControl?,
        ): Boolean {
            if (find(sessionId)?.canAcceptCommand != true) return false
            inputs += input to control
            return true
        }

        override fun screen(sessionId: String, maxLines: Int): String? = screenText
    }

    private fun portWithSession(): FakePort = FakePort().let { p ->
        // create 是 suspend，用 runBlocking 建好初始会话；
        // 之所以不把每个用例写成 suspend 的辅助函数：`runTest` 里的
        // 挂起点会被记录成「测试挂起」，混进业务断言里反而更难读。
        kotlinx.coroutines.runBlocking { p.create("main") }
        p
    }

    // ── create ────────────────────────────────────────────────

    @Test
    fun `create 幂等 同名不新建`() = runTest {
        val port = FakePort()
        val tool = CreateTerminalSessionTool(port)

        val first = tool.execute("""{"session_name":"main"}""", context)
        val second = tool.execute("""{"session_name":"main"}""", context)

        assertFalse(first.isError)
        assertFalse(second.isError)
        assertEquals("两次同名只应建一个会话", 1, port.sessions().size)
        assertTrue(first.content.contains("session_id"))
        assertTrue("应回显名字", second.content.contains("main"))
    }

    @Test
    fun `create 不传名字时用默认名`() = runTest {
        val port = FakePort()
        CreateTerminalSessionTool(port).execute("{}", context)
        assertEquals("main", port.sessions().single().name)
    }

    // ── execute ───────────────────────────────────────────────

    @Test
    fun `execute 缺 command 报错且不下发`() = runTest {
        val port = portWithSession()
        val result = ExecuteInTerminalSessionTool(port).execute("""{"session_id":"s1"}""", context)
        assertTrue(result.isError)
        assertTrue(result.content.contains("command"))
        assertEquals("不该真的执行", 0, port.executed.size)
    }

    @Test
    fun `execute 无会话时提示先建会话`() = runTest {
        val port = FakePort()
        val result = ExecuteInTerminalSessionTool(port).execute("""{"command":"ls"}""", context)
        assertTrue(result.isError)
        assertTrue("应提示去 create", result.content.contains("create_terminal_session"))
        assertEquals(0, port.executed.size)
    }

    @Test
    fun `execute 成功时输出含命令与当前目录`() = runTest {
        val port = portWithSession()
        port.nextResult = LinuxExecResult(exitCode = 0, output = "file1\nfile2")
        val result = ExecuteInTerminalSessionTool(port).execute("""{"command":"ls"}""", context)

        assertFalse(result.isError)
        assertTrue(result.content.contains("$ ls"))
        assertTrue("应带 cwd 供模型拼绝对路径", result.content.contains("cwd:"))
        assertTrue(result.content.contains("file1"))
        assertEquals("ls", port.executed.single().command)
    }

    @Test
    fun `execute 非零退出码标记为错误但保留输出`() = runTest {
        val port = portWithSession()
        port.nextResult = LinuxExecResult(exitCode = 2, output = "no such file")
        val result = ExecuteInTerminalSessionTool(port).execute("""{"command":"cat x"}""", context)

        assertTrue(result.isError)
        assertTrue(result.content.contains("exit=2"))
        assertTrue("失败时输出也必须保留", result.content.contains("no such file"))
    }

    @Test
    fun `execute 超时时明确说超时而不是普通失败`() = runTest {
        val port = portWithSession()
        port.nextResult = LinuxExecResult(exitCode = 124, output = "", timedOut = true)
        val result = ExecuteInTerminalSessionTool(port).execute("""{"command":"sleep 999"}""", context)

        assertTrue(result.content.contains("超时"))
    }

    @Test
    fun `execute 不传 session_id 时落到默认会话`() = runTest {
        val port = portWithSession()
        val result = ExecuteInTerminalSessionTool(port).execute("""{"command":"pwd"}""", context)
        assertFalse(result.isError)
        assertEquals("s1", port.executed.single().sessionId)
    }

    // ── streaming ─────────────────────────────────────────────

    @Test
    fun `streaming 保留分段边界`() = runTest {
        val port = portWithSession()
        port.emitChunks = listOf("line A", "line B")
        port.nextResult = LinuxExecResult(exitCode = 0, output = "line A\nline B")
        val result = ExecuteInTerminalSessionStreamingTool(port)
            .execute("""{"command":"echo"}""", context)

        assertFalse(result.isError)
        assertTrue("应标注为分段输出", result.content.contains("分段输出"))
        assertTrue(result.content.contains("line A"))
        assertTrue(result.content.contains("line B"))
    }

    // ── input ─────────────────────────────────────────────────

    @Test
    fun `input 未知 control 明确报错并列出可用值`() = runTest {
        val port = portWithSession()
        val result = InputInTerminalSessionTool(port)
            .execute("""{"session_id":"s1","control":"ctrl_xx"}""", context)

        assertTrue(result.isError)
        assertTrue(result.content.contains("ctrl_xx"))
        assertTrue("应列可用值帮助模型改正", result.content.contains("ctrl_c"))
        assertEquals("不该下发任何写入", 0, port.inputs.size)
    }

    @Test
    fun `input control 优先于裸文本`() = runTest {
        val port = portWithSession()
        InputInTerminalSessionTool(port).execute(
            """{"session_id":"s1","input":"ignored","control":"enter"}""",
            context,
        )
        val (input, control) = port.inputs.single()
        assertEquals(TerminalControl.Enter, control)
        // input 仍原样传下去（由实现方决定忽略），这里只断言 control 生效。
        assertEquals("ignored", input)
    }

    @Test
    fun `input 两者都不给时报错`() = runTest {
        val port = portWithSession()
        val result = InputInTerminalSessionTool(port).execute("""{"session_id":"s1"}""", context)
        assertTrue(result.isError)
        assertEquals(0, port.inputs.size)
    }

    @Test
    fun `input 已关闭的会话写入失败`() = runTest {
        val port = portWithSession()
        port.close("s1")
        val result = InputInTerminalSessionTool(port)
            .execute("""{"session_id":"s1","control":"ctrl_c"}""", context)
        assertTrue(result.isError)
    }

    // ── close ─────────────────────────────────────────────────

    @Test
    fun `close 不存在的会话返回失败`() = runTest {
        val port = FakePort()
        val result = CloseTerminalSessionTool(port).execute("""{"session_id":"nope"}""", context)
        assertTrue(result.isError)
        assertTrue(result.content.contains("nope"))
    }

    @Test
    fun `close 存在的会话成功并报剩余数`() = runTest {
        val port = portWithSession()
        val result = CloseTerminalSessionTool(port).execute("""{"session_id":"s1"}""", context)
        assertFalse(result.isError)
        assertTrue(result.content.contains("剩余会话"))
        assertEquals(listOf("s1"), port.closed)
    }

    // ── screen ────────────────────────────────────────────────

    @Test
    fun `screen 返回屏幕内容`() = runTest {
        val port = portWithSession()
        port.screenText = "root@praxis:~# "
        val result = GetTerminalScreenTool(port).execute("""{"session_id":"s1"}""", context)
        assertFalse(result.isError)
        assertTrue(result.content.contains("root@praxis"))
    }

    @Test
    fun `screen 无内容时也给出可读结果而不是空串`() = runTest {
        val port = portWithSession()
        port.screenText = ""
        val result = GetTerminalScreenTool(port).execute("""{"session_id":"s1"}""", context)
        assertFalse(result.isError)
        assertTrue(result.content.contains("(空)"))
    }

    // ── control 序列 ──────────────────────────────────────────

    @Test
    fun `control 序列是真实字节而不是字面转义`() {
        // 这三个最容易写错成四个字符 "\u0003" 之类。
        assertEquals(1, TerminalControlSequence.CTRL_C.length)
        assertEquals(3, TerminalControlSequence.CTRL_C[0].code)
        assertEquals(4, TerminalControlSequence.CTRL_D[0].code)
        assertEquals(13, TerminalControlSequence.ENTER[0].code)
        // 方向键是 CSI 序列，长度 3。
        assertEquals(3, TerminalControlSequence.ARROW_UP.length)
        assertEquals('\u001b', TerminalControlSequence.ARROW_UP[0])
        assertEquals('A', TerminalControlSequence.ARROW_UP[2])
    }

    @Test
    fun `control 从模型字符串还原`() {
        assertEquals(TerminalControl.CtrlC, TerminalControl.fromWire("ctrl_c"))
        assertEquals(TerminalControl.Enter, TerminalControl.fromWire("ENTER"))
        assertEquals(TerminalControl.ArrowUp, TerminalControl.fromWire(" up "))
        assertNull(TerminalControl.fromWire("-"))
        assertNull(TerminalControl.fromWire(""))
        assertNull(TerminalControl.fromWire(null))
        assertNull(TerminalControl.fromWire("nope"))
    }

    // ── 交互模式判定 ──────────────────────────────────────────

    @Test
    fun `交互模式识别编辑器与监控器`() {
        assertTrue(TerminalSessionCommand.classifyInteractive("vim a.txt"))
        assertTrue(TerminalSessionCommand.classifyInteractive("sudo apt install nmap"))
        assertTrue(TerminalSessionCommand.classifyInteractive("top"))
        assertTrue(TerminalSessionCommand.classifyInteractive("/usr/bin/htop"))
    }

    @Test
    fun `普通命令不算交互`() {
        assertFalse(TerminalSessionCommand.classifyInteractive("ls -la"))
        assertFalse(TerminalSessionCommand.classifyInteractive("echo hi"))
        assertFalse(TerminalSessionCommand.classifyInteractive(""))
        // 后台运行不算交互（会被 shell 立刻放回提示符）。
        assertFalse(TerminalSessionCommand.classifyInteractive("python3 server.py &"))
    }

    @Test
    fun `sudo 前缀会被跳过再判定程序名`() {
        // sudo vim → 程序名是 vim，不是 sudo。
        assertTrue(TerminalSessionCommand.classifyInteractive("sudo vim /etc/hosts"))
    }

    // ── 参数解析 ──────────────────────────────────────────────

    @Test
    fun `空 JSON 与空串都能安全解析`() = runTest {
        val port = portWithSession()
        // "" 与 "{}" 都应落到默认会话 + 报缺 command，而不是抛异常。
        val a = ExecuteInTerminalSessionTool(port).execute("", context)
        val b = ExecuteInTerminalSessionTool(port).execute("{}", context)
        assertTrue(a.isError)
        assertTrue(b.isError)
    }

    @Test
    fun `非法 JSON 不抛出而是转成错误结果`() = runTest {
        val port = portWithSession()
        val result = ExecuteInTerminalSessionTool(port).execute("{not json", context)
        assertTrue(result.isError)
        assertTrue(result.content.contains("JSON"))
        assertEquals("非法 JSON 不该下发到端口", 0, port.executed.size)
    }
}