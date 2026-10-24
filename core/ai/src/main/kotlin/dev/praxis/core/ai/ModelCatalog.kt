package dev.praxis.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 一个可选模型（对齐 Operit `ModelOption`）。
 *
 * [id] 是**要写进请求体**的那个字符串（`gpt-4o-mini`）；[name] 是列表里显示
 * 的文本。两者通常相同，但 Gemini 的 `name` 是 `models/gemini-2.0-flash`、
 * `displayName` 才是「Gemini 2.0 Flash」，此时 id 取短名、name 取 displayName。
 */
data class AiModelOption(
    val id: String,
    val name: String = id,
)

/**
 * 模型列表的 URL 派生与响应解析（1:1 移植 Operit `ModelListFetcher` 的纯逻辑部分）。
 *
 * ## 为什么拆成纯 JVM 模块
 *
 * 上游把「发请求」与「算 URL / 解析 JSON」写在一个 `object` 里（`ModelListFetcher`，
 * 700+ 行，依赖 OkHttp + Android Context + 资源字符串）。那样一来：
 *
 *  - 解析逻辑**没法单测**（要先有 Context）；
 *  - 出错信息只能靠 `context.getString(...)`，测试里断言不了。
 *
 * 这里把两件事分开：
 *
 * ```
 * :core:ai        纯函数：算 URL、解析 JSON      ← 本文件，可单测
 * :app            OkHttp 发请求、加认证头         ← OkHttpModelCatalogFetcher
 * ```
 *
 * 与 `ChatEndpoint` / `SseParser` 是同一套分工，不是新发明。
 */
object ModelCatalog {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 拉取失败的原因（给用户看的话都从这里出，便于测试断言）。 */
    sealed interface Failure {
        data object EmptyBaseUrl : Failure
        data class Http(val code: Int, val body: String) : Failure
        data class Network(val detail: String?) : Failure
        data class Parse(val raw: String) : Failure
        data object EmptyResponse : Failure

        /**
         * 一句话说明，直接进 Snackbar / 弹窗。
         *
         * ## 为什么叫 `description` 而不是 `message`
         *
         * `message` 会被 [Network] 的同名字段遮蔽（它的 payload 就是一句
         * 网络错误原文），类型也不同 —— 接口是 `String`、payload 是 `String?`。
         * 两者同名会编译失败，且失败信息（"hides member and needs override"）
         * 不容易一眼看出根因。换个名字最省事。
         */
        val description: String
            get() = when (this) {
                EmptyBaseUrl -> "请先填写接口地址。"
                is Http -> "服务端返回 $code：${body.take(200)}"
                is Network -> "网络错误：${detail ?: "无法连接"}"
                is Parse -> "返回的不是模型列表（前 200 字：${raw.take(200)}）。" +
                    "该服务商可能不支持 /models 端点。"
                EmptyResponse -> "服务端返回了空响应。"
            }
    }

    /**
     * 从 base URL 派生模型列表 URL。
     *
     * ## 为什么不能简单 `baseUrl + "/models"`
     *
     * Praxis 的 `baseUrl` 允许三种写法（见 `ModelScreen` 的提示）：
     *
     * ```
     * https://api.deepseek.com                        ← 裸域名
     * https://api.deepseek.com/v1                     ← 带版本段
     * https://api.deepseek.com/v1/chat/completions    ← 完整端点
     * ```
     *
     * 直接拼会得到 `.../v1/chat/completions/models`，必然 404。所以复用
     * [ChatEndpoint] 已经确立的「版本段判定」：先去端点后缀，再看是否已有版本段，
     * 没有才补 `/v1`。这样与真正发对话请求的地址**永远同源** —— 不会出现
     * 「对话能通但拉不到列表」这种最难查的错位。
     *
     * ## 为什么不做「失败后自动回退到 /models」
     *
     * 上游会这么干（`modelsUrl.removeSuffix("/v1/models") + "/models"`）。
     * 这里**也做**，但放在发请求那一层（见 `OkHttpModelCatalogFetcher`），
     * 因为它是一次额外的网络往返，属于传输策略而不是地址计算。
     */
    fun modelsUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        // 完整端点：剥掉 /chat/completions 再用它的前缀。
        val base = if (trimmed.endsWith(ChatEndpoint.CHAT_COMPLETIONS_PATH)) {
            trimmed.removeSuffix(ChatEndpoint.CHAT_COMPLETIONS_PATH)
        } else {
            trimmed
        }
        return if (ChatEndpoint.endsWithVersionSegment(base)) {
            "$base/models"
        } else {
            "$base/v1/models"
        }
    }

    /**
     * 解析响应体。
     *
     * ## 三种格式，按「先看结构、不猜服务商」判定
     *
     * 上游是按 `apiProviderType` 分支选解析器的。这里改为**看 JSON 结构**：
     *
     * | 结构 | 来源 | 取 id 的方式 |
     * |---|---|---|
     * | `{"data":[{"id":…}]}` | OpenAI 兼容（绝大多数） | `id` |
     * | `{"models":[{"name":…}]}` | Gemini | `name` 的末段 |
     * | `{"models":[{"id":…}]}` / `{"data":[{"id":…,"display_name":…}]}` | Anthropic | `id`，显示名用 `display_name` |
     *
     * 理由：同一个服务商的 `/models` 返回结构比它的对话协议更"兼容" ——
     * 中转站常常是多家的混合，按用户选的 provider 猜反而更容易错。
     * 结构判定是**幂等**的：看的是实际的键，不是我们的假设。
     */
    fun parse(body: String): Result<List<AiModelOption>> {
        val text = body.trim()
        if (text.isEmpty()) return Result.failure(ParseFailure(Failure.EmptyResponse))

        val root = runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?: return Result.failure(ParseFailure(Failure.Parse(text)))

        val obj = root as? JsonObject
            ?: return Result.failure(ParseFailure(Failure.Parse(text)))

        // ① 取数组：data 优先（OpenAI / Anthropic 都用它），其次 models（Gemini）。
        val array = (obj["data"] as? JsonArray)
            ?: (obj["models"] as? JsonArray)
            ?: (obj["result"] as? JsonArray)
            ?: return Result.failure(ParseFailure(Failure.Parse(text)))

        val options = array.mapNotNull { element ->
            val model = element as? JsonObject ?: return@mapNotNull null
            // id 优先；Gemini 只有 name，取末段（`models/gemini-2.0-flash` → `gemini-2.0-flash`）。
            val id = model.string("id")
                ?: model.string("name")?.substringAfterLast('/')
                ?: model.string("model")
                ?: return@mapNotNull null
            if (id.isBlank()) return@mapNotNull null
            // 显示名：display_name（Anthropic）/ displayName（Gemini）/ 末段 name / id。
            val name = model.string("display_name")
                ?: model.string("displayName")
                ?: model.string("label")
                ?: id
            AiModelOption(id = id, name = name)
        }

        if (options.isEmpty()) {
            return Result.failure(ParseFailure(Failure.Parse(text)))
        }
        // 按 id 去重并排序：中转站返回的列表里常有重复项（同模型多个别名），
        // 不去重会让用户看到两条一模一样的行，选哪个都一样 —— 纯困惑。
        return Result.success(
            options.distinctBy { it.id }.sortedBy { it.id.lowercase() },
        )
    }

    /**
     * 只用于携带 [Failure] 的异常（`Result.failure` 需要一个 Throwable）。
     *
     * `message` 取 `reason.description` 而不是 `reason.message`：字段已经
     * 在 [Failure] 里改名（`message` 会被 [Failure.Network.detail] 遮蔽），
     * 这里忘了跟着改就会得到一个 `null` message —— 上层 `?: "未知错误"`
     * 会把它兜住，于是用户看到的是「未知错误」而不是真正的原因，
     * 属于最难查的那类退化。
     */
    class ParseFailure(val reason: Failure) : Exception(reason.description)

    private fun JsonObject.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()
            ?.takeIf { it.isNotBlank() && it != "null" }
}

/**
 * 拉取模型列表的端口。
 *
 * ## 为什么是接口
 *
 * 与 `ChatPersistence` / `LinuxEnvPort` 同一个思路：**端口在里、实现在外**。
 * `:feature:settings` 只依赖这个接口，真正发 HTTP 的 OkHttp 实现留在 `:app`。
 * 于是：
 *  - 设置页的单测可以传一个返回固定列表的假实现；
 *  - `:feature:settings` 不需要知道 OkHttp 存在（它连 core:ai 的传输层都不碰）。
 */
interface ModelCatalogFetcher {
    /**
     * 拉取可用模型。
     *
     * 返回 `Result` 而不是抛异常：失败是**预期路径**（没填 key、服务商不提供
     * `/models`、中转站 404），调用点要能直接把原因显示出来。
     */
    suspend fun fetch(
        baseUrl: String,
        apiKey: String,
        /** 服务商预设，决定认证头与是否需要 key。 */
        providerKind: ProviderKind,
    ): Result<List<AiModelOption>>

    /** 认证方式的抽象：`:core:ai` 不该认识 `ApiProvider`（那是 `:core:model`）。 */
    enum class ProviderKind {
        /** OpenAI 兼容：`Authorization: Bearer`。 */
        Bearer,

        /** OpenRouter：Bearer + 两个标识头（上游强制要求）。 */
        OpenRouter,

        /** 本地推理（Ollama / LM Studio / llama.cpp）：不带认证头。 */
        NoAuth,
    }
}
