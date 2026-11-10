package dev.praxis.core.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 网关的「系统设置」（文档 §5.18，逐项过滤后的可用子集）。
 *
 * ## 只放**真的接上了消费者**的项
 *
 * 文档 §5.18 列了 18 条端点，其中大部分是服务端专属（CORS / captcha / sidebar-layout /
 * storage / system-info / health —— 那一栏文档自己也标了不移植）。
 * 这里只保留三项：**超时**、**换号次数上限**、**debug 原文**，
 * 因为它们各自有一个明确的读取点（OkHttp / FailoverBudget / 日志），
 * 改了立刻有可观察的效果。
 *
 * 其余（图片压缩阈值、脱敏规则、提示词模式、自动续写、客户端识别）**不放进来**：
 * 摆一个没人读的开关，比没有更糟（用户调了没反应，会以为整个设置页坏了）。
 *
 * ## 默认值刻意等于现状
 *
 * 超时默认 15/120 秒：那是移植前写死在 NetworkModule 里的值。
 * 默认值一改，所有用户的网络行为都变了 —— 那不是「加个设置」该有的副作用。
 */
data class GatewaySystemSettings(
    /** 建连超时（秒）。 */
    val connectTimeoutSeconds: Int = DEFAULT_CONNECT_SECONDS,
    /** 读取超时（秒）：流式回复会一直读，这里给的是「两次数据之间」的上限。 */
    val readTimeoutSeconds: Int = DEFAULT_READ_SECONDS,
    /** 一次请求最多换几个账号（文档 §7.4：换号次数上限默认 3）。 */
    val maxSwitches: Int = DEFAULT_MAX_SWITCHES,
    /** 调试：把脱敏后的请求体与响应摘要写进 App 日志。默认关。 */
    val debugRawTraffic: Boolean = false,
    /**
     * 请求队列：等一个并发位最多等多少秒。0 = 不限（旧行为）。
     *
     * 文档 §5.18 的「请求队列（最多等待 / 等待秒数）」。此前 `acquire()` 是
     * **无限等** —— 上游全忙时请求一直挂着，用户看到的是「转了十分钟才报错」，
     * 中间没有任何可操作提示。给了上限之后超时能变成一句明确的话。
     *
     * 默认 0（不限）而不是给个数字：改了默认值就等于改了所有用户的行为，
     * 那不是「加个设置」该有的副作用。
     */
    val queueWaitSeconds: Int = 0,
) {
    companion object {
        const val DEFAULT_CONNECT_SECONDS: Int = 15
        const val DEFAULT_READ_SECONDS: Int = 120
        const val DEFAULT_MAX_SWITCHES: Int = 3

        const val MIN_CONNECT_SECONDS: Int = 5
        const val MAX_CONNECT_SECONDS: Int = 120
        const val MIN_READ_SECONDS: Int = 30
        const val MAX_READ_SECONDS: Int = 600
        const val MIN_SWITCHES: Int = 1
        const val MAX_SWITCHES: Int = 10

        /** 0 = 不限；上限给 300 秒（再长不如让用户直接取消）。 */
        const val MIN_QUEUE_WAIT_SECONDS: Int = 0
        const val MAX_QUEUE_WAIT_SECONDS: Int = 300
    }
}

/** 归一化：越界夹到边界（这些是有界选择，不是用户逐位输入的数字）。 */
object GatewaySystemSettingsRules {

    fun normalize(settings: GatewaySystemSettings): GatewaySystemSettings =
        GatewaySystemSettings(
            connectTimeoutSeconds = settings.connectTimeoutSeconds.coerceIn(
                GatewaySystemSettings.MIN_CONNECT_SECONDS,
                GatewaySystemSettings.MAX_CONNECT_SECONDS,
            ),
            readTimeoutSeconds = settings.readTimeoutSeconds.coerceIn(
                GatewaySystemSettings.MIN_READ_SECONDS,
                GatewaySystemSettings.MAX_READ_SECONDS,
            ),
            maxSwitches = settings.maxSwitches.coerceIn(
                GatewaySystemSettings.MIN_SWITCHES,
                GatewaySystemSettings.MAX_SWITCHES,
            ),
            debugRawTraffic = settings.debugRawTraffic,
            queueWaitSeconds = settings.queueWaitSeconds.coerceIn(
                GatewaySystemSettings.MIN_QUEUE_WAIT_SECONDS,
                GatewaySystemSettings.MAX_QUEUE_WAIT_SECONDS,
            ),
        )
}

/** `gateway_system` 的编解码。 */
object GatewaySystemSettingsCodec {

    fun encode(settings: GatewaySystemSettings): String = buildJsonObject {
        put("connectTimeoutSeconds", settings.connectTimeoutSeconds)
        put("readTimeoutSeconds", settings.readTimeoutSeconds)
        put("maxSwitches", settings.maxSwitches)
        put("debugRawTraffic", settings.debugRawTraffic)
        put("queueWaitSeconds", settings.queueWaitSeconds)
    }.toString()

    /** @param text null/空白 = 没配过（返回 null）；缺字段用默认值（schema 演进）。 */
    fun decode(text: String?): Result<GatewaySystemSettings>? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val root: JsonObject = Json.parseToJsonElement(text).jsonObject
            val defaults = GatewaySystemSettings()
            GatewaySystemSettingsRules.normalize(
                GatewaySystemSettings(
                    connectTimeoutSeconds = root.intOr("connectTimeoutSeconds", defaults.connectTimeoutSeconds),
                    readTimeoutSeconds = root.intOr("readTimeoutSeconds", defaults.readTimeoutSeconds),
                    maxSwitches = root.intOr("maxSwitches", defaults.maxSwitches),
                    debugRawTraffic = root["debugRawTraffic"]?.jsonPrimitive?.contentOrNull
                        ?.toBooleanStrictOrNull() ?: defaults.debugRawTraffic,
                    queueWaitSeconds = root.intOr("queueWaitSeconds", defaults.queueWaitSeconds),
                ),
            )
        }
    }

    private fun JsonObject.intOr(key: String, fallback: Int): Int {
        val raw = this[key]?.jsonPrimitive?.contentOrNull ?: return fallback
        return raw.toIntOrNull() ?: error(key + " 不是整数")
    }
}
