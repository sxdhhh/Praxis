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
     * ## 一条硬规则：**网页登录只给真有网页登录的家**
     *
     * 旧实现是「只要有 http 地址就加一条『打开登录页』」。结果 `api.openai.com`、
     * `api.deepseek.com` 这些只有 API Key 的家也被挂了网页授权入口 —— 点开是官网首页，
     * 用户在页面里找不到任何「授权」按钮，只能以为是自己搞错了。
     *
     * 现在登录方式由 [ProviderLoginKind] 决定（事实来自上游 preset 目录与插件文档），
     * 这里只负责把它翻成能点的入口。
     *
     * @param loginKind 该家**实际**的登录方式；未知的家按 [ProviderLoginKind.ApiKey] 走，
     *   那是最保守、也一定能走完的一条。
     */
    fun forProvider(
        providerLabel: String,
        baseUrl: String?,
        loginKind: ProviderLoginKind = ProviderLoginKind.ApiKey,
    ): List<GatewayLoginMethod> {
        val landing = baseUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?.takeIf { GatewayAccountDraftRules.isHttpUrl(it) }

        // 凭据方式：每种家的「怎么拿到那串东西」不一样，文案必须跟着变，
        // 否则用户会照着错的话去错的地方找 Key。
        val credentialMethod = when (loginKind) {
            ProviderLoginKind.ApiKey, ProviderLoginKind.Unknown -> GatewayLoginMethod(
                type = GatewayLoginType.Api,
                label = "直接填 API Key",
                instructions = "在 " + providerLabel + " 的控制台里新建一个 Key，粘到这里。",
                landingUrl = null,
            )

            ProviderLoginKind.WebLogin -> GatewayLoginMethod(
                type = GatewayLoginType.Api,
                label = "粘贴登录后的 Key / 令牌",
                instructions = "在 " + providerLabel + " 官网登录后，把控制台里的 Key 或令牌粘到这里。",
                landingUrl = null,
            )

            ProviderLoginKind.DeviceCode -> GatewayLoginMethod(
                type = GatewayLoginType.Api,
                label = "粘贴设备授权拿到的令牌",
                instructions = "该家用官方设备授权页登录；授权完成后把拿到的令牌粘到这里。",
                landingUrl = null,
            )

            ProviderLoginKind.TokenImport -> GatewayLoginMethod(
                type = GatewayLoginType.Api,
                label = "粘贴访问令牌",
                instructions = "从官方客户端里取出访问令牌粘到这里（Praxis 不读别的 App 的存储）。",
                landingUrl = null,
            )

            ProviderLoginKind.SmsCode -> GatewayLoginMethod(
                type = GatewayLoginType.Api,
                label = "粘贴短信登录后的令牌",
                instructions = "该家用手机号验证码登录官方客户端或网页，登录后把拿到的令牌粘到这里。",
                landingUrl = null,
            )

            ProviderLoginKind.CookieImport -> GatewayLoginMethod(
                type = GatewayLoginType.Api,
                label = "粘贴网页 Cookie / userToken",
                instructions = "该家用网页会话：在官网登录后，把 Cookie 或 userToken 粘到这里。",
                landingUrl = null,
            )
        }

        val methods = mutableListOf(credentialMethod)

        // 只有**确认存在网页登录**、且手上有一个像样地址时，才给「打开登录页」。
        // 两个条件缺一不可：少一个都会变成打开空白页 / 打开一个没有授权入口的首页。
        if (loginKind == ProviderLoginKind.WebLogin && landing != null) {
            methods += GatewayLoginMethod(
                type = GatewayLoginType.OAuth,
                label = "打开登录页（网页授权）",
                instructions = "在打开的页面里登录，然后复制 Key 或令牌回来粘贴。" +
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
