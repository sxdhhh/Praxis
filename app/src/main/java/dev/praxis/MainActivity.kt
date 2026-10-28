package dev.praxis

import android.os.Bundle

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import dev.praxis.core.data.settings.SettingsRepository
import dev.praxis.core.model.settings.ThemeSettings
import dev.praxis.ui.PraxisApp
import dev.praxis.ui.theme.PraxisTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
            val themeStateFlow = remember(repository) {
                repository.themeSettings.map<ThemeSettings, ThemeSettings?> { value -> value }
            }
            val themeOrNull by themeStateFlow.collectAsStateWithLifecycle(
                initialValue = null,
            )

            // 读到真实值之后才放开 splash。
            //
            // 写在组合体里而不是 LaunchedEffect：这里是每帧都执行的位置，
            // 首帧组合完成时 `themeLoaded` 就已经是 true，
            // 比 LaunchedEffect 少一次调度 —— 那一帧之差就是可见的白闪。
            if (themeOrNull != null) {
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
            if (themeOrNull == null && !forceReady) return@setContent

            // 走到这里两种可能：读到真实设置，或超时兜底。
            // 兜底时 `themeLoaded` 也要置上，否则 splash 不会撤。
            themeLoaded = true
            val resolved = themeOrNull ?: ReadyState(
                theme = ThemeSettings(),
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
                    // ## 引导链闸门
                    //
                    // HyperCeiler 的引导链（`:ui:provision`，131 个文件 /
                    // 9108 行）是独立 Activity，不是 Composable，没法塞进
                    // 这个组合体里。所以用「Activity 跳转 + 回来」的方式接：
                    //
                    // ```
                    // MainActivity.onCreate
                    //   ├─ 未引导 → startActivity(DefaultActivity) → finish 自己
                    //   │            └─ 五步走完（Startup → Permission → Terms
                    //   │               → Basic → Congratulation）
                    //   │               └─ setProvisioned(true)
                    //   │               └─ startActivity(MainActivity)  CLEAR_TASK
                    //   └─ 已引导 → PraxisApp(...)   ← 正常路径
                    // ```
                    //
                    // ## 为什么放在 `PraxisTheme` 里面而不是 `onCreate` 开头
                    //
                    // 放开头也行，但那样 `PraxisTheme` 会给一个即将被 finish
                    // 的 Activity 建一整棵主题树，纯属浪费。放在这里，主题已经
                    // 就绪（不会闪），而且只在这一帧做一次判断。
                    //
                    // `LaunchedEffect` 而不是直接调用：`startActivity` 会触发
                    // 生命周期切换，在组合过程中调用属于「组合期副作用」，
                    // 会被 Compose 报 warning。
                    var provisionChecked by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        if (!ProvisionGate.isDone(this@MainActivity)) {
                            if (ProvisionGate.launch(this@MainActivity)) {
                                // 引导自己会再拉起 MainActivity（CLEAR_TASK），
                                // 这里把自己收掉，避免两层叠着。
                                finish()
                                return@LaunchedEffect
                            }
                        }
                        // 引导已完成 → 把引导期间暂存的两项「转存」进 SettingsRepository。
                        //
                        // ## 为什么要这一步
                        //
                        // `:ui:provision` 不依赖 `:core:data`（见 `ProvisionPrefs` 的
                        // KDoc：那样会让 ui 层反过来依赖数据层，形成越级依赖）。
                        // 所以引导期间它只能写自己的 SharedPreferences，是**暂存**。
                        // 真正的唯一真相来源是 `SettingsRepository`（DataStore），
                        // 由这里在闸门放行前完成转存，之后 Repository 说了算。
                        //
                        // 幂等：`ProvisionPrefs.hasPermissionLevel` 为 false 时不写，
                        // 避免每次冷启动都把用户后来在设置页改的档位覆盖回去。
                        runCatching {
                            ProvisionGate.importFromProvision(this@MainActivity, repository)
                        }
                        provisionChecked = true
                    }

                    if (provisionChecked) {
                        // 引导已完成：进主界面。
                        //
                        // 「已引导」时 `LaunchedEffect` 在第一帧就把它置 true，
                        // 中间不会出现可见空档（同一帧内完成判定与放行）。
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
 * 启动时需要的主题。
 *
 * 保留成包装类而不是直接 `ThemeSettings?`：
 * `null` 表示「还没读到盘」，与「用户真的选了默认主题」必须能区分，
 * 否则第一帧只能用默认值建树，就是那一次「闪一下才跟上莫奈」。
 */
private data class ReadyState(
    val theme: ThemeSettings,
)