package dev.praxis.data.imagegen

import dev.praxis.core.imagegen.EngineLaunchError
import dev.praxis.core.imagegen.EngineLaunchRules
import dev.praxis.core.imagegen.EngineLaunchSpec
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 形态 C：随包引擎的进程管理（文档 §1.1 的 C + §3.1 的起法）。
 *
 * ## 为什么只有这一条能让「生图完全本地可用」
 *
 * - 形态 A 要求用户另装 Local Dream（现在的界面就是这么写的：「先手动准备」）；
 * - 形态 B 要在 proot 容器里跑，ptrace 开销与 QNN 设备节点访问都得实机验证；
 * - 形态 C **不需要用户做任何事**：引擎 `.so` 就在 APK 里，从 `nativeLibraryDir` 起。
 *
 * 许可：引擎是 Local Dream 的 CC BY-NC 4.0 代码，**随包内置只适合自用**
 * （文档 §1.1 表格的「可行性」一栏写的就是这句）。公开分发前必须换成 A/B。
 *
 * ## 为什么引擎必须从 nativeLibraryDir 起
 *
 * API 29+ 的 W^X：应用可写目录（`filesDir`）里的二进制**不能执行**，
 * `setExecutable(true, false)` 也改不了。可执行的只有 `nativeLibraryDir` ——
 * 那是系统解压 APK 里 jniLibs 中那些 .so 的地方。
 *
 * ⚠️ 这里踩过一个坑：注释里**不能**写出斜杠加星号的连写形式 —— Kotlin 的块注释
 * 是可嵌套的，那两字符会开一层内层注释，让整个 KDoc 变成「未闭合」
 * （编译器报的是文件末尾 `Unclosed comment`，而真正的起点在这行）。
 */
class EngineProcess(
    /** `applicationInfo.nativeLibraryDir`（由 :app 装配时传入）。 */
    private val nativeLibraryDir: () -> String,
    /** 引擎可执行文件名（与 jniLibs 里的文件名一致）。 */
    private val engineName: String = ENGINE_NAME,
    /** 端口；与 EngineProtocol 的默认一致。 */
    private val port: Int = EngineLaunchSpec.DEFAULT_PORT,
) {

    private var process: Process? = null
    private val stopping = AtomicBoolean(false)

    /** 日志尾部：启动失败时最有用的东西（引擎把 usage/错误都打在 stdout）。 */
    private val logLines = ArrayDeque<String>()

    /** 引擎文件在不在（不在 = 这个构建没有内置引擎）。 */
    fun engineFile(): File = File(nativeLibraryDir(), engineName)

    fun isInstalled(): Boolean = runCatching { engineFile().isFile }.getOrDefault(false)

    fun isRunning(): Boolean = process?.isAlive == true

    /** 端口（界面与 client 都要它保持一致）。 */
    fun port(): Int = port

    /**
     * 起引擎。
     *
     * @return null = 起来了；非 null = 起不来的原因（可直接展示）。
     */
    fun start(spec: EngineLaunchSpec): EngineLaunchError? {
        if (isRunning()) return null
        val engine = engineFile()
        val error = EngineLaunchRules.validate(
            spec = spec,
            engineExists = runCatching { engine.isFile }.getOrDefault(false),
            modelDirEmpty = !File(spec.modelDir).isDirectory,
            libDirFiles = spec.libDir?.let { dir ->
                runCatching {
                    File(dir).listFiles()?.map { it.name } ?: emptyList()
                }.getOrDefault(emptyList())
            },
        )
        if (error != null) return error

        return runCatching {
            stopping.set(false)
            val command = EngineLaunchRules.command(spec.copy(enginePath = engine.absolutePath))
            // redirectErrorStream：引擎的启动错误全走 stdout，分开收会漏掉一半。
            val started = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            process = started
            // 收日志尾部（有界）：不读会阻塞子进程；全读会吃内存。
            thread(isDaemon = true, name = "praxis-engine-log") {
                runCatching {
                    started.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(logLines) {
                            logLines.addLast(line)
                            while (logLines.size > LOG_TAIL_LINES) logLines.removeFirst()
                        }
                    }
                }
            }
            null
        }.getOrElse { error ->
            process = null
            // 参数已过校验、进程仍起不来：**不要把原因说成「文件不在」** ——
            // 那会让用户去查一个不存在的问题（文件明明在）。
            // 真实异常记进日志尾部，界面与 StartFailed 一起显示。
            synchronized(logLines) {
                logLines.addLast("启动失败：" + (error.message ?: error.javaClass.simpleName))
            }
            EngineLaunchError.StartFailed
        }
    }

    /** 停引擎（用户点「停止」或切换模型时用）。 */
    fun stop() {
        stopping.set(true)
        runCatching { process?.destroy() }
        process = null
    }

    /** 最近日志（最多 [LOG_TAIL_LINES] 行）。 */
    fun logTail(maxLines: Int = 12): String = synchronized(logLines) {
        logLines.toList().takeLast(maxLines).joinToString("\n")
    }

    companion object {
        /** 引擎可执行文件名（Local Dream 的 BackendService 同款常量名）。 */
        const val ENGINE_NAME: String = "libstable_diffusion_core.so"

        /** 日志尾部保留多少行。 */
        const val LOG_TAIL_LINES: Int = 200
    }
}
