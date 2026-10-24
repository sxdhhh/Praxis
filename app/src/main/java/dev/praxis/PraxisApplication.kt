package dev.praxis

import android.app.Application
import dev.praxis.core.data.log.PraxisLogger
import dev.praxis.core.data.log.PraxisTimberTree
import dev.praxis.di.appModule
import dev.praxis.di.networkModule
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import timber.log.Timber

/**
 * 应用入口。
 *
 * ## 初始化顺序有要求，改前先读这段
 *
 * 1. **先 init 日志器，再 plant Timber**：反过来会让「第一个 Tree 里发出的日志」
 *    落不到盘（`init` 之前 `resolveLogsDir()` 返回 null，记录直接丢）。
 * 2. **再 startKoin**：DI 构建期出错属于最需要落盘的那类问题，必须已经在收集。
 * 3. 最后打第一条日志：看到它说明整条链路（logcat + 文件）都通了。
 *    它是日志页的自检信号。
 *
 * ## 为什么 Timber 只 plant 一个 Tree
 *
 * `PraxisTimberTree` 内部已同时写 logcat 与文件。若再 plant `Timber.DebugTree`，
 * 每条日志会走两遍 logcat（重复行），而文件侧只写一次 ——
 * 表现为「logcat 两条、日志页一条」，排查时会误以为丢日志。
 * DebugTree 的额外价值（调用栈解析）已在本项目 Tree 里实现。
 */
class PraxisApplication : Application() {

    /**
     * 应用级协程作用域。
     *
     * 用 `SupervisorJob`：内存侧的任一个协程（轮询、压力订阅）抛异常
     * 都不该拖垮另一个，更不该拖垮整个进程。
     */
    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
    )

    override fun onCreate() {
        super.onCreate()

        PraxisLogger.init(this)
        Timber.plant(PraxisTimberTree(resolveCallerTag = BuildConfig.DEBUG))

        // 把 UI 诊断层接到真实日志（2026-10-22）。
        //
        // `UiDiagnostics` 本身不依赖日志库（它在 ui:design，不该知道落盘），
        // 只暴露一个 sink 让上层注入。在这里接一次，全 App 的页面进出/
        // 组合异常就都会落到同一个日志文件里，与其它模块同一条时间线。
        //
        // 不接的后果是上一轮的真实处境：日志里只有 "Praxis started"，
        // 用户报「UI 一堆 bug」时没有任何证据可用。
        dev.praxis.ui.design.diagnostics.UiDiagnostics.install { level, screen, message, error ->
            when (level) {
                'E' -> if (error != null) {
                    Timber.tag("UiTrace").e(error, "%s: %s", screen, message)
                } else {
                    Timber.tag("UiTrace").e("%s: %s", screen, message)
                }
                'W' -> Timber.tag("UiTrace").w("%s: %s", screen, message)
                'D' -> Timber.tag("UiTrace").d("%s: %s", screen, message)
                else -> Timber.tag("UiTrace").i("%s: %s", screen, message)
            }
        }

        startKoin {
            androidContext(this@PraxisApplication)
            modules(appModule, networkModule)
        }

        // 自检信号：日志页能看到这一行，说明写入链路完整。
        // 用 I 级别而不是 D：release 也保留，用户反馈问题时第一屏就有信息。
        Timber.i(
            "Praxis started, baseUrl=%s, debug=%s",
            BuildConfig.DEFAULT_BASE_URL,
            BuildConfig.DEBUG,
        )

        // 内存监控：放在最后启动。
        //
        // ## 为什么必须最后
        //
        // 它起一个每 15 秒采样的协程。若放在最前面，采样会在 DI 还没建好
        // 时就跑起来（`get<MemoryMonitor>()` 失败），而 Application 里抛异常
        // 是最难排查的一类崩溃。放在所有初始化之后，失败面最小。
        //
        // `CoroutineScope` 用 `SupervisorJob` 而不是默认 scope：
        // 内存监控的异常不该拖垮整个 Application。
        val monitor = org.koin.core.context.GlobalContext.get()
            .get<dev.praxis.data.memory.MemoryMonitor>()
        monitor.start(appScope)
        memoryMonitor = monitor

        // 轮询侧的「清可重建缓存」。
        //
        // ## 为什么不能只靠 onTrimMemory
        //
        // trim 回调只在系统**已经决定**回收时来，往往已经晚了。轮询能在
        // 堆涨到 Elevated 时提前清掉 Markdown 解析缓存（几百 KB 到几 MB），
        // 把代价摊到用户不察觉的时刻。
        //
        // ## 为什么只在**升档**时清
        //
        // `pressure` 是 StateFlow，档位不变不会重复发射；但为了语义明确，
        // 这里仍按「档位变高」判一次。停在 Elevated 期间反复清缓存没有收益，
        // 反而会把用户正在滚动的长消息反复打回重新解析。
        appScope.launch {
            var lastLevel = dev.praxis.core.ai.MemoryPressure.Normal
            monitor.pressure.collect { level ->
                val rising = level > lastLevel
                lastLevel = level
                if (!rising) return@collect
                if (!dev.praxis.core.ai.MemoryPressurePolicy.shouldTrimCaches(level)) {
                    return@collect
                }
                dev.praxis.ui.widgets.markdown.MarkdownParseCache.clear()
                Timber.tag("Memory").i("pressure rising to %s: cleared markdown cache", level.name)
            }
        }
    }

    /**
     * 系统内存回收回调（`onTrimMemory`）。
     *
     * ## 为什么在 Application 上实现
     *
     * 这是系统唯一会**主动通知**我们「该释放了」的通道。挂在 Application
     * 上才能覆盖全部页面（聊天页、终端、下载），挂在 Activity 上就只能
     * 覆盖前台那一个。
     *
     * ## 这里做什么，不做什么
     *
     * ```
     * 做：把 level 转发给 MemoryMonitor（它会立刻重算档位并发布）
     * 不做：直接清缓存 / 直接压缩
     * ```
     *
     * 原因：Action 需要知道「清什么、压哪个会话」。`MarkdownParseCache` 是
     * 全局单例可以在这里清，但**压缩必须由 ChatViewModel 做**（要动
     * history、要调 provider）。所以这里只转发信号，动作交给订阅方 ——
     * 这也让「谁在什么条件下做了什么」只有一处真相。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val monitor = memoryMonitor ?: return

        // 转发 level（内部会立刻 sample 一次并发布新档位）。
        monitor.onTrimMemory(level)

        // 可重建的全局缓存可以直接清：它是纯计算结果，清掉只会让下一次
        // 解析重跑一遍，不影响任何状态。
        //
        // 从 UI_HIDDEN（20）起才清：更低的 level（RUNNING_MODERATE=5 等）
        // 只表示「内存偏紧，但还在正常用」，此时清缓存会让用户正在看的
        // 长消息滚动时重新解析，出现可感知的卡顿。
        if (level >= TRIM_CACHE_THRESHOLD) {
            dev.praxis.ui.widgets.markdown.MarkdownParseCache.clear()
            Timber.tag("Memory").i("onTrimMemory(%d): cleared markdown cache", level)
        }
    }

    /**
     * 系统极低内存回调（老 API，`onTrimMemory(TRIM_MEMORY_COMPLETE)` 的前身）。
     *
     * 转发成对应 level 走同一条路径，而不是另写一套逻辑：
     * 两套逻辑必然分叉，而这条路径极少被走到，分叉后更难发现。
     */
    override fun onLowMemory() {
        super.onLowMemory()
        onTrimMemory(TRIM_MEMORY_COMPLETE_LEVEL)
    }

    companion object {
        /**
         * 清缓存的最低 trim level。
         *
         * `TRIM_MEMORY_UI_HIDDEN` = 20：界面已不可见，此时清缓存零代价。
         * 更低的值代表「还在前台但内存紧」，那些留给轮询判定去处理。
         */
        private const val TRIM_CACHE_THRESHOLD = 20

        /**
         * `ComponentCallbacks2.TRIM_MEMORY_COMPLETE` 的数值。
         *
         * 直接写字面量而不是引用常量：`onLowMemory` 是废弃 API，
         * 语义上等价于「进程即将被杀」，用 COMPLETE 的数值最贴近。
         */
        private const val TRIM_MEMORY_COMPLETE_LEVEL = 80

        /**
         * 当前的内存监控实例（`onTrimMemory` 要用）。
         *
         * ## 为什么用静态字段而不是 Koin 注入
         *
         * `onTrimMemory` 是 `Application` 的重写方法，拿不到构造参数。
         * 用 `companion object` 存一份是唯一办法。
         *
         * 用 `@Volatile`：主线程写（onCreate）、主线程读（onTrimMemory），
         * 但为了将来可能从别的线程读，标上更安全，代价为零。
         */
        @Volatile
        private var memoryMonitor: dev.praxis.data.memory.MemoryMonitor? = null
    }
}
