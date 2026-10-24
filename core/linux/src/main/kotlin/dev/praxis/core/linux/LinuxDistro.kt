package dev.praxis.core.linux

/**
 * 可内置的发行版（容器根文件系统）。
 *
 * ## 为什么是 Kali 而不是继续用 Ubuntu
 *
 * Ubuntu 解决的是「我想有个 Linux 环境跑 apt」；Kali 解决的是「我想做安全相关的
 * 工具链」。两者在 proot 里的运行方式**完全一样**（都是 rootfs + proot），
 * 所以这里把它们统一成 [LinuxDistro] 的一个枚举项，共用同一套
 * [ProotCommand] / SSH / FRP 实现，不需要写第二套。
 *
 * user 明确要求「内置一个 kali linux，然后让 AI 可以调用」。这条需求拆成两件事：
 *  1. **内置** = 发行版清单与下载地址写进代码（可单测、不被网络漂移影响），
 *     而不是让用户自己去翻官网；
 *  2. **AI 可调用** = 容器里的命令执行能力要能通过工具层暴露出去
 *     （见 `:app` 的 `LinuxToolBridge`），而不是只能人手动敲。
 *
 * ## 为什么内置的是「下载地址」而不是「rootfs 本体」
 *
 * Kali minimal 的 rootfs 是 **131 MB**（实测
 * `kali-nethunter-rootfs-minimal-arm64.tar.xz` → HTTP 200，content-length 137313840）。
 * 塞进 APK 会让安装包从几十 MB 涨到近 300 MB，且每次更新 rootfs 都要重新发版。
 *
 * 采用「首次使用时下载」：APK 体积不变，rootfs 可独立升级。
 * 代价是首次需要联网 —— 这一点必须在界面上明确告知，不能装作是即时的。
 */
enum class LinuxDistro(
    /** 显示名。 */
    val label: String,
    /** 一句话说明，显示在选择器里。 */
    val summary: String,
    /**
     * rootfs 下载候选地址（按优先级）。
     *
     * 用列表而不是单个 URL：上游换文件名、镜像暂时不可达时能自动降级，
     * 而不是让用户卡在一个 404 上。
     *
     * ⚠️ 2026-10-22 起**内置优先**：APK 里带了 tarball 时根本不会走这里
     * （见 [builtInAsset]）。这两个列表只在「内置资产缺失/损坏」时兜底。
     */
    val rootfsUrls: List<String>,
    /** 解包后的体积估算（字节），用于界面提示与磁盘空间预检。 */
    val approxUnpackedBytes: Long,
    /**
     * 发行版的包管理族。
     *
     * 决定换源写哪个文件、`apt` 还是 `apk`。Kali 是 Debian 系（apt），
     * 但它的源地址与 Ubuntu **完全不同**，不能复用 [UbuntuMirror]。
     */
    val family: DistroFamily,
    /**
     * 打进 APK assets 的**候选**文件名（按优先级）。
     *
     * ## 为什么是列表而不是单个名字（2026-10-23 修，「Ubuntu 装不上」的根因）
     *
     * 旧实现是单个 `builtInAsset: String`，值与打包脚本的产物一度**不一致**：
     *
     * | | 实际打进 APK 的 | 代码里找的 |
     * |---|---|---|
     * | 文件名 | `rootfs/ubuntu-noble-arm64.tar` | `rootfs/ubuntu-noble-arm64.tar.gz` |
     * | 形态 | 裸 tar，106 MB | 以为 29.8 MB 的 gzip |
     *
     * 后果是必然的：`assets.open("...tar.gz")` 抛 `FileNotFoundException`
     * → [ProotEnvPort.copyBuiltInAsset] 返回 false → 回落到网络下载
     * → 而下载在很多网络下确实失败 → 用户看到「Ubuntu 安装失败」。
     *
     * 更糟的是「就算命中也会失败」：`builtInIsXz = false` 会走
     * `toybox tar -xzof`，而那个 `-z` 对裸 tar 直接报
     * `zcat: not gzip / tar: had errors`（真机实测退出码 1）。
     *
     * 所以现在两层都改成**默认宽容**：
     *  1. 文件名按候选列表逐个探测，命中哪个用哪个；
     *  2. 压缩格式**不看文件名、不看配置**，直接读文件头魔数判断
     *     （见 `ProotEnvPort.detectCompression`）。
     *
     * 这样 CI 无论塞进来 `.tar.gz` / `.tar` / `.tar.xz` 都能装，
     * 不需要「构建产物与常量手工保持同步」这种脆弱约定。
     *
     * 顺序：压缩版在前（体积小、CI 优先产出），裸 tar 在后（兜底）。
     */
    val builtInAssets: List<String>,

) {
    Ubuntu(
        label = "Ubuntu 24.04 LTS",
        summary = "通用 Linux 环境，体积小、apt 生态完整",
        rootfsUrls = listOf(
            // 已实测可达（2026-10-21）：HTTP 200, content-length 29865086。
            "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz",
            "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz",
        ),
        approxUnpackedBytes = 120L * 1024 * 1024,
        family = DistroFamily.Debian,
        builtInAssets = listOf(
            // 压缩版优先（CI 产出的小体积版本）。
            "rootfs/ubuntu-noble-arm64.tar.gz",
            // 裸 tar 兜底：实测某些构建流程会把它 gunzip 掉再改名（2026-10-23
            // 真机 APK 里就是 106 MB 的 `ubuntu-noble-arm64.tar`）。
            "rootfs/ubuntu-noble-arm64.tar",
        ),
    ),

    /**
     * Kali Linux（NetHunter minimal）。
     *
     * ## 为什么选 NetHunter 的 rootfs
     *
     * 官方 `cdimage.kali.org` 的 arm64 镜像是**安装器**（installer），
     * 不是可直接解包的 rootfs —— 它需要走安装流程（分区、grub），
     * 在 proot 里根本跑不通。
     *
     * NetHunter 的 rootfs 是给「在 Android 上跑」准备的，本身就是
     * 一个可直接 `tar -x` 的根文件系统，与 proot 的需求严格对齐。
     * 这是它能「内置」而不是「装不了」的关键区别。
     *
     * ## 三种体积怎么选
     *
     * | 变体 | 压缩包 | 装完 | 取舍 |
     * |---|---|---|---|
     * | nano | 198 MB | ~600 MB | 工具最少，很多 kali 工具要现装 |
     * | minimal | 131 MB | ~500 MB | **选它**：核心工具齐、体积最小 |
     * | full | ~1.2 GB | ~5 GB | 全量工具，手机存储吃得太多 |
     *
     * 实测可达：`kali-nethunter-rootfs-minimal-arm64.tar.xz` → HTTP 200，
     * content-length 137313840（131 MB）。
     */
    Kali(
        label = "Kali Linux（NetHunter minimal）",
        summary = "安全工具链，内置 nmap / metasploit 等常用工具",
        rootfsUrls = listOf(
            "https://kali.download/nethunter-images/current/rootfs/kali-nethunter-rootfs-minimal-arm64.tar.xz",
            "https://mirrors.tuna.tsinghua.edu.cn/kali/nethunter-images/current/rootfs/kali-nethunter-rootfs-minimal-arm64.tar.xz",
        ),
        approxUnpackedBytes = 500L * 1024 * 1024,
        family = DistroFamily.Debian,
        builtInAssets = listOf(
            "rootfs/kali-nethunter-minimal-arm64.tar.xz",
            "rootfs/kali-nethunter-minimal-arm64.tar",
        ),
    ),
    ;


    companion object {
        fun fromNameOrDefault(name: String?): LinuxDistro =
            entries.firstOrNull { it.name == name } ?: Ubuntu
    }
}

/** 发行版的包管理族。目前只有 Debian 系，保留枚举是为了不把 `apt` 写死。 */
enum class DistroFamily(val packageManager: String) {
    Debian("apt"),
}

/**
 * Kali / Debian 系镜像源。
 *
 * ## 为什么不能复用 [UbuntuMirror]
 *
 * Ubuntu 的源路径是 `ubuntu` / `ubuntu-ports`，Kali 是 `kali` / `kali-raw`
 * 之类完全不同的结构；套用会得到 `404 Not Found`，而 `apt update` 的报错
 * 只会说 `Failed to fetch`，看不出是「源地址写错」。
 *
 * ## arm64 的坑（与 Ubuntu 同一个）
 *
 * Kali 的 arm64 包在 `kali` 池里是完整的（不像 Ubuntu 需要单独 ports），
 * 所以只有一个地址。但组件名必须写全：`main contrib non-free non-free-firmware`，
 * 少写 `non-free` 会导致大量安全工具「包不存在」。
 */
enum class KaliMirror(
    val label: String,
    val base: String,
) {
    /** 清华 TUNA（国内最快）。 */
    Tsinghua(label = "清华 TUNA", base = "https://mirrors.tuna.tsinghua.edu.cn/kali"),

    /** 中科大 USTC。 */
    Ustc(label = "中科大 USTC", base = "https://mirrors.ustc.edu.cn/kali"),

    /** 阿里云。 */
    Aliyun(label = "阿里云", base = "https://mirrors.aliyun.com/kali"),

    /** Kali 官方（海外，兜底）。 */
    Official(label = "Kali 官方", base = "http://http.kali.org/kali"),
    ;

    /** Kali 的 suite 只有这两个：`kali-rolling` 是主滚动版。 */
    val suite: String get() = "kali-rolling"

    /** 组件必须写全，尤其 `non-free`（大量安全工具在那里）。 */
    val components: String get() = "main contrib non-free non-free-firmware"
}