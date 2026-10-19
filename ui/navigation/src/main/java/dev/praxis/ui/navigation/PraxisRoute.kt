package dev.praxis.ui.navigation

import dev.praxis.domain.model.PraxisDomain
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * 导航栈元素。
 *
 * 与旧实现（sealed + 手写 listSaver）的三处差异，都是被 miuix-nav 的实际要求逼出来的：
 *
 * 1. **必须 `@Serializable`**：`rememberNavBackStack` 用 kotlinx.serialization 把整个栈
 *    编成一个字符串存进 Bundle。key 不可序列化会在首帧吐 `SerializationException`。
 * 2. **domain 存 id 字符串，不存枚举**：枚举直接进序列化契约会把 `core:model` 的
 *    枚举项名字变成线上格式——改一次枚举名，所有旧栈全部恢复失败。存 id 则只是字符串，
 *    未知 id 可以在读取侧降级。
 * 3. **`domain` 变成计算属性**：它不参与序列化（非构造属性），只做「id → 枚举」的解析。
 *
 * 为什么不用 `domain: PraxisDomain` 构造参数 + `@Serializable` 枚举：
 * `core:model` 是纯 JVM 模块、不加序列化插件，枚举没法标 `@Serializable`；
 * 而把它搬进 UI 层会破坏「模型层无 UI 依赖」这条红线。存 id 是代价最小的办法。
 */
@Serializable
sealed interface PraxisRoute : NavKey {

    /** 序列化字段：域 id（对应 [PraxisDomain.id]）。 */
    val domainId: String

    /**
     * 域枚举。
     *
     * 未知 id（枚举被改名/删项、或旧版本存的栈）一律回落 [PraxisDomain.CHAT]，
     * 不抛异常——恢复状态失败让整个 Activity 白屏，代价远大于「回到对话页」。
     */
    val domain: PraxisDomain
        get() = PraxisDomain.byId(domainId) ?: PraxisDomain.CHAT

    /** 域首页。 */
    @Serializable
    data class Home(override val domainId: String) : PraxisRoute

    /**
     * 域内子页。
     *
     * [arg] 是域内的块 id / 设置条目 key；[title] 只是兜底标题（清单改名时仍可显示）。
     */
    @Serializable
    data class Detail(
        override val domainId: String,
        val arg: String,
        val title: String = "",
    ) : PraxisRoute
}

/** 路由工厂：调用侧只写枚举，不手拼 id 字符串。 */
object PraxisRoutes {

    fun home(domain: PraxisDomain): PraxisRoute = PraxisRoute.Home(domain.id)

    fun detail(domain: PraxisDomain, arg: String, title: String = ""): PraxisRoute =
        PraxisRoute.Detail(domainId = domain.id, arg = arg, title = title)
}
