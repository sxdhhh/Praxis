package dev.praxis.core.linux

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ④ Ubuntu apt 源重复配置警告：换源后传统 `sources.list` 必须让位。
 *
 * ## 用户报的现象
 *
 * 换过镜像源之后，每次 `apt update` 都警告：
 *
 * ```
 * W: Target Translations (multiverse/i18n/Translation-en) is configured
 *    multiple times in /etc/apt/sources.list:4
 *    and /etc/apt/sources.list.d/ubuntu.sources:1
 * ```
 *
 * ## 真因
 *
 * Ubuntu 24.04 起用 deb822（`sources.list.d/ubuntu.sources`），但**同时也读**
 * 传统的 `/etc/apt/sources.list`。旧实现把两个文件写成同一套源、以为「留着无害」，
 * 于是同一批源被声明两遍。
 *
 * 后果不只是刷屏：每条目标索引都会被重复下载与重复解析。
 * 用户看到的是「换过源了，apt 还在抱怨配置」。
 *
 * ## 为什么是改名不是删除
 *
 * 换源是「用户要求改配置」的那一步，改配置不该让它不可逆。
 * 改名为 `*.praxis-disabled` —— apt 只认 `.list` / `.sources` 后缀，
 * 别的名字一律不扫，既彻底失效又留了退路。
 */
class AptSourcesRetireTest {

    private fun env(distro: LinuxDistro = LinuxDistro.Ubuntu) = LinuxEnvironment(
        rootfsDir = File("/tmp/rootfs"),
        prootBinary = File("/tmp/libproot.so"),
        distro = distro,
    )

    /**
     * 换源脚本里必须出现「把 sources.list 改名」的那一步。
     *
     * 少了它，apt 就会同时读两个文件，警告照旧。
     */
    @Test
    fun `Ubuntu 换源脚本会退役传统 sources_list`() {
        val e = env()
        val script = ProotCommand.switchMirrorScript(e)
        assertTrue(
            "换源脚本必须把 ${e.legacySourcesFile} 改名让位，否则 apt 会读两份：\n$script",
            script.contains("mv -f ${e.legacySourcesFile}"),
        )
        assertTrue(
            "改名的目标后缀必须是 .praxis-disabled（apt 不扫这个后缀）",
            script.contains("${e.legacySourcesFile}.praxis-disabled"),
        )
    }

    /**
     * 退役动作必须**在写新源之后、apt update 之前**。
     *
     * 顺序错了会有两种坏结果：
     *  - 在写新源之前退役：中间态是「一个源都没有」，若脚本在此中断用户就没源了；
     *  - 在 apt update 之后退役：这一轮 update 仍然会读两份，警告照旧出现一次。
     */
    @Test
    fun `退役动作在写新源之后且在 apt-get update 之前`() {
        val e = env()
        val script = ProotCommand.switchMirrorScript(e)
        val retireAt = script.indexOf("mv -f ${e.legacySourcesFile}")
        val writeAt = script.indexOf("> ${e.sourcesFile}")
        val updateAt = script.indexOf("apt-get update")
        assertTrue("脚本里找不到退役动作", retireAt > 0)
        assertTrue("脚本里找不到写新源", writeAt > 0)
        assertTrue("脚本里找不到 apt-get update", updateAt > 0)
        assertTrue(
            "退役必须在写新源之后（否则中间态没有源）：write=$writeAt retire=$retireAt\n$script",
            retireAt > writeAt,
        )
        assertTrue(
            "退役必须在 apt-get update 之前（否则这轮仍会读两份）：retire=$retireAt update=$updateAt\n$script",
            retireAt < updateAt,
        )
    }

    /**
     * 备份要在退役**之前**做 —— 否则备份到的是已改名后的空文件。
     */
    @Test
    fun `备份动作在退役之前`() {
        val e = env()
        val script = ProotCommand.switchMirrorScript(e)
        val backupAt = script.indexOf("${e.legacySourcesFile}.praxis-bak")
        val retireAt = script.indexOf("mv -f ${e.legacySourcesFile}")
        assertTrue("脚本里找不到备份动作", backupAt > 0)
        assertTrue(
            "备份必须在退役之前，否则备份到的是空文件：backup=$backupAt retire=$retireAt",
            backupAt < retireAt,
        )
    }

    /**
     * 退役是**改名**，不是删除 —— 用户要有退路。
     */
    @Test
    fun `退役用改名而不是 rm 删除`() {
        val e = env()
        val retire = ProotCommand.retireLegacySources(e)
        assertTrue("应该是 mv", retire.contains("mv -f"))
        assertFalse(
            "不能直接 rm 掉用户的 sources.list（换源是可逆操作，不该让它不可逆）：\n$retire",
            retire.contains("rm -f ${e.legacySourcesFile}"),
        )
    }

    /**
     * Kali 走另一套脚本：它必须清掉 Ubuntu 的源，否则两套发行版的源混在一起。
     */
    @Test
    fun `Kali 换源会清掉 Ubuntu 的源文件`() {
        val e = env(LinuxDistro.Kali)
        val script = ProotCommand.switchMirrorScript(e)
        assertTrue(
            "Kali 脚本必须删掉 ubuntu.sources，否则源混用：\n$script",
            script.contains("rm -f /etc/apt/sources.list.d/ubuntu.sources"),
        )
        assertTrue(
            "Kali 脚本也要清掉传统 sources.list：\n$script",
            script.contains("rm -f /etc/apt/sources.list"),
        )
    }
}
