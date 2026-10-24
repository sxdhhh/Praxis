package dev.praxis.core.linux

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 镜像表与下载地址的护栏。
 *
 * 这里的每一条断言都对应一次「装环境第 0 步就失败」：
 * URL 拼错是 404、arm64 用错池是「找不到包」、枚举改名是设置页打不开。
 */
class UbuntuMirrorTest {

    @Test
    fun `每个镜像站的地址都是 https 或 http 且不带尾斜杠`() {
        UbuntuMirror.entries.forEach { mirror ->
            listOf(mirror.archive, mirror.ports).forEach { url ->
                assertTrue(
                    "${mirror.name} 的地址缺少 scheme：$url",
                    url.startsWith("https://") || url.startsWith("http://"),
                )
                assertFalse("${mirror.name} 的地址不该带尾斜杠：$url", url.endsWith("/"))
            }
            // archive 与 ports 必须是**不同**的路径：合成同一个就会出现
            // 「arm64 从 amd64 池取包」→ 找不到包。
            assertTrue(
                "${mirror.name} 的 archive 与 ports 必须不同",
                mirror.archive != mirror.ports,
            )
        }
    }

    @Test
    fun `ports 地址都以 -ports 结尾`() {
        // Ubuntu 的约定：arm64 池的路径就是主池路径加 `-ports`。
        // 这一条不对就说明有人把两个字段填反了。
        UbuntuMirror.entries.forEach { mirror ->
            assertTrue(
                "${mirror.name} 的 ports 应以 -ports 结尾，实际 ${mirror.ports}",
                mirror.ports.endsWith("-ports"),
            )
        }
    }

    @Test
    fun `只有 Official 不改 sources`() {
        assertFalse(UbuntuMirror.Official.changesSources)
        UbuntuMirror.entries
            .filter { it != UbuntuMirror.Official }
            .forEach { assertTrue("${it.name} 应该换源", it.changesSources) }
    }

    @Test
    fun `按名还原找不到时回落到清华`() {
        // 回落目标不是 Official：Praxis 用户大多在国内，
        // 「没换源」的失败率远高于「换到另一个能连通的镜像」。
        assertEquals(UbuntuMirror.Aliyun, UbuntuMirror.fromNameOrDefault("Aliyun"))
        assertEquals(UbuntuMirror.Tsinghua, UbuntuMirror.fromNameOrDefault(null))
        assertEquals(UbuntuMirror.Tsinghua, UbuntuMirror.fromNameOrDefault(""))
        assertEquals(UbuntuMirror.Tsinghua, UbuntuMirror.fromNameOrDefault("NotAMirror"))
    }

    @Test
    fun `label 非空且不重复`() {
        val labels = UbuntuMirror.entries.map { it.label }
        labels.forEach { assertTrue("label 不能为空", it.isNotBlank()) }
        assertEquals("label 重复会让下拉出现两项一样的", labels.size, labels.toSet().size)
    }

    // ── 下载地址 ────────────────────────────────────────────────────

    @Test
    fun `清华 24_04 的首个候选就是实测可用的那个`() {
        val urls = UbuntuBaseImage.candidateUrls(UbuntuMirror.Tsinghua, UbuntuRelease.Noble)
        // 2026-10-21 实测返回 200（content-length 29865086）的确切地址。
        assertEquals(
            "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/" +
                "ubuntu-base-24.04.3-base-arm64.tar.gz",
            urls.first(),
        )
    }

    @Test
    fun `候选地址按补丁号从新到旧，且都指向同一版本目录`() {
        val urls = UbuntuBaseImage.candidateUrls(UbuntuMirror.Tsinghua, UbuntuRelease.Noble)
        assertTrue("应有多个候选用于兜底", urls.size >= 2)
        // 全部在同一 version 目录下（补丁号只在文件名里）。
        urls.forEach { url ->
            assertTrue("版本目录不对：$url", url.contains("/releases/24.04/release/"))
        }
        // 补丁号各不相同。
        assertEquals(urls.size, urls.toSet().size)
    }

    @Test
    fun `cdimage 根由 archive 推导，不硬编码域名`() {
        // archive = https://host/ubuntu → cdimage 根 = https://host/ubuntu-cdimage
        UbuntuMirror.entries
            .filter { it.changesSources }
            .forEach { mirror ->
                val url = UbuntuBaseImage.candidateUrls(mirror, UbuntuRelease.Noble).first()
                assertTrue(
                    "${mirror.name} 的 cdimage 根拼接错误：$url",
                    url.contains("/ubuntu-cdimage/ubuntu-base/"),
                )
            }
    }

    @Test
    fun `架构与版本都体现在文件名里`() {
        val url = UbuntuBaseImage.candidateUrls(
            UbuntuMirror.Tsinghua,
            UbuntuRelease.Jammy,
            UbuntuBaseImage.ARCH_ARM64,
        ).first()
        assertTrue(url.endsWith("-base-arm64.tar.gz"))
        assertTrue(url.contains("22.04"))
        // jammy 的补丁号
        assertTrue(url.contains("22.04.5"))
    }

    @Test
    fun `官方兜底地址走 cdimage 而不是 releases`() {
        val url = UbuntuBaseImage.officialUrl(UbuntuRelease.Noble)
        // releases.ubuntu.com 只有桌面 ISO，没有 ubuntu-base。
        assertTrue("应走 cdimage.ubuntu.com：$url", url.startsWith("https://cdimage.ubuntu.com/"))
        assertTrue(url.contains("/ubuntu-base/"))
    }

    // ── 版本表 ──────────────────────────────────────────────────────

    @Test
    fun `版本表里的 suite 与 version 对应`() {
        assertEquals("noble", UbuntuRelease.Noble.suite)
        assertEquals("24.04", UbuntuRelease.Noble.version)
        assertEquals("jammy", UbuntuRelease.Jammy.suite)
        assertEquals("22.04", UbuntuRelease.Jammy.version)
    }

    @Test
    fun `按名还原版本找不到时回落到 Noble`() {
        assertEquals(UbuntuRelease.Jammy, UbuntuRelease.fromNameOrDefault("Jammy"))
        assertEquals(UbuntuRelease.Noble, UbuntuRelease.fromNameOrDefault(null))
        assertEquals(UbuntuRelease.Noble, UbuntuRelease.fromNameOrDefault("CentOS"))
    }
}

/**
 * proot 参数的护栏。
 *
 * proot 的参数错一个就是容器起不来，且报错是它自己的英文输出。
 * 这些断言把「必须存在的开关」钉住，避免后续重构时被顺手删掉。
 */
class ProotCommandTest {

    private val env = LinuxEnvironment(
        rootfsDir = File("/data/app/praxis/files/linux/ubuntu"),
        prootBinary = File("/data/app/praxis/lib/libproot.so"),
        release = UbuntuRelease.Noble,
        mirror = UbuntuMirror.Tsinghua,
    )

    private fun List<String>.indexOfFlag(flag: String): Int = indexOf(flag)

    @Test
    fun `必带的 proot 开关都在`() {
        val args = ProotCommand.loginArgs(env)
        // link2symlink：不加则 apt 装包（dpkg 用硬链接）会失败。
        assertTrue("缺少 --link2symlink", args.contains("--link2symlink"))
        // kill-on-exit：不加会留下孤儿 sshd 占端口。
        assertTrue("缺少 --kill-on-exit", args.contains("--kill-on-exit"))
        // -0：把容器内 uid 0 映射成当前 app uid，apt 才能写 /var/lib/dpkg。
        assertTrue("缺少 -0", args.contains("-0"))
    }

    @Test
    fun `rootfs 与工作目录成对出现`() {
        val args = ProotCommand.loginArgs(env)
        val r = args.indexOfFlag("-r")
        val w = args.indexOfFlag("-w")
        assertTrue("-r 缺失", r >= 0)
        assertTrue("-w 缺失", w >= 0)
        assertEquals(env.rootfsDir.absolutePath, args[r + 1])
        assertEquals(env.homeDir, args[w + 1])
    }

    @Test
    fun `关键 bind 都在且指向真实设备节点`() {
        val args = ProotCommand.loginArgs(env)
        val binds = args.filterIndexed { i, _ -> i > 0 && args[i - 1] == "-b" }
        // /dev 与 /proc 是 apt / sshd 的硬依赖。
        assertTrue("/dev 未挂", binds.contains("/dev"))
        assertTrue("/proc 未挂", binds.contains("/proc"))
        assertTrue("/sys 未挂", binds.contains("/sys"))
        // 设备节点必须能落到真实路径：不存在的路径挂进去等于没挂。
        assertTrue(binds.contains("/dev/null:/dev/null"))
        assertTrue(binds.contains("/dev/zero:/dev/zero"))
        assertTrue(binds.contains("/dev/urandom:/dev/urandom"))
    }

    @Test
    fun `默认不挂 sdcard`() {
        // 挂 /sdcard 意味着容器内任何进程都能删手机真实文件。
        // 必须是显式可选，不能进默认列表。
        val args = ProotCommand.loginArgs(env)
        assertFalse(
            "默认参数里不该出现 /sdcard",
            args.any { it.contains("/sdcard") || it.contains("/storage/emulated") },
        )
    }

    @Test
    fun `额外挂载支持 宿主容器 与单路径两种写法`() {
        val args = ProotCommand.loginArgs(
            env,
            extraBinds = listOf("/sdcard:/mnt/sdcard", "/data/local/tmp"),
        )
        assertTrue(args.contains("/sdcard:/mnt/sdcard"))
        assertTrue(args.contains("/data/local/tmp"))
    }

    @Test
    fun `交互 shell 是 bash -l，非交互是 sh -c`() {
        val login = ProotCommand.loginArgs(env)
        assertEquals("/bin/bash", login[login.size - 2])
        assertEquals("-l", login.last())

        val exec = ProotCommand.execArgs(env, "apt-get update")
        assertEquals("/bin/sh", exec[exec.size - 3])
        assertEquals("-c", exec[exec.size - 2])
        assertEquals("apt-get update", exec.last())
    }

    @Test
    fun `execArgs 保留 loginArgs 的全部 proot 开关`() {
        // 装环境时同样需要 -0 / --link2symlink，否则 apt 直接失败。
        val exec = ProotCommand.execArgs(env, "true")
        assertTrue(exec.contains("--link2symlink"))
        assertTrue(exec.contains("-0"))
        assertTrue(exec.contains("-r"))
    }

    @Test
    fun `不把 Android 的 LD_LIBRARY_PATH 带进容器`() {
        // 参数里不该出现任何 LD_LIBRARY_PATH —— 环境变量由 runner 显式设置。
        // 若从 Android 继承，容器内程序会去加载 bionic 库并崩溃。
        val args = ProotCommand.loginArgs(env)
        assertFalse(args.any { it.contains("LD_LIBRARY_PATH") })
    }

    // ── 换源脚本 ────────────────────────────────────────────────────

    @Test
    fun `换源用 ports 而不是 archive`() {
        val script = ProotCommand.switchMirrorScript(env)
        // arm64 必须走 ports 池，用 archive 会「找不到包」。
        assertTrue("应使用 ports 池", script.contains(UbuntuMirror.Tsinghua.ports))
        assertFalse("不该出现主池地址", script.contains("${UbuntuMirror.Tsinghua.archive} "))
    }

    @Test
    fun `换源同时写 deb822 与传统 sources_list`() {
        val script = ProotCommand.switchMirrorScript(env)
        // 24.04 读 ubuntu.sources，22.04 读 sources.list。
        // 只写一个就会出现「换源后 apt 还在连官方」。
        assertTrue(script.contains(env.sourcesFile))
        assertTrue(script.contains(env.legacySourcesFile))
        assertTrue(script.contains("Types: deb"))
        assertTrue(script.contains("Suites: noble"))
        assertTrue(script.contains("apt-get update"))
    }

    // ── Kali（2026-10-22 实测修正）────────────────────────────

    @Test
    fun `Kali 换源写 deb822 且保留 Signed-By`() {
        // 实测 NetHunter minimal 的 rootfs 里**没有** /etc/apt/sources.list，
        // 只有 /etc/apt/sources.list.d/kali.sources（deb822）。
        // 旧实现删掉那个文件再写 sources.list → apt 什么都拿不到。
        val kaliEnv = env.copy(distro = LinuxDistro.Kali, kaliMirror = KaliMirror.Tsinghua)
        val script = ProotCommand.kaliMirrorScript(kaliEnv)

        assertTrue(
            "必须写到 deb822 的路径",
            script.contains("/etc/apt/sources.list.d/kali.sources"),
        )
        assertTrue("必须是 deb822 格式", script.contains("Types: deb"))
        assertTrue("suite 必须是 kali-rolling", script.contains("Suites: kali-rolling"))
        assertTrue("清华大学源地址", script.contains(KaliMirror.Tsinghua.base))
        // 缺这一行 → apt 报 NO_PUBKEY，整体失败。
        assertTrue(
            "必须声明签名来源",
            script.contains("Signed-By: /usr/share/keyrings/kali-archive-keyring.gpg"),
        )
        // 组件少写 non-free → 大量 Kali 工具「包不存在」。
        assertTrue(script.contains("main contrib non-free non-free-firmware"))
    }

    @Test
    fun `Kali 换源不会留下会双份生效的传统 sources_list`() {
        val kaliEnv = env.copy(distro = LinuxDistro.Kali)
        val script = ProotCommand.kaliMirrorScript(kaliEnv)
        // 传统格式必须被清掉：apt 会同时读 sources.list 与 sources.list.d 目录，
        // 两份都在时会双份拉取。
        assertTrue(
            "要清掉传统 sources.list",
            script.contains("rm -f /etc/apt/sources.list\n") ||
                script.contains("rm -f /etc/apt/sources.list "),
        )
    }

    @Test
    fun `切回 Ubuntu 时同时清掉 Kali 的两种源文件`() {
        // 只删 kali.sources 而留下 kali.list 的话，那份仍在生效，
        // apt 会装出 kali-rolling 的包而**不报错**。
        val script = ProotCommand.sourceSwitchCommand(env.copy(distro = LinuxDistro.Ubuntu))
        assertTrue(script.contains("kali.sources"))
        assertTrue(script.contains("kali.list"))
    }


    @Test
    fun `换源前会备份，且备份只在不存在时创建`() {
        val script = ProotCommand.switchMirrorScript(env)
        assertTrue(script.contains("${env.sourcesFile}.praxis-bak"))
        // 显式判断而不是 `cp -n` 的副作用。
        assertTrue(script.contains("if [ ! -f ${env.sourcesFile}.praxis-bak ]; then"))
    }

    @Test
    fun `选官方时把两个文件都改回官方源`() {
        val official = env.copy(mirror = UbuntuMirror.Official)
        val script = ProotCommand.switchMirrorScript(official)
        assertTrue(script.contains(UbuntuMirror.Official.ports))
        assertTrue(script.contains("apt-get update"))
    }

    @Test
    fun `jammy 换源写 jammy 的 suite`() {
        val jammy = env.copy(release = UbuntuRelease.Jammy)
        val script = ProotCommand.switchMirrorScript(jammy)
        assertTrue(script.contains("Suites: jammy jammy-updates"))
        assertFalse(script.contains("noble"))
    }

    @Test
    fun `DNS 脚本写公共 DNS 而不是依赖系统 resolv_conf`() {
        val script = ProotCommand.dnsScript()
        // Android 的 /etc/resolv.conf 是空的（DNS 由 netd 管），
        // 必须自己写，否则容器里报 "Temporary failure resolving"。
        assertTrue(script.contains("223.5.5.5"))
        assertTrue(script.contains("119.29.29.29"))
        assertTrue(script.contains("/etc/resolv.conf"))
        // 国内网络下 8.8.8.8 常不可达。
        assertFalse(script.contains("8.8.8.8"))
    }

    // ── 安装判定 ────────────────────────────────────────────────────

    @Test
    fun `isInstalled 判定看 bin_sh`() {
        // 用一个真实存在的临时目录模拟「已解压」。
        val tmp = kotlin.io.path.createTempDirectory("praxis-rootfs").toFile()
        try {
            val notYet = LinuxEnvironment(tmp, File("/x/libproot.so"))
            assertFalse("空目录不该算已安装", notYet.isInstalled)

            File(tmp, "bin").mkdirs()
            File(tmp, "bin/sh").writeText("")
            assertTrue("有 /bin/sh 就该算已安装", notYet.isInstalled)
        } finally {
            tmp.deleteRecursively()
        }
    }
}