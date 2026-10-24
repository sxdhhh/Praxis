package dev.praxis.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import top.yukonga.miuix.kmp.nav.core.NavBackStack

/**
 * 导航器：栈操作的唯一入口。
 *
 * ## 为什么要有这一层，而不是各页面直接改 `NavBackStack`
 *
 * `NavBackStack` 是 `SnapshotStateList<NavKey>`，裸用它有三类必踩的坑：
 *
 * 1. **重复压栈直接崩**。`NavDisplay` 在 reconcile 时对重复 contentKey 抛
 *    `IllegalArgumentException`。而「连点两下同一个设置项」不是用户错误，
 *    是正常手速。必须在入口挡掉。
 * 2. **快速连按返回会连退两页**。真机上很容易在 100ms 内触发两次返回，
 *    用户看到的是「一下退了两层」。需要时间窗防抖。
 * 3. **栈底弹空直接崩**。`NavDisplay` 内部 `require(backStack.isNotEmpty())`。
 *    栈底那一层该交给系统返回键（Activity 退出），不该由页面代码去弹。
 *
 * ## 为什么幂等判据是「栈顶相同」而不是「栈中已存在」
 *
 * 这两者语义不同，差别很具体：
 *
 * - `栈中已存在` → 挡掉 **A → B → A** 这种合法导航（用户确实可能从 A 进 B 再回 A 的
 *   另一个实例）。旧实现的 `pushRoute` 就是这样，于是必须靠 `switchDomain`
 *   特判来绕开，越绕越乱。
 * - `栈顶相同` → 只挡真正的连击（栈顶已经是它，再压一次毫无意义）。
 *
 * 现在采后者（照 InstallerX `Navigator.push`）。
 *
 * ## 为什么不用参数化回调「透传」
 *
 * 页面通过 `LocalNavigator.current` 拿本对象，导航动作就地完成。否则每加一个
 * 页面级导航动作，就要沿 `PraxisApp → MainShell → FeatureHost → SettingsHost → Screen`
 * 这条链改四五个函数签名（第 12 项为加一个「终端设置」入口就改了四处）。
 */
class Navigator(val backStack: NavBackStack) {

    /**
     * 压栈，幂等。
     *
     * 判据是**栈顶相同**（见 KDoc）。`PraxisRoute` 全是 `data object`，
     * 相等性即类型相等，所以这层判断同时挡掉了「同一页连点两次」。
     */
    fun push(key: PraxisRoute) {
        if (backStack.lastOrNull() == key) return
        backStack.add(key)
    }

    /**
     * 出栈。
     *
     * 两重保护：
     *  - 栈深 <= 1 时不弹（栈底交给系统返回键，弹空会让 `NavDisplay` 抛异常）；
     *  - 100ms 内重复调用忽略（真机连按返回会连退两层）。
     *
     * 100ms 这个值来自上游（InstallerX `Navigator.pop` 的 `lastPopTime` 判据）：
     * 短于人手双击间隔、长于同一次返回事件可能触发的重复分发。
     */
    fun pop() {
        val now = System.currentTimeMillis()
        if (now - lastPopTime < POP_DEBOUNCE_MS) return
        if (backStack.size <= 1) return
        lastPopTime = now
        backStack.removeAt(backStack.lastIndex)
    }

    /**
     * 直接出栈，**没有**防抖。
     *
     * 用于「不是用户按返回、而是程序语义上要退回」的场景，
     * 例如：会话列表里选中一个会话 → 回到对话页。
     *
     * 这类调用不该被 100ms 窗口吞掉 —— 用户点条目后立刻点返回是合法操作，
     * 两个动作确实该退两层。
     */
    fun popNow() {
        if (backStack.size <= 1) return
        backStack.removeAt(backStack.lastIndex)
    }

    /** 回到根（`Main`），清掉全部层级页。用于「回主页」这类归位动作。 */
    fun popToRoot() {
        while (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    /** 栈顶路由。空栈时为 null（调用方需处理，通常回落 [PraxisRoute.Main]）。 */
    fun current(): PraxisRoute? = backStack.lastOrNull() as? PraxisRoute

    /** 栈深。`MainScreenBackHandler` 用它判断「是否只剩根页」。 */
    fun backStackSize(): Int = backStack.size

    private var lastPopTime = 0L

    private companion object {
        /**
         * 出栈防抖窗口（毫秒）。
         *
         * 上游用的是内联的 100 毫秒。提成常量是为了在测试里能读到同一个值 ——
         * 硬编码两处必然漂移（测试改了一处、生产没改，就变成「测试测的是别的东西」）。
         */
        const val POP_DEBOUNCE_MS = 100L
    }
}

/**
 * 把 [Navigator] 下发给整棵子树。
 *
 * `staticCompositionLocalOf` 而不是 `compositionLocalOf`：它一整场都不变，
 * 用静态版可以省掉每次重组读值的跟踪开销；同时意味着**不能**在里面塞会变的值
 * （塞了也不会触发重组）。
 *
 * 未 provide 时直接 error：这属于接线错误，静默给一个空实现只会让
 * 「点按钮没反应」变成难查的悬案。
 */
val LocalNavigator = staticCompositionLocalOf<Navigator> {
    error("LocalNavigator 未提供：页面必须挂在 PraxisNavDisplay 之下")
}