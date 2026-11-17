package dev.praxis.core.gateway.adapters

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request

/**
 * Praxis ↔ Agent2API 网关的通信层。
 *
 * ## 为什么需要这个类
 *
 * 33 家 Agent 插件提供商的登录/签到/积分/保活/拉模型全部由网关实现（Rust 进程）。
 * Praxis 不重复这些逻辑 —— 它通过 HTTP 调用网关的 REST API。
 *
 * ## 支持的操作
 *
 * | 操作 | 网关端点 | 说明 |
 * |---|---|---|
 * | 拉模型清单 | GET 〔v1〕models | 含能力位 |
 * | 发消息 | POST 〔v1〕chat completions | OpenAI 兼容 SSE |
 * | 登录（获取方式列表） | GET /api/providers/{id}/login-methods | 每家不同 |
 * | 登录（启动流程） | POST /api/providers/{id}/login/start | 返回 URL 或状态 |
 * | 登录（轮询结果） | GET /api/providers/{id}/login/poll/{sessionId} | 设备码/轮询型 |
 * | 签到 | POST /api/providers/{id}/checkin | 每家不同 |
 * | 余额/积分 | GET /api/providers/{id}/credits | 每家不同 |
 * | 账号列表 | GET /api/accounts | 所有已登录账号 |
 *
 * ## 认证
 *
 * 网关 Key（面板里建的）放在 `Authorization: Bearer <key>` 头里。
 * 没配 Key 时网关处于 fail-closed 状态，所有 〔v1〕* 请求返回 403。
 */
class GatewayConnection(
    /** 网关地址（如 http://127.0.0.1:3065 或 https://xxx/a2a/v1）。 */
    val baseUrl: String,
    /** 网关 Key（面板里建的；空 = fail-closed，所有请求被拒）。 */
    val apiKey: String = "",
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun authHeaders(): Map<String, String> =
        if (apiKey.isNotBlank()) mapOf("Authorization" to "Bearer $apiKey") else emptyMap()

    /** 面板 session cookie（登录后获得，管理 API 用它）。 */
    var panelSession: String = ""
        private set

    private fun url(path: String): String = baseUrl.trimEnd('/') + path

    /**
     * 拉取全部模型（含能力位）。
     * @return (模型列表, 所属提供商 id)；提供商 id 从模型的 owned_by 字段取。
     */
    suspend fun fetchModels(): Result<List<GatewayModelEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = ProviderHttpClient.get(url("/v1/models"), authHeaders())
            response.use { resp ->
                if (!resp.isSuccessful) error("HTTP " + resp.code + ": " + resp.body?.string()?.take(200))
                val body = resp.body?.string() ?: error("空响应")
                val root = json.parseToJsonElement(body).jsonObject
                val data = root["data"] as? JsonArray ?: error("响应没有 data 数组")
                data.mapNotNull { element ->
                    val obj = element as? JsonObject ?: return@mapNotNull null
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    GatewayModelEntry(
                        id = id,
                        name = obj["name"]?.jsonPrimitive?.contentOrNull ?: id,
                        provider = obj["owned_by"]?.jsonPrimitive?.contentOrNull ?: "",
                        supportsImages = obj["supports_images"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull(),
                        supportsReasoning = obj["supports_reasoning"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull(),
                        supportsToolCall = obj["supports_tool_call"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull(),
                        maxInputTokens = obj["max_input_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                        maxOutputTokens = obj["max_output_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                    )
                }
            }
        }
    }

    /** 获取指定提供商的登录方式列表。 */
    suspend fun loginMethods(providerId: String): Result<List<GatewayLoginMethodInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = ProviderHttpClient.get(
                    url("/api/providers/$providerId/login-methods"), authHeaders())
                response.use { resp ->
                    if (!resp.isSuccessful) error("HTTP " + resp.code)
                    val body = resp.body?.string() ?: error("空响应")
                    val root = json.parseToJsonElement(body).jsonObject
                    val methods = root["methods"] as? JsonArray ?: error("没有 methods 数组")
                    methods.mapNotNull { element ->
                        val obj = element as? JsonObject ?: return@mapNotNull null
                        GatewayLoginMethodInfo(
                            type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "unknown",
                            label = obj["label"]?.jsonPrimitive?.contentOrNull ?: "",
                            url = obj["url"]?.jsonPrimitive?.contentOrNull,
                        )
                    }
                }
            }
        }

    /** 启动登录流程（返回 sessionId 或 URL）。 */
    suspend fun startLogin(providerId: String, method: String): Result<GatewayLoginSession> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = json.encodeToString(
                    kotlinx.serialization.json.JsonObject.serializer(),
                    buildJsonObject {
                        put("method", kotlinx.serialization.json.JsonPrimitive(method))
                    }
                )
                val response = ProviderHttpClient.postJson(
                    url("/api/providers/$providerId/login/start"), body, authHeaders())
                response.use { resp ->
                    if (!resp.isSuccessful) error("HTTP " + resp.code + ": " + resp.body?.string()?.take(200))
                    val root = json.parseToJsonElement(resp.body?.string() ?: error("空响应")).jsonObject
                    GatewayLoginSession(
                        sessionId = root["sessionId"]?.jsonPrimitive?.contentOrNull ?: "",
                        url = root["url"]?.jsonPrimitive?.contentOrNull,
                        pollIntervalMs = root["pollIntervalMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3000L,
                    )
                }
            }
        }

    /** 轮询登录结果。 */
    suspend fun pollLogin(providerId: String, sessionId: String): Result<GatewayLoginResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = ProviderHttpClient.get(
                    url("/api/providers/$providerId/login/poll/$sessionId"), authHeaders())
                response.use { resp ->
                    if (!resp.isSuccessful) error("HTTP " + resp.code)
                    val root = json.parseToJsonElement(resp.body?.string() ?: error("空响应")).jsonObject
                    GatewayLoginResult(
                        status = root["status"]?.jsonPrimitive?.contentOrNull ?: "pending",
                        accountId = root["accountId"]?.jsonPrimitive?.contentOrNull,
                        token = root["token"]?.jsonPrimitive?.contentOrNull,
                        error = root["error"]?.jsonPrimitive?.contentOrNull,
                    )
                }
            }
        }

    /** 触发签到。 */
    suspend fun checkIn(accountId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val response = ProviderHttpClient.postJson(
                url("/api/accounts/$accountId/checkin"), "{}", authHeaders())
            response.use { resp ->
                if (!resp.isSuccessful) error("HTTP " + resp.code)
                resp.body?.string() ?: "签到完成"
            }
        }
    }

    /** 查余额/积分。 */
    suspend fun credits(accountId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val response = ProviderHttpClient.get(
                url("/api/accounts/$accountId/credits"), authHeaders())
            response.use { resp ->
                if (!resp.isSuccessful) error("HTTP " + resp.code)
                resp.body?.string() ?: "未知"
            }
        }
    }

    /** 健康检查。 */
    suspend fun health(): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val response = ProviderHttpClient.get(url("/health"))
            response.use { resp -> resp.isSuccessful }
        }
    }

    /**
     * 面板登录（获取 session cookie，管理 API 用）。
     * 用户输入网关管理员账号密码；session 存在 [panelSession] 供后续调用。
     */
    suspend fun panelLogin(username: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = ProviderHttpClient.postJson(
                    url("/api/panel/login"),
                    Json.encodeToString(
                        kotlinx.serialization.json.JsonObject.serializer(),
                        buildJsonObject {
                            put("username", kotlinx.serialization.json.JsonPrimitive(username))
                            put("password", kotlinx.serialization.json.JsonPrimitive(password))
                        }
                    ),
                )
                response.use { resp ->
                    val body = resp.body?.string() ?: error("空响应")
                    if (!resp.isSuccessful) error("登录失败（HTTP " + resp.code + "）：" + body.take(200))
                    // 从 Set-Cookie 提取 session —— OkHttp 自动管理 cookie jar，这里直接标记成功
                    panelSession = "ok"
                }
            }
        }

    // ── 账号导入 / 保活（语义来自 api/accounts.rs 与 api/billing.rs）──

    /**
     * 把一份账号 JSON 导入网关。
     *
     * 接受的就是网关「导出」的原样文件：顶层 `{accounts:[...], customProviders:[...]}`。
     * 网关还兼容 `{success, data:{accounts}}` 这种信封壳（旧版网页导出把信封存成了文件），
     * 所以 Praxis 不需要先拆包 —— 原样转交即可。
     */
    suspend fun importAccounts(json: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            ProviderHttpClient.postJson(url("/api/accounts/import"), json, authHeaders()).use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("导入失败（HTTP " + resp.code + "）：" + body.take(200))
                body
            }
        }
    }

    /** 保活配置（读）。 */
    suspend fun keepaliveState(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            ProviderHttpClient.get(url("/api/checkin-keepalive"), authHeaders()).use { resp ->
                if (!resp.isSuccessful) error("HTTP " + resp.code)
                resp.body?.string().orEmpty()
            }
        }
    }

    /** 保活配置（写）。body 就是 GET 拿到的那份 JSON（改完再传回）。 */
    suspend fun saveKeepalive(json: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            ProviderHttpClient.postJson(url("/api/checkin-keepalive"), json, authHeaders()).use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("保存失败（HTTP " + resp.code + "）：" + body.take(200))
                body
            }
        }
    }

    /**
     * 手动跑某个账号的活跃任务。
     * @param mode "full" | "claim" | "keepalive"（网关 api/billing.rs 的粒度）
     */
    suspend fun runActivity(accountId: String, mode: String = "keepalive"): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = json.encodeToString(
                    kotlinx.serialization.json.JsonObject.serializer(),
                    buildJsonObject {
                        put("id", kotlinx.serialization.json.JsonPrimitive(accountId))
                        put("mode", kotlinx.serialization.json.JsonPrimitive(mode))
                    },
                )
                ProviderHttpClient.postJson(url("/api/checkin-activity"), body, authHeaders()).use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("活跃任务失败（HTTP " + resp.code + "）：" + text.take(200))
                    text
                }
            }
        }
    // ── 签到 / 积分（文档语义来自网关 api/billing.rs，不是猜的）────
    //
    // **409 的语义**：POST /api/checkin 在「今天已领过」时返回
    // 409 + success:false + msg（如「今日已签到」）—— 这是正常结果，不是失败。
    // 面板前端按 warn 展示；Praxis 也这么对待（CheckinResult.takenToday）。

    /** 签到活动状态（无活动/无数据时 data 为 null）。 */
    suspend fun checkinStatus(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            ProviderHttpClient.get(url("/api/checkin/status"), authHeaders()).use { resp ->
                if (!resp.isSuccessful) error("HTTP " + resp.code)
                resp.body?.string().orEmpty()
            }
        }
    }

    /**
     * 领取每日签到积分。
     * @return [CheckinResult]：takenToday=true 表示今天已经领过（409 分支）。
     */
    suspend fun claimCheckin(): Result<CheckinResult> = withContext(Dispatchers.IO) {
        runCatching {
            ProviderHttpClient.postJson(url("/api/checkin"), "{}", authHeaders()).use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.code == 409) {
                    // 已领取：从 body 里把上游的 msg 抠出来给用户看。
                    val msg = runCatching {
                        json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
                            ?.get("msg")?.jsonPrimitive?.contentOrNull
                    }.getOrNull()
                    return@use CheckinResult(takenToday = true, message = msg ?: "今日已签到")
                }
                if (!resp.isSuccessful) error("签到失败（HTTP " + resp.code + "）：" + body.take(160))
                CheckinResult(takenToday = false, message = body)
            }
        }
    }

    /** 签到 + 查余额（一次调用拿两个结果）。 */
    suspend fun claimAndReport(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            ProviderHttpClient.postJson(url("/api/checkin/claim-and-report"), "{}", authHeaders()).use { resp ->
                if (!resp.isSuccessful) error("HTTP " + resp.code)
                resp.body?.string().orEmpty()
            }
        }
    }

    /** 积分汇总（GET /api/usage）。 */
    suspend fun usageSummary(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            ProviderHttpClient.get(url("/api/usage"), authHeaders()).use { resp ->
                if (!resp.isSuccessful) error("HTTP " + resp.code)
                resp.body?.string().orEmpty()
            }
        }
    }

    /** 自动签到设置（GET /api/auto-checkin）。 */
    suspend fun autoCheckin(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            ProviderHttpClient.get(url("/api/auto-checkin"), authHeaders()).use { resp ->
                if (!resp.isSuccessful) error("HTTP " + resp.code)
                resp.body?.string().orEmpty()
            }
        }
    }

    /** 立即跑一轮自动签到（对全部目标账号）。 */
    suspend fun runAutoCheckin(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            ProviderHttpClient.postJson(url("/api/auto-checkin/run"), "{}", authHeaders()).use { resp ->
                if (!resp.isSuccessful) error("HTTP " + resp.code)
                resp.body?.string().orEmpty()
            }
        }
    }
    /**
     * 面板首次注册（网关刚初始化、还没有管理员时用）。
     * 已注册时网关返回 409 —— 这里把它当作「已存在」的成功。
     */
    suspend fun panelSetup(username: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = json.encodeToString(
                    kotlinx.serialization.json.JsonObject.serializer(),
                    buildJsonObject {
                        put("username", kotlinx.serialization.json.JsonPrimitive(username))
                        put("password", kotlinx.serialization.json.JsonPrimitive(password))
                    },
                )
                ProviderHttpClient.postJson(url("/api/panel/setup"), body).use { resp ->
                    if (resp.code == 409) return@use  // 已注册：等同成功
                    if (!resp.isSuccessful) error("初始化失败（HTTP " + resp.code + "）：" + (resp.body?.string()?.take(160) ?: ""))
                }
            }
        }

    /** 面板是否已经注册过管理员。 */
    suspend fun isPanelRegistered(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            ProviderHttpClient.get(url("/api/panel/status")).use { resp ->
                resp.isSuccessful && (resp.body?.string() ?: "").contains("\"registered\":true")
            }
        }.getOrDefault(false)
    }

    // ── 登录助手（33 家 Agent 提供商的登录流程）─────────────
    //
    // 协议对齐网关 web_shim.rs 的真实实现（不是猜的）：
    //   methods → start(拿 authUrl) → [H5 容器打开] → wait 轮询 → callback（需要粘贴时）

    /** 取该家提供商的登录方式清单。 */
    suspend fun loginMethodsOf(provider: String): Result<LoginMethods> =
        withContext(Dispatchers.IO) {
            runCatching {
                ProviderHttpClient.get(
                    url("/api/session/login/methods?provider=" + java.net.URLEncoder.encode(provider, "UTF-8")),
                    authHeaders(),
                ).use { resp ->
                    if (!resp.isSuccessful) error("取登录方式失败（HTTP " + resp.code + "）")
                    LoginMethods.parse(resp.body?.string() ?: error("空响应"))
                }
            }
        }

    /**
     * 发起登录。
     * @param methodIndex 来自 [LoginMethod.methodIndex]（不是数组下标）
     * @param edition "cn" 或 "intl"（各家分区不同）
     */
    suspend fun startLogin(
        provider: String,
        methodIndex: Int,
        edition: String = "cn",
    ): Result<LoginStart> = withContext(Dispatchers.IO) {
        runCatching {
            val body = json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                buildJsonObject {
                    put("provider", kotlinx.serialization.json.JsonPrimitive(provider))
                    put("edition", kotlinx.serialization.json.JsonPrimitive(edition))
                    put("mode", kotlinx.serialization.json.JsonPrimitive("embedded"))
                    put("methodIndex", kotlinx.serialization.json.JsonPrimitive(methodIndex))
                    put("socialRestore", kotlinx.serialization.json.JsonPrimitive(false))
                },
            )
            ProviderHttpClient.postJson(url("/api/session/login/start"), body, authHeaders()).use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("发起登录失败（HTTP " + resp.code + "）：" + text.take(160))
                val root = json.parseToJsonElement(text).jsonObject
                LoginStart(
                    state = root["state"]?.jsonPrimitive?.contentOrNull ?: error("响应缺 state"),
                    authUrl = root["authUrl"]?.jsonPrimitive?.contentOrNull,
                    instructions = root["instructions"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    pollIntervalMs = root["pollIntervalMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3000L,
                )
            }
        }
    }

    /** 轮询登录结果（一次）。 */
    suspend fun waitLogin(state: String): Result<LoginWait> = withContext(Dispatchers.IO) {
        runCatching {
            ProviderHttpClient.get(
                url("/api/session/login/wait?state=" + java.net.URLEncoder.encode(state, "UTF-8")),
                authHeaders(),
            ).use { resp ->
                if (!resp.isSuccessful) error("轮询失败（HTTP " + resp.code + "）")
                LoginWait.parse(resp.body?.string() ?: error("空响应"))
            }
        }
    }

    /** 提交回调（manualCallback 的方式需要：粘贴整条回调 URL 或 code）。 */
    suspend fun submitLoginCallback(state: String, callbackUrl: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = json.encodeToString(
                    kotlinx.serialization.json.JsonObject.serializer(),
                    buildJsonObject {
                        put("state", kotlinx.serialization.json.JsonPrimitive(state))
                        put("callbackUrl", kotlinx.serialization.json.JsonPrimitive(callbackUrl))
                    },
                )
                ProviderHttpClient.postJson(url("/api/session/login/callback"), body, authHeaders()).use { resp ->
                    if (!resp.isSuccessful) error("提交回调失败（HTTP " + resp.code + "）：" + (resp.body?.string()?.take(160) ?: ""))
                }
            }
        }

    /** 取消登录（撤掉网关侧任务；Trae 的回调端口靠这一步释放）。 */
    suspend fun cancelLogin(state: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                buildJsonObject { put("state", kotlinx.serialization.json.JsonPrimitive(state)) },
            )
            ProviderHttpClient.postJson(url("/api/session/login/cancel"), body, authHeaders()).use { resp ->
                if (!resp.isSuccessful) error("HTTP " + resp.code)
            }
        }
    }

    /** 账号列表（原始 JSON；登录成功后用它刷新 Praxis 账号页）。 */
    suspend fun listAccounts(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            ProviderHttpClient.get(url("/api/accounts"), authHeaders()).use { resp ->
                if (!resp.isSuccessful) error("HTTP " + resp.code)
                resp.body?.string().orEmpty()
            }
        }
    }
    /**
     * 从官方发布页安装提供商插件（33 家 Agent 插件都在这一个包里）。
     * 需要先 [panelLogin]；target 不传 —— 网关自己知道自己跑在什么平台上。
     */
    suspend fun installPluginsFromRelease(pluginId: String = "agent2api-providers"): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = json.encodeToString(
                    kotlinx.serialization.json.JsonObject.serializer(),
                    buildJsonObject {
                        put("id", kotlinx.serialization.json.JsonPrimitive(pluginId))
                        put("action", kotlinx.serialization.json.JsonPrimitive("install"))
                    },
                )
                ProviderHttpClient.postJson(url("/api/plugins/install-from-release"), body).use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("安装请求失败（HTTP " + resp.code + "）：" + text.take(160))
                    text
                }
            }
        }

    /** 插件安装进度（轮询用；返回原始 JSON）。 */
    suspend fun pluginProgress(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            ProviderHttpClient.get(url("/api/plugins/progress")).use { resp ->
                if (!resp.isSuccessful) error("HTTP " + resp.code)
                resp.body?.string().orEmpty()
            }
        }
    }

    /** 已安装的插件清单（原始 JSON）。 */
    suspend fun listPlugins(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            ProviderHttpClient.get(url("/api/plugins")).use { resp ->
                if (!resp.isSuccessful) error("HTTP " + resp.code)
                resp.body?.string().orEmpty()
            }
        }
    }
    /** 面板已登录（session 有效）。 */
    suspend fun isPanelLoggedIn(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url("/api/session"))
                .get()
                .apply { if (panelSession.isNotBlank()) addHeader("Cookie", panelSession) }
                .build()
            ProviderHttpClient.client.newCall(request).execute().use { resp -> resp.isSuccessful }
        }.getOrDefault(false)
    }
}

/** 网关返回的模型条目（含能力位与所属提供商）。 */
data class GatewayModelEntry(
    val id: String,
    val name: String,
    val provider: String,
    val supportsImages: Boolean? = null,
    val supportsReasoning: Boolean? = null,
    val supportsToolCall: Boolean? = null,
    val maxInputTokens: Int? = null,
    val maxOutputTokens: Int? = null,
)

/** 网关返回的登录方式。 */
data class GatewayLoginMethodInfo(
    val type: String,
    val label: String,
    val url: String?,
)

/** 登录会话（设备码/轮询型的中间态）。 */
data class GatewayLoginSession(
    val sessionId: String,
    val url: String?,
    val pollIntervalMs: Long,
)

/** 登录轮询结果。 */
data class GatewayLoginResult(
    val status: String,  // pending / completed / failed
    val accountId: String?,
    val token: String?,
    val error: String?,
)