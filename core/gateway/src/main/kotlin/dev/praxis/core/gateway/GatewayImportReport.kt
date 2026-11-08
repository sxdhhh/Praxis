package dev.praxis.core.gateway

/**
 * 导入报告。**固定四段**：新增 / 更新 / 跳过 / 失败（文档 §8.6）。
 *
 * ## 为什么不合成一个「成功/失败」二元计数
 *
 * 导入最容易出的问题是「看起来成功了一部分，但用户不知道是哪部分」。
 * 四段各自可展开看明细，用户才能对上「我导了 8 个，怎么只多了 5 个」。
 */
data class GatewayImportReport(
    val added: List<Item> = emptyList(),
    val updated: List<Item> = emptyList(),
    val skipped: List<Item> = emptyList(),
    val failed: List<Item> = emptyList(),
) {
    /**
     * 一条明细。
     *
     * [reason] 只在跳过/失败、或需要提示（可能是重复账号）时非空 ——
     * 成功项没有理由可说，硬塞一句「成功」只是噪音。
     */
    data class Item(
        val id: String,
        val name: String,
        val reason: String? = null,
    )

    val total: Int get() = added.size + updated.size + skipped.size + failed.size
}
