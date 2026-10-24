package dev.praxis.core.linux

/**
 * FTP 服务器配置（容器内的 `vsftpd`）。
 *
 * ## 为什么是「容器内装 vsftpd」而不是照抄 Operit 的 Apache FtpServer
 *
 * Operit 的 `FtpServerManager` 把 `org.apache.ftpserver` 嵌进 **App 进程**，
 * 用 JVM 直接读 `filesDir/usr/var/lib/proot-distro/installed-rootfs/ubuntu`。
 * 那条路在 Praxis 走不通，原因不是技术洁癖：
 *
 *  - Praxis 的容器内容（apt 装的东西、用户改的配置）都在 rootfs 里，
 *    而这些内容由 **proot 内的进程**产生。用一个宿主 JVM 进程直接按路径
 *    读同一个目录，看起来能读到，但**文件属主、权限位、符号链接**
 *    会被 Android 的 SELinux 与 uid 映射解释成另一套结果 ——
 *    用户在容器里 `chmod 600` 的文件，FTP 侧看到的是宿主 uid，可能读不了。
 *  - 更关键的是**生命周期**：Praxis 的 sshd / frpc 已经统一走
 *    `LinuxToolPort.startSession`（随容器启停、日志回流到同一个日志区）。
 *    再引入一个跑在 App 进程里的 FTP 服务，就多了一套独立的启停状态，
 *    用户会遇到「容器停了但 FTP 还在跑」或反之。
 *
 * 所以这里选**同一条架构路径**：容器内 `apt install vsftpd` + 生成独立配置
 * + 前台运行，与 [SshdScript] / [FrpScript] 完全一致。
 *
 * ## proot 下 vsftpd 的三个必须处理的坑
 *
 * ### 1. 必须开 `allow_writeable_chroot`
 *
 * vsftpd 拒绝让用户 chroot 到一个**可写**目录（安全设计），报
 * `500 OOPS: vsftpd: refusing to run with writable root inside chroot()`。
 * 而我们的根就是用户家目录，必须可写 —— 否则 FTP 只能下载不能上传，
 * 那这个功能就没意义。
 *
 * ### 2. 不能用 PAM（与 sshd 同一个原因）
 *
 * proot 里 `pam_loginuid` / `pam_systemd` 会失败，导致**所有登录被拒**。
 * 所以 `pam_service_name` 指向一个我们自建的极简 PAM 配置（只有
 * `pam_unix`），而不是系统的 `vsftpd` 服务名。
 *
 * ### 3. 被动模式必须固定端口段
 *
 * FTP 的主动模式要求客户端能回连服务端 —— 在手机 + NAT + 防火墙下基本不可用。
 * 所以必须用被动模式，并**固定一段端口**（否则每次协商一个随机端口，
 * 用户没法在路由器/防火墙里放行）。这与 Operit 的
 * `setPassivePorts("2128-2136")` 是同一个判断，端口段也沿用它的默认值。
 *
 * ## 端口为什么不能是 21
 *
 * 与 [SshConfig] 同一条内核约束：绑定 < 1024 需要 `CAP_NET_BIND_SERVICE`，
 * proot 只是把 uid 映射成 0，**不给内核能力**。写 21 的后果是
 * `500 OOPS: cannot bind listening socket` 然后退出。
 *
 * @param port FTP 控制端口（命令通道）。
 * @param passivePorts 被动模式数据端口段（`"2128-2136"` 这种格式）。
 * @param user 登录用户名。
 * @param homeDir 用户家目录，同时作为 chroot 根（FTP 客户端看到的 `/`）。
 */
data class FtpConfig(
    /** 控制端口。必须 >= 1024（见类 KDoc）。默认 2127，与 Operit 一致。 */
    val port: Int = DEFAULT_PORT,
    /**
     * 被动模式端口段，格式 `"最小-最大"`。
     *
     * 只保留一段：多段对手机没有实际收益，反而让「该放行哪些端口」更难说清。
     */
    val passivePorts: String = DEFAULT_PASSIVE_PORTS,
    /** 登录用户名。默认 `ubuntu`，与 Operit 的 `FTP_USERNAME` 一致。 */
    val user: String = "ubuntu",
    /**
     * 家目录（容器内绝对路径），同时是 chroot 根。
     *
     * 默认 `/home/ubuntu`。**不要**填 `/`：chroot 到 `/` 在 proot 下等于
     * 没有隔离（客户端能看到整个 rootfs），而且可写根目录检查会直接拒启。
     */
    val homeDir: String = "/home/ubuntu",
) {
    /**
     * 控制端口是否可用。
     *
     * 与 [SshConfig.isPortUsable] 同一口径：单独属性而不是 init 里 require，
     * 这样 UI 在「用户还在改数字」时也能显示问题而不是抛异常。
     */
    val isPortUsable: Boolean get() = port in MIN_UNPRIVILEGED_PORT..65535

    /** 端口问题说明；合法时为 null。 */
    val portProblem: String?
        get() = when {
            port < MIN_UNPRIVILEGED_PORT ->
                "端口必须 ≥ $MIN_UNPRIVILEGED_PORT：Android 上普通应用无法绑定 1024 以下端口，" +
                    "vsftpd 会直接启动失败。"
            port > 65535 -> "端口必须 ≤ 65535。"
            // 与 SSH 默认端口冲突是最常见的实际错误：用户开了 SSH 又开 FTP，
            // 两个服务抢同一个端口，后启动的那个失败但报错在日志深处。
            port == 2222 -> "2222 是 SSH 的默认端口，这里换一个（如 $DEFAULT_PORT）。"
            else -> null
        }

    /** 被动端口段是否可解析成合法范围。 */
    val isPassiveRangeValid: Boolean
        get() {
            val parts = passivePorts.trim().split("-")
            if (parts.size != 2) return false
            val lo = parts[0].trim().toIntOrNull() ?: return false
            val hi = parts[1].trim().toIntOrNull() ?: return false
            return lo in MIN_UNPRIVILEGED_PORT..65535 &&
                hi in MIN_UNPRIVILEGED_PORT..65535 &&
                lo <= hi
        }

    /** 被动端口段的问题说明；合法时为 null。 */
    val passiveRangeProblem: String?
        get() = if (isPassiveRangeValid) {
            null
        } else {
            "被动端口段要写成「最小-最大」（例如 $DEFAULT_PASSIVE_PORTS），" +
                "两端都必须 ≥ $MIN_UNPRIVILEGED_PORT。"
        }

    /** 用户名是否可用（vsftpd 的用户名不允许空格与斜杠）。 */
    val isUserUsable: Boolean
        get() = user.isNotBlank() &&
            user.none { it.isWhitespace() || it == '/' || it == ':' }

    companion object {
        /** 非特权端口下界（与 [SshConfig.MIN_UNPRIVILEGED_PORT] 同值）。 */
        const val MIN_UNPRIVILEGED_PORT = 1024

        /** 默认控制端口。沿用 Operit 的 2127，避开 21 与常见服务端口。 */
        const val DEFAULT_PORT = 2127

        /**
         * 默认被动端口段。沿用 Operit 的 `2128-2136`（9 个端口）。
         *
         * 9 个够用：被动模式下**每个数据传输**占一个端口，用完即释放。
         * 并发 9 个传输已经超出手机 FTP 客户端的常见行为。
         */
        const val DEFAULT_PASSIVE_PORTS = "2128-2136"

        /** 我们管理的配置文件名（容器内绝对路径），与 `SshdScript` 同一命名习惯。 */
        const val CONFIG_PATH = "/etc/vsftpd.praxis.conf"
    }
}

/**
 * 生成 vsftpd 的配置与启停参数。
 *
 * 结构与 [SshdScript] / [FrpScript] 刻意保持一致：
 *  - `installScript` 幂等安装 + 写配置；
 *  - `setPasswordScript` 独立设置密码（不走命令行参数）；
 *  - `runArgs` 前台运行，生命周期 == 进程生命周期。
 */
object FtpServerScript {

    /** 配置路径（转发到 [FtpConfig.CONFIG_PATH]，保留单一来源）。 */
    const val CONFIG_PATH = FtpConfig.CONFIG_PATH

    /** PAM 配置路径。 */
    const val PAM_PATH = "/etc/pam.d/vsftpd.praxis"

    /**
     * 安装 vsftpd 并写我们的配置。幂等：可重复执行。
     *
     * ## 为什么用 `--no-install-recommends`
     *
     * vsftpd 的 recommends 会拖进 `ssl-cert` 之类的东西，
     * 而我们不用 TLS（见配置里 `ssl_enable=NO` 的说明）。
     * 与 `SshdScript` 同一条判断：这类「系统服务」装得越少越安全。
     */
    fun installScript(cfg: FtpConfig): String = buildString {
        appendLine("set -e")
        // 与 sshd 同理：debconf 交互会让 apt 卡住不动。
        appendLine("export DEBIAN_FRONTEND=noninteractive")
        appendLine("apt-get update")
        appendLine("apt-get install -y --no-install-recommends vsftpd")
        // 建用户（幂等）。`-m` 建家目录，`-s /bin/bash` 便于用户也能 SSH 进来。
        //
        // `|| ` 之后的兜底不能省：`useradd` 在用户已存在时返回 9，
        // 而 `set -e` 会因此中断整个脚本 —— 第二次点「启用 FTP」就失败了。
        appendLine(
            "id -u ${shellQuote(cfg.user)} >/dev/null 2>&1 || " +
                "useradd -m -d ${shellQuote(cfg.homeDir)} -s /bin/bash ${shellQuote(cfg.user)}",
        )
        // 家目录必须存在且属主正确：`useradd -d` 只写 passwd，
        // 目录已被删掉时不会重建。
        appendLine("mkdir -p ${shellQuote(cfg.homeDir)}")
        appendLine(
            "chown ${shellQuote(cfg.user)}:${shellQuote(cfg.user)} ${shellQuote(cfg.homeDir)}",
        )
        appendLine(writePamConfig())
        appendLine(writeConfig(cfg))
        // Ubuntu 的 vsftpd 包自带 init.d 脚本与 systemd unit，
        // 但 proot 里没有 systemd —— 若它们被触发会立即失败。
        // 我们不启动系统服务，只用 `-obackground=NO` 前台跑（见 [runArgs]）。
        appendLine("echo '已安装 vsftpd 并生成 ${FtpConfig.CONFIG_PATH}'")
    }

    /**
     * 设置 FTP 用户密码。
     *
     * 与 [SshdScript.setPasswordScript] 逐字同构（含同样的诚实说明）：
     * `chpasswd` 只从 stdin 读，密码不进命令行参数；但 `printf` 的参数本身
     * 仍在 cmdline 里，所以这是「不给容器内其他进程顺便看到」，
     * 不是密码学意义上的保密。
     */
    fun setPasswordScript(user: String, password: String): String {
        val escapedUser = user.replace("'", "'\\''")
        val escapedPassword = password.replace("'", "'\\''")
        return buildString {
            appendLine("set -e")
            appendLine("printf '%s:%s\\n' '$escapedUser' '$escapedPassword' | chpasswd")
            appendLine("echo '密码已更新'")
        }
    }

    /**
     * 前台启动 vsftpd。
     *
     * ## `-obackground=NO` 是必须的
     *
     * vsftpd 默认 fork 到后台。那样我们的进程句柄立刻退出，
     * UI 会显示「已停止」但 vsftpd 其实还在跑 —— 状态永远对不上。
     * 这与 [SshdScript.runArgs] 里 `-D` 的理由完全相同。
     *
     * ## 为什么还要 `-olisten=YES`
     *
     * 默认配置里 `listen=NO` + `listen_ipv6=YES`（走 IPv6 socket 同时接 IPv4）。
     * proot 里 IPv6 经常不可用，`listen_ipv6=YES` 会让 bind 失败。
     * 显式 `listen=YES` + `listen_ipv6=NO` 走纯 IPv4，
     * 在手机上（尤其开热点/切网络时）更稳。
     */
    fun runArgs(cfg: FtpConfig): List<String> = listOf(
        "/usr/sbin/vsftpd",
        "-obackground=NO",
        "-olisten=YES",
        "-oport=${cfg.port}",
        FtpConfig.CONFIG_PATH,
    )

    /**
     * 极简 PAM 配置：只用 `pam_unix`。
     *
     * ## 为什么不用系统自带的 `/etc/pam.d/vsftpd`
     *
     * 那个文件包含 `pam_loginuid.so`（写 `/proc/self/loginuid`）与
     * `pam_systemd.so`（要 systemd）。proot 里两者都失败，
     * 结果是**任何密码都登不进去**，而错误只出现在 vsftpd 的 debug 日志里。
     * 这与 sshd 的 `UsePAM no` 是同一个根因的两种解法（那边直接关 PAM，
     * 这边必须留 PAM 但精简掉有问题的模块 —— vsftpd 没有「关 PAM」的选项）。
     */
    private fun writePamConfig(): String = buildString {
        append("printf '%s\\n' ")
        append("'auth required pam_unix.so' ")
        append("'account required pam_unix.so' ")
        append("'session required pam_unix.so' ")
        append("> $PAM_PATH")
    }

    /**
     * 配置正文。
     *
     * 每个选项之所以写出来，是因为 vsftpd 的默认值里
     * 有一半是「能启动但功能不对」，排查时最耗时。
     */
    private fun writeConfig(cfg: FtpConfig): String = buildString {
        append("printf '%s\\n' ")
        // ── 监听 ──
        // 纯 IPv4：见 runArgs 的说明。不设则走 listen_ipv6，proot 下易 bind 失败。
        append("'listen=YES' ")
        append("'listen_ipv6=NO' ")
        append("'listen_port=${cfg.port}' ")
        // 不设则默认 YES，会让 vsftpd 以 standalone 模式再起一份。
        append("'background=NO' ")
        // ── 匿名 ──
        // 不设则默认允许匿名。手机上是纯粹的风险面。
        append("'anonymous_enable=NO' ")
        // ── 本地用户 ──
        append("'local_enable=YES' ")
        // 不设则只读：FTP 的主要用途（往容器里传文件）就没了。
        append("'write_enable=YES' ")
        // 上传文件的权限位。022 = 组/其他不可写，属主可写，与 sshd 场景一致。
        append("'local_umask=022' ")
        // ── chroot ──
        // 把用户锁在家目录里。不设则能看到整个 rootfs（含 /etc/shadow）。
        append("'chroot_local_user=YES' ")
        // ★ 关键：见 FtpConfig 类 KDoc 第 1 条。不设则 chroot 因「根可写」直接拒启。
        append("'allow_writeable_chroot=YES' ")
        // ── PAM ──
        // 指向我们的极简配置（见 writePamConfig）。
        append("'pam_service_name=vsftpd.praxis' ")
        // ── 被动模式 ──
        // 固定端口段：不设则每个传输协商随机端口，用户无法放行防火墙。
        val (lo, hi) = passiveRange(cfg)
        append("'pasv_enable=YES' ")
        append("'pasv_min_port=$lo' ")
        append("'pasv_max_port=$hi' ")
        // 被动模式回给客户端的地址。写 `0.0.0.0` 让 vsftpd 自动取本地 IP ——
        // **不能**写死具体 IP：手机切 Wi-Fi / 热点时 IP 会变。
        append("'pasv_address=0.0.0.0' ")
        // ── 传输编码 ──
        // UTF-8：中文文件名必须。不设则中文名显示乱码，且上传会失败。
        append("'utf8_filesystem=YES' ")
        // ── TLS ──
        // 关闭 TLS：开启需要证书，而自签证书会让主流客户端报警告或拒连。
        // 局域网文件传输场景下收益不抵成本，如实关闭而不是留个半开的开关。
        append("'ssl_enable=NO' ")
        // ── 日志与限制 ──
        // 不设则连接记录只进 syslog，proot 里没有 syslog → 完全看不到。
        append("'xferlog_enable=YES' ")
        append("'xferlog_file=/var/log/vsftpd.praxis.log' ")
        // 每 IP 最大并发连接：防止客户端多线程模式把手机打满。
        append("'max_per_ip=3' ")
        // 空闲超时（秒）。默认 300 太长：客户端异常断开后端口要等 5 分钟才释放。
        append("'idle_session_timeout=120' ")
        append("'data_connection_timeout=120' ")
        // 本地时间：日志时间戳与用户手机时钟一致，排查时不用换算时区。
        append("'use_localtime=YES' ")
        append("> ${FtpConfig.CONFIG_PATH}")
    }

    /**
     * 把 `"2128-2136"` 解析成 `(2128, 2136)`。
     *
     * 解析失败时回落到默认段而不是抛异常：`FtpConfig.isPassiveRangeValid`
     * 已经负责在 UI 上拦不合法输入，这里拿到脏值时宁可「用一个能用的段」
     * 也不要让整个安装脚本失败。
     */
    fun passiveRange(cfg: FtpConfig): Pair<Int, Int> {
        val parts = cfg.passivePorts.trim().split("-")
        val lo = parts.getOrNull(0)?.trim()?.toIntOrNull()
        val hi = parts.getOrNull(1)?.trim()?.toIntOrNull()
        return if (lo != null && hi != null && lo <= hi) {
            lo to hi
        } else {
            val d = FtpConfig.DEFAULT_PASSIVE_PORTS.split("-")
            d[0].toInt() to d[1].toInt()
        }
    }

    /**
     * 单引号包裹，用于塞进 `printf '%s\n' '...'` 的参数里。
     *
     * 与 [SshdScript] 里的转义同构：单引号内的 `'` 要写成 `'\''`。
     * 用户名与家目录来自用户输入，不转义会被命令注入 ——
     * 虽然这是用户自己的容器，但没有理由留一个「在 rootfs 里执行任意命令」的入口。
     */
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}