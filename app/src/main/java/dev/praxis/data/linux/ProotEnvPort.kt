package dev.praxis.data.linux

import android.content.Context
import dev.praxis.core.linux.KaliMirror
import dev.praxis.core.linux.LinuxDiskUsage
import dev.praxis.core.linux.LinuxDistro
import dev.praxis.core.linux.LinuxEnvironment
import dev.praxis.core.linux.ProotCommand
import dev.praxis.core.linux.UbuntuBaseImage
import dev.praxis.core.linux.UbuntuMirror
import dev.praxis.core.linux.UbuntuRelease
import dev.praxis.feature.settings.env.LinuxEnvPort
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `LinuxEnvPort` 的 Android 实现。
 *
 * ## 为什么 proot 打包成 `libproot.so`
 *
 * Android 10+ 起禁止从应用私有目录执行可执行文件（W^X）。唯一**稳定**的可执行位置
 * 是 `nativeLibraryDir` —— 实测系统以 `-rwxr-xr-x` 挂载（本项目设备已确认）。
 *
 * 而 Android 的打包器只会把 `lib*.so` 放进 `nativeLibraryDir`。所以标准做法是：
 * 把 proot 二进制改名成 `libproot.so` 放进 `jniLibs/arm64-v8a/`。
 * 系统不会去「加载」它，只是把它解压到那儿并给可执行权限。
 *
 * 本项目的 `praxis.android.application` 插件已设 `useLegacyPackaging = true`，
 * 保证 native 库落地为**真实文件**（而不是 APK 内未压缩条目），这是可执行的前提。
 *
 * ## 为什么 rootfs 解压用 toybox 的 tar
 *
 * `ubuntu-base` 是 tar.gz，里面含符号链接、硬链接、权限位。
 * Java 的 `ZipInputStream` 解不了（格式不同）；自己写 tar 解析又要处理长文件名、
 * 稀疏文件等边界。Android 自带 `/system/bin/toybox tar` 支持 `-z`（gzip）与
 * `-o`（忽略 owner），是正确且最省事的路径（已在设备上确认支持这两个选项）。
 *
 * ## 下载为什么用 HttpURLConnection
 *
 * 「一次性把 30MB 拷到文件」不需要 OkHttp 的连接池与拦截器。
 * 少一层依赖，也避免 proot 这条链路被 OkHttp 的版本/超时策略牵制。
 */
class ProotEnvPort(private val context: Context) : LinuxEnvPort {

    /**
     * 共享 rootfs 目录。
     *
     * ## 为什么不带发行版名（2026-10-22 改）
     *
     * 旧实现是 `filesDir/linux/<distro>`，即 Ubuntu 与 Kali **各一份**。
     * 当时的理由是「两个都想试时不互相覆盖，且 `rm -rf` 一个 500 MB
     * rootfs 要几十秒，中途取消会得到半残目录」。
     *
     * 但用户明确要求「kali 和 ubuntu 都下下来直接放随便选，然后共用
     * 一个文件系统」，所以改成两者指向同一个目录 `filesDir/linux/shared`。
     *
     * 半残目录的风险改由 [install] 的「先解到临时目录再原子改名」承担
     * （见那里的说明），而不是靠「分目录」来回避。
     *
     * @param distro 保留参数但不参与路径 —— 签名不改是为了让调用点
     *   继续读成「给我这个发行版的环境」，而内部已共享。
     */
    @Suppress("UNUSED_PARAMETER")
    private fun rootfsDirOf(distro: LinuxDistro): File =
        File(
            context.filesDir,
            "linux/" + LinuxEnvironment.SHARED_ROOTFS_DIR_NAME,
        )

    /** 下载缓存目录。 */
    private val cacheDir = File(context.cacheDir, "linux").apply { mkdirs() }

    /** 活跃会话（stop 时统一清理）。 */
    private val sessions = mutableListOf<ProotSession>()

    /**
     * proot 可执行文件。
     *
     * 先找 `libproot.so`（随 APK 打包的正常路径）；找不到时回落到几个常见变体，
     * 都不在也返回标准路径 —— 供 UI 显示「缺少 proot」而不是抛异常。
     */
    private val prootBinary: File by lazy {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        listOf("libproot.so", "libproot-arm64.so", "libproot")
            .map { File(nativeDir, it) }
            .firstOrNull { it.isFile }
            ?: File(nativeDir, "libproot.so")
    }

    /**
     * 构造容器环境描述。
     *
     * @param distro 选哪个发行版。默认 Ubuntu（体积小、首装快）；
     *   用户要 Kali 时由界面传入。
     */
    override fun defaultEnvironment(distro: LinuxDistro): LinuxEnvironment = LinuxEnvironment(
        rootfsDir = rootfsDirOf(distro),
        prootBinary = prootBinary,
        distro = distro,
        release = UbuntuRelease.Noble,
        mirror = UbuntuMirror.Tsinghua,
        kaliMirror = KaliMirror.Tsinghua,
    )

    override fun isProotAvailable(): Boolean = prootBinary.isFile

    override fun isInstalled(env: LinuxEnvironment): Boolean = env.isInstalled

    // ── 安装 ────────────────────────────────────────────────────────

    override suspend fun install(
        env: LinuxEnvironment,
        onLine: (String) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val sizeHint = env.distro.approxUnpackedBytes / 1024 / 1024
            // ① 取 tarball：**内置优先**，缺失才下载。
            //
            // 用户实测「下不了 Ubuntu」后要求内置。内置路径不碰网络，
            // 所以「移动网络拦 TLS」「后台进程被冻结」「缓存被清理器删掉」
            // 这三类失败直接消失。
            //
            // ## 缓存文件名为什么不再由 `isXz` 拼（2026-10-23 修）
            //
            // 旧写法 `"rootfs-xxx" + if (isXz) ".tar.xz" else ".tar.gz"` 的
            // 扩展名来自编译期常量，与真实内容无关。本次真机上常量说 gzip、
            // 实际是裸 tar，于是解包参数选错 —— 一个纯因命名而起的故障。
            //
            // 现在缓存文件名固定 `.tarball`（不带任何格式暗示），
            // 格式只在解压时由 `detectCompression()` 读文件头决定。
            val tarball = File(cacheDir, "rootfs-" + env.distro.name.lowercase() + ".tarball")
            val fromAsset = copyBuiltInAsset(env.distro, tarball, onLine)
            if (!fromAsset) {
                // ② 兜底：内置资产缺失（CI 未同步 assets）时退回下载。
                val candidates = imageCandidates(env)
                if (candidates.isEmpty()) {
                    onLine("内置镜像缺失，且没有为 ${env.distro.label} 配置下载地址。")
                    return@withContext false
                }
                onLine("内置镜像不可用，改用下载（解压后约 $sizeHint MB）。")
                onLine("共 ${candidates.size} 个候选镜像。")
                val failure = downloadAny(candidates, tarball, onLine)
                if (failure != null) {
                    onLine("下载失败：$failure")
                    return@withContext false
                }
            }

            // ③ 解到**临时目录**再原子替换。
            //
            // ## 为什么不能像旧实现那样「清空 target 再解压」
            //
            // 旧实现 `target.deleteRecursively()` 之后再解。那在「一个发行版
            // 一个目录」时是安全的（最坏只是那个发行版没了）。
            // 但现在**两个发行版共用同一个 rootfs**：解压 Kafka 到一半失败
            // 就意味着 Ubuntu 那边也一起废了 —— 用户会同时失去两个环境。
            //
            // 解到 `<target>.staging` 再 `renameTo`：rename 在同一文件系统上
            // 是原子的，要么看到完整的旧树、要么看到完整的新树，
            // 不存在「半个 rootfs」的中间态。
            onLine("正在解压…")
            val target = env.rootfsDir
            val staging = File(target.absolutePath + ".staging")
            if (staging.exists()) staging.deleteRecursively()
            if (!staging.mkdirs()) {
                onLine("无法创建解压目录：${staging.absolutePath}")
                return@withContext false
            }
            // ③ 探测压缩格式 —— **读文件头魔数，不看文件名/配置**。
            //
            // 这是本次「Ubuntu 装不上」的核心修复点之一。
            val format = detectCompression(tarball)
            if (format == TarFormat.Unknown) {
                onLine("无法识别的镜像格式（文件头既不是 gzip/xz，也不像 tar）。")
                onLine("文件：${tarball.absolutePath}（${tarball.length() / 1024 / 1024} MB）")
                runCatching { tarball.delete() }
                return@withContext false
            }
            onLine("镜像格式：${format.label}")

            val tarExit = extract(tarball, staging, format, onLine)
            if (tarExit != 0) {
                // ## 报错必须带出 tar 的真实输出
                //
                // 旧实现只说「解压失败（tar 退出码 N）」。而 tar 的 stderr
                // 已经被 `redirectErrorStream` 合并到 onLine 里了，用户能看到
                // `zcat: not gzip` 这类原文 —— 但**没有一句告诉他这意味着什么**。
                //
                // 这里补一句可操作的解读：格式判断与 tar 的抱怨对不上，
                // 几乎只有两种可能（头几个字节是压缩流但内容被截断，
                // 或者 tar 版本不支持该格式）。
                onLine(
                    "解压失败（tar 退出码 $tarExit，按 ${format.label} 处理）。" +
                        if (format == TarFormat.Raw) {
                            "若是「not gzip / not xz」，说明文件其实被压缩过，请检查镜像来源。"
                        } else {
                            "若是「not gzip / not xz」，说明镜像文件被截断或损坏。"
                        },
                )
                staging.deleteRecursively()
                runCatching { tarball.delete() }
                return@withContext false
            }

            // ③-b 扁平化：Kali 的 tarball 全部在 `kali-arm64/` 之下，
            //      不剥掉这层目录，`/bin/sh` 就不在根上，容器起不来。
            flattenIfSingleTopDir(staging, onLine)

            // ④ 校验：没有 /bin/sh 说明解压不完整。
            if (!File(staging, "bin/sh").isFile && !File(staging, "usr/bin/sh").isFile) {
                onLine("解压后找不到 /bin/sh，镜像可能不完整。")
                staging.deleteRecursively()
                return@withContext false
            }

            // ⑤ 写入「这份 rootfs 来自哪里」的标记。共用文件系统后，
            //    界面要靠它如实告诉用户「当前底层是 Ubuntu base 还是 Kali」。
            runCatching {
                val optDir = File(staging, "opt").apply { mkdirs() }
                File(optDir, ".praxis-rootfs-origin").writeText(env.distro.name.lowercase())
                // 刚装完时的 apt 源就是安装镜像自带的那个，所以两者一致。
                // 写下这个标记，「应用 XX 源」按钮才不会在刚装完时就冒出来。
                File(optDir, ".praxis-apt-sources").writeText(env.distro.name.lowercase())
            }

            // ⑥ 原子替换。旧目录先挪走再删，避免「删到一半失败」既丢新的又丢旧的。
            val backup = File(target.absolutePath + ".old")
            if (backup.exists()) backup.deleteRecursively()
            var moved = false
            if (target.exists()) {
                moved = target.renameTo(backup)
                if (!moved) {
                    // 重命名失败（跨挂载点等极端情况）：只能删了重来。
                    onLine("警告：无法暂存旧环境，将直接替换。")
                    target.deleteRecursively()
                }
            }
            if (!staging.renameTo(target)) {
                onLine("无法把新环境移到目标位置：${target.absolutePath}")
                // 尽量把旧环境还回去，别让用户两个都没有。
                if (moved) backup.renameTo(target)
                staging.deleteRecursively()
                return@withContext false
            }
            if (backup.exists()) backup.deleteRecursively()

            if (!env.isInstalled) {
                onLine("替换后找不到 /bin/sh，安装失败。")
                return@withContext false
            }

            runCatching { tarball.delete() }
            onLine("安装完成（${env.distro.label}）。")
            true
        }.getOrElse { error ->
            onLine("安装出错：${error.message ?: error::class.simpleName}")
            false
        }
    }

    /**
     * 内置 rootfs 解压后的**顶层多余目录**名。
     *
     * ## 这是一个真实踩到的坑（2026-10-22）
     *
     * 两个上游 tarball 的结构**不一样**：
     *
     * | | 顶层结构 | `tar -x` 到 target 后 |
     * |---|---|---|
     * | Ubuntu base | `bin/` `etc/` `usr/` … 直接在根 | **正确**，`target/bin/sh` 存在 |
     * | Kali NetHunter | 全部在 `kali-arm64/` 之下 | **错误**，只有 `target/kali-arm64/bin/sh` |
     *
     * 不处理的话：Kali 装完，`LinuxEnvironment.isInstalled` 检查
     * `target/bin/sh` 不存在 → 报「解压后找不到 /bin/sh，镜像可能不完整」。
     * 而 tarball 其实是好的 —— 这个错误信息会把排查引向错误方向。
     *
     * ## 为什么不用 `tar --strip-components=1` 统一剥一层
     *
     * 那会在 Ubuntu 上剥掉**真实的根目录**（`bin/` → 不见了），
     * 直接把好镜像毁掉。两个源不一致，就必须分别处理。
     *
     * ## 为什么是「解完再挪」而不是解的时候 `-C` 进子目录
     *
     * 在解压前无法知道 tarball 的顶层结构（要读全表）。解完再判断
     * 「顶层是否只有一个目录且不是标准根目录之一」，是唯一稳妥的做法。
     */
    private fun flattenIfSingleTopDir(target: File, onLine: (String) -> Unit) {
        val entries = target.listFiles() ?: return
        // 标准 rootfs 根目录的必备成员。只要出现任意一个，说明没套壳。
        val standardRootEntries = setOf(
            "bin", "etc", "usr", "var", "lib", "opt", "root", "home", "srv", "tmp",
        )
        if (entries.any { it.name in standardRootEntries }) return

        // 只剩一个目录才认为是「套了一层」。
        val only = entries.singleOrNull() ?: run {
            onLine("顶层有 ${entries.size} 项且无标准根目录，跳过扁平化。")
            return
        }
        if (!only.isDirectory) {
            onLine("顶层唯一项不是目录，跳过扁平化。")
            return
        }
        onLine("检测到顶层套壳目录 ${only.name}/，正在扁平化…")

        // 逐项挪到 target 下。用 renameTo（同文件系统，原子且瞬时）；
        // 失败时退回「拷贝 + 删」，慢但不至于整包失败。
        val children = only.listFiles().orEmpty()
        for (child in children) {
            val dest = File(target, child.name)
            if (dest.exists()) {
                // 同名冲突（极罕见：套壳目录里同时有 `bin/` 和根层的 `bin`）。
                // 保守处理：删掉内层，保留先到的那个，并明确告知。
                onLine("跳过重复项 ${child.name}（外层已存在）。")
                child.deleteRecursively()
                continue
            }
            if (!child.renameTo(dest)) {
                onLine("无法移动 ${child.name}，尝试拷贝…")
                runCatching {
                    child.copyRecursively(dest, overwrite = false)
                    child.deleteRecursively()
                }.onFailure { onLine("移动 ${child.name} 失败：${it.message}") }
            }
        }
        runCatching { only.delete() }
        onLine("扁平化完成。")
    }

    /**
     * 把 APK assets 里的内置 rootfs 拷到 [target]。
     *
     * ## 为什么要拷贝而不是直接解压 assets
     *
     * `AssetManager.open()` 返回的是**不可随机访问**的流，
     * 而 `toybox tar` 必须拿到真实文件路径（它要 seek 读 gzip/xz 流）。
     * 而且 assets 里的文件无法像普通文件那样 `length()` 预检。
     *
     * 所以流程是「asset → cache 文件 → tar 解压」。多占一份和解压后
     * 大小相当的临时空间，装完即删。
     *
     * ## 为什么 assets 必须 `noCompress`
     *
     * APK 默认会对 assets 做 deflate。一个已经是 `.tar.gz` / `.tar.xz` 的
     * 文件再 deflate 一次几乎没有压缩收益（可能还变大），
     * 更要命的是 AAPT2 会在无压缩标记缺失时改变文件可访问性。
     * 已在 `app/build.gradle.kts` 的 `androidResources.noCompress` 里
     * 把 `rootfs/` 前缀排除（见那里的说明）。
     *
     * ## 2026-10-23：改为**按候选列表逐个探测**
     *
     * 旧实现只试 `distro.builtInAsset` 这一个名字。真机 APK 里实际的资产是
     * `rootfs/ubuntu-noble-arm64.tar`（裸 tar，106 MB），而常量写的是
     * `rootfs/ubuntu-noble-arm64.tar.gz` —— 于是每次都 miss，静默回落到下载，
     * 用户看到的就是「Ubuntu 安装失败」。
     *
     * 现在按 [LinuxDistro.builtInAssets] 顺序探测，命中即用。
     * 同时**打日志说明试过哪些名字**：再不匹配时，日志本身就是诊断依据，
     * 不用再靠翻 APK 的 `unzip -l` 反推。
     *
     * @return true 表示成功从 assets 拷出；false 表示所有候选都不存在或拷贝失败
     *   （调用方应退回下载）
     */
    private fun copyBuiltInAsset(
        distro: LinuxDistro,
        target: File,
        onLine: (String) -> Unit,
    ): Boolean = runCatching {
        val tried = mutableListOf<String>()
        val assetName = distro.builtInAssets.firstOrNull { candidate ->
            tried += candidate
            // 先探存在性：`open` 失败会抛 FileNotFoundException，用它判断也可以，
            // 但先 open/close 一次更明确，且能区分「不存在」与「读失败」。
            runCatching { context.assets.open(candidate).close() }.isSuccess
        }
        if (assetName == null) {
            onLine("内置镜像缺失（已尝试：${tried.joinToString("、")}）。")
            return false
        }
        onLine("正在使用内置镜像（${distro.label} · ${assetName.substringAfterLast('/')}）…")
        target.parentFile?.mkdirs()
        var written = 0L
        context.assets.open(assetName).use { input ->
            target.outputStream().use { output ->
                val buf = ByteArray(256 * 1024)
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    written += n
                    // 内置资产较大（最大 131 MB），每 16MB 报一次进度。
                    if (written - lastReport > 16L * 1024 * 1024) {
                        lastReport = written
                        onLine("已释放 ${written / 1024 / 1024} MB")
                    }
                }
            }
        }
        if (written == 0L) {
            onLine("内置镜像为 0 字节，视为损坏。")
            target.delete()
            return false
        }
        onLine("内置镜像已释放（${written / 1024 / 1024} MB）。")
        true
    }.getOrElse { error ->
        onLine("读取内置镜像失败：${error.message ?: error::class.simpleName}")
        false
    }

    /**
     * 候选 rootfs 下载地址（按优先级）。
     *
     * ## 为什么不再先做 HEAD 探测（2026-10-22 修）
     *
     * 旧实现先用 HEAD 找出「第一个 200」的地址，再只对**那一个**地址发 GET。
     * 这里有两个真实缺陷：
     *
     * 1. **HEAD 200 不代表 GET 能成功**。镜像做限速、命中回源失败、
     *    或对 HEAD 与 GET 给不同响应时，HEAD 是 200 而 GET 会失败；
     * 2. 一旦那个地址的 GET 失败，旧代码**直接放弃**，从不试其余候选 ——
     *    明明后面还有能用的镜像。
     *
     * 现在直接返回全部候选，由 [downloadAny] 逐个 GET 尝试：
     * 不存在「探到一个坏地址就无解」的情况，而且第一个成功的就是真正可用的。
     *
     * 代价是「404 的候选也要发一次 GET」——但 404 在响应头阶段就返回，
     * 几乎不耗流量，换来的是「失败可重试」这个更重要的性质。
     */
    private fun imageCandidates(env: LinuxEnvironment): List<String> = when (env.distro) {
        // Kali 的 NetHunter rootfs 只有固定几个地址，不需要拼版本号。
        LinuxDistro.Kali -> env.distro.rootfsUrls
        // Ubuntu 的补丁号不等于版本号（24.04 → 24.04.3），必须靠候选列表兜。
        LinuxDistro.Ubuntu ->
            UbuntuBaseImage.candidateUrls(env.mirror, env.release) +
                UbuntuBaseImage.officialUrl(env.release)
    }.distinct()

    /** 一次下载的结果 —— 成功，或「失败 + 为什么」。 */
    private sealed interface DownloadOutcome {
        data object Success : DownloadOutcome

        /** @param reason 给用户看的一句话（含可操作信息，不含堆栈） */
        data class Failed(val reason: String) : DownloadOutcome
    }

    /**
     * 依次尝试候选地址下载，返回**第一个成功**的。
     *
     * @return 成功返回 null；全失败返回最后一个失败原因（用于展示）。
     *
     * ## 为什么把「重试下一个镜像」放在这一层
     *
     * 上层 `install` 只关心「成不成」，不该知道有几个镜像。
     * 而且「哪个镜像失败、失败原因是什么」必须**逐条**报给用户 ——
     * 只报最后一次会掩盖「第一个是 404、第二个才是超时」这类信息。
     */
    private fun downloadAny(
        urls: List<String>,
        target: File,
        onLine: (String) -> Unit,
    ): String? {
        var lastReason: String? = null
        for ((index, url) in urls.withIndex()) {
            val host = runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)
            onLine("镜像 ${index + 1}/${urls.size}：$host")
            when (val outcome = download(url, target, onLine)) {
                is DownloadOutcome.Success -> return null
                is DownloadOutcome.Failed -> {
                    lastReason = outcome.reason
                    onLine("该镜像不可用：${outcome.reason}")
                }
            }
        }
        return lastReason ?: "没有可用的镜像地址。"
    }

    /**
     * 流式下载到文件。
     *
     * ## 这次重写修的是什么（2026-10-22）
     *
     * 旧实现是 `runCatching { ... }.getOrDefault(false)`，把**所有**异常
     * 压成一个 `false`。于是用户界面上只有「下载失败。」四个字，
     * 而真实原因可能是下面任意一种，处理方式完全不同：
     *
     * | 真实原因 | 旧表现 | 用户该做什么 |
     * |---|---|---|
     * | 磁盘空间不足 | 下载失败 | 清理存储 |
     * | TLS/证书失败 | 下载失败 | 换镜像或检查时间 |
     * | DNS 解析失败 | 下载失败 | 检查网络 |
     * | 连接/读超时 | 下载失败 | 重试或换镜像 |
     * | HTTP 404/503 | 下载失败 | 换镜像 |
     * | 传输被截断 | 下载失败（或更糟：解压失败）| 重试 |
     *
     * 这不是「体验问题」，而是**故障不可定位**：同一个提示对应六种处置方式，
     * 用户只能反复点重试。所以这里改为返回带原因的结果。
     *
     * ## 另外三处修复
     *
     * 1. **写文件前重建父目录**：`cacheDir` 只在构造时 `mkdirs()` 过一次。
     *    Android 在存储紧张时会清理缓存，之后 `outputStream()` 直接抛
     *    `FileNotFoundException` —— 旧代码把它吞成「下载失败」，
     *    而界面上的「已在下载」提示让人完全想不到是缓存被清了。
     * 2. **校验长度**：`Content-Length` 已知时核对实际写入量。
     *    截断的 tarball 旧代码会当成「下载成功」，然后在解压阶段以
     *    「不是 gzip 格式」失败 —— 那个报错会把人引向错误的排查方向。
     * 3. **失败时删掉半截文件**：留着它既占空间，又会让下一次
     *    「文件已存在」的判断出现歧义。
     */
    private fun download(
        url: String,
        target: File,
        onLine: (String) -> Unit,
    ): DownloadOutcome {
        // 父目录必须在写之前确保存在（缓存可能已被系统清掉）。
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return DownloadOutcome.Failed("无法创建下载目录：${parent.absolutePath}")
        }

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                // 单次 read 的等待上限。29.8 MB 在慢网下整体要几分钟，
                // 但单次读通常几十毫秒到几秒，60s 足够容忍卡顿。
                readTimeout = 60_000
                instanceFollowRedirects = true
            }

            val code = conn.responseCode
            // 200 = 正常；206 = 服务端/代理对 GET 返回了部分内容
            // （部分镜像网关会这样，仍可接受）。其余一律失败。
            if (code != HttpURLConnection.HTTP_OK && code != 206) {
                return DownloadOutcome.Failed(httpReason(code))
            }

            val total = conn.contentLengthLong
            var written = 0L
            var lastReport = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        written += n
                        // 每 2MB 报一次，避免状态更新过频。
                        if (written - lastReport > 2L * 1024 * 1024) {
                            lastReport = written
                            onLine(
                                if (total > 0) {
                                    "已下载 ${written * 100 / total}%"
                                } else {
                                    "已下载 ${written / 1024 / 1024} MB"
                                },
                            )
                        }
                    }
                }
            }

            // 长度校验：服务端给了 Content-Length 就必须接收完整。
            // 少了说明连接中途断开（read 返回 -1 让循环正常退出），
            // 这种情况下文件表面「写完」，实际是坏的。
            if (total > 0 && written != total) {
                target.delete()
                return DownloadOutcome.Failed(
                    "传输不完整：收到 ${mb(written)} / 应为 ${mb(total)}，请重试。",
                )
            }
            if (written == 0L) {
                target.delete()
                return DownloadOutcome.Failed("服务端返回空内容（0 字节）。")
            }
            DownloadOutcome.Success
        } catch (e: java.net.SocketTimeoutException) {
            target.delete()
            DownloadOutcome.Failed("连接超时（${e.message ?: "读/连接超时"}）。")
        } catch (e: javax.net.ssl.SSLException) {
            target.delete()
            DownloadOutcome.Failed("HTTPS 握手失败：${e.message ?: e::class.simpleName}。")
        } catch (e: java.net.UnknownHostException) {
            target.delete()
            DownloadOutcome.Failed("域名解析失败：${e.message ?: "未知主机"}。")
        } catch (e: java.io.FileNotFoundException) {
            // 这里最常见的两个来源：父目录不存在、磁盘写满。
            target.delete()
            DownloadOutcome.Failed("写入文件失败：${e.message ?: "文件无法创建"}。")
        } catch (e: java.io.IOException) {
            target.delete()
            DownloadOutcome.Failed("网络中断：${e.message ?: e::class.simpleName}。")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 把字节数换成便于阅读的 MB。 */
    private fun mb(bytes: Long): String =
        (bytes / 1024 / 1024).toString() + " MB"

    /**
     * HTTP 状态码 → 可操作的一句话。
     *
     * 不直接把「HTTP 404」甩给用户：他不知道该干什么。
     * 但也不能只写「失败」—— 那等于没说话。
     */
    private fun httpReason(code: Int): String = when (code) {
        404 -> "镜像上不存在该文件（HTTP 404），请换一个镜像源。"
        403 -> "镜像拒绝访问（HTTP 403），请换一个镜像源。"
        429, 503 -> "镜像限流或暂时不可用（HTTP $code），请稍后重试或换源。"
        500, 502, 504 -> "镜像服务端错误（HTTP $code），请换一个镜像源。"
        else -> "镜像返回 HTTP $code。"
    }

    /**
     * 用 toybox tar 解压。
     *
     * `-o`：忽略 owner。普通 app 无法 chown，不加时 tar 会为每个文件打
     * `Cannot change ownership` 并**以非 0 退出** —— 看起来像失败，但文件其实解开了。
     *
     * ## 参数由 [TarFormat] 决定，不再由编译期常量决定
     *
     * 旧签名是 `xz: Boolean`，值来自 `distro.isXz`。真机上那个值与实际文件
     * 不符（说 gzip、实际裸 tar），于是 `-z` 被塞给了裸 tar，tar 报
     * `zcat: not gzip / tar: had errors`（实测退出码 1）。
     *
     * 现在由调用方用 [detectCompression] 读文件头得到格式再传进来。
     * 三个分支各自对应一个明确的 tar 参数组合，没有「猜」的余地。
     *
     * ## 2026-10-24 重写：放弃 `-h`，改为「排除硬链接条目 + 事后补副本」
     *
     * ### 旧实现（`-h` 重试）为什么**必然**失败
     *
     * 旧代码在检测到 `can't link` 后用 `-xofh` 重试，理由是 toybox usage 里
     * 写着 `h  Follow symlinks`。真机实验推翻了两个前提：
     *
     * 1. **`h` 不能拼在合并选项串里**。实测 `toybox tar -xofh` 报
     *    `tar: h: No such file or directory`、退出码 1 —— toybox 把 `h`
     *    当成了**文件名参数**。正确写法只有 `-h -xof`（`-h` 单独在前）。
     *    所以那次「降级重试」实际上一个文件都没解，等于把第一次的结果删掉
     *    之后又失败了一次 —— 这解释了截图里「重试后仍然报同一个错」。
     * 2. **`-h` 对硬链接条目根本不生效**。实测 tar 包里
     *    `./alias.txt link to ./real.txt` 这种条目，用 `-h -xof` 解开后
     *    两个文件仍是同一 inode（链接计数 2）。`h` 在 toybox 里只处理
     *    **符号链接**，硬链接条目仍走 `link()`。
     *
     * 结论：`-h` 这条路不存在可行写法，必须换实现。
     *
     * ### 现在的做法
     *
     * 分三步，每一步都有实测支撑：
     *
     * 1. **先列出** `tar -tvf` 里的硬链接条目（输出含 ` link to `）。
     *    Ubuntu base 镜像里恰好只有两个（`usr/bin/perl`、`usr/bin/gunzip`），
     *    但这个函数不假设数量，遇到几个处理几个。
     * 2. **解压时逐个 `--exclude` 掉它们**。这样 tar 从头到尾不会调用
     *    `link()`，SELinux 的 `avc: denied { link }` 无从触发，退出码为 0。
     *    实测：`tar -xof t.tar -C o --exclude='alias.txt'` → exit=0。
     * 3. **解压后按「链接目标 → 链接名」用 `cp` 补齐独立副本**。
     *    实测 `cp real.txt alias.txt` 得到独立 inode，功能等价（`gunzip`
     *    与 `perl` 都能正常跑），代价是各多占一份空间
     *    （`perl5.38.2` 约 2MB、`gunzip` 约 2.3KB，对 100MB+ 的 rootfs 可忽略）。
     *
     * 相比 `proot --link2symlink`（Termux 路线的正解），这个方案不需要
     * 先有可用的 rootfs 就能解压 —— 而**解压本身正是为了造出 rootfs**，
     * 鸡生蛋的问题绕开了。将来若要做「在容器内 apt 装包」，那条路径上
     * proot 的 `--link2symlink` 仍然必要（已在 `ProotCommand.loginArgs` 里）。
     *
     * @return tar 的退出码；`0` 表示成功。
     */
    private fun extract(
        tarball: File,
        target: File,
        format: TarFormat,
        onLine: (String) -> Unit,
    ): Int {
        // ① 先问 tar 有哪些硬链接条目。用同一次探测顺带拿到「这份镜像有没有」，
        //    没有就完全走原路径，不多付任何代价。
        val linkEntries = listHardLinkEntries(tarball, format, onLine)
        if (linkEntries.isEmpty()) {
            // ## 必须用具名参数 `onLine =`
            //
            // `runTar` 的签名是 `(tarball, target, format, excludes, onLine)`，
            // 第 4 位是 `excludes: List<String>`。位置传参会把 `onLine`
            // 塞进 `excludes` 槽位，编译期报的却是「No value passed for
            // parameter 'onLine'」—— 错误信息指向缺失参数，而真实错因是
            // 第 4 个位置被占。这类报错极易误判成「忘了传 onLine」。
            return runTar(tarball, target, format, onLine = onLine).exitCode
        }

        // ② 有硬链接：排除它们再解，避开 SELinux 的 link 限制。
        onLine(
            "镜像含 ${linkEntries.size} 个硬链接条目（${linkEntries.joinToString("、") { it.linkName }}）。" +
                "已改为「跳过硬链接、解出独立副本」方式解压 —— " +
                "系统不允许应用创建硬链接（SELinux 拒绝 link），这与镜像本身无关。",
        )
        val exit = runTar(
            tarball = tarball,
            target = target,
            format = format,
            excludes = linkEntries.map { it.linkName },
            onLine = onLine,
        ).exitCode
        if (exit != 0) return exit

        // ③ 解压成功后再补副本。失败不影响整体（少两个别名而已），
        //    但必须报出来 —— 否则用户会在容器里遇到「perl 找不到」且毫无线索。
        materializeLinks(target, linkEntries, onLine)
        return 0
    }

    /**
     * 一个硬链接条目：`linkName` 是这条的名字，`targetName` 是它指向的路径。
     *
     * `tar -tv` 对硬链接的输出形如：
     *
     * ```
     * -rw-r--r-- root/root   0 2026-10-24 08:57 ./alias.txt link to ./real.txt
     * ```
     *
     * 注意**大小是 0**（硬链接条目在 tar 里不重复存内容），
     * 所以不能靠「大小是否为 0」判断，只能认 ` link to ` 这个分隔串。
     */
    private data class HardLinkEntry(val linkName: String, val targetName: String)

    /**
     * 列出 tarball 里的硬链接条目。
     *
     * ## 为什么用 `tar -tvf` 而不是自己解析 tar 头
     *
     * 自己解析要处理 gzip/xz 解压、512 字节块对齐、GNU 长文件名（`L` 类型）
     * 与 PAX 扩展头 —— 任何一处错都会静默产出错误的条目名，
     * 而错误的 `--exclude` 会让本该解出的文件消失。用 tar 自己的 `-t`
     * 就没有这些风险：它已经正确解析过一遍了。
     *
     * ## 失败时返回空列表（= 走原路径）
     *
     * 探测失败不应该让安装直接失败：有些 tar 版本对 `-tv` 的措辞不同。
     * 返回空列表等于「当作没有硬链接」，退回原始行为 —— 若真有硬链接，
     * 后面的 `can't link` 会让用户在日志里看到真实原因。
     */
    private fun listHardLinkEntries(
        tarball: File,
        format: TarFormat,
        onLine: (String) -> Unit,
    ): List<HardLinkEntry> {
        // 压缩标志与解压时保持一致。`Unknown` 在调用方已被拦下，
        // 这里只是让 `when` 穷举完整（返回空列表 = 当作没有硬链接）。
        val flag = when (format) {
            TarFormat.Gzip -> "-tzf"
            TarFormat.Xz -> "-tJf"
            TarFormat.Raw -> "-tf"
            TarFormat.Unknown -> return emptyList()
        }
        return runCatching {
            val p = ProcessBuilder(
                "/system/bin/toybox", "tar", flag, tarball.absolutePath,
            ).redirectErrorStream(true).start()
            val entries = mutableListOf<HardLinkEntry>()
            BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    // 只认硬链接行。`link to` 前后都有空格，避免误伤文件名里
                    // 恰好含 "linkto" 的普通文件。
                    val marker = " link to "
                    val at = line.indexOf(marker)
                    if (at <= 0) continue
                    // 这一行里「权限 + 属主 + 长度 + 时间」之后才是文件名，
                    // 形如 `... 08:57 ./alias.txt link to ./real.txt`。
                    // 用标记切分：左段取最后一个空格后的部分 = 链接名。
                    val linkName = line.substring(0, at).trim().substringAfterLast(' ')
                    val targetName = line.substring(at + marker.length).trim()
                    if (linkName.isNotEmpty() && targetName.isNotEmpty()) {
                        entries += HardLinkEntry(linkName, targetName)
                    }
                }
            }
            p.waitFor(2, TimeUnit.MINUTES)
            entries
        }.getOrElse { error ->
            onLine("硬链接预检失败（${error.message ?: error::class.simpleName}），按无硬链接处理。")
            emptyList()
        }
    }

    /**
     * 把被跳过的硬链接条目补成**独立副本**。
     *
     * 目标可能解在任意深度（`./usr/bin/perl`），所以先按条目名定位已解出的
     * 原文件，再 `copyTo` 到链接名所在路径 —— 两者都在 [root] 之下。
     */
    private fun materializeLinks(
        root: File,
        entries: List<HardLinkEntry>,
        onLine: (String) -> Unit,
    ) {
        for (entry in entries) {
            val source = File(root, entry.targetName.removePrefix("./"))
            val dest = File(root, entry.linkName.removePrefix("./"))
            if (!source.isFile) {
                onLine("补副本跳过 ${entry.linkName}：源文件 ${entry.targetName} 不存在。")
                continue
            }
            if (dest.exists()) continue
            dest.parentFile?.mkdirs()
            runCatching { source.copyTo(dest, overwrite = true) }
                .onFailure { onLine("补副本失败 ${entry.linkName}：${it.message}") }
        }
    }

    /**
     * 跑一次 tar。
     *
     * @param excludes 需要跳过的条目名（硬链接条目）。空列表表示不排除任何东西。
     */
    private fun runTar(
        tarball: File,
        target: File,
        format: TarFormat,
        excludes: List<String> = emptyList(),
        onLine: (String) -> Unit,
    ): TarRun {
        return runCatching {
            // 「忽略 owner + 指定文件 + 切换目录」这三项对所有分支都一样，
            // 只有压缩标志位不同：
            //   gzip → -z
            //   xz   → -J（已实测本机 toybox tar 的 usage 里明确列了 `J  xz compression`）
            //   raw  → 不给压缩标志
            val mode = when (format) {
                TarFormat.Gzip -> "-xzof"
                TarFormat.Xz -> "-xJof"
                TarFormat.Raw -> "-xof"
                TarFormat.Unknown -> "-xof" // 不会走到（调用方已拦下）
            }
            onLine("解压参数：tar $mode")
            val argv = mutableListOf(
                "/system/bin/toybox", "tar",
                mode, tarball.absolutePath,
                "-C", target.absolutePath,
            )
            // `--exclude` 必须在文件参数之后：toybox 的选项解析是顺序的，
            // 放在 tar 文件之前有时会被当成「要排除的第一个成员」。
            excludes.forEach { argv += "--exclude=$it" }
            val pb = ProcessBuilder(argv).redirectErrorStream(true)
            val p = pb.start()
            var linkDenied = false
            BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) onLine(line)
                    // 逐行识别而不是事后 grep 全部输出：后者要把几万行缓存起来。
                    //
                    // 这里**不再**用它决定是否降级（旧实现用它触发 `-h` 重试，
                    // 而那条路已验证无效，见 [extract] 的 KDoc）。
                    // 保留识别只为在日志里补一句准确的归因 —— 万一某个镜像的
                    // 硬链接藏在 `--exclude` 匹配不到的位置，用户也能看懂原因。
                    if (!linkDenied && line.contains("can't link")) {
                        linkDenied = true
                        onLine(
                            "该条目无法创建硬链接（系统 SELinux 限制，与镜像无关）。" +
                                "缺失的只是别名，不影响系统主体功能。",
                        )
                    }
                }
            }
            p.waitFor(10, TimeUnit.MINUTES)
            TarRun(exitCode = p.exitValue())
        }.getOrElse { error ->
            onLine("解压进程启动失败：${error.message ?: error::class.simpleName}")
            TarRun(exitCode = -1)
        }
    }

    /**
     * 一次 tar 执行的结果。
     *
     * @param exitCode 进程退出码。`0` 才是成功。
     */
    private data class TarRun(val exitCode: Int)

    /**
     * 读文件头判断压缩格式。**唯一的格式判定入口。**
     *
     * ## 为什么必须读文件头而不是看文件名/常量
     *
     * 本次故障就是两个「元数据与实际文件不符」叠在一起：
     *
     * | 判据 | 说是什么 | 实际是什么 |
     * |---|---|---|
     * | 文件名 `ubuntu-noble-arm64.tar.gz` | gzip | 不存在这个文件 |
     * | 实际资产 `ubuntu-noble-arm64.tar` | 裸 tar（后缀对） | 裸 tar |
     * | 常量 `builtInIsXz = false` | gzip | 裸 tar |
     *
     * 前两行还能靠「都试一遍」兼容，第三行（用 `false` 去选 `-z`）
     * 是**必然出错**的，因为裸 tar 没有 gzip 魔数。
     *
     * 文件头是唯一与内容强绑定的事实，读它就不可能错。
     *
     * ## 三种格式的魔数
     *
     * - gzip：`1F 8B`
     * - xz：`FD 37 7A 58 5A 00`（即 `\xFD7zXZ\0`）
     * - tar：没有「前两字节」魔数，`ustar` 在偏移 **257**。所以先排除前两者，
     *   再确认偏移 257 处是 `ustar`；都不是就返回 [TarFormat.Unknown]。
     *
     * 不能把「非 gzip 非 xz」一律当 tar：那会把「一个 HTML 错误页」
     * 也送去 tar 解压，报出一堆无意义的行。Unknown 让调用方给出准确提示。
     */
    private fun detectCompression(file: File): TarFormat {
        if (!file.isFile || file.length() < 512) return TarFormat.Unknown
        return runCatching {
            val header = ByteArray(512)
            val read = file.inputStream().use { it.read(header) }
            if (read < 2) return TarFormat.Unknown

            fun at(i: Int) = header[i].toInt() and 0xFF

            // gzip: 1F 8B
            if (at(0) == 0x1F && at(1) == 0x8B) return TarFormat.Gzip
            // xz: FD 37 7A 58 5A 00
            if (read >= 6 &&
                at(0) == 0xFD && at(1) == 0x37 && at(2) == 0x7A &&
                at(3) == 0x58 && at(4) == 0x5A && at(5) == 0x00
            ) {
                return TarFormat.Xz
            }
            // tar: "ustar" 在偏移 257（老式 v7 tar 没有该魔数，
            // 但 ubuntu-base / NetHunter 都是 GNU/POSIX 格式，必然有）。
            if (read >= 262) {
                val magic = String(header, 257, 5, Charsets.US_ASCII)
                if (magic == "ustar") return TarFormat.Raw
            }
            TarFormat.Unknown
        }.getOrDefault(TarFormat.Unknown)
    }

    // ── 存储管理（2026-10-23 新增）────────────────────────────────

    /**
     * 共享 tmp 的宿主目录。
     *
     * 与 rootfs 平级（见 `ProotCommand.HOST_TMP_DIR_NAME` 的说明）：
     * `filesDir/linux/tmp-host`。
     */
    private fun hostTmpDirOf(env: LinuxEnvironment): File =
        File(env.rootfsDir.parentFile, LinuxEnvironment.HOST_TMP_DIR_NAME)

    /**
     * 统计环境占用。
     *
     * ## 为什么切到 IO 调度器
     *
     * 要遍历 3 万+ 个 inode，主线程会 ANR 级别地卡住。
     * `withContext(Dispatchers.IO)` 是这里唯一的正确做法 ——
     * 不要指望调用方记得切。
     *
     * ## 为什么 tarball 用 cacheDir 而不是 rootfs 附近
     *
     * 见 [cacheDir] 的定义：下载缓存落在 `cacheDir/linux`。
     * 它在系统空间不足时会被系统**自动清掉**，这正是缓存该有的待遇。
     */
    override suspend fun usage(
        env: LinuxEnvironment,
    ): LinuxDiskUsage.EnvironmentUsage? = withContext(Dispatchers.IO) {
        runCatching {
            LinuxDiskUsage.environmentUsage(
                rootfsDir = env.rootfsDir,
                // 未开启共享 tmp 时传 null：那时容器的 /tmp 在 rootfs 内部，
                // 已包含在 rootfs 的统计里，再算一次会重复。
                hostTmpDir = if (env.sharedTmp) hostTmpDirOf(env) else null,
                // tarball 缓存：装完之后正常应当已被删除（见 install 末尾），
                // 残留说明中断过一次 —— 用户看到它占几百 MB 正好提示可以清。
                tarballPath = cacheDir,
            )
        }.getOrNull()
    }

    /**
     * 清空共享 tmp。
     *
     * ## 为什么不在这里停容器
     *
     * 停止容器是 `LinuxEnvViewModel.clearSharedTmp` 之前用户自己的动作
     * （按钮在运行中置灰）。这一层只做「删文件」，不越权改变运行状态 ——
     * 否则会出现「用户只想清垃圾，结果容器被静默杀了」。
     *
     * 但若文件正被占用，删除会失败，返回值告诉 UI 究竟是几个 —— 见 KDoc。
     */
    override suspend fun clearSharedTmp(env: LinuxEnvironment): Int? =
        withContext(Dispatchers.IO) {
            runCatching { LinuxDiskUsage.clearContents(hostTmpDirOf(env)) }.getOrNull()
        }

    /**
     * 重置环境：停容器 → 删 rootfs → 清标记。
     *
     * ## 顺序不能换
     *
     * 1. **先停会话**：proot 子进程持有 rootfs 里的可执行文件与共享库映射，
     *    不停就删会出现「删了一半，剩下的 EBUSY」，得到一个半残目录 ——
     *    比没删更糟（下次 `isInstalled` 看到 `bin/sh` 还在，认为装好了）。
     * 2. **再删目录**：`deleteRecursively`。
     * 3. **最后清标记**：标记在 `opt/` 里，会随 rootfs 一起被删，
     *    这一步是防「目录删失败但用户以为成功了」的不一致 ——
     *    目录若没删干净，标记也该留着，让界面如实显示「底层是 X」。
     *
     * ## 为什么保留共享 tmp
     *
     * 它是「垃圾目录」，不是环境的一部分。用户点「重置环境」时
     * 往往正想保留临时文件（比如从里面抠出一个下载好的包）。
     * 想清它有一个独立的按钮。
     */
    override suspend fun resetEnvironment(env: LinuxEnvironment): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                // ① 停掉所有会话（含 shell / sshd / frpc）。
                stopAllSessions()
                // 稍等进程真正退出：destroy 是异步的，立刻删会撞上 EBUSY。
                // 200ms 是经验值（与 `ShellCommandRunner.cancel` 同量级）。
                Thread.sleep(200)

                // ② 删 rootfs。
                val deleted = if (env.rootfsDir.exists()) {
                    env.rootfsDir.deleteRecursively()
                } else {
                    true
                }

                // ③ 目录真没了才算成功（deleteRecursively 失败时返回 false，
                //    但它也可能「删了一部分」—— 所以要用 exists() 复核）。
                deleted && !env.rootfsDir.exists()
            }.getOrDefault(false)
        }

    // ── 执行 ────────────────────────────────────────────────────────

    /**
     * 在容器里执行一条命令。
     *
     * @param timeoutSeconds 超时秒数。默认 30 分钟（人工在设置页跑 apt 可能要很久）；
     *   AI 工具调用会传一个更短的上限，避免一次工具调用把整轮对话挂死。
     * @return 退出码；超时返回 [TIMEOUT_EXIT_CODE]。
     */
    override suspend fun exec(
        env: LinuxEnvironment,
        command: String,
        timeoutSeconds: Long,
        onLine: (String) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        runCatching {
            val args = ProotCommand.execArgs(env, command)
            val pb = ProcessBuilder(listOf(env.prootBinary.absolutePath) + args)
                .redirectErrorStream(true)
            applyEnv(pb, env)
            val p = pb.start()
            BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    onLine(line)
                }
            }
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                onLine("[超时 ${timeoutSeconds}s，已终止]")
                p.destroyForcibly()
                TIMEOUT_EXIT_CODE
            } else {
                p.exitValue()
            }
        }.getOrElse { error ->
            onLine("执行失败：${error.message ?: error::class.simpleName}")
            -1
        }
    }

    override suspend fun startSession(
        env: LinuxEnvironment,
        args: List<String>,
        onLine: (String) -> Unit,
    ): LinuxEnvPort.SessionHandle = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder(listOf(env.prootBinary.absolutePath) + args)
            .redirectErrorStream(true)
        applyEnv(pb, env)
        val process = pb.start()
        val session = ProotSession(process, onLine)
        session.startPump()
        synchronized(sessions) { sessions += session }
        session
    }

    companion object {
        /** 超时伪退出码。用 124 与 coreutils `timeout` 的约定保持一致。 */
        const val TIMEOUT_EXIT_CODE = 124
    }

    /** 停止所有会话（App 退出前调用）。 */
    fun stopAllSessions() {
        synchronized(sessions) {
            sessions.forEach { runCatching { it.stop() } }
            sessions.clear()
        }
    }

    /**
     * 给子进程设环境变量。
     *
     * ⚠️ **必须清掉 Android 的库路径**：继承下来的 `LD_LIBRARY_PATH` 会让容器里的
     * 程序去加载 Android 的 bionic 库，直接崩。apixiaoyuan 在终端里踩过同一个坑：
     * `curl` 报 `cannot locate symbol "EVP_MD_CTX_create"`。
     */
    private fun applyEnv(pb: ProcessBuilder, env: LinuxEnvironment) {
        pb.environment().apply {
            remove("LD_LIBRARY_PATH")
            remove("LD_PRELOAD")
            put("HOME", env.homeDir)
            put("TERM", "xterm-256color")
            put("LANG", "C.UTF-8")
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            // proot 自己需要临时目录。
            put("PROOT_TMP_DIR", context.cacheDir.absolutePath)

            // ## PROOT_LOADER：不设这一项，容器**一条命令都跑不起来**
            //
            // 真机实测（2026-10-21，aarch64 / SDK 37）：
            //
            // Termux 版的 proot 在 `execve` 任何 guest 程序之前，需要先把自己的
            // `loader` 注入到被跟踪进程里。它默认去硬编码路径找：
            //
            // ```
            // /data/data/com.termux/files/usr/libexec/proot/loader
            // ```
            //
            // 普通 App 的私有目录不是那个路径，于是 proot 报：
            //
            // ```
            // proot error: execve("/bin/sh"): No such file or directory
            // ```
            //
            // 这句报错极具误导性 —— `/bin/sh` 在 rootfs 里明明白白存在，
            // 它把「找不到自己的 loader」说成了「找不到目标程序」，
            // 还附了四条「可能原因」（脚本解释器缺失 / ELF 解释器缺失 / 架构不符 / qemu 异常）
            // 全部指向错误方向。
            //
            // 正确做法：把 loader 也随 APK 分发（`libproot-loader.so`），
            // 并用 `PROOT_LOADER` 指过去。proot 支持这个环境变量覆写。
            //
            // 验证记录（同一台设备）：
            //  - 未设 PROOT_LOADER → `execve: No such file or directory`；
            //  - 设了之后 → 输出 `PROOT_REAL_OK`，`-0` 伪 root 与 bind 全部生效。
            prootLoaderFile()?.let { put("PROOT_LOADER", it.absolutePath) }
        }
    }

    /**
     * proot 的 loader 路径（随 APK 分发的 `libproot-loader.so`）。
     *
     * 返回 null 表示包里没有 —— 此时不设 `PROOT_LOADER`，让 proot 走它自己的
     * 默认查找（在 Termux 环境下仍然可用，只是本 App 里会失败）。
     * 用 `?.let` 而不是硬塞一个不存在的路径：塞错的路径会让 proot 报
     * `loader was not found`，与「根本没设」是两种不同的排错线索。
     */
    private fun prootLoaderFile(): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        return listOf("libproot-loader.so", "libproot_loader.so")
            .map { File(nativeDir, it) }
            .firstOrNull { it.isFile }
    }
}
/**
 * rootfs 镜像的压缩格式。
 *
 * ## 为什么需要这个枚举，而不是一个 `Boolean`
 *
 * 旧代码用 `xz: Boolean` 表达格式。两个值只能编码「gzip / xz」两种，
 * 而实际存在的第三种（**裸 tar**）无法表达 —— 本次真机 APK 里
 * 恰好就是裸 tar。用 `false` 表示裸 tar 会退化成「按 gzip 解」，
 * 必然报 `zcat: not gzip`。
 *
 * 三种格式与 tar 参数的对应关系写死在 [ProotEnvPort.extract] 的 `when` 里，
 * 新增格式时编译器会强制补全分支。
 *
 * @see ProotEnvPort.detectCompression
 */
enum class TarFormat(
    /** 给用户看的名字（日志里会打出来，用于确认判断是否正确）。 */
    val label: String,
) {
    /** 裸 tar（无压缩）。`ubuntu-base-*.tar` 在某些构建流程里会被 gunzip 掉。 */
    Raw("tar（未压缩）"),

    /** gzip：`1F 8B` 开头。`ubuntu-base-*.tar.gz` 的常态。 */
    Gzip("gzip"),

    /** xz：`FD 37 7A 58 5A 00` 开头。Kali NetHunter 的常态。 */
    Xz("xz"),

    /** 三种都认不出来。**不要**把它当 tar 去解 —— 那通常意味着拿到了 HTML 错误页。 */
    Unknown("未知格式"),
}

/**
 * 一个长驻的 proot 会话（交互 shell / sshd / frpc）。
 *
 * 用守护线程按行读输出：`read()` 在无输出时会一直阻塞，**不能放主线程**。
 */
private class ProotSession(
    private val process: Process,
    private val onLine: (String) -> Unit,
) : LinuxEnvPort.SessionHandle {

    override val isAlive: Boolean get() = process.isAlive

    fun startPump() {
        Thread {
            runCatching {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        onLine(line)
                    }
                }
            }.onFailure { error ->
                onLine("会话读取结束：${error.message ?: error::class.simpleName}")
            }
        }.apply {
            isDaemon = true
            name = "praxis-proot-session"
        }.start()
    }

    override fun stop() {
        runCatching {
            // 先温和终止再强杀：proot 需要一点时间回收它跟踪的子进程
            // （`--kill-on-exit` 靠这个窗口生效）。
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }
}