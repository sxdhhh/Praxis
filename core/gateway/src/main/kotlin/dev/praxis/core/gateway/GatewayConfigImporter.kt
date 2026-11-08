package dev.praxis.core.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 从网关 config.json 读出来的路由/重试/超时配置。
 *
 * ## 为什么全是可空
 *
 * null 表示「网关文件里没写这个键」，与 false / 0 是两件事：
 * 没写就该保留 Praxis 自己的默认值，写了 false 才是用户明确要关。
 * 用布尔默认值会把这两种情况揉成一个，用户关掉的开关会在导入后自己打开。
 */
data class GatewayRoutingConfig(
    val routingStrategy: String? = null,
    val routingSticky: String? = null,
    val routingFailoverBackoff: Boolean? = null,
    val routingSink: Boolean? = null,
    val breakerEnabled: Boolean? = null,
    val breakerThreshold: Int? = null,
    val breakerTimeoutSeconds: Int? = null,
    val breakerSuccessThreshold: Int? = null,
    val retryCount: Int? = null,
    /** 注意键名是 CrossProvider，不是 AccountSwitch（文档 8.4 特意点出）。 */
    val retryCrossProviderCount: Int? = null,
    val retryIntervalSeconds: Int? = null,
    /** 不重试、直接换号的状态码。 */
    val noRetryStatusCodes: List<Int>? = null,
    val connectTimeoutSeconds: Int? = null,
    val headersTimeoutSeconds: Int? = null,
    val streamIdleTimeoutSeconds: Int? = null,
    val bodyTimeoutSeconds: Int? = null,
    val queueMaxWaits: Int? = null,
    val queueWaitSeconds: Int? = null,
    val imageCompressEnabled: Boolean? = null,
    val imageCompressMaxEdge: Int? = null,
    val imageCompressQuality: Int? = null,
    val imageCompressThresholdKb: Int? = null,
    val autoContinue: Boolean? = null,
    val autoContinueMaxRounds: Int? = null,
    val debugMode: Boolean? = null,
)

/** 配置导入结果。 */
data class GatewayConfigImportResult(
    val config: GatewayRoutingConfig,
    /** 成功映射的键。 */
    val appliedKeys: List<String>,
    /**
     * 服务端专属、明确未导入的键（文档 8.4 的 C 类）。
     *
     * 必须逐条列出：用户看到「导入完成」却少了配置，是最容易引发不信任的体验。
     */
    val serverOnlyKeys: List<String>,
    /** 完全不认识的键（可能是网关新版本加的）。 */
    val unknownKeys: List<String>,
    /** 认识但类型不对的键（如 retryCount 给了字符串）。 */
    val invalidKeys: List<String>,
)

/**
 * 网关 config.json 的键映射（文档 8.4）。
 *
 * ## 只做 A 类
 *
 * B 类（提示词模式、models.dev、客户端识别、保留天数）都需要先改造 Praxis 侧
 * 才有意义，本轮不做。C 类不导入，但要逐条列出来。
 *
 * ## 熔断与图片压缩两组键名的说明
 *
 * 文档在这两组只写了「前缀 / 后缀」：routingBreakerEnabled 加 Threshold、
 * TimeoutSeconds、SuccessThreshold；imageCompressEnabled 加 MaxEdge、Quality、
 * ThresholdKb。这里按「组前缀 + 后缀」拼出全名。
 *
 * 如果网关真实的键名不是这样，它们会出现在 unknownKeys 里 —— **不会静默丢失**，
 * 用户能看见「这几个键我没认出来」。这是有意的：宁可报告里多一行，也不猜两套名字。
 */
object GatewayConfigImporter {

    private val json = Json { ignoreUnknownKeys = true }

    /** 服务端专属、明确不导入的键（文档 8.4 的 C 类）。 */
    private val SERVER_ONLY_KEYS: Set<String> = setOf(
        "scheduledTasks",
        "githubToken",
        "updateProxy",
        "updateBeta",
        "captchaEnabled",
        "captchaDesktop",
        "captchaWeb",
        "corsEnabled",
        "logDir",
        "requestStatsDir",
        "debugDir",
        "providerRoute",
        "developerRoleCompat",
        "clineUpstreamHeaders",
        "stripNewlineKeepalive",
    )

    /** 只看前缀就能判定的服务端专属键（zcodeCaptchaPool 一族）。 */
    private val SERVER_ONLY_PREFIXES: List<String> = listOf("zcodeCaptchaPool")

    fun parse(text: String): Result<GatewayConfigImportResult> = runCatching {
        val root = json.parseToJsonElement(text) as? JsonObject
            ?: error("config.json 不是 JSON 对象")

        val applied = mutableListOf<String>()
        val serverOnly = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        val invalid = mutableListOf<String>()

        var config = GatewayRoutingConfig()

        fun bool(key: String, apply: (Boolean) -> GatewayRoutingConfig) {
            val raw = root[key]
            if (raw == null) return
            val value = (raw as? JsonPrimitive)?.booleanOrNull
            if (value == null) {
                invalid += key
                return
            }
            config = apply(value)
            applied += key
        }

        fun int(key: String, apply: (Int) -> GatewayRoutingConfig) {
            val raw = root[key]
            if (raw == null) return
            val value = (raw as? JsonPrimitive)?.intOrNull
            if (value == null) {
                invalid += key
                return
            }
            config = apply(value)
            applied += key
        }

        fun text(key: String, apply: (String) -> GatewayRoutingConfig) {
            val raw = root[key]
            if (raw == null) return
            val value = (raw as? JsonPrimitive)?.contentOrNullValue()
            if (value == null) {
                invalid += key
                return
            }
            config = apply(value)
            applied += key
        }

        text("routingStrategy") { config.copy(routingStrategy = it) }
        text("routingSticky") { config.copy(routingSticky = it) }
        bool("routingFailoverBackoff") { config.copy(routingFailoverBackoff = it) }
        bool("routingSink") { config.copy(routingSink = it) }
        bool("routingBreakerEnabled") { config.copy(breakerEnabled = it) }
        int("routingBreakerThreshold") { config.copy(breakerThreshold = it) }
        int("routingBreakerTimeoutSeconds") { config.copy(breakerTimeoutSeconds = it) }
        int("routingBreakerSuccessThreshold") { config.copy(breakerSuccessThreshold = it) }
        int("retryCount") { config.copy(retryCount = it) }
        int("retryCrossProviderCount") { config.copy(retryCrossProviderCount = it) }
        int("retryIntervalSeconds") { config.copy(retryIntervalSeconds = it) }
        int("connectTimeoutSeconds") { config.copy(connectTimeoutSeconds = it) }
        int("headersTimeoutSeconds") { config.copy(headersTimeoutSeconds = it) }
        int("streamIdleTimeoutSeconds") { config.copy(streamIdleTimeoutSeconds = it) }
        int("bodyTimeoutSeconds") { config.copy(bodyTimeoutSeconds = it) }
        int("queueMaxWaits") { config.copy(queueMaxWaits = it) }
        int("queueWaitSeconds") { config.copy(queueWaitSeconds = it) }
        bool("imageCompressEnabled") { config.copy(imageCompressEnabled = it) }
        int("imageCompressMaxEdge") { config.copy(imageCompressMaxEdge = it) }
        int("imageCompressQuality") { config.copy(imageCompressQuality = it) }
        int("imageCompressThresholdKb") { config.copy(imageCompressThresholdKb = it) }
        bool("autoContinue") { config.copy(autoContinue = it) }
        int("autoContinueMaxRounds") { config.copy(autoContinueMaxRounds = it) }
        bool("debugMode") { config.copy(debugMode = it) }

        // noRetryStatusCodes 是逗号分隔的字符串（文档举例 "400,402,413"）。
        root["noRetryStatusCodes"]?.let { raw ->
            val text = (raw as? JsonPrimitive)?.contentOrNullValue()
            if (text == null) {
                invalid += "noRetryStatusCodes"
            } else {
                val codes = text.split(',').mapNotNull { it.trim().toIntOrNull() }
                if (codes.isEmpty()) invalid += "noRetryStatusCodes" else {
                    config = config.copy(noRetryStatusCodes = codes)
                    applied += "noRetryStatusCodes"
                }
            }
        }

        // 其余键分三类：服务端专属 / 已知但未实现 / 完全不认识。
        root.keys.forEach { key ->
            if (key in applied || key in invalid) return@forEach
            when {
                key in SERVER_ONLY_KEYS -> serverOnly += key
                SERVER_ONLY_PREFIXES.any { key.startsWith(it) } -> serverOnly += key
                else -> unknown += key
            }
        }

        GatewayConfigImportResult(
            config = config,
            appliedKeys = applied.sorted(),
            serverOnlyKeys = serverOnly.sorted(),
            unknownKeys = unknown.sorted(),
            invalidKeys = invalid.sorted(),
        )
    }

    /** JsonPrimitive 的内容；非字符串/数字/布尔的 null 值返回 null。 */
    private fun JsonPrimitive.contentOrNullValue(): String? =
        if (this is JsonPrimitive && content.isNotEmpty() && content != "null") content else null
}
