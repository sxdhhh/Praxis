package dev.praxis.data.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ③ Kali/Ubuntu rootfs 解压时「认硬链接条目」的解析规则。
 *
 * ## 为什么这段必须单独测（2026-11-02）
 *
 * 用户的 ③ 是 `tar: can't create 'kali-arm64/dev/zero': Operation not permitted`。
 * 修法里有一环是「先把 tar -tv 里的硬链接条目列出来、解压时 --exclude 掉，
 * 解完再 cp 成独立副本」—— 因为 Android 的 SELinux 不允许应用建硬链接。
 *
 * 这条链上最危险的是**解析**：判错一个名字，那个文件就不会被解出来，
 * 而 tar 的退出码仍可能是 0。**错了完全不会响**，用户只会发现「少了个命令」。
 * 这种「静默少文件」比直接报错难查得多，所以把解析抽成纯函数并逐条钉死。
 *
 * 下面所有输入都是 `toybox tar -tvf` 的**真实输出格式**。
 */
class HardLinkParseTest {

    /**
     * 单条用例 30 秒闸门（2026-11-05）。
     *
     * ## 为什么加在每一个 :app 测试类上
     *
     * `:app:testDebugUnitTest` 在 CI 上卡住过 17 分钟不结束
     * （正常 0.3~1.4 分钟），而 Gradle **默认不给测试任务设超时**，
     * 也**不打印正在跑哪条用例** —— 于是只看到一个不动的进度条。
     *
     * 试过在 app/build.gradle.kts 里配 `Test.timeout` 与
     * `testLogging.events(started)`：对**被取消**的任务，Gradle 会把
     * 缓冲的输出一起丢掉，日志里一行都没有。
     *
     * 所以改成让**断言层**自己超时 —— 失败信息里直接带类名与方法名，
     * 不依赖 Gradle 的输出缓冲。
     *
     * 30 秒是宽裕值：本文件最快的用例不到 10 毫秒。
     */
    @get:org.junit.Rule
    val praxisTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(30)

    @Test
    fun `识别带路径前缀的硬链接行`() {
        // Ubuntu base 镜像里真实存在的两条之一（gunzip）。
        val line = "-rwxr-xr-x root/root       24 2024-04-01 12:00 ./usr/bin/gunzip link to ./usr/bin/uncompress"
        val e = ProotEnvPort.parseHardLinkLine(line)
        assertEquals("./usr/bin/gunzip", e?.linkName)
        assertEquals("./usr/bin/uncompress", e?.targetName)
    }

    @Test
    fun `识别 perl 的硬链接条目`() {
        val line = "-rwxr-xr-x root/root  2941304 2024-04-01 12:00 ./usr/bin/perl link to ./usr/bin/perl5.38.2"
        val e = ProotEnvPort.parseHardLinkLine(line)
        assertEquals("./usr/bin/perl", e?.linkName)
        assertEquals("./usr/bin/perl5.38.2", e?.targetName)
    }

    /**
     * 普通文件不得被当成硬链接。
     *
     * 误判的代价：这个文件会被 `--exclude` 跳过，解压完就没有它了。
     */
    @Test
    fun `普通文件行返回 null`() {
        val line = "-rw-r--r-- root/root       123 2024-04-01 12:00 ./etc/hostname"
        assertNull(ProotEnvPort.parseHardLinkLine(line))
    }

    /**
     * 符号链接行也不该被认成硬链接。
     *
     * `tar -tv` 对符号链接输出的是 `a -> b`，与硬链接的 `a link to b` 不同；
     * 符号链接在 Android 上是可以创建的，不需要排除。
     */
    @Test
    fun `符号链接行返回 null`() {
        val line = "lrwxrwxrwx root/root        0 2024-04-01 12:00 ./usr/bin/sh -> dash"
        assertNull(ProotEnvPort.parseHardLinkLine(line))
    }

    /**
     * 目录行返回 null。
     */
    @Test
    fun `目录行返回 null`() {
        val line = "drwxr-xr-x root/root        0 2024-04-01 12:00 ./usr/bin/"
        assertNull(ProotEnvPort.parseHardLinkLine(line))
    }

    /**
     * 文件名里含 `linkto`（无空格）不得误判。
     *
     * 这是「判据必须带前后空格」的直接原因。
     */
    @Test
    fun `文件名含 linkto 的普通文件不误判`() {
        val line = "-rw-r--r-- root/root        10 2024-04-01 12:00 ./opt/mylinkto.txt"
        assertNull(ProotEnvPort.parseHardLinkLine(line))
    }

    /**
     * 空行或残缺行不得崩。
     */
    @Test
    fun `空行与残缺行安全返回 null`() {
        assertNull(ProotEnvPort.parseHardLinkLine(""))
        assertNull(ProotEnvPort.parseHardLinkLine(" link to "))
        assertNull(ProotEnvPort.parseHardLinkLine("garbage"))
    }

    /**
     * 目标侧为空时不产出条目（避免 `--exclude` 拿到空串）。
     */
    @Test
    fun `目标为空返回 null`() {
        val line = "-rw-r--r-- root/root 0 2024-04-01 12:00 ./a.txt link to "
        assertNull(ProotEnvPort.parseHardLinkLine(line))
    }

    // ── toybox 格式（2026-11-02 在模拟器实测到的真实输出）────────────

    /**
     * Android 11 的 toybox 0.8.3 用 `-> ` 而不是 ` link to `。
     *
     * ## 这是本解析器最关键的一条
     *
     * 只认 ` link to ` 的话，在这类设备上**一条硬链接都认不出来** ——
     * 排除静默失效，tar 继续报 `can't link ... Permission denied`，
     * 而错误信息完全指不到「你的 tar 措辞我没认出来」。
     *
     * 下面的输入是在 API 30 模拟器上 `toybox tar -tvf` 的**逐字输出**。
     */
    @Test
    fun `toybox 的箭头格式也认作硬链接`() {
        val line = "-rw-rw-rw- root/root         0  2026-11-01 20:28 ./usr/bin/gunzip -> ./usr/bin/uncompress"
        val e = ProotEnvPort.parseHardLinkLine(line)
        assertEquals("./usr/bin/gunzip", e?.linkName)
        assertEquals("./usr/bin/uncompress", e?.targetName)
    }

    /**
     * 符号链接同样是 `-> `，但行首是 `l` —— 不能当成硬链接。
     *
     * 误判的后果：那个符号链接被 `--exclude` 掉，解压后凭空少一个文件。
     * 而符号链接在 Android 上本来就能正常创建，完全不需要排除。
     */
    @Test
    fun `toybox 的符号链接不算硬链接`() {
        val line = "lrwxrwxrwx root/root         0  2026-11-01 20:28 ./usr/bin/sh -> dash"
        assertNull(ProotEnvPort.parseHardLinkLine(line))
    }

    /** 两种格式的目录条目都不能认成硬链接。 */
    @Test
    fun `toybox 的目录条目不算硬链接`() {
        val line = "drwxrwxrwx root/root         0  2026-11-01 20:28 ./usr/bin/"
        assertNull(ProotEnvPort.parseHardLinkLine(line))
    }

    /**
     * 两种格式同时存在时各自都能解析（GNU 与 toybox 混排的极端情况）。
     */
    @Test
    fun `两种格式都能解析`() {
        val gnu = "-rw-r--r-- root/root  0 2024-04-01 12:00 ./a link to ./b"
        val toy = "-rw-r--r-- root/root  0 2026-11-01 20:28 ./c -> ./d"
        assertEquals("./b", ProotEnvPort.parseHardLinkLine(gnu)?.targetName)
        assertEquals("./d", ProotEnvPort.parseHardLinkLine(toy)?.targetName)
    }
}
