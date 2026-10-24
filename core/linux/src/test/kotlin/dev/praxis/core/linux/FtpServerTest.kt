package dev.praxis.core.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FTP 配置与脚本生成的回归护栏（2026-10-24 引入）。
 *
 * ## 为什么这些断言值得写
 *
 * 这些脚本是**拼字符串**生成的：一次手滑就能拼出一个
 * 「能装上但跑不起来」的 vsftpd，而失败只出现在容器里的日志中，
 * 真机上很难每次都手工复现。所以把「必须出现的选项」钉在这里。
 *
 * 重点盯三处历史上真会踩的坑：
 *  1. `allow_writeable_chroot=YES` —— 缺了会因「chroot 根不可写」直接拒启；
 *  2. `-obackground=NO` —— 缺了 UI 状态与进程状态永久错位；
 *  3. `pasv_min_port`/`pasv_max_port` —— 缺了客户端登录成功但看不到文件。
 */
class FtpConfigTest {

    @Test
    fun `default config matches operit`() {
        // 与 Operit 的 FTP_PORT=2127 / 被动段 2128-2136 / 用户 ubuntu 对齐。
        val cfg = FtpConfig()
        assertEquals(2127, cfg.port)
        assertEquals("2128-2136", cfg.passivePorts)
        assertEquals("ubuntu", cfg.user)
        assertEquals("/home/ubuntu", cfg.homeDir)
    }

    @Test
    fun `port below 1024 is rejected with reason`() {
        // 与 sshd 同一条内核约束：proot 不给 CAP_NET_BIND_SERVICE。
        val cfg = FtpConfig(port = 21)
        assertFalse(cfg.isPortUsable)
        assertTrue(cfg.portProblem!!.contains("1024"))
    }

    @Test
    fun `port above 65535 is rejected`() {
        val cfg = FtpConfig(port = 70000)
        assertFalse(cfg.isPortUsable)
        assertTrue(cfg.portProblem!!.contains("65535"))
    }

    @Test
    fun `port 1024 is usable`() {
        assertTrue(FtpConfig(port = 1024).isPortUsable)
        assertNull(FtpConfig(port = 1024).portProblem)
        assertTrue(FtpConfig(port = 65535).isPortUsable)
    }

    @Test
    fun `port colliding with ssh default is flagged`() {
        // 用户同时开 SSH 与 FTP 时最容易踩：两个服务抢同一端口，
        // 后启动的失败但报错埋在日志深处。
        val cfg = FtpConfig(port = 2222)
        assertTrue(cfg.isPortUsable)
        assertTrue(cfg.portProblem!!.contains("SSH"))
    }

    @Test
    fun `passive range parses valid value`() {
        assertTrue(FtpConfig(passivePorts = "2128-2136").isPassiveRangeValid)
        assertTrue(FtpConfig(passivePorts = " 3000 - 3010 ").isPassiveRangeValid)
        assertNull(FtpConfig(passivePorts = "2128-2136").passiveRangeProblem)
    }

    @Test
    fun `passive range rejects malformed values`() {
        // 缺横线、只有一段、非数字、颠倒、低于 1024 全部要拦。
        assertFalse(FtpConfig(passivePorts = "2128").isPassiveRangeValid)
        assertFalse(FtpConfig(passivePorts = "2128-").isPassiveRangeValid)
        assertFalse(FtpConfig(passivePorts = "a-b").isPassiveRangeValid)
        assertFalse(FtpConfig(passivePorts = "2136-2128").isPassiveRangeValid)
        assertFalse(FtpConfig(passivePorts = "20-30").isPassiveRangeValid)
        assertFalse(FtpConfig(passivePorts = "").isPassiveRangeValid)
    }

    @Test
    fun `user name rejects blank and separators`() {
        assertTrue(FtpConfig(user = "ubuntu").isUserUsable)
        assertFalse(FtpConfig(user = "").isUserUsable)
        assertFalse(FtpConfig(user = "a b").isUserUsable)
        assertFalse(FtpConfig(user = "a/b").isUserUsable)
        assertFalse(FtpConfig(user = "a:b").isUserUsable)
    }

    @Test
    fun `passive range falls back to default on malformed input`() {
        // UI 已拦不合法输入；脚本拿到脏值时用默认段而不是让安装失败。
        assertEquals(2128 to 2136, FtpServerScript.passiveRange(FtpConfig(passivePorts = "bad")))
        assertEquals(3000 to 3010, FtpServerScript.passiveRange(FtpConfig(passivePorts = "3000-3010")))
    }
}

class FtpServerScriptTest {

    private val cfg = FtpConfig()

    @Test
    fun `install script installs vsftpd and is idempotent on user creation`() {
        val s = FtpServerScript.installScript(cfg)
        assertTrue(s.contains("apt-get install -y --no-install-recommends vsftpd"))
        // 用户已存在时 useradd 返回 9，`set -e` 会中断整个脚本 ——
        // 所以必须先 `id -u` 判断，这是幂等的关键。
        assertTrue(s.contains("id -u 'ubuntu'"))
        assertTrue(s.contains("useradd -m"))
        assertTrue(s.contains("DEBIAN_FRONTEND=noninteractive"))
    }

    @Test
    fun `install script writes pam and vsftpd config`() {
        val s = FtpServerScript.installScript(cfg)
        assertTrue(s.contains(FtpServerScript.PAM_PATH))
        assertTrue(s.contains(FtpConfig.CONFIG_PATH))
    }

    @Test
    fun `config enables writeable chroot`() {
        // ★ 本类最重要的断言：缺了这一条 vsftpd 直接拒启。
        val s = FtpServerScript.installScript(cfg)
        assertTrue(s.contains("allow_writeable_chroot=YES"))
        assertTrue(s.contains("chroot_local_user=YES"))
    }

    @Test
    fun `config pins passive port range`() {
        // 缺了被动段，客户端登录成功但 LIST 卡住 → 看不到任何文件。
        val s = FtpServerScript.installScript(FtpConfig(passivePorts = "4000-4010"))
        assertTrue(s.contains("pasv_enable=YES"))
        assertTrue(s.contains("pasv_min_port=4000"))
        assertTrue(s.contains("pasv_max_port=4010"))
    }

    @Test
    fun `config disables anonymous and enables local write`() {
        val s = FtpServerScript.installScript(cfg)
        assertTrue(s.contains("anonymous_enable=NO"))
        assertTrue(s.contains("local_enable=YES"))
        assertTrue(s.contains("write_enable=YES"))
    }

    @Test
    fun `config uses our pam service not system one`() {
        // 系统的 /etc/pam.d/vsftpd 含 pam_loginuid / pam_systemd，
        // proot 里会失败 → 任何密码都登不进。
        val s = FtpServerScript.installScript(cfg)
        assertTrue(s.contains("pam_service_name=vsftpd.praxis"))
        assertTrue(s.contains("pam_unix.so"))
        // 有问题的模块绝对不能出现。
        assertFalse(s.contains("pam_loginuid"))
        assertFalse(s.contains("pam_systemd"))
    }

    @Test
    fun `config listens on ipv4 only`() {
        // proot 里 IPv6 常不可用，listen_ipv6=YES 会 bind 失败。
        val s = FtpServerScript.installScript(cfg)
        assertTrue(s.contains("listen=YES"))
        assertTrue(s.contains("listen_ipv6=NO"))
    }

    @Test
    fun `run args keep process in foreground`() {
        val args = FtpServerScript.runArgs(FtpConfig(port = 2127))
        // ★ 缺了 background=NO，进程会 fork 到后台 → UI 状态永久错位。
        assertTrue(args.contains("-obackground=NO"))
        assertTrue(args.contains("-oport=2127"))
        assertTrue(args.contains(FtpConfig.CONFIG_PATH))
        assertEquals("/usr/sbin/vsftpd", args.first())
    }

    @Test
    fun `password script never puts password in argv of chpasswd`() {
        val s = FtpServerScript.setPasswordScript("ubuntu", "p@ss")
        // 走 stdin 而不是参数：`printf '%s:%s\n' 'user' 'pass' | chpasswd`
        assertTrue(s.contains("chpasswd"))
        assertTrue(s.contains("printf"))
        assertTrue(s.contains("'ubuntu'"))
        assertTrue(s.contains("'p@ss'"))
    }

    @Test
    fun `password script escapes single quotes`() {
        // 不转义会让 `printf '...'` 断掉，甚至拼出额外命令。
        val s = FtpServerScript.setPasswordScript("u", "a'b")
        assertTrue(s.contains("a'\\''b"))
    }

    @Test
    fun `user name is shell quoted against injection`() {
        // 用户名来自输入框，必须按 shell 规则转义。
        val s = FtpServerScript.installScript(FtpConfig(user = "evil; rm -rf /"))
        assertTrue(s.contains("'evil; rm -rf /'"))
    }
}