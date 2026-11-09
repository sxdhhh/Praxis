package dev.praxis.data.imagegen

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dev.praxis.core.imagegen.GallerySaveResult

/**
 * 把一张图写进系统相册（文档 §7.1 的「自动存相册」）。
 *
 * ## 为什么只支持 Android 10+
 *
 * 10 起有了 `RELATIVE_PATH` + `IS_PENDING`，写自己应用的媒体条目**不需要任何权限**；
 * 9 及以下要先拿 `WRITE_EXTERNAL_STORAGE` 运行时权限、再写公共目录、再喊 MediaScanner。
 * 那是一条完整的权限流程（请求、拒绝后的说明、再次拒绝的处理），不该塞进「存张图」里。
 * 所以 9 及以下如实返回 [GallerySaveResult.Unsupported] ——
 * 不是失败，是「这个功能在你的系统版本上没接」。
 *
 * ## 关于文档里那句「已有 ImageSaver 同款」
 *
 * docs/PORTING-IMAGEGEN.md 第 276 行写着「图片保存到相册：已有（附件/截图相关）」。
 * 实际查过全仓：**没有**任何 MediaStore 写入路径（`data/attachment/` 只处理读）。
 * 所以这里是新写的，不是复用。
 */
object GalleryWriter {

    private const val FOLDER: String = "Praxis"

    /**
     * @param displayName 相册里显示的文件名（含扩展名）。
     */
    fun save(context: Context, bytes: ByteArray, displayName: String): GallerySaveResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return GallerySaveResult.Unsupported
        }
        val resolver = context.contentResolver
        var uri: android.net.Uri? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/" + FOLDER,
                )
                // 写完之前标 pending：否则相册可能在半个文件时就扫到它。
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                return GallerySaveResult.Failed("相册拒绝了这次写入（MediaStore 返回空 Uri）")
            }
            val stream = resolver.openOutputStream(uri)
            if (stream == null) {
                resolver.delete(uri, null, null)
                return GallerySaveResult.Failed("拿不到相册的输出流")
            }
            stream.use { output -> output.write(bytes) }

            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            GallerySaveResult.Saved(uri.toString())
        } catch (error: Exception) {
            // 失败时把半截条目删掉：留着会让相册里出现一张打不开的图。
            uri?.let { pending ->
                runCatching { resolver.delete(pending, null, null) }
            }
            GallerySaveResult.Failed(error.message ?: "写入相册失败")
        }
    }
}
