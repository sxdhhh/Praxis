package dev.praxis.core.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 把 [GatewayRoutingConfig] 写回网关原生的 config 片段（文档 8.5）。
 *
 * ## 只写非空的键
 *
 * null 在导入侧的语义是「网关文件里原本没有这个键」。导出时把它写成 false 或 0，
 * 就等于替用户做了一次「他从未做过的选择」—— 回灌到网关之后，原本跟随默认值的
 * 项会变成显式值，以后再改默认值也影响不到它。
 *
 * ## 键名用网关原生的 camelCase
 *
 * 与 Praxis 内部的 DataStore 键（gateway_routing_strategy 这种下划线风格）刻意不同：
 * 这个文件是给网关吃的，不是给 Praxis 自己读的。
 */
object GatewayConfigExporter {

    private val json = Json { prettyPrint = true }

    fun encode(config: GatewayRoutingConfig): String {
        val obj = buildJsonObject {
            config.routingStrategy?.let { put("routingStrategy", it) }
            config.routingSticky?.let { put("routingSticky", it) }
            config.routingFailoverBackoff?.let { put("routingFailoverBackoff", it) }
            config.routingSink?.let { put("routingSink", it) }
            config.breakerEnabled?.let { put("routingBreakerEnabled", it) }
            config.breakerThreshold?.let { put("routingBreakerThreshold", it) }
            config.breakerTimeoutSeconds?.let { put("routingBreakerTimeoutSeconds", it) }
            config.breakerSuccessThreshold?.let { put("routingBreakerSuccessThreshold", it) }
            config.retryCount?.let { put("retryCount", it) }
            config.retryCrossProviderCount?.let { put("retryCrossProviderCount", it) }
            config.retryIntervalSeconds?.let { put("retryIntervalSeconds", it) }

            // 空列表与「没写」在网关侧等价（都是「没有免重试状态码」），所以空列表不写出。
            config.noRetryStatusCodes
                ?.takeIf { it.isNotEmpty() }
                ?.let { put("noRetryStatusCodes", it.joinToString(",")) }

            config.connectTimeoutSeconds?.let { put("connectTimeoutSeconds", it) }
            config.headersTimeoutSeconds?.let { put("headersTimeoutSeconds", it) }
            config.streamIdleTimeoutSeconds?.let { put("streamIdleTimeoutSeconds", it) }
            config.bodyTimeoutSeconds?.let { put("bodyTimeoutSeconds", it) }
            config.queueMaxWaits?.let { put("queueMaxWaits", it) }
            config.queueWaitSeconds?.let { put("queueWaitSeconds", it) }
            config.imageCompressEnabled?.let { put("imageCompressEnabled", it) }
            config.imageCompressMaxEdge?.let { put("imageCompressMaxEdge", it) }
            config.imageCompressQuality?.let { put("imageCompressQuality", it) }
            config.imageCompressThresholdKb?.let { put("imageCompressThresholdKb", it) }
            config.autoContinue?.let { put("autoContinue", it) }
            config.autoContinueMaxRounds?.let { put("autoContinueMaxRounds", it) }
            config.debugMode?.let { put("debugMode", it) }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }
}
