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
        // loader 只被 proot `mmap` **读**，不需要可执行位；给上读权限即可。
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

    /** 目标缺失、长度不符、或比源文件旧 —— 三种情况都要重拷。 */
    internal fun stale(dest: File, source: File): Boolean =
        !dest.isFile || dest.length() != source.length() ||
            dest.lastModified() < source.lastModified()

    /** 历史打包名（下划线），见 [pickPackagedLoader]。 */
    internal const val LEGACY_LOADER_FILE_NAME: String = "libproot_loader.so"
}
