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

    fun connection(apiKey: String = ""): dev.praxis.core.gateway.adapters.GatewayConnection {
        val base = when (val s = _state.value) {
            is GatewayState.Running -> s.baseUrl
            else -> externalBaseUrl
        }
        return dev.praxis.core.gateway.adapters.GatewayConnection(base, apiKey)
    }
}