package dev.praxis.feature.settings.env

import dev.praxis.core.linux.LinuxDistro
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 共用文件系统下「要不要显示『应用 XX 的软件源』」的判定。
 *
 * ## 为什么这个纯逻辑值得单独测
 *
 * 它决定一个按钮出不出现。判错的后果是**用户卡死**：
 *
 * ```
 * 底层 Kali → 用户切回 Ubuntu 源 → 按钮不出现
 * → 用户以为已经切回来了 → apt 装的还是 kali-rolling 的包
 * ```
 *
 * 而这类问题在真机上极难发现（apt 不报错，只是装的包不对）。
 * 抽成 `LinuxEnvUiState` 的派生属性后就能纯 JVM 锁住。
 */
class LinuxEnvSharedRootfsTest {

    /** 只关心 shared rootfs 相关字段，其余取默认值。 */
    private fun state(
        distro: LinuxDistro,
        origin: String? = null,
        activeSources: String? = null,
    ) = LinuxEnvUiState(
        distro = distro,
        installed = true,
        rootfsOrigin = origin,
        activeSources = activeSources,
    )

    @Test
    fun `底层与选中一致时不需要应用按钮`() {
        assertTrue(state(LinuxDistro.Kali, origin = "kali", activeSources = "kali").sourcesMatchSelection)
        assertTrue(state(LinuxDistro.Ubuntu, origin = "ubuntu", activeSources = "ubuntu").sourcesMatchSelection)
    }

    @Test
    fun `底层是 Ubuntu 但选中 Kali 时需要应用按钮`() {
        // 这是最常见的一条：用户从 Ubuntu 镜像装，然后切到 Kali。
        assertFalse(state(LinuxDistro.Kali, origin = "ubuntu", activeSources = "ubuntu").sourcesMatchSelection)
    }

    @Test
    fun `底层是 Kali 但用户切回 Ubuntu 源后需要应用按钮`() {
        // ★ 这条是「只用 origin 判断」会漏掉的场景：
        //   底层 Kali（origin=kali），但源已经切到 Ubuntu（activeSources=ubuntu），
        //   此时选中 Ubuntu 应该算「一致」。
        assertTrue(state(LinuxDistro.Ubuntu, origin = "kali", activeSources = "ubuntu").sourcesMatchSelection)
        // 反过来：底层 Kali、源还是 Kali、选中 Ubuntu → 需要按钮。
        assertFalse(state(LinuxDistro.Ubuntu, origin = "kali", activeSources = "kali").sourcesMatchSelection)
    }

    @Test
    fun `没有 activeSources 时退回比较 origin`() {
        // 刚装完还没换过源：装完时的源就是安装镜像对应的那套，等价。
        assertTrue(state(LinuxDistro.Kali, origin = "kali", activeSources = null).sourcesMatchSelection)
        assertFalse(state(LinuxDistro.Ubuntu, origin = "kali", activeSources = null).sourcesMatchSelection)
    }

    @Test
    fun `两个标记都缺失时显示按钮`() {
        // 老版本装的环境（那时还没有标记文件）。
        // 这种时候用户确实需要一个动作把它对齐，所以按钮应该出现。
        assertFalse(state(LinuxDistro.Ubuntu, origin = null, activeSources = null).sourcesMatchSelection)
        assertFalse(state(LinuxDistro.Kali, origin = null, activeSources = null).sourcesMatchSelection)
    }

    @Test
    fun `大小写不敏感`() {
        // 标记文件里的内容是 `name.lowercase()` 写的，但读回来可能带空白/大小写差异。
        assertTrue(state(LinuxDistro.Kali, origin = "KALI", activeSources = "Kali").sourcesMatchSelection)
    }
}