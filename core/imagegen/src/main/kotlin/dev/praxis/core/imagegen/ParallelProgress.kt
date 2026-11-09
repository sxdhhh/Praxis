package dev.praxis.core.imagegen

/**
 * 并发下载时的进度聚合（纯函数，便于单测）。
 *
 * ## 为什么要单独算
 *
 * 多个文件同时下时，每个文件的已下字节都在变。直接把某个文件的字节数当总进度会来回跳；
 * 全部加起来才是单调递增的。而「加起来」这件事必须**只有一个地方**做 ——
 * 每个下载线程各报各的，界面就会收到一会 3MB 一会 1MB。
 *
 * 调用方负责线程安全（用 ConcurrentHashMap 或者加锁取快照），这里只管算。
 */
object ParallelProgress {

    /**
     * 把「各文件已下字节」合成一条总进度。
     *
     * @param done 文件本地名 -> 已下字节。取快照时调用方保证一致性。
     * @param totalBytes 计划里的总字节；null = 下载前无法预估（分片包常见）。
     */
    fun aggregate(done: Map<String, Long>, totalBytes: Long?): DownloadProgress {
        val written = done.values.sum()
        return DownloadProgress(
            bytesDownloaded = written,
            totalBytes = totalBytes,
            // 总大小未知时 fraction 为 null —— 界面显示「已下 N MB」而不是一个假百分比。
            fraction = totalBytes?.takeIf { it > 0L }?.let { total ->
                (written.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
            },
            // 并发时没有「当前文件」这一个概念，取名字最大的那个只是为了让字段有确定值；
            // 界面在并发下不该用这个字段（见 DownloadProgress 的说明）。
            fileName = done.maxByOrNull { it.value }?.key.orEmpty(),
        )
    }
}
