package dev.praxis.core.linux

/**
 * SSH 服务器配置（容器内的 `openssh-server`）。
 *
 * ## 三个 Android 上必须绕开的现实
 *
 * ### 1. 端口不能是 22
 *
 * Linux 内核要求绑定 < 1024 的端口需要 `CAP_NET_BIND_SERVICE`。Android 上
 * 普通应用没有这个 capability，**即使容器内的 proot 让你看起来是 root**——
 * proot 只是把 uid 映射成 0，并不给内核能力。
 *
 * 所以 sshd 必须监听 **1024 以上**的端口。默认 2222（ssh 的常用替代端口）。
 * 如果写 22，sshd 会报 `Bind to port 22 on 0.0.0.0 failed: Permission denied`
 * 然后退出——用户看到的是「ssh 开了但连不上」，因为进程根本没起来。
 *
 * ### 2. 不能改 `/etc/ssh/sshd_config` 里的 `UsePAM yes`
 *
 * proot 里 PAM 的很多模块（`pam_loginuid`、`pam_systemd`）要写 `/proc/self/loginuid`
 * 或找 systemd，会失败并导致**所有密码登录被拒**。必须改成 `no`，
 * 并显式开 `PasswordAuthentication yes`。
 *
 * ### 3. 必须显式指定 host key 与 pid 路径
 *
 * 容器内的 `/run/sshd` 可能不存在（`tmpfs` 没挂），sshd 会因「无法写 pid」退出。
 * 所以启动前先 `mkdir -p /run/sshd`。
 *
 * ## 为什么用密码登录而不是只给公钥
 *
 * 公钥当然更安全，但用户第一次用手机上这个功能时**没有现成的公钥可用**
 * （他多半是在另一台电脑上敲 `ssh`）。只支持公钥等于「先得会配公钥才能用」，
 * 而这恰恰是大多数人卡住的地方。所以默认开密码，同时把公钥写入口一起给出。
 */
data class SshConfig(
    /** 监听端口。必须 >= 1024（见类 KDoc）。 */
    val port: Int = DEFAULT_PORT,
    /** 用户名。登录容器时用 `ssh -p <port> <user>@<host>`。 */
    val user: String = "praxis",
    /** 是否允许密码登录。 */
    val passwordAuth: Boolean = true,
    /** 是否允许 root 登录（容器内 root 是映射出来的，不是真 root）。 */
    val permitRootLogin: Boolean = false,
) {
    /**
     * 端口是否可用。
     *
     * 单独一个属性而不是在 init 里 `require`：UI 需要「输入框还在编辑中」时
     * 也能拿到一个可显示的错误，而不是抛异常把整页打挂。
     */
    val isPortUsable: Boolean get() = port in MIN_UNPRIVILEGED_PORT..65535

    /** 给用户看的端口问题说明；端口合法时为 null。 */
    val portProblem: String?
        get() = when {
            port < MIN_UNPRIVILEGED_PORT ->
                "端口必须 ≥ $MIN_UNPRIVILEGED_PORT：Android 上普通应用无法绑定 1024 以下端口，" +
                    "sshd 会直接启动失败。"
            port > 65535 -> "端口必须 ≤ 65535。"
            else -> null
        }

    companion object {
        /** 非特权端口下界。 */
        const val MIN_UNPRIVILEGED_PORT = 1024

        /** 默认端口。2222 是 ssh 最常用的替代端口，避开与宿主 ssh 混淆。 */
        const val DEFAULT_PORT = 2222
    }
}

/**
 * 生成 sshd 的配置片段与启停脚本。
 *
 * ## 为什么不直接改系统的 `/etc/ssh/sshd_config`
 *
 * 容器是用户的，他可能自己改过配置。我们**写一份独立文件**并让 sshd 用 `-f`
 * 指定它，好处是：
 *  - 用户的改动不会被我们覆盖；
 *  - 关闭 SSH 只需要杀进程，不需要「把配置改回去」；
 *  - 我们的配置出问题时，用户仍能用原始 `sshd_config` 手工起。
 *
 * 文件落在容器内 `/etc/ssh/sshd_config.praxis`。
 */
object SshdScript {

    /** 我们管理的配置文件名（容器内绝对路径）。 */
    const val CONFIG_PATH = "/etc/ssh/sshd_config.praxis"

    /**
     * 安装 sshd 并生成配置。幂等：可重复执行。
     *
     * `DEBIAN_FRONTEND=noninteractive` 是必需的：`apt install openssh-server`
     * 在 debconf 里会弹「如何启动 sshd」的交互菜单，非交互环境下会**卡住不动**，
     * 用户看到的是「安装卡在某个百分比」。
     */
    fun installScript(cfg: SshConfig): String = buildString {
        appendLine("set -e")
        appendLine("export DEBIAN_FRONTEND=noninteractive")
        appendLine("apt-get update")
        appendLine("apt-get install -y --no-install-recommends openssh-server")
        appendLine("mkdir -p /run/sshd")
        // 生成 host key（幂等：已存在则跳过）。
        appendLine("ssh-keygen -A")
        appendLine(writeConfig(cfg))
        // 建用户（幂等）。
        appendLine("id -u ${cfg.user} >/dev/null 2>&1 || useradd -m -s /bin/bash ${cfg.user}")
        appendLine("echo '已安装 openssh-server 并生成 $CONFIG_PATH'")
    }

    /**
     * 设置用户密码。
     *
     * ## 密码怎么传才不留痕
     *
     * 用 `printf '%s:%s\n' user pass | chpasswd`：`chpasswd` **只从 stdin 读**，
     * 所以密码不会出现在命令行参数里。
     *
     * 为什么这一点重要：Linux 的 `/proc/<pid>/cmdline` 对同 uid 的进程可读。
     * 若写成 `chpasswd <<< "user:pass"` 或 `passwd --stdin`（RHEL 系才有），
     * 密码就会以参数/环境变量的形式被容器内任何进程看到。
     *
     * 代价是：`printf` 的**参数本身**仍在 cmdline 里（`sh -c "printf '...' ..."`）。
     * 因此这里做的是「不给容器内其他进程**顺便**看到」，而不是密码学的保密 ——
     * 真要做到不留痕，得用 fd 重定向写 `/proc/self/fd/0`，proot 下不可靠。
     * 这一点如实写明，不夸大。
     */
    fun setPasswordScript(user: String, password: String): String {
        // 转义单引号：`printf '%s\n' 'a'b'` 会断掉。
        val escapedUser = user.replace("'", "'\\''")
        val escapedPassword = password.replace("'", "'\\''")
        return buildString {
            appendLine("set -e")
            appendLine("printf '%s:%s\\n' '$escapedUser' '$escapedPassword' | chpasswd")
            appendLine("echo '密码已更新'")
        }
    }

    /** 写公钥（`~/.ssh/authorized_keys`）。 */
    fun authorizeKeyScript(user: String, publicKey: String): String {
        val key = publicKey.trim().replace("'", "")
        return buildString {
            appendLine("set -e")
            appendLine("mkdir -p /home/$user/.ssh")
            appendLine("printf '%s\\n' '$key' >> /home/$user/.ssh/authorized_keys")
            appendLine("sort -u /home/$user/.ssh/authorized_keys -o /home/$user/.ssh/authorized_keys")
            appendLine("chown -R $user:$user /home/$user/.ssh")
            appendLine("chmod 700 /home/$user/.ssh")
            appendLine("chmod 600 /home/$user/.ssh/authorized_keys")
            appendLine("echo '公钥已加入'")
        }
    }

    /**
     * 前台启动 sshd（由 runner 在后台线程跑）。
     *
     * `-D` = 不 daemonize。必须这样：如果 sshd 自己 fork 到后台，
     * 我们的进程句柄立刻退出，UI 会显示「已停止」，但 sshd 其实还在跑 ——
     * 状态就永远对不上。用 `-D` 让进程生命周期 == sshd 生命周期。
     */
    fun runArgs(cfg: SshConfig): List<String> = listOf(
        "/usr/sbin/sshd",
        "-D",
        "-f", CONFIG_PATH,
        "-p", cfg.port.toString(),
        // `-e`：日志走 stderr 而不是 syslog。容器里没有 syslog，
        // 不加的话 sshd 的报错**完全看不到**，只剩「连不上」。
        "-e",
    )

    /** sshd 配置正文。 */
    private fun writeConfig(cfg: SshConfig): String {
        val passwordFlag = if (cfg.passwordAuth) "yes" else "no"
        val rootFlag = if (cfg.permitRootLogin) "yes" else "no"
        return buildString {
            append("printf '%s\\n' ")
            append("'Port ${cfg.port}' ")
            append("'ListenAddress 0.0.0.0' ")
            append("'PasswordAuthentication $passwordFlag' ")
            append("'PermitRootLogin $rootFlag' ")
            // PAM：见类 KDoc。proot 里 pam_loginuid / pam_systemd 会失败。
            append("'UsePAM no' ")
            append("'PubkeyAuthentication yes' ")
            append("'PermitEmptyPasswords no' ")
            // 端口转发：用户开 ssh 的常见目的就是做隧道，默认放开。
            append("'AllowTcpForwarding yes' ")
            append("'X11Forwarding no' ")
            // 关掉反向 DNS：容器里的 DNS 解析慢，每次连接会卡好几秒。
            append("'UseDNS no' ")
            append("'PidFile /run/sshd.praxis.pid' ")
            append("> $CONFIG_PATH")
        }
    }
}

/**
 * frp 内网穿透配置。
 *
 * ## 为什么手机上需要它
 *
 * 手机在运营商 NAT 后面，**没有公网 IP**，外面无法直接连进来。
 * frp 的 `frpc` 从手机主动连到有公网 IP 的 `frps`，把
 * 「公网:远程端口」转发到「本机:本地端口」。
 *
 * 典型用法就是把容器里的 sshd（本地 2222）映射到公网的 6000。
 * 配置文件是 **toml**（frp 0.52 起）：
 *
 * ```toml
 * serverAddr = "1.2.3.4"
 * serverPort = 7000
 *
 * [[proxies]]
 * name = "praxis-ssh"
 * type = "tcp"
 * localIP = "127.0.0.1"
 * localPort = 2222
 * remotePort = 6000
 * ```
 *
 * ⚠️ **不要用旧版的 ini 写法**（`[common]` / `local_ip` / `remote_port`）。
 * 0.52 起旧格式会被当成 toml 解析而直接报错，症状是 frpc 一启动就退出。
 *
 * ## `local_ip` 为什么是 127.0.0.1 而不是 0.0.0.0
 *
 * frpc 与 sshd **跑在同一台手机**上，所以走回环即可。
 * 写 0.0.0.0 会让 frpc 尝试连所有网卡，在切换 Wi-Fi/移动网络时可能连到错误地址。
 */
data class FrpConfig(
    /** frps 服务器地址（IP 或域名）。 */
    val serverAddr: String = "",
    /** frps 的 bind_port（服务端控制端口，frp 默认 7000）。 */
    val serverPort: Int = 7000,
    /** 与服务端 `token` 一致。 */
    val token: String = "",
    /** 隧道名（会出现在 frps 的后台里，别用中文）。 */
    val proxyName: String = "praxis-ssh",
    /** 本地被转发的端口（一般就是 sshd 的端口）。 */
    val localPort: Int = SshConfig.DEFAULT_PORT,
    /** 公网侧暴露的端口，由**服务端**决定哪些可用。 */
    val remotePort: Int = 6000,
) {
    /** 是否已填够信息可以启动。 */
    val isReady: Boolean
        get() = serverAddr.isNotBlank() && serverPort in 1..65535 &&
            localPort in 1..65535 && remotePort in 1..65535
}

/**
 * 生成 frpc 配置与启停脚本。
 *
 * ## 为什么自己写 toml 而不用 `frpc` 的命令行参数
 *
 * frp **完全没有命令行参数**（0.52 起只接受 `-c 配置文件`）。
 * 所以必须生成一份 toml。这一点很容易记错成「用 `-s -p -t` 传参」，
 * 结果就是 `frpc: flag provided but not defined`。
 */
object FrpScript {

    /** 我们管理的配置文件（容器内绝对路径）。 */
    const val CONFIG_PATH = "/etc/frp/frpc.toml"

    /**
     * 安装 frpc 并写配置。
     *
     * ## 为什么从 GitHub Releases 装而不是 apt
     *
     * Ubuntu 官方源**没有** `frp` 包（它不是发行版自带软件）。所以只能下二进制。
     * 这里用官方的 GitHub Releases —— 但**国内访问 GitHub 常失败**，
     * 所以同时给出「用户自己上传二进制」的路径（见 KDoc 末尾说明）。
     */
    fun installScript(cfg: FrpConfig, arch: String = "arm64"): String = buildString {
        appendLine("set -e")
        appendLine("export DEBIAN_FRONTEND=noninteractive")
        appendLine("apt-get install -y --no-install-recommends curl ca-certificates tar")
        appendLine("mkdir -p /etc/frp /opt/frp")
        // ⚠️ 资源名里的架构只有 `arm64` / `amd64` / `arm` 这几种，
        // **不带** `linux_` 前缀。写成 `linux_arm64` 会得到 404。
        // 完整文件名形如：frp_0.61.0_linux_arm64.tar.gz —— 架构前**已有** `linux_`。
        appendLine(
            "if [ ! -x /opt/frp/frpc ]; then\n" +
                "  echo '下载 frpc…'\n" +
                "  VER=\$(curl -fsSL https://api.github.com/repos/fatedier/frp/releases/latest " +
                "| grep -o '\"tag_name\": *\"v[^\"]*' | head -1 | cut -d '\"' -f4)\n" +
                "  if [ -z \"\$VER\" ]; then echo '拿不到最新版本号（GitHub 可能被墙），" +
                "请手动下载 frpc 放到 /opt/frp/frpc'; exit 1; fi\n" +
                "  echo \"版本: \$VER\"\n" +
                "  curl -fL \"https://github.com/fatedier/frp/releases/download/\$VER/" +
                "frp_\${VER#v}_linux_$arch.tar.gz\" -o /tmp/frp.tar.gz\n" +
                "  tar -xzf /tmp/frp.tar.gz -C /opt/frp --strip-components=1\n" +
                "  chmod +x /opt/frp/frpc\n" +
                "fi",
        )
        appendLine(writeConfig(cfg))
        appendLine("echo '已安装 frpc 并写入 $CONFIG_PATH'")
    }

    /** 前台启动 frpc（与 sshd 同一个理由：不加 `-D` 类的选项，保持前台）。 */
    fun runArgs(): List<String> = listOf("/opt/frp/frpc", "-c", CONFIG_PATH)

    /** toml 正文。 */
    private fun writeConfig(cfg: FrpConfig): String {
        // ⚠️ toml 里的字符串要用双引号，且内容里的 `"` 与 `\` 要转义。
        // 这里只做双引号转义：token 是随机串，含双引号的概率极低，
        // 但含 `$` 的密码很常见 —— 因为是写进 printf 的单引号参数，`$` 不会被展开。
        val token = if (cfg.token.isBlank()) "" else cfg.token.replace("\"", "\\\"")
        return buildString {
            append("printf '%s\\n' ")
            append("'serverAddr = \"${cfg.serverAddr}\"' ")
            append("'serverPort = ${cfg.serverPort}' ")
            if (token.isNotEmpty()) {
                append("'auth.method = \"token\"' ")
                append("'auth.token = \"$token\"' ")
            }
            append("'' ")
            append("'[[proxies]]' ")
            append("'name = \"${cfg.proxyName}\"' ")
            append("'type = \"tcp\"' ")
            // 见 FrpConfig KDoc：frpc 与目标服务同机，走回环。
            append("'localIP = \"127.0.0.1\"' ")
            append("'localPort = ${cfg.localPort}' ")
            append("'remotePort = ${cfg.remotePort}' ")
            append("> $CONFIG_PATH")
        }
    }
}