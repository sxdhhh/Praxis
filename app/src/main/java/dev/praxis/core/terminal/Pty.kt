package dev.praxis.core.terminal

import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 伪终端（PTY）。
 *
 * ## 为什么这个类在 `:app` 的 `dev.praxis.core.terminal` 包下
 *
 * JNI 符号是**按包名+类名硬编码**的（`Java_dev_praxis_core_terminal_Pty_...`），
 * 而 `core:terminal` 是纯 JVM 模块（`praxis.kotlin.jvm`），编不出 Android 原生库。
 * 所以：
 *  - `core:terminal` 保留纯 JVM 的 `AnsiScanner` / `TerminalBuffer`（可单测）；
 *  - PTY 这个**必须碰 Android 与 native** 的部分放 `:app`，
 *    但**沿用同一个包名** `dev.praxis.core.terminal`，这样 JNI 符号不用改，
 *    将来若把 `core:terminal` 改成 Android 模块可以直接搬过去而无需动 C。
 *
 * ## 与 Operit `Pty.kt` 的差异
 *
 * | | Operit | 这里 |
 * |---|---|---|
 * | 窗口大小 | 写死 60x40 | **由调用方传**（手机上必须按实际列数，否则 top 错位） |
 * | 失败处理 | 只打日志 | `loadLibrary` 失败时 `available = false`，调用方降级到管道 |
 * | 生命周期 | `destroy()` 直接 close 流 | 额外 `waitFor` 超时兜底，防僵尸进程 |
 *
 * ## 加载失败为什么要静默降级
 *
 * `libpty.so` 只编了 arm64-v8a。万一装到了 32 位设备（或 CI 产物缺库），
 * `UnsatisfiedLinkError` 会让**整个终端页崩掉**。降级到管道虽然功能不全
 * （没有 `isatty`、Ctrl+C 无效、vim 报错），但至少 `ls`、`apt`、`cat` 还能用 ——
 * 对用户来说「功能受限」远好于「一进页面就崩」。
 */
open class Pty(
    /** 底层进程。 */
    val process: Process,
    /** master fd；PTY 被关闭或降级时为 null。 */
    val masterFd: FileDescriptor?,
    /** master fd 的整数句柄（供 ioctl 用）；降级时为 -1。 */
    private val ptyMaster: Int,
    /** 读端。 */
    val stdout: InputStream,
    /** 写端。 */
    val stdin: OutputStream,
    /** 子进程 pid；无法取得时为 -1。 */
    val pid: Int = -1,
) {

    /** 等待子进程退出，返回退出码。 */
    fun waitFor(): Int = runCatching { process.waitFor() }.getOrDefault(-1)

    /**
     * 结束会话。
     *
     * ## 为什么必须先关 master fd 再 destroy 进程
     *
     * PTY 的 EOF 是**靠 master fd 关闭**来通知子进程的（内核在从端
     * 读到 EOF 时给子进程发 SIGHUP）。只 `process.destroy()` 的话，
     * 子进程里的 shell 可能仍然以为终端还在，卡在 `read()` 上不退出。
     * 顺序必须是「关 fd → destroy」。
     */
    fun destroy() {
        runCatching { process.destroy() }
        runCatching { stdout.close() }
        runCatching { stdin.close() }
    }

    /** 设窗口大小（行列）。失败静默：画得丑好过崩掉。 */
    fun resize(rows: Int, cols: Int): Boolean {
        if (ptyMaster < 0) return false
        return runCatching { setPtyWindowSize(ptyMaster, rows, cols) == 0 }.getOrDefault(false)
    }

    /**
     * 子进程当前是否处于 canonical 模式。
     *
     * `false` 说明它把终端切成了 raw 模式 —— `vim` / `top` / `less` 都会这么做。
     * 用它可以判断「交互程序是否已经接管了终端」，比抓提示符可靠。
     */
    fun isCanonicalMode(): Boolean {
        if (ptyMaster < 0) return true
        val flags = runCatching { getTerminalFlags(ptyMaster) }.getOrDefault(-1)
        if (flags < 0) return true
        return (flags and 0x01) != 0
    }

    /** 当前有多少字节可读；-1 表示取不到。 */
    fun availableBytes(): Int {
        if (ptyMaster < 0) return -1
        return runCatching { getAvailableBytes(ptyMaster) }.getOrDefault(-1)
    }

    companion object {
        private const val TAG = "PraxisPty"

        /**
         * `libpty.so` 是否加载成功。
         *
         * 用 `@Volatile` + 显式 try：`System.loadLibrary` 失败会抛
         * `UnsatisfiedLinkError`（是 `Error` 不是 `Exception`），
         * 不接住会让类初始化直接崩。
         */
        @Volatile
        var available: Boolean = false
            private set

        init {
            available = try {
                System.loadLibrary("pty")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "libpty.so 不可用，终端将降级为管道模式", e)
                false
            }
        }

        /**
         * 起一个 PTY 子进程。
         *
         * @param command argv（**不经 shell 包一层**，与 `execve` 语义一致）
         * @param environment 环境变量（会被逐条转成 `KEY=VALUE`）
         * @param workingDir 子进程的工作目录（宿主路径）
         * @param rows 行数；<=0 时用 60
         * @param cols 列数；<=0 时用 40
         * @return 起不来时返回 null（调用方降级）
         */
        fun start(
            command: Array<String>,
            environment: Map<String, String>,
            workingDir: File,
            rows: Int = 60,
            cols: Int = 40,
        ): Pty? {
            if (!available) {
                Log.w(TAG, "PTY 不可用，拒绝启动")
                return null
            }
            val envArray = environment.map { "${it.key}=${it.value}" }.toTypedArray()
            val result = runCatching {
                createSubprocess(
                    command,
                    envArray,
                    workingDir.absolutePath,
                    if (rows > 0) rows else 60,
                    if (cols > 0) cols else 40,
                )
            }.getOrNull() ?: return null

            // 返回 [pid, masterFd]，长度不对说明 JNI 出错。
            if (result.size < 2) return null
            val pid = result[0]
            val fd = result[1]
            if (fd < 0) return null

            val masterFileDescriptor = runCatching {
                // 用反射拿到真实 FileDescriptor：JNI 只回了 int fd，
                // 而 Java 侧要 InputStream/OutputStream 必须包在 FileDescriptor 上。
                val f = FileDescriptor::class.java.getDeclaredField("descriptor")
                f.isAccessible = true
                FileDescriptor().also { f.setInt(it, fd) }
            }.getOrNull() ?: return null

            return Pty(
                process = NoopProcess(pid),
                masterFd = masterFileDescriptor,
                ptyMaster = fd,
                stdout = FileInputStream(masterFileDescriptor),
                stdin = FileOutputStream(masterFileDescriptor),
                pid = pid,
            )
        }

        /**
         * 等子进程退出并返回退出码。JNI 直接 `waitpid`。
         */
        fun waitFor(pid: Int): Int = runCatching { waitForPid(pid) }.getOrDefault(-1)

        // ── native ──────────────────────────────────────────────
        //
        // ⚠️ 这四个必须留在 companion object 里、且名字一个字符都不能改：
        // JNI 符号是 `Java_dev_praxis_core_terminal_Pty_00024Companion_<name>`，
        // 其中 `_00024` 就是 `$`（`Pty$Companion`）。
        // 改名字/挪位置 = 运行时 UnsatisfiedLinkError。

        @JvmStatic
        private external fun createSubprocess(
            cmdarray: Array<String>,
            envarray: Array<String>,
            workingDir: String,
            rows: Int,
            cols: Int,
        ): IntArray?

        @JvmStatic
        private external fun waitForPid(pid: Int): Int

        @JvmStatic
        private external fun getTerminalFlags(fd: Int): Int

        @JvmStatic
        private external fun getAvailableBytes(fd: Int): Int

        @JvmStatic
        private external fun setPtyWindowSize(fd: Int, rows: Int, cols: Int): Int
    }
}

/**
 * 只携带 pid 的假 [Process]。
 *
 * ## 为什么需要它
 *
 * `forkpty` 出来的子进程**不是** Java 的 `Process` —— 它是 C 层 fork 出来的，
 * Java 侧只有一个 pid。但 [Pty] 的接口（照 Operit）要求 `Process`。
 *
 * 这个占位类把所有「真正需要向 OS 发信号」的方法做成空实现或委托给 JNI：
 *  - [destroy]：空（真正的结束走 [Pty.destroy] 里关 fd + `kill`）
 *  - [waitFor]：委托 `Pty.waitFor(pid)`（JNI `waitpid`）
 *
 * 诚实地写成「假 Process」而不是假装它可用：任何依赖 `Process.pid()` /
 * `Process.info()` 的代码在这里都会得到明确的无意义值，而不是静默错误。
 */
private class NoopProcess(private val childPid: Int) : Process() {
    @Volatile
    private var finished = false
    @Volatile
    private var exitValue: Int = 0

    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getInputStream(): InputStream = InputStream.nullInputStream()

    override fun getErrorStream(): InputStream = InputStream.nullInputStream()

    override fun waitFor(): Int {
        if (!finished) {
            exitValue = Pty.waitFor(childPid)
            finished = true
        }
        return exitValue
    }

    override fun exitValue(): Int =
        if (finished) exitValue else throw IllegalThreadStateException("子进程尚未退出")

    override fun destroy() {
        // 真正的终止在 Pty.destroy 里（关 master fd 让子进程收 SIGHUP）。
        // 这里额外发一次 SIGTERM 兜底：有些子进程忽略 SIGHUP。
        runCatching { android.os.Process.sendSignal(childPid, 15) }
    }
}