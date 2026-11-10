package dev.praxis.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 一条脱敏规则（文档 §5.18 的「脱敏指纹 sanitize rules」）。
 *
 * @param pattern 正则；匹配到的内容会被 [replacement] 替换。
 * @param replacement 替换文本，默认 `***`。
 */
data class SanitizeRule(
    val pattern: String,
    val replacement: String = "***",
    val enabled: Boolean = true,
)

/**
 * 出站前改写：把命中规则的片段替换掉（文档 §5.18）。
 *
 * ## 为什么放在发送前这一层
 *
 * 文档写的是「`core:ai` 出站前改写」—— 一处生效、两条路都覆盖（走网关与直连），
 * 而不是各自在 provider 里各写一遍。
 *
 * ## 默认关闭 + 规则可编辑
 *
 * 默认关闭是刻意的：**改写用户的正文是有副作用的操作**，不该默认发生。
 * 用户开了之后，规则也是他自己写的 —— 我们不预设任何「像密钥的东西」，
 * 因为猜错的代价是把他正常的内容也改掉。
 *
 * ## 坏正则不能拖垮整条请求
 *
 * 用户在设置里手写正则，写错是常态。那一类规则**单独跳过**（[compile] 会把它挑出来），
 * 而不是让整次发送失败 —— 用户开了脱敏却发不出消息，比不脱敏更糟。
 */
object OutboundSanitizer {

    /** 编译结果：可用的规则 + 坏正则（供界面报错）。 */
    data class Compiled(
        val rules: List<Pair<Regex, String>>,
        /** 编译失败的正则原文。 */
        val broken: List<String>,
    )

    /**
     * 编译规则。
     *
     * 大小写敏感（`RegexOption` 不预设）：脱敏的对象常常是大小写敏感的令牌，
     * 放宽会把用户没打算改的文本也改掉。
     */
    fun compile(rules: List<SanitizeRule>): Compiled {
        val usable = mutableListOf<Pair<Regex, String>>()
        val broken = mutableListOf<String>()
        rules.forEach { rule ->
            if (!rule.enabled) return@forEach
            if (rule.pattern.isBlank()) return@forEach
            val regex = runCatching { Regex(rule.pattern) }.getOrNull()
            if (regex == null) broken += rule.pattern else usable += regex to rule.replacement
        }
        return Compiled(usable, broken)
    }

    /**
     * 应用规则。
     *
     * 依次应用（后一条能看到前一条的结果）—— 顺序由用户列表决定，
     * 因为「先去掉手机号再去掉身份证」与反过来可能不同，那是用户的表达自由。
     */
    fun apply(text: String, rules: List<SanitizeRule>): String {
        if (text.isEmpty() || rules.isEmpty()) return text
        val compiled = compile(rules)
        var out = text
        compiled.rules.forEach { (regex, replacement) ->
            out = regex.replace(out, replacement)
        }
        return out
    }
}

/** `gateway_sanitize_rules` 的编解码。 */
object SanitizeRuleCodec {

    fun encode(rules: List<SanitizeRule>): String = buildJsonArray {
        rules.forEach { rule ->
            addJsonObject {
                put("pattern", rule.pattern)
                put("replacement", rule.replacement)
                put("enabled", rule.enabled)
            }
        }
    }.toString()

    /** @param text null/空白 = 没配过（返回 null）；坏内容返回 failure。 */
    fun decode(text: String?): Result<List<SanitizeRule>>? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            Json.parseToJsonElement(text).jsonArray.map { element ->
                val obj = element.jsonObject
                val pattern = obj["pattern"]?.jsonPrimitive?.contentOrNull ?: error("缺 pattern")
                SanitizeRule(
                    pattern = pattern,
                    replacement = obj["replacement"]?.jsonPrimitive?.contentOrNull ?: "***",
                    enabled = obj["enabled"]?.jsonPrimitive?.contentOrNull
                        ?.toBooleanStrictOrNull() ?: true,
                )
            }
        }
    }
}
