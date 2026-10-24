package dev.praxis.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.nav.gesture.PredictiveBackHandler

/**
 * 系统返回键的**第二级**处理：Tab 内退回到第一个 Tab。
 *
 * ## 返回键的三级优先级（缺一条顺序就乱）
 *
 * ```
 * ① 栈里还有层级页（size > 1） → 退栈            ← NavDisplay 内部接管
 * ② 只剩根页、且不在第 1 个 Tab → 回第 1 个 Tab   ← 本文件
 * ③ 只剩根页、且已在第 1 个 Tab → Activity 退出   ← 系统默认
 * ```
 *
 * 三个判据必须**同时**成立才接管，少一个都有具体故障：
 *
 * | 漏掉的判据 | 故障现象 |
 * |---|---|
 * | `current() is Main` | 在二级页按返回会先播放 Pager 归位动画（界面看着纹丝不动，因为根页被上层页盖着），用户以为返回键失灵 |
 * | `backStackSize() == 1` | 二级页按返回会**先跳 Tab 再退栈**，方向反了：该退回上一页，却先平移到另一个 Tab |
 * | `selectedPage != 0` | 在第一个 Tab 按返回被本 handler 吃掉，App 退不出去 |
 *
 * ## 2026-10-24：这一级曾经完全失效，且**没有任何报错**
 *
 * miuix `PredictiveBackHandler` 内部第一行是：
 *
 * ```kotlin
 * val dispatcher = LocalNavigationEventDispatcherOwner.current
 *     ?.navigationEventDispatcher ?: return   // ← 取不到就 return，静默失效
 * ```
 *
 * 而 Praxis 当时 provide 的是一个 `parent = null` 的**孤立 root dispatcher**
 * （见 `MainActivity` 的 KDoc）。那个 dispatcher 没有挂任何
 * `NavigationEventInput`，系统返回事件永远进不来 —— 于是：
 *
 * ```
 * handler 注册成功 → enabled=true → 但 dispatcher 收不到事件
 *   → 本应「回第 1 个 Tab」的动作从不发生
 *     → 系统把返回当作无人消费 → Activity.finish()   ← 用户报的「直接退出软件」
 * ```
 *
 * 现在 `MainActivity` 改用宿主自带的 owner（带 `OnBackInvokedDispatcher`
 * 输入源），这条链路才真正接上。
 *
 * ## 为什么这里仍然保留「静默判断」而不是加断言
 *
 * 取不到 dispatcher 属于「宿主没提供返回源」，在桌面/预览环境是**正常**状态。
 * 断言会让预览崩掉。真正该保证的是「Android 宿主一定提供」——
 * 那条约束放在 `MainActivity`（那里用 `error()` 明确失败）而不是这里。
 *
 * ## 为什么用 miuix 的 `PredictiveBackHandler` 而不是 `androidx.activity.BackHandler`
 *
 * 两件事：
 *
 * 1. **预测性返回**。`activity` 的 `BackHandler` 直接消费返回事件，系统拿不到
 *    「手势进行中」的信息，于是没有跟手动画。miuix 的这个 handler 参与
 *    `NavigationEvent` 分发链，能与 `NavDisplay` 内部的
 *    `PredictiveBackHandlerWithSessions` 共存并按 `enabled` 排序。
 * 2. **依赖面**。`androidx.navigationevent`（`NavigationBackHandler` 所在包）
 *    在 Praxis 里**没有显式声明**，用它会变成隐式传递依赖 ——
 *    InstallerX 甚至专门把它 `exclude` 掉。miuix-nav 已在依赖里，用它零新增。
 *
 * ## 为什么 `onProgress` 是空实现
 *
 * 这一级动作是「Pager 从第 N 页滑回第 1 页」。Pager 自己的 `animateToPage`
 * 已有完整缓动，不需要把手指进度映射成 `scrollBy`：
 * 做进度映射反而会在中途松手时留下一个不完整的偏移（Pager 没有「半程吸附」语义）。
 * 所以这里只关心「最终提交」，进度流向空消费。
 */
@Composable
fun MainScreenBackHandler(
    mainPagerState: MainPagerState,
    navigator: Navigator,
    /** 第一个 Tab 的页序。恒为 0，抽成参数便于将来调整底栏顺序。 */
    homeTabIndex: Int = 0,
) {
    val enabled by remember(mainPagerState, navigator, homeTabIndex) {
        derivedStateOf {
            navigator.current() is PraxisRoute.Main &&
                navigator.backStackSize() == 1 &&
                mainPagerState.selectedPage != homeTabIndex
        }
    }

    PredictiveBackHandler(
        enabled = enabled,
        // 进度流向空消费：见 KDoc 最后一段。
        onProgress = { events -> events.collect { /* 不使用中途进度 */ } },
        onCommit = { mainPagerState.animateToPage(homeTabIndex) },
        onCancel = { /* 手势取消：什么都不做，Pager 停在当前页 */ },
    )
}