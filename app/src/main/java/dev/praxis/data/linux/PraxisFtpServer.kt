package dev.praxis.data.linux

import android.util.Log
import dev.praxis.core.linux.FtpSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission
import org.apache.ftpserver.usermanager.impl.TransferRatePermission
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.apache.ftpserver.DataConnectionConfigurationFactory
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 应用进程内的 FTP 服务（Apache FtpServer）。
 *
 * ## 为什么不用容器内的 vsftpd —— 真机实测结论
 *
 * 在 proot 容器里 `apt-get install vsftpd` 的实际输出：
 *
 * ```
 * /bin/sh: 1: /usr/sbin/dpkg-preconfigure: Permission denied
 * /var/lib/dpkg/info/procps.postinst: 64: update-rc.d: Permission denied
 * dpkg: dependency problems prevent configuration of vsftpd
 * E: Sub-process /usr/bin/dpkg returned an error code (1)
 * ```
 *
 * 关键证据：`update-rc.d` 的权限是 `-rwxr-xr-x`，**执行仍被拒绝**。
 * 所以这不是权限位问题，而是 **Android SELinux 禁止从 app 数据目录执行脚本**。
 * vsftpd 依赖 procps，两者的 maintainer script 都跑不起来，
 * 于是一起停在 unconfigured，`/usr/sbin/vsftpd` 根本不存在。
 *
 * ## 为什么这条路可行
 *
 * Apache FtpServer 是**纯 JVM 库**，跑在我们自己的进程里：
 *  - 不依赖容器里任何二进制，SELinux / dpkg 全部绕开；
 *  - `homeDirectory` 直接指向 rootfs 的绝对路径，FTP 客户端看到的就是容器文件；
 *  - 配置在内存里，天然可持久化（不像容器内配置散落在 /etc）。
 *
 * Operit 用的正是这个库（`FtpServerManager.kt`）。
 *
 * ## 生命周期
 *
 * 服务的存活与 App 进程一致：进程被回收，FTP 就停。这是有意为之 ——
 * 让一个 FTP 服务在后台长期监听，用户难以及时察觉它在跑。
 */
class PraxisFtpServer {

    @Volatile
    private var server: FtpServer? = null

    @Volatile
    private var current: FtpSettings? = null

    /** 当前是否在监听。 */
    val isRunning: Boolean get() = server?.isStopped == false

    /** 启动时使用的设置（未启动为 null）。 */
    val runningSettings: FtpSettings? get() = if (isRunning) current else null

    /**
     * 启动服务。已启动时先停掉再按新设置启动（配置变更的唯一正确路径）。
     *
     * @return 失败原因；成功返回 null。
     *
     *   ## 为什么返回原因而不是抛异常
     *
     *   调用方是 ViewModel，它要做的第一件事是把原因**显示给用户**。
     *   抛异常会穿过协程边界变成「应用崩了」，而用户真正需要的是
     *   「端口被占用」这种可操作的信息。
     */
    suspend fun start(settings: FtpSettings): String? = withContext(Dispatchers.IO) {
        // 先校验：不合格的设置在启动前就拦下，避免「起来了但用不了」。
        settings.problem?.let { return@withContext it }

        // 配置变更 = 停掉重建。FtpServer 不支持热改端口/用户表。
        stopInternal()

        // 根目录必须在：FtpServer 启动时不会校验，只会在客户端登录后才报错。
        val root = File(settings.homeDirectory)
        if (!root.isDirectory) {
            return@withContext "根目录不存在：${settings.homeDirectory}"
        }

        runCatching {
            val factory = FtpServerFactory()

            val listener = ListenerFactory().apply {
                port = settings.port
                val dataConfig = DataConnectionConfigurationFactory().apply {
                    // 被动端口段：不给的话客户端在 NAT / 移动网络下会连不上数据通道。
                    setPassivePorts("${settings.passivePortLow}-${settings.passivePortHigh}")
                    // ## 为什么显式设外部地址
                    //
                    // 手机常有多张网卡（WiFi + 移动数据）。不指定时，
                    // FtpServer 会把控制连接的本地地址当作被动地址发给客户端 ——
                    // 若客户端从另一张网卡进来，那个地址它根本路由不到。
                    //
                    // 取不到地址时**不设**（让库自己决定），而不是传空串：
                    // 空串会让客户端收到一个无效的 PASV 应答。
                    localIpv4()?.let { setPassiveExternalAddress(it) }
                }
                dataConnectionConfiguration = dataConfig.createDataConnectionConfiguration()
            }
            factory.addListener("default", listener.createListener())

            val userManager = PropertiesUserManagerFactory().createUserManager()
            val authorities = mutableListOf<Authority>()
            if (!settings.readOnly) authorities += WritePermission()
            // 并发登录上限：给一个上限避免同一账号被大量客户端打满。
            authorities += ConcurrentLoginPermission(settings.maxConnections, settings.maxConnections)
            // 不限速（-1 / -1）：局域网传文件时限速只会让人等。
            authorities += TransferRatePermission(-1, -1)

            val user = BaseUser().apply {
                name = settings.user
                password = settings.password
                homeDirectory = settings.homeDirectory
                this.authorities = authorities
            }
            userManager.save(user)
            factory.userManager = userManager

            val created = factory.createServer()
            created.start()
            server = created
            current = settings
            Log.i(TAG, "FTP 已启动：端口 ${settings.port}，根目录 ${settings.homeDirectory}")
            null
        }.getOrElse { e ->
            // 常见原因：端口被占用（EADDRINUSE）。把原始消息带出去，
            // 用户据此能判断是换个端口还是先关掉别的服务。
            Log.e(TAG, "FTP 启动失败", e)
            "启动失败：${e.message ?: e::class.simpleName}"
        }
    }

    /** 停止服务。幂等。 */
    suspend fun stop() = withContext(Dispatchers.IO) { stopInternal() }

    private fun stopInternal() {
        val s = server ?: return
        runCatching { s.stop() }
            .onFailure { Log.w(TAG, "FTP 停止时出错（忽略）", it) }
        server = null
        current = null
    }

    /**
     * 本机的 IPv4 地址（优先非回环、非链路本地）。
     *
     * 取不到返回 null：调用方据此决定不设被动外部地址，
     * 而不是传一个可能错误的地址。
     */
    private fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }.getOrNull()

    private companion object {
        const val TAG = "PraxisFtpServer"
    }
}
