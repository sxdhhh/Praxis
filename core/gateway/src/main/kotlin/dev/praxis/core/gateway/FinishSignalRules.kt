package dev.praxis.core.gateway

/**
 * 把上游的 `finish_reason` 映射成 [FinishSignal]（文档 §7.5）。
 *
 * ## 为什么「没给 finish_reason」不算腰斩
 *
 * 很多中转站与自建网关**根本不发** `finish_reason`（Praxis 自己的注释里就记着
 * 「某些网关只发 tool_calls 不发 finish_reason」）。把「没写」当成「被截断」，
 * 会让每一次正常回答都自动多跑一轮 —— 那是**真金白银**的浪费，而用户完全不知情。
 *
 * 所以只有明确写着长度截断的那几个值才认。
 *
 * ## 为什么断流不在这里判
 *
 * 「上游断流」是**异常路径**的事实（流在回答中途抛了），不是上游给的字符串。
 * 那个由调用方直接传 [FinishSignal.StreamCut]，不必也不该从这个函数猜。
 */
object FinishSignalRules {

    /** 明确表示「被长度截断」的取值（大小写不敏感）。 */
    private val LENGTH_MARKERS: Set<String> = setOf(
        "length",
        "max_tokens",
        "max_output_tokens",
        "max_completion_tokens",
    )

    fun of(finishReason: String?): FinishSignal {
        val value = finishReason?.trim()?.lowercase()
        if (value.isNullOrEmpty()) return FinishSignal.Normal
        return if (value in LENGTH_MARKERS) FinishSignal.LengthLimit else FinishSignal.Normal
    }
}
