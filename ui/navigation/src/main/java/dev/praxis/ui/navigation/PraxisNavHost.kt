package dev.praxis.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import dev.praxis.domain.model.PraxisDomain
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransition

/**
 * Praxis 导航栈的宿主。
 *
 * ## 为什么要换掉手写的 listSaver
 *
 * 旧实现只能表达「域首页 → 一层子页」，返回键是手动 `BackHandler` 改路由。
 * 三个真实缺陷：
 *  - 没有预测性返回动画（左滑到一半松手回弹做不到）；
 *  - 栈深只有 1，设置子页里再进一层无处可去；
 *  - 用「改状态」代替「出栈」，转场是瞬时跳变，不符合用户对二级页的期待。
 *
 * miuix-nav 一次把这三件都解决了：栈是真 `SnapshotStateList`、转场由
 * `NavTransition` 驱动、预测性返回由 `PredictiveBackHandlerWithSessions` 接管。
 *
 * ## 为什么入口是 [NavBackStack] 而不是内部新建
 *
 * 底栏（`PraxisBottomBar`）与顶栏标题都要读「当前在哪」，`:app` 也需要在
 * `Home` / `Detail` 之间做「点底栏换域」这类操作。栈由调用方持有，
 * 本组件只负责渲染与转场，职责单一且可测试。
 */
@Composable
fun PraxisNavHost(
    backStack: NavBackStack,
    transition: NavTransition = NavTransitions.MiuixDefault,
    effects: NavDisplayEffects = NavDisplayEffects(),
    modifier: Modifier = Modifier,
    entryContent: @Composable (NavEntryScope) -> Unit,
) {
    // 侧滑返回方向是「物理方向」，不随布局方向镜像：
    // LTR 下右滑返回（从左边缘进），RTL 下左滑返回。
    val swipeDirection = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
        NavSwipeDirection.RightToLeft
    } else {
        NavSwipeDirection.LeftToRight
    }

    NavDisplay(
        backStack = backStack,
        onBack = { popRoute(backStack) },
        transition = transition,
        effects = effects,
        modifier = modifier.fillMaxSize(),
    ) {
        entry<PraxisRoute.Home>(swipeDismiss = swipeDirection) { route ->
            entryContent(NavEntryScope(route, backStack))
        }
        entry<PraxisRoute.Detail>(swipeDismiss = swipeDirection) { route ->
            entryContent(NavEntryScope(route, backStack))
        }
    }
}

/**
 * 一个已入栈路由 + 它所在的栈。
 *
 * 页面通过它导航，而不是通过闭包回调：需要在详情页里再进一层时，
 * 闭包回调会让 `:app` 的参数量爆炸（每多一层多一个回调）；
 * 给页面一个栈引用，导航动作就变成 `push` / `pop` 两个动作。
 */
class NavEntryScope(
    val route: PraxisRoute,
    val backStack: NavBackStack,
) {
    /** 压栈。重复压同一个路由会被忽略（miuix-nav 要求 contentKey 唯一）。 */
    fun push(next: PraxisRoute) = pushRoute(backStack, next)

    /** 出栈。栈底不弹，避免出现空栈（NavDisplay 会直接抛异常）。 */
    fun pop() = popRoute(backStack)
}

/**
 * 压栈，幂等。
 *
 * 为什么必须幂等：`NavDisplay` 在 reconcile 时对重复 contentKey 直接抛
 * `IllegalArgumentException`。双击一个设置项会同一个路由入栈两次，
 * 这不是用户错误而是正常手速，必须在入口就挡掉。
 */
fun pushRoute(backStack: NavBackStack, next: PraxisRoute) {
    if (next in backStack) return
    backStack.add(next)
}

/**
 * 出栈，保底一层。
 *
 * `NavDisplay` 要求栈非空，栈深 1 时弹栈会让整棵树抛异常；
 * 栈底交给系统返回键处理（Activity 自己退出）。
 */
fun popRoute(backStack: NavBackStack) {
    if (backStack.size > 1) {
        backStack.removeAt(backStack.lastIndex)
    }
}

/**
 * 回到某域的首页：把栈顶以上全部弹出，若栈里没有该域的 Home 则替换栈顶。
 *
 * 底栏切 Tab 用这个。语义是「切 Tab 不是压新栈」，
 * 否则从设置里连点三次对话，栈里会积累三个 Home。
 */
fun switchDomain(backStack: NavBackStack, domainId: String) {
    val target = PraxisRoute.Home(domainId)
    val existing = backStack.indexOfFirst { it == target }
    if (existing >= 0) {
        while (backStack.size > existing + 1) {
            backStack.removeAt(backStack.lastIndex)
        }
    } else {
        if (backStack.isEmpty()) {
            backStack.add(target)
        } else {
            backStack[backStack.lastIndex] = target
        }
    }
}

/** 当前栈顶路由。 */
fun NavBackStack.currentRoute(): PraxisRoute? = lastOrNull() as? PraxisRoute

/** 若栈里一个路由都没有（恢复失败等），补一个。 */
fun NavBackStack.ensureSeeded(seed: PraxisRoute = PraxisRoutes.home(PraxisDomain.CHAT)) {
    if (isEmpty()) add(seed)
}
