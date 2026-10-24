package dev.praxis

import android.os.Bundle

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import dev.praxis.core.data.permission.CapabilityDetector
import dev.praxis.core.data.permission.CapabilityRequester
import dev.praxis.core.data.settings.SettingsRepository
import dev.praxis.core.model.settings.ThemeSettings
import dev.praxis.ui.PraxisApp
import dev.praxis.ui.theme.PraxisTheme
import dev.praxis.ui.design.onboarding.PraxisOnboardingFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import org.koin.compose.koinInject
import timber.log.Timber

/**
 * 单 Activity 宿主。
 *
 * 主题的**唯一来源**是 [SettingsRepository]：设置页改完立刻落盘，
 * 这里用 `collectAsStateWithLifecycle` 订阅，整棵树按新设置重组。
 * 不在 Activity 里另存一份主题状态 —— 两份状态一旦分叉，
 * 就会出现「设置页显示深色、界面还是浅色」这种自相矛盾。
 *
 * ## 浮层返回键为什么要 owner，以及为什么**不能**自建（2026-10-24 修正）
 *
 * ### 崩溃现场（2026-10-23）
 *
 * ```
 * java.lang.IllegalStateException: No NavigationEventDispatcher was provided via
 *   LocalNavigationEventDispatcherOwner
 *   at androidx.navigationevent.compose.NavigationEventHandlerKt.NavigationEventHandler
 *   at androidx.navigationevent.compose.NavigationEventHandlerKt.NavigationBackHandler
 *   at top.yukonga.miuix.kmp.utils.MiuixPopupUtils$Companion.PopupEntry(MiuixPopupUtils.kt:530)
 *   at top.yukonga.miuix.kmp.utils.MiuixPopupUtils$Companion.MiuixPopupHost(MiuixPopupUtils.kt:560)
 * ```
 *
 * ### 机制（三层各自的事实）
 *
 * 1. **miuix 0.9.4 硬读、无备选**：`MiuixPopupHost` 组合展开中的浮层时，
 *    `PopupEntry` 无条件调用
 *    `NavigationBackHandler(state, enabled, onCancelled, onCompleted)`
 *    （用于「返回键关浮层」）。该函数内部直接
 *    `LocalNavigationEventDispatcherOwner.current`，**取不到即抛**。
 * 2. **`LocalNavigationEventDispatcherOwner` 有宿主默认值**：该 CompositionLocal
 *    由 `compositionLocalWithHostDefaultOf(NavigationEventDispatcherOwnerHostDefaultKey)`
 *    构造（javap 确认），默认值来自 ViewTree。
 * 3. **`ComponentActivity` 会把自己注册到 ViewTree**：其 `setContentView` 路径里
 *    调用了
 *    `ViewTreeNavigationEventDispatcherOwner.set(view, this)`
 *    （activity 1.13.0 字节码确认），且它 **`implements NavigationEventDispatcherOwner`**，
 *    内部持有 `OnBackInvokedDispatcher` 作为**系统返回输入源**。
 *
 * ### 2026-10-24：上一版的修法是错的
 *
 * 上一版写 `rememberNavigationEventDispatcherOwner(parent = null)`，
 * 意思是「这里就是根 dispatcher，别再向上找」。它确实让浮层不再崩 ——
 * 但代价是把系统返回链路**整个切断**：
 *
 * ```
 * 系统返回手势 → Activity.onBackInvokedDispatcher
 *              → ComponentActivity 自己的 dispatcher
 *                → handler 全注册在那个孤立的 root 上，这里空着
 *                  → 无人消费 → Activity.finish()      ← 用户报的「直接退出软件」
 * ```
 *
 * `parent = null` 建出来的是 `ComposeNavigationEventDispatcherOwner`，
 * 它内部只有一个**裸 `NavigationEventDispatcher`**，不持有任何
 * `NavigationEventInput`（`addInput` 从未被调用）—— 因此那个 dispatcher
 * 永远不会收到系统事件。预测性返回的跟手动画当然也不会出现。
 *
 * ### 现在的做法
 *
 * 不自建，直接取宿主默认值（`ComponentActivity` 注册的那个）。
 * 它是唯一同时满足两件事的实例：
 *
 * | 要求 | 孤立 root | Activity 自带 |
 * |---|---|---|
 * | 浮层 `NavigationBackHandler` 能取到 owner | ✅ | ✅ |
 * | 系统返回手势能进来（预测性返回跟手） | ❌ | ✅ |
 *
 * 这也解释了为什么 InstallerX 什么都不 provide 却工作正常 ——
 * 它直接用 `ComponentActivity` 的，从没有那段自建代码。
 *
 * ### 为什么 provide 点在 `PraxisTheme` 外层
 *
 * miuix 的 popup 宿主在每一页的 `Scaffold` 内部（`PraxisPage` → `AppScaffold`
 * → `MiuixPopupHost`），位于整棵树更深的层级。CompositionLocal 只能向下传递，
 * 所以 provide 必须在**所有页面之上**。
 *
 * 注意：现在 provide 的是「从宿主默认值取到的那个 owner」，
 * 相当于把 ViewTree 上的值显式提进 CompositionLocal —— 而不是新建一个。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()

        // ## 启动白屏 + 闪一下才跟上莫奈（2026-10-23 修）
        //
        // ### 现场
        //
        // 用户：打开 App 先白屏，然后闪一下才跟上莫奈取色。
        //
        // ### 三个原因叠在一起，缺一个都还会闪
        //
        // **① 首帧就用默认主题建树**
        //
        // `repository.themeSettings` 是 DataStore 的 Flow，
        // `collectAsStateWithLifecycle(initialValue = ThemeSettings())` 的初始值
        // 是**默认设置**（`Wallpaper` + 系统调色板）。于是第一帧按「跟随壁纸」的
        // 颜色画出整棵树，几十毫秒后真实设置（用户自定义的种子色）到达，
        // `ThemeController` 换色，整棵树重绘 —— 这就是那一次「闪一下才跟上」。
        //
        // **② splash 退出得太早**
        //
        // 旧代码 `installSplashScreen()` 之后什么都不做，splash 在
        // 「第一帧可绘制」时立即退出，而那时读盘还没完成。
        //
        // **③ 窗口底色原来是 transparent / 白色**
        //
        // 见 `res/values/themes.xml` 与 `res/values/colors.xml` 里的说明。
        //
        // ### 修法（照 InstallerX `SettingsActivity.kt:55-70`）
        //
        // 拿住 `splashScreen`，用 `setKeepOnScreenCondition` 把它**钉在屏幕上**，
        // 直到主题流真正发射过一次；同时 `setContent` 里在未加载时**直接返回**
        // （不建 UI 树）。
        //
        // 这样顺序变成：
        //
        // ```
        // splash（底色已是跟随深浅的实色，不是白）
        //   → 读到真实主题
        //     → 用真实主题建第一帧
        //       → 撤掉 splash
        // ```
        //
        // 全程只有一次建树、一次上色，没有「先画错的再改对」。
        //
        // ### 为什么用 `var` + 闭包而不是 Compose 状态
        //
        // `setKeepOnScreenCondition` 的 lambda 在**非 Compose 线程**被反复轮询，
        // 它读的必须是普通可变字段。用 `mutableStateOf` 虽然也能读，
        // 但那是「为了让 UI 重组」而设计的机制，在这里只会额外挂一个观察者。
        var themeLoaded = false
        splashScreen.setKeepOnScreenCondition { !themeLoaded }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val repository: SettingsRepository = koinInject()
            // ## 浮层返回键要用 Activity 自带的 dispatcher（2026-10-24 重写）
            //
            // 以前这里是 `rememberNavigationEventDispatcherOwner(parent = null)`，
            // 建了一个**孤立的 root dispatcher** 再 provide 下去。它解决了
            // 「miuix 浮层读不到 owner 而崩溃」，但把系统返回链路彻底截断了：
            //
            // ```
            // 系统返回手势
            //   → Activity.onBackInvokedDispatcher
            //     → ComponentActivity 自己的 NavigationEventDispatcher
            //       → 没有任何 handler 注册在这里（handler 全注册在孤立 root 上）
            //         → 无人消费 → Activity 直接 finish()   ← 用户报的「直接退出软件」
            // ```
            //
            // `ComponentActivity`（activity 1.13.0）**本身就实现了**
            // `NavigationEventDispatcherOwner`，并且内部持有
            // `OnBackInvokedDispatcher` 作为输入源。正确做法是不再自建，
            // 直接取 Activity 那一个 —— 它有系统输入源，也是 handler 该注册的地方。
            //
            // 取法用 `LocalNavigationEventDispatcherOwner.current` 的宿主默认值：
            // 在 Activity 的 setContent 里，Compose 会从 ViewTree 上取到
            // `ComponentActivity` 注册的那个 owner（见下面 KDoc 的机制说明）。
            val navigationEventOwner =
                androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner.current
                    ?: error(
                        "Activity 未提供 NavigationEventDispatcherOwner —— " +
                            "ComponentActivity 本应自带，检查是否被某个 override 覆盖掉了",
                    )

            // 本次进程内「刚刚点了开始使用」的标记。
            //
            // ## 为什么需要它（而不是只靠 DataStore 回写）
            //
            // 写盘是挂起操作，写完要再经 Flow 发射才能反映到 `resolved`。
            // 中间那段（几十毫秒）里引导页还在显示 —— 用户已经点了「开始使用」
            // 却仍停在原页，会被读成「点了没反应」。
            //
            // 用本地标记让这一帧就切到主界面，写盘在背后完成。
            // 重启后以盘上的值为准（所以它不能代替写盘）。
            var onboardingDoneLocal by remember { mutableStateOf(false) }

            // ## 主题流为什么多套一层 `map`

            // `collectAsStateWithLifecycle(initialValue)` 的类型参数是从 Flow 的
            // 元素类型推导的：`Flow<ThemeSettings>` 就只能传 `ThemeSettings`，
            // 传 `null` 直接编译失败。
            //
            // 但「还没读到盘」与「用户真的选了默认主题」必须能区分开 ——
            // 否则第一帧只能用默认值建树，就是那一次「闪一下才跟上莫奈」。
            //
            // 所以先把元素类型**放宽成可空**，再用 `null` 当「未就绪」哨兵。
            //
            // ## 为什么把「引导状态」也并进这一条流（而不是各采一次）
            //
            // 两件事决定**第一帧长什么样**：用哪套颜色、进哪一屏。它们必须
            // 在**同一帧**里就已知，否则会出现这两种真实会发生的闪：
            //
            // ```
            // 分流写错①：先按 completed=true 建主界面 → 读到 false → 跳引导
            //           = 启动时主界面闪一下再被引导页盖住
            // 分流写错②：先按 completed=false 建引导 → 读到 true → 跳主界面
            //           = 老用户每次启动都闪一下引导页
            // ```
            //
            // 用 `combine` 合并成一条流、只给一个 `null` 初值，
            // 就保证「拿到值的那一刻，两件事都已经确定」。
            //
            // `remember(repository)`：这个 Flow 包装本身不该每次重组新建
            // （新建会让 `collectAsStateWithLifecycle` 重启收集）。
            val readyStateFlow = remember(repository) {
                combine(
                    repository.themeSettings.map<ThemeSettings, ThemeSettings?> { value -> value },
                    repository.onboardingCompleted,
                    repository.acceptedAgreementVersion,
                ) { theme, onboardingDone, agreementVersion ->
                    // 任一未就绪都算未就绪。theme 本身不为 null，
                    // 这里只是保留可空形态以便统一处理。
                    theme?.let {
                        ReadyState(
                            theme = it,
                            onboardingDone = onboardingDone,
                            acceptedAgreementVersion = agreementVersion,
                        )
                    }
                }
            }
            val readyState by readyStateFlow.collectAsStateWithLifecycle(
                initialValue = null,
            )

            // 读到真实值之后才放开 splash。
            //
            // 写在组合体里而不是 LaunchedEffect：这里是每帧都执行的位置，
            // 首帧组合完成时 `themeLoaded` 就已经是 true，
            // 比 LaunchedEffect 少一次调度 —— 那一帧之差就是可见的白闪。
            if (readyState != null) {
                themeLoaded = true
            }

            // ## 兜底：3 秒后无论读没读到都必须放行
            //
            // DataStore 读失败（文件损坏、Keystore 异常、极端 IO 卡住）时
            // Flow 可能永不发射。此时若一直 `return@setContent`，
            // splash 会**永久停在屏幕上** —— 比「闪一下」严重得多。
            //
            // 3 秒是用户能忍受的上限：正常情况下读盘是几十毫秒，
            // 真到了 3 秒说明已经出问题，这时用默认主题把界面交出来，
            // 让用户至少能进设置页修。
            var forceReady by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(3_000)
                forceReady = true
            }

            // 未就绪：不建任何 UI。
            //
            // ## 为什么直接 return 而不是画一个占位 Box
            //
            // 画占位 = 又一次「先画一个不是最终样子的东西」。占位只要和真实
            // 主题差一点色，用户在 splash 撤掉的瞬间就会看到跳变。
            // 什么都不画时，屏幕上留着的正是 splash 那一帧 —— 这个函数
            // 返回后 splash 立刻被撤掉，中间没有可见空档。
            if (readyState == null && !forceReady) return@setContent

            // 走到这里两种可能：读到真实设置，或超时兜底。
            // 兜底时 `themeLoaded` 也要置上，否则 splash 不会撤。
            themeLoaded = true
            val resolved = readyState ?: ReadyState(
                theme = ThemeSettings(),
                // ## 兜底的引导状态为什么是 `true`（已完成）
                //
                // 这是本文件唯一一个「宁可少弹也不误弹」的决定。
                // 读盘失败时无法知道用户是否看过引导：
                //  - 当成「未完成」→ 老用户被强行塞一遍引导，且他没有任何办法跳过
                //    之外的操作（引导页是模态的，跳过按钮也在里面）；
                //  - 当成「已完成」→ 新用户少看一遍说明书，但主界面能正常用，
                //    而且他随时能在设置里找到对应功能。
                //
                // 少一次介绍远好过把老用户挡在门外。
                onboardingDone = true,
                // 同理：兜底按「协议已同意」处理。若给 null，协议门禁会拦住
                // 一个读盘已经出问题的用户 —— 而他此时连「同意」都写不进去，
                // 会被永久卡在协议屏。这与上面同一个取舍。
                // 读盘失败的兜底：按「已同意」处理（理由见上）。
                //
                // 兜底值用当前协议版本：读盘失败时无法知道用户看过哪个版本，
                // 按「已同意当前版本」处理，避免把他永久拦在协议屏。
                acceptedAgreementVersion = AGREEMENT_VERSION,
            )

            CompositionLocalProvider(
                LocalNavigationEventDispatcherOwner provides navigationEventOwner,
            ) {
                // 把存量明文密钥升级为密文（幂等，加密失败则保持原样）。
                // 放在 MainActivity 而不是 SettingsRepository 构造里：
                // 构造里有 suspend 调用会让 DI 变成异步初始化，
                // 而这里天然就在协程作用域内。
                LaunchedEffect(Unit) {
                    runCatching { repository.migrateApiKeyIfNeeded() }
                }

                PraxisTheme(settings = resolved.theme) {
                    // ── 首启引导门禁 ──────────────────────────────────
                    //
                    // ## 为什么放在 PraxisTheme 内部而不是外面
                    //
                    // 引导页自己要用 `MiuixTheme.colorScheme` / `textStyles`
                    // 和 AGSL 流光背景 —— 那些都要求祖先有 `MiuixTheme`。
                    // 放在外层会拿到默认主题（无莫奈取色），观感与主界面不一致。
                    //
                    // ## 为什么用 `if` 而不是导航到某个 Route
                    //
                    // 引导页是**一次性门禁**，不是可来回的页面：
                    //  - 放进 `NavDisplay` 会让返回键能退回引导页（荒诞）；
                    //  - 也会让 `MainShellPage` 的底栏在引导阶段就存在（闪烁）。
                    //
                    // 用 `if` 从结构上保证「引导阶段没有主界面」，
                    // 退出引导就是真的换成主界面，没有中间态。
                        // ── 引导阶段 / 主界面：一次真实的转场（用户要求第 3 条）──
                        //
                        // ## 为什么不是 `if` 直接替换
                        //
                        // 上一版 `onFinish` 里置 `onboardingDoneLocal = true`，这一帧条件翻转、
                        // 子树整体换掉 —— 用户看到的是**瞬间跳切**：引导页消失、主界面凭空出现。
                        // 那不像「进入应用」，像「界面崩了一次」。
                        //
                        // 原版 HyperCeiler 走 `overridePendingTransition(slide_in_right, slide_out_left)`，
                        // 两个 Activity 之间有真实转场。Praxis 的引导与主界面在**同一个 Activity** 内，
                        // 所以转场只能在 Compose 层做。
                        //
                        // ## 参数直接取自原版那两个 anim（不是我自己配的）
                        //
                        // 原版 `CongratulationFragment.startHome()`：
                        //
                        // ```java
                        // ActivityOptions.makeCustomAnimation(
                        //     ctx, R.anim.enter_home_anim, R.anim.provision_out_anim)
                        // ```
                        //
                        // 两个文件的真实参数（已逐字拷进 ui:design/res/anim）：
                        //
                        // | anim | 内容 |
                        // |---|---|

                        // | `enter_home_anim` | `scale 1.3→1.0`，619ms；`alpha 0→1`，230ms，startOffset 60 |
                        // | `provision_out_anim` | `alpha 1→0`，360ms |
                        //
                        // 也就是说：**新页从 1.3 倍缩放收回来并淡入，旧页同时淡出**。
                        // 这是「进入软件」的手感来源 —— 从「贴在脸上的近景」退到正常视距。
                        //
                        // Praxis 的引导与主界面在同一个 Activity 内，所以用
                        // `AnimatedContent` 复刻同一组数值：
                        //  - 主界面：`scaleIn 1.3 → 1.0`（619ms）+ `fadeIn` 230ms（`delayMillis = 60`）；
                        //  - 引导页：`fadeOut` 360ms。
                        //
                        // 注意 `scaleIn` 的 `initialScale = 1.3f`：Compose 里 scale > 1 是放大，
                        // 从 1.3 收到 1.0 正好对应原版的 `fromXScale=1.3 → toXScale=1.0`。
                        AnimatedContent(
                            targetState = !resolved.onboardingDone && !onboardingDoneLocal,
                            transitionSpec = {
                                // 退出（引导页）→ 进入（主界面）。
                                // 这是唯一会用到的方向：走完引导就进主界面。
                                if (targetState) {
                                    // 极端情况：从主界面回到引导（只会发生在开发调试）
                                    fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                                } else {
                                    (
                                        scaleIn(
                                            animationSpec = tween(619, easing = FastOutSlowInEasing),
                                            initialScale = 1.3f,
                                        ) + fadeIn(
                                            animationSpec = tween(
                                                durationMillis = 230,
                                                delayMillis = 60,
                                                easing = FastOutSlowInEasing,
                                            ),
                                        )
                                        ) togetherWith fadeOut(
                                        animationSpec = tween(
                                            durationMillis = 360,
                                            easing = FastOutSlowInEasing,
                                        ),
                                    )
                                }
                            },
                            label = "onboarding-to-app",
                        ) { showOnboarding ->
                            if (showOnboarding) {
                    // ── 权限检测（引导页要显示真实状态，不是硬编码）──────
                    //
                    // 这三项是同一件事的三段：
                    //  - `detector` 负责**查询**当前状态（快照 Flow）；
                    //  - `requester` 负责**发起**申请（必要时跳系统设置）；
                    //  - `launcher` 是运行时权限的系统弹窗入口。
                    //
                    // ## 为什么进引导流就 refresh 一次
                    //
                    // 不刷的话权限屏会先显示 `CapabilitySnapshot.Unknown`
                    // （全部 UNSUPPORTED），用户会看到「全部不可用」再闪成真实状态。
                    // 那是一次可见的错误信息。
                    val capabilityDetector: CapabilityDetector = koinInject()
                    val capabilityRequester: CapabilityRequester = koinInject()
                    val capabilities by
                        capabilityDetector.snapshot.collectAsStateWithLifecycle(
                            initialValue = dev.praxis.core.model.permission
                                .CapabilitySnapshot.Unknown,
                        )
                    LaunchedEffect(Unit) { capabilityDetector.refresh() }
                    // 运行时权限弹窗。回来后必须刷新一次 —— 用户可能刚点了拒绝，
                    // 界面要立刻反映，而不是等下次进页面。
                    val permissionLauncher = androidx.activity.compose
                        .rememberLauncherForActivityResult(
                            contract = androidx.activity.result.contract
                                .ActivityResultContracts.RequestMultiplePermissions(),
                        ) {
                            onboardingScope.launch { capabilityDetector.refresh() }
                        }

                            // ── Basic 屏要改的三项设置 ──────────────────────────
                            //
                            // 三条都是**真实订阅**（不是占位）：
                            //  - `themeMode` 与 `PraxisTheme` 读同一份 Flow，所以在 Basic 屏
                            //    切主题，整页配色立刻变 —— 这是「设置真的生效」的直接证据；
                            //  - `chatDisplayStyle` 存 `cursor` / `bubble` 字符串
                            //    （见 `SettingsRepository` 注释：缺省 `cursor`）；
                            //  - `disableStreamOutput` 是**反向**语义，界面上写「流式输出」。
                            val themeModeNow by repository.themeSettings
                                .map { it.mode }
                                .collectAsStateWithLifecycle(initialValue = resolved.theme.mode)
                            val chatDisplayStyleNow by repository.chatDisplayStyle
                                .collectAsStateWithLifecycle(initialValue = "cursor")
                            val streamDisabledNow by repository.disableStreamOutput
                                .collectAsStateWithLifecycle(initialValue = false)

                            // 六屏内容全部是 Praxis 的真实文案，见 `ProvisionContent.kt`；
                            // 视觉与关于页同源，见 `ProvisionGlassScaffold.kt`。
                            // 每一屏都是原版 HyperCeiler XML 的逐字拷贝，用 AndroidView inflate；
                            // 版式/尺寸/字体/颜色全部来自 `ui:design/src/main/res`（82 条 dimen、
                            // 16 色 + 13 夜间色、17 个 drawable、12 个 layout）。
                            // 每一屏都是原版 HyperCeiler XML 的逐字拷贝，用 AndroidView inflate；
                            // 版式/尺寸/字体/颜色全部来自 `ui:design/src/main/res`。
                            PraxisOnboardingFlow(
                                onFinish = {
                                    // 写盘是挂起操作；这里不 await 它 —— 界面该立刻切走，
                                    // 不能让用户等 IO。写失败会导致下次启动又看到引导，
                                    // 所以失败至少留痕；但不能因此卡住界面。
                                    //
                                    // 用 Activity 级 scope：重建时这次写入不该被取消。
                                    onboardingScope.launch {
                                        runCatching {
                                            repository.completeOnboarding()
                                            // 协议同意与引导完成是同一次「走完流程」。分开写会留下
                                            // 「引导已完成、协议版本为空」的半截状态，
                                            // 下次启动会被协议门禁再拦一次。
                                            repository.acceptAgreement(AGREEMENT_VERSION)
                                        }.onFailure {
                                            Timber.tag("Onboarding")
                                                .w(it, "写入引导完成状态失败，下次启动会再次显示引导")
                                        }
                                    }
                                    // 本地立刻置为已完成：让这一帧就切到主界面。
                                    onboardingDoneLocal = true
                                },
                            )
                            } else {
                                PraxisApp(
                                    settingsRepository = repository,
                                    versionName = BuildConfig.VERSION_NAME,
                                    versionCode = BuildConfig.VERSION_CODE,
                                )
                            }
                        }
                }
            }
        }
    }

    /**
     * 写引导完成状态用的作用域。
     *
     * ## 为什么不用 composition 里的 `rememberCoroutineScope`
     *
     * 那个 scope 的 Job 绑在**当前组合**上：引导页被 `if` 换掉时整棵子树离开组合，
     * 它的 scope 会被取消 —— 正在写盘的协程直接消失，用户看不到任何错误，
     * 下次启动却又看到引导。这是「点了没生效」的典型成因。
     *
     * 用 Activity 的生命周期作用域（`lifecycleScope`）就能跨过那次重组。
     * 它同样在 Activity 销毁时取消 —— 那时写盘本来也没必要继续。
     */
    private val onboardingScope: CoroutineScope
        get() = lifecycleScope

    /**
     * 读盘失败时用的「协议已同意」哨兵值。
     *
     * 与 [acceptedAgreementVersion] 的语义对应：`null` = 从未同意，
     * 非 null = 已同意。空串刻意不用 —— 它与「未设置」在部分
     * 存储实现里不可区分。
     */
    private val AGREEMENT_VERSION = "2026-10-24"
}

/**
 * 启动时需要的两件事：主题 + 是否已完成引导。
 *
 * ## 为什么打包成一个类而不是两个独立状态
 *
 * 它们必须**同时**就绪才有意义（见 `MainActivity` 里的说明）。
 * 分成两个 `by collectAsState` 会有「一个到位、另一个没到位」的中间帧，
 * 那正是启动闪一下的成因。
 */
private data class ReadyState(
    val theme: ThemeSettings,
    val onboardingDone: Boolean,
    /**
     * 已同意的协议版本 id；`null` = 从未同意。
     *
     * 与 [onboardingDone] **必须分开**：两者比较方式不同
     * （`>=` vs `==`），合并会让「引导加屏但协议没改」的老用户
     * 被要求重新同意一份他没变过的协议。详见
     * `SettingsRepository.acceptedAgreementVersion` 的 KDoc。
     */
    val acceptedAgreementVersion: String?,
)