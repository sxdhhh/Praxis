package dev.praxis.core.gateway

/**
 * 候选队列里的一条（文档 9.4 的「候选队列」：位次 · 账号 · 可用/跳过原因）。
 *
 * ## 为什么要有这个类型，而不是直接把 RoutePlan 丢给界面
 *
 * RoutePlan 里可用与跳过是**两个分开的列表**（attempts / skipped），而界面要的是
 * 「一条队列」：先用谁、谁被跳过、为什么。这个转换只做一次、做在纯逻辑层，
 * 界面就只管画，不用自己拼两遍。
 */
data class GatewayQueueEntry(
    val accountId: String,
    val wireModel: String,
    /** 可用时是位次（1 起）；被跳过时为 0。 */
    val position: Int,
    /** 被跳过的原因；可用时为 null。 */
    val skipReason: SkipReason?,
    /** 处于垫底态（仍可用，排队尾）。 */
    val sink: Boolean,
) {
    val usable: Boolean get() = skipReason == null
}

/**
 * 把选路结果摊成一条队列：可用的按位次在前，跳过的跟在后。
 *
 * ## 跳过的排在后面而不是内联
 *
 * 内联（按账号出现顺序混排）会让「第几位」这个信息被跳过的行切断，用户数不清
 * 自己排第几。分开之后，前半段就是完整的尝试顺序，后半段是「这些没参与、
 * 原因是什么」。
 */
object GatewayQueue {

    fun of(plan: RoutePlan): List<GatewayQueueEntry> {
        val usable = plan.attempts.mapIndexed { index, attempt ->
            GatewayQueueEntry(
                accountId = attempt.accountId,
                wireModel = attempt.wireModel,
                position = index + 1,
                skipReason = null,
                sink = attempt.sink,
            )
        }
        val skipped = plan.skipped.map { item ->
            GatewayQueueEntry(
                accountId = item.accountId,
                wireModel = item.wireModel,
                position = 0,
                skipReason = item.reason,
                sink = false,
            )
        }
        return usable + skipped
    }

    /** 跳过原因的用户可见文案。 */
    fun reasonText(reason: SkipReason): String = when (reason) {
        SkipReason.Disabled -> "账号已停用"
        SkipReason.NoCredentials -> "凭证解不开（可能换过密钥，需要重填）"
        SkipReason.ImageNotSupported -> "明确声明不支持图片，而这次带图"
        SkipReason.CoolingDown -> "冷却中（退避 / 熔断 / 额度受限）"
        SkipReason.ConcurrencyFull -> "并发已满"
        SkipReason.RateLimited -> "限流已满（每账号或每家的配额）"
    }
}
