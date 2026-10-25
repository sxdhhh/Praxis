package dev.praxis.core.linux

import java.io.File

/**
 * Linux 环境（proot 容器）的完整配置。
 *
 * ## 与 apixiaoyuan 的结论**不同**，且这里说清为什么
 *
 * apixiaoyuan 的 `TerminalSession` KDoc 有一条很有说服力的论证：Android 自带
 * `/system/bin/sh` + toybox 就是一个完整用户态，再塞 Ubuntu 只是「把同样的东西
 * 换成 200MB 的另一份副本，而且还要 proot 才能跑」。
 *
 * 那条结论**对它的目标成立**（它只需要在终端里跑 node 服务）。但用户这次要的是
 * 三件 apixiaoyuan 的 sh 给不了的事，所以必须真有一个 Linux 发行版：
 *
 * | 需求 | 系统 sh 能不能 | 为什么 |
 * |---|---|---|
 * | `apt` 装包 | ✗ | toybox 没有包管理器，只有上百个 applet |
 * | 换成国内镜像源 | ✗ | 没有 apt 就没有 sources.list 可换 |
 * | `sshd` / `frpc` | ✗ | 需要 glibc 用户态与完整 `/etc`；Android 是 bionic |
 *
 * 所以本模块走「真 rootfs + proot」这条路，并把这套能力**独立**于系统终端存在
 * （系统终端仍可用，两者不冲突）。
 *
 * ## 为什么容器配置是纯数据
 *
 * 它不碰 `Context`、不碰 `ProcessBuilder`，只描述「该跑什么、该挂什么」。
 * 于是可以：
 *  - 纯 JVM 单测（参数拼错能在 CI 拦住，而不是等用户装了 30MB 镜像后才发现）；
 *  - 换执行方式（本地 proot / 未来的真 root / 远端）不改这份描述。
 *
 * 与 `CommandRunner`（`feature:terminal`）同一套「端口在里、实现在外」的思路。
 */
data class LinuxEnvironment(
    /** 容器根目录（rootfs 解开的位置），如 `filesDir/linux/ubuntu`。 */
    val rootfsDir: File,
    /** proot 可执行文件（`nativeLibraryDir/libproot.so`，见类 KDoc 的说明）。 */
    val prootBinary: File,
    /**
     * 发行版（Ubuntu / Kali）。
     *
     * ## 2026-10-22 起：它不再决定 [rootfsDir]
     *
     * 用户要求「kali 和 ubuntu 都下下来直接放随便选，然后共用一个文件系统」。
     * 所以现在 **rootfs 路径是共享的**（见 [SHARED_ROOTFS_DIR_NAME]），
     * [distro] 只决定两件事：
     *
     *  1. 首次安装时解包哪个内置 rootfs（Ubuntu base 或 Kali NetHunter）；
     *  2. 切换后往 `/etc/apt/sources.list*` 写哪个源、以及提供哪套预置工具链。
     *
     * ## 为什么共用而不是各装一份
     *
     * | | 各装一份 | 共用一份 |
     * |---|---|---|
     * | 磁盘 | 120 MB + 500 MB = **620 MB** | 约 500 MB（取大者） |
     * | 切发行版 | 摘掉一个、挂另一个 | 换 `/etc/apt` 源 + 重新 `apt install` 差集 |
     * | 用户文件 | 两份 `/root`，互不可见 | **同一份**，切过去还在 |
     * | 装过的工具 | 两份，各装一遍 | 一份，切过去仍在（同族包管理） |
     *
     * 最后一行是决定性理由：Ubuntu 与 Kali 都是 Debian 系、都用 `apt`、
     * 包名基本通用。用户装一次 `python3` 后切到另一个发行版，
     * 那份 `python3` 照样能用 —— 这才是用户想要的「随便选」。
     *
     * 代价：两个发行版不能同时「纯净」。共用文件系统的本质就是
     * 一个 Debian 环境加两套源 + 两套预置工具，而不是两个独立系统。
     * 这一点必须在界面上写清，不能假装是两套。
     */
    val distro: LinuxDistro = LinuxDistro.Ubuntu,
    /** 发行版。仅 Ubuntu 生效；Kali 恒为 rolling，忽略此字段。 */
    val release: UbuntuRelease = UbuntuRelease.Noble,
    /** Ubuntu 镜像源。`distro == Ubuntu` 时生效。 */
    val mirror: UbuntuMirror = UbuntuMirror.Tsinghua,
    /** Kali 镜像源。`distro == Kali` 时生效。 */
    val kaliMirror: KaliMirror = KaliMirror.Tsinghua,
    /** 容器内的家目录（一般 `/root`）。 */
    val homeDir: String = "/root",
    /**
     * 是否把容器的 `/tmp` 与宿主的私有 tmp 目录共享。
     *
     * ## 为什么这是个真实需求（不是一个“高级选项”）
     *
     * 默认**不共享**：容器里的 `/tmp` 是 rootfs 自带的普通目录，
     * 所有内容随 rootfs 一起留在 `filesDir` 里。这有两个实际后果：
     *
     *  1. **容器内的临时文件会永久占空间**。`apt` 会往 `/tmp` 解压大量中间文件，
     *     很多程序（编译器、pip、npm）在异常退出时也不清理 —— 用久了
     *     `/tmp` 能涨到几百 MB，而用户在设置页看到的“环境体积”只增不减；
     *  2. **App 侧看不到这些文件**。用户在终端里 `cp` 一个东西到 `/tmp`
     *     想用文件管理器拿出来，拿不到（`/tmp` 在 rootfs 里，不是共享存储）。
     *
     * 打开之后 `/tmp` 挂到 `filesDir/linux/tmp-host`，于是：
     *  - 清理它就等于清理容器临时文件（设置页的“清空临时文件”接这个）；
     *  - App 侧可以直接读那个目录。
     *
     * ## 为什么不直接挂 `/sdcard`
     *
     * 见 [ProotCommand] 的 KDoc：挂真实外部存储会让容器里任何进程
     * （含用户随手 `rm -rf`）都能改手机的真实文件。私有 tmp 目录
     * 是“可清理、可丢弃”的语义，风险与收益才是匹配的。
     *
     * ## 为什么它要参与 proot 参数
     *
     * `-b host:container` 是启动参数，**改它必须重启容器**才生效。
     * 所以设置页改这个开关时要把容器停掉（见 `LinuxEnvPort.start`），
     * 否则用户会看到“打开了但没生效”。
     */
    val sharedTmp: Boolean = false,
) {
    /** `ubuntu.sources` 的位置（24.04 起的 deb822 格式）。 */
    val sourcesFile: String get() = "/etc/apt/sources.list.d/ubuntu.sources"

    /** 旧版 `sources.list`。22.04 用这个；两个都写覆盖两种情况。 */
    val legacySourcesFile: String get() = "/etc/apt/sources.list"

    /** 容器是否已经解压好。 */
    val isInstalled: Boolean
        get() = File(rootfsDir, "bin/sh").isFile || File(rootfsDir, "usr/bin/sh").isFile

    /**
     * 当前共享 rootfs 里**已装**的算是哪个发行版。
     *
     * ## 为什么要记录这个
     *
     * 共用文件系统后，「装的是 Ubuntu 还是 Kali」不再是路径问题，而是
     * 「这份 rootfs 最初从哪个 tarball 解出来」的**历史事实**。
     * 它写在 rootfs 里的一个标记文件（[MARKER_FILE_NAME]）中：
     *
     * ```
     * /opt/.praxis-rootfs-origin   →   "ubuntu" | "kali"
     * ```
     *
     * 用途只有一个：当用户在界面上选「Kali」但共享 rootfs 是从 Ubuntu
     * base 解出来的时，能明确告诉他「当前底层是 Ubuntu base，
     * 切成 Kali 只会换源 + 补装工具，不会重装系统」。
     * 不写清的话用户会以为点了 Kali 就变成 Kali 了。
     *
     * @return 标记的发行版名；还没装或标记缺失时 null
     */
    fun rootfsOrigin(): String? = runCatching {
        val marker = File(rootfsDir, MARKER_FILE_NAME)
        if (marker.isFile) marker.readText().trim().lowercase().takeIf { it.isNotEmpty() } else null
    }.getOrNull()

    /**
     * `/etc/apt` 当前指向哪个发行版的仓库。
     *
     * 读的是换源时写下的标记文件（[SOURCES_MARKER_FILE_NAME]），
     * 而不是去解析 `sources.list` 内容 —— 解析要区分 deb822 与传统格式，
     * 还要处理注释与多文件合并，成本高且易错。写一个标记就够。
     *
     * @return `"ubuntu"` / `"kali"`；换过源才有值，刚装完为 null
     */
    fun activeSources(): String? = runCatching {
        val marker = File(rootfsDir, SOURCES_MARKER_FILE_NAME)
        if (marker.isFile) marker.readText().trim().lowercase().takeIf { it.isNotEmpty() } else null
    }.getOrNull()

    companion object {
        /**
         * 共享 rootfs 的目录名（相对 `filesDir/linux/`）。
         *
         * ## 为什么名字里不带发行版
         *
         * 它是两个发行版**共用**的那一份，名字里带 `ubuntu` 或 `kali`
         * 都会误导后来者以为「另一个在这」。用中性的 `shared`。
         *
         * 选 `shared` 而不是 `rootfs`：`rootfs` 太泛，和 `LinuxDistro`
         * 的术语容易混；`shared` 直接表达了「两个发行版共用这一份」。
         */
        const val SHARED_ROOTFS_DIR_NAME: String = "shared"

        /**
         * 记录「这份 rootfs 最初从哪个发行版解出来」的标记文件（容器内路径）。
         *
         * 放 `/opt` 而不是 `/` 或 `/etc`：
         *  - `/` 根目录会被 `ls` 看到，用户会困惑为什么多了个文件；
         *  - `/etc` 是配置文件区，`dpkg` 升级时可能被覆盖或产生 conffile 询问；
         *  - `/opt` 是「第三方附加内容」的约定位置，不会被包管理器动。
         */
        const val MARKER_FILE_NAME: String = "opt/.praxis-rootfs-origin"

        /**
         * 记录「`/etc/apt` 当前指向哪个发行版的仓库」的标记文件（容器内路径）。
         *
         * 与 [MARKER_FILE_NAME] 分开：
         *  - 前者记「底层从哪来」（装完就不变）；
         *  - 本文件记「源指向谁」（每次换源都改）。
         *
         * 合成一个文件会让「切换源之后无法区分底层与当前源」——
         * 而界面正是靠这两个独立事实告诉用户「底层是 Ubuntu，源是 Kali」。
         */
        const val SOURCES_MARKER_FILE_NAME: String = "opt/.praxis-apt-sources"

        /**
         * 共享 tmp 的宿主目录名（相对 `filesDir/linux/`）。
         *
         * 与 [SHARED_ROOTFS_DIR_NAME] 平级而不是放进去：
         * rootfs 是「系统文件」，tmp 是「可随时删掉的垃圾」。
         * 放 rootfs 内部的话，清理 tmp 会触碰 rootfs 目录，
         * 而「重置环境」会连带删掉 tmp —— 两件本该独立的事被绑在一起。
         *
         * 名字带 `host` 后缀是为了让人一眼看出「这是宿主侧的真实目录，
         * 被挂进容器当 /tmp」。
         */
        const val HOST_TMP_DIR_NAME: String = "tmp-host"
    }
}

/**
 * 构造 proot 命令行。
 *
 * ## 这一段是本模块最容易错的地方
 *
 * proot 的参数顺序、`-b`（bind）的写法、`/dev` 与 `/proc` 的处理都极其敏感，
 * 错一个就是容器起不来，而报错是 proot 自己的英文输出（`proot info:` 前缀）。
 * 所以每个参数都在下面单独说明**为什么必须是这样**。
 *
 * ## 关键取舍：为什么 bind `/dev` 与 `/proc`
 *
 * rootfs 是从 tarball 解开的一个普通目录树，里面**没有**设备节点
 * （tarball 不带 `/dev/` 下的设备节点，即使带了，普通 app 也 mknod 不出真设备）。
 * 不 bind 的后果非常具体：
 *  - `apt` 报 `Could not open lock file /dev/null`；
 *  - `sshd` 起不来（要 `/dev/ptmx`、`/dev/urandom`）；
 *  - bash 提示 `cannot create /dev/null`。
 *
 * `/proc` 同理：`ps`、`apt` 的依赖解析、`sshd` 都要读。
 *
 * ## 为什么不 bind `/sdcard`
 *
 * **默认不挂**，只提供可选。理由：一旦挂了，容器里任何进程（包括用户随手
 * `rm -rf`）都能改手机的真实文件。真要传文件时由用户在界面里显式打开。
 */
object ProotCommand {

    /** 容器内必须存在的挂载点（bind 目标先得有）。 */
    val REQUIRED_MOUNTS: List<String> = listOf("/dev", "/proc", "/sys", "/tmp")

    /**
     * 启动一个**交互式**登录 shell 的完整参数。
     *
     * 返回的是「可执行文件 + 参数列表」，不是一条拼好的 shell 字符串 ——
     * 拼接会带来引号/空格的转义问题（路径里带空格时直接崩），
     * 而且无法在单测里逐参数断言。
     */
    fun loginArgs(env: LinuxEnvironment, extraBinds: List<String> = emptyList()): List<String> {
        val root = env.rootfsDir.absolutePath
        val args = mutableListOf<String>()

        // `--link2symlink`：Android 上 proot 处理硬链接的方式。
        // 不加时 `apt install` 里大量用硬链接的包（如 `dpkg` 自己）会失败。
        args += "--link2symlink"

        // `--kill-on-exit`：proot 退出时杀掉所有被跟踪的子进程。
        // 不加会留下一堆孤儿 sshd / apt 进程占着端口。
        args += "--kill-on-exit"

        // `-0`：把容器内的 uid/gid 0 映射成当前 app 的 uid。
        // 免 root 也能「像 root 一样」写文件（apt 必须能写 /var/lib/dpkg）。
        // ⚠️ 这是 proot 的**开关**，不是要执行的程序。
        args += "-0"

        // ── 日志级别：用 `-v`，**绝不能用 `-q`** ─────────────────
        //
        // ## 这条注释曾写错，导致容器一条命令都跑不起来
        //
        // 旧代码是 `args += listOf("-q", "/bin/true")`，注释写着
        // 「静默 proot 自己的 info 日志……传 /bin/true 是社区惯例，
        // proot 不会真的去执行它」。
        //
        // 那个理解是**反的**。看本机 `libproot.so --help` 的原文：
        //
        // ```
        //   -q *command*, --qemu=*command*
        //     Execute guest programs through QEMU as specified by *command*.
        // ```
        //
        // `-q` 是 **--qemu**，语义是「所有 guest 程序都通过这个 QEMU 前端执行」。
        // 传 `/bin/true` 等于告诉 proot：「用 /bin/true 去跑一切」。
        // `/bin/true` 读完参数就退出、什么都不执行，于是**每一个 guest 程序都
        // 被它吃掉**，proot 只能报：
        //
        // ```
        // proot error: execve("/usr/bin/sh"): No such file or directory
        // ```
        //
        // 这句报错把「被 QEMU 前端吞掉」说成「文件不存在」，还附了五条
        // 全部指向错误方向的「可能原因」（脚本解释器缺失 / ELF 解释器缺失 /
        // 架构不符 / qemu 异常 / loader 有问题）。
        //
        // ## 真机实测（2026-10-25，aarch64 / SDK 37）
        //
        // 同一份 rootfs、同一套环境变量（PROOT_LOADER / PROOT_TMP_DIR 均已设）：
        //
        // | 参数 | 结果 |
        // |---|---|
        // | `-0 -r <rootfs> --link2symlink -q /bin/true -w /root /bin/sh -c ...` | `execve: No such file or directory`，退出 |
        // | 去掉 `-q /bin/true`，其余完全不变 | 正常输出 |
        //
        // 另外 `--kill-on-exit`、`-b /dev`、`-b /proc`、`-b /sys`、`-b /dev/null`
        // 都逐项单独验证过，**均不影响**。唯一杀手就是 `-q`。
        //
        // ## 那日志怎么静默
        //
        // proot 的 info 日志受 `-v <level>` 控制，默认级别本来就不打印
        // `proot info:`（那是 `-v 1` 以上）。所以正确做法是**不传任何
        // 日志相关参数**，而不是用 `-q` 去「假装静默」。
        args += listOf("-v", "0")

        // ── bind ──────────────────────────────────────────────────
        //
        // 语法是 `-b 宿主路径[:容器路径]`。省略容器路径时按同路径挂载。
        args += bind("/dev")
        args += bind("/proc")
        args += bind("/sys")
        // 这四个设备节点单独再挂一次：`-b /dev` 已经把整个目录挂进来了，
        // 但在部分 ROM 上 `/dev` 本身是 tmpfs 且 proot 的目录 bind 对
        // 子节点不完全生效，单独挂能保证 `apt` / `sshd` 拿到它们。
        args += bind("/dev/urandom", "/dev/urandom")
        args += bind("/dev/random", "/dev/random")
        args += bind("/dev/null", "/dev/null")
        args += bind("/dev/zero", "/dev/zero")
        // DNS：Android 的 `/etc/resolv.conf` 内容通常是空的（DNS 由 netd 管），
        // 所以**不挂系统的**，而是由调用方在容器内写一份（见 [dnsScript]）。
        // 时区：不挂的话容器内时间是 UTC，apt 的时间戳告警会满天飞。
        if (File("/system/usr/share/zoneinfo").isDirectory) {
            args += bind("/system/usr/share/zoneinfo", "/usr/share/zoneinfo")
        }

        // ── 共享 tmp（2026-10-23 新增，由 [LinuxEnvironment.sharedTmp] 驱动）──
        //
        // ## 为什么放在这里而不是让调用方拼进 extraBinds
        //
        // 这个开关会被三个入口读到：设置页的「启动容器」、AI 工具的
        // `LinuxToolPort.run`、以及 SSH 的 `startSession`。若每个调用方
        // 自己判断 `sharedTmp` 再拼 bind，加一处入口就会漏一处 ——
        // 表现是「开了共享 tmp，但走 AI 那条路进去看不到文件」。
        //
        // 收在 `loginArgs` 里（`execArgs` / `interactiveArgs` 都经过它），
        // 一次改对所有路径生效，这是唯一不会漏的地方。
        //
        // ## 为什么要先 mkdir
        //
        // proot 的 `-b host:container` 要求**宿主路径存在**，否则它打印
        // `proot warning: can't bind` 然后**静默继续**（不报错、容器照起）。
        // 那是最糟的失败方式：用户开了开关、以为生效、实际没挂。
        // 所以这里先用 Kotlin 建目录 —— 启动参数生成处做副作用不优雅，
        // 但比「静默不生效」好得多。
        if (env.sharedTmp) {
            val hostTmp = File(env.rootfsDir.parentFile, LinuxEnvironment.HOST_TMP_DIR_NAME)
            runCatching { hostTmp.mkdirs() }
            if (hostTmp.isDirectory) {
                args += bind(hostTmp.absolutePath, "/tmp")
            }
        }

        extraBinds.forEach { spec ->
            // 支持 `宿主:容器` 与单个路径两种写法。
            val parts = spec.split(':', limit = 2)
            args += if (parts.size == 2) bind(parts[0], parts[1]) else bind(spec)
        }

        // ── 工作目录与 rootfs ─────────────────────────────────────
        args += listOf("-w", env.homeDir)
        args += listOf("-r", root)

        // ── 要执行的程序 ─────────────────────────────────────────
        //
        // ⚠️ 环境变量**不通过 `env` 命令传**，而是交给 proot 的 `-b` 之外的另一条路：
        // proot 会把父进程的环境原样带进容器，所以由调用方在 ProcessBuilder 的
        // environment() 里 set（见 :app 的 ProotCommandRunner）。
        // 这里只决定「进去跑什么」—— 一个登录 bash。
        args += listOf("/bin/bash", "-l")

        return args
    }

    /**
     * 非交互执行一条命令（安装、换源等）。
     *
     * 与 [loginArgs] 的差别只有最后两个参数：`/bin/sh -c <command>`。
     * 单独一个函数是为了让「装环境」的调用点读起来明确，而不是自己拼尾部。
     */
    fun execArgs(
        env: LinuxEnvironment,
        command: String,
        extraBinds: List<String> = emptyList(),
    ): List<String> {
        val login = loginArgs(env, extraBinds)
        // 去掉尾部的 `/bin/bash` `-l`，换成 `sh -c`。
        // 用 index 计算而不是 `filter`：`-l` 也可能出现在别处。
        val head = login.dropLast(2)
        return head + listOf("/bin/sh", "-c", command)
    }

    /**
     * 直接在容器里 exec 一个常驻程序（不加 `-c`，不套 shell）。
     *
     * ## 为什么要和 [execArgs] 分开
     *
     * `execArgs` 把命令拼成 `/bin/sh -c "<command>"`，适合「跑一条命令然后退出」。
     * 但终端会话要的是**一个长期驻留的交互 shell**：`/bin/sh -c "exec /bin/sh -i"`
     * 虽然能跑起来，`-i` 会去读 stdin 判断是否 tty，在 `-c` 的包裹下
     * 行为不稳定（有的实现直接不进入交互模式）。
     *
     * 这里直接给出 `proot ... /bin/sh -i`，由 ProcessBuilder 的
     * stdin/stdout 当作管道驱动 —— 这也是 Operit 用 `Pty.start(command)` 传入
     * 的就是纯 argv 数组（不经 shell 包一层）的原因。
     *
     * @param program 容器内要 exec 的程序与参数（如 `listOf("/bin/sh", "-i")`）
     */
    fun sessionArgs(
        env: LinuxEnvironment,
        program: List<String>,
        extraBinds: List<String> = emptyList(),
    ): List<String> {
        require(program.isNotEmpty()) { "program must not be empty" }
        val login = loginArgs(env, extraBinds)
        // 去掉尾部的 `/bin/bash` `-l`。
        return login.dropLast(2) + program
    }

    /** `-b host:container`，容器路径省略。 */
    private fun bind(host: String): List<String> = listOf("-b", host)

    /** `-b host:container`。 */
    private fun bind(host: String, container: String): List<String> =
        listOf("-b", "$host:$container")

    /**
     * 「切到当前发行版的软件源」的完整命令（共用文件系统下用）。
     *
     * ## 与 [switchMirrorScript] 的关系
     *
     * 复用同一份换源逻辑（它已按 [LinuxEnvironment.distro] 分流到
     * Ubuntu / Kali 两套），区别只在语义与收尾：
     *
     * | | `switchMirrorScript` | 本函数 |
     * |---|---|---|
     * | 触发 | 用户在设置页点「换镜像源」 | 用户在发行版选择器里切换 |
     * | 收尾 | 跑 `apt-get update`（要立刻验证源可用） | **只换源，不 update** |
     * | 原因 | 换源失败必须当场告诉用户 | 切发行版是高频操作，不该每次都等 apt update |
     *
     * ## 为什么要顺带清掉「另一个发行版的残留源文件」
     *
     * 共用文件系统意味着两个发行版写的是同一批文件。从 Kali 切回 Ubuntu 时，
     * Kali 留下的 `/etc/apt/sources.list.d/kali.sources` 如果不清，
     * `apt update` 会同时读 Ubuntu 与 Kali 两个源 —— 表现是
     * 「明明选了 Ubuntu，装出来的却是 kali-rolling 的包」，
     * 而且 apt 不会报错。这个残留必须在切换时主动清掉。
     *
     * ## 为什么末尾不 `apt-get update`
     *
     * 换源本身是本地文件写入，成功与否立刻可知。`apt update` 要联网、
     * 可能几十秒，放在这里会让「切个发行版」变成一个需要等的操作。
     * 由界面提供单独的「刷新软件源」按钮承接那一步。
     */
    fun sourceSwitchCommand(env: LinuxEnvironment): String {
        val script = switchMirrorScript(env)
        // switchMirrorScript 末尾固定是 `apt-get update`（可能带前导换行）。
        // 用行过滤而不是 `replace` 子串：注释里也可能出现这个词。
        val withoutUpdate = script.lineSequence()
            .filterNot { it.trim() == "apt-get update" }
            .joinToString("\n")
        val cleanup = buildString {
            appendLine("# 清掉另一个发行版残留的源文件（共用 rootfs 时必须）")
            if (env.distro == LinuxDistro.Kali) {
                // 切到 Kali：Ubuntu 的源文件要让位（kaliMirrorScript 自己会写 kali.sources）。
                appendLine("rm -f ${env.sourcesFile} ${env.legacySourcesFile}")
            } else {
                // 切回 Ubuntu：Kali 的两个源文件都要让位。
                // ⚠️ 必须同时删 `kali.sources`（deb822）与 `kali.list`（传统格式）——
                // 只删一个的话另一个仍在生效，apt 会同时读 Ubuntu 与 Kali 两个源，
                // 装出 kali-rolling 的包而**不报错**。
                appendLine("rm -f /etc/apt/sources.list.d/kali.sources /etc/apt/sources.list.d/kali.list")
            }
            // 记录「源现在指向谁」。界面靠它判断「应用 XX 源」按钮要不要出现。
            // 与 origin 标记分开：origin 是「底层从哪来」，本标记是「源指向谁」。
            appendLine("mkdir -p /opt")
            appendLine("echo ${env.distro.name.lowercase()} > /${LinuxEnvironment.SOURCES_MARKER_FILE_NAME}")
        }
        return cleanup + withoutUpdate
    }

    /**
     * 换源命令。
     *
     * ## 为什么两个文件都要写
     *
     * | 版本 | 实际生效的文件 |
     * |---|---|
     * | 24.04 (noble) | `/etc/apt/sources.list.d/ubuntu.sources`（**deb822 新格式**） |
     * | 22.04 (jammy) | `/etc/apt/sources.list`（传统单行格式） |
     *
     * 只写一个就会出现「换源后 apt 还在连官方」—— 因为另一个文件仍在生效。
     * 所以：**先注释掉旧文件里的内容，再写目标文件**，两个版本都覆盖。
     *
     * ## 为什么 arm64 必须用 ports
     *
     * 见 [UbuntuMirror] 的 KDoc。这里用 `ports` 而不是 `archive`。
     */
    fun switchMirrorScript(env: LinuxEnvironment): String {
        // Kali 与 Ubuntu 的源地址、suite、组件名全都不同，必须分流。
        // 走错分支的表现是 `apt-get update` 全部 404，而报错只有
        // `Failed to fetch`，看不出是「源写错」还是「网络不通」。
        if (env.distro == LinuxDistro.Kali) return kaliMirrorScript(env)
        val mirror = env.mirror
        // 备份文件固定为 `<sources>.praxis-bak`，规则：**只在不存在时创建**
        // （即只保留最原始那一版）。用显式 `[ -f ]` 判断，而不是靠 `cp -n`。
        //
        // ## 为什么不用 `cp -n`
        //
        // `-n` 的行为是「目标存在就跳过」，第一次换源时确实建了备份；
        // **第二次**换源（清华 → 阿里云）时备份已存在 → 跳过。
        // 结果看起来一样，但语义是「第一次的原始」这个副作用，而不是写明规则。
        // 显式判断让「备份=最原始那版」成为写出来的约定，后人不会误解。
        val backup = buildString {
            appendLine("if [ ! -f ${env.sourcesFile}.praxis-bak ]; then")
            appendLine("  cp ${env.sourcesFile} ${env.sourcesFile}.praxis-bak 2>/dev/null || true")
            appendLine("fi")
            appendLine("if [ ! -f ${env.legacySourcesFile}.praxis-bak ]; then")
            appendLine("  cp ${env.legacySourcesFile} ${env.legacySourcesFile}.praxis-bak 2>/dev/null || true")
            appendLine("fi")
        }

        if (!mirror.changesSources) {
            // 选了「官方」：把两个文件都改回官方（可能是从国内镜像切回来）。
            return buildString {
                appendLine("set -e")
                append("$backup")
                appendLine("# 恢复官方源")
                appendLine(writeDeb822(env, UbuntuMirror.Official))
                appendLine(writeLegacy(env, UbuntuMirror.Official))
                appendLine("apt-get update")
            }
        }

        return buildString {
            appendLine("set -e")
            append("$backup")
            appendLine("# 换源：${mirror.label}")
            appendLine(writeDeb822(env, mirror))
            appendLine(writeLegacy(env, mirror))
            appendLine("apt-get update")
        }
    }

    /**
     * Kali 换源脚本。
     *
     * ## ⚠️ 2026-10-22 实测修正：Kali rootfs 用的是 **deb822**，不是 sources.list
     *
     * 旧实现（错的）假设 Kali 只读 `/etc/apt/sources.list`，于是：
     *
     * ```
     * rm -f /etc/apt/sources.list.d/kali.sources      ← 删掉真正生效的那个
     * printf 'deb ...' > /etc/apt/sources.list        ← 写一个 Kali 根本不读的
     * ```
     *
     * 实测 NetHunter minimal 的 rootfs 里 **没有** `/etc/apt/sources.list`，
     * 只有 `/etc/apt/sources.list.d/kali.sources`，内容是：
     *
     * ```
     * Types: deb
     * URIs: http://http.kali.org/kali/
     * Suites: kali-rolling
     * Components: main contrib non-free non-free-firmware
     * Signed-By: /usr/share/keyrings/kali-archive-keyring.gpg
     * ```
     *
     * 于是旧实现的结果是：删掉唯一生效的源 → 写一个不会被读的文件 →
     * `apt-get update` **什么都拿不到** → 用户看到的是
     * 「apt update 没报错，但装什么都 Unable to locate package」。
     * 这就是「必须修复 kali」的真因。
     *
     * ## 为什么必须保留 `Signed-By`
     *
     * Kali 的包由 `kali-archive-keyring` 签。deb822 里不写 `Signed-By` 时
     * apt 会退回「信任所有 trusted.gpg.d 里的钥」，而 Kali 的 keyring 是
     * **单独一个文件**、不在默认位置 → 报
     * `The following signatures couldn't be verified`（NO_PUBKEY），
     * 然后 `apt update` 整体失败。这不是权限问题，是缺签名来源声明。
     *
     * ## 输出路径为什么是 `kali.sources`
     *
     * 现代 apt 按「目录里任意 `*.sources`」扫描，文件名无所谓。用 `kali.sources`
     * 是为了**覆盖官方那个文件**而不是新增一个 —— 新增会让两份源同时生效
     * （一份官方、一份镜像），`apt update` 会从官方再拉一遍，等于换源白做。
     *
     * ## 组件必须写全
     *
     * `main contrib non-free non-free-firmware`：大量 Kali 工具
     * （`metasploit-framework` 的部分依赖、无线固件）在 `non-free` 里。
     * 少写这一项，用户装工具时会得到「包不存在」，而 `apt search` 也搜不到。
     *
     * ## DNS 必须在换源之前
     *
     * `apt-get update` 要解析镜像域名。没有 `/etc/resolv.conf` 时
     * 报 `Temporary failure resolving`，与「源地址写错」的表象完全不同，
     * 所以这一步由调用方保证顺序（见 `LinuxEnvViewModel.install`）。
     */
    fun kaliMirrorScript(env: LinuxEnvironment): String = buildString {
        val m = env.kaliMirror
        appendLine("set -e")
        appendLine("mkdir -p /etc/apt/sources.list.d")
        // 传统格式的残留（有的镜像/旧操作会留下）要清掉：
        // apt 同时读 sources.list 与 sources.list.d 目录下所有文件，会双份拉取。
        appendLine("rm -f /etc/apt/sources.list")
        // 其它可能存在的 deb822 源也清掉，只留我们写的那一个。
        appendLine("rm -f /etc/apt/sources.list.d/kali.list")
        appendLine("rm -f /etc/apt/sources.list.d/ubuntu.sources")
        // 写 deb822。`cat > file <<'EOF'` 比 printf 更可读，
        // 且不怕内容里有单引号（Kali 的 URL 里没有，但防御性写法更稳）。
        appendLine("cat > /etc/apt/sources.list.d/kali.sources <<'KALI_SOURCES_EOF'")
        appendLine("Types: deb")
        appendLine("URIs: ${m.base}/")
        appendLine("Suites: ${m.suite}")
        appendLine("Components: ${m.components}")
        appendLine("Signed-By: /usr/share/keyrings/kali-archive-keyring.gpg")
        appendLine("KALI_SOURCES_EOF")
        // ── 自检 + update 容错 ────────────────────────────────
        //
        // 关键问题：**换源是本地写文件，成功与否与 `apt-get update` 无关**。
        // 但 `apt-get update` 在以下情形会返回非 0：
        //
        // | 情形 | 退出码 | 源文件是否已写好 |
        // |---|---|---|
        // | keyring 缺失/不匹配（NO_PUBKEY） | 100 | **已写好** |
        // | DNS 不通 | 100 | **已写好** |
        // | 镜像 404（源地址写错） | 100 | 已写好，但地址确实错 |
        //
        // 旧写法把 `apt-get update` 直接放在 `set -e` 下 —— 前两种情形会让
        // 脚本**在文件已写好之后**中断，调用方看到「失败」，用户重试，
        // 于是无限循环换源而每次都停在同一处。
        //
        // 这里改成：先断言源文件确实写成功了（这是「换源」本身），
        // 再单独跑 update 并用 `|| { ... }` 捕获失败码，**把 update 的真实结果
        // 作为可读诊断打印出来**，让用户看到「源已切好，只是还连不上」。
        // 用 `|| { ... }` 而不是 `|| true`：`true` 会连失败码一起丢掉，
        // 诊断里就只剩一句无信息量的「失败了」。
        appendLine("if [ ! -s /etc/apt/sources.list.d/kali.sources ]; then")
        appendLine("  echo 'PRAXIS_ERROR: 未能写入 kali.sources' >&2")
        appendLine("  exit 1")
        appendLine("fi")
        appendLine("echo 'PRAXIS_SOURCES_OK: kali'")
        appendLine("apt-get update || {")
        appendLine("  rc=$?")
        appendLine("  echo \"PRAXIS_WARN: apt-get update 失败（退出码 \$rc），但软件源已切换完成\" >&2")
        appendLine("  echo 'PRAXIS_WARN: 常见原因：网络不通 / 镜像暂时不可达；源配置本身已写入' >&2")
        appendLine("}")
    }

    /**
     * 写容器内的 DNS 配置。
     *
     * ## 为什么不 bind 系统的 `/etc/resolv.conf`
     *
     * Android 的 DNS 由 `netd` 管理，`/etc/resolv.conf` 要么不存在、要么是空的。
     * 直接把空文件挂进容器 = 容器里 `apt` 解析不了域名，报
     * `Temporary failure resolving 'mirrors.tuna.tsinghua.edu.cn'`，
     * 而这个报错完全不提 DNS 配置，很难联想到。
     *
     * 所以改为**写一份固定的公共 DNS**。用 223.5.5.5（阿里）与 119.29.29.29（腾讯）
     * 而不是 8.8.8.8：国内网络下 8.8.8.8 经常被污染或不可达。
     */
    fun dnsScript(): String = buildString {
        appendLine("mkdir -p /etc")
        appendLine(
            "printf '%s\\n' 'nameserver 223.5.5.5' 'nameserver 119.29.29.29' " +
                "> /etc/resolv.conf",
        )
    }

    /**
     * 写 deb822 格式的 `ubuntu.sources`（24.04 起生效）。
     *
     * 格式（Ubuntu 官方推荐）：
     * ```
     * Types: deb
     * URIs: https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports
     * Suites: noble noble-updates noble-backports noble-security
     * Components: main restricted universe multiverse
     * Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
     * ```
     */
    private fun writeDeb822(env: LinuxEnvironment, mirror: UbuntuMirror): String {
        val suite = env.release.suite
        return buildString {
            append("printf '%s\\n' ")
            append("'Types: deb' ")
            append("'URIs: ${mirror.ports}' ")
            append("'Suites: $suite $suite-updates $suite-backports $suite-security' ")
            append("'Components: main restricted universe multiverse' ")
            append("'Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg' ")
            append("> ${env.sourcesFile}")
        }
    }

    /**
     * 写传统 `sources.list`（22.04 生效；24.04 上也留着无害，因为 deb822 文件优先）。
     */
    private fun writeLegacy(env: LinuxEnvironment, mirror: UbuntuMirror): String {
        val suite = env.release.suite
        return buildString {
            append("printf '%s\\n' ")
            append("'deb ${mirror.ports} $suite main restricted universe multiverse' ")
            append("'deb ${mirror.ports} $suite-updates main restricted universe multiverse' ")
            append("'deb ${mirror.ports} $suite-backports main restricted universe multiverse' ")
            append("'deb ${mirror.ports} $suite-security main restricted universe multiverse' ")
            append("> ${env.legacySourcesFile}")
        }
    }
}
