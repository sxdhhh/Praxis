package dev.praxis.core.ai

/**
 * 一次内存采样（纯数据，零 Android 依赖，可单测）。
 *
 * ## 为什么是「快照 + 纯判定」而不是直接在 Android 层写 if
 *
 * 内存压力的判定逻辑如果和 `ActivityManager` 混在一起，就变成
 * 「只能在真机上试」的代码 —— 而这类逻辑最容易出错的地方恰恰是
 * **阈值组合**（哪个优先、临界值算不算、某项不可用时怎么办）。
 * 抽成纯数据 + 纯函数后，那些边界都能用 1 毫秒的单测锁死。
 *
 * 与 `TokenEstimator` / `ContextCompactor` 同一套分工：
 * 判定在 `:core:ai`，采集在 `:app`。
 *
 * @param javaHeapUsedBytes 本进程 Java 堆已用（`Runtime.totalMemory - freeMemory`）
 * @param javaHeapMaxBytes 本进程 Java 堆上限（`Runtime.maxMemory`）
 * @param systemAvailBytes 系统可用内存（`MemoryInfo.availMem`）；0 表示读不到
 * @param systemTotalBytes 系统总内存（`MemoryInfo.totalMem`）；0 表示读不到
 * @param lowMemory 系统是否已判定为低内存（`MemoryInfo.lowMemory`）
 * @param trimLevel 最近一次 `onTrimMemory` 的 level；0 表示没收到过
 * @param nativeHeapBytes 本进程 native 堆已用；0 表示读不到
 */
data class MemorySnapshot(
    val javaHeapUsedBytes: Long = 0L,
    val javaHeapMaxBytes: Long = 0L,
    val systemAvailBytes: Long = 0L,
    val systemTotalBytes: Long = 0L,
    val lowMemory: Boolean = false,
    val trimLevel: Int = 0,
    val nativeHeapBytes: Long = 0L,
) {
    /** 本进程 Java 堆占用率（0..1）。上限未知时为 0。 */
    val javaHeapRatio: Float
        get() = if (javaHeapMaxBytes <= 0L) {
            0f
        } else {
            (javaHeapUsedBytes.toFloat() / javaHeapMaxBytes).coerceIn(0f, 1f)
        }

    /** 系统内存占用率（0..1）。总内存未知时为 0。 */
    val systemRatio: Float
        get() = if (systemTotalBytes <= 0L) {
            0f
        } else {
            ((systemTotalBytes - systemAvailBytes).toFloat() / systemTotalBytes)
                .coerceIn(0f, 1f)
        }

    /** 是否所有指标都读不到（用于「监控不可用」时的降级判断）。 */
    val isUnavailable: Boolean
        get() = javaHeapMaxBytes <= 0L && systemTotalBytes <= 0L

    /** 一行人类可读摘要（进日志与诊断面板）。 */
    fun describe(): String = buildString {
        append("java=")
        append(formatBytes(javaHeapUsedBytes))
        append('/')
        append(formatBytes(javaHeapMaxBytes))
        append(" (")
        append((javaHeapRatio * 100).toInt())
        append("%)")
        if (systemTotalBytes > 0L) {
            append(", sys=")
            append((systemRatio * 100).toInt())
            append("%")
        }
        if (lowMemory) append(", lowMemory")
        if (trimLevel != 0) {
            append(", trim=")
            append(trimLevel)
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> {
            val mb = bytes / (1024L * 1024L)
            (mb / 1024).toString() + "G"
        }
        bytes >= 1024L * 1024L -> (bytes / (1024L * 1024L)).toString() + "M"
        bytes >= 1024L -> (bytes / 1024L).toString() + "K"
        else -> bytes.toString() + "B"
    }
}

/**
 * 内存压力的档位。
 *
 * ## 为什么分四档而不是「高/不高」
 *
 * 因为**能做和该做的事不同**：
 *
 * | 档位 | 该做什么 | 为什么 |
 * |---|---|---|
 * | [Normal] | 什么都不做 | 无故清理会丢掉热缓存，下次滚动要重新解析 |
 * | [Elevated] | 只清纯计算缓存（Markdown 解析） | 可重建、代价低，且立即可省几百 KB 到几 MB |
 * | [High] | 清缓存 **且** 触发上下文压缩 | 压缩要调一次模型（花钱、耗时），但能真正把历史体积降下来 |
 * | [Critical] | 清缓存 + 立即压缩 + 关掉非必要缓存 | 已经接近被杀，先用一切手段把水位降下去 |
 *
 * 把「清缓存」与「压缩上下文」分开是关键：前者免费但不能救急，
 * 后者有效但要调模型。混成一个动作会导致「内存一紧张就烧一次模型调用」，
 * 或者「一直只清缓存，涨上去还是被杀」。
 */
enum class MemoryPressure {
    Normal,
    Elevated,
    High,
    Critical,
    ;

    val label: String
        get() = when (this) {
            Normal -> "正常"
            Elevated -> "偏高"
            High -> "高"
            Critical -> "危险"
        }
}

/**
 * 内存压力判定（1:1 思路对齐 Operit `PerformanceMonitorManager` 的采样口径，
 * 判定策略按「能做什么」重新分层）。
 *
 * ## 三条独立信号，取最严重的一条
 *
 * ```
 * ① 系统 lowMemory 标志      ← 最权威：系统已经认为该进程该收敛了
 * ② onTrimMemory 的 level    ← 次权威：系统明确要求释放
 * ③ 本进程 Java 堆占用率      ← 我们自己能观测到的
 * ```
 *
 * **取最大值而不是加权平均**：任一条极端都该触发动作。
 * 加权平均会让「堆已占 95% 但系统内存充足」被平均成「还好」，
 * 而那种状态离 OOM 只差一次分配。
 *
 * ## 为什么不看系统内存占用率做主判据
 *
 * 现代 Android 系统的 `availMem` 包含可回收的 page cache，长期偏高是正常的。
 * 拿它做主判据会在「系统看着紧张但其实随时可回收」时误触发压缩（费钱）。
 * 所以它只作为**辅助降档**：堆不高但系统确实低时，升一档到 Elevated（清缓存）。
 *
 * ## 阈值怎么定的
 *
 * | 阈值 | 取值 | 理由 |
 * |---|---|---|
 * | 堆 Elevated | 0.75 | 与 `ContextCompactor.DEFAULT_THRESHOLD` 同量级，留出一次大分配的余量 |
 * | 堆 High | 0.85 | 再往上，一次 `ByteArray` 分配就可能 OOM |
 * | 堆 Critical | 0.92 | 已经到了「下一帧就可能崩」的区域 |
 * | 系统 Elevated | 0.90 | 只在系统也真的很紧时升一档，避免误触发 |
 */
object MemoryPressurePolicy {

    /** 本进程 Java 堆占上限的比例 → Elevated。 */
    const val HEAP_ELEVATED = 0.75f

    /** → High。 */
    const val HEAP_HIGH = 0.85f

    /** → Critical。 */
    const val HEAP_CRITICAL = 0.92f

    /** 系统占用率 >= 此值 → 至少 Elevated（辅助判据）。 */
    const val SYSTEM_ELEVATED = 0.90f

    /**
     * 降档死区（滞回宽度）。
     *
     * 升档用上面四个阈值，降档要额低这么多才降。取值 0.05 ≈ 一次大 GC
     * 能拉开的幅度：比它小的波动属于噪声，不值得改变动作。
     */
    const val DEADBAND = 0.05f

    /**
     * `ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL` 的数值。
     *
     * 写数值而不是引用常量：`:core:ai` 是纯 JVM 模块，不依赖 Android SDK。
     * 这几个值从 API 14 起没变过，写死并在此注明来源。
     */
    const val TRIM_RUNNING_MODERATE = 5

    /** `TRIM_MEMORY_RUNNING_LOW`。 */
    const val TRIM_RUNNING_LOW = 10

    /** `TRIM_MEMORY_RUNNING_CRITICAL`。 */
    const val TRIM_RUNNING_CRITICAL = 15

    /**
     * `TRIM_MEMORY_UI_HIDDEN`：**界面已不可见**，与内存压力无关。
     *
     * 它在每次切后台都会发。旧实现把它归到「>= 15」那一档，于是用户
     * 切一次后台回来，判定就永久是 Critical —— 这是本轮修掉的真实缺陷。
     */
    const val TRIM_UI_HIDDEN = 20

    /**
     * 判定档位。
     *
     * @param snapshot 本次采样
     * @param previous 上一次判定的档位。用于**滞回**（见下）
     *
     * ## 为什么需要 `previous`（滞回 / hysteresis）
     *
     * 内存占用在 0.75 附近来回跳是常态（一次 GC 就掉下去）。
     * 若「到 0.75 就升档、低于 0.75 就降档」，会在几秒内反复触发
     * 清缓存与压缩 —— 压缩还要调模型，等于持续烧钱。
     *
     * 所以降档要**多降一档的余量**：只有掉到「上一档阈值 - 5%」以下才降。
     * 这与 `ChatViewModel` 里 `queueWasBlocked` 的「跳变沿」是同一类处理：
     * 状态机需要一个死区。
     */
    fun evaluate(
        snapshot: MemorySnapshot,
        previous: MemoryPressure = MemoryPressure.Normal,
    ): MemoryPressure {
        val raw = rawLevel(snapshot)
        // 升档或不变：直接用原始判定。
        if (raw >= previous) return raw

        // 降档方向：先看系统是不是还在要求释放。
        //
        // `lowMemory` 不会因为我们清了缓存就变 false —— 它由系统侧的内存水位决定。
        // 在这个标志还在时降档，会出现「清完缓存立刻又升回去」的循环，
        // 而每轮升上去就可能多烧一次压缩。
        if (snapshot.lowMemory) return previous

        // 仍在死区内 → 维持原档位；否则才真的降。
        return if (holdsAtLeast(snapshot, previous)) previous else raw
    }

    /**
     * 当前采样是否仍足以支撑 [level] 这一档（降档死区判据）。
     *
     * ## 为什么不能只看堆占用率
     *
     * `rawLevel` 里有一项辅助判据是「系统占用率 >= [SYSTEM_ELEVATED] → Elevated」。
     * 如果降档死区只查堆，就会出现这种真实抖动：
     *
     * ```
     * 系统 95% / 堆 30%  → raw = Elevated
     * 死区判据（只看堆 30% < 0.70）→ 判定可以降到 Normal
     * 下一轮 raw 又说 Elevated → 每 15 秒在 Elevated / Normal 之间跳一次
     * ```
     *
     * 每跳一次都跟着一轮「清缓存」。这一项必须一起查。
     *
     * 只对 [MemoryPressure.Elevated] 查系统项：High / Critical 这两档
     * 都是堆或前台 trim 推上去的，系统占用率低不能成为降档理由。
     */
    private fun holdsAtLeast(snapshot: MemorySnapshot, level: MemoryPressure): Boolean {
        val heapFloor = heapThresholdFor(level) - DEADBAND
        if (snapshot.javaHeapRatio > heapFloor) return true
        if (level == MemoryPressure.Elevated &&
            snapshot.systemRatio > SYSTEM_ELEVATED - DEADBAND
        ) {
            return true
        }
        // 前台 trim 仍在有效期内（由 MemoryMonitor 负责过期）：不降。
        if (snapshot.trimLevel >= TRIM_RUNNING_MODERATE &&
            snapshot.trimLevel < TRIM_UI_HIDDEN &&
            trimLevelToPressure(snapshot.trimLevel) >= level
        ) {
            return true
        }
        return false
    }

    /**
     * 不做滞回的原始判定（测试与诊断用）。
     *
     * ## 为什么是「逐项算、取最大」而不是 if 链
     *
     * 旧实现是一条 if 链，`if (trim >= 15) return Critical` 在最前面。
     * 那会在「系统顺手发了个后台 trim」时把**堆的真实水位盖住**：
     * 堆其实只有 40%，判定却一直是 Critical。
     *
     * 反过来把堆放最前也不行 —— 会盖住「系统已经明确要求释放」这条更权威的信号。
     *
     * 逐项算完取最大值：两条信号都保留，互不遮蔽。
     */
    fun rawLevel(snapshot: MemorySnapshot): MemoryPressure {
        var level = MemoryPressure.Normal

        // ① 本进程堆占用率：主判据。
        //    各 Android 版本都稳定，且是唯一「涨上去必然 OOM」的量。
        val heap = snapshot.javaHeapRatio
        if (heap >= HEAP_CRITICAL) {
            level = MemoryPressure.Critical
        } else if (heap >= HEAP_HIGH) {
            level = MemoryPressure.High
        } else if (heap >= HEAP_ELEVATED) {
            level = MemoryPressure.Elevated
        }

        // ② 系统内存占用率：只在堆不高时兜底升到 Elevated。
        //    理由见对象 KDoc：`availMem` 含可回收 page cache，拿它触发压缩会误烧模型调用。
        if (snapshot.systemRatio >= SYSTEM_ELEVATED && level < MemoryPressure.Elevated) {
            level = MemoryPressure.Elevated
        }

        // ③ 系统 lowMemory 标志：至少 High。
        //
        // 不直接跳到 Critical：`lowMemory` 的口径包含「后台进程该退出」这类
        // 与本进程无关的情况，一步到 Critical（连带压缩）过于激进。
        if (snapshot.lowMemory && level < MemoryPressure.High) {
            level = MemoryPressure.High
        }

        // ④ onTrimMemory 的 level：按「系统到底说了什么」映射，与上面取较大者。
        val trim = trimLevelToPressure(snapshot.trimLevel)
        if (trim > level) level = trim

        return level
    }

    /**
     * 把 `ComponentCallbacks2` 的 trim level 映射成档位。
     *
     * | level | 常量 | 档位 | 理由 |
     * |---|---|---|---|
     * | 5 | RUNNING_MODERATE | Elevated | 前台偏低，清缓存就够 |
     * | 10 | RUNNING_LOW | High | 前台运行中，已明确偏低 |
     * | 15 | RUNNING_CRITICAL | Critical | 前台临界，再不放就可能被杀 |
     * | 20 | UI_HIDDEN | Elevated | **只是「界面不可见」**，与内存压力无关 |
     * | 40 / 60 / 80 | BACKGROUND / MODERATE / COMPLETE | Elevated | 后台 LRU 里排到可回收位 |
     *
     * ## 为什么 20 与 40/60/80 只到 Elevated
     *
     * 20 的官方语义是「界面已不可见，可释放 UI 资源」，每次切后台都会发，
     * **不代表内存紧张**。把它当危险是旧实现的真实缺陷。
     *
     * 40/60/80 表示进程在后台 LRU 表里排到会被优先回收的位置 —— 确实危险，
     * 但**后台不会发消息**，此时压缩上下文是纯浪费（一次模型调用换不到任何体验）。
     * 所以后台只做到「可以清缓存」（Elevated）而不进入压缩档（High 起）。
     *
     * @param trim 原始 level；0 表示没收到过
     */
    fun trimLevelToPressure(trim: Int): MemoryPressure = when {
        trim < TRIM_RUNNING_MODERATE -> MemoryPressure.Normal
        // MODERATE：系统内存偏低，但还不需要动上下文（那是要花钱的）。
        trim < TRIM_RUNNING_LOW -> MemoryPressure.Elevated
        trim < TRIM_RUNNING_CRITICAL -> MemoryPressure.High
        trim < TRIM_UI_HIDDEN -> MemoryPressure.Critical
        // 20 / 40 / 60 / 80：后台态，只做到「可以清缓存」。
        else -> MemoryPressure.Elevated
    }

    /** 某个档位对应的堆阈值（供滞回计算与 UI 说明）。 */
    fun heapThresholdFor(level: MemoryPressure): Float = when (level) {
        MemoryPressure.Normal -> 0f
        MemoryPressure.Elevated -> HEAP_ELEVATED
        MemoryPressure.High -> HEAP_HIGH
        MemoryPressure.Critical -> HEAP_CRITICAL
    }

    /**
     * 这个档位是否应当**清可重建缓存**。
     *
     * `Elevated` 起就清：清缓存是免费的，而 Elevated 已经说明水位不低。
     */
    fun shouldTrimCaches(level: MemoryPressure): Boolean =
        level >= MemoryPressure.Elevated

    /**
     * 这个档位是否应当**触发上下文压缩**。
     *
     * ## 为什么从 High 起才压缩
     *
     * 压缩要调一次模型：花钱、耗时几秒到几十秒，且会改变模型看到的历史。
     * 在 Elevated（只是偏高）就压缩，会造成「聊得稍微久一点就被压缩」
     * 的体验，而收益很小 —— 真实的内存大头往往在图片与 Markdown 缓存，
     * 清缓存就够了。
     *
     * 到了 High 才说明「清缓存不够」，此时压缩是唯一能真正降低
     * `history` 内存的手段（一份长对话的 `ChatMessage` 列表可以占几 MB）。
     */
    fun shouldCompact(level: MemoryPressure): Boolean =
        level >= MemoryPressure.High
}
/**
 * 一次压缩是由什么触发的（只用于日志与「为什么被压了」的追溯）。
 *
 * ## 为什么需要它
 *
 * 压缩会改变模型看到的历史，用户会觉得「它忘了刚才的话」。
 * 事后要能回答「那次为什么压」—— 是用户自己点的，还是内存压力触发的。
 * 两种情况的责任归属完全不同，没有这个标记就只能猜。
 *
 * 它**不影响压缩行为**：折叠范围、提示词、温度在两种触发下完全一致。
 */
sealed interface CompactionTrigger {
    /** 用户手动点「压缩」。 */
    data object Manual : CompactionTrigger

    /** 内存压力达到某个档位时自动触发。 */
    data class ByMemoryPressure(val level: MemoryPressure) : CompactionTrigger

    /** 日志用的一行描述。 */
    fun describe(): String = when (this) {
        is Manual -> "manual"
        is ByMemoryPressure -> "memory:" + level.name
    }
}