package dev.praxis.ui.navigation

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import dev.praxis.domain.model.PraxisDomain

/**
 * 页面栈元素。Phase 1 只有「域首页」和「域内子页」两种，
 * 子页用 [arg] 携带实体 id，避免过早引入完整路由表。
 *
 * 这里刻意用 sealed interface + 手写 Saver，而不是引 navigation-compose：
 * Phase 1 的栈深度只有 2，引整套 Navigation 反而会把「返回键 = 关子页」
 * 这件小事变成图配置问题。等 P4 出现真正的多级栈时再换。
 */
sealed interface PraxisRoute {
    val domain: PraxisDomain

    data class Home(override val domain: PraxisDomain) : PraxisRoute

    data class Detail(
        override val domain: PraxisDomain,
        val arg: String,
        val title: String,
    ) : PraxisRoute
}

/**
 * [PraxisRoute] 的进程重建 Saver。
 *
 * 落地为 `listOf(kind, domainId, arg, title)` 这样的平坦字符串列表：
 * Bundle 里只放 String 最稳，跨版本也不会因为 Parcelable 类变更而丢状态。
 *
 * 编解码抽成 [encodeRoute] / [decodeRoute] 两个纯函数：
 * 纯函数能直接单测，Saver 只是把两者接到 Compose 的保存机制上。
 *
 * 第二个泛型参数按 listSaver 的实际返回类型写 `Any` —— `Saver<Original, Saveable>`
 * 没有声明变型，写成 `Saver<PraxisRoute, List<String>>` 会编译不过。
 */
val PraxisRouteSaver: Saver<PraxisRoute, Any> = listSaver<PraxisRoute, String>(
    save = { route -> encodeRoute(route) },
    restore = { saved -> decodeRoute(saved) },
)

/** 路由 → 平坦字符串列表（第 0 位是类型标记，第 1 位恒为 domainId）。 */
fun encodeRoute(route: PraxisRoute): List<String> = when (route) {
    is PraxisRoute.Home -> listOf(KIND_HOME, route.domain.id)
    is PraxisRoute.Detail -> listOf(KIND_DETAIL, route.domain.id, route.arg, route.title)
}

/**
 * 平坦字符串列表 → 路由。
 *
 * 容错约定：domainId 认不出来（枚举被改名/删项）时回落到 [PraxisDomain.CHAT]，
 * 而不是抛异常 —— 恢复状态失败让整个 Activity 白屏，代价远大于「回到对话页」。
 */
fun decodeRoute(saved: List<String>): PraxisRoute {
    val domain = PraxisDomain.byId(saved.getOrNull(1)) ?: PraxisDomain.CHAT
    return when (saved.firstOrNull()) {
        KIND_DETAIL -> PraxisRoute.Detail(
            domain = domain,
            arg = saved.getOrNull(2).orEmpty(),
            title = saved.getOrNull(3).orEmpty(),
        )

        // KIND_HOME 与「任意损坏数据」都落到首页：宁可少一层，不要空白屏。
        else -> PraxisRoute.Home(domain)
    }
}

private const val KIND_HOME = "home"
private const val KIND_DETAIL = "detail"