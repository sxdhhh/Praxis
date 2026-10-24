package dev.praxis.data.memory

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import dev.praxis.core.ai.MemoryPressure
import dev.praxis.core.ai.MemoryPressurePolicy
import dev.praxis.core.ai.MemorySnapshot
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 内存监控（采集 + 档位发布）。
 *
 * ## 职责边界：只采集，不执行动作
 *
 * 这个类**不做**清缓存、不调压缩。它只做两件事：
 *
 *  1. 周期性读真实内存，算出 [MemoryPressure] 并发到 [pressure]；
 *  2. 提供 [onTrimMemory] 入口，把系统的 trim level 注入进来。
 *
 * 「该做什么」由订阅方决定（`ChatViewModel` 订阅后决定是否压缩，
 * `PraxisApplication` 订阅后决定是否清缓存）。
 *
 * 这样分的两个实际好处：
 *
 *  - 压缩需要 `ChatViewModel`（要动 history、要调 provider），
 *    监控器不该反向依赖 feature 层；
 *  - 单测可以只测采集与判定，不需要造一个 ViewModel。
 *
 * ## 为什么采集口径是「三项」而不是只看 Java 堆
 *
 * `Runtime.maxMemory()` 是**堆上限**，而 Android 上一个进程还持有
 * native 堆（图片解码、SQLite、字体）与图形内存。只看 Java 堆会漏掉
 * 「Java 堆 50% 但 native 已经 300MB」这类真实 OOM 场景。
 *
 * 所以三项都读：
 *
 * | 项 | 来源 | 用途 |
 * |---|---|---|
 * | Java 堆 | `Runtime` | 主判据（最稳定，各版本一致） |
 * | 系统可用 / 总量 | `ActivityManager.MemoryInfo` | 辅助判据 |
 * | native 堆 | `Debug.getNativeHeapAllocatedSize` | 只进日志与诊断，不参与判定 |
 *
 * native 堆不参与判定是**有意**的：它的正常水位随使用场景波动极大
 * （一张 4K 图片就能涨几十 MB），拿它判档会在正常看图时误触发压缩。
 *
 * ## 为什么轮询而不是只靠 onTrimMemory
 *
 * `onTrimMemory` 只在系统**已经决定**要回收时回调，那时通常已经晚了
 * （体验上是「用着用着突然卡一下，然后历史被压缩」）。
 * 轮询能在到达 High 之前就提前清理，把动作摊平到用户不察觉的时刻。
 *
 * 但轮询不能太频繁：每次读 `MemoryInfo` 要走一次 binder，1 秒一次
 * 在低端机上是可观的浪费。15 秒是折中（内存涨到危险水位通常以分钟计）。
 */
class MemoryMonitor(
    private val context: Context?,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {

    private val _snapshot = MutableStateFlow(MemorySnapshot())
    val snapshot: StateFlow<MemorySnapshot> = _snapshot.asStateFlow()

    private val _pressure = MutableStateFlow(MemoryPressure.Normal)
    val pressure: StateFlow<MemoryPressure> = _pressure.asStateFlow()

    /**
     * 最近一次 `onTrimMemory` 的 level。
     *
     * 用 `AtomicInteger` 而不是普通字段：`onTrimMemory` 在主线程回调，
     * 而轮询在 Default 线程读，两者没有 happens-before 关系。
     */
    private val lastTrimLevel = AtomicInteger(0)

    /**
     * [lastTrimLevel] 的写入时刻（`SystemClock.elapsedRealtime`，单调递增）。
     *
     * ## 为什么 trim level 需要过期
     *
     * `onTrimMemory` 描述的是「**此刻**系统希望我们释放」，不是持续状态。
     * 若把它永久保留，会有两条真实的坏结果：
     *
     *  1. 用户切一次后台（发 `TRIM_MEMORY_UI_HIDDEN` = 20），回来之后的每一轮
     *     轮询都带着 20 —— 档位永久卡在 Elevated，一直在清缓存；
     *  2. 收到一次 `RUNNING_MODERATE`（= 5）就永久 High，于是每次进聊天页
     *     第一轮就触发一次上下文压缩（一次模型调用），而内存其实早就正常了。
     *
     * 所以只保留 [TRIM_TTL_MS] 内的最近一条。过期后按「没收到过」处理，
     * 让堆占用率重新成为唯一判据。
     *
     * 用 `elapsedRealtime` 而不是 `currentTimeMillis`：用户改系统时间时
     * 后者会跳变，跳变方向一旦让 `now - at` 变成负数，trim 就永远不会过期。
     */
    private val lastTrimAt = AtomicLong(0L)

    private var pollJob: Job? = null

    /**
     * 开始轮询。
     *
     * 幂等：重复调用不会起第二个 job（否则每次进聊天页都多一个协程，
     * 内存监控本身变成内存泄漏源）。
     */
    fun start(scope: CoroutineScope) {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                sample()
                delay(pollIntervalMs)
            }
        }
    }

    /** 停止轮询（进程退出或进入长期后台时调）。 */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * 系统内存回调转发（`Application.onTrimMemory` / `onLowMemory`）。
     *
     * ## 为什么这里要「立即重算」而不是等下一轮轮询
     *
     * trim 回调是系统在**要求**我们释放。此时若等 15 秒后的下一轮，
     * 可能已经被杀了。所以收到回调立刻记下 level 并重算一次，
     * 让订阅方在同一帧就能拿到新档位。
     */
    fun onTrimMemory(level: Int) {
        lastTrimLevel.set(level)
        lastTrimAt.set(SystemClock.elapsedRealtime())
        sample()
    }

    /**
     * 取当前仍然有效的 trim level（超出 [TRIM_TTL_MS] 视为 0）。
     *
     * 单独的 `if` 而不是在 `onTrimMemory` 里安排一个 `delay` 去清零：
     * 定时清零需要多一个协程与一个 job 句柄，而延迟读的判断是纯函数、
     * 无生命周期问题。
     */
    private fun effectiveTrimLevel(): Int {
        val at = lastTrimAt.get()
        if (at == 0L) return 0
        val age = SystemClock.elapsedRealtime() - at
        return if (age in 0 until TRIM_TTL_MS) lastTrimLevel.get() else 0
    }

    /** 读一次并发布。 */
    fun sample(): MemorySnapshot {
        val snapshot = readSnapshot()
        _snapshot.value = snapshot

        val previous = _pressure.value
        val next = MemoryPressurePolicy.evaluate(snapshot, previous)
        if (next != previous) {
            _pressure.value = next
            // 只在**档位变化**时打日志。每 15 秒打一条会把日志刷满，
            // 而这个信号的用途是「事后复盘内存为什么涨」，变化点就够。
            Timber.tag(TAG).i(
                "memory pressure %s -> %s (%s)",
                previous.name,
                next.name,
                snapshot.describe(),
            )
        }
        return snapshot
    }

    /** 读真实内存。任何一项读不到都给 0，并在快照里体现（不抛异常）。 */
    private fun readSnapshot(): MemorySnapshot {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        val max = runtime.maxMemory()

        // `maxMemory()` 返回 `Long.MAX_VALUE` 表示「无上限」（JVM 默认，
        // 但 Android 上不会是）。此时按 0 处理，避免占用率恒为 0。
        val heapMax = if (max > 0L && max < Long.MAX_VALUE / 2) max else 0L

        val native = runCatching { Debug.getNativeHeapAllocatedSize() }.getOrDefault(0L)

        val am = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = if (am != null) {
            runCatching {
                ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            }.getOrNull()
        } else {
            null
        }

        return MemorySnapshot(
            javaHeapUsedBytes = used,
            javaHeapMaxBytes = heapMax,
            systemAvailBytes = info?.availMem ?: 0L,
            systemTotalBytes = info?.totalMem ?: 0L,
            lowMemory = info?.lowMemory ?: false,
            trimLevel = effectiveTrimLevel(),
            nativeHeapBytes = native,
        )
    }

    companion object {
        private const val TAG = "MemoryMonitor"

        /**
         * 轮询间隔。
         *
         * 15 秒的理由见类 KDoc：内存涨到危险水位以分钟计，而每次采样
         * 要过一次 binder。1 秒一次在低端机上是纯浪费。
         */
        const val DEFAULT_POLL_INTERVAL_MS: Long = 15_000L

        /**
         * 一条 trim 信号的有效期。
         *
         * 60 秒 = 4 个轮询周期。取值理由：
         *  - 太短（如 15 秒）会让「系统连续要求释放」在两次回调之间被清掉，
         *    档位在 High / Normal 之间抖；
         *  - 太长（如 5 分钟）就是回到「永久保留」的老问题。
         *
         * 4 个周期足够让「内存真的紧」在多次轮询里被重复确认，
         * 也足够让「只是切了一次后台」在一分钟内自然过期。
         */
        const val TRIM_TTL_MS: Long = 60_000L
    }
}