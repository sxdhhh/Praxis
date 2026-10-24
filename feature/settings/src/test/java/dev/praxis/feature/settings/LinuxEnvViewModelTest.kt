package dev.praxis.feature.settings

import dev.praxis.feature.settings.env.LinuxEnvPort
import dev.praxis.feature.settings.env.LinuxEnvViewModel
import dev.praxis.core.linux.FrpConfig
import dev.praxis.core.linux.LinuxDistro
import dev.praxis.core.linux.LinuxEnvironment
import dev.praxis.core.linux.SshConfig
import dev.praxis.core.linux.UbuntuMirror
import dev.praxis.core.linux.UbuntuRelease
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Linux 环境状态机的护栏。
 *
 * ## 为什么这些点必须测
 *
 * 全部是「只能在真机上跑一次才发现」的失败：
 *  1. `busy` 没复位 → 所有按钮永久禁用，用户只能杀 App；
 *  2. 端口不合法却允许开 SSH → 显示「已启用」但连不上；
 *  3. FRP 缺服务器地址却允许开 → 一启动就退出；
 *  4. 装环境顺序错（先换源再写 DNS）→ apt 报 `Temporary failure resolving`。
 *
 * 用假端口把这些行为钉住，不需要 proot、不需要网络。
 */
class LinuxEnvViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUpMain() {
        // viewModelScope 跑在 Dispatchers.Main 上，纯 JVM 里必须先替换，
        // 否则访问即抛 "Module with the Main dispatcher had failed to initialize"。
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDownMain() {
        Dispatchers.resetMain()
    }

    /** 记录调用顺序的假端口。 */
    private class FakePort(
        var prootOk: Boolean = true,
        var installed: Boolean = false,
        var installSucceeds: Boolean = true,
        var execExit: Int = 0,
    ) : LinuxEnvPort {
        /** 按顺序记录发生过什么，用于验证「DNS 在换源之前」。 */
        val calls = mutableListOf<String>()
        var stopped = 0

        override fun isProotAvailable(): Boolean = prootOk

        override fun defaultEnvironment(distro: LinuxDistro): LinuxEnvironment = LinuxEnvironment(
            rootfsDir = File("/tmp/praxis-test-rootfs"),
            prootBinary = File("/tmp/libproot.so"),
        )

        override fun isInstalled(env: LinuxEnvironment): Boolean = installed

        override suspend fun install(
            env: LinuxEnvironment,
            onLine: (String) -> Unit,
        ): Boolean {
            calls += "install"
            onLine("下载中")
            if (installSucceeds) installed = true
            return installSucceeds
        }

        override suspend fun exec(
            env: LinuxEnvironment,
            command: String,
            timeoutSeconds: Long,
            onLine: (String) -> Unit,
        ): Int {
            // 用命令里的特征判断是哪一步，不依赖具体全文。
            // ## 判定必须「先具体后笼统」（2026-10-21 修正）
            //
            // `SshdScript.installScript()` 和 `FrpScript.installScript()` 里**都**含
            // `apt-get update` 那一行。旧代码把 `apt-get update -> "mirror"` 放在最前，
            // 于是 ssh / frp 的安装全被记成 "mirror" —— 测试因此永远断言不到 ssh-install。
            //
            // 顺序原则：越独特的特征越靠前。
            calls += when {
                command.contains("resolv.conf") -> "dns"
                command.contains("openssh-server") -> "ssh-install"
                command.contains("frpc") -> "frp-install"
                // 换源脚本才会同时出现 ubuntu.sources 与 apt-get update。
                command.contains("apt-get update") -> "mirror"
                else -> "exec"
            }
            return execExit
        }

        override suspend fun startSession(
            env: LinuxEnvironment,
            args: List<String>,
            onLine: (String) -> Unit,
        ): LinuxEnvPort.SessionHandle {
            calls += "session"
            return object : LinuxEnvPort.SessionHandle {
                override val isAlive: Boolean = true
                override fun stop() {
                    stopped++
                }
            }
        }
    }

    private fun vm(port: FakePort) = LinuxEnvViewModel(port) { distro -> port.defaultEnvironment(distro) }

    // ── 初始探测 ────────────────────────────────────────────────────

    @Test
    fun `缺 proot 时摘要直接说明不可用`() {
        val state = vm(FakePort(prootOk = false)).state.value
        assertFalse(state.prootAvailable)
        assertTrue(state.summary.contains("缺少 proot"))
        // 缺 proot 时不能点安装。
        assertFalse(state.canInstall)
    }

    @Test
    fun `未安装时摘要为尚未安装`() {
        val state = vm(FakePort(installed = false)).state.value
        assertFalse(state.installed)
        assertTrue(state.summary.contains("尚未安装"))
    }

    // ── 安装流程 ────────────────────────────────────────────────────

    @Test
    fun `安装顺序是 下载 到 DNS 到 换源`() = runTest {
        val port = FakePort()
        val vm = vm(port)
        vm.install(UbuntuRelease.Noble, UbuntuMirror.Tsinghua)
        advanceUntilIdle()

        // DNS 必须在换源之前：没有 resolv.conf 时 apt 连域名都解析不了，
        // 而报错是 "Temporary failure resolving"，看不出是 DNS 问题。
        assertEquals(
            listOf("install", "dns", "mirror"),
            port.calls.filter { it in listOf("install", "dns", "mirror") },
        )
        assertTrue(vm.state.value.installed)
        assertFalse("busy 必须复位", vm.state.value.busy)
    }

    @Test
    fun `安装失败时 busy 也要复位`() = runTest {
        val port = FakePort(installSucceeds = false)
        val vm = vm(port)
        vm.install(UbuntuRelease.Noble, UbuntuMirror.Tsinghua)
        advanceUntilIdle()

        // 最关键的一条：不复位就是「按钮永久禁用、只能杀 App」。
        assertFalse(vm.state.value.busy)
        assertTrue(vm.state.value.summary.contains("失败"))
        // 下载失败就不该继续换源。
        assertFalse(port.calls.contains("mirror"))
    }

    /**
     * 「安装中重复点击被忽略」——它守的是 `busy` 闸门。
     *
     * ## 为什么这里**必须**换成 `StandardTestDispatcher`（2026-10-21 修正）
     *
     * 类级 `@Before` 装的是 `UnconfinedTestDispatcher`，`viewModelScope.launch`
     * 会**立即**把 `install()` 整个跑完（FakePort 没有真正的挂起点）。
     * 那样第二次 `install()` 看到的是 `busy = false`，闸门根本没机会生效，
     * 断言 1 次会得到 2 次 —— 这不是实现的问题，是测试没构造出「进行中」。
     *
     * `StandardTestDispatcher` 会把协程排进队列、**不**自动执行，
     * 于是两次连续调用都发生在同一个 `busy = true` 窗口里，闸门才被真正验证。
     */
    @Test
    fun `安装进行中时重复点击被忽略`() = runTest {
        val standard = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(standard)
        try {
            val port = FakePort()
            val vm = vm(port)
            vm.install(UbuntuRelease.Noble, UbuntuMirror.Tsinghua)
            // 此刻协程还排着队，busy 已经是 true —— 正是要拦的那种情况。
            assertTrue("第一次点击后应进入 busy", vm.state.value.busy)
            vm.install(UbuntuRelease.Noble, UbuntuMirror.Tsinghua)
            advanceUntilIdle()

            assertEquals("重复点击不该再走一次下载", 1, port.calls.count { it == "install" })
        } finally {
            Dispatchers.setMain(testDispatcher)
        }
    }

    // ── 换源 ────────────────────────────────────────────────────────

    @Test
    fun `换源成功与失败给出不同摘要`() = runTest {
        val okPort = FakePort()
        val okVm = vm(okPort)
        okVm.switchMirror(UbuntuMirror.Aliyun)
        advanceUntilIdle()
        assertTrue(okVm.state.value.summary.contains("已切到"))
        assertEquals(UbuntuMirror.Aliyun, okVm.state.value.mirror)

        val badPort = FakePort(execExit = 100)
        val badVm = vm(badPort)
        badVm.switchMirror(UbuntuMirror.Ustc)
        advanceUntilIdle()
        // 失败要把退出码写出来，用户才有个可报的信息。
        assertTrue(badVm.state.value.summary.contains("退出码 100"))
        assertFalse(badVm.state.value.busy)
    }

    // ── SSH ─────────────────────────────────────────────────────────

    @Test
    fun `端口小于 1024 时不许开启 SSH`() = runTest {
        val port = FakePort(installed = true)
        val vm = vm(port)
        vm.updateSshConfig(SshConfig(port = 22))
        vm.toggleSsh(true)
        advanceUntilIdle()

        // 开关不能被打开：让它「已启用」但连不上，排查成本高得多。
        assertFalse(vm.state.value.sshEnabled)
        // 也不该去装 openssh。
        assertFalse(port.calls.contains("ssh-install"))
        // 但必须把原因说出来。
        val line = vm.state.value.lastLine
        assertNotNull(line)
        assertTrue(line!!.contains("1024"))
    }

    @Test
    fun `端口合法时开启 SSH 会先装再起会话`() = runTest {
        val port = FakePort(installed = true)
        val vm = vm(port)
        vm.updateSshConfig(SshConfig(port = 2222))
        vm.toggleSsh(true)
        advanceUntilIdle()

        assertTrue(vm.state.value.sshEnabled)
        assertEquals(1, port.calls.count { it == "ssh-install" })
        assertTrue(port.calls.contains("session"))
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `关闭 SSH 会停掉会话`() = runTest {
        val port = FakePort(installed = true)
        val vm = vm(port)
        vm.toggleSsh(true)
        advanceUntilIdle()
        val before = port.stopped

        vm.toggleSsh(false)
        assertFalse(vm.state.value.sshEnabled)
        assertEquals("应停掉 sshd 会话", before + 1, port.stopped)
    }

    @Test
    fun `空密码不触发 chpasswd`() = runTest {
        val port = FakePort(installed = true)
        val vm = vm(port)
        vm.setSshPassword("")
        advanceUntilIdle()
        // 空密码不该执行 —— 避免把已有密码误清成空。
        assertFalse(port.calls.contains("exec"))
    }

    // ── FRP ─────────────────────────────────────────────────────────

    @Test
    fun `FRP 缺服务器地址时不许开启`() = runTest {
        val port = FakePort(installed = true)
        val vm = vm(port)
        vm.toggleFrp(true)   // 默认 serverAddr 为空
        advanceUntilIdle()

        assertFalse(vm.state.value.frpEnabled)
        assertFalse(port.calls.contains("frp-install"))
        assertTrue(vm.state.value.lastLine!!.contains("服务器地址"))
    }

    @Test
    fun `FRP 填好地址后开启会装并起会话`() = runTest {
        val port = FakePort(installed = true)
        val vm = vm(port)
        vm.updateFrpConfig(FrpConfig(serverAddr = "1.2.3.4", remotePort = 6000))
        vm.toggleFrp(true)
        advanceUntilIdle()

        assertTrue(vm.state.value.frpEnabled)
        assertTrue(port.calls.contains("frp-install"))
        assertTrue(vm.state.value.summary.contains("6000"))
    }

    // ── 停止级联 ────────────────────────────────────────────────────

    @Test
    fun `停止容器会连带停掉 SSH 与 FRP`() = runTest {
        val port = FakePort(installed = true)
        val vm = vm(port)
        vm.updateFrpConfig(FrpConfig(serverAddr = "1.2.3.4"))
        vm.start()
        advanceUntilIdle()
        vm.toggleSsh(true)
        advanceUntilIdle()
        vm.toggleFrp(true)
        advanceUntilIdle()

        assertTrue(vm.state.value.running)
        assertTrue(vm.state.value.sshEnabled)
        assertTrue(vm.state.value.frpEnabled)

        vm.stop()

        // 容器没了，里面的 sshd / frpc 也没意义，必须一起停。
        assertFalse(vm.state.value.running)
        assertFalse(vm.state.value.sshEnabled)
        assertFalse(vm.state.value.frpEnabled)
        assertEquals(3, port.stopped)
    }
}