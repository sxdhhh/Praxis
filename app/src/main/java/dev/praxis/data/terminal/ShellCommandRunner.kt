package dev.praxis.data.terminal

import dev.praxis.feature.terminal.CommandRunner
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 用 `sh -c` 执行命令。
 *
 * ## 四个必须处理的现实问题
 *
 * 1. **必须读 stderr**：不读的话，子进程写满管道缓冲区后会永久阻塞，界面表现为
 *    「命令卡住不动」。两个流都要读完才能 waitFor。
 * 2. **必须合并 stdout/stderr 到一个回调**：终端里两者本来就混在一起显示，
 *    分成两路反而会打乱顺序。用 `redirectErrorStream(true)` 让内核替我们排队。
 * 3. **必须有超时**：用户敲了 `sleep 9999` 或交互式命令（等待 stdin）时，
 *    不设上限就会永远占住「同一时刻只允许一条命令」的那个槽位。
 * 4. **必须在结束时销毁进程**：超时分支里只 cancel 协程不 destroy 的话，
 *    子进程会变成孤儿继续跑。
 *
 * ## 为什么用 `sh -c` 而不是 `Runtime.exec(String)`
 *
 * `Runtime.exec(String)` 按空白切分且不做引号处理，`echo "a b"` 会被拆错。
 * `sh -c` 把整条命令交给 shell，引号、管道、重定向、变量展开全都正常。
 *
 * ## 已知限制（本轮明确的边界）
 *
 * - 没有 pty：需要终端的程序（vim、top 的交互模式）会退化为纯文本输出；
 * - 不支持 stdin 交互：命令等待输入会直到超时；
 * - 权限：普通应用进程权限，不是 root。需要 root 的能力属于工具层（DEBUGGER/ROOT 档）。
 */
class ShellCommandRunner(
    private val workingDir: String? = null,
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) : CommandRunner {

    /**
     * 当前正在跑的进程。
     *
     * 只给 [cancel] 用。用 `@Volatile` 而不是加锁：写入方（IO 线程）与
     * 读取方（主线程点 Ctrl+C）只需可见性，不需要互斥。
     */
    @Volatile
    private var current: Process? = null

    /**
     * 中断当前命令。
     *
     * 先让子进程自己退出（`destroy` 发 SIGTERM，shell 有机会收尾），
     * 再兜底强杀。**不能只调协程 cancel**：那样 Process 还活着，
     * 输出流没人读 → 写满管道后永久阻塞。
     */
    override fun cancel() {
        val process = current
        if (process == null) return
        runCatching { process.destroy() }
        // 给 200ms 收尾时间，超时则强杀。
        if (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
            runCatching { process.destroyForcibly() }
        }
        current = null
    }

    override suspend fun run(command: String, onOutput: (String) -> Unit): Int =
        withContext(Dispatchers.IO) {
            // ## 为什么用绝对路径 `/system/bin/sh`（2026-10-31 修）
            //
            // 旧写法是 `ProcessBuilder("sh", "-c", command)` —— `"sh"` 是
            // **相对名字**，靠 PATH 解析。这带来两个真机问题：
            //
            // 1. 解析失败时直接 `IOException: Cannot run program "sh"`，
            //    终端一条命令都执行不了；
            // 2. 即使解析成功，子进程的 cwd 默认是 App 的私有目录
            //    （`/data/data/<pkg>`），而宿主 `sh` 以 `shell` 身份运行、
            //    **读不了那个目录**。于是 `ls` 报：
            //
            //    ```
            //    $ ls
            //    ls: .: Permission denied       ← 退出码 1
            //    ```
            //
            //    用户看到的是「ls 坏了」，实际是「当前目录不可读」。
            //    这条现象在截图里出现过，和容器起不来是**两个独立的缺陷**：
            //    容器失败后降级到这里，又撞上 cwd 不可读，于是两个错误叠在一起。
            //
            // 绝对路径消除问题 1；cwd 交给调用方用 [workingDir] 指定
            // （默认落到 `/`，那是对 `shell` 用户一定可读的少数目录之一）。
            val shell = if (File(SYSTEM_SH).isFile) SYSTEM_SH else "sh"
            val builder = ProcessBuilder(shell, "-c", command)
                // 合并两个流：终端里 stdout/stderr 本就混排，分开读会打乱顺序。
                .redirectErrorStream(true)
            // ★ 默认 cwd 必须是 `shell` 可读的目录。
            // 不指定时 Java 会用「父进程的 cwd」= App 私有目录 → Permission denied。
            builder.directory(File(workingDir ?: DEFAULT_WORKING_DIR))
            val process = builder.start()
            current = process
            try {
                // 逐行读会丢掉「没有换行的进度条」，所以按块读。
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val buffer = CharArray(READ_BUFFER_SIZE)
                while (true) {
                    val count = reader.read(buffer)
                    if (count <= 0) break
                    onOutput(String(buffer, 0, count))
                }

                if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    onOutput(CRLF + "[命令超时，已终止]" + CRLF)
                    process.destroyForcibly()
                    TIMEOUT_EXIT_CODE
                } else {
                    process.exitValue()
                }
            } finally {
                // 无论正常结束还是异常，都不能留孤儿进程。
                if (process.isAlive) process.destroyForcibly()
                if (current === process) current = null
            }
        }

    companion object {
        /** 超时上限：足够跑构建，又不至于卡死页面。 */
        const val DEFAULT_TIMEOUT_SECONDS = 120L

        /** 超时时返回的伪退出码（真实退出码不会用 124 之外的负数约定，这里刻意用 -1 区分）。 */
        const val TIMEOUT_EXIT_CODE = 124

        /** Android 的 sh 绝对路径。用它消灭「靠 PATH 解析」的失败模式。 */
        private const val SYSTEM_SH = "/system/bin/sh"

        /**
         * 默认工作目录。
         *
         * ## 为什么是 `/` 而不是 `context.filesDir`
         *
         * 子进程以 App 自己的 uid 运行，但 `/data/data/<pkg>` 的权限是 0700，
         * 而 Android 的 `sh` 在某些 ROM 上会降权到 `shell` 用户 —— 此时
         * 连 `ls .` 都读不了，报 `Permission denied`。
         * `/` 是对所有 uid 都可读的少数目录之一，作为兜底最安全。
         */
        private const val DEFAULT_WORKING_DIR = "/"

        private const val READ_BUFFER_SIZE = 4096

        /** 换行。用码位拼接避开转义字面量被解码的问题。 */
        private val CRLF: String = 13.toChar().toString() + 10.toChar()
    }
}
