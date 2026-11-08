package dev.praxis.feature.chat.widget

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 把磁盘上的原图缩成消息里用的缩略图（docs/PORTING-IMAGEGEN.md §6.3）。
 *
 * ## 为什么消息里放缩略图、原图留在磁盘
 *
 * `ChatMessage.images` 的既有约定是 base64（用户发图就是这么存的），而生成图的原图
 * 有 1~2 MB —— 塞进每条历史消息既拖慢列表也撑大数据库。缩略图长边 ≤512、JPEG q80
 * 通常只有 30~60 KB，与用户上传图同款观感；要看原图可以点开（路径在工具结果里）。
 *
 * ## 为什么失败返回 null 而不是抛异常
 *
 * 这是**展示**环节：缩图失败不该让整轮对话失败。返回 null 时气泡就不挂图，
 * 用户仍能在工具结果里看到文件路径（那不是静默失败，是降级到「只有路径」）。
 */
object ImageThumbnail {

    private const val MAX_EDGE = 512
    private const val QUALITY = 80

    /** @return base64（无 `data:` 前缀）；文件不存在/解码失败返回 null。 */
    fun toBase64Jpeg(path: String): String? {
        val file = File(path)
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            // 先按整数倍取样，再精确缩放：直接 decode 全尺寸在 1024×1024 的原图上
            // 会吃掉几 MB 堆，列表滚动时容易 OOM。
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        val decoded = BitmapFactory.decodeFile(path, options) ?: return null
        val scaled = scaleToMaxEdge(decoded)
        if (scaled !== decoded) decoded.recycle()

        val bytes = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, bytes)
        scaled.recycle()
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }

    /** 取样倍数：让解码后的长边仍不小于 [MAX_EDGE]，避免过度降质。 */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= MAX_EDGE) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleToMaxEdge(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_EDGE) return bitmap
        val ratio = MAX_EDGE.toFloat() / longest
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}
