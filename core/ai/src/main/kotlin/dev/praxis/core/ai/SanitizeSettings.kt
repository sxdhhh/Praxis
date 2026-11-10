package dev.praxis.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 脱敏设置 = **开关 + 规则**（文档 §5.18 与 §6.4 都是这么分的两个键）。
 *
 * ## 为什么开关不能靠「规则列表为空」表示
 *
 * 那样用户一暂停就得把规则全删了，再开还得重写一遍。开关与规则是两件事：
 * 「先别改我的正文」和「我要改哪些」不是同一个决定。
 */
data class SanitizeSettings(
    val enabled: Boolean = false,
    val rules: List<SanitizeRule> = emptyList(),
) {
    /** 实际会生效的规则：开关关着、或没有规则时为空。 */
    val effectiveRules: List<SanitizeRule>
        get() = if (enabled) rules else emptyList()
}

/** `gateway_sanitize` 的编解码（对象：`{enabled, rules[]}`）。 */
object SanitizeSettingsCodec {

    fun encode(settings: SanitizeSettings): String = buildJsonObject {
        put("enabled", settings.enabled)
        put("rules", buildJsonArray {
            settings.rules.forEach { rule ->
                addJsonObject {
                    put("pattern", rule.pattern)
                    put("replacement", rule.replacement)
                    put("enabled", rule.enabled)
                }
            }
        })
    }.toString()

    /** @param text null/空白 = 没配过（返回 null）；坏内容返回 failure。 */
    fun decode(text: String?): Result<SanitizeSettings>? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val root = Json.parseToJsonElement(text).jsonObject
            val rules = root["rules"]?.jsonArray?.map { element ->
                val obj = element.jsonObject
                val pattern = obj["pattern"]?.jsonPrimitive?.contentOrNull ?: error("缺 pattern")
                SanitizeRule(
                    pattern = pattern,
                    replacement = obj["replacement"]?.jsonPrimitive?.contentOrNull ?: "***",
                    enabled = obj["enabled"]?.jsonPrimitive?.contentOrNull
                        ?.toBooleanStrictOrNull() ?: true,
                )
            } ?: emptyList()
            SanitizeSettings(
                enabled = root["enabled"]?.jsonPrimitive?.contentOrNull
                    ?.toBooleanStrictOrNull() ?: false,
                rules = rules,
            )
        }
    }
}

/**
 * 规则文本 <-> 规则列表（界面用「一行一条正则」的文本框，比 N 个输入框好写）。
 *
 * 空行与 `#` 开头的注释行会被忽略 —— 用户会想给自己的规则加个说明。
 */
object SanitizeRuleLines {

    fun toText(rules: List<SanitizeRule>): String =
        rules.joinToString("\n") { it.pattern }

    fun fromText(text: String): List<SanitizeRule> = text
        .split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { SanitizeRule(pattern = it) }
}
