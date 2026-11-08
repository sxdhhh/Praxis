package dev.praxis.core.imagegen

/**
 * 下载进度。
 *
 * [fraction] 为 null = 总量未知（不编一个假进度）。
 *
 * ## 为什么放在 core 而不是 :app
 *
 * 设置页（`:feature:settings`）要画进度条。若定义在 `:app`，依赖方向就反了
 * （feature 不能依赖 app）。它本身是纯数据。
 */
data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val fraction: Float?,
    val fileName: String,
)

/** 一次模型下载的状态（界面只认这一个流）。 */
sealed interface ImageDownloadState {

    data object Idle : ImageDownloadState

    /** 正在下载。[progress] 为 null = 还没收到第一块。 */
    data class Running(val modelId: String, val progress: DownloadProgress?) : ImageDownloadState

    data class Completed(
        val modelId: String,
        val spaceWasUnknown: Boolean,
        val leftoverZipName: String?,
    ) : ImageDownloadState

    data class Failed(val modelId: String, val reason: Reason, val detail: String) : ImageDownloadState {
        enum class Reason {
            /** 空间不足：一个字节都没写。 */
            InsufficientSpace,
            /** 传输/解压失败。 */
            Transport,
        }
    }

    /** 用户取消。不是错误，界面不该弹红字。 */
    data class Cancelled(val modelId: String) : ImageDownloadState
}
