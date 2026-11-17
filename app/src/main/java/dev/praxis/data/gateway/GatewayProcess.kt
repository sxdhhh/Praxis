package dev.praxis.data.gateway

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** 网关进程状态。 */
sealed interface GatewayState {
    data object Stopped : GatewayState
    data class Starting(val pid: Long? = null) : GatewayState
    data class Running(val pid: Long, val port: Int, val baseUrl: String) : GatewayState
    data class Crashed(val exitCode: Int, val logTail: String) : GatewayState
}

/**
 * Agent2API 网关的进程管理（启动/停止/日志/存活监听）。
 *
 * ## 两种运行方式
 *
 * 1. **外部网关**：用户已经部署了网关（本机或远端），Praxis 只需要连接。
 * 2. **本地进程**：APK 里带了网关二进制（等交叉编译/容器方案确认后实现）。
 *
 * 目前先支持方式 1（外部网关）—— 用户已有网关在跑，Praxis 连上去就行。
 *
 * ## 日志
 *
 * 网关的 stdout/stderr 逐行捕获到环形缓冲区（最多 200 行），
 * UI 的日志窗口直接显示这个缓冲。
 */
class GatewayProcess(
    /** 网关端口（默认 3065，与 Agent2API 一致）。 */
    val port: Int = 3065,
    /** 网关地址（用于外部网关连接模式）。 */
    val externalBaseUrl: String = "http://127.0.0.1:3065",
) {

    private val _state = MutableStateFlow<GatewayState>(GatewayState.Stopped)
    val state: StateFlow<GatewayState> = _state.asStateFlow()

    private val stopping = AtomicBoolean(false)
    private val processRunning = AtomicBoolean(false)
    private val reachable = AtomicBoolean(false)
    private val logBuffer = ConcurrentLinkedDeque<String>()

    fun logLines(): List<String> = logBuffer.toList()

    fun addLog(line: String) {
        logBuffer.addLast(line)
        while (logBuffer.size > 200) logBuffer.removeFirst()
    }

    suspend fun connectExternal(): Result<String> {
        return try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val request = okhttp3.Request.Builder()
                .url(externalBaseUrl.trimEnd('/') + "/health")
                .get()
                .build()
            val response = client.newCall(request).execute()
            response.use { resp ->
                if (resp.isSuccessful) {
                    reachable.set(true)
                    _state.value = GatewayState.Running(pid = 0, port = port, baseUrl = externalBaseUrl)
                    addLog("[gateway] 已连接外部网关 " + externalBaseUrl)
                    Result.success(externalBaseUrl)
                } else {
                    Result.failure(Exception("网关健康检查失败：HTTP " + resp.code))
                }
            }
        } catch (e: Exception) {
            addLog("[gateway] 连接失败：" + (e.message ?: "未知原因"))
            Result.failure(e)
        }
    }

    fun startLocal(binaryPath: String, args: List<String> = emptyList()): Result<Unit> {
        val binary = File(binaryPath)
        if (!binary.isFile) return Result.failure(Exception("网关二进制不存在：" + binaryPath))
        if (processRunning.get()) return Result.success(Unit)
        return runCatching {
            processRunning.set(true)
            val command = listOf(binaryPath) + args
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            _state.value = GatewayState.Starting(process.pid())
            thread(isDaemon = true, name = "gateway-log") {
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        addLog("[gateway] " + line)
                    }
                }
            }
            thread(isDaemon = true, name = "gateway-watch") {
                val exitCode = process.waitFor()
                processRunning.set(false)
                if (!stopping.get()) {
                    _state.value = GatewayState.Crashed(
                        exitCode = exitCode,
                        logTail = logLines().takeLast(6).joinToString("\n"),
                    )
                    addLog("[gateway] 进程退出（code=" + exitCode + "）")
                }
            }
            addLog("[gateway] 进程已启动 pid=" + process.pid())
        }.onFailure { error ->
            processRunning.set(false)
            addLog("[gateway] 启动失败：" + (error.message ?: error.javaClass.simpleName))
        }
    }

    fun stopLocal() {
        stopping.set(true)
        processRunning.set(false)
        _state.value = GatewayState.Stopped
        addLog("[gateway] 已停止")
    }


    // ── 内置模式：跑在手机自己的 proot 容器里 ─────────────────

    /** 长驻会话句柄（stop 时用）。 */
    private var containerSession: dev.praxis.core.linux.LinuxEnvPort.SessionHandle? = null

    /** 容器安装器（懒建 —— 它只在「启动内置网关」时用到）。 */
    var containerInstaller: GatewayContainerInstaller? = null

    /**
     * 在容器里启动网关（长驻 proot 会话）。
     *
     * 前置条件：
     *  1. rootfs 已装（Linux 环境页装过 Ubuntu/Kali）；
     *  2. [GatewayContainerInstaller.install] 已把二进制放进容器。
     *
     * 日志通过 [onLine] 直接进环形缓冲 —— 网关是前台进程，stdout 就是它的日志。
     */
    fun startInContainer(
        envPort: dev.praxis.core.linux.LinuxEnvPort,
        env: dev.praxis.core.linux.LinuxEnvironment,
    ): Result<Unit> {
        if (containerSession?.isAlive == true) return Result.success(Unit)
        val installer = containerInstaller
        if (installer == null || !installer.isInstalled(env)) {
            return Result.failure(IllegalStateException("网关还没装进容器（先点安装）"))
        }
        return runCatching {
            stopping.set(false)
            val args = GatewayContainerInstaller.runArgs(env)
            val handle = envPort.startSession(env, args) { line -> addLog("[gateway] " + line) }
            containerSession = handle
            if (!handle.isAlive) error("网关进程启动后立刻退出（看日志窗口找原因）")
            _state.value = GatewayState.Running(
                pid = 0,
                port = port,
                baseUrl = "http://127.0.0.1:" + port,
            )
            addLog("[gateway] 内置网关已启动（容器内 " + installer.binaryPath() + "）")
        }.onFailure { error ->
            addLog("[gateway] 容器启动失败：" + (error.message ?: error.javaClass.simpleName))
        }
    }

    /** 停止容器里的网关：先关会话（杀 proot），再补一刀 pkill 确保干净。 */
    suspend fun stopInContainer(
        envPort: dev.praxis.core.linux.LinuxEnvPort,
        env: dev.praxis.core.linux.LinuxEnvironment,
    ) {
        stopping.set(true)
        containerSession?.stop()
        containerSession = null
        runCatching {
            envPort.exec(env, "pkill -f agent2api-server", timeoutSeconds = 15L) { }
        }
        processRunning.set(false)
        _state.value = GatewayState.Stopped
        addLog("[gateway] 内置网关已停止")
    }

    /** 内置网关是否在跑。 */
    fun isContainerRunning(): Boolean = containerSession?.isAlive == true
    fun connection(apiKey: String = ""): dev.praxis.core.gateway.adapters.GatewayConnection {
        val base = when (val s = _state.value) {
            is GatewayState.Running -> s.baseUrl
            else -> externalBaseUrl
        }
        return dev.praxis.core.gateway.adapters.GatewayConnection(base, apiKey)
    }
}