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
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val packaged = pickPackagedLoader(nativeDir)
            ?: return@runCatching null

        val binDir = File(context.filesDir, "proot/bin")
        if (!binDir.isDirectory && !binDir.mkdirs()) return@runCatching null
        val loader = File(binDir, ProotCommand.LOADER_RUNTIME_NAME)

        // 新版本 APK 的文件会变，副本必须跟着换。比大小与时间戳，不做哈希：
        // 这是热路径（每次 exec 都会走一遍），十几 KB 级文件的哈希不划算。
        if (stale(loader, packaged)) packaged.copyTo(loader, overwrite = true)
        // ## ⚠️ 必须给执行位（2026-11-02 在模拟器上实测确认）
        //
        // 我一度以为「loader 只被 proot mmap 读，不需要 +x」，于是只调了
        // `setReadable`。结果是 644。实测（API 30，x86_64 proot 5.1.107）：
        //
        // ```
        // chmod 644 -> proot error: execve("/bin/sh"): Permission denied   FAIL
        // chmod 755 -> RUN_OK                                                PASS
        // ```
        //
        // 来回切了 4 次，4 次都一致 —— 执行位就是那个开关。
        //
        // ## 为什么这个错误特别难查
        //
        // 报错是 `execve("/bin/sh"): Permission denied` —— 它说的**不是** loader，
        // 而是 guest 的 /bin/sh。于是第一反应永远是「rootfs 权限不对」或
        // 「proot 参数错」，不会想到「loader 缺执行位」。
        //
        // 而 proot 秒退之后，`ProotTerminalSessionPort.create` 的 400ms 存活检查
        // 会判定「启动后立即退出」，终端回退到宿主 shell —— 用户看到的是
        // `/bin/sh: can't access tty`。整条链上**没有一句提到 loader**。
        loader.setExecutable(true, false)
        loader.setReadable(true, false)
        if (!loader.isFile) return@runCatching null
        //
        // ## 这里曾经还拷过一份 `proot` 别名 —— 已删除（2026-11-02）
        //
        // 目的本来是让 proot 从可写目录起跑、好在 `/proc/self/exe` 的目录里
        // 找到 loader。真机报 `Cannot run program ".../files/proot/bin/proot":
        // error=13, Permission denied` —— **Android 10 起禁止从应用可写数据
        // 目录执行文件**，`setExecutable` 没用，那是 SELinux 策略不是权限位。
        //
        // 结论：可执行的位置只有 `nativeLibraryDir`（只读），loader 留在
        // `filesDir` 靠 `PROOT_LOADER` 显式指过去就够（loader 只被 mmap 读）。
        // 详见 [ProotCommand.launchArgs] 的 KDoc。
        loader
    }.getOrNull()

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
