package dev.praxis.data.imagegen

import android.content.Context
import dev.praxis.core.imagegen.GallerySaveResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [dev.praxis.feature.chat.ImageGalleryPort] 的实现：把 base64 解回字节再交给 [GalleryWriter]。
 *
 * ## 为什么解码失败要单独说
 *
 * 消息里的图片是 base64 存的。真出现解不开的情况（数据被截断、旧版本写坏过），
 * 用户需要知道是「图坏了」而不是「相册写不进去」—— 两者的处置完全不同。
 */
class GalleryImagePort(
    private val context: Context,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : dev.praxis.feature.chat.ImageGalleryPort {

    override suspend fun saveImage(base64: String, mimeType: String): GallerySaveResult =
        withContext(Dispatchers.IO) {
            val bytes = runCatching {
                android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            }.getOrNull()
            if (bytes == null) {
                return@withContext GallerySaveResult.Failed("图片数据不是合法的 base64")
            }
            val extension = if (mimeType.contains("png")) "png" else "jpg"
            GalleryWriter.save(
                context = context,
                bytes = bytes,
                displayName = "praxis-" + clock() + "." + extension,
            )
        }
}
