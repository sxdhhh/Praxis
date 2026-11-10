package dev.praxis.core.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 出口节点的协议。 */
enum class ProxyNodeKind { Http, Socks }

/**
 * 一个命名出口节点（文档 §5.9）。
 *
 * 粒度与网关一致：**账号级绑定**（与 `base_url_override` 同一层）。
 */
data class ProxyNode(
    val id: String,
    val label: String,
    val kind: ProxyNodeKind = ProxyNodeKind.Http,
    val host: String,
    val port: Int,
    /** 有用户名时才发 Proxy-Authorization（SOCKS 走 Authenticator）。 */
    val username: String? = null,
    val password: String? = null,
)

/**
 * 代理设置（文档 §5.9）。
 *
 * @property binding 账号 id -> 节点 id。没有绑定的账号走直连。
 */
data class ProxySettings(
    val enabled: Boolean = false,
    val nodes: List<ProxyNode> = emptyList(),
    val binding: Map<String, String> = emptyMap(),
)

enum class ProxyNodeError {
    MissingLabel,
    MissingHost,
    BadPort,
    DuplicateLabel,
    UnknownNode,
}

fun ProxyNodeError.userMessage(): String = when (this) {
    ProxyNodeError.MissingLabel -> "给这个出口起个名字（列表里要能认出来）"
    ProxyNodeError.MissingHost -> "要填主机名或 IP"
    ProxyNodeError.BadPort -> "端口要在 1..65535 之间"
    ProxyNodeError.DuplicateLabel -> "已经有同名出口了"
    ProxyNodeError.UnknownNode -> "这个出口已经不在列表里了"
}

/** 出口节点与绑定的校验/归一化（纯函数）。 */
object ProxyRules {

    fun validateNode(node: ProxyNode, existing: List<ProxyNode> = emptyList()): ProxyNodeError? {
        if (node.label.isBlank()) return ProxyNodeError.MissingLabel
        if (node.host.isBlank()) return ProxyNodeError.MissingHost
        if (node.port !in 1..65535) return ProxyNodeError.BadPort
        if (existing.any { it.id != node.id && it.label.equals(node.label, ignoreCase = true) }) {
            return ProxyNodeError.DuplicateLabel
        }
        return null
    }

    fun normalizeNode(node: ProxyNode): ProxyNode = node.copy(
        label = node.label.trim(),
        host = node.host.trim(),
        username = node.username?.trim()?.takeIf { it.isNotEmpty() },
        password = node.password?.takeIf { it.isNotEmpty() },
    )

    /**
     * 这个账号该走哪个出口。
     *
     * ## 一条硬规矩（文档 §5.9 的要点）
     *
     * **余额查询、拉模型清单、转发，三处必须用同一个出口。**
     * 网关踩过的坑是「清单能拉、请求 403」—— 三处各算一次出口就会这样，
     * 而那种错误最难查。所以出口只由这一个函数决定，三处都调它。
     *
     * @return null = 直连（代理没开、账号没绑定、或绑定的节点已不存在）。
     */
    fun egressFor(accountId: String, settings: ProxySettings): ProxyNode? {
        if (!settings.enabled) return null
        val nodeId = settings.binding[accountId] ?: return null
        // 绑定指向一个已被删掉的节点：直连而不是报错 —— 删节点时不应该让
        // 已经绑它的账号全废掉。界面上会把这个绑定显示成「未绑定」。
        return settings.nodes.firstOrNull { it.id == nodeId }
    }

    /** 清掉指向不存在节点的绑定（删节点后调用）。 */
    fun pruneBinding(settings: ProxySettings): ProxySettings {
        val ids = settings.nodes.map { it.id }.toSet()
        val kept = settings.binding.filterValues { it in ids }
        return if (kept.size == settings.binding.size) settings else settings.copy(binding = kept)
    }
}

/** `gateway_proxy` 的编解码。 */
object ProxySettingsCodec {

    fun encode(settings: ProxySettings): String = buildJsonObject {
        put("enabled", settings.enabled)
        put("nodes", buildJsonArray {
            settings.nodes.forEach { node ->
                addJsonObject {
                    put("id", node.id)
                    put("label", node.label)
                    put("kind", node.kind.name)
                    put("host", node.host)
                    put("port", node.port)
                    node.username?.let { put("username", it) }
                    node.password?.let { put("password", it) }
                }
            }
        })
        put("binding", buildJsonObject {
            settings.binding.forEach { (accountId, nodeId) -> put(accountId, nodeId) }
        })
    }.toString()

    /** @param text null/空白 = 没配过（返回 null）；坏内容返回 failure。 */
    fun decode(text: String?): Result<ProxySettings>? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val root: JsonObject = Json.parseToJsonElement(text).jsonObject
            val nodes = root["nodes"]?.jsonArray?.map { element ->
                val node = element.jsonObject
                val id = node["id"]?.jsonPrimitive?.contentOrNull ?: error("节点缺 id")
                val host = node["host"]?.jsonPrimitive?.contentOrNull ?: error("节点缺 host")
                val port = node["port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: error("节点缺 port")
                ProxyNode(
                    id = id,
                    label = node["label"]?.jsonPrimitive?.contentOrNull ?: id,
                    kind = when (node["kind"]?.jsonPrimitive?.contentOrNull) {
                        "Socks" -> ProxyNodeKind.Socks
                        else -> ProxyNodeKind.Http
                    },
                    host = host,
                    port = port,
                    username = node["username"]?.jsonPrimitive?.contentOrNull,
                    password = node["password"]?.jsonPrimitive?.contentOrNull,
                )
            } ?: emptyList()
            val binding = root["binding"]?.jsonObject?.mapValues { entry ->
                entry.value.jsonPrimitive.content
            } ?: emptyMap()
            ProxyRules.pruneBinding(
                ProxySettings(
                    enabled = root["enabled"]?.jsonPrimitive?.contentOrNull
                        ?.toBooleanStrictOrNull() ?: false,
                    nodes = nodes,
                    binding = binding,
                ),
            )
        }
    }
}
