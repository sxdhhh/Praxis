package dev.praxis.data.linux

import android.content.Context
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
            Thread({ pumpStream(pty.stdout, tap) }, "praxis-term-pty-$id")
                .apply { isDaemon = true }.start()
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
            return@withContext live[id]!!.info
        }

        // ② 降级：libpty 不可用（32 位设备 / CI 缺库）时用管道。
        //    功能不全但仍能跑 ls/apt/cat，好过整页崩掉。
        val pb = ProcessBuilder(argv).redirectErrorStream(true)
        applyEnv(pb, env)
        val process = pb.start()
        val writer = OutputStreamWriter(process.outputStream)
        // 后台泵：把输出同时喂给「屏幕缓冲」与「当前等待者」。
        Thread({ pumpStream(process.inputStream, tap) }, "praxis-term-$id").apply { isDaemon = true }.start()

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

        val interactive = TerminalSessionCommand.classifyInteractive(command.command)
        val timeout = if (command.timeoutSeconds > 0) command.timeoutSeconds else defaultTimeout

        // 哨兵里带上「写 cwd」：同一次往返顺手刷新目录，省掉一条额外命令。
        val script = buildString {
            append(command.command)
            append(" ; ")
            append("pwd > /tmp/.praxis_cwd 2>/dev/null ; ")
            append("echo \"$SENTINEL:\$?\"")
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

    override fun screen(sessionId: String, maxLines: Int): String? {
        val item = live[sessionId] ?: return null
        return item.output.snapshot(maxLines)
    }

    // ── 内部：哨兵协议 ────────────────────────────────────────

    private class ReadOutcome(val exitCode: Int, val timedOut: Boolean)

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
            BufferedReader(InputStreamReader(input)).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    tap.offer(line)
                }
            }
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

    /** 待读取的行（哨兵协议消费）。 */
    private val pending = ArrayDeque<String>()

    fun offer(line: String) {
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
            pending.addLast(line)
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