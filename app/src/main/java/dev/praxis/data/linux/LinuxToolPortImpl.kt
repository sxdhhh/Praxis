package dev.praxis.data.linux

import android.content.Context
import dev.praxis.core.linux.LinuxDistro
import dev.praxis.core.linux.LinuxExecResult
import dev.praxis.core.linux.LinuxStatus
import dev.praxis.core.linux.LinuxToolPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [LinuxToolPort] 的 Android 实现：让 AI 真的能在 Kali 容器里跑命令。
 *
 * ## 与 `ProotEnvPort` 的分工
 *
 * | | `ProotEnvPort` | 本类 |
 * |---|---|---|
 * | 使用者 | 设置页（人点） | 工具层（AI 调） |
 * | 建模 | 安装 / 启动 / SSH / FRP 这些**动作** | 「跑一条命令 → 拿输出」 |
 * | 输出 | `onLine` 回调刷 UI | 一次性返回汇总 |
 *
 * 两者共用同一份 [ProotEnvPort]（同一个实例），所以：
 *  - AI 装的环境，用户在设置页能看到「已安装」；
 *  - 用户在设置页装好的环境，AI 可以直接用。
 *
 * 这一点很重要：如果各建一份，会出现「AI 说装好了，设置页显示未安装」这种
 * 用户完全无法理解的状态。
 *
 * ## 为什么安装要加锁
 *
 * 模型可能在一轮里并行发起多个 `linux_exec`（带 autoInstall）。
 * 没有互斥就会**同时下两次 131 MB**、同时往同一个目录解压 ——
 * 结果是目录被两个 tar 交叉写坏，且磁盘占用翻倍。
 */
class LinuxToolPortImpl(
    private val context: Context,
    private val envPort: ProotEnvPort,
    private val distro: LinuxDistro = LinuxDistro.Kali,
) : LinuxToolPort {

    /** 安装互斥：同一时刻只允许一个安装流程。 */
    private val installMutex = Mutex()

    /** 执行互斥：proot 的两次并发执行会争用 PTX 通道，输出会串。 */
    private val execMutex = Mutex()

    override fun status(): LinuxStatus {
        val env = envPort.defaultEnvironment(distro)
        return LinuxStatus(
            distro = distro,
            prootAvailable = envPort.isProotAvailable(),
            installed = envPort.isInstalled(env),
            running = false,
        )
    }

    override suspend fun run(
        command: String,
        timeoutSeconds: Long,
        autoInstall: Boolean,
    ): LinuxExecResult = withContext(Dispatchers.IO) {
        val env = envPort.defaultEnvironment(distro)

        // ① proot 不在就没什么可做的 —— 这是 APK 打包问题，不是命令问题。
        if (!envPort.isProotAvailable()) {
            return@withContext LinuxExecResult(
                exitCode = -1,
                output = "缺少 proot 可执行文件（libproot.so），容器无法启动。" +
                    "这是 APK 打包问题，不是命令本身的问题。",
            )
        }

        // ② 需要时先装。
        if (!envPort.isInstalled(env)) {
            if (!autoInstall) {
                return@withContext LinuxExecResult(
                    exitCode = -1,
                    output = distro.label + " 尚未安装。安装需要下载约 " +
                        (distro.approxUnpackedBytes / 1024 / 1024) +
                        " MB 数据（解压后体积），可重试并传 autoInstall=true。",
                )
            }
            val ok = installMutex.withLock {
                // 双检：等锁期间可能已被别的调用装好了。
                if (envPort.isInstalled(env)) {
                    true
                } else {
                    envPort.install(env) { /* 进度对 AI 无意义，丢弃 */ }
                }
            }
            if (!ok) {
                return@withContext LinuxExecResult(
                    exitCode = -1,
                    output = "自动安装失败。可能原因：网络不可达、磁盘空间不足、" +
                        "或镜像地址失效。可先用 linux_status 查看状态。",
                )
            }
        }

        // ③ 执行。加锁避免并发串台。
        execMutex.withLock {
            execMutexGuard(
                command = command,
                env = env,
                timeoutSeconds = timeoutSeconds,
            )
        }
    }

    /**
     * 真正执行并把输出收成一块文本。
     *
     * 用 `ProotEnvPort.exec` 而不是自己起进程：那里已经处理好
     * 「清掉 LD_LIBRARY_PATH」「HOME/TERM/PATH」这些 proot 必需的环境变量，
     * 自己再写一遍就是对同一套坑重踩一次。
     */
    /**
     * 真正执行并把输出收成一块文本。
     *
     * 用 `ProotEnvPort.exec` 而不是自己起进程：那里已经处理好
     * 「清 LD_LIBRARY_PATH」「HOME/TERM/PATH」「PROOT_TMP_DIR」这些 proot 必需的环境，
     * 自己再写一遍就是对同一套坑重踩一次。
     *
     * 超时也在那一层管（进程级 waitFor + destroyForcibly），
     * **不用**容器内的 `timeout` 命令：Kali minimal 未必带 coreutils，
     * 那样会在本该超时时反而得到 `timeout: not found`。
     */
    private suspend fun execMutexGuard(
        command: String,
        env: dev.praxis.core.linux.LinuxEnvironment,
        timeoutSeconds: Long,
    ): LinuxExecResult {
        val lines = StringBuilder()
        var exit = -1
        runCatching {
            exit = envPort.exec(
                env = env,
                command = command,
                timeoutSeconds = timeoutSeconds,
                onLine = { line ->
                    if (lines.length < LinuxToolPort.MAX_OUTPUT_CHARS * 2) {
                        lines.append(line).append('\n')
                    }
                },
            )
        }.onFailure { error ->
            return LinuxExecResult(
                exitCode = -1,
                output = "执行失败：" + (error.message ?: error::class.simpleName),
            )
        }
        // 124 是 ProotEnvPort 的超时约定（与 coreutils 一致）。
        // 必须翻成显式标记：否则模型会把「超时」误读成「命令返回了 124」。
        val timedOut = exit == ProotEnvPort.TIMEOUT_EXIT_CODE
        return LinuxExecResult(
            exitCode = exit,
            output = lines.toString().trimEnd(),
            timedOut = timedOut,
        )
    }
}