package dev.praxis.data.imagegen

/** 远端生图要用的一个网关账号。 */
data class ImageGatewayAccount(
    val accountId: String,
    val baseUrl: String,
    val apiKey: String,
)

/**
 * 让远端生图**也走网关账号池**（文档 §5.1 的 Images 入口 + §7.2 的限流/熔断）。
 *
 * ## 为什么值得接
 *
 * 在接之前，远端生图用的是「Praxis 全局 API Key」——那是一把裸 key：
 * 换不了号、不吃退避、不计入熔断，也和「网关」那套完全脱节。
 * 用户配了一池账号，画图时却用不上，这不符合直觉。
 *
 * ## 与文本请求的关系
 *
 * 选号用的是**同一套** `RoutePlanner` 与 `RouteState`：所以「某账号刚被限流」
 * 这类运行态对画图同样生效 —— 不需要另建一套。
 *
 * ## 什么时候回退到全局 Key
 *
 * [pick] 返回 null 时（该家不在池里、或池里此刻没有可用账号）。
 * 这是**显式**的回退，不是兜底：远端目标本来就允许直连一个没进池的服务商
 * （比如用户只填了 baseUrl + key 的场景），那种情况下池里当然没有它。
 * 界面上的说明写清了这条顺序。
 */
interface ImageGatewayAccounts {

    /**
     * 按当前路由策略选一个可用账号。
     *
     * @return null = 该家不在池里或没有可用账号（调用方回退全局 Key）。
     */
    suspend fun pick(provider: String, model: String): ImageGatewayAccount?

    /**
     * 把这一次的结果回报给运行态：退避/熔断/限额据此更新。
     *
     * @param kind 失败分类；成功传 null。
     */
    suspend fun report(
        accountId: String,
        success: Boolean,
        kind: dev.praxis.core.ai.AiErrorKind?,
    )
}
