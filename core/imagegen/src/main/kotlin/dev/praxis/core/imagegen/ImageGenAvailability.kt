package dev.praxis.core.imagegen

/**
 * 本机能不能跑本地生图（文档 §9 的「设备门控」，P4）。
 *
 * ## 只判能确证的事
 *
 * 1. **不是 arm64** → 引擎二进制跑不了。这是**确定**的（它只有 arm64 构建）。
 * 2. **SoC 型号认不出档位** → NPU 包不适用，只能走 CPU 包（`min` 档）。
 *    这不是「不支持」，而是「能用但慢」—— 所以单独一档，不并进 Unsupported。
 *
 * ## 为什么不写「哪些 SoC 一定能跑」的白名单
 *
 * 我没有那份清单的权威来源。编一份出来，会让**本来能用的设备被挡在门外** ——
 * 而门控的目的恰恰相反：把明确不行的说清楚，其余一律放行（让引擎自己去试）。
 */
sealed interface ImageGenAvailability {

    /** arm64 且认得出芯片档位：可以下 NPU 包。 */
    data object NpuCapable : ImageGenAvailability

    /** arm64，但芯片型号认不出档位：只能走 CPU 包，速度明显更慢。 */
    data class CpuOnly(val soc: String?) : ImageGenAvailability

    /** 跑不了（目前只有一种原因：不是 arm64）。 */
    data class Unsupported(val reason: String) : ImageGenAvailability
}

/** 门控判定（纯函数，便于单测）。 */
object ImageGenAvailabilityRules {

    fun decide(abis: List<String>, socModel: String?): ImageGenAvailability {
        // ABI 判定用 contains：真机给的是 `arm64-v8a`，而不同 ROM 的写法大同小异，
        // 精确相等会把某些设备误判成不支持。
        val hasArm64 = abis.any { it.contains("arm64", ignoreCase = true) }
        if (!hasArm64) {
            return ImageGenAvailability.Unsupported(
                "生图引擎只有 arm64 构建，本机 ABI 是 " +
                    (abis.joinToString("/").ifBlank { "未知" }),
            )
        }
        return if (ChipsetTier.suffixFor(socModel) == null) {
            ImageGenAvailability.CpuOnly(socModel)
        } else {
            ImageGenAvailability.NpuCapable
        }
    }
}

/**
 * 「降一档尺寸」（文档 §12：OOM 时自动降一档重试一次）。
 *
 * ## 为什么是「减半并向下取到 64 的倍数」
 *
 * 扩散模型的尺寸必须是 64 的倍数（引擎自己也会拒绝非倍数）。减半是唯一能保证
 * **面积明显变小**又不需要知道模型档位的做法 —— 而 64 对齐保证结果仍然合法。
 *
 * 两个方向都还能缩时一起缩；只有一个方向能缩时只缩它（比如 512×256 → 256×256）。
 * 都到下限（256）就返回 null：**不能再降**，上层据此直接报错而不是无限重试。
 */
object ImageGenDownscale {

    /** 引擎接受的最小边长（与 [ImageGenParamsRules] 的下限一致）。 */
    const val MIN_DIMENSION: Int = 256

    private const val ALIGN: Int = 64

    fun smaller(width: Int, height: Int): Pair<Int, Int>? {
        val downWidth = nextDown(width)
        val downHeight = nextDown(height)
        return when {
            downWidth != null && downHeight != null -> downWidth to downHeight
            downWidth != null -> downWidth to height
            downHeight != null -> width to downHeight
            else -> null
        }
    }

    private fun nextDown(value: Int): Int? {
        if (value <= MIN_DIMENSION) return null
        val halved = (value / 2 / ALIGN) * ALIGN
        val clamped = halved.coerceAtLeast(MIN_DIMENSION)
        return clamped.takeIf { it < value }
    }
}

/**
 * 从失败文本里认出「显存/内存不足」。
 *
 * ## 为什么要靠关键词
 *
 * 引擎与驱动对 OOM 的措辞不统一（`out of memory` / `failed to allocate` / `OOM`），
 * 而且它是以 HTTP 500 + 正文的形式回来的 —— 没有稳定的错误码可判。
 * 关键词法会漏判（漏判的代价只是「这次不自动降档」），但不会误判成别的错误。
 */
object ImageGenErrorClassifier {

    private val OOM_MARKERS = listOf(
        "out of memory",
        "outofmemory",
        "oom",
        "failed to allocate",
        "allocation failed",
        "insufficient memory",
        "memory allocation",
        "显存",
        "内存不足",
    )

    fun isOutOfMemory(text: String?): Boolean {
        val lower = text?.lowercase() ?: return false
        return OOM_MARKERS.any { marker -> lower.contains(marker) }
    }
}
