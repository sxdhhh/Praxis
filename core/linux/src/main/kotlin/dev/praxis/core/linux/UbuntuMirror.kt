package dev.praxis.core.linux

/**
 * 国内镜像源。
 *
 * ## 为什么必须换源
 *
 * Ubuntu 官方源 `archive.ubuntu.com` 在国内的 `apt update` 常常几十 KB/s 甚至超时。
 * 用户的第一次体验就会是「装环境卡住」，而失败信息是 `E: Failed to fetch`，
 * 看不出是网速问题。所以**建完容器第一件事就是换源**，不该让用户自己想。
 *
 * ## 为什么把 URL 写死在枚举里而不是配置文件
 *
 * 与 `ApiProvider` 同一个理由：这些地址是**公开且稳定**的事实，
 * 放在代码里就能被单测逐个钉（`MirrorTest` 检查 scheme/尾斜杠/可拼接性），
 * 放进 assets 反而会漂移。
 *
 * ## `ubuntuPorts` 为什么要单列
 *
 * Ubuntu 的归档分成两个池：
 *  - `archive.ubuntu.com/ubuntu`  —— amd64 等；
 *  - `ports.ubuntu.com/ubuntu-ports` —— **arm64/armhf/ppc64el/riscv64** 等。
 *
 * 手机全是 arm64，所以**必须用 ports**。用官方 archive 会得到
 * `N: Skipping acquire of configured file 'main/binary-arm64/Packages'`（找不到包），
 * 这是换源类问题里最容易踩、又最难从报错里看出来的一个。
 *
 * 镜像站都把 ports 放在独立路径下，因此每一项都要给出**两个**地址。
 */
enum class UbuntuMirror(
    /** 显示名。 */
    val label: String,
    /** 主池（amd64 等）。 */
    val archive: String,
    /** ports 池（arm64 —— 手机用这个）。 */
    val ports: String,
) {
    /**
     * 清华 TUNA。
     *
     * 已实测：`ubuntu-cdimage/.../ubuntu-base-24.04.3-base-arm64.tar.gz` 返回 200（29.8 MB）。
     */
    Tsinghua(
        label = "清华 TUNA",
        archive = "https://mirrors.tuna.tsinghua.edu.cn/ubuntu",
        ports = "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports",
    ),

    /** 阿里云。 */
    Aliyun(
        label = "阿里云",
        archive = "https://mirrors.aliyun.com/ubuntu",
        ports = "https://mirrors.aliyun.com/ubuntu-ports",
    ),

    /** 中科大 USTC。 */
    Ustc(
        label = "中科大 USTC",
        archive = "https://mirrors.ustc.edu.cn/ubuntu",
        ports = "https://mirrors.ustc.edu.cn/ubuntu-ports",
    ),

    /** 华为云。 */
    Huawei(
        label = "华为云",
        archive = "https://repo.huaweicloud.com/ubuntu",
        ports = "https://repo.huaweicloud.com/ubuntu-ports",
    ),

    /** 网易。 */
    Netease(
        label = "网易",
        archive = "https://mirrors.163.com/ubuntu",
        ports = "https://mirrors.163.com/ubuntu-ports",
    ),

    /**
     * 不换源（官方）。
     *
     * 留着是因为：用户可能本来就在境外，或者公司内网有代理。
     * 不提供这个选项等于逼所有人换源。
     */
    Official(
        label = "官方（不换源）",
        archive = "http://archive.ubuntu.com/ubuntu",
        ports = "http://ports.ubuntu.com/ubuntu-ports",
    ),
    ;

    /** 是否真的会改动 sources.list（Official 不改）。 */
    val changesSources: Boolean get() = this != Official

    companion object {
        /**
         * 按名还原，找不到回落到 [Tsinghua]。
         *
         * 回落目标选 Tsinghua 而不是 Official：Praxis 的用户绝大多数在国内，
         * 默认不换源的失败率远高于换错一个镜像站（后者仍能连通）。
         * 与 `ApiProvider.fromNameOrDefault` 用同一套「不抛异常」的写法。
         */
        fun fromNameOrDefault(name: String?): UbuntuMirror =
            entries.firstOrNull { it.name == name } ?: Tsinghua
    }
}

/**
 * Ubuntu 版本。
 *
 * 只放 **LTS** 且是 `ubuntu-base` 有产物的版本。
 * 非 LTS 的 `ubuntu-base` 只在 `daily/` 下，随时会变，不能作为默认。
 */
enum class UbuntuRelease(
    val label: String,
    /** 版本号（用于 URL 与 `ubuntu-base` 文件名）。 */
    val version: String,
    /** `/etc/apt/sources.list.d/ubuntu.sources` 里的 `Suites` 名。 */
    val suite: String,
) {
    Noble(label = "24.04 LTS（Noble）", version = "24.04", suite = "noble"),
    Jammy(label = "22.04 LTS（Jammy）", version = "22.04", suite = "jammy"),
    ;

    companion object {
        fun fromNameOrDefault(name: String?): UbuntuRelease =
            entries.firstOrNull { it.name == name } ?: Noble
    }
}

/**
 * `ubuntu-base` tarball 的下载地址构造。
 *
 * ## 为什么要单独一个对象 + 单测
 *
 * 这个 URL 拼错一个字符就是 404，而失败发生在「装环境」的第 0 步，
 * 用户看到一个 HTTP 404 完全不知道下一步该干什么。
 *
 * ## 已实测的事实（2026-10-21）
 *
 * ```
 * https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz
 * → HTTP 200, content-length: 29865086
 * ```
 *
 * 注意三点，任一处想错都是 404：
 *  1. 路径是 **`ubuntu-cdimage/ubuntu-base`**，不是 `ubuntu/` 或 `ubuntu-base/`；
 *  2. 中间还有 `releases/<version>/release/`（两个 release，一个复数一个单数）；
 *  3. 文件名里的补丁号（`24.04.3`）**不等于** `version`（`24.04`）。
 *
 * 第 3 点决定了：**不能凭 version 猜文件名**。所以这里采用「候选列表 + 逐个探测」
 * 的策略，而不是拼一个「应该对」的 URL。
 */
object UbuntuBaseImage {

    /**
     * 各版本已知可用的补丁号，**按新到旧**排列。
     *
     * 第一项是当前主线；后面的用于上游删掉旧补丁包时兜底。
     * 上游发布新补丁时第一个会 404，此时 [candidateUrls] 还有后面的可用。
     */
    private val patchLevels: Map<UbuntuRelease, List<String>> = mapOf(
        UbuntuRelease.Noble to listOf("24.04.3", "24.04.2", "24.04.1"),
        UbuntuRelease.Jammy to listOf("22.04.5", "22.04.4", "22.04.3"),
    )

    /** `ubuntu-base` 只提供这几种架构；手机是 arm64。 */
    const val ARCH_ARM64: String = "arm64"

    /**
     * 生成候选下载地址（按优先级）。
     *
     * 调用方逐个 `HEAD` 探测，第一个 200 的就是要下的。
     * 只探 HEAD 不探 GET：29 MB 的文件，探错一次就白下。
     */
    fun candidateUrls(
        mirror: UbuntuMirror,
        release: UbuntuRelease,
        arch: String = ARCH_ARM64,
    ): List<String> {
        // ⚠️ cdimage 子站只在**部分**镜像站同步（清华有，实测 200）。
        // 所以这里对每个镜像站都生成候选，探测 404 就换下一个 —— 不硬编码
        // 「哪家有、哪家没有」，那种表会过期。
        //
        // 拼接方式：`archive` 是 `https://host/ubuntu`，把 `ubuntu` 换成
        // `ubuntu-cdimage` 就得到 cdimage 根。
        val cdimageRoot = mirror.archive.substringBeforeLast('/') + "/ubuntu-cdimage"
        val patches = patchLevels[release].orEmpty()
        return patches.map { patch ->
            "$cdimageRoot/ubuntu-base/releases/${release.version}/release/" +
                "ubuntu-base-$patch-base-$arch.tar.gz"
        }
    }

    /**
     * 官方上游地址（所有镜像都挂时的最后兜底）。
     *
     * 走 `cdimage.ubuntu.com` 而不是 `releases.ubuntu.com`：
     * 后者只放桌面/服务器 ISO，没有 `ubuntu-base`。
     */
    fun officialUrl(release: UbuntuRelease, arch: String = ARCH_ARM64): String {
        val patch = patchLevels[release]?.firstOrNull() ?: release.version
        return "https://cdimage.ubuntu.com/ubuntu-base/releases/${release.version}/release/" +
            "ubuntu-base-$patch-base-$arch.tar.gz"
    }
}