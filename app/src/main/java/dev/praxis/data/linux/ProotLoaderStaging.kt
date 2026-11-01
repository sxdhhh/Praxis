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

    /** proot 别名的文件名（与 [ProotCommand.launchArgs] 的约定配套）。 */
    private const val PROOT_ALIAS_NAME: String = "proot"

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
        // 打包名（短横线）与历史别名（下划线）都认，与旧实现保持同一套探测。
        val packaged = listOf(ProotCommand.LOADER_FILE_NAME, "libproot_loader.so")
            .map { File(nativeDir, it) }
            .firstOrNull { it.isFile }
            ?: return@runCatching null

        // ## 别名拷的是 **proot 本体**，不是 loader（2026-11-01 修正）
        //
        // 第一版这里写的是 `packaged.copyTo(alias)` —— 把 loader 拷成了
        // `proot`。那样别名实际是个 loader，execve 它必然失败，
        // 而失败又被外层 runCatching 静默吞掉，表现与「完全没修」一模一样。
        //
        // 别名的意义是：让 proot **以 `proot` 这个名字、从可写目录起跑**，
        // 这样 `/proc/self/exe` 的目录才会是 `filesDir/proot/bin/`，
        // 而那里正放着 `proot-loader`。所以别名必须拷 libproot.so。
        val prootBody = File(nativeDir, "libproot.so")
        if (!prootBody.isFile) return@runCatching null

        val binDir = File(context.filesDir, "proot/bin")
        if (!binDir.isDirectory && !binDir.mkdirs()) return@runCatching null
        val loader = File(binDir, ProotCommand.LOADER_RUNTIME_NAME)
        val alias = File(binDir, PROOT_ALIAS_NAME)

        // 新版本 APK 的文件会变，副本必须跟着换。比大小与时间戳，不做哈希：
        // 这是热路径（每次 exec 都会走一遍），十几 KB 级文件的哈希不划算。
        if (stale(loader, packaged)) packaged.copyTo(loader, overwrite = true)
        if (stale(alias, prootBody)) prootBody.copyTo(alias, overwrite = true)
        // filesDir 默认不给执行位，而 proot 必须能 execve 这个别名。
        alias.setExecutable(true, false)
        loader.setReadable(true, false)
        if (!alias.isFile) return@runCatching null
        loader
    }.getOrNull()

    /** 目标缺失、长度不符、或比源文件旧 —— 三种情况都要重拷。 */
    private fun stale(dest: File, source: File): Boolean =
        !dest.isFile || dest.length() != source.length() ||
            dest.lastModified() < source.lastModified()
}
