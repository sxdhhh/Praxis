package dev.praxis.data.gateway

import android.os.Build
import dev.praxis.core.linux.LinuxEnvironment
import dev.praxis.core.linux.LinuxEnvPort
import dev.praxis.core.linux.ProotCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 把 Agent2API 网关**装到手机自己的容器里**（而不是连电脑）。
 *
 * ## 为什么这条路是对的
 *
 * proot 容器与 App **共享同一个网络命名空间** —— 容器里监听 127.0.0.1:3065，
 * App 侧直接就能连 127.0.0.1:3065，中间不需要任何转发。
 *
 * ## 二进制为什么能直接跑（不需要 glibc 适配）
 *
 * Agent2API 官方 linux-arm64 产物是**静态链接**（ELF e_machine=183，无 INTERP 段）。
 * 没有动态加载器意味着它不依赖 rootfs 里的 libc 版本，Ubuntu 24.04 / Kali 都能跑。
 *
 * ## 为什么用容器里的 tar 解包
 *
 * rootfs 自带 GNU tar，一条命令就够；Kotlin 里手写 tar 解析器是没人读得完的代码。
 *
 * ## 许可
 *
 * Agent2API 是 AGPL-3.0。这里**不把它打进 APK**，而是运行时从官方发布页
 * 下载进容器、作为**独立进程**运行。Praxis 与它只通过 localhost HTTP 往来，
 * 不构成衍生作品，Praxis 保持 MIT。
 */
class GatewayContainerInstaller(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build(),
) {

    /** 容器内的安装目录。 */
    private val containerDir: String = "/opt/agent2api"

    /** 下载缓存目录（用 [withCacheDir] 注入）。 */
    private var cacheOverride: File? = null

    fun withCacheDir(dir: File): GatewayContainerInstaller {
        cacheOverride = dir
        return this
    }

    private fun cacheDirForDownloads(): File {
        val dir = cacheOverride ?: error("未设置缓存目录，见 withCacheDir")
        if (!dir.isDirectory) dir.mkdirs()
        return dir
    }

    /** 宿主侧的安装目录（rootfs 是普通目录树，可以直接写文件）。 */
    private fun hostDir(env: LinuxEnvironment): File =
        File(env.rootfsDir, containerDir.removePrefix("/"))

    /** 网关二进制在容器里的路径。 */
    fun binaryPath(): String = containerDir + "/agent2api-server"

    /** 是否已经装好（判据：二进制存在且非空）。 */
    fun isInstalled(env: LinuxEnvironment): Boolean {
        val binary = File(hostDir(env), "agent2api-server")
        return binary.isFile && binary.length() > 1_000_000
    }

    /**
     * 下载并安装到容器。
     *
     * @param onLine 逐行进度（直接喂给网关日志窗口）
     */
    suspend fun install(
        envPort: LinuxEnvPort,
        env: LinuxEnvironment,
        onLine: (String) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!env.isInstalled) {
                error("容器还没装好（rootfs 不存在）。先到 Linux 环境页装一次 Ubuntu 或 Kali。")
            }
            val asset = assetForCurrentAbi()
            onLine("准备下载 " + asset + " …")
            val tarball = download(asset, onLine)

            val dir = hostDir(env)
            if (!dir.isDirectory && !dir.mkdirs()) error("无法创建目录：" + dir.absolutePath)
            val staged = File(dir, "gateway.tar.gz")
            tarball.copyTo(staged, overwrite = true)
            onLine("已就位（" + staged.length() / 1048576 + " MB），正在容器内解包…")

            val command = "cd " + containerDir +
                " && tar -xzf gateway.tar.gz" +
                " && chmod +x agent2api-server" +
                " && rm -f gateway.tar.gz" +
                " && ls -l agent2api-server"
            val exit = envPort.exec(env, command, timeoutSeconds = 180L) { line -> onLine("  " + line) }
            if (exit != 0) error("容器内解包失败（退出码 " + exit + "）")
            if (!isInstalled(env)) error("解包后找不到可执行文件，安装未完成")
            onLine("安装完成：" + binaryPath())
        }.onFailure { error ->
            onLine("安装失败：" + (error.message ?: error.javaClass.simpleName))
        }
    }

    /** 按当前设备 ABI 选发布资产（手机几乎都是 arm64-v8a）。 */
    private fun assetForCurrentAbi(): String {
        val abis = Build.SUPPORTED_ABIS.toList()
        return when {
            abis.any { it == "arm64-v8a" } -> "agent2api-server-linux-arm64.tar.gz"
            abis.any { it == "x86_64" } -> "agent2api-server-linux-amd64.tar.gz"
            else -> error("这个设备架构（" + abis.joinToString("/") + "）没有对应的网关产物")
        }
    }

    /** 下载（带 sha256 校验 —— 我们要执行它）。 */
    private fun download(asset: String, onLine: (String) -> Unit): File {
        val target = File(cacheDirForDownloads(), asset)
        if (target.isFile && target.length() > 1_000_000) {
            onLine("使用已下载的安装包（" + target.length() / 1048576 + " MB）")
            return target
        }
        val base = "https://github.com/aimod-cc/Agent2API/releases/latest/download/"
        fetch(base + asset, target, onLine)
        val expected = runCatching { fetchText(base + asset + ".sha256") }.getOrNull()
        if (expected != null) {
            val actual = sha256Of(target)
            val declared = expected.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
            if (declared.length == 64) {
                if (!actual.equals(declared, ignoreCase = true)) {
                    target.delete()
                    error("校验失败：下载到的文件与官方 sha256 不一致，已删除")
                }
                onLine("sha256 校验通过")
            }
        } else {
            onLine("官方校验文件拿不到，跳过校验（安装包大小 " + target.length() / 1048576 + " MB）")
        }
        return target
    }

    private fun fetch(url: String, target: File, onLine: (String) -> Unit) {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("下载失败：HTTP " + response.code)
            val body = response.body ?: error("下载失败：响应为空")
            val total = body.contentLength()
            var written = 0L
            var lastReport = 0L
            target.outputStream().use { sink ->
                body.byteStream().use { source ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = source.read(buffer)
                        if (read <= 0) break
                        sink.write(buffer, 0, read)
                        written += read
                        if (total > 0 && written - lastReport > total / 5) {
                            lastReport = written
                            onLine("  下载中 " + written * 100 / total + "%")
                        }
                    }
                }
            }
        }
    }

    private fun fetchText(url: String): String? {
        val request = Request.Builder().url(url).get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        /**
         * 构造「在容器里前台运行网关」的 proot 参数。
         *
         * 必须**前台**跑（不能用 nohup 甩到后台）：proot 的 bind 挂载是
         * ptrace 里的路径翻译，一旦进程脱离 proot，它看到的就是真实的
         * Android 文件系统 —— 容器路径全部消失，网关立刻起不来。
         */
        fun runArgs(env: LinuxEnvironment, containerDir: String = "/opt/agent2api"): List<String> {
            val command = "cd " + containerDir + " && exec ./agent2api-server"
            return ProotCommand.execArgs(env, command)
        }
    }
}