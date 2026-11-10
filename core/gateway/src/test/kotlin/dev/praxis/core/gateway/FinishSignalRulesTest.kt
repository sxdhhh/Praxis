package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

/** finish_reason -> FinishSignal（文档 §7.5）。 */
class FinishSignalRulesTest {

    @Test
    fun `没给 finish_reason 不算腰斩`() {
        // 很多中转站根本不发这个字段；当成截断会让每次正常回答都多跑一轮（花钱）。
        assertEquals(FinishSignal.Normal, FinishSignalRules.of(null))
        assertEquals(FinishSignal.Normal, FinishSignalRules.of(""))
        assertEquals(FinishSignal.Normal, FinishSignalRules.of("   "))
    }

    @Test
    fun `length 与 max_tokens 都算长度截断`() {
        assertEquals(FinishSignal.LengthLimit, FinishSignalRules.of("length"))
        assertEquals(FinishSignal.LengthLimit, FinishSignalRules.of("LENGTH"))
        assertEquals(FinishSignal.LengthLimit, FinishSignalRules.of(" max_tokens "))
        assertEquals(FinishSignal.LengthLimit, FinishSignalRules.of("max_output_tokens"))
    }

    @Test
    fun `正常结束与其它取值都不算腰斩`() {
        assertEquals(FinishSignal.Normal, FinishSignalRules.of("stop"))
        assertEquals(FinishSignal.Normal, FinishSignalRules.of("tool_calls"))
        assertEquals(FinishSignal.Normal, FinishSignalRules.of("content_filter"))
        // 不认识的取值**不猜**：宁可不续写，也不要无端多花钱。
        assertEquals(FinishSignal.Normal, FinishSignalRules.of("whatever"))
    }
}
