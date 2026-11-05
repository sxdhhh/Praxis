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
import kotlinx.coroutines.channels.awaitClose
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
    private val partialFlushChars = 2048

    private val live = ConcurrentHashMap<String, Live>()
    private val counter = AtomicInteger(1)

    /**
     * 正在被**主动关闭**的会话 id。
     *
     * ## 为什么需要它（2026-11-01 修「read interrupted by close() on another thread」）
     *
     * 关会话时 `Pty.destroy()` 会在另一个线程上关掉 master fd，正阻塞在
     * `read()` 里的泵线程于是抛 `IOException("read interrupted by close()
     * on another thread")`。那条异常是**预期**的（用户就是让它结束），
     * 但它与「会话真的出故障」长得一样。
     *
     * 所以关闭前先在这里登记 id，泵线程收尾时检查：
     *  - id 在集合里 → 静默结束（不报「会话读取结束：…」）；
     *  - 不在 → 照旧把原因报给用户，真正的 IO 故障仍然可见。
     *
     * 用并发集合而不是普通 Set：`close` 在 UI 线程，泵线程在 IO 线程。
     */
    private val closing: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

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
        // ## 终端连的必须是「容器里的登录 shell」，不是宿主的 /bin/sh（2026-11-03 修）
        //
        // 旧实现是 `sessionArgs(env, listOf("/bin/sh", "-i"))` —— 两个错误：
        //
        // **错误 1：硬编码 `/bin/sh`，绕过了容器内的登录 shell 探测。**
        // `loginArgs` 尾部本来会调 `loginShell(env)` 选 rootfs 里实际存在的
        // bash（优先）或 sh；而 `sessionArgs` 的职责正是把尾部两个元素
        // **替换**成调用方给的 program。于是容器里明明装了 bash，用户拿到的
        // 却被钉死在 `/bin/sh` —— 没有登录 shell 的 PATH、别名、提示符，
        // `ls`/`apt` 甚至可能因 PATH 不全而找不到。
        // 用户原话：「终端连接的应该是内置的 linux 系统而不是 /bin/sh」。
        //
        // **错误 2：`-i` 在非 tty 路径上刷 stty 报错。**
        // `sh -i` 强制交互模式，交互 shell 会 `tcgetattr(stdin)` 读终端属性；
        // 一旦落到下面的管道降级分支，stdin 不是 tty，于是输出里夹一行
        // `stty: 'standard input': Inappropriate ioctl for device`。
        // 这正是用户看到的那句。
        //
        // 改用 `loginArgs(env)`（尾部是 `loginShell(env) + "-l"`）：
        //   - `-l` 是**登录 shell** 语义，它自己判断要不要交互，
        //     不需要 `-i` 强行指定，因此不会在非 tty 上刷 stty 错误；
        //   - shell 从 rootfs 里探测，容器里有什么就用什么；
        //   - 探测规则只有 `loginShell` 一处，不会在这儿写第二份。
        // ## 等价简化（2026-11-03 自查）
        //
        // 上一版写的是 `sessionArgs(env, loginTail(env))`。
        // 而 `sessionArgs(env, X) = loginArgs(env).dropLast(2) + X`，
        // `loginTail(env)` 又恰好等于 `loginArgs(env)` 的尾部两个元素 ——
        // 去掉再加回去，结果就是 `loginArgs(env)` 本身。
        //
        // 功能上两者等价，但绕了一圈：读代码的人得在脑子里做一次代入
        // 才能确认「终端用的确实是容器里的登录 shell」。核心路径上
        // 不该有这种绕法，直接调 `loginArgs`。
        val args = ProotCommand.loginArgs(env)
        // ## 铺文件只做一次，`PROOT_LOADER` 与别名目录都从它来（2026-11-01 修）
        //
        // 之前 `ptyEnvironment` 内部自己调了一次 `prootLoaderFileFor()`，
        // 而 `launchArgs` 又按 `executable.parentFile` 推别名路径 ——
        // 两条来源不同，别名目录对不上，修复静默失效。
        //
        // 现在先把 loader 铺好，环境变量与 argv 都吃同一个结果：
        // loader 的**父目录**就是别名所在目录。
        val loader = prootLoaderFileFor()
        val envVars = ptyEnvironment(env, loader)
        // 见 [ProotCommand.launchArgs]：只有「执行 nativeLibraryDir 里的
        // `libproot.so`」一个候选。两条启动器（`forkpty` 与 `ProcessBuilder`）
        // 必须用同一份规则 —— 各写一份正是历史上「PTY 能用、降级后全崩」的来源。
        val argvCandidates = ProotCommand.launchArgs(
            executable = env.prootBinary,
            arguments = args,
        )
        val tap = ScreenTap()

        // ① 优先真 PTY：vim/top 能用、Ctrl+C 生效、输出行缓冲（不攒 4KB）。
        //    窗口大小按手机常见列数给（80x24 会让 top 错位）。
        //    候选 argv 逐个试：第一个是别名路径，起不来（fork 后立刻死）就换下一个。
        var pty: dev.praxis.core.terminal.Pty? = null
        // 每条候选 PTY 的失败原因。全部失败时会写进终端，让用户看到为什么降级。
        val ptyFailureReasons = mutableListOf<String>()
        for (argv in argvCandidates) {
            val started = dev.praxis.core.terminal.Pty.start(
                command = argv.toTypedArray(),
                environment = envVars,
                workingDir = context.filesDir,
                rows = 60,
                cols = 80,
            ) ?: break
            // 立刻死掉的会话不值得留着：先判活 200ms，活的才采纳。
            // 这里比 create() 末尾的 400ms 更早，只为决定「要不要换下一个候选」。
            java.lang.Thread.sleep(200)
            if (started.process.isAlive) {
                Log.i(TAG, "PTY 会话建立成功 pid=${started.pid} argv[0]=${argv.firstOrNull()}")
                pty = started
                break
            }
            // ## 丢弃也要留证据（2026-11-03 真机实证后补）
            //
            // 真机现象：终端一进就报 `can't access tty`，而 logcat 一条都没有。
            // 原因就在这里 —— PTY 子进程 200ms 内死掉，被静默丢弃、降级到管道，
            // 全程不留痕。用户和我都无从知道到底是 execve 失败、还是权限问题。
            //
            // 退出码要取出来：127 是 pty.c 里 execve/chdir 失败，
            // 其它值多半是 shell 自己退出。这条日志能直接区分两类原因。
            val exitNow = runCatching { started.process.waitFor() }.getOrNull()
            Log.w(
                TAG,
                "PTY 子进程 200ms 内已退出（exit=$exitNow），丢弃该候选并降级到管道；" +
                    "argv[0]=${argv.firstOrNull()}"
            )
            // ## 把原因**留下来**，后面要写进终端（2026-11-03，P7 待办③）
            //
            // 上面那条 Log.w 只有 logcat 能看到。用户看到的却是
            // 「终端能敲命令但 vim/top 打不开、Ctrl+C 不生效」——
            // 完全不知道系统已经悄悄降级到了管道。
            //
            // 127 是 pty.c 里 execve/chdir 失败；其它值多半是 shell 自己退出。
            // 把它记下来，降级时一并显示，用户与模型都能看到真实原因。
            ptyFailureReasons += "argv[0]=${argv.firstOrNull()} 退出码=$exitNow"
            runCatching { started.destroy() }
        }
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
                    //
                    // ## 流被别的线程关掉是**正常结束**，不是故障（2026-11-01 修）
                    //
                    // 用户在终端页关掉标签、或者切走页面触发 `close(id)` 时，
                    // `close` 会调 `pty.destroy()` 关掉 master fd。此刻如果本线程
                    // 正阻塞在 `read()` 上，JDK 会抛：
                    //
                    //     java.io.IOException: read interrupted by close() on another thread
                    //
                    // 旧实现把它原样报成「会话读取结束：read interrupted by close()
                    // on another thread」—— 用户看到容器「跑着跑着结束了」，
                    // 而实际上那是他自己关的。正常关闭不该产生错误文案。
                    //
                    // 判据：只要会话已被标记为关闭，或流是被自己关的，
                    // 这条异常就是预期的。**不吞其它异常**（真正的 IO 故障仍要可见）。
                    val closedByOwner = runCatching { pumpStream(pty.stdout, tap) }
                    if (!isExpectedShutdown(closedByOwner.isFailure, closing.contains(id))) {
                        Log.w(TAG, "PTY 会话 $id 读取中断：${closedByOwner.exceptionOrNull()?.message}")
                    }
                    // 读线程自己收尾：master fd 不关会漏一对 PTY fd（见 Pty 的 KDoc）。
                    pty.closeMasterIfOwner()
                    val code = runCatching { pty.process.waitFor() }.getOrNull()
                    deadReason = "会话进程已退出（退出码 ${code ?: "未知"}）。"
                    tap.offer(DEAD_MARKER)
                    // 关闭登记在这里才摘：见 close() 的 finally —— 那之后再没人读它，
                    // 提前摘会让「关标签页」重新变成一条伪故障日志。
                    closing.remove(id)
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
        //
        // ## 降级必须让用户看见（2026-11-03，P7 待办③）
        //
        // 旧实现降级时**一声不响**：终端照样出提示符、照样能敲命令，
        // 只是 vim/top 打不开、Ctrl+C 不生效、输出延迟。
        // 用户只会觉得「这终端怎么怪怪的」，无从知道系统已经退到了管道。
        //
        // 这里在管道会话开好后，往输出流头里写一条醒目提示 + 收集到的
        // 每条候选的退出码。这样：
        //   - 用户看到「已降级到管道模式」就知道真因；
        //   - 退出码 127 = execve/chdir 失败（pty.c 约定），
        //     其它值 = shell 自己退出，两者能直接区分。
        //
        // 候选 argv 同样逐个试：这里没有 200ms 预判的机会（ProcessBuilder 要
        // 等到 400ms 存活检查才知道），所以只对「启动阶段就抛异常」的情形换下一个。
        val argv = argvCandidates.first()
        val pb = ProcessBuilder(argv).redirectErrorStream(true)
        applyEnv(pb, env)
        val process = pb.start()
        val writer = OutputStreamWriter(process.outputStream)
        // 后台泵：把输出同时喂给「屏幕缓冲」与「当前等待者」。
        // EOF 时同样补一条死亡标记（理由见上面 PTY 分支的注释）。
        Thread(
            {
                // 与 PTY 分支同一条规则：主动关闭时读被打断是预期，不报错；
                // 其它异常照旧打日志（否则会话静默死掉，调用方只能等到超时）。
                val read = runCatching { pumpStream(process.inputStream, tap) }
                if (!isExpectedShutdown(read.isFailure, closing.contains(id))) {
                    Log.w(TAG, "管道会话 $id 读取中断：${read.exceptionOrNull()?.message}")
                }
                val code = runCatching { process.waitFor() }.getOrNull()
                Log.w(TAG, "管道会话 $id 已结束，退出码=$code")
                closing.remove(id)
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
        // ## 把「已降级到管道」写进终端（P7 待办③，2026-11-03）
        //
        // 走到这里说明所有 PTY 候选都失败了，当前是管道路径。
        // 管道模式下：
        //   - 没有终端窗口概念（`resize` 无效，`top`/`vim` 排版错乱）
        //   - 没有 Ctrl+C 之类的信号键
        //   - 输出被行缓冲，长命令不会实时刷新
        //
        // 旧实现在这种情况下**什么都不说**，用户只会觉得终端「怪怪的」。
        // 这里显式写一行提示 + 每条候选的退出码，让原因可见。
        //
        // 为什么先写提示再让 pump 继续：tap 是有序队列，
        // 此刻 shell 还没输出（刚启动），这条提示会排在最前面。
        if (ptyFailureReasons.isNotEmpty()) {
            runCatching {
                tap.offer("[Praxis] 真 PTY 不可用，已降级到管道模式。")
                tap.offer("[Praxis] 影响：vim/top 无法正常显示、Ctrl+C 无效、输出有延迟。")
                ptyFailureReasons.forEach { tap.offer("[Praxis]   $it") }
                tap.offer("[Praxis] 退出码 127 = 容器内找不到可执行文件（rootfs 可能不完整）。")
            }
        }
        live[id]!!.info
    }

    override fun close(sessionId: String): Boolean {
        val item = live.remove(sessionId) ?: return false
        // ## 关闭顺序（2026-11-01 修）
        //
        // 1. **先登记**「这条会话是主动关的」——泵线程靠它把
        //    `read interrupted by close() on another thread` 当成正常结束。
        //    漏了这一步，每次关标签页都会在界面上留下一条看着像故障的红字。
        // 2. 再发 exit：交互 shell 会自己收尾，比直接 kill 干净。
        // 3. 最后 destroy：关 master fd（内核据此给子进程发 SIGHUP）+ 兜底信号。
        // ## 只在「泵线程还活着」时才登记（2026-11-02 修一个内存泄漏）
        //
        // 这张表的清理者是泵线程自己（它收尾时 `closing.remove(id)`）。
        // 但有一种情形它永远不会清：**会话早已自己结束、用户之后才关标签页**。
        //
        // ```
        // 用户建会话 -> 打 exit -> shell 退出 -> 泵线程跑完 finally（remove）
        //            -> 之后点标签页的 × -> close() 走到这里 -> add
        //            -> 没人再 remove 了（泵线程已经结束）
        // ```
        //
        // 每这么走一次就永久多一个 id。用户一天开关上百次标签页，集合会一直涨。
        // 判据用 `process.isAlive`：进程已死说明泵线程必然已经收尾过，
        // 此刻没有「正在读的线程」需要被认定为预期关闭。
        if (item.process.isAlive) closing.add(sessionId)
        try {
            runCatching { item.writer.write("exit\n"); item.writer.flush() }
            runCatching { item.process.waitFor(600, TimeUnit.MILLISECONDS) }
            // PTY 必须走自己的 destroy：它会先关 master fd（内核据此给子进程发 SIGHUP），
            // 再兜底 destroy 进程。只 destroy 进程的话 shell 可能还卡在 read() 上。
            item.pty?.destroy() ?: runCatching { item.process.destroyForcibly() }
        } finally {
            // 不在这里 remove：泵线程可能还没从 read() 里出来，
            // 提前摘掉它就等于让那条异常又变成「故障」。交给泵线程在
            // EOF 收尾时自己清理（它一定会跑到，最迟在 destroy 关流之后）。
        }
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

    /**
     * 订阅原始输出字节流。
     *
     * ## 实现方式
     *
     * [ScreenTap] 的 `addRawListener` 是回调式，这里用 `callbackFlow` 桥成
     * Flow，并在 `awaitClose` 里注销 —— 协程取消（页面离开）时一定能摘掉
     * 监听，否则泵线程会一直往一个已死的 Channel 里写。
     *
     * `trySend` 而不是 `send`：泵线程是普通 `Thread`，不能挂起。Channel
     * 满了（下游太慢）就丢字节 —— 丢字节比卡住泵线程好，而且真实场景里
     * 只有 `yes` 这种无限输出能把它填满。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun outputFlow(sessionId: String): kotlinx.coroutines.flow.Flow<ByteArray> {
        val item = live[sessionId] ?: return kotlinx.coroutines.flow.emptyFlow()
        return kotlinx.coroutines.flow.callbackFlow {
            val remove = item.output.addRawListener { bytes ->
                // 缓冲 64 块，足够跨越一次重组；满了丢最旧的无从做起，
                // 所以直接丢当前块（`trySend` 返回失败）。
                trySend(bytes)
            }
            awaitClose { remove() }
        }
    }

    /**
     * 逐键发送：把字节原样写进 PTY master（或管道 stdin）。
     *
     * 与 `sendInput` 的差别见接口 KDoc。这里共用 `writer`（它包着 PTY 的
     * stdin 或管道的 outputStream），所以两种模式都能用。
     */
    override fun writeBytes(sessionId: String, bytes: ByteArray): Boolean {
        val item = live[sessionId] ?: return false
        if (item.info.closed) return false
        if (bytes.isEmpty()) return true
        return runCatching {
            // ## 为什么先 flush 旧内容再写
            //
            // 用户可能刚用 sendInput 写过半条命令（没换行）。PTY 不会因为
            // 「没 flush」就丢数据，但 `writer` 是带缓冲的 OutputStreamWriter，
            // 不 flush 的话这一批字节可能停留到下一次写才真的下发 ——
            // 表现为「按了键，两百毫秒后才动」。
            item.writer.flush()
            item.writer.write(String(bytes, Charsets.UTF_8))
            item.writer.flush()
            true
        }.getOrDefault(false)
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

            // ## 回车协议标记绝不能回传给 AI（2026-11-05）
            //
            // `pumpStream` 收到 CR 时会往 ScreenTap.pending 塞一条
            // `CR_MARKER`，让终端 UI 知道「这一行是回车重写，不是新行」。
            //
            // 上一版只在 `ScreenTap.lines`（屏幕快照）里过滤了它，
            // 却故意保留在 `pending`（命令消费队列）。而这里正是从
            // pending 取行并转成工具输出的路径：
            //
            //   持久终端 -> pending -> readUntilSentinel -> onChunk -> AI 上下文
            //
            // 于是 AI 仍会看到 `PRAXIS_CR`，并把它误当成命令输出继续推理。
            // 用户反馈的正是这个第二条路径。
            //
            // CR 是**显示控制信号**，不是命令语义、更不是模型上下文；
            // 在这里直接跳过。DEAD_MARKER / SENTINEL 在上面已分别处理，
            // 所以不会影响退出码、会话死亡诊断或屏幕上的回车渲染。
            if (line == CR_MARKER) continue

            onChunk?.invoke(line + "\n")
        }
        @Suppress("UNREACHABLE_CODE")
        ReadOutcome(0, false)
    }

    /**
     * 把一行原始字节按 UTF-8 解码。
     *
     * ## 为什么用 `String(bytes, UTF_8)` 在这里是**对的**
     *
     * 这个方法只在**行边界**（`\n` / `\r`）被调用，此时 [lineBytes] 里
     * 是一个完整的行 —— UTF-8 的多字节序列不可能跨行（`\n` 是单字节
     * 0x0A，绝不会出现在多字节序列中间）。所以整行解码不会把字符劈开。
     *
     * 旧实现错在**逐字节** `b.toChar()`，那是把字节当码点，与这里无关。
     *
     * 非法字节用 REPLACE（默认行为）：真终端里 `cat` 二进制文件是常态，
     * 抛异常会断掉整条输出流。
     */
    private fun decodeLine(bytes: java.io.ByteArrayOutputStream): String =
        if (bytes.size() == 0) "" else String(bytes.toByteArray(), Charsets.UTF_8)

    /** 只解码前 [length] 个字节（其余留给下一次）。 */
    private fun decodeBytes(bytes: ByteArray, length: Int): String =
        if (length <= 0) "" else String(bytes, 0, length, Charsets.UTF_8)

    /**
     * 从缓冲区里找出「最后一个完整 UTF-8 字符之后」的偏移。
     *
     * ## 为什么要它
     *
     * 半行 flush（`partialFlushChars` 触发）时，缓冲区末尾可能正好停在
     * 一个多字节字符中间。此时整段解码会把那半个字符变成替换字符 ——
     * 又回到用户报的乱码。
     *
     * 做法：从末尾往前找 UTF-8 起始字节（`110xxxxx` / `1110xxxx` /
     * `11110xxx`，即 `(b & 0xC0) != 0x80`），再看它声明了几个字节、
     * 当前是否够长。够长就 flush 到它结尾；不够就退到它前面。
     *
     * 最多回退 3 字节（UTF-8 最长 4 字节），所以循环上界很小。
     */
    private fun safeFlushLength(bytes: java.io.ByteArrayOutputStream): Int {
        val size = bytes.size()
        if (size == 0) return 0
        val arr = bytes.toByteArray()
        // 从末尾往前最多看 4 个字节，找最后一个「字符起点」。
        var back = 1
        while (back <= 4 && back <= size) {
            val b = arr[size - back].toInt() and 0xFF
            if ((b and 0xC0) != 0x80) {
                // 找到一个起点，看它声明的长度。
                val need = when {
                    (b and 0x80) == 0x00 -> 1
                    (b and 0xE0) == 0xC0 -> 2
                    (b and 0xF0) == 0xE0 -> 3
                    (b and 0xF8) == 0xF0 -> 4
                    else -> 1 // 非法起始字节，当单字节处理（解码器会替换）
                }
                // 这个字符在缓冲区里是否完整。
                return if (back >= need) size else size - back
            }
            back++
        }
        // 连着 4 个续字节（异常数据）：不 flush，等更多字节。
        return 0
    }

    /** 读 `/tmp/.praxis_cwd`（容器内绝对路径对应宿主上的位置）。 */
    private fun readCwdFile(): String? = runCatching {
        val env = envPort.defaultEnvironment(distroProvider())
        val hostFile = File(env.rootfsDir, "tmp/.praxis_cwd")
        if (hostFile.isFile) hostFile.readText().trim().takeIf { it.startsWith("/") } else null
    }.getOrNull()

    /**
     * 后台泵：把输出流逐行送进 [ScreenTap]。
     *
     * ## 为什么不再内部吞异常（2026-11-01）
     *
     * 旧实现整个函数体包在 `runCatching` 里，异常被吃掉，调用方无法区分
     * 「正常 EOF」与「读被打断」。而现在这个区分是必需的：
     * 关闭会话时读线程收到的 `read interrupted by close() on another thread`
     * 是**预期**的，不该往界面上报（见 `create()` 里泵线程的说明）。
     *
     * 所以异常上抛给调用方判断；调用方按「会话是否正在被主动关闭」决定
     * 是静默还是报错。
     */
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
            // ## 累积**字节**而不是 Char（2026-11-05 修「终端乱码」）
            //
            // 旧实现是 `line.append(b.toChar())` —— 把单个字节当成
            // Unicode 码点。而 UTF-8 里一个中文占 3 字节，于是「你好」
            // 的 6 个字节被拆成 6 个 Latin-1 字符，屏幕上显示成
            // `ä½ å¥½` 这种形态。
            //
            // 这正是用户报的「终端有时会出现乱码字符」「只是部分乱码」：
            // ASCII 部分完全正常（单字节就是码点），只有非 ASCII 炸掉；
            // 而「有时」是因为取决于输出里有没有中文。
            //
            // 改成先攒字节、遇到行边界再整体按 UTF-8 解码：
            //  - 行内多字节字符天然完整（不会被单字节截断）；
            //  - 跨 read 边界的半个字符也会等下一轮补齐（同一个 buffer 里累积）。
            val lineBytes = java.io.ByteArrayOutputStream(256)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                // ## 先把原始字节推给终端页（2026-10-29）
                //
                // 必须在「拆行」之前推：拆行过程会丢掉 `\r` 的语义、
                // 也会把 ANSI 转义混进文本行，而渲染层要的是未经解释的字节。
                // 拷贝一份而不是直接交 buffer：buffer 下一轮会被覆写，
                // 而监听者可能异步持有它。
                tap.emitRaw(buffer.copyOf(count))
                var i = 0
                while (i < count) {
                    val b = buffer[i].toInt() and 0xFF
                    when (b) {
                        0x0A -> { // LF
                            tap.offer(decodeLine(lineBytes))
                            lineBytes.reset()
                        }
                        0x0D -> { // CR：先交出当前内容，再单独交一个空行表示「回车」
                            tap.offer(decodeLine(lineBytes))
                            lineBytes.reset()
                            tap.offer(CR_MARKER)
                        }
                        else -> lineBytes.write(b)
                    }
                    i++
                }
                // 缓冲里可能一个行边界都没有（比如一整块进度条文字），
                // 此时先进一版，避免用户长时间看不到东西；
                // 行号不丢：`lineBytes` 继续累积，下一次遇到边界时一起交。
                //
                // ## 为什么这里要判「够不够一个字符」
                //
                // 直接在半个字符处 flush 会把它解成替换字符 —— 又回到乱码。
                // `safeFlushLength` 只取到最后一个完整字符的边界，
                // 剩下的半个留在缓冲里等下一轮。
                if (lineBytes.size() >= partialFlushChars) {
                    val safe = safeFlushLength(lineBytes)
                    if (safe > 0) {
                        tap.offer(decodeBytes(lineBytes.toByteArray(), safe))
                        // 把没消费的尾部搬回缓冲头部。
                        val tail = lineBytes.toByteArray().copyOfRange(safe, lineBytes.size())
                        lineBytes.reset()
                        lineBytes.write(tail)
                    }
                }
            }
            if (lineBytes.size() > 0) tap.offer(decodeLine(lineBytes))
        }.getOrThrow()
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
     *
     * ## 为什么必须显式给 `PROOT_LOADER`（2026-10-31 修）
     *
     * 这条环境是**全新的**（`execve` 语义），所以 proot 拿不到父进程的任何
     * 变量，也拿不到 App 进程里已经设好的 `PROOT_LOADER` —— 除非这里显式给。
     *
     * 不给的后果是 proot 只能按它编译期写死的路径去找 loader：
     *
     * ```
     * proot error: execve("/bin/sh"): No such file or directory
     * ```
     *
     * 而这条错误实际含义是「loader 没找到」，不是「/bin/sh 不存在」。
     * 真机实测（2026-10-31）：同样的 rootfs、同样的 proot 二进制，
     * 只加这一行就恢复正常 —— 这解释了截图里
     * 「proot 进程启动后立即退出」的真正原因：
     *
     * 1. PTY 分支缺 `PROOT_LOADER` → proot 秒退 → `Pty` 存活探测失败；
     * 2. 降级到管道分支，那个分支的 `applyEnv` 又缺 `PATH`
     *    → 容器里的 `ls` / `id` 全部 `not found`。
     *
     * 两个缺陷叠加，就是用户截图里那一整屏报错。
     */
    private fun ptyEnvironment(
        env: LinuxEnvironment,
        loader: File? = prootLoaderFileFor(),
    ): Map<String, String> = buildMap {
        put("TERM", "xterm-256color")
        put("HOME", "/root")
        put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        put("LANG", "C.UTF-8")
        put("PROOT_TMP_DIR", context.cacheDir.absolutePath)
        put("PROOT_NO_SECCOMP", "1")
        // ★ 缺它 proot 起不来。loader 由调用方传进来，与 `launchArgs` 的
        // 别名目录同源，不会出现「loader 铺在 A、别名找 B」的分叉。
        loader?.let { put("PROOT_LOADER", it.absolutePath) }
        // 与 applyEnv() 逐字一致：proot 自己的动态依赖也要能找到。
        prootLibDirFor()?.let { put("LD_LIBRARY_PATH", it.absolutePath) }
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

    /**
     * 给子进程设环境变量（必须清掉 Android 的库路径）。
     *
     * ## 为什么这里必须显式给 PATH（2026-10-31 修，真机复现）
     *
     * 上一版只设了 `PROOT_TMP_DIR` / `PROOT_NO_SECCOMP` / `HOME` / `TERM`，
     * 没设 `PATH`。注释当时写着「ProcessBuilder 自带父进程环境副本，
     * 只需增删」—— 这句话对了一半，代价却是致命的：
     *
     * `ProcessBuilder.environment()` 是**父进程环境的副本**，所以
     * Android 的 PATH 被原样继承下来：
     *
     * ```
     * PATH=/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:
     *      /system/bin:/system/xbin:/data/adb/ksu/bin
     * ```
     *
     * proot 会把这个环境**再原样带进容器**（`ProotCommand.loginArgs` 里
     * 那条注释「proot 会把父进程的环境原样带进容器」说的就是这件事）。
     * 于是容器里的 shell 拿着全 Android 路径去找 binutils —— 一个都找不到：
     *
     * ```
     * # ls
     * /bin/sh: 1: ls: not found
     * ```
     *
     * 真机实测（2026-10-31，aarch64 / SDK 37）：
     *
     * | 环境 | 结果 |
     * |---|---|
     * | 只设 4 个变量（旧写法） | `ls: not found`、`id: not found`，几乎全部命令失败 |
     * | 加上容器 PATH | `ls` / `id` / `uname` 全部正常 |
     *
     * 其余参数（`-v 0` / `--kill-on-exit` / 全部 `-b` 挂载 / `-w /root`）
     * 逐项验证过，**均无影响**。唯一原因就是 PATH。
     *
     * ## 为什么是「覆盖」而不是「追加」
     *
     * 追加（`$PATH:/usr/bin:...`）会把 Android 的路径留在前面，
     * 容器里若碰巧有同名程序（比如 busybox 的 `ls`）会优先命中宿主的，
     * 行为不可预测。容器必须有自己**完整且自洽**的 PATH，
     * 和 [ptyEnvironment] 保持逐字一致 —— 两条路径的环境不同，
     * 就会出现「真机 PTY 能用、降级后全崩」这种只在部分设备复现的缺陷。
     */
    private fun applyEnv(pb: ProcessBuilder, env: LinuxEnvironment) {
        val e = pb.environment()
        // ★ 必须先清空再重建：父进程环境里的 Android 路径一条都不能留下。
        e.clear()
        e["HOME"] = "/root"
        e["TERM"] = "xterm-256color"
        // 与 ptyEnvironment() 逐字一致。这两份一旦分叉，就会出现
        // 「PTY 分支正常、管道分支全崩」——而管道分支只在部分设备上走得到，
        // 极难复现（本次就是 aarch64 + libpty 可用时一直没暴露）。
        e["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        e["LANG"] = "C.UTF-8"
        e["PROOT_TMP_DIR"] = context.cacheDir.absolutePath
        e["PROOT_NO_SECCOMP"] = "1"
        // 引导器：不设的话容器一条命令都跑不起来（真机实测 execve ENOENT）。
        // PTY 分支通过 ptyEnvironment() 传入，这里同样要传 ——
        // 之前漏了，靠 proot 自己找默认路径，换个 ROM 就失败。
        prootLoaderFileFor()?.let { e["PROOT_LOADER"] = it.absolutePath }
        // ## 依赖库路径（2026-11-02 实测后加）
        //
        // proot 的 ELF 里有 `DT_NEEDED: libtalloc.so.2 / libandroid-shmem.so`。
        // **执行** `nativeLibraryDir` 里的二进制时，Android 的链接器**不搜**那个
        // 目录（实测：库就在同目录，仍报 `library "libtalloc.so.2" not found`）。
        // 不指路的话 proot 根本起不来。
        //
        // 指的那个目录里只有这两个库，**不掺 Android 系统库** —— 所以不会重现
        // 下面说的「容器程序加载 bionic 后 cannot locate symbol」那个老问题。
        prootLibDirFor()?.let { e["LD_LIBRARY_PATH"] = it.absolutePath }
        // LD_PRELOAD 已随 clear() 一并清掉：留着会让容器里的程序去加载 bionic，
        // curl 报 `cannot locate symbol "EVP_MD_CTX_create"`。
    }

    /**
     * loader 路径 —— 直接取 `nativeLibraryDir` 里的原件。
     *
     * ## 三条弯路都走完了（2026-11-02 定案）
     *
     * 1. 直接按 `env.prootBinary.parentFile` 找：错，proot 推导 loader 时不看那里；
     * 2. 拷到 `filesDir/proot/bin/proot-loader` 改名：错，`filesDir` 不可执行；
     * 3. 造一个 `proot` 别名让它「从自己的目录找」：错，`filesDir` 里连 exec 都不许。
     *
     * 真因是 proot 会 **`execve`** loader，而 Android 10+ 的 `untrusted_app`
     * 对 `app_data_file`（`filesDir`）**没有 execute 权限**，只对 `apk_data_file`
     * （`nativeLibraryDir`）有。所以 loader 必须原地用，详见
     * `ProotCommand.LOADER_FILE_NAME`。
     *
     * 返回 null 表示 APK 里没有 loader，此时不设 `PROOT_LOADER`，行为退回旧样子。
     */
    private fun prootLoaderFileFor(): File? = ProotLoaderStaging.stage(context)

    /**
     * proot 动态依赖所在目录（`filesDir/proot/lib/`），供 `LD_LIBRARY_PATH` 使用。
     *
     * 与 [prootLoaderFileFor] 同源（都走 [ProotLoaderStaging]），保证
     * 「loader 铺在 A、依赖铺在 B」这种分叉不会发生。
     *
     * 返回 null 时调用方就不设 `LD_LIBRARY_PATH` —— 与修之前的行为一致。
     */
    private fun prootLibDirFor(): File? = ProotLoaderStaging.stageLibs(context)

    // internal 而不是 private：isExpectedShutdown 是 ⑤ 的核心判定，
    // 单测要直接断言它（private companion 会让测试完全访问不到，
    // 2026-11-02 CI 就是这样红了 4 条）。
    internal companion object {
        /** 哨兵前缀。带随机后缀意义不大：同一会话内串行执行，不会撞。 */
        const val SENTINEL = "__PRAXIS_DONE__"

        /** 超时伪退出码，与 coreutils `timeout` 一致。 */
        const val TIMEOUT_EXIT_CODE = 124

        /**
         * 读线程拿到的这个异常，该不该被当成**故障**报给界面。
         *
         * ## 为什么需要这个判断（⑤ 的核心，2026-11-01 修 / 2026-11-02 抽测）
         *
         * 用户报的第 ⑤ 项是「容器会话读取中断（read interrupted by close()
         * on another thread）」。真因是：用户点关闭标签页时 `close()` 会关掉
         * master fd，而此刻泵线程正阻塞在 `read()` 上，JDK 于是抛：
         *
         * ```
         * java.io.IOException: read interrupted by close() on another thread
         * ```
         *
         * 旧实现把它原样报成「会话读取结束：read interrupted by close()…」——
         * 用户看到容器「跑着跑着结束了」，实际上那是**他自己关的**。
         * 正常关闭不该产生错误文案。
         *
         * ## 判据
         *
         * 只有「会话已被标记为关闭」时才算预期。**其它异常一律可见** ——
         * 真正的 IO 故障（磁盘错、fd 被系统回收）必须能被用户看到，
         * 否则会变成「会话静默死掉，调用方只能等到超时」。
         *
         * ## 为什么抽成纯函数
         *
         * 这条判据原先在两处（PTY 分支与管道分支）各内联了一遍。
         * 抽出来之后既能单测，也保证两条分支**不会再次分叉** ——
         * 那种分叉历史上出现过（一条改了一条没改，表现是「终端能用、
         * 设置页里的命令全崩」）。
         *
         * @param failed 泵线程是否抛了异常
         * @param closing 该会话是否已被标记为「正在关闭」
         * @return true 表示这是预期内的收尾，不该报错
         */
        internal fun isExpectedShutdown(failed: Boolean, closing: Boolean): Boolean =
            !failed || closing
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

    /**
     * 原始字节监听者（终端页订阅用）。
     *
     * ## 为什么它和 pending 是两条路
     *
     * pending 是给哨兵协议用的「行」队列，已经被 CR 拆开、被 trim 过，
     * 适合「判断命令跑完没」。而终端页需要**未经解释的原始字节**：
     * ANSI 转义要原样送到 AnsiScanner，CR 要保留语义（回行首重写），
     * 这两件都以「字节」为前提。
     *
     * ## 为什么要 runCatching 包起来
     *
     * 监听者是 UI 侧的回调，它抛异常不能反过来杀掉泵线程 —— 泵线程一死，
     * 整个会话就废了，而用户只是碰到了渲染层的一个 bug。
     */
    private val rawListeners =
        java.util.concurrent.CopyOnWriteArrayList<(ByteArray) -> Unit>()

    /** 注册原始字节监听。返回取消句柄。 */
    fun addRawListener(listener: (ByteArray) -> Unit): () -> Unit {
        rawListeners.add(listener)
        return { rawListeners.remove(listener) }
    }

    /** 泵线程把原始字节推给监听者。 */
    fun emitRaw(bytes: ByteArray) {
        if (bytes.isEmpty() || rawListeners.isEmpty()) return
        rawListeners.forEach { listener -> runCatching { listener(bytes) } }
    }


    fun offer(line: String) {
        // ## 控制标记不进屏幕（2026-11-05 修）
        //
        // `CR_MARKER` / `DEAD_MARKER` 是**协议内部标记**：
        // 前者告诉下游「这一行要回车重写」，后者让 execute 知道会话死了。
        // 它们只该进 [pending]（消费队列），不该进 [lines]（屏幕快照）。
        //
        // 旧实现两者都进 —— 但那时解码是逐字节 `b.toChar()`，
        // marker 里的 `\u0000` 在渲染阶段被当成空白吃掉，所以看不出来。
        // 改成按字节累积 + UTF-8 解码之后，`\u0000` 不再是「空白」，
        // 屏幕上就冒出满地的 `PRAXIS_CR`。
        //
        // 这属于「修好一个 bug 暴露了另一个被掩盖的 bug」的典型。
        //
        // ## 为什么不直接比 `line == CR_MARKER`（2026-11-05）
        //
        // `CR_MARKER` / `DEAD_MARKER` 是 `ProotTerminalSessionPort` 的
        // 类内私有成员，而 `ScreenTap` 是**顶层** private class ——
        // 它看不到那两个常量（CI #162 就是这么红的）。
        //
        // 与其把常量搬到文件级（那只是让作用域凑合上），不如让
        // `ScreenTap` 守住一条**它自己能判断**的规则：
        //
        //   终端输出里永远不会含 NUL（0x00）。
        //
        // 这不是权宜之计 —— 它正是那两个常量**故意**用 `\u0000` 包裹的
        // 原因（见它们的 KDoc：「NUL 包裹的固定标记在真实输出里不会出现」）。
        // 所以 ScreenTap 依赖的是那条**设计契约**，而不是某个具体字符串。
        //
        // `SENTINEL`（`__PRAXIS_DONE__`）不含 NUL，因此不在过滤范围内 ——
        // 它走的是真实命令输出，行为与修改前完全一致。
        val isMarker = line.indexOf('\u0000') >= 0
        synchronized(lock) {
            if (!isMarker) {
                lines.addLast(line)
                while (lines.size > MAX_LINES) lines.removeFirst()
            }
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