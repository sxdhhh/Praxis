package dev.praxis.core.gateway

/**
 * 冷却键：**账号 + 上游真名**。
 *
 * ## 为什么必须是独立类型（文档 6.5）
 *
 * 网关 issue #200 的成因是：某个分支把「客户端请求的对外名」当成上游真名写进
 * 冷却表，于是冷却记在了错误的键位上 —— 账号看起来「没限额」，却被反复选中，
 * 每次都被上游拒一次。这类错误的可怕之处是**没有任何异常**：只是慢、只是贵。
 *
 * 用两个 String 参数（accountId, wireModel）也能编译，但调用点写反顺序同样能编译。
 * 独立类型 + 具名参数让「传错」变成编译错误或一眼可见的语义错误。
 *
 * [wireModel] 必须是通过映射之后**真正发给上游的名字**，不是对外名。
 */
data class CooldownKey(
    val accountId: String,
    val wireModel: String,
)
