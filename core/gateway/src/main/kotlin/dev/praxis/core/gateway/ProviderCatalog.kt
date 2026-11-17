package dev.praxis.core.gateway

/**
 * 一个提供商「在 Praxis 里怎么用」。
 *
 * 这个区分是这套目录存在的理由：**Praxis 不是网关**。它能自己发请求的只有
 * OpenAI / Anthropic 兼容的 API 家；网关里那批「Agent 提供商」「网页提供商」
 * 是客户端伪装 + 网页会话，实现住在网关的插件里（Rust 进程），Praxis 里没有、
 * 也不该假装有。
 */
enum class ProviderKind {
    /** 直接连：Praxis 自己拼请求、自己解流。 */
    Api,

    /** Agent 插件家：登录与伪装都在网关里，Praxis 只能经网关用。 */
    AgentPlugin,

    /** 网页插件家：同上，且上游是网页会话（userToken / Cookie）。 */
    WebPlugin,
}

/**
 * 该家的登录方式 —— **上游真有的哪一种**，不是我们挑的。
 *
 * 之前这里只有一个 `baseUrl != null` 就给出「打开登录页」的规则，于是
 * `api.openai.com`、`api.deepseek.com` 这类根本不存在网页登录的 API 家
 * 也被挂了一个网页授权入口：点开是个登录不了的官网首页，用户以为是自己操作错了。
 */
enum class ProviderLoginKind {
    /** 只要一个 API Key（或等价令牌），没有网页登录这回事。 */
    ApiKey,

    /** 要在浏览器里登录（含官方 CLI 的本机回调）。 */
    WebLogin,

    /** 官方设备授权页：页面里登录授权，本方轮询收账号。 */
    DeviceCode,

    /** 粘贴令牌，或导入本机已装客户端的登录态。 */
    TokenImport,

    /** 需要网页会话 Cookie。 */
    CookieImport,

    /**
     * 手机号验证码。
     *
     * 与 [WebLogin] 分开：它没有网页可打开，走的是上游短信接口
     * （上游申报的就是 `LoginMethod::Sms`）。混成「网页登录」会让界面
     * 打开一个没有意义的页面。
     */
    SmsCode,

    /** 上游/插件文档没写明 —— 不猜。 */
    Unknown,
}

/**
 * 目录里的一家。
 *
 * [note] 只在**有出处**时写，写的是「凭什么这么判」，不是营销文案。
 */
data class ProviderEntry(
    val id: String,
    val label: String,
    val order: Int,
    val kind: ProviderKind,
    val login: ProviderLoginKind,
    val note: String = "",

) {
    /** 能不能由 Praxis 自己发请求（否则必须经网关）。 */
    val direct: Boolean get() = kind == ProviderKind.Api
}

/**
 * 全部已知提供商的**单一真相**。
 *
 * ## 数据出处（不要凭印象改）
 *
 * 1. **API 家（24）**：`Agent2API/desktop-tauri/ui/preset-providers.js` 的 PRESET_PROVIDERS，
 *    该文件自述抄自 9Router 的 `open-sse/providers/registry`。全部只要 API Key。
 * 2. **Agent 插件家（34）与网页插件家（4）**：`~/.agent2api/plugins/<pack>/pack.json` 的
 *    `providers` 数组（id / label / order / default_enabled），以插件自述的能力说明
 *    （`ui/provider-docs.json`）与插件仓库 README 的支持矩阵判断登录方式。
 * 3. 归类为 [ProviderKind.Api] 的门槛是「Praxis 能用标准协议说清」；其余一律
 *    标成需要网关 —— 宁可少列，不做假入口。
 *
 * ## 登录方式只填有证据的
 *
 * 拿到明确写法的（commandcode 静态 API Key、qwenwork 设备授权页、doubaowork 完整 Cookie、
 * raccoon 导入本机登录态、loomy/kiro/astudio 需网页登录、deepseek-web 用 userToken+Cookie）
 * 按证据写；其余写 [ProviderLoginKind.Unknown]。Unknown 的界面表现为「只给粘贴 Key」，
 * 那是最保守、也一定能走完的一条。
 */
object ProviderCatalog {

    /**
     * agent2api 网关入口：地址由用户填（网关的 /v1）；Key 用面板里建的网关密钥。
     * 33 家 Agent 插件提供商 + 4 家网页提供商的模型全部经它可选。
     * API preset 家已删除 —— 模型配置页本来就能填任意 API 地址和 Key。
     */
    private val API: List<ProviderEntry> = listOf(
        ProviderEntry(
            "agent2api", "Agent2API 网关", 0, ProviderKind.Api, ProviderLoginKind.ApiKey,
            "地址由用户填（网关的 /v1）；Key 用面板里建的网关密钥",
        ),
    )
    /**
     * Agent 插件家：登录与客户端伪装都在网关的插件进程里。
     *
     * 登录方式逐家来自取证矩阵（lookme/index/read/07-provider-login-matrix.md，
     * 基准 ProvidersPlugin/Agent2Providers main@3055f41）：协议申报的 `LoginMethod`
     * 与 `login_start` 实现双证据，没有证据的写 Unknown。
     */
    private val AGENT: List<ProviderEntry> = listOf(
        ProviderEntry("commandcode", "Command Code", 1, ProviderKind.AgentPlugin, ProviderLoginKind.ApiKey, "静态 API Key；另有设备指纹预请求"),
        ProviderEntry("modelscope", "ModelScope 国内版", 2, ProviderKind.AgentPlugin, ProviderLoginKind.ApiKey, "ms- 令牌，同一串兼作 Cookie 值"),
        ProviderEntry("modelscope-intl", "ModelScope 国际版", 3, ProviderKind.AgentPlugin, ProviderLoginKind.ApiKey, "同上，两站账号体系独立"),
        ProviderEntry("orcaterm", "OrcaTerm", 4, ProviderKind.AgentPlugin, ProviderLoginKind.ApiKey, "可粘 JWT / data.bin；另支持微信扫码与桌面端导入"),
        ProviderEntry("loomy", "Loomy", 5, ProviderKind.AgentPlugin, ProviderLoginKind.SmsCode, "上游申报 LoginMethod::Sms（手机号验证码）"),
        ProviderEntry("lobsterai", "LobsterAI", 6, ProviderKind.AgentPlugin, ProviderLoginKind.WebLogin, "网页登录，回调落 127.0.0.1"),
        ProviderEntry("raccoon", "小浣熊", 7, ProviderKind.AgentPlugin, ProviderLoginKind.TokenImport, "可粘贴 auth.json / accessToken，或实时读桌面端"),
        ProviderEntry("atomcode", "AtomCode", 8, ProviderKind.AgentPlugin, ProviderLoginKind.WebLogin, "网页登录；授权在上游终结，无本机回调"),
        ProviderEntry("autoclaw", "AutoClaw 国内版", 10, ProviderKind.AgentPlugin, ProviderLoginKind.SmsCode, "国内版唯一入口是手机号验证码"),
        ProviderEntry("autoclaw-intl", "AutoClaw 国际版", 11, ProviderKind.AgentPlugin, ProviderLoginKind.WebLogin, "两跳 OAuth，本机回调"),
        ProviderEntry("officeace", "OfficeAce 果办", 12, ProviderKind.AgentPlugin, ProviderLoginKind.WebLogin, "华为云 OAuth；云端回调页不回跳本机"),
        ProviderEntry("zcode", "ZCode 国内版", 13, ProviderKind.AgentPlugin, ProviderLoginKind.WebLogin, "CLI 轮询授权，上游明确拒绝本机回调"),
        ProviderEntry("zcode-intl", "ZCode 国际版", 14, ProviderKind.AgentPlugin, ProviderLoginKind.WebLogin, "同一套 CLI 轮询"),
        ProviderEntry("workbuddy", "WorkBuddy 国内版", 15, ProviderKind.AgentPlugin, ProviderLoginKind.WebLogin, "打开 authUrl 后轮询取 token，无本机回调"),
        ProviderEntry("workbuddy-intl", "WorkBuddy 国际版", 16, ProviderKind.AgentPlugin, ProviderLoginKind.WebLogin, "同上，platform 段不同"),
        ProviderEntry("cline-free", "Cline 免费池", 17, ProviderKind.AgentPlugin, ProviderLoginKind.DeviceCode, "WorkOS 设备码（RFC 8628）"),
        ProviderEntry("cline-pass", "Cline 订阅池", 18, ProviderKind.AgentPlugin, ProviderLoginKind.DeviceCode, "同一实现按池参数化"),
        ProviderEntry("kuku", "KukuAI 库库", 19, ProviderKind.AgentPlugin, ProviderLoginKind.CookieImport, "网页登录后取 BDUSS，或直接粘 Cookie"),
        ProviderEntry("monkeycode", "MonkeyCode 国内版", 20, ProviderKind.AgentPlugin, ProviderLoginKind.WebLogin, "内嵌窗口登录 + 验证码，宿主回收 session"),
        ProviderEntry("monkeycode-intl", "MonkeyCode 国际版", 21, ProviderKind.AgentPlugin, ProviderLoginKind.WebLogin, "同上，Region 参数化"),
        ProviderEntry("qoder", "Qoder 中国版", 22, ProviderKind.AgentPlugin, ProviderLoginKind.DeviceCode, "PKCE 设备授权；另可粘 PAT"),
        ProviderEntry("qoder-intl", "Qoder 国际版", 23, ProviderKind.AgentPlugin, ProviderLoginKind.DeviceCode, "同上"),
        ProviderEntry("qwenwork", "千问办公 国内版", 24, ProviderKind.AgentPlugin, ProviderLoginKind.DeviceCode, "官方设备授权页，轮询收账号；无 PAT"),
        ProviderEntry("qwenwork-intl", "千问办公 国际版", 25, ProviderKind.AgentPlugin, ProviderLoginKind.DeviceCode, "与国内版同一套设备授权"),
        ProviderEntry("trae", "Trae", 26, ProviderKind.AgentPlugin, ProviderLoginKind.WebLogin, "网页登录，302 回 127.0.0.1"),
        ProviderEntry("catpaw", "CatPaw", 27, ProviderKind.AgentPlugin, ProviderLoginKind.WebLogin, "passport 登录后把 token POST 到本机；另可粘 accessToken"),
        ProviderEntry("accio", "Accio 国际版", 28, ProviderKind.AgentPlugin, ProviderLoginKind.WebLogin, "loopback + PKCE(S256)"),
        ProviderEntry("accio-cn", "Accio 国内版", 29, ProviderKind.AgentPlugin, ProviderLoginKind.WebLogin, "同上，区域 CN"),
        ProviderEntry("codearts", "CodeArts", 30, ProviderKind.AgentPlugin, ProviderLoginKind.WebLogin, "loopback + PKCE + DPoP；另可粘 AK/SK"),
        ProviderEntry("antigravity", "Antigravity", 31, ProviderKind.AgentPlugin, ProviderLoginKind.WebLogin, "Google OAuth，一轮一个随机端口"),
        ProviderEntry("doubaowork", "豆包工作", 32, ProviderKind.AgentPlugin, ProviderLoginKind.CookieImport, "官方页面登录后保存完整 Cookie"),
        ProviderEntry("kiro", "Kiro", 33, ProviderKind.AgentPlugin, ProviderLoginKind.WebLogin, "门户 OAuth，监听 Kiro IDE 那组端口；另可导入登录态"),
        ProviderEntry("astudio", "AStudio", 34, ProviderKind.AgentPlugin, ProviderLoginKind.WebLogin, "本机内嵌登录页（手机号/密码 + 滑块）"),
    )

    /** 网页插件家：上游是网页会话，凭据是 Cookie / userToken。 */
    private val WEB: List<ProviderEntry> = listOf(
        ProviderEntry("chatglm", "智谱清言", 0, ProviderKind.WebPlugin, ProviderLoginKind.Unknown, "实现在网页插件包内，源码未公开，未确认"),
        ProviderEntry("deepseek-web", "DeepSeek 网页", 1, ProviderKind.WebPlugin, ProviderLoginKind.CookieImport, "插件文档：使用本人 userToken 与 Cookie"),
        ProviderEntry("tabbit", "Tabbit 国内版", 2, ProviderKind.WebPlugin, ProviderLoginKind.Unknown, "源码未公开，未确认"),
        ProviderEntry("tabbit-intl", "Tabbit 国际版", 3, ProviderKind.WebPlugin, ProviderLoginKind.Unknown, "源码未公开，未确认"),
    )
    /** 全部条目：API 家在前，插件家在后（与界面分组一致）。 */
    fun all(): List<ProviderEntry> = API + AGENT + WEB

    /** 只有 Praxis 能直接连的那些。 */
    fun direct(): List<ProviderEntry> = API

    /** 必须经网关才能用的那些（Agent 插件 + 网页插件）。 */
    fun viaGateway(): List<ProviderEntry> = AGENT + WEB

    fun of(id: String): ProviderEntry? =
        all().firstOrNull { it.id.equals(id, ignoreCase = true) }

    fun kindOf(id: String): ProviderKind? = of(id)?.kind

    /** 未知 id（自定义家）按 [ProviderLoginKind.ApiKey] 处理 —— 那是最保守的路径。 */
    fun loginOf(id: String): ProviderLoginKind = of(id)?.login ?: ProviderLoginKind.ApiKey

    fun labelOf(id: String): String = of(id)?.label ?: id

    /**
     * 登录方式的人话。
     *
     * 界面与报告都读这一处：同一种登录方式在别处被写成另一种说法，
     * 用户就会照着错的说明去找凭据。
     */
    fun loginLabel(id: String): String = when (loginOf(id)) {
        ProviderLoginKind.ApiKey -> "直填 API Key"
        ProviderLoginKind.WebLogin -> "网页登录"
        ProviderLoginKind.DeviceCode -> "官方设备授权"
        ProviderLoginKind.TokenImport -> "粘贴 / 导入令牌"
        ProviderLoginKind.CookieImport -> "网页 Cookie / userToken"
        ProviderLoginKind.SmsCode -> "手机号验证码"
        ProviderLoginKind.Unknown -> "上游未声明登录方式"
    }

    fun counts(): Map<ProviderKind, Int> = ProviderKind.entries.associateWith { kind ->
        all().count { it.kind == kind }
    }
}