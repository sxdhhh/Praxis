package dev.praxis.data.linux

import android.content.Context
import dev.praxis.core.linux.ProotCommand
import java.io.File

/**
 * 把 proot 的 loader（与 proot 本体）铺到 `filesDir/proot/bin/`。
 *
 * ## 为什么需要「铺一份」而不是直接用 APK 里的（2026-11-01 修）
 *
 * 用户真机截图里的故障是「proot 进程启动后立即退出」，而
 * `PROOT_LOADER` 明明已经设了（`ProotEnvPort` 里那一行）。真因有两层：
 *
 * 1. **名字**：proot 找 loader 时认的是运行名 `proot-loader`，
 *    而 Android 只允许 `lib*.so` 进 `nativeLibraryDir`，
 *    所以我们只能把它打包成 `libproot-loader.so` —— 两个名字对不上。
 * 2. **目录**：loader 与 proot 的相对位置是 proot 用 `/proc/self/exe` 的目录
 *    推导的，那个值恒为 `.../lib/arm64/libproot.so`。符号链接能让 `open()`
 *    通过，但 `/proc/self/exe` 解析到的仍是真实文件，目录推导没有变化。
 *
 * 唯一可行的做法是让 proot **从我们说了算的目录起跑**：那边放好
 * `proot-loader`（运行名）与 `proot`（argv[0] 别名）。
 * argv 的生成规则在 [ProotCommand.launchArgs]，本对象只负责把文件铺好。
 *
 * ## 为什么单独一个对象，而不是各写一份
 *
 * 起 proot 的有两套启动器：`ProotEnvPort`（`ProcessBuilder`：设置页、AI 工具）
 * 与 `ProotTerminalSessionPort`（`forkpty`：终端页）。历史上两条路径
 * 各写一份环境，结果是「PTY 分支能用、降级分支全崩」。铺文件这件事同理：
 * 两边必须指向**同一个**目录与同一套文件名，否则又是一次分叉。
 */
internal object ProotLoaderStaging {


    /**
     * 铺好 loader 与 proot 别名，返回 loader 的路径。
     *
     * @return 铺成功时返回 `filesDir/proot/bin/proot-loader`；
     *   APK 里没有 loader、或 `filesDir` 下建不了目录/文件时返回 null。
     *   **返回 null 不是失败**：调用方会退回旧行为（直接用
     *   `nativeLibraryDir/libproot.so`），容器要么照常起来，
     *   要么报错信息与修之前完全一致 —— 修一个坑不该制造新的坑。
     */
    fun stage(context: Context): File? = runCatching {
        pickPackagedLoader(context.applicationInfo.nativeLibraryDir)
    }.getOrNull()

    // ## 为什么不再往 filesDir 拷一份（2026-11-02 定案）
    //
    // 这个函数**曾经**把 loader 拷到 `filesDir/proot/bin/proot-loader` 再指过去，
    // 理由是「proot 只会按运行名 `proot-loader` 在自身目录里找，而
    // nativeLibraryDir 里只能叫 `libproot-loader.so`」。那个理由在**显式设置**
    // `PROOT_LOADER` 时并不成立 —— 环境变量给的是完整路径，名字随便叫。
    //
    // ### 真正致命的一点：proot 是 `execve` loader 的，不是简单 mmap
    //
    // 实测（API 30，x86_64 proot 5.1.107，模拟器，App 自己的进程）：
    //
    // | `PROOT_LOADER` 指向 | 结果 |
    // |---|---|
    // | `files/proot/bin/proot-loader`（`app_data_file`） | `execve("/bin/sh"): Permission denied` |
    // | `nativeLibraryDir/libproot-loader.so`（`apk_data_file`） | **正常起来，见到 `~ #` 提示符** |
    //
    // 两份文件**逐字节相同**（md5 一致）、权限都是 755。差别只有 SELinux 标签：
    //
    // - `app_data_file`：`untrusted_app` **没有** `execute` 权限（Android 10+ 的 W^X）；
    // - `apk_data_file`：`untrusted_app` **有** `execute` 权限。
    //
    // 所以「拷到 filesDir」这条思路从根上就是错的：`setExecutable` 改的是 POSIX
    // 权限位，改不动 SELinux 策略。这与之前那个
    // `files/proot/bin/proot: error=13, Permission denied` 是**同一个原因**，
    // 当时只修了 proot 本体、没意识到 loader 也一样。
    //
    // 现在的做法：**直接用 nativeLibraryDir 里的原件**，不拷贝、不改名。
    // `PROOT_LOADER` 直接指向它，proot 就能 exec 它。
    //
    // ### 报错为什么完全指不到真因
    //
    // 整条链上 proot 只会说 `execve("/bin/sh"): Permission denied` —— 它说的是
    // guest 的 /bin/sh，**不是 loader**。于是排查方向永远是「rootfs 权限」或
    // 「proot 参数」。而 proot 秒退后 400ms 存活检查判定「启动后立即退出」，
    // 终端回退宿主 shell，用户最后看到 `/bin/sh: can't access tty`。
    // 三层转述，没有一句提到 loader。

    /**
     * 在 `nativeLibraryDir` 里挑出**实际存在的** loader 打包文件；都没有返回 null。
     *
     * ## 为什么两个名字都要认（2026-11-02 抽出来可测）
     *
     * 现行打包名是 `libproot-loader.so`（短横线）。历史上还有过
     * `libproot_loader.so`（下划线）这个别名 —— 早期构建产物里有它，
     * 用户手机上装的旧包也可能带它。两个名字都认，是为了让「升级 App
     * 但 loader 打包名改过」的设备不至于直接拿不到 loader。
     *
     * ## 为什么返回 null 是正确的「不修」结果
     *
     * 拿不到 loader 时调用方会退回旧行为（直接用 nativeLibraryDir 里的
     * proot 本体）。此时容器要么照常起来，要么报错信息与修之前完全一致 ——
     * **修一个坑不该制造新的坑**。抛异常反而是错的：那会让终端页整页崩。
     */
    internal fun pickPackagedLoader(nativeDir: String): File? =
        listOf(ProotCommand.LOADER_FILE_NAME, LEGACY_LOADER_FILE_NAME)
            .map { File(nativeDir, it) }
            .firstOrNull { it.isFile }

    /**
     * 把 proot 本体的**动态依赖**铺到 `filesDir/proot/lib/`，返回该目录。
     *
     * ## 为什么必须有这一步（2026-11-02 在模拟器上实测确认）
     *
     * proot 的 ELF 头里写着两个 `DT_NEEDED`：
     *
     * ```
     * libtalloc.so.2
     * libandroid-shmem.so
     * ```
     *
     * 而**执行**一个 nativeLibraryDir 里的二进制时，Android 的动态链接器
     * **不会**去那个目录找依赖（那个目录只对 `dlopen` 的 JNI 库生效）。
     * 实测：把 `libtalloc.so.2` 就放在同目录里，`exec` 仍报
     *
     * ```
     * CANNOT LINK EXECUTABLE ".../lib/x86_64/libproot.so":
     *   library "libtalloc.so.2" not found: needed by main executable
     * ```
     *
     * 更糟的是**名字也对不上**：APK 里打包的是 `libtalloc.so`（Android 只允许
     * `lib*.so` 进 `nativeLibraryDir`），而 proot 要的是 `libtalloc.so.2`。
     * 也就是说即便链接器肯搜那个目录，它找的也是 `libtalloc.so.2`，那里没有。
     *
     * 两条加起来的后果：proot 在真机上**起不来**，秒退后终端回退宿主 shell。
     * 这正是 ② 的另一半根因。
     *
     * ## 解法
     *
     * 按**运行时名字**铺到我们说了算的目录，再把 `LD_LIBRARY_PATH` 指过去。
     * 目录里只有这两个库，不掺 Android 的系统库 —— 所以不会出现
     * 「容器里的程序去加载 Android 的 libc 然后报 cannot locate symbol」那个老问题。
     *
     * @return 至少铺成一个时返回目录；一个都没有（老 APK）时返回 null，
     *   调用方退回旧行为 —— 修一个坑不该制造新的坑。
     */
    fun stageLibs(context: Context): File? = runCatching {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val libDir = File(context.filesDir, "proot/lib")
        if (!libDir.isDirectory && !libDir.mkdirs()) return@runCatching null
        var staged = 0
        for ((packagedName, runtimeName) in DEPENDENCY_LIBS) {
            val packaged = File(nativeDir, packagedName)
            if (!packaged.isFile) continue
            val dest = File(libDir, runtimeName)
            if (stale(dest, packaged)) packaged.copyTo(dest, overwrite = true)
            dest.setReadable(true, false)
            // 与 loader 同理：缺执行位时动态链接器会以「找不到库」的形式失败。
            dest.setExecutable(true, false)
            if (dest.isFile) staged++
        }
        if (staged == 0) null else libDir
    }.getOrNull()

    /**
     * proot 的动态依赖：**打包名 → 运行时名**。
     *
     * 打包名受 Android 限制必须是 `lib*.so`；运行时名由 proot 的 ELF
     * `DT_NEEDED` 决定，两者不一定相同（`libtalloc.so` 对 `libtalloc.so.2`）。
     *
     * 这里刻意用显式映射而不是「改个后缀」的规则：将来再加一个依赖时，
     * 名字对不对必须由人看一眼，而不是由一条猜出来的规则决定。
     */
    internal val DEPENDENCY_LIBS: List<Pair<String, String>> = listOf(
        "libtalloc.so" to "libtalloc.so.2",
        "libandroid-shmem.so" to "libandroid-shmem.so",
    )

    /** 目标缺失、长度不符、或比源文件旧 —— 三种情况都要重拷。 */
    internal fun stale(dest: File, source: File): Boolean =
        !dest.isFile || dest.length() != source.length() ||
            dest.lastModified() < source.lastModified()

    /** 历史打包名（下划线），见 [pickPackagedLoader]。 */
    internal const val LEGACY_LOADER_FILE_NAME: String = "libproot_loader.so"
}
