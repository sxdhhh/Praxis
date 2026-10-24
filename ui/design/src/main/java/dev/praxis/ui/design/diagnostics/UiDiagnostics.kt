package dev.praxis.ui.design.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap

/**
 * UI 缺陷的**证据收集器**（不依赖任何日志库）。
 *
 * ## 为什么要有这一层（2026-10-22）
 *
 * 用户连着四轮说「UI 上 bug 有好多」「设计一点都不规范还全是 tm 的 bug」。
 * 我去读了真机日志 `praxis-2026-10-22.log`，全文只有三行：
 *
 * ```
 * Praxis started, baseUrl=https://api.example.com, debug=true
 * ```
 *
 * **UI 层一行日志都没有。** 后果不是「日志不好看」，而是修 bug 只能靠猜：
 *  - 「控件不正规」→ 不知道是哪个控件、在哪个页面；
 *  - 「点了没反应」→ 无法区分是回调没接线、组合没重组、还是异常被吞；
 *  - 「切了页面内容没变」→ 无法判断页面是被复用还是被重建。
 *
 * ## 为什么不直接在本模块调日志
 *
 * `ui:design` 不依赖 `core:data`（日志实现在那里），也不依赖 Timber。
 * 硬加依赖会破坏分层：一个纯 UI 组件库不该知道日志落盘。
 *
 * 所以这里只**收集**，把「怎么记」交给上层注入（[install]）。
 * `:app` 启动时用一行把它接到 `PraxisLogger` / Timber；测试里不接，
 * 统计功能照常可用。这与项目其它端口（`LinuxEnvPort`、`MemoryPort`）同一原则。
 */
object UiDiagnostics {

    /** 记录回调：页面名 + 事件。由上层注入。 */
    private var sink: ((level: Char, screen: String, message: String, error: Throwable?) -> Unit)? = null

    /**
     * 各页面重组次数。
     *
     * 为什么是 `SnapshotStateMap`：调试面板读它时要能自动刷新。
     * 用普通 HashMap 改了不会触发重组，面板数字会停在第一次的值。
     */
    private val recompositions: SnapshotStateMap<String, Int> = mutableStateMapOf()

    /** 各页面「进入组合」次数。与重组次数分开才能区分「重建」与「一直重组」。 */
    private val enters: SnapshotStateMap<String, Int> = mutableStateMapOf()

    /** 接上真正的日志实现。在 Application.onCreate 里调一次。 */
    fun install(
        sink: (level: Char, screen: String, message: String, error: Throwable?) -> Unit,
    ) {
        this.sink = sink
    }

    fun recompositionCount(screen: String): Int = recompositions[screen] ?: 0

    fun enterCount(screen: String): Int = enters[screen] ?: 0

    /** 只读快照：screen -> (重组次数, 进入次数)。 */
    fun snapshot(): Map<String, Pair<Int, Int>> =
        (recompositions.keys + enters.keys).associateWith {
            recompositionCount(it) to enterCount(it)
        }

    fun reset() {
        recompositions.clear()
        enters.clear()
    }

    internal fun onRecompose(screen: String) {
        recompositions[screen] = (recompositions[screen] ?: 0) + 1
    }

    internal fun onEnter(screen: String) {
        enters[screen] = (enters[screen] ?: 0) + 1
        sink?.invoke('I', screen, "enter", null)
    }

    internal fun onError(screen: String, t: Throwable) {
        sink?.invoke('E', screen, "UI error in $screen", t)
    }

    /**
     * 记一条**普通事件**（不是错误）。
     *
     * 用途：把「用户做了什么」与「界面怎么响应」串起来。
     * 典型场景是交互类缺陷 —— 上一轮查第 5 项（长按无菜单）时，
     * 日志里既没有「长按发生了」也没有「弹窗显示了」，
     * 于是无法判断是手势没触发、状态没置位、还是浮层弹在屏幕外。
     * 三个环节各留一条 note，一次真机操作就能定位断点。
     *
     * 与 [onError] 分开而不是复用：错误的语义是「有异常」，
     * 混进普通事件后，日志页按级别筛选就失真了。
     */
    fun note(screen: String, message: String) {
        sink?.invoke('D', screen, message, null)
    }
}

/**
 * 记录一次页面进入 + 统计重组次数。放在页面 Composable 的第一行。
 *
 * ## 为什么「进入」用 `LaunchedEffect(screen)` 而不是直接写函数体
 *
 * 直接写函数体的话每重组一次就 +1，和重组计数没区别。
 * `LaunchedEffect` 的块只在首次进入组合时跑一次（key 不变则不再跑），
 * 正好对应「用户进了这个页面一次」。
 *
 * 这个计数是有实际诊断价值的：用户报「对话记录退出后怎么就没了」时，
 * 若某个页面的进入次数远大于用户实际进出次数，说明页面在被反复销毁重建
 * （NavDisplay 的 entry 回收策略有问题），而不是数据没存住。
 */
@Composable
fun TraceRecomposition(screen: String) {
    UiDiagnostics.onRecompose(screen)
    LaunchedEffect(screen) {
        UiDiagnostics.onEnter(screen)
    }
}

// ## 为什么没有「错误边界」（UiErrorBoundary）
//
// 我最初写了一个 `try { content() } catch (t: Throwable) { ... }` 包住
// composable 调用，想让组合期异常就地落盘。**Compose 编译器直接拒绝**：
//
// ```
// e: Try catch is not supported around composable function invocations.
// ```
//
// 这不是工具链限制而不足，而是语义上就不允许：组合函数调用之间靠
// 位置（group key）配对，中途抛异常会让 Composer 的插入/结束组不配对，
// 后续组合的整棵状态树会错位。即使强行编过，行为也无法预期。
//
// 所以组合期异常只能靠框架兜（它会以 `ComposeRuntimeError` 冒到 Recomposer）。
// 能做的替代方案，也是真机上真正有用的两条：
//  1. **组合前后打点**（`TraceRecomposition`）—— 异常发生时，日志里
//     最后一条 "enter Xxx" 就指向出事页面，比没有信息强得多；
//  2. **协程内各自 catch** —— `LaunchedEffect` / `rememberCoroutineScope`
//     里的异常本来就走另一条路，在那些地方用 runCatching 记录。
//
// 这一条要写在这里，否则后来者（包括我自己）会重复尝试同一个不可行方案。