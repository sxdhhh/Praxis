package dev.praxis.data.linux

import android.content.Context
import android.util.Log
import dev.praxis.core.linux.LinuxEnvironment
import dev.praxis.core.linux.LinuxDistro
import dev.praxis.core.linux.LinuxExecResult
import dev.praxis.core.linux.ProotCommand
import dev.praxis.core.linux.TerminalChunk
import dev.praxis.core.linux.TerminalControl
import dev.praxis.core.linux.TerminalControlSequence
import dev.praxis.core.linux.TerminalSessionCommand
import dev.praxis.core.linux.TerminalSessionInfo
import dev.praxis.core.linux.TerminalSessionPort
import dev.praxis.feature.settings.env.LinuxEnvPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 多会话 proot 终端（`:app` 侧真实实现）。
 *
 * ## 与 [ProotEnvPort] 的关系
 *
 * | | `ProotEnvPort` | 本类 |
 * |---|---|---|
 * | 用途 | 设置页：装环境、跑一次性命令、起常驻服务 | AI 工具：7 个会话工具 |
 * | 会话 | `startSession(args)` 给调用方自己拼参数 | 内置 `create/execute/sendInput/screen` |
 * | 状态 | 无（每次现拼） | **有**：每个会话一个常驻 `sh`，`cd` 会保留 |
 *
 * 两者共用同一个 `ProotEnvPort` 实例来拿 `LinuxEnvironment`（rootfs 路径、
 * proot 二进制、镜像源），避免「设置页显示已安装、工具层说没装」。
 *
 * ## 为什么每个会话是「一个常驻 sh + 哨兵行」而不是「每次新起进程」
 *
 * 新起进程的方案里 `cd /tmp` 结束后目录就丢了，`export` 也不会保留，
 * 表现出来就是「AI 每次都要重新 cd」。Operit 用的是完整 PTY（`Pty.kt` +
 * `libpty.so` + `AnsiTerminalEmulator` 946 行），那是「真终端」的做法，
 * 但需要 NDK 构建与完整的 ANSI 屏幕解析。
 *
 * 本实现用**哨兵行协议**达到「有状态」这一核心目标：
 *
 * ```
 * 下发：{命令} ; echo "__PRAXIS_DONE__:$?"
 * 读回：直到读到 "__PRAXIS_DONE__:<exit>" 这一行为止
 * 顺带：命令里前置 `pwd > /tmp/.praxis_cwd`，读回后刷新当前目录
 * ```
 *
 * 这是「命令 + 哨兵」的经典做法，代价是：
 *  - 拿不到真实屏幕（`vim`/`top` 的全屏绘制会被当作普通字符流）；
 *  - 交互程序会阻塞读取（所以 [TerminalSessionCommand.classifyInteractive]
 *    提前识别，并在超时后强制中断）。
 *
 * 优点是不需要 NDK：`ProcessBuilder` + stdin/stdout 就够了。
 * 真 PTY 作为后续增量（`Pty.kt` + `pty.c` 已在参考实现里验证过）。
 */
class ProotTerminalSessionPort(
    private val context: Context,
    /**
     * 环境来源。**必须是同一个实例**（与设置页共用），
     * 否则「AI 装好的环境设置页看不到」。
     */
    private val envPort: LinuxEnvPort,
    /** 默认发行版。 */
    private val distroProvider: () -> LinuxDistro = { LinuxDistro.Kali },
) : TerminalSessionPort {

    /** 一个活着的会话。 */
    private data class Live(
        val info: TerminalSessionInfo,
        val process: Process,
        val writer: Writer,
        val output: ScreenTap,
        /** 有值说明用的是真 PTY；null 说明降级到管道。 */
        val pty: dev.praxis.core.terminal.Pty? = null,
    )

    private val TAG = "ProotTermSession"

    /**
     * 「会话已死」的内部标记。
     *
     * 泵线程在流结束时把它塞进 [ScreenTap]；[readUntilSentinel] 见到它就能
     * 立刻返回明确原因，而不是一直等到超时。
     *
     * 为什么用不可见字符包裹：终端输出可能是任意字节，用普通文本（如 `[dead]`）
     * 会与用户命令的真实输出撞车，于是把正常内容误判成会话死亡。
     * NUL 包裹的固定标记在真实输出里不会出现。
     */
    private val DEAD_MARKER = "\u0000PRAXIS_SESSION_DEAD\u0000"

    /**
     * 「回车」标记。
     *
     * ## 为什么需要一个单独标记而不是直接传 `"\r"`
     *
     * [ScreenTap] 与 `TerminalChunk` 走的是「行」语义，`"\r"` 混在
     * 文本行里很容易被下游再次 `trim()` 掉。用一个不可能出现在终端输出里的
     * 私有标记，渲染层才能明确区分「这一行是回车」与「这一行内容里带反斜杠 r」。
     */
    private val CR_MARKER = "\u0000PRAXIS_CR\u0000"

    /** 无行边界时最长积多少字符就先进一版，避免长时间无输出。 */
    private const val PARTIAL_FLUSH_CHARS = 2048

    private val live = ConcurrentHashMap<String, Live>()
    private val counter = AtomicInteger(1)

    /** 默认超时（秒），与 `core:linux` 的常量一致。 */
    private val defaultTimeout: Long = TerminalSessionCommand.DEFAULT_TIMEOUT_SECONDS

    override fun sessions(): List<TerminalSessionInfo> =
        // 显式标出 lambda 参数类型：`live` 是 `ConcurrentHashMap<String, Live>`，
        // 而 `Live` 是本文件内的 private data class。K2 在链式泛型调用里
        // 偶发推不出 `R`（报 Cannot infer type for type parameter 'R'），
        // 显式标注可消除这种不稳定。
        live.values.map { session: Live -> session.info }
            .sortedBy { info: TerminalSessionInfo -> info.name }

    override fun find(sessionId: String): TerminalSessionInfo? = live[sessionId]?.info

    override suspend fun create(name: String): TerminalSessionInfo = withContext(Dispatchers.IO) {
        // 幂等：同名已存在直接返回（照 Operit createOrGetSession）。
        live.values.firstOrNull { it.info.name == name && !it.info.closed }?.let { return@withContext it.info }

        val id = "t${counter.getAndIncrement()}"
        val env = envPort.defaultEnvironment(distroProvider())
        // 直接用 sessionArgs 起一个常驻交互 shell，**不套 `/bin/sh -c`**。
        // 套了之后 `-i` 会去判断 stdin 是否 tty，行为不稳定（见 sessionArgs 的说明）。
        val args = ProotCommand.sessionArgs(env, listOf("/bin/sh", "-i"))
        val argv = listOf(env.prootBinary.absolutePath) + args
        val tap = ScreenTap()

        // ① 优先真 PTY：vim/top 能用、Ctrl+C 生效、输出行缓冲（不攒 4KB）。
        //    窗口大小按手机常见列数给（80x24 会让 top 错位）。
        val pty = dev.praxis.core.terminal.Pty.start(
            command = argv.toTypedArray(),
            environment = ptyEnvironment(),
            workingDir = context.filesDir,
            rows = 60,
            cols = 80,
        )
        if (pty != null) {
            var deadReason: String? = null
            Thread(
                {
                    // ## 泵线程必须在 EOF 时记下「会话已死」（2026-10-25 修）
                    //
                    // 旧实现是 `readLine() ?: break`：流一断就静静退出循环，
                    // 什么都不留下。后果是后续 `execute()` 往 `item.writer`
                    // 写命令时抛 `Stream closed` —— 用户看到的是
                    // 「exit=-1 / Stream closed」，完全指不到「子进程早就退出了」。
                    //
                    // 这里在退出前跑一次 `waitFor` 拿退出码，把它记进 tap，
                    // 于是上层能把「会话已死，退出码 N」如实报给调用方。
                    pumpStream(pty.stdout, tap)
                    val code = runCatching { pty.process.waitFor() }.getOrNull()
                    deadReason = "会话进程已退出（退出码 ${code ?: "未知"}）。"
                    tap.offer(DEAD_MARKER)
                    Log.w(TAG, "PTY 会话 $id 已结束，退出码=$code")
                },
                "praxis-term-pty-$id",
            ).apply { isDaemon = true }.start()

            val info = TerminalSessionInfo(id = id, name = name, currentDirectory = "/root")
            val item = Live(
                info = info,
                process = pty.process,
                writer = OutputStreamWriter(pty.stdin),
                output = tap,
                pty = pty,
            )
            live[id] = item
            refreshCwd(item)

            // ## 启动后必须验证一次存活（2026-10-25 修）
            //
            // `Pty.start` 只保证 fork 成功、拿到 master fd；它**不知道**
            // proot 里的 shell 会不会立刻退出（rootfs 缺 shell、参数错、
            // 权限不足都会让它秒退）。
            //
            // 不验证的话：`create` 报「会话已就绪」，用户/模型接着发命令，
            // 每条都拿 `Stream closed`。这就是用户截图里的 t1/t2 现象 ——
            // 会话看起来建成了，实则是个空壳。
            //
            // 等 400ms 再判：proot 启动 + shell 初始化通常 100~200ms，
            // 给够了才不会把「还在启动」误判成「已死」。
            kotlinx.coroutines.delay(400)
            if (!pty.process.isAlive) {
                live.remove(id)
                runCatching { pty.destroy() }
                val why = deadReason ?: "会话进程启动后立即退出。"
                Log.e(TAG, "会话 $id 启动失败：$why")
                throw IllegalStateException(
                    "无法建立终端会话：$why\n" +
                        "常见原因：Linux 环境未安装完成、rootfs 缺 /bin/sh、" +
                        "或 proot 启动参数不适用于本机。可先在「Linux 环境」页确认状态。",
                )
            }
            return@withContext live[id]!!.info
        }

        // ② 降级：libpty 不可用（32 位设备 / CI 缺库）时用管道。
        //    功能不全但仍能跑 ls/apt/cat，好过整页崩掉。
        val pb = ProcessBuilder(argv).redirectErrorStream(true)
        applyEnv(pb, env)
        val process = pb.start()
        val writer = OutputStreamWriter(process.outputStream)
        // 后台泵：把输出同时喂给「屏幕缓冲」与「当前等待者」。
        // EOF 时同样补一条死亡标记（理由见上面 PTY 分支的注释）。
        Thread(
            {
                pumpStream(process.inputStream, tap)
                val code = runCatching { process.waitFor() }.getOrNull()
                Log.w(TAG, "管道会话 $id 已结束，退出码=$code")
                tap.offer(DEAD_MARKER)
            },
            "praxis-term-$id",
        ).apply { isDaemon = true }.start()

        // 管道分支同样要验证存活：没验证的话 proot 秒退时同样得到空壳会话。
        kotlinx.coroutines.delay(400)
        if (!process.isAlive) {
            live.remove(id)
            runCatching { process.destroyForcibly() }
            throw IllegalStateException(
                "无法建立终端会话：proot 进程启动后立即退出。" +
                    "可先在「Linux 环境」页确认状态。",
            )
        }

        val info = TerminalSessionInfo(
            id = id,
            name = name,
            currentDirectory = "/root",
        )
        val item = Live(info, process, writer, tap)
        live[id] = item
        refreshCwd(item)
        live[id]!!.info
    }

    override fun close(sessionId: String): Boolean {
        val item = live.remove(sessionId) ?: return false
        // 先发 exit：交互 shell 会自己收尾，比直接 kill 干净。
        runCatching { item.writer.write("exit\n"); item.writer.flush() }
        runCatching { item.process.waitFor(600, TimeUnit.MILLISECONDS) }
        // PTY 必须走自己的 destroy：它会先关 master fd（内核据此给子进程发 SIGHUP），
        // 再兜底 destroy 进程。只 destroy 进程的话 shell 可能还卡在 read() 上。
        item.pty?.destroy() ?: runCatching { item.process.destroyForcibly() }
        return true
    }

    override suspend fun execute(
        command: TerminalSessionCommand,
        onChunk: ((TerminalChunk) -> Unit)?,
    ): LinuxExecResult = withContext(Dispatchers.IO) {
        val item = live[command.sessionId]
            ?: return@withContext LinuxExecResult(-1, "会话 ${command.sessionId} 不存在。")

        // ## 写之前先确认会话还活着（2026-10-25 修「Stream closed」）
        //
        // 旧实现直接 `item.writer.write(script)`。子进程已经退出时，底层的
        // master fd 已关闭，写入抛 `java.io.IOException: Stream closed` ——
        // 用户与模型都只看到这一句，完全指不到「会话进程早就死了」。
        //
        // 这里先探活：死了就直接返回明确原因，并把这条死会话从表里摘掉，
        // 让调用方（以及模型）知道该重建会话，而不是反复在死会话上重试。
        val processAlive = runCatching { item.process.isAlive }.getOrDefault(false)
        if (!processAlive) {
            live.remove(command.sessionId)
            runCatching { item.pty?.destroy() ?: item.process.destroyForcibly() }
            return@withContext LinuxExecResult(
                -1,
                "会话 ${command.sessionId} 的进程已退出，无法执行命令。\n" +
                    "请先用 create_terminal_session 重建会话再试。",
            )
        }

        val interactive = TerminalSessionCommand.classifyInteractive(command.command)
        val timeout = if (command.timeoutSeconds > 0) command.timeoutSeconds else defaultTimeout

        // 哨兵里带上「写 cwd」：同一次往返顺手刷新目录，省掉一条额外命令。
        //
        // ## 为什么先存 `$?` 再跑 pwd（2026-10-29 修）
        //
        // 旧写法是：
        //
        // ```
        // <用户命令> ; pwd > /tmp/.praxis_cwd 2>/dev/null ; echo "SENTINEL:$?"
        // ```
        //
        // `$?` 只记住**紧邻的前一条**命令的退出码，而中间夹了 `pwd > ...`。
        // 于是哨兵拿到的永远是 `pwd` 的退出码（基本恒为 0）——
        // 用户跑 `apt install 不存在的包`、`cat 不存在`，界面一律报成功，
        // 模型也就照着「成功了」继续往下推。
        //
        // 修法：命令一结束立刻把 `$?` 落进变量，后面怎么折腾都不影响它。
        //
        // Kotlin 字符串里的 `$` 会当模板插值，所以是 `\$?` / `\$__praxis_rc`。
        val script = buildString {
            append(command.command)
            append(" ; __praxis_rc=\$? ; ")
            append("pwd > /tmp/.praxis_cwd 2>/dev/null ; ")
            append("echo \"$SENTINEL:\$__praxis_rc\"")
            append("\n")
        }

        val collected = StringBuilder()
        val readResult = try {
            readUntilSentinel(
                item = item,
                script = script,
                timeoutSeconds = timeout,
                onChunk = { text ->
                    collected.append(text)
                    onChunk?.invoke(
                        TerminalChunk(
                            sessionId = command.sessionId,
                            commandId = command.commandId,
                            text = text,
                        ),
                    )
                },
            )
        } catch (e: Exception) {
            return@withContext LinuxExecResult(-1, collected.toString() + "\n" + (e.message ?: "执行失败"))
        }

        val exit = readResult.exitCode
        val timedOut = readResult.timedOut && exit == TIMEOUT_EXIT_CODE

        // 会话已死：直接返回明确原因，并且**不要**把这条会话写回 `live`
        // （写回去会让它继续以「活着」的身份被复用，下一条命令又是同样的失败）。
        readResult.failureReason?.let { reason ->
            live.remove(command.sessionId)
            runCatching { item.pty?.destroy() ?: item.process.destroyForcibly() }
            return@withContext LinuxExecResult(
                exitCode = -1,
                output = collected.toString().trimEnd() + "\n" + reason +
                    "\n请先用 create_terminal_session 重建会话再试。",
                timedOut = false,
            )
        }

        // 刷新 cwd 与交互状态。
        val cwd = readCwdFile() ?: item.info.currentDirectory
        val updated = item.info.copy(
            currentDirectory = cwd,
            lastExitCode = exit,
            isInteractiveMode = interactive && timedOut,
            interactivePrompt = if (interactive) command.command.take(80) else "",
        )
        live[command.sessionId] = item.copy(info = updated)

        if (timedOut) {
            // 交互程序把终端占住了：发 Ctrl+C 把它打出来，否则后续命令全会喂给它。
            runCatching {
                item.writer.write(TerminalControlSequence.CTRL_C)
                item.writer.flush()
            }
        }

        LinuxExecResult(
            exitCode = exit,
            output = collected.toString().trimEnd(),
            timedOut = timedOut,
        )
    }

    override fun sendInput(
        sessionId: String,
        input: String?,
        control: TerminalControl?,
    ): Boolean {
        val item = live[sessionId] ?: return false
        if (item.info.closed) return false
        val payload = if (control != null) {
            TerminalControlSequence.of(control)
        } else {
            input ?: return false
        }
        return runCatching {
            item.writer.write(payload)
            item.writer.flush()
            true
        }.getOrDefault(false)
    }

    /**
     * 调 `ioctl(TIOCSWINSZ)` 告诉内核新尺寸。
     *
     * 只有真 PTY 能调（`pty != null`）。管道降级的会话没有窗口概念，
     * 返回 false —— 不假装成功，否则 UI 会以为设置生效了。
     */
    override fun resize(sessionId: String, rows: Int, cols: Int): Boolean {
        val item = live[sessionId] ?: return false
        if (item.info.closed) return false
        val pty = item.pty ?: return false
        if (rows <= 0 || cols <= 0) return false
        return pty.resize(rows, cols)
    }

    override fun screen(sessionId: String, maxLines: Int): String? {
        val item = live[sessionId] ?: return null
        return item.output.snapshot(maxLines)
    }

    // ── 内部：哨兵协议 ────────────────────────────────────────

    /**
     * 一次「下发命令 → 读到哨兵」的结果。
     *
     * [failureReason] 只在**会话已死**这类硬失败时非空：它与「命令返回非零」
     * 是两件不同的事，必须能分开 —— 前者说明会话本身不能再用（要重建），
     * 后者只说明这条命令失败（会话仍可用）。
     */
    private class ReadOutcome(
        val exitCode: Int,
        val timedOut: Boolean,
        val failureReason: String? = null,
    )

    /**
     * 下发 [script] 并一直读到哨兵行。
     *
     * ## 为什么必须按行读而不是按字节
     *
     * 命令输出可能包含任意二进制（`cat` 一个 elf）。按行读至少能在
     * 遇到换行时切成可控片段；按字节读会让回调被调用几万次。
     *
     * ## 超时之后的处理
     *
     * 超时说明前台进程还占着终端（vim/top/apt 确认框）。此时**不销毁会话**
     * —— 会话里还有用户可能想恢复的工作。只返回 [TIMEOUT_EXIT_CODE]，
     * 由 `execute` 发 Ctrl+C 把它打出来。
     */
    private suspend fun readUntilSentinel(
        item: Live,
        script: String,
        timeoutSeconds: Long,
        onChunk: ((String) -> Unit)? = null,
    ): ReadOutcome = withContext(Dispatchers.IO) {
        item.writer.write(script)
        item.writer.flush()

        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000

        while (true) {
            if (System.currentTimeMillis() > deadline) {
                return@withContext ReadOutcome(TIMEOUT_EXIT_CODE, true)
            }
            val line = item.output.pollLine(deadline) ?: continue

            // ## 会话已死的显式信号（2026-10-25 修）
            //
            // 旧实现只有「哨兵」与「超时」两种出口。子进程一死，泵线程
            // 因 EOF 退出、屏幕上再也不会出现哨兵，于是这条命令要**干等
            // 到完整超时**（默认 600 秒）才返回 —— 用户看到的就是
            // 「发一条 echo 卡住很久，然后报失败」。
            //
            // 泵线程现在会在 EOF 时塞一条死标；这里见到就立刻返回，
            // 并带上真实的退出原因，而不是让调用方猜。
            if (line.contains(DEAD_MARKER)) {
                val why = "会话进程已退出（终端流已关闭），命令未执行完。"
                return@withContext ReadOutcome(
                    exitCode = -1,
                    timedOut = false,
                    failureReason = why,
                )
            }

            if (line.startsWith(SENTINEL)) {
                val code = line.removePrefix(SENTINEL).removePrefix(":").trim().toIntOrNull() ?: 0
                return@withContext ReadOutcome(code, false)
            }
            onChunk?.invoke(line + "\n")
        }
        @Suppress("UNREACHABLE_CODE")
        ReadOutcome(0, false)
    }

    /** 读 `/tmp/.praxis_cwd`（容器内绝对路径对应宿主上的位置）。 */
    private fun readCwdFile(): String? = runCatching {
        val env = envPort.defaultEnvironment(distroProvider())
        val hostFile = File(env.rootfsDir, "tmp/.praxis_cwd")
        if (hostFile.isFile) hostFile.readText().trim().takeIf { it.startsWith("/") } else null
    }.getOrNull()

    /** 后台泵：把输出流逐行送进 [ScreenTap]。 */
    private fun pumpStream(input: java.io.InputStream, tap: ScreenTap) {
        runCatching {
            // ## 为什么不再用 BufferedReader.readLine()（2026-10-29 修）
            //
            // 旧实现是 `while (true) { val line = r.readLine() ?: break; tap.offer(line) }`，
            // 有两个真问题：
            //
            // 1. **无换行就不返回**。`readLine()` 必须遇到 `\n` / `\r\n`
            //    才交出数据，而进度条（`wget` / `apt` / `pip`）、不带换行的
            //    `printf`、`top` 的首次全屏刷新全都不带换行 —— 这些输出
            //    会**卡在 BufferedReader 8KB 缓冲区里**，一直到进程结束或
            //    缓冲满才突然涌出。用户看到的是「命令在跑，屏幕不动」。
            // 2. **吃掉 `\r`**。`readLine()` 把 `\r` 当行结束符并丢弃，
            //    而进度条原地刷新正是靠「回到行首再覆盖」（`进度 30%\r进度 40%`）。
            //    丢掉 `\r` 之后，原本应该覆盖的一行会变成新的一行，
            //    屏幕上堆出几百行垃圾。
            //
            // 现在改成按字节读、`\n` 与 `\r` **都当行边界**，并且两者
            // 都作为独立行交给 [ScreenTap]（不丢弃）—— 下游的
            // `TerminalBuffer` 才知道「这一行要回车重写」而不是「新起一行」。
            val buffer = ByteArray(4096)
            val line = StringBuilder()
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                var i = 0
                while (i < count) {
                    val b = buffer[i].toInt() and 0xFF
                    when (b) {
                        0x0A -> { // LF
                            tap.offer(line.toString())
                            line.setLength(0)
                        }
                        0x0D -> { // CR：先交出当前内容，再单独交一个空行表示「回车」
                            tap.offer(line.toString())
                            line.setLength(0)
                            tap.offer(CR_MARKER)
                        }
                        else -> line.append(b.toChar())
                    }
                    i++
                }
                // 缓冲里可能一个行边界都没有（比如一整块进度条文字），
                // 此时先进一版，避免用户长时间看不到东西；
                // 行号不丢：`line` 继续累积，下一次遇到边界时一起交。
                if (line.length >= PARTIAL_FLUSH_CHARS) {
                    tap.offer(line.toString())
                    line.setLength(0)
                }
            }
            if (line.isNotEmpty()) tap.offer(line.toString())
        }
    }

    /**
     * PTY 子进程的环境变量。
     *
     * ## 为什么单独一份，不复用 [applyEnv]
     *
     * `applyEnv` 是给 `ProcessBuilder` 用的（它自带父进程环境副本，只需增删）。
     * PTY 走 `execve`，**环境是全新的**：不给 `PATH` 就连 `ls` 都找不到。
     * 所以这里必须给一份完整、自洽的环境。
     *
     * ## 三个关键变量
     *
     * - `TERM=xterm-256color`：不给的话 ncurses 程序（vim/top/htop）会拒绝启动，
     *   或者只画 ASCII 边框。256color 让 `ls --color` 与语法高亮都能出真彩。
     * - `PROOT_TMP_DIR` 指向 `cacheDir`：proot 要在宿主侧建临时文件，
     *   不指的话会尝试写 `/tmp`（安卓上不可写），表现为 proot 静默失败。
     * - **不能有 `LD_LIBRARY_PATH`**：继承 Android 的库路径会让容器里的
     *   程序去加载 bionic 库，`curl` 会报 `cannot locate symbol "EVP_MD_CTX_create"`。
     */
    private fun ptyEnvironment(): Map<String, String> = buildMap {
        put("TERM", "xterm-256color")
        put("HOME", "/root")
        put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        put("LANG", "C.UTF-8")
        put("PROOT_TMP_DIR", context.cacheDir.absolutePath)
        put("PROOT_NO_SECCOMP", "1")
    }

    /**
     * 探一次真实 cwd 并回写。
     *
     * ## 为什么是「发命令 + 睡 150ms + 读文件」而不是走完整哨兵往返
     *
     * 建会话时还没有任何输出消费者在等待；走 [readUntilSentinel] 会把
     * 建会话变成一个可能要等很久的阻塞操作（shell 启动慢时更明显）。
     * 这里只要求「尽力刷新」，拿不到就保留 `/root` 默认值 ——
     * 下一次 `execute` 会通过哨兵里的 `pwd >` 自动纠正。
     */
    private fun refreshCwd(item: Live) {
        runCatching {
            item.writer.write("pwd > /tmp/.praxis_cwd 2>/dev/null\n")
            item.writer.flush()
            Thread.sleep(150)
            readCwdFile()?.let { cwd ->
                live[item.info.id] = item.copy(info = item.info.copy(currentDirectory = cwd))
            }
        }
    }

    /** 给子进程设环境变量（必须清掉 Android 的库路径）。 */
    private fun applyEnv(pb: ProcessBuilder, env: LinuxEnvironment) {
        val e = pb.environment()
        e["PROOT_TMP_DIR"] = context.cacheDir.absolutePath
        e["PROOT_NO_SECCOMP"] = "1"
        e["HOME"] = "/root"
        e["TERM"] = "xterm-256color"
        e.remove("LD_LIBRARY_PATH")
        e.remove("LD_PRELOAD")
    }

    private companion object {
        /** 哨兵前缀。带随机后缀意义不大：同一会话内串行执行，不会撞。 */
        const val SENTINEL = "__PRAXIS_DONE__"

        /** 超时伪退出码，与 coreutils `timeout` 一致。 */
        const val TIMEOUT_EXIT_CODE = 124
    }
}

/**
 * 会话输出的环形屏幕缓冲。
 *
 * ## 为什么单独一个类
 *
 * 「把 stdout 交给等待者」与「保留最近若干行供 `screen()` 查看」是两件
 * 不同的事：前者是一次性消费，后者要长期留。混在 `execute` 里会导致
 * 每次执行完屏幕就被清空，`get_terminal_session_screen` 永远返回空。
 *
 * ## 为什么有上限
 *
 * 一条 `yes` 能让输出无限增长。保留最后 [MAX_LINES] 行足够回答
 * 「现在屏幕上是什么」，而内存有确定上限。
 */
private class ScreenTap {
    private val lines = ArrayDeque<String>()
    /**
     * 互斥对象。
     *
     * ## 为什么是 `java.lang.Object` 而不是 `Any()`
     *
     * `wait()` / `notifyAll()` 是 `Object` 的 final 方法，Kotlin 的 `Any`
     * **不映射这两个**（`Any` 只有 equals/hashCode/toString）。
     * 声明成 `Any()` 时下面两处调用直接编译失败：
     *
     * ```
     * lock.notifyAll()   → Unresolved reference 'notifyAll' on receiver of type 'Any'
     * lock.wait(...)     → Unresolved reference 'wait' on receiver of type 'Any'
     * ```
     *
     * 显式写成 `java.lang.Object` 才能用这两个方法。
     */
    private val lock: Any = java.lang.Object()

    /**
     * 待读取的行（哨兵协议消费）。
     *
     * ## 为什么它也必须有上限（2026-10-29 修）
     *
     * [lines] 早就限了 [MAX_LINES]，但这个队列**漏了**。两者职责不同：
     * `lines` 是屏幕快照（永远有读者），`pending` 是给
     * [readUntilSentinel] 的顺序消费队列。
     *
     * 一旦**没有**协程在消费（用户在终端页看输出、模型没在跑命令；
     * 或命令已超时返回、不再有人 poll），泵线程依旧在无限 `offer`。
     * `yes` 每秒几十万行，一分钟就能把堆吃光 —— 这是真 OOM，不是泄漏。
     *
     * 上限取 [MAX_LINES] 的两倍：正常情况下 `readUntilSentinel` 会立刻
     * 消费，永远撞不到上限；真撞到时说明没人读，丢掉最旧的才对
     * （保留最新的，因为它们才可能包含哨兵）。
     */
    private val pending = ArrayDeque<String>()

    fun offer(line: String) {
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
            pending.addLast(line)
            // 见 pending 的 KDoc：无消费者时会无限涨。
            while (pending.size > MAX_LINES * 2) pending.removeFirst()
            (lock as java.lang.Object).notifyAll()
        }
    }

    /**
     * 取一行，最多等到 [deadlineMs] 这一时刻。
     *
     * 返回 null 表示这一轮没拿到（调用方继续轮询并检查自己的 deadline）。
     */
    fun pollLine(deadlineMs: Long): String? {
        synchronized(lock) {
            while (pending.isEmpty()) {
                val wait = deadlineMs - System.currentTimeMillis()
                if (wait <= 0) return null
                runCatching { (lock as java.lang.Object).wait(minOf(wait, 200L)) }
            }
            return pending.removeFirst()
        }
    }

    /** 最近 [maxLines] 行的快照（旧 → 新）。 */
    fun snapshot(maxLines: Int): String {
        synchronized(lock) {
            val take = maxLines.coerceIn(1, MAX_LINES)
            return lines.takeLast(take).joinToString("\n")
        }
    }

    private companion object {
        /** 屏幕缓冲上限。2000 行 ≈ 一屏 vim + 滚动回看。 */
        const val MAX_LINES = 2000
    }
}