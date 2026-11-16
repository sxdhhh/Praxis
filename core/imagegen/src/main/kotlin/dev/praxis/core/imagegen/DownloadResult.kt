package dev.praxis.core.imagegen

/**
 * 一次下载的结果。四档对应四种不同的用户处置，不合并。
 *
 * 放在 core 而不是 :app 的原因：**「哪个终态才算这次的终态」是一条规则，不是一个实现细节**。
 * 并发下载时它是唯一会出错的地方（见 [DownloadTerminal]），而 :app 的单测在 Windows 上跑不起来，
 * 规则留在这里才能被本地验证。
 */
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
 * 把「每个文件的终态」收敛成「整次下载的终态」。
 *
 * ## 为什么不能直接取「第一个非 null」
 *
 * 并发下载时，一个文件失败会把 `abort` 置真（半装好的模型没用，继续下只是白耗流量）。
 * **同一时刻已在跑的那个文件**读到 `abort` 就返回 [DownloadResult.Cancelled]，
 * 于是结果列表是 `[Cancelled, Failed]` 或 `[Failed, Cancelled]` —— 顺序由线程池调度决定。
 * 取第一个非 null 会有一半概率把「传输失败」报成「用户取消」：
 * 用户没点取消却看到「已取消」，且真实原因（超时/404/磁盘写失败）被吞掉。
 *
 * 所以这里的规则是**先按语义排序，再按顺序取**，并且把「用户意图」单列一档：
 * - 用户点了取消（[pick] 的 `userCancelled`）：取消优先。此时别的文件的失败多半是取消的连带结果；
 * - 用户没点取消：**真失败优先**。`Cancelled` 在没有用户取消时只可能是「被同伴中止」，不是终态原因；
 * - 两者都没有：null = 全部成功。
 */
object DownloadTerminal {

    /**
     * @param results 每个 target 的终态；null = 该文件成功。
     * @param userCancelled 用户是否点了取消（读 `shouldStop()`，不是读 `abort`）。
     */
    fun pick(results: List<DownloadResult?>, userCancelled: Boolean): DownloadResult? {
        if (!userCancelled) {
            results
                .firstOrNull { it is DownloadResult.Failed || it is DownloadResult.InsufficientSpace }
                ?.let { return it }
        }
        results.firstOrNull { it is DownloadResult.Cancelled }?.let { return it }
        return results.firstOrNull { it != null }
    }
}
