package dev.praxis.core.gateway.adapters

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 一家提供商的一条登录方式（网关 GET /api/session/login/methods 的 methods[] 元素）。
 *
 * 字段全部来自网关真实响应，没有猜测：
 * - methodIndex：选择登录方式时**必须回传**的索引（不是数组下标，是运行时清单里的编号）
 * - manualCallback：授权完成后要把回调地址/码**粘回来**（浏览器与网关不同机时必然如此）
 * - callbackInput：粘的是整条 URL 还是 code
 * - localOnly：只接受回环地址的回调（如小浣熊的上游白名单）
 */
data class LoginMethod(
    val methodIndex: Int,
    val label: String,
    val manualCallback: Boolean = false,
    /** "url" 或 "code"。 */
    val callbackInput: String = "url",
    val localOnly: Boolean = false,
    val instructions: String = "",
)

/** 登录方式的运行时清单（含该家的通用说明）。 */
data class LoginMethods(
    val methods: List<LoginMethod>,
    val instructions: String = "",
) {
    companion object {
        /** 解析网关响应。methods 缺失 = 该家不支持登录助手（返回空清单，不抛）。 */
        fun parse(body: String): LoginMethods {
            val root = Json.parseToJsonElement(body).jsonObject
            val array = root["methods"] as? JsonArray ?: return LoginMethods(emptyList())
            val list = array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val index = obj["methodIndex"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: return@mapNotNull null
                LoginMethod(
                    methodIndex = index,
                    label = obj["label"]?.jsonPrimitive?.contentOrNull
                        ?: obj["name"]?.jsonPrimitive?.contentOrNull
                        ?: ("方式 " + index),
                    manualCallback = obj["manualCallback"]?.jsonPrimitive?.contentOrNull == "true",
                    callbackInput = obj["callbackInput"]?.jsonPrimitive?.contentOrNull ?: "url",
                    localOnly = obj["localOnly"]?.jsonPrimitive?.contentOrNull == "true",
                    instructions = obj["instructions"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
            return LoginMethods(list, root["instructions"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
    }
}

/** 一次登录的中间态（start 的响应）。 */
data class LoginStart(
    val state: String,
    /** 要打开的授权页；null = 该方式不需要跳浏览器（如直接填 Key）。 */
    val authUrl: String?,
    /** 该方式的说明（会随 wait 的 hint 更新）。 */
    val instructions: String = "",
    /** 轮询间隔（网关给的建议值，默认 3 秒）。 */
    val pollIntervalMs: Long = 3000L,
)

/** wait 的一次结果。 */
data class LoginWait(
    val done: Boolean,
    val pending: Boolean,
    /** done 且无 error 时，账号已建好。 */
    val accountId: String? = null,
    /** done 且非空 = 失败原因。 */
    val error: String? = null,
    /** 还没完成时给用户的下一步提示（会变）。 */
    val hint: String? = null,
    val instructions: String? = null,
) {
    companion object {
        fun parse(body: String): LoginWait {
            val root = Json.parseToJsonElement(body).jsonObject
            val done = root["done"]?.jsonPrimitive?.contentOrNull == "true"
            val pendingObj = root["pending"] as? JsonObject
            val session = root["session"] as? JsonObject
            return LoginWait(
                done = done,
                pending = (root["pending"] != null) && pendingObj != null ||
                    root["pending"]?.jsonPrimitive?.contentOrNull == "true",
                accountId = session?.get("accountId")?.jsonPrimitive?.contentOrNull
                    ?: session?.get("id")?.jsonPrimitive?.contentOrNull
                    ?: root["accountId"]?.jsonPrimitive?.contentOrNull,
                error = root["error"]?.jsonPrimitive?.contentOrNull
                    ?: (root["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull,
                hint = root["hint"]?.jsonPrimitive?.contentOrNull
                    ?: pendingObj?.get("hint")?.jsonPrimitive?.contentOrNull,
                instructions = root["instructions"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }
}