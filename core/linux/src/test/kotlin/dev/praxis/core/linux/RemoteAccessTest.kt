package dev.praxis.core.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSH 配置的护栏。
 *
 * 三条 Android 特有问题都会让「开了 ssh 但连不上」：
 *  1. 端口 < 1024 时 sshd 起不来（没有 CAP_NET_BIND_SERVICE）；
 *  2. `UsePAM yes` 在 proot 里会让所有密码登录被拒；
 *  3. sshd 若自己 daemonize，我们的进程句柄立刻退出，状态永远显示「已停止」。
 */
class SshConfigTest {

    @Test
    fun `默认端口是 2222 且可用`() {
        val cfg = SshConfig()
        assertEquals(2222, cfg.port)
        assertTrue(cfg.isPortUsable)
        assertNull(cfg.portProblem)
    }

    @Test
    fun `端口小于 1024 被判为不可用并给出原因`() {
        val cfg = SshConfig(port = 22)
        assertFalse("22 端口在 Android 上绑不了", cfg.isPortUsable)
        val problem = cfg.portProblem
        assertNotNull(problem)
        // 提示必须说清「为什么」，否则用户会以为是自己填错了。
        assertTrue(problem!!.contains("1024"))
        assertTrue(problem.contains("无法绑定"))
    }

    @Test
    fun `边界值 1024 可用、1023 不可用`() {
        assertTrue(SshConfig(port = 1024).isPortUsable)
        assertFalse(SshConfig(port = 1023).isPortUsable)
    }

    @Test
    fun `端口超过 65535 被判为不可用`() {
        val cfg = SshConfig(port = 70000)
        assertFalse(cfg.isPortUsable)
        assertNotNull(cfg.portProblem)
    }

    @Test
    fun `不抛异常，只给 portProblem`() {
        // UI 在用户还在编辑输入框时就会调用这些属性，
        // 抛异常会把整页打挂。必须是「可查询的问题」而不是 require 失败。
        val cfg = SshConfig(port = 1)
        cfg.isPortUsable
        cfg.portProblem
    }

    // ── 生成的配置正文 ──────────────────────────────────────────────

    @Test
    fun `安装脚本关掉 PAM 并开密码登录`() {
        val script = SshdScript.installScript(SshConfig())
        // UsePAM yes 在 proot 里会让密码登录全部失败（pam_loginuid 写不了）。
        assertTrue("必须 UsePAM no", script.contains("'UsePAM no'"))
        assertTrue(script.contains("'PasswordAuthentication yes'"))
        assertTrue(script.contains("'PubkeyAuthentication yes'"))
    }

    @Test
    fun `安装脚本设 DEBIAN_FRONTEND 免得卡在 debconf`() {
        val script = SshdScript.installScript(SshConfig())
        // 不加的话 apt install openssh-server 会卡在交互式 debconf 菜单。
        assertTrue(script.contains("DEBIAN_FRONTEND=noninteractive"))
        assertTrue(script.contains("mkdir -p /run/sshd"))
        assertTrue(script.contains("ssh-keygen -A"))
    }

    @Test
    fun `sshd 用 -D 前台运行并带 -e`() {
        val args = SshdScript.runArgs(SshConfig(port = 2222))
        // -D：不 daemonize，否则进程句柄立刻退出、状态永远显示已停止。
        assertTrue("-D 缺失", args.contains("-D"))
        // -e：日志走 stderr。容器里没有 syslog，不加就看不到任何报错。
        assertTrue("-e 缺失", args.contains("-e"))
        assertEquals("/usr/sbin/sshd", args.first())
        // 端口必须显式传，且是配置里的值。
        val p = args.indexOf("-p")
        assertTrue(p >= 0)
        assertEquals("2222", args[p + 1])
        // 用我们自己的配置文件，不动用户的 sshd_config。
        assertTrue(args.contains(SshdScript.CONFIG_PATH))
    }

    @Test
    fun `配置正文里端口与端口转发都写进去`() {
        val script = SshdScript.installScript(SshConfig(port = 2222, permitRootLogin = true))
        assertTrue(script.contains("'Port 2222'"))
        // ssh 隧道是主要用途之一。
        assertTrue(script.contains("'AllowTcpForwarding yes'"))
        assertTrue(script.contains("'PermitRootLogin yes'"))
        // 反向 DNS 会让每次连接卡几秒。
        assertTrue(script.contains("'UseDNS no'"))
    }

    @Test
    fun `root 登录默认关闭`() {
        val script = SshdScript.installScript(SshConfig())
        assertTrue(script.contains("'PermitRootLogin no'"))
    }

    @Test
    fun `改密码用 chpasswd 而不是把密码放命令行参数`() {
        val script = SshdScript.setPasswordScript("praxis", "s3cret")
        assertTrue(script.contains("chpasswd"))
        assertTrue(script.contains("praxis"))
        assertTrue(script.contains("s3cret"))
        // 必须是管道进 chpasswd（它只读 stdin），而不是 `passwd --stdin`。
        assertTrue(script.contains("| chpasswd"))
        assertFalse(script.contains("passwd --stdin"))
    }

    @Test
    fun `密码里的单引号被转义`() {
        // 不转义会让 sh 的引号提前闭合，命令被截断 —— 密码就设成了前半段。
        val script = SshdScript.setPasswordScript("praxis", "a'b'c")
        assertTrue("应包含转义序列", script.contains("'\\''"))
    }

    @Test
    fun `写公钥会修正权限`() {
        val script = SshdScript.authorizeKeyScript("praxis", "ssh-ed25519 AAAAKEY user@host")
        assertTrue(script.contains("authorized_keys"))
        // sshd 对权限极其严格：组或其他可写就拒绝用这把 key。
        assertTrue(script.contains("chmod 700"))
        assertTrue(script.contains("chmod 600"))
        assertTrue(script.contains("chown -R praxis:praxis"))
    }
}

/**
 * FRP 配置的护栏。
 *
 * 最容易错的两点：
 *  1. frp 0.52+ 只认 toml，旧 ini 写法会直接解析失败；
 *  2. 资源名里的架构是 `arm64`（前面已有 `linux_`），写成 `linux_arm64` 是 404。
 */
class FrpConfigTest {

    @Test
    fun `空配置不算就绪`() {
        assertFalse(FrpConfig().isReady)
    }

    @Test
    fun `填了服务器地址就算就绪`() {
        assertTrue(FrpConfig(serverAddr = "1.2.3.4").isReady)
    }

    @Test
    fun `端口越界不算就绪`() {
        assertFalse(FrpConfig(serverAddr = "1.2.3.4", serverPort = 0).isReady)
        assertFalse(FrpConfig(serverAddr = "1.2.3.4", remotePort = 70000).isReady)
    }

    @Test
    fun `生成的是 toml 而不是 ini`() {
        val cfg = FrpConfig(serverAddr = "1.2.3.4", token = "tk")
        val script = FrpScript.installScript(cfg)
        // toml 的键名是 serverAddr / serverPort / [[proxies]]。
        assertTrue(script.contains("serverAddr = "))
        assertTrue(script.contains("serverPort = "))
        assertTrue(script.contains("[[proxies]]"))
        // 旧 ini 的写法一个都不能出现。
        assertFalse("不该有 [common]", script.contains("[common]"))
        assertFalse("不该有 local_ip", script.contains("local_ip"))
        assertFalse("不该有 remote_port", script.contains("remote_port"))
    }

    @Test
    fun `有 token 才写 auth 段`() {
        val withToken = FrpScript.installScript(FrpConfig(serverAddr = "a", token = "tk"))
        // 生成的是 shell 里的 `'auth.method = "token"'`，字符串里就是单层双引号。
        assertTrue(withToken.contains("auth.method = \"token\""))
        assertTrue(withToken.contains("auth.token = \"tk\""))

        val noToken = FrpScript.installScript(FrpConfig(serverAddr = "a", token = ""))
        assertFalse("没 token 就别写 auth", noToken.contains("auth.token"))
    }

    @Test
    fun `下载文件名里的架构是 arm64 而不是 linux_arm64`() {
        val script = FrpScript.installScript(FrpConfig(serverAddr = "a"))
        // 完整名形如 frp_0.61.0_linux_arm64.tar.gz，架构前已有 linux_。
        assertTrue("应拼成 linux_->arm64", script.contains("frp_" + '$' + "{VER#v}_linux_arm64.tar.gz"))
        assertFalse("不该出现 linux_linux_arm64", script.contains("linux_linux_"))
    }

    @Test
    fun `本地地址走回环`() {
        val script = FrpScript.installScript(FrpConfig(serverAddr = "a", localPort = 2222))
        // frpc 与 sshd 同机，写 0.0.0.0 会在切网时连到错误网卡。
        assertTrue(script.contains("localIP = \"127.0.0.1\""))
        assertTrue(script.contains("localPort = 2222"))
    }

    @Test
    fun `frpc 用 -c 指定配置文件`() {
        val args = FrpScript.runArgs()
        // frp 没有任何 «-s -p -t» 式的命令行参数，只认配置文件。
        assertEquals("/opt/frp/frpc", args.first())
        assertTrue(args.contains("-c"))
        assertTrue(args.contains(FrpScript.CONFIG_PATH))
    }

    @Test
    fun `拿不到版本号时给出可执行的提示而不是静默失败`() {
        val script = FrpScript.installScript(FrpConfig(serverAddr = "a"))
        // GitHub 在国内常被墙，必须告诉用户「自己放二进制到这里」。
        assertTrue(script.contains("请手动下载 frpc 放到 /opt/frp/frpc"))
    }
}