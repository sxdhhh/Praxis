package dev.praxis.data.shell

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 宿主 Android shell 的执行结果。
 *
 * 与 `LinuxExecResult` 分开建模的理由：那个是**容器内**的命令，
 * 这个是**宿主系统**的命令。两者权限、影响范围、失败原因完全不同，
 * 合成一个类型会让「命令在容器里跑」与「命令在手机上跑」变得难以区分 ——
 * 而这是本项目里最容易造成不可挽回后果的一处混淆。
 */
data class HostShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    /** 实际使用的通道，供回执里如实告知模型「你是在什么身份下跑的」。 */
    val channel: HostShellChannel,
    val timedOut: Boolean = false,
) {
    val isSuccess: Boolean get() = exitCode == 0 && !timedOut
}

/** 宿主 shell 的身份通道。 */
enum class HostShellChannel(val label: String) {
    /** `su -c`：内核级 root。 */
    ROOT("root"),

    /** Shizuku：ADB 级（shell uid 2000），能跑多数 `pm`/`am`/`settings`。 */
    SHIZUKU("shizuku"),

    /** 应用自身 uid：什么都做不了，但要能如实报出来而不是假装成功。 */
    APP("app"),
}

/**
 * 宿主 shell 通道选择与执行 —— 移植自 Operit 的
 * `RootShellExecutor` / `DebuggerShellExecutor` / `AndroidShellExecutor` 三件套。
 *
 * ## 与 Operit 的对应关系
 *
 * | Operit | 本类 | 说明 |
 * |---|---|---|
 * | `RootShellExecutor`（libsu `Shell.Builder`） | [runAsRoot] | 直接用 `ProcessBuilder("su", "-c", ...)` |
 * | `DebuggerShellExecutor`（Shizuku binder） | [runAsShizuku] | 反射调 Shizuku，不引编译期依赖 |
 * | `AndroidShellExecutor.executeShellCommand` | [execute] | 按可用性自动分流 |
 *
 * ## 为什么不用 libsu
 *
 * Operit 引了 `com.topjohnwu.superuser:libsu`，好处是 `Shell.cmd()` 写法简洁、
 * 有 `CallbackList` 流式回调。但对本项目只有「跑一条命令、拿完整输出」这一个用途，
 * 而 libsu 会带来三个实际代价：
 *
 *  1. 它的 `Shell` 静态初始化要求 `Shell.setDefaultBuilder(...)` 在
 *     `Application.onCreate` 之前完成，否则首次调用会抛异常 —— 与 Praxis
 *     现在的 Koin 初始化顺序冲突，需要额外一层包装；
 *  2. libsu 的 `su` 调用是**常驻 shell 进程**（为性能），进程生命周期要自己管，
 *     否则会留下僵留进程；
 *  3. 这里需要精确控制超时与 kill（模型可能发起 `sleep 9999`），
 *     `ProcessBuilder` + `waitFor(timeout)` + `destroyForcibly` 更直接。
 *
 * 所以这里用标准 `ProcessBuilder`，与 `CapabilityDetector.tryRunSu()` 同一套做法 ——
 * 检测与执行用同一条路径，才不会出现「检测说有 root、执行却失败」。
 *
 * ## Shizuku 为什么走反射
 *
 * 与 `CapabilityDetector.isShizukuAlive()` 保持一致（见那里的注释）：
 * 引入 `rikka.shizuku` 会把它的 `provider` / `UserService` 合进 manifest，
 * 并要求用户必须装 Shizuku。反射调 `Shizuku.newProcess(...)` 只在
 * Shizuku 真的可用时才走到，装都没装的用户完全不受影响。
 */
class AndroidHostShellPort(
    private val context: Context,
) {
    companion object {
        /** 默认超时。模型发起的命令不能无限挂住。 */
        const val DEFAULT_TIMEOUT_SECONDS: Long = 15L

        /** 上限。全盘 `find` 之类可能需要，但不能到「半天」的量级。 */
        const val MAX_TIMEOUT_SECONDS: Long = 600L

        /** 输出上限，避免 `cat` 一个巨大文件把上下文撑爆。 */
        const val MAX_OUTPUT_CHARS: Int = 40_000

        private const val SHIZUKU_CLASS = "rikka.shizuku.Shizuku"
    }

    /**
     * 按「root → shizuku → app」顺序选通道并执行。
     *
     * ## 为什么顺序是 root 优先
     *
     * root 权限是超集：能跑 shizuku 能跑的一切，反过来不成立
     * （shizuku 是 uid 2000，改不了 `/data/data` 下别的应用）。
     * 所以有 root 就用 root。
     *
     * ## `preferChannel` 的用途
     *
     * 少数命令在 root 下行为不同（例如某些 ROM 的 `pm` 在 uid 0 下会拒绝对
     * 当前用户操作），此时调用方可以显式指定 `shizuku`。
     */
    suspend fun execute(
        command: String,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
        preferChannel: HostShellChannel? = null,
        workingDir: String? = null,
    ): HostShellResult = withContext(Dispatchers.IO) {
        val timeout = timeoutSeconds.coerceIn(1L, MAX_TIMEOUT_SECONDS)

        val order = when (preferChannel) {
            HostShellChannel.ROOT -> listOf(HostShellChannel.ROOT)
            HostShellChannel.SHIZUKU -> listOf(HostShellChannel.SHIZUKU)
            HostShellChannel.APP -> listOf(HostShellChannel.APP)
            null -> listOf(HostShellChannel.ROOT, HostShellChannel.SHIZUKU, HostShellChannel.APP)
        }

        for (channel in order) {
            val result = when (channel) {
                HostShellChannel.ROOT -> runAsRoot(command, timeout, workingDir)
                HostShellChannel.SHIZUKU -> runAsShizuku(command, timeout, workingDir)
                HostShellChannel.APP -> runAsApp(command, timeout, workingDir)
            }
            // 通道不可用（连进程都起不来）时继续试下一个；
            // 命令**执行了但失败**则直接返回 —— 那种失败是命令本身的问题，
            // 换通道重跑会掩盖真实错误，甚至造成重复副作用。
            if (result != null) return@withContext result
        }

        // 三个通道全不可用。
        HostShellResult(
            exitCode = -1,
            stdout = "",
            stderr = "没有可用的执行通道：root 不可用、Shizuku 未运行、应用自身权限也不足以执行任何命令。",
            channel = HostShellChannel.APP,
        )
    }

    /** 探活：当前有哪些通道可用。给 UI 显示与工具描述用。 */
    fun availableChannels(): List<HostShellChannel> = buildList {
        if (hasRoot()) add(HostShellChannel.ROOT)
        if (isShizukuAlive()) add(HostShellChannel.SHIZUKU)
        add(HostShellChannel.APP)
    }

    // ── 通道实现 ──────────────────────────────────────────────────

    /**
     * `su -c <command>`。
     *
     * 返回 null 表示**通道不可用**（没有 su / 被 SELinux 拒 / 授权超时），
     * 与「命令跑失败」区分开 —— 后者返回带 exitCode 的结果。
     */
    private fun runAsRoot(
        command: String,
        timeoutSeconds: Long,
        workingDir: String?,
    ): HostShellResult? = try {
        val process = ProcessBuilder("su", "-c", command)
            .apply { workingDir?.let { directory(File(it)) } }
            .start()
        collect(process, timeoutSeconds, HostShellChannel.ROOT)
    } catch (t: Throwable) {
        // 没有 su 二进制是 IOException；被 SELinux 拒绝同样抛异常；
        // 用户点了拒绝授权则是 SecurityException。三者结论一致：通道不可用。
        null
    }

    /**
     * Shizuku 通道。
     *
     * Shizuku 的 `newProcess(String[] cmd, String[] env, String dir)`
     * 返回一个 `Process`。它在 App 进程里通过 binder 让 shell uid 2000 执行，
     * 所以能跑 `pm` / `am` / `settings` / `dumpsys`，但**不能**读别的应用私有目录。
     *
     * 反射失败（没装 / 没授权 / 服务没起）一律返回 null = 通道不可用。
     */
    private fun runAsShizuku(
        command: String,
        timeoutSeconds: Long,
        workingDir: String?,
    ): HostShellResult? = try {
        val clazz = Class.forName(SHIZUKU_CLASS)
        val newProcess = clazz.getMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        // `sh -c` 而不是把命令拆成 argv：模型给的是一条完整命令行，
        // 里面有管道、重定向、变量展开，拆 argv 会全部失效。
        val process = newProcess.invoke(
            null,
            arrayOf("sh", "-c", command),
            null,
            workingDir ?: "/",
        ) as? Process ?: return null
        collect(process, timeoutSeconds, HostShellChannel.SHIZUKU)
    } catch (t: Throwable) {
        null
    }

    /** 应用自身 uid。几乎什么都做不了，但必须能如实报错。 */
    private fun runAsApp(
        command: String,
        timeoutSeconds: Long,
        workingDir: String?,
    ): HostShellResult? = try {
        val process = ProcessBuilder("sh", "-c", command)
            .apply { workingDir?.let { directory(File(it)) } }
            .start()
        collect(process, timeoutSeconds, HostShellChannel.APP)
    } catch (t: Throwable) {
        null
    }

    /**
     * 收一个进程的输出并等待结束。
     *
     * ## 为什么两条流分开读
     *
     * `redirectErrorStream(true)` 会把 stderr 混进 stdout，模型就看不出
     * 「命令没输出」和「命令报错」的区别。而如果只读 stdout 不读 stderr，
     * 子进程写满 stderr 缓冲区（64 KB）后会**阻塞在 write 上**，
     * 表现为「命令卡死到超时」—— 这是最典型的误诊来源。
     *
     * 所以起一个守护线程专门抽干 stderr。
     *
     * ## 超时必须 destroyForcibly
     *
     * `destroy()` 发 SIGTERM，被 `trap` 或忽略信号的进程可以赖着不死；
     * `su` 包装层尤其容易这样。`destroyForcibly()` 发 SIGKILL 才可靠。
     */
    private fun collect(
        process: Process,
        timeoutSeconds: Long,
        channel: HostShellChannel,
    ): HostShellResult {
        val stderrBuffer = StringBuilder()
        val stderrThread = Thread {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line ->
                    if (stderrBuffer.length < MAX_OUTPUT_CHARS) {
                        stderrBuffer.append(line).append('\n')
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        val stdoutBuffer = StringBuilder()
        val stdoutThread = Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (stdoutBuffer.length < MAX_OUTPUT_CHARS) {
                        stdoutBuffer.append(line).append('\n')
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        val finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            // 给两个抽取线程一点时间把已缓冲的内容落进 StringBuilder，
            // 否则超时回执里会丢掉「卡住前已经输出的部分」—— 那往往正是
            // 判断卡在哪里的唯一线索。
            stdoutThread.join(300)
            stderrThread.join(300)
            return HostShellResult(
                exitCode = 124,
                stdout = stdoutBuffer.toString().trimEnd(),
                stderr = stderrBuffer.toString().trimEnd(),
                channel = channel,
                timedOut = true,
            )
        }

        stdoutThread.join(500)
        stderrThread.join(500)
        return HostShellResult(
            exitCode = process.exitValue(),
            stdout = stdoutBuffer.toString().trimEnd(),
            stderr = stderrBuffer.toString().trimEnd(),
            channel = channel,
        )
    }

    // ── 探活 ─────────────────────────────────────────────────────

    /**
     * 是否真有 root。
     *
     * 与 `CapabilityDetector.tryRunSu()` 同一判据（`su -c id` 输出含 `uid=0`），
     * 但这里**不设超时等待**：调用点在工具执行路径上，用户可能正在看授权弹窗，
     * 多等 1.5 秒会让工具调用明显变慢。改成「能起进程就算有机会，
     * 真伪交给命令自己暴露」—— 因为 `execute()` 里本来就区分了
     * 「通道不可用」与「命令失败」。
     */
    private fun hasRoot(): Boolean = try {
        val process = ProcessBuilder("su", "-c", "id").start()
        val finished = process.waitFor(1200, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            false
        } else {
            val out = process.inputStream.bufferedReader().readText()
            process.exitValue() == 0 && out.contains("uid=0")
        }
    } catch (t: Throwable) {
        false
    }

    /** 反射查 Shizuku binder 是否存活 —— 与 `CapabilityDetector` 同一做法。 */
    private fun isShizukuAlive(): Boolean = try {
        val clazz = Class.forName(SHIZUKU_CLASS)
        val ping = clazz.getMethod("pingBinder")
        ping.invoke(null) as? Boolean ?: false
    } catch (t: Throwable) {
        false
    }
}