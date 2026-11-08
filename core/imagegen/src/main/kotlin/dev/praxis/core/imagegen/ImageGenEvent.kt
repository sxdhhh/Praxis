package dev.praxis.core.imagegen

/**
 * `/generate` 流里的一条事件（文档 §3.3 的两种消息）。
 *
 * 只有两种：进度与完成。**没有「错误事件」** —— 引擎侧的失败表现为流断开或 HTTP 状态码，
 * 由调用方映射成 [ImageGenError]（文档 §5.2）。
 */
sealed interface ImageGenEvent {

    /**
     * 进度帧。
     *
     * @param value 0~1。引擎实测给的是 0~1 的浮点；读的时候会夹到区间内
     *   （见 [NdjsonReader]），所以这里保证在范围内。
     * @param previewJpegBase64 每步预览的 base64 JPEG。**可为 null**：
     *   文档 §3.3 明确 `image` 是可选的（预览关掉时引擎不推帧）。
     */
    data class Progress(
        val value: Float,
        val previewJpegBase64: String?,
    ) : ImageGenEvent

    /**
     * 完成帧：最终图 + 引擎实际用的 seed 与尺寸。
     *
     * @param bytes 解码后的图字节。`output_format=jpeg`（见 [ImageGenRequest]），
     *   所以是 JPEG；解码失败时是空数组（见 [NdjsonReader] 的说明）。
     * @param seed 实际种子 —— 请求里省略 seed 时，这是唯一能复现结果的东西。
     */
    data class Complete(
        val bytes: ByteArray,
        val seed: Long,
        val width: Int,
        val height: Int,
    ) : ImageGenEvent {
        // data class 自动生成的 equals 比的是数组引用，两张内容相同的图会被判成不等。
        // 单测与「重试得到同一张图」的判断都要按内容比，所以显式实现。
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Complete) return false
            return seed == other.seed &&
                width == other.width &&
                height == other.height &&
                bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + seed.hashCode()
            result = 31 * result + width
            result = 31 * result + height
            return result
        }
    }
}
