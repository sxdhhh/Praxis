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
 * 重点盯四处历史上真会踩的坑（第 1 条在 2026-10-31 被真机实测反转）：
 *  1. **关闭** `chroot_local_user` —— 开它会因 proot 的路径伪造与 chroot
 *     冲突，导致「能登录、列不出文件、RETR 全 550」；
 *  2. `-obackground=NO` —— 缺了 UI 状态与进程状态永久错位；
 *  3. `pasv_min_port`/`pasv_max_port` —— 缺了客户端登录成功但看不到文件；
 *  4. `mkdir -p /var/run/vsftpd/empty` —— 缺了每个连接都 `500 OOPS`。
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
    fun `config disables chroot because proot cannot honour it`() {
        // ## 这条断言的方向被颠倒过（2026-10-31 真机实测）
        //
        // 旧断言要求 `chroot_local_user=YES` + `allow_writeable_chroot=YES`。
        // 那是**普通 Linux 上的正解**，在 proot 下却让 FTP 完全不可用：
        //
        // - 登录成功（230）、被动模式握手成功（227）、数据连接也能建立；
        // - 但 LIST 返回 `226 Transfer done (but failed to open directory)`；
        // - RETR / SIZE 一律 `550`。
        //
        // 根因：`chroot()` 会改进程自己的根目录视图，而 proot 的路径伪造
        // 依赖固定的 host↔guest 映射，两者冲突 → 之后所有 openat 都失败。
        //
        // 对照实验：同一个 rootfs、同一被动段，用不做 chroot 的 Python
        // 最小 FTP，LIST/RETR 全部正常。所以变量只有 chroot。
        //
        // 现在用 `local_root` 代替它：登录后仍落在家目录，
        // 但不锁根 —— 代价是能看到 rootfs 其他路径，换来 FTP 真的能用。
        val s = FtpServerScript.installScript(cfg)
        assertTrue(
            "proot 下必须关闭 chroot，否则所有目录操作失败",
            s.contains("chroot_local_user=NO"),
        )
        assertTrue(
            "allow_writeable_chroot 在关闭 chroot 后已无意义，不应残留",
            !s.contains("allow_writeable_chroot"),
        )
        assertTrue(
            "没有 chroot 时靠 local_root 把用户落到家目录",
            s.contains("local_root=${cfg.homeDir}"),
        )
    }

    @Test
    fun `install script creates secure_chroot_dir`() {
        // ## 为什么这条必须存在（2026-10-31 真机实测）
        //
        // vsftpd 默认 `secure_chroot_dir=/var/run/vsftpd/empty`。该目录在
        // 普通发行版由包安装脚本 + init 创建；proot 里没有 init，
        // postinst 的 invoke-rc.d 被 block，于是目录不存在。
        //
        // 表现极隐蔽：服务能起、端口能听、登录也返回 230，
        // 但每个新连接被立刻否掉：
        //
        //   500 OOPS: vsftpd: not found: directory given in
        //              'secure_chroot_dir':/var/run/vsftpd/empty
        //
        // vsftpd 在处理任何命令之前先 chroot 到那里（它的沙箱设计），
        // 目录不在就 OOPS。所以安装脚本必须自己建。
        val s = FtpServerScript.installScript(cfg)
        assertTrue(
            "必须创建 secure_chroot_dir，否则每个连接都被 500 OOPS 拒绝",
            s.contains("mkdir -p /var/run/vsftpd/empty"),
        )
    }

    @Test
    fun `config does not pin pasv_address to 0_0_0_0`() {
        // ## 0.0.0.0 会被原样回给客户端（2026-10-31 真机实测）
        //
        // 旧配置写 `pasv_address=0.0.0.0`，注释说「让 vsftpd 自动取本地 IP」。
        // 实测它把这串原样发出：
        //
        //   227 Entering Passive Mode (0,0,0,0,8,85)
        //
        // 客户端拿 0.0.0.0 去连数据端口必然失败，而这个失败发生在数据连接
        // 阶段，表现是「能登录、列目录卡住」，很难指向配置。
        //
        // 不设该项时 vsftpd 用控制连接的本地地址应答（实测 127.0.0.1 或
        // 真实网卡地址），手机换网络也不需要改配置。
        val s = FtpServerScript.installScript(cfg)
        assertTrue(
            "pasv_address 不能写 0.0.0.0（会被原样发给客户端）",
            !s.contains("pasv_address=0.0.0.0"),
        )
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