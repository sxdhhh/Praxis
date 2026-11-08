package dev.praxis.core.gateway

/**
 * 状态的界面文案（文档 9.0 的三态灯 + 冷却倒计时）。
 *
 * 放在 core:gateway 而不是 UI 层：这些字符串要被单测钉住。
 * 「冷却中」后面跟不跟倒计时、倒计时怎么取整，都属于用户能直接看到的语义，
 * 值得像逻辑一样被验证。
 */
object GatewayStatusText {

    const val NORMAL: String = "正常"
    const val COOLING: String = "冷却"
    const val DISABLED: String = "已停用"

    /** 状态灯上的短文案。 */
    fun label(status: GatewayAccountStatus): String = when (status.state) {
        GatewayAccountState.Normal -> NORMAL
        GatewayAccountState.Cooling -> COOLING
        GatewayAccountState.Disabled -> DISABLED
    }

    /**
     * 冷却原因说明。非冷却态返回 null（界面不显示这一行）。
     *
     * 说明里带上原因，因为用户要采取的动作不同：退避等一会儿就行、熔断说明
     * 上游一直在错、额度受限得等重置或换号。
     */
    fun reasonText(status: GatewayAccountStatus): String? = when (status.reason) {
        GatewayCooldownReason.Backoff -> "上游连续出错，暂时退避"
        GatewayCooldownReason.Breaker -> "熔断中，多次失败后自动停用一段时间"
        GatewayCooldownReason.QuotaLimit -> "额度用尽，等上游重置"
        null -> null
    }

    /**
     * 倒计时文案。
     *
     * ## 取整方向是有讲究的
     *
     * 向上取整（还有 1 秒也说「不到 1 分钟」是另一回事）：如果向下取整，
     * 「还有 12 分 59 秒」会显示成「还有 12 分钟」，用户按 12 分钟去算就会早
     * 一分钟点回去。宁可说多不少。
     *
     * @return null 表示没有冷却（界面不显示倒计时）
     */
    fun countdownText(untilMillis: Long?, nowMillis: Long): String? {
        val remaining = (untilMillis ?: return null) - nowMillis
        if (remaining <= 0L) return null

        val minutes = (remaining + 59_999L) / 60_000L
        if (minutes < 1L) return "不到 1 分钟"
        if (minutes < 60L) return "还有 " + minutes + " 分钟"

        val hours = (minutes + 59L) / 60L
        return "还有 " + hours + " 小时"
    }

    /** 受限模型的提示（部分模型被限时用）。没有则为 null。 */
    fun limitedModelsText(status: GatewayAccountStatus): String? {
        if (status.limitedModels.isEmpty()) return null
        val head = status.limitedModels.first()
        return if (status.limitedModels.size == 1) {
            head + " 额度受限"
        } else {
            head + " 等 " + status.limitedModels.size + " 个模型额度受限"
        }
    }

    /** 垫底提示。 */
    fun sinkText(status: GatewayAccountStatus): String? =
        if (status.sink) "垫底（仍可用，排在最后）" else null
}
