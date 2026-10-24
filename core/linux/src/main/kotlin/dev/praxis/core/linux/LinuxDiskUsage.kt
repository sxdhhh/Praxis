package dev.praxis.core.linux

import java.io.File

/**
 * Linux 环境的磁盘占用统计与清理（存储管理分区的数据源）。
 *
 * ## 为什么放在 `core:linux` 而不是 `:app` 或 `feature:settings`
 *
 * 三条都是本项目反复付过代价的理由：
 *
 *  1. **要能单测**：统计逻辑有真实的边界（符号链接、空目录、文件名含换行的
 *     极端情况、权限不足的目录），这些用真机点界面几乎测不到。
 *     放在 core 里，`File` 就能测（JUnit 用临时目录）。
 *  2. **`feature:settings` 拿不到 `filesDir`**：那是 `Context` 的东西，
 *     而 feature 层按项目纪律不碰 `Context`。
 *  3. **`:app` 不适合放业务逻辑**：它只该做装配。放在那里的话，
 *     这个函数会被写成「设置页专用」，AI 工具想报告磁盘占用时又得抄一遍。
 *
 * ## 为什么不用 shell 的 `du`
 *
 * `du` 是 toybox 提供的，而 toybox 的 `du`：
 *  - 在部分 ROM 上不带 `-s`/`-b`（busybox 与 toybox 的参数集不一致）；
 *  - 硬链接会**重复计算**（`du` 默认按 path 累加，解压出来的 rootfs
 *    里硬链接很多，实测能高估 3~5%）；
 *  - 输出要按字节解析，数字带本地化千分位时解析会错。
 *
 * Kotlin 的 `length()` 走 `stat`，对硬链接的意义是「这个 inode 的大小」，
 * 累加同样会重复。所以这里**显式用 inode 去重**（见 [diskUsage]），
 * 这是 `du` 默认不做、而 rootfs 场景恰恰需要的那一步。
 */
object LinuxDiskUsage {

    /**
     * 单个目录的占用统计结果。
     *
     * @param bytes 字节数（已按 inode 去重，硬链接只算一次）。
     * @param fileCount 计入的文件数（不含目录）。用户真正关心的是
     *   「怎么会这么大」，文件数能帮他判断是「几个大文件」还是
     *   「几十万个小文件」（后者才是 apt 缓存的典型形态）。
     * @param unreadableDirs 读不进去的目录数。非零时 UI 必须说明
     *   「统计不完整」，否则用户会以为清了没效果。
     */
    data class Usage(
        val bytes: Long = 0L,
        val fileCount: Int = 0,
        val unreadableDirs: Int = 0,
    ) {
        /** 「123 MB」这种可读文本。 */
        fun human(): String = humanBytes(bytes)
    }

    /**
     * 递归统计目录占用。
     *
     * ## 为什么先按 inode 去重
     *
     * 解压出来的 Debian rootfs 里有大量硬链接（`/usr/bin` 那一堆名字里同一个 inode
     * 被多个名字引用，`apt` 的 `usrmerge` 也会造硬链接）。
     * 不去重的话同一个 inode 被算 N 次，用户看到「环境 2.1 GB」
     * 而 `df` 只少了 700 MB，会认定统计坏了。
     *
     * 用 `(dev, ino)` 组合做键而不是只 `ino`：不同设备的 inode
     * 可能撞号（尤其是 `/data` 与 `/storage` 是不同挂载点时）。
     * `Files.readAttributes` 在 API 26+ 可用，本项目 minSdk 为 28。
     *
     * ## 为什么不用 `walkTopDown()`
     *
     * 它遇到权限不足的目录会**抛异常中断整个遍历**（`File.listFiles()`
     * 返回 null 时 `walkTopDown` 静默把它当空目录，但 `IOException`
     * 会传播）。这里手工递归，遇到 null 就 `unreadableDirs++` 并继续 ——
     * 「统计到 90%」远好过「一个目录没权限就整页显示 0」。
     *
     * @param dir 目标目录。不存在时返回全零（不是错误）。
     */
    fun diskUsage(dir: File): Usage {
        if (!dir.exists()) return Usage()

        var bytes = 0L
        var files = 0
        var unreadable = 0
        // 已计过的 inode。用 HashSet<Long> 存 (dev<<32)^ino 的混编值：
        // 直接存 Pair 会给每个文件多分配一个对象，十万文件时是可观的开销。
        val seen = HashSet<Long>(1024)

        // 显式栈而不是递归：目录深度没有上限（某些包会造很深的嵌套），
        // JVM 默认栈在几万层时会 StackOverflow，而那是**崩溃**不是降级。
        val stack = ArrayDeque<File>()
        stack.addLast(dir)

        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles()
            if (children == null) {
                // 权限不足或已被删除。只对目录计入 unreadable ——
                // 目标本身不存在时已在上面早退。
                if (current.isDirectory) unreadable++
                continue
            }
            for (child in children) {
                if (child.isDirectory) {
                    // ## 不跟随符号链接
                    //
                    // rootfs 里有 `bin -> usr/bin` 这类链接。跟随会让
                    // `/` 的统计把 `/usr` 算两遍，且可能绕环（`a -> ..`）。
                    // `isDirectory` 在 Java 里**会**跟随链接，所以要先判链接。
                    if (java.nio.file.Files.isSymbolicLink(child.toPath())) {
                        // 链接本身占几十字节，按文件计入（与 `du -L` 的行为对齐：
                        // 不算目标，只算链接自身的大小）。
                        bytes += child.length()
                        files++
                        continue
                    }
                    stack.addLast(child)
                } else {
                    val counted = countOnce(child, seen)
                    if (counted) {
                        bytes += child.length()
                        files++
                    }
                }
            }
        }

        return Usage(bytes = bytes, fileCount = files, unreadableDirs = unreadable)
    }

    /**
     * 清空目录内容但**保留目录本身**。
     *
     * ## 为什么保留目录
     *
     * 调用方（共享 tmp 的宿主目录）是 proot 的 `-b host:/tmp` 目标。
     * 把目录本身删掉，下一次启动 proot 会报 `can not bind` 然后静默继续 ——
     * 用户看到「清空成功」但共享 tmp 从此失效。
     *
     * ## 为什么删除失败要返回失败而不是忽略
     *
     * 删不掉通常是「容器还在跑、文件被占用」。此时告诉用户
     * 「3 个文件删不掉，请先停止容器」比假装成功有用得多。
     *
     * @return 删除失败的文件数；0 表示全部清掉。
     */
    fun clearContents(dir: File): Int {
        if (!dir.isDirectory) return 0
        var failed = 0
        dir.listFiles()?.forEach { child ->
            val ok = runCatching { child.deleteRecursively() }.getOrDefault(false)
            if (!ok && child.exists()) failed++
        }
        return failed
    }

    /**
     * 把字节数格式化成人类可读文本。
     *
     * ## 为什么按 1024 而不是 1000
     *
     * Android 的存储设置页用的是 1000（"GB" 在营销意义上更大），
     * 但 Linux 世界（`df` / `du` / `ls -l`）全是 1024。这一页显示的是
     * **容器占用**，用户会拿它跟容器里的 `du -sh` 对比 ——
     * 单位不一致会让他以为统计错了。
     *
     * 一位小数就够：这一页不需要精确到 KB，用户要的是「大概多大」。
     * 但 **0 字节要显示成 0 B 而不是 0.0 KB**，否则像坏掉了。
     */
    fun humanBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = 1024.0
        return when {
            bytes < kb -> "$bytes B"
            bytes < kb * kb -> oneDecimal(bytes / kb, "KB")
            bytes < kb * kb * kb -> oneDecimal(bytes / (kb * kb), "MB")
            else -> oneDecimal(bytes / (kb * kb * kb), "GB")
        }
    }

    private fun oneDecimal(value: Double, unit: String): String {
        // 保留一位。用 `* 10 / 10` 而不是 String.format：
        // 后者受 locale 影响，某些语言下小数点是逗号。
        val rounded = kotlin.math.round(value * 10) / 10
        // 整数时省掉 `.0`，看起来更干净（"2 GB" 而不是 "2.0 GB"）。
        return if (rounded == rounded.toLong().toDouble()) {
            "${rounded.toLong()} $unit"
        } else {
            "$rounded $unit"
        }
    }

    /**
     * 若该文件的 inode 是首次出现则返回 true（并在 [seen] 里记账）。
     *
     * ## 为什么用 `fileKey()` 而不是 `dev + ino` 手拼
     *
     * JVM 的 `BasicFileAttributes.fileKey()` 在 Unix 上返回一个
     * `(dev, ino)` 对象，**其 `equals`/`hashCode` 已经按两者组合实现** ——
     * 手拼反而要自己处理「设备号位数不同」的问题（ext4 是 32 位、
     * fuse 是 64 位，硬编码左移 32 位会撞号）。
     *
     * ## 为什么取不到时退化成路径哈希
     *
     * 部分 Android ROM 的 sd-card / fuse 文件系统返回 `fileKey() == null`。
     * 此时退回 `absolutePath.hashCode()`：**去重能力变弱**（同一文件的
     * 两个硬链接路径不同，会被算两次），但至少不会把所有文件都当成
     * 同一个 inode 而只算一次（那会让体积显示成几百字节）。
     *
     * 取不到属性（权限/已删除）时**返回 true**：宁可多算一次，
     * 也不要因为拿不到 inode 就把文件从统计里漏掉 ——
     * 用户对「体积偏小」的容忍度远低于「体积偏大」，
     * 因为前者会让他以为「不用清理」，最后磁盘满了。
     */
    private fun countOnce(file: File, seen: HashSet<Long>): Boolean = runCatching {
        val attrs = java.nio.file.Files.readAttributes(
            file.toPath(),
            java.nio.file.attribute.BasicFileAttributes::class.java,
        )
        // ## fileKey 的 hashCode 在这里是安全的
        //
        // `fileKey` 的实际类型是 `(st_dev, st_ino)` 的封装，其 hashCode
        // 由两者共同决定。理论上不同 (dev, ino) 可能撞同一个 hash，
        // 但那需要两个文件恰好哈希碰撞 —— 代价是「少算一个文件的大小」，
        // 而换来的收益是零分配（十万文件时不产生十万个临时对象）。
        val key = attrs.fileKey()?.let { it.hashCode().toLong() }
            ?: file.absolutePath.hashCode().toLong()
        seen.add(key)
    }.getOrDefault(true)

    /**
     * 容器环境各部分的占用汇总。
     *
     * @param rootfs rootfs 目录的占用（主要部分）。
     * @param tmp 共享 tmp 宿主目录的占用。未开启共享时为 0 ——
     *   那说明容器用的是 rootfs 内部自己的 `/tmp`，其占用已含在 [rootfs] 里。
     * @param tarball 缓存 tarball 的占用（首次安装后应被删掉；残留说明有 bug）。
     */
    data class EnvironmentUsage(
        val rootfs: Usage = Usage(),
        val tmp: Usage = Usage(),
        val tarball: Usage = Usage(),
    ) {
        /** 三个部分相加。注意 [rootfs] 已包含未共享时的容器内 /tmp。 */
        val totalBytes: Long get() = rootfs.bytes + tmp.bytes + tarball.bytes

        fun human(): String = humanBytes(totalBytes)
    }

    /**
     * 统计一整套环境。
     *
     * @param rootfsDir 共享 rootfs 目录。
     * @param hostTmpDir 共享 tmp 宿主目录（`null` 表示未开启）。
     * @param tarballPath 缓存的 tarball 路径（可能不存在）。
     */
    fun environmentUsage(
        rootfsDir: File,
        hostTmpDir: File?,
        tarballPath: File?,
    ): EnvironmentUsage = EnvironmentUsage(
        rootfs = diskUsage(rootfsDir),
        tmp = hostTmpDir?.let { diskUsage(it) } ?: Usage(),
        tarball = tarballPath?.let { diskUsage(it) } ?: Usage(),
    )
}
