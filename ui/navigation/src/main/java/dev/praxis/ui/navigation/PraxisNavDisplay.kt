package dev.praxis.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransition

/**
 * 导航宿主：`NavDisplay` + 每一页的 entry 声明。
 *
 * ## 为什么每个 Route 都要显式写一行 `entry<...>`
 *
 * 这是「二级页篡页」的根治手段。旧实现只有两个 entry（`Home` / `Detail`），
 * 全部页面的区分都落在 `route.arg` 字符串上，于是：
 *
 * ```
 * 清单写 "advanced" → 枚举里没有 → byKey 返回 null → 走兜底分支
 *                  → 渲染出一个完整设置首页 → 用户看到「点了没反应」
 * ```
 *
 * 这类错误**编得过**。现在每个目的地是一个 `data object`，这里的
 * `entry<PraxisRoute.Xxx>` 与 `Routes.kt` 的声明必须一一对应：
 *  - 少写一个 entry → 运行时 `NavDisplay` 找不到对应 entry，页面全白；
 *  - 写错名字 → 编译不过。
 *
 * 为了让「少写一个」也在编译期暴露，[PraxisDestinations] 用 `when` 覆盖
 * 全部子类型（sealed + exhaustive when，Kotlin 会强制穷举），且**没有 `else` 分支**。
 *
 * ## 为什么 `swipeDismiss` 逐页配置而不是统一
 *
 * 侧滑返回与页面内的横向手势是直接竞争关系：
 *  - 编辑类页面（模型配置、系统提示词、Linux 环境、终端设置）里有横向可拖的
 *    输入框 / 滑杆 / 分段控件，从屏幕边缘起手会被系统当成「返回」，
 *    表现为「滑杆拖不动 / 一拖就退页」；
 *  - 纯展示与列表页没有横向手势，侧滑返回是纯收益。
 *
 * 所以按页面性质分成两组（见 [swipeFor]）。
 */
@Composable
fun PraxisNavDisplay(
    backStack: NavBackStack,
    navigator: Navigator,
    transition: NavTransition,
    effects: NavDisplayEffects,
    modifier: Modifier = Modifier,
    /**
     * 一个目的地对应的内容。
     *
     * 只交出 `route`，不给「压栈/出栈」闭包：导航动作由页面自己通过
     * `LocalNavigator` 完成，这样新增页面不需要动本文件的签名。
     */
    content: @Composable (PraxisRoute) -> Unit,
) {
    // 侧滑方向是「物理方向」，不随布局方向镜像：
    // LTR 下从左边进（手指右滑），RTL 反之。
    val swipe = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
        NavSwipeDirection.RightToLeft
    } else {
        NavSwipeDirection.LeftToRight
    }

    // Navigator 下发给整棵子树。放在 NavDisplay **外层**：
    // NavDisplay 的 entry 是子组合，若放在里面，页面能拿到；
    // 但 `MainScreenBackHandler` 之类挂在根页之外的逻辑也需要它。
    CompositionLocalProvider(LocalNavigator provides navigator) {
        NavDisplay(
            backStack = backStack,
            onBack = { navigator.pop() },
            transition = transition,
            effects = effects,
            modifier = modifier.fillMaxSize(),
        ) {
            // ── 根页：不设 swipeDismiss ──
            // 根页侧滑没有任何语义（退无可退），设了只会在边缘产生一个
            // 「滑一下什么都不发生」的死区。
            entry<PraxisRoute.Main> { route -> content(route) }

            // ── 对话域 ──
            // 列表页无横向手势 → 允许侧滑返回。
            entry<PraxisRoute.ChatSessions>(swipeDismiss = swipe) { route -> content(route) }

            // ── 设置域：编辑/表单类页面关掉侧滑 ──
            //
            // 这四个页面里有横向可拖控件（温度滑杆、上下文滑杆、滑杆式设置项、
            // 可横向滚动的代码/提示词输入），侧滑返回会和它们抢手势。
            entry<PraxisRoute.SettingsModel>(swipeDismiss = NavSwipeDirection.None) { route ->
                content(route)
            }
            entry<PraxisRoute.SettingsFunctionModel>(swipeDismiss = swipe) { route ->
                content(route)
            }
            entry<PraxisRoute.SettingsSystemPrompt>(swipeDismiss = NavSwipeDirection.None) { route ->
                content(route)
            }
            // ── 角色卡三页（2026-10-23 补，此前漏写 entry）──
            //
            // ## 漏写的后果（这是本轮扫描抓到的真实 P0）
            //
            // `Routes.kt` 里声明了这三个目的地，`SettingsHost` 里也有完整分支，
            // 但从没在这里注册 entry。`NavDisplay` 找不到目的地对应的 entry 时
            // 不报错、不崩，只是**渲染不出内容** —— 用户从设置点「角色卡」
            // 得到一张纯背景色的空白页，且返回键还能正常退回，
            // 表现像「这一页本来就是空的」。
            //
            // 这类缺陷不会在编译期暴露（entry 是运行期按 key 查表），
            // 也因此在上一轮「导航重构」里被漏掉。三个目的地同时漏，
            // 说明当时是整体复制粘贴 entry 清单时断在中间。
            //
            // 列表页无横向手势 → 允许侧滑返回。
            entry<PraxisRoute.SettingsCharacters>(swipeDismiss = swipe) { route ->
                content(route)
            }
            // 编辑页：有可横向滚动的长文本输入（人设 / 开场白），关掉侧滑。
            entry<PraxisRoute.SettingsCharacterEdit>(swipeDismiss = NavSwipeDirection.None) { route ->
                content(route)
            }
            // 标签库：纯列表页（可点选、可扫读），允许侧滑返回。
            entry<PraxisRoute.SettingsPromptTags>(swipeDismiss = swipe) { route ->
                content(route)
            }
            entry<PraxisRoute.SettingsAppearance>(swipeDismiss = swipe) { route -> content(route) }
            entry<PraxisRoute.SettingsTools>(swipeDismiss = swipe) { route -> content(route) }
            entry<PraxisRoute.SettingsPermission>(swipeDismiss = swipe) { route -> content(route) }
            entry<PraxisRoute.SettingsMemory>(swipeDismiss = swipe) { route -> content(route) }
            entry<PraxisRoute.SettingsWorkflow>(swipeDismiss = swipe) { route -> content(route) }
            entry<PraxisRoute.SettingsSpeech>(swipeDismiss = swipe) { route -> content(route) }
            entry<PraxisRoute.SettingsLinux>(swipeDismiss = NavSwipeDirection.None) { route ->
                content(route)
            }
            entry<PraxisRoute.SettingsTerminal>(swipeDismiss = NavSwipeDirection.None) { route ->
                content(route)
            }
            entry<PraxisRoute.SettingsPackages>(swipeDismiss = swipe) { route -> content(route) }
            entry<PraxisRoute.SettingsLog>(swipeDismiss = swipe) { route -> content(route) }
            entry<PraxisRoute.SettingsAbout>(swipeDismiss = swipe) { route -> content(route) }
            // 许可明细是纯列表页，无横向手势 → 允许侧滑返回。
            entry<PraxisRoute.SettingsOpenSourceLicense>(swipeDismiss = swipe) { route ->
                content(route)
            }
        }
    }
}

/**
 * 目的地 → 标题。
 *
 * 标题从**路由**推导，不再由路由携带（旧实现把 title 序列化进 Bundle，
 * 清单改名后旧栈恢复出的标题会和内容不符）。这里的 `data object` 是穷举
 * `when`，新增 Route 忘了加标题就是编译错误。
 */
fun PraxisRoute.title(): String = when (this) {
    PraxisRoute.Main -> "Praxis"
    PraxisRoute.ChatSessions -> "会话列表"
    PraxisRoute.SettingsModel -> "模型"
    PraxisRoute.SettingsFunctionModel -> "功能模型"
    PraxisRoute.SettingsSystemPrompt -> "系统提示词"
    PraxisRoute.SettingsCharacters -> "角色卡"
    // 参数化 Route：新建与编辑共用一个标题（与上游一致，那两态在页内区分）。
    is PraxisRoute.SettingsCharacterEdit -> "角色卡编辑"
    PraxisRoute.SettingsPromptTags -> "提示词标签"
    PraxisRoute.SettingsAppearance -> "外观"
    PraxisRoute.SettingsTools -> "工具"
    PraxisRoute.SettingsPermission -> "权限"
    PraxisRoute.SettingsMemory -> "记忆"
    PraxisRoute.SettingsWorkflow -> "工作流"
    PraxisRoute.SettingsSpeech -> "语音"
    PraxisRoute.SettingsLinux -> "Linux 环境"
    PraxisRoute.SettingsTerminal -> "终端设置"
    PraxisRoute.SettingsPackages -> "扩展包"
    PraxisRoute.SettingsLog -> "日志"
    PraxisRoute.SettingsAbout -> "关于"
    PraxisRoute.SettingsOpenSourceLicense -> "开源许可"
}

/** 该页是否是一级页（显示底栏、不显示返回箭头）。 */
fun PraxisRoute.isRoot(): Boolean = this is PraxisRoute.Main

/** 栈顶路由，空栈回落 [PraxisRoute.Main]。 */
fun NavBackStack.currentRoute(): PraxisRoute =
    lastOrNull() as? PraxisRoute ?: PraxisRoute.Main

/**
 * 恢复兜底：栈为空时补一个根页。
 *
 * `NavDisplay` 内部 `require(backStack.isNotEmpty())`，空栈直接抛异常 ——
 * 表现为启动白屏崩溃。恢复失败（序列化格式变更、栈内容不可反序列化被跳过）
 * 是真会发生的，必须在渲染前补齐且不留空隙。
 *
 * 幂等（只在 `isEmpty` 时写），重组期反复调用不产生额外状态变更。
 */
fun NavBackStack.ensureSeeded() {
    if (isEmpty()) add(PraxisRoute.Main)
}