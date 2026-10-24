package dev.praxis.core.model.linux

/**
 * 一组可一键安装的软件包（对齐 Operit `SetupScreen` 的包分类卡片）。
 *
 * ## 为什么是「分类」而不是「一个大列表」
 *
 * 用户在容器里装东西的真实意图总是「我要 Python」或「我要 Node」，
 * 而不是「我要装 47 个包」。按用途分类之后：
 *  - 每类只有一个勾选框，交互成本是 1 次点击而不是 47 次；
 *  - 类名本身就是说明（`Python 开发` 比 `python3 python3-pip python3-venv` 好懂）；
 *  - `apt-get install` 一次跑完，比逐包安装少几十次 `dpkg` 锁竞争。
 *
 * ## 为什么包名必须写成 Ubuntu / Kali 通用的那部分
 *
 * 两个发行版**共用同一份 rootfs**（见 `LinuxEnvironment` 的 KDoc），
 * 而且用户随时可以换源。所以这里只能放「两套源里都存在、包名相同」的包。
 * 放 Kali 独有的包（如 `nmap` 在 Ubuntu 主源里没有）会让切换发行版后
 * 一键安装直接失败 —— 表现是「昨天还能装，今天报 Unable to locate package」。
 *
 * 这条约束牺牲了一点覆盖面（Kali 用户可能想要的那些安全工具不在这里），
 * 但换来的是**分类卡在任何发行版下都工作**。专有工具留给用户自己在终端里
 * `apt install`，那是他们本来就会做的事。
 *
 * @param id 稳定标识，用于持久化勾选状态（不能改，改了老用户的勾选会丢）。
 * @param title 显示名。
 * @param summary 一句话说明这组装完之后能做什么。
 * @param packages 要传给 `apt-get install` 的包名。
 */
data class LinuxPackageGroup(
    val id: String,
    val title: String,
    val summary: String,
    val packages: List<String>,
) {
    /** 拼成一行 `apt-get install` 参数。空组返回空串（调用方应跳过）。 */
    val installArgs: String get() = packages.joinToString(" ")
}

/**
 * 内置的六组软件包（分类照 Operit `SetupScreen`，包名按 Ubuntu/Kali 通用性筛选）。
 *
 * ## 六组的选择依据
 *
 * Operit 的六类是 Node.js / Python / SSH / Java / Rust / Go。这里保留同样的六类，
 * 因为它们是「容器里最常被临时需要」的六件事，而且都有共同的特性：
 * **装完立刻可用，不需要编译**。
 *
 * ## 三处与 Operit 不同的处理
 *
 * 1. **SSH 组不在这里**。Praxis 的 SSH 是「在容器里起 sshd 供外部连入」，
 *    它有自己的配置（端口、用户、密码）且要和 `SshConfig` 保持一致。
 *    塞进「一键装包」会让用户装完 sshd 却不知道端口是哪个、
 *    也拿不到 Praxis 生成的配置文件。它在环境页有专门的开关（`SshdScript`）。
 * 2. **Rust 用 `rustc` + `cargo` 而不是 `rustup`**。`rustup` 要联网下载
 *    一个几百 MB 的 toolchain 到用户家目录，在 proot 里偶发卡死；
 *    Debian 源的 `rustc`/`cargo` 是纯 apt 安装，装完就能 `cargo new`。
 *    版本旧一点，但「一键安装必须能装上」优先于「装最新版」。
 * 3. **Go 用 `golang-go`**。这个名字在 Ubuntu 与 Kali 里都有效；
 *    `golang` 只在部分源里是虚包。
 *
 * ## 为什么每组都不带 `--no-install-recommends`
 *
 * `apt-get install -y --no-install-recommends python3` 装出来的 python3
 * **没有 pip、没有 venv**（它们在 recommends 里）。而用户要 Python 就是
 * 为了 pip 装东西。省那几十 MB 换来「装完还得再装 pip」是净损失。
 * 所以这批安装**保留 recommends**（与 `SshdScript` 里的
 * `--no-install-recommends` 相反 —— 那里是 sshd，少装东西更安全）。
 */
val BuiltInPackageGroups: List<LinuxPackageGroup> = listOf(
    LinuxPackageGroup(
        id = "python",
        title = "Python 开发",
        summary = "python3 + pip + venv + 常用构建依赖",
        packages = listOf("python3", "python3-pip", "python3-venv", "python3-dev", "build-essential"),
    ),
    LinuxPackageGroup(
        id = "node",
        title = "Node.js 开发",
        summary = "nodejs + npm（Debian 源版本，不带 nvm）",
        packages = listOf("nodejs", "npm"),
    ),
    LinuxPackageGroup(
        id = "java",
        title = "Java 运行时",
        summary = "OpenJDK 17（headless，不含图形界面依赖）",
        packages = listOf("openjdk-17-jdk-headless"),
    ),
    LinuxPackageGroup(
        id = "rust",
        title = "Rust 工具链",
        summary = "rustc + cargo（Debian 源版本，非 rustup）",
        packages = listOf("rustc", "cargo"),
    ),
    LinuxPackageGroup(
        id = "go",
        title = "Go 工具链",
        summary = "golang-go + git（拉取模块要用 git）",
        packages = listOf("golang-go", "git"),
    ),
    LinuxPackageGroup(
        id = "tools",
        title = "常用命令行工具",
        summary = "curl / wget / unzip / vim / nano / tree / htop",
        packages = listOf(
            "curl", "wget", "unzip", "zip", "vim", "nano", "tree", "htop", "procps", "less",
        ),
    ),
)

/**
 * 生成「一键安装选中包组」的容器内脚本。
 *
 * ## 为什么末尾要 `apt-get clean`
 *
 * `apt` 会把下载的 `.deb` 留在 `/var/cache/apt/archives`。装 Java + Rust
 * 之后那里能积几百 MB —— 而这正是设置页「存储管理」里显示的数字。
 * 用户装完去看「环境占用 1.2 GB」会以为装错了，其实是缓存。
 *
 * `clean` 是安全的（下次安装会重新下载），所以在这里做掉，
 * 而不是让用户自己想起来去清。
 *
 * ## 为什么 `set -e` 之后还要 `|| true` 兜 apt update
 *
 * 与 `ProotCommand.switchMirrorScript` 里那段同一个理由：
 * `apt-get update` 在「某个第三方源暂时 404」「DNS 刚写还没生效」时会返回非 0，
 * 但**包索引其实已经拿到大部分**。直接 `set -e` 会让整次安装失败，
 * 而用户看到的是「什么都没装」。
 *
 * 这里的处理是：update 失败只告警、继续往下装。若索引真的完全没拿到，
 * 下面的 `apt-get install` 自己会失败并给出明确原因
 * （`Unable to locate package`），比提前中断更容易诊断。
 *
 * ## 空选返回什么
 *
 * 返回空串。调用方必须先判空 —— 传空串给 `sh -c` 会跑一个空脚本
 * （退出码 0、什么都没发生），用户会以为点了没反应。
 *
 * @param groupIds 选中的包组 id。未知 id 被忽略（老版本存的 id 可能已删除）。
 */
fun buildPackageInstallScript(groupIds: Set<String>): String {
    val groups = BuiltInPackageGroups.filter { it.id in groupIds }
    if (groups.isEmpty()) return ""

    val packages = groups.flatMap { it.packages }.distinct()
    if (packages.isEmpty()) return ""

    return buildString {
        appendLine("set -e")
        // 非交互：debconf 弹菜单在无 TTY 的 proot 里会永久挂住。
        appendLine("export DEBIAN_FRONTEND=noninteractive")
        // update 失败不中断（见 KDoc）。
        appendLine("apt-get update || echo \"PRAXIS_WARN: apt-get update 失败，继续尝试安装\" >&2")
        appendLine("apt-get install -y " + packages.joinToString(" "))
        // 清缓存：它直接计入用户看到的「环境占用」。
        appendLine("apt-get clean")
        // 回显装了什么，让用户/日志能确认这次真的动了包。
        appendLine("echo \"PRAXIS_OK: 已安装 " + groups.joinToString("、") { it.title } + "\"")
    }
}
