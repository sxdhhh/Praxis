package dev.praxis.data.imagegen

import dev.praxis.core.imagegen.DownloadCleanup
import dev.praxis.core.imagegen.DownloadProgress
import dev.praxis.core.imagegen.ImageDownloadPlan
import dev.praxis.core.imagegen.SpaceCheck
import dev.praxis.core.imagegen.SpaceDecision
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import okhttp3.OkHttpClient
import okhttp3.Request

/** 一次下载的结果。四档对应四种不同的用户处置，不合并。 */
sealed interface DownloadResult {

    /**
     * 装好了。
     *
     * [spaceWasUnknown] 为 true 表示下载前**无法预估**所需空间（`approximateSize` 解析不出来），
     * 不是「预检通过」—— 这两种情况给用户的文案必须不同。
     */
    data class Completed(
        val spaceWasUnknown: Boolean,
        /**
         * 解压后删不掉的压缩包文件名；null = 删干净了。
         *
         * 不算失败（模型已经能用），但必须带出去说一声 —— 它会一直占着空间，
         * 下次空间预检也会把它算进去。静默忽略的话，用户看到的是一块不明去向的占用。
         */
        val leftoverZipName: String? = null,
    ) : DownloadResult

    /** 空间不足，一个字节都没写。 */
    data class InsufficientSpace(val requiredBytes: Long, val freeBytes: Long) : DownloadResult

    /** 传输/解压失败。残留已清理。 */
    data class Failed(val detail: String) : DownloadResult

    /**
     * 用户取消。残留已清理。
     *
     * 与 [Failed] 分开：取消不是错误，界面不该弹红字，也不该进「失败率」这类统计。
     */
    data object Cancelled : DownloadResult
}

/**
 * 模型下载器（文档 §8.3/§8.4 的落地）。
 *
 * ## 两种包型
 *
 * - **单 zip**：下到 `<id>.zip.part` → 解压到 `<id>_extract/` → 内容搬进模型目录 → 删压缩包；
 * - **多文件**：逐个下到 `<name>.part` → 完成后改名去掉 `.part` → 最后写标记文件。
 *
 * ## 为什么续传是「文件级」而不是「字节级」
 *
 * 与上游同一语义：每个 `.part` 从头写，重下前先删残留。字节级续传（`Range`）要处理
 * 「远端换了文件但没换地址」这类情况，收益不大而坑不少 —— 这里先照简单可靠的做，
 * 要字节级再单独加。
 *
 * ## 为什么 HTTP 是一个 lambda
 *
 * 本机编不了 Android 模块，这层如果直接 `newCall(...).execute()` 就**完全没法验证** ——
 * 而下载/解压/清理恰恰是最容易出错的地方。把「打开一个远端流」抽成函数后，
 * 单测可以喂 ByteArrayInputStream（包括现造的 zip），把解压、改名、清理、zip-slip 防护全跑一遍。
 */
class ModelDownloader(
    /** 打开远端文件的输入流（阻塞；调用方保证在 IO 线程）。 */
    private val openStream: (String) -> InputStream = defaultOpenStream(),
) {

    /**
     * 执行下载。阻塞函数，调用方负责放到 IO 线程。
     *
     * @param onProgress 每写一块回调一次；`fraction` 可能为 null（总量未知）。
     */
    fun download(
        plan: ImageDownloadPlan,
        /**
         * 下载源根地址（设置页可改，见 §7.2）。默认值保住单测与既有调用。
         *
         * 每次调用传入而不是构造时固定：用户换了镜像源，下一次下载就该走新源。
         */
        baseUrl: String = DEFAULT_BASE_URL,
        /**
         * 每写一块检查一次「要不要停」。
         *
         * 为什么不用协程取消：这里的读是**阻塞 IO**，`Job.cancel()` 打断不了卡在
         * `InputStream.read` 上的线程。用标志位让循环自己退出，才能保证清理真的跑完。
         *
         * 注意它排在 [onProgress] **之前**：`download(plan) { ... }` 这种尾随 lambda 写法
         * 绑的是最后一个参数，所以进度回调必须留在末尾。
         */
        shouldStop: () -> Boolean = { false },
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadResult {
        // ① 目录 + 预检。先清残留再判空间：否则上次失败的 .part 会让预检自相矛盾地报「不够」。
        if (!plan.targetDir.exists() && !plan.targetDir.mkdirs()) {
            return DownloadResult.Failed("无法创建模型目录：" + plan.targetDir.absolutePath)
        }
        cleanStale(plan)

        when (val space = SpaceCheck.decide(plan.requiredBytes, plan.targetDir.usableSpace)) {
            is SpaceDecision.Insufficient ->
                return DownloadResult.InsufficientSpace(space.requiredBytes, space.freeBytes)
            else -> Unit
        }
        val spaceWasUnknown = plan.requiredBytes == null

        // ② 逐个文件下载。
        val total = plan.requiredBytes
        var written = 0L
        try {
            for (target in plan.targets) {
                val part = File(plan.targetDir, target.localName + ImageDownloadPlan.PART_SUFFIX)
                if (part.exists() && !part.delete()) {
                    return DownloadResult.Failed("无法删除残留文件：" + part.name)
                }
                openStream(baseUrl + target.remotePath).use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            if (shouldStop()) {
                                cleanStale(plan)
                                return DownloadResult.Cancelled
                            }
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            written += read
                            onProgress(
                                DownloadProgress(
                                    bytesDownloaded = written,
                                    totalBytes = total,
                                    fraction = total?.takeIf { it > 0L }?.let { (written.toDouble() / it).toFloat().coerceIn(0f, 1f) },
                                    fileName = target.localName,
                                ),
                            )
                        }
                    }
                }
                // 去掉 .part 才算这个文件下好了（多文件包缺一个都不算装完）。
                val finalFile = File(plan.targetDir, target.localName)
                if (!part.renameTo(finalFile)) {
                    return DownloadResult.Failed("无法重命名：" + part.name)
                }
            }

            // ③ 单 zip：解压 + 搬家。
            if (plan.isZip) {
                val zip = File(plan.targetDir, plan.targets.first().localName)
                extractZip(zip, plan)
                // 删不掉不算失败（模型已经能用），但要把名字带出去 —— 见 Completed 的说明。
                val leftover = if (zip.delete()) null else zip.name
                return DownloadResult.Completed(spaceWasUnknown, leftover)
            } else if (plan.markerFileName.isNotEmpty()) {
                // ④ 多文件：写标记。这一步之后 [ImageModelCatalog.isInstalled] 才会认。
                File(plan.targetDir, plan.markerFileName).writeText("")
            }
            return DownloadResult.Completed(spaceWasUnknown)
        } catch (error: IOException) {
            cleanStale(plan)
            return DownloadResult.Failed(error.message ?: "下载失败")
        } catch (error: SecurityException) {
            cleanStale(plan)
            return DownloadResult.Failed(error.message ?: "解压被拒绝（zip 内含越界路径）")
        }
    }

    /**
     * 解压 zip 并把内容搬进模型目录。
     *
     * 两个必须处理的真实情况：
     * 1. **zip-slip**：条目名带 `..` 或绝对路径时，解压会写到目录外。这里逐条校验规范化路径。
     * 2. **包内多一层目录**：很多包是 `AnythingV5/xxx.mnn` 结构，直接搬会把模型目录变成
     *    `models/<id>/AnythingV5/xxx.mnn`，而引擎只认 `models/<id>/xxx.mnn`。
     */
    private fun extractZip(zip: File, plan: ImageDownloadPlan) {
        val temp = plan.extractTempDir
        if (temp.exists()) temp.deleteRecursively()
        if (!temp.mkdirs()) throw IOException("无法创建解压目录：" + temp.name)
        val tempPrefix = temp.canonicalPath + File.separator

        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val out = File(temp, entry.name)
                if (!out.canonicalPath.startsWith(tempPrefix)) {
                    throw SecurityException("zip 条目越界：" + entry.name)
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { sink -> input.copyTo(sink, BUFFER_SIZE) }
                }
                input.closeEntry()
            }
        }

        // 剥掉唯一的那层同名目录。
        val children = temp.listFiles().orEmpty()
        val contentRoot = if (children.size == 1 && children[0].isDirectory) children[0] else temp
        contentRoot.listFiles().orEmpty().forEach { child ->
            val dest = File(plan.targetDir, child.name)
            if (dest.exists()) dest.deleteRecursively()
            if (!child.renameTo(dest)) throw IOException("无法移动：" + child.name)
        }
        temp.deleteRecursively()
    }

    private fun cleanStale(plan: ImageDownloadPlan) {
        DownloadCleanup.staleFiles(plan).forEach { file -> if (file.exists()) file.delete() }
        DownloadCleanup.staleDirs(plan).forEach { dir -> if (dir.exists()) dir.deleteRecursively() }
    }

    companion object {
        /** GitHub raw/release 的默认源；换源时整条 baseUrl 替换即可。 */
        const val DEFAULT_BASE_URL: String = "https://github.com/"

        private const val BUFFER_SIZE = 64 * 1024

        private fun defaultOpenStream(): (String) -> InputStream {
            val client = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                // 大包下载：读超时 60s 是按「两块数据之间」算的，不是整包时长。
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
            return { url ->
                val response = client.newCall(Request.Builder().url(url).get().build()).execute()
                if (!response.isSuccessful) {
                    response.close()
                    throw IOException("HTTP " + response.code + "：" + url)
                }
                response.body?.byteStream() ?: run {
                    response.close()
                    throw IOException("响应没有 body：" + url)
                }
            }
        }
    }
}
