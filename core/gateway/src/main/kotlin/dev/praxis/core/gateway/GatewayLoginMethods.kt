package dev.praxis.core.gateway

/** 登录方式（文档 §9.9 的 `LoginMethodMetadata` 的本地子集）。 */
enum class GatewayLoginType {
    /** 直接填 API Key。 */
    Api,

    /** 打开上游的登录页（H5 容器），登录后把 Key/令牌粘贴回来。 */
    OAuth,
}

/**
 * 一种登录方式。
 *
 * ## 为什么没有 `prompts` / `pollIntervalMs` / `login/wait`
 *
 * 那些字段属于**网关的插件家**（它知道每家的授权端点、轮询接口与参数表）。
 * Praxis 面对的是「用户自己填地址的家」，没有那份元数据 —— 编一份出来
 * 就会变成「点了没反应的按钮」。所以这里只留两种**真的能走完**的方式：
 * 直填 Key、或者去上游登录页登完再粘回来。
 */
data class GatewayLoginMethod(
    val type: GatewayLoginType,
    val label: String,
    val instructions: String?,
    /** 打开登录页时的初始地址（可改）。 */
    val landingUrl: String?,
)

/** 登录方式的生成规则（纯函数，便于单测）。 */
object GatewayLoginMethods {

    /**
     * 给一家生成可选登录方式。
     *
     * 顺序固定：**直填 Key 在前**。它是唯一一定成功的方式（不依赖页面能否打开），
     * 而「打开登录页」在 WebView 被墙、被拦 UA、需要第三方 Cookie 时都可能卡住。
     */
    fun forProvider(providerLabel: String, baseUrl: String?): List<GatewayLoginMethod> {
        val methods = mutableListOf(
            GatewayLoginMethod(
                type = GatewayLoginType.Api,
                label = "直接填 API Key",
                instructions = "在 " + providerLabel + " 的控制台里新建一个 Key，粘到这里。",
                landingUrl = null,
            ),
        )
        // 只有拿到一个像样的 http(s) 地址时才提供「打开登录页」：
        // 没有地址的话那个按钮会打开空白页，等于假入口。
        val landing = baseUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (landing != null && GatewayAccountDraftRules.isHttpUrl(landing)) {
            methods += GatewayLoginMethod(
                type = GatewayLoginType.OAuth,
                label = "打开登录页（网页授权）",
                instructions = "在打开的页面里登录，然后复制 API Key 回来粘贴。" +
                    "Praxis 不代管你的账号密码。",
                landingUrl = landing,
            )
        }
        return methods
    }

    /**
     * 从回调 URL 或用户粘贴的内容里取出可用的凭据。
     *
     * ## 为什么两种都收
     *
     * 网页登录的结局有两种：拿到带 `code`/`api_key` 的回调地址，或直接复制一段 Key。
     * 文档 §9.9 的 `manualCallback` 说的就是「容器外完成」这条路 —— 两种输入都收，
     * 用户不必知道自己在走哪条。
     */
    fun extractCredential(pasted: String): String {
        val text = pasted.trim()
        if (text.isEmpty()) return ""
        // 回调地址：把常见参数名里的值取出来。
        listOf("api_key=", "apiKey=", "code=", "token=", "access_token=").forEach { key ->
            val index = text.indexOf(key)
            if (index >= 0) {
                val rest = text.substring(index + key.length)
                val value = rest.substringBefore('&').trim()
                if (value.isNotEmpty()) return value
            }
        }
        // 不是地址就当作直接粘贴的 Key。
        return text
    }
}
