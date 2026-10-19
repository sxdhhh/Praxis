package dev.praxis.core.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 工具参数解析。
 *
 * 模型给的 [argumentsJson] 可能：空串、`{}`、缺字段、类型不对、甚至半截 JSON。
 * 这四种都要给**可读的中文错误**，而不是抛 IllegalStateException ——
 * 模型看到「缺少参数 path」会自己补，看到「internal error」只会道歉。
 */
class ToolArguments(
    private val object0: JsonObject,
) {

    /** 取字符串参数。[name] 用于错误信息。 */
    fun string(name: String): String =
        object0[name]?.jsonPrimitive?.contentOrNull
            ?: throw ToolArgumentException("缺少必需的字符串参数：$name")

    fun stringOrNull(name: String): String? =
        object0[name]?.jsonPrimitive?.contentOrNull

    fun int(name: String, default: Int? = null): Int {
        val raw = object0[name]?.jsonPrimitive?.contentOrNull
            ?: return default ?: throw ToolArgumentException("缺少必需的整数参数：$name")
        return raw.toIntOrNull()
            ?: throw ToolArgumentException("参数 $name 应该是整数，实际是：$raw")
    }

    fun boolean(name: String, default: Boolean = false): Boolean {
        val raw = object0[name]?.jsonPrimitive?.contentOrNull ?: return default
        return raw.toBooleanStrictOrNull()
            ?: throw ToolArgumentException("参数 $name 应该是 true/false，实际是：$raw")
    }

    /** 原始 JSON 对象（供需要嵌套结构的工具使用）。 */
    fun raw(): JsonObject = object0

    companion object {

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * 解析参数。空串按空对象处理 —— 上游对「无参数工具」有时发 `""`、有时发 `"{}"`。
         */
        fun parse(argumentsJson: String): ToolArguments {
            val trimmed = argumentsJson.trim()
            if (trimmed.isEmpty()) return ToolArguments(JsonObject(emptyMap()))

            val element = try {
                json.parseToJsonElement(trimmed)
            } catch (e: Exception) {
                throw ToolArgumentException(
                    "参数不是合法 JSON：${e.message?.take(120) ?: "解析失败"}。原始内容：${trimmed.take(200)}",
                )
            }

            val obj = element as? JsonObject
                ?: throw ToolArgumentException("参数必须是 JSON 对象，实际是：${trimmed.take(120)}")
            return ToolArguments(obj)
        }
    }
}

/**
 * 参数错误。
 *
 * 单独一个类型（而不是复用 [ToolException]）：参数错误是**可恢复**的，
 * 执行器会把它转成 `isError = true` 的 [ToolResult] 回传模型；
 * 而 [ToolException] 表示工具实现自身有问题。
 */
class ToolArgumentException(message: String) : Exception(message)
