package dev.praxis.core.model.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ModelAdvancedSettings] 的序列化往返与边界。
 *
 * ## 为什么要专门测这个
 *
 * 请求头是「用户手填的任意字符串」，而它要经过一串真实世界的粗糙处理：
 * 存成单个 DataStore 字符串（加密）→ 读回来 → 变成 Http 头。
 * 中间任何一步偷懒，症状都是**请求 401 且没有任何错误提示** ——
 * 用户只会看到「模型不能用」。这类静默错必须被测试钉住。
 */
class ModelAdvancedSettingsTest {
    /**
     * 分隔符常量，与 [ModelAdvancedSettings.SEPARATOR] 同值。
     *
     * ## 为什么写成 chr(31) 而不是字面量
     *
     * 这是一个不可见的控制字符。直接写字面量既看不见也容易在
     * 编辑/粘贴中丢掉，测试就会变成「拿两个空字符串比相不相等」。
     * 用 chr(31) 表达意图明确，也不可能被肉眼误读。
     */
    private val SEP = chr(31).toString()


    @Test
    fun serialize_then_parse_roundtrip() {
        val headers = listOf(
            "X-Api-Key" to "sk-123",
            "HTTP-Referer" to "https://example.com",
            "X-Title" to "Praxis",
        )
        val raw = ModelAdvancedSettings.serializeHeaders(headers)
        assertEquals(headers, ModelAdvancedSettings.parseHeaders(raw))
    }

    @Test
    fun parse_handles_empty_and_blank() {
        assertTrue(ModelAdvancedSettings.parseHeaders("").isEmpty())
        assertTrue(ModelAdvancedSettings.parseHeaders("   ").isEmpty())
    }

    @Test
    fun serialize_drops_blank_keys() {
        val raw = ModelAdvancedSettings.serializeHeaders(
            listOf("" to "orphan", "X-Real" to "v"),
        )
        assertEquals("X-Real" + SEP + "v", raw)
    }

    @Test
    fun parse_drops_odd_tail() {
        // 写到一半被打断：key 后面没有 value。丢掉孤儿，保留完好的。
        val raw = "A" + SEP + "1" + SEP + "B"
        assertEquals(listOf("A" to "1"), ModelAdvancedSettings.parseHeaders(raw))
    }

    @Test
    fun parse_preserves_commas_in_value() {
        // ## 这是选择分隔符时最关键的一条
        //
        // `Accept` 这类头的值本来就含逗号。若用逗号当分隔符，
        // `text/html,application/json` 会在解析时被切成两条错的。
        val headers = listOf("Accept" to "text/html,application/json")
        val raw = ModelAdvancedSettings.serializeHeaders(headers)
        assertEquals(headers, ModelAdvancedSettings.parseHeaders(raw))
    }

    @Test
    fun parse_preserves_pipe_in_value() {
        // 同理：竖线也是合法头值字符。
        val headers = listOf("X-Foo" to "a|b")
        val raw = ModelAdvancedSettings.serializeHeaders(headers)
        assertEquals(headers, ModelAdvancedSettings.parseHeaders(raw))
    }

    @Test
    fun parse_trims_key_but_keeps_value_verbatim() {
        // 键名两端的空格一定是手误；值里的空格可能是刻意的（很少但合法）。
        val raw = "  X-Key  " + SEP + "  spaced value  "
        assertEquals(
            listOf("X-Key" to "  spaced value  "),
            ModelAdvancedSettings.parseHeaders(raw),
        )
    }

    @Test
    fun configured_header_count_ignores_blank_keys() {
        val settings = ModelAdvancedSettings(
            customHeaders = listOf(
                "" to "x",
                "A" to "1",
                " " to "y",
                "B" to "2",
            ),
        )
        // 空白键（空格）也不算已配置。
        assertEquals(2, settings.configuredHeaderCount)
    }

    @Test
    fun has_queue_limits_is_false_when_both_zero() {
        assertTrue(!ModelAdvancedSettings().hasQueueLimits)
        assertTrue(ModelAdvancedSettings(requestLimitPerMinute = 10).hasQueueLimits)
        assertTrue(ModelAdvancedSettings(maxConcurrentRequests = 2).hasQueueLimits)
    }

    @Test
    fun empty_settings_serialize_to_empty_string() {
        assertEquals("", ModelAdvancedSettings.serializeHeaders(emptyList()))
    }
}
