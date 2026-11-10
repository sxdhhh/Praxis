package dev.praxis.core.ai

/**
 * 消息里图片的压缩策略（文档 §5.18 的「图片压缩：开关 / 长边 / 质量 / 阈值」）。
 *
 * ## 这四项各自管什么
 *
 * | 字段 | 含义 |
 * |---|---|
 * | [enabled] | 总开关；关掉时**不做任何改写**（原图原样进消息） |
 * | [maxEdge] | 长边上限（像素）。照片通常 4000+，缩到这个值才像「消息里的图」 |
 * | [quality] | JPEG 质量（1..100） |
 * | [thresholdKb] | 小于这个体积的图**不动它** —— 再压也省不下多少，只是多掉一层画质 |
 *
 * ## 为什么阈值这一条必须存在
 *
 * 缩略图本身就只有几十 KB。对已经是小图的附件再走一遍解码/压缩，
 * 收益近似为零，而代价是**画质再掉一层**（JPEG 是又一次有损编码）。
 * 所以「小图不碰」不是优化，是正确性。
 */
data class ChatImageCompression(
    val enabled: Boolean = false,
    val maxEdge: Int = DEFAULT_MAX_EDGE,
    val quality: Int = DEFAULT_QUALITY,
    val thresholdKb: Int = DEFAULT_THRESHOLD_KB,
) {
    companion object {
        /** 与既有 `ImageThumbnail` 的硬编码值一致（512 / 80）。 */
        const val DEFAULT_MAX_EDGE: Int = 512
        const val DEFAULT_QUALITY: Int = 80
        const val DEFAULT_THRESHOLD_KB: Int = 256

        const val MIN_MAX_EDGE: Int = 128
        const val MAX_MAX_EDGE: Int = 4096
        const val MIN_QUALITY: Int = 20
        const val MAX_QUALITY: Int = 100
        const val MIN_THRESHOLD_KB: Int = 0
        const val MAX_THRESHOLD_KB: Int = 8192
    }

    /** 归一化：越界夹到边界（这些是有界选择，不是用户逐位输入的数字）。 */
    fun normalized(): ChatImageCompression = copy(
        maxEdge = maxEdge.coerceIn(MIN_MAX_EDGE, MAX_MAX_EDGE),
        quality = quality.coerceIn(MIN_QUALITY, MAX_QUALITY),
        thresholdKb = thresholdKb.coerceIn(MIN_THRESHOLD_KB, MAX_THRESHOLD_KB),
    )
}

/** 一次压缩决策。 */
sealed interface ChatImageCompressionDecision {
    /** 不动它（开关关着、或图已经够小）。 */
    data object Keep : ChatImageCompressionDecision

    /** 按这个长边与质量压。 */
    data class Compress(
        val maxEdge: Int,
        val quality: Int,
    ) : ChatImageCompressionDecision
}

/** 决策规则（纯函数）。 */
object ChatImageCompressionRules {

    /**
     * @param width/height 原图尺寸（像素）；未知传 0。
     * @param byteSize 原图字节数；未知传 0。
     */
    fun decide(
        policy: ChatImageCompression,
        width: Int,
        height: Int,
        byteSize: Long,
    ): ChatImageCompressionDecision {
        if (!policy.enabled) return ChatImageCompressionDecision.Keep
        val normalized = policy.normalized()
        val thresholdBytes = normalized.thresholdKb.toLong() * 1024L
        // 小图不碰：再压省不下多少，只是多掉一层画质（JPEG 是又一次有损编码）。
        if (byteSize > 0L && thresholdBytes > 0L && byteSize <= thresholdBytes) {
            return ChatImageCompressionDecision.Keep
        }
        // 尺寸未知时不猜：宁可不动，也不要按错误的长边去缩放。
        if (width <= 0 || height <= 0) return ChatImageCompressionDecision.Keep
        val longest = maxOf(width, height)
        if (longest <= normalized.maxEdge) {
            // 已经比长边还小：尺寸不动，但**体积仍可能超阈值**（高质 JPEG）。
            // 这时只降质量，不再改尺寸。
            return ChatImageCompressionDecision.Compress(
                maxEdge = longest,
                quality = normalized.quality,
            )
        }
        return ChatImageCompressionDecision.Compress(
            maxEdge = normalized.maxEdge,
            quality = normalized.quality,
        )
    }
}
