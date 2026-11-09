package dev.praxis.feature.chat

/**
 * 把一张图存进系统相册的端口（生图文档 P2 的「长按保存到相册」）。
 *
 * ## 为什么是端口，而不是直接调
 *
 * 真正的写入（MediaStore + Context）在 `:app` 的 `GalleryWriter`，而模块方向是
 * `app -> feature`：`:feature:chat` 不能反向依赖。用端口让这一层只表达
 * 「我要把这张图存进相册」，具体怎么存由 :app 决定。
 *
 * ## 为什么返回值要有三种
 *
 * 「这台机器做不到」（Android 9 及以下）与「写盘失败」用户能做的事完全不同：
 * 前者要换方式（导出发送、或升级系统），后者可以重试。混成一个布尔的话，
 * 提示语只能写"保存失败"，而那是最没用的一句反馈。
 */
fun interface ImageGalleryPort {

    /**
     * @param base64 图片原始字节的 base64（**不含** `data:` 前缀）。
     * @param mimeType 如 `image/jpeg`。
     */
    suspend fun saveImage(
        base64: String,
        mimeType: String,
    ): dev.praxis.core.imagegen.GallerySaveResult
}
