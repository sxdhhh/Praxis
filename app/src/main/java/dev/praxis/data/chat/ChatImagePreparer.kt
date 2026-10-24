package dev.praxis.data.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import dev.praxis.core.ai.ChatImage
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * 把相册/文件选择器给来的 `Uri` 变成可发送的 [ChatImage]。
 *
 * ## 为什么必须在这里压缩，而不是原图直送
 *
 * 三个硬约束叠在一起：
 *
 * 1. **base64 会膨胀 33%**。一张 4MB 的手机原图变成约 5.5MB 的字符串，
 *    而它要进 `ChatState`（内存）、进请求体（网络）、若落库还会进数据库。
 * 2. **上游有请求体上限**。多数 OpenAI 兼容网关在 10~20MB 会直接 413，
 *    而我方还没算上文本历史。
 * 3. **视觉模型的有效分辨率远低于手机原图**。主流模型会把图缩到短边
 *    768~1024 再切 patch —— 送 4000px 的原图，多出来的像素在服务端被丢弃，
 *    只白白吃带宽与时间。
 *
 * ## 为什么用 `BitmapFactory` 的 `inSampleSize`
 *
 * 两趟法最稳：
 *  - 第一趟 `inJustDecodeBounds = true` 只读尺寸（几乎不分配像素内存）；
 *  - 第二趟按采样率真解码。
 *
 * `inSampleSize` 只能取 2 的幂，误差最多差一倍，所以第二趟之后再用
 * `createScaledBitmap` 精修到目标长边。
 *
 * ## 输出格式：JPEG
 *
 * 同等视觉质量下 JPEG 比 PNG 小一个数量级（照片类内容）。代价是透明通道
 * 丢失 —— 对「发给模型看」这个用途，透明像素本来也会被服务端铺平，
 * 不是有效信息。
 */
object ChatImagePreparer {

    /** 目标长边像素。1024 是「视觉模型不浪费、肉眼仍清晰」的折中点。 */
    private const val TARGET_LONG_EDGE = 1024

    /** JPEG 质量。85 在照片上肉眼接近无损，体积约为 95 的 60%。 */
    private const val JPEG_QUALITY = 85

    /** 单图最大字节数（编码后）。超过就降一档质量重编。 */
    private const val MAX_BYTES = 1_200_000

    /**
     * 读取并压缩一张图片。
     *
     * @return null 表示读不到 / 不是图片。调用方应给用户提示，而不是静默丢弃 ——
     *   「点了选图什么都没发生」是最难排查的用户反馈。
     */
    fun prepare(context: Context, uri: Uri): ChatImage? {
        // 用一层显式 try 而不是 `runCatching { ... return null }`：
        // 后者在 lambda 里的 `return` 是非局部返回，读起来像是「提前退出
        // runCatching」而实际退出的是 `prepare`，极易误读。
        return try {
            val resolver = context.contentResolver

            // ① 第一趟：只读边界。
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            // ② 算采样率：除以 2 后仍不小于目标长边就继续翻倍。
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= TARGET_LONG_EDGE) {
                sample *= 2
            }

            // ③ 第二趟：真解码。
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            // ④ 精修到目标长边。
            val scaled = scaleDown(decoded)
            if (scaled !== decoded) decoded.recycle()

            // ⑤ 编码。明显超标时降质量重编一次（最多两档，不递归）。
            var bytes = compress(scaled, JPEG_QUALITY)
            if (bytes.size > MAX_BYTES) {
                bytes = compress(scaled, 60)
            }
            scaled.recycle()

            ChatImage(
                mimeType = "image/jpeg",
                base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            )
        } catch (_: Exception) {
            // 任何失败都归成「这张图读不了」：调用方据此提示用户，
            // 而不是让选图把整个页面崩掉（比如超大图 OOM、URI 权限失效）。
            null
        }
    }

    /** 长边超过目标就等比缩小；否则原样返回（引用相同，调用方据此决定是否 recycle）。 */
    private fun scaleDown(src: Bitmap): Bitmap {
        val longEdge = max(src.width, src.height)
        if (longEdge <= TARGET_LONG_EDGE) return src
        val ratio = TARGET_LONG_EDGE.toFloat() / longEdge
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}