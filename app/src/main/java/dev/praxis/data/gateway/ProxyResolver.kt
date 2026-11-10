package dev.praxis.data.gateway

import dev.praxis.core.gateway.ProxyNode
import dev.praxis.core.gateway.ProxyNodeKind
import dev.praxis.core.gateway.ProxySettings
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Credentials
import okhttp3.OkHttpClient

/**
 * 每账号出口（文档 §5.9、§10 指定的文件位置）。
 *
 * ## 一条硬规矩：三处必须同一个出口
 *
 * 「余额查询、拉模型清单、转发」只要有一处没走代理，就会出现
 * **「清单能拉、请求 403」** —— 网关踩过，而且这种错误最难查（两处结论看起来都合理）。
 * 所以出口只由 `ProxyRules.egressFor` 决定一次，三处都从这儿取客户端。
 *
 * ## 为什么按节点缓存客户端
 *
 * OkHttp 的代理是**客户端属性**（`proxy` 与 `proxyAuthenticator` 都在 Builder 上），
 * 不能逐请求改。而节点是用户随时可改的设置，所以这里按「节点 id + 认证信息」
 * 缓存已建好的客户端；节点改了内容就换一把新的（key 里带 host/port/用户名）。
 */
class ProxyResolver(
    /** 每次现取：用户在设置页改完，下一次请求就该按新的走。 */
    private val settings: () -> ProxySettings,
    /** 基线客户端（超时等已配好）；null = 直连时用调用方自己的客户端。 */
    private val baseClient: () -> OkHttpClient = { OkHttpClient() },
) {

    private val cache = ConcurrentHashMap<String, OkHttpClient>()

    /** 这个账号的出口；null = 直连。 */
    fun egressFor(accountId: String): ProxyNode? =
        dev.praxis.core.gateway.ProxyRules.egressFor(accountId, settings())

    /**
     * 这个账号该用的 OkHttp 客户端。
     *
     * 没绑定（或代理没开）时返回 null —— 调用方用基线客户端，
     * **不要**在这里偷偷造一个直连客户端：那会让「客户端」在两处各建一份。
     */
    fun clientFor(accountId: String): OkHttpClient? {
        val node = egressFor(accountId) ?: return null
        val key = listOf(node.id, node.host, node.port.toString(), node.username.orEmpty()).joinToString("|")
        return cache.getOrPut(key) { build(node) }
    }

    private fun build(node: ProxyNode): OkHttpClient {
        val address = InetSocketAddress.createUnresolved(node.host, node.port)
        val type = when (node.kind) {
            ProxyNodeKind.Socks -> Proxy.Type.SOCKS
            ProxyNodeKind.Http -> Proxy.Type.HTTP
        }
        val builder = baseClient().newBuilder().proxy(Proxy(type, address))
        val username = node.username
        val password = node.password
        if (!username.isNullOrEmpty()) {
            builder.proxyAuthenticator { _, response ->
                val credential = Credentials.basic(username, password.orEmpty())
                response.request.newBuilder()
                    .header("Proxy-Authorization", credential)
                    .build()
            }
        }
        return builder.build()
    }

    /** 设置变了（节点删了/改了）就丢弃缓存。 */
    fun invalidate() = cache.clear()
}
