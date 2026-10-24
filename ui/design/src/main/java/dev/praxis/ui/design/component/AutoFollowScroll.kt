package dev.praxis.ui.design.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/**
 * 「有新内容时才跟随滚动」的引擎（pk-node 弹簧 + 增量推进）。
 *
 * ## 用户要的语义（逐字）
 *
 * > 「丝滑滚动不是切页的时候自动滚到底部，而是有新内容输出的时候才滚动」
 *
 * 触发条件是**内容变多**，不是**页面出现**。
 *
 * ## 2026-10-23 重构：为什么流式输出「完全不丝滑」
 *
 * 上一版有两个各自独立的缺陷，叠加后表现为「AI 吐字时列表纹丝不动」：
 *
 * ### 缺陷 1：触发源只看条目数，流式输出时它不变
 *
 * 旧版只有 `itemCount` 一个触发键，而聊天页传的是
 * `bubbles.size + (header != null ? 1 : 0)`。流式输出期间：
 *
 * ```
 * 气泡数量不变（还是同一条 assistant 气泡）
 * 变的是 bubble.text 越来越长 → 同一个 item 的高度在增长
 * ```
 *
 * 于是 `LaunchedEffect(…, itemCount, …)` 的 key 不变 → effect 不重跑 →
 * 判定「没有新增」→ `scrollToEnd` 从未被调用过一次。
 *
 * **修法**：新增 [contentVersion] 参数（调用方传「末条内容长度」之类的单调量），
 * 条目数与它任一变大就算「内容变多」。这是上游 Operit 的做法
 * （`ChatArea.kt:292` 的 `LaunchedEffect` key 里有 `lastMessageContentLength`）。
 *
 * ### 缺陷 2：每帧 `scrollToItem` 把速度轨迹抹平
 *
 * 旧版 `applyScrollPx` 每帧调 `state.scrollToItem(index, offset)`。
 * 那是**绝对定位 + 框架内部 snap**：每帧一次等于每帧取消一次滚动状态，
 * 弹簧算出来的速度轨迹被「每帧硬落位置」覆盖 —— 位移确实在动，
 * 但没有任何速度感，该滑行的段变成逐帧跳。
 *
 * **修法**：改增量推进 `dispatchRawDelta(step)`（等价 pk-node 的
 * `container.scrollTop += v * dt`）。它不取消滚动状态，速度才会变成
 * 视觉上连贯的位移。
 *
 * ### 缺陷 3（顺手修掉）：原来的「目标」其实是「剩余量」却被当绝对位置用
 *
 * 旧 `endContentOffsetPx` 返回 `lastVisible.offset + size - viewportEndOffset`，
 * 这是「还差多少像素到底」——一个**增量**。但它被当成绝对滚动位置喂给
 * `applyScrollPx` 去换算 `scrollToItem(index, -offset)`，语义错位，
 * 于是滚动终点经常偏。这里改成显式的「剩余量 → 速度 → 增量」模型。
 *
 * ## 为什么弹簧循环必须单独一个 effect，且 key 只有 [lazyState]
 *
 * 记忆里 v4 的死因：弹簧状态放在 `LaunchedEffect(itemCount)` 的局部变量里，
 * 每次 `itemCount` 变化协程被取消重启 → `v` 归零 → 永远停在起步加速段 →
 * 真机症状「一帧一停的闪帧式」。
 *
 * 所以本版把结构切成三块，职责互不重叠：
 *
 * | effect | key | 职责 |
 * |---|---|---|
 * | ① 视口探测 | `lazyState` | 只维护「是否跟随」开关，从不滚动 |
 * | ② 内容变更 | `lazyState` | 只**发信号**，从不滚动 |
 * | ③ 弹簧循环 | `lazyState` | 只滚动，信号来了就跑，跑完挂起等待 |
 *
 * 三者都**不随内容变化重启**（内容量通过 [rememberUpdatedState] 在内部读），
 * 于是速度状态天然连续。①②③ 之间用 [RunState.followSignal] 通信。
 *
 * ## 为什么弹簧用「剩余量」而不是「绝对位置」建模
 *
 * 绝对位置需要 `firstVisibleItemIndex × 平均项高` 这类估算，流式场景下
 * 项高每帧都在变，估算必然漂。改成一阶形式的「离底部还有多远」：
 *
 * ```
 * a = (k * remaining - c * v) / m
 * v += a * dt
 * 本帧滚动 = v * dt
 * ```
 *
 * 每帧重算 `remaining` 天然自校正 —— 内容又长高时它变大，速度自然接着给，
 * 不需要任何重启。这也是 pk-node 把 `target` 写在 step 内部的同一个道理。
 */
@Composable
fun AutoFollowScrollLazy(
    lazyState: LazyListState,
    /** 内容**条目数**。用于判「新增了一条」。 */
    itemCount: Int,
    /**
     * 内容**版本号**，可以是任意单调量（聊天页传末条正文字数）。
     *
     * 它存在的唯一目的：捕捉「条目数没变、但已有条目在长高」这种增量 ——
     * 流式输出、工具输出追加、日志行内追加都属于这一类。
     * 传常量即可退回旧行为（只在条目数变化时跟随）。
     */
    contentVersion: Int = 0,
    /** 是否启用。false 时整套逻辑不跑（连跟随开关都不维护）。 */
    enabled: Boolean = true,
    /**
     * 首次拿到**已测量**的列表时，是否直接定位到末尾。
     *
     * - 聊天 / 日志 / 终端：**true**（末尾才是最新）。
     * - 设置页 / 会话列表：**false**（打开时停在顶部）。
     */
    initialAutoFollow: Boolean = true,
    /**
     * 「换了一份内容」的标识（如终端切会话时的会话 id）。
     *
     * ## 为什么需要它（终端切会话必须重来一遍，但不该「滚一次」）
     *
     * 终端页切会话时，`lazyState` 仍是同一个（`rememberLazyListState()` 不随
     * 会话重建），而 `itemCount` / `contentVersion` 会变成一个**更小**的值
     * （新会话只有几行）。引擎把「变小」当「没新增」→ 不滚。
     * 那是对的：切过去应该从顶部开始读。
     *
     * 但如果新会话的行数恰好比旧会话**多**，`currentCount > previousCount`
     * 成立 → 引擎误判「有新内容」→ 切会话时自己滚到底。
     * 用户看到的正是「一进终端页就自动滚到最下面」，而他什么都没输入。
     *
     * 传了 `resetKey` 之后，它一变就**重置水位**并把跟随状态交还给
     * [initialAutoFollow]：切会话时既不残留旧会话的水位，也不会把
     * 「会话 A 有 50 行 → 会话 B 有 80 行」当成一次新增。
     *
     * ## 默认值为什么是 null
     *
     * null 表示「调用方不区分内容源」（聊天页、日志页都是单一份列表），
     * 行为与旧版完全一致。
     */
    resetKey: Any? = null,
) {
    // ## 为什么 key 是 `resetKey` 而不是无参 `remember`
    //
    // 无参时 `RunState` 跟着**组合位置**活。终端页切会话不换组合位置，
    // 于是 `lastCount` 留着上一个会话的水位 —— 见 `resetKey` 的 KDoc。
    //
    // 用 `remember(resetKey)` 重建：水位回到 [NOT_SEEN]、`measured` 归 false、
    // `autoFollow` 回到 [initialAutoFollow]，等价于「这个列表是新的」。
    // 注意这不是「滚一次就没了」：重建后仍需经过「测量 → 内容变多」两道判定，
    // 而 `initialAutoFollow = true` 的首次会滚到末尾 —— 对**首次进入**是对的，
    // 对**切会话**不对，所以终端页那边传 `initialAutoFollow = false`。
    val run = remember(resetKey) { RunState(initialAutoFollow) }

    // snapshotFlow 里要「读得到变化」，而 RunState 的字段是普通字段
    // （服务协程状态不能是 Compose 状态，理由见 RunState 注释）。
    // 只有 measured 需要被 snapshotFlow 观察：它翻一次 true 就该触发首轮判定。
    val measuredState = remember(resetKey) { mutableStateOf(false) }
    val latestCount = rememberUpdatedState(itemCount)
    val latestVersion = rememberUpdatedState(contentVersion)

    // ── ① 视口位置 → 跟随开关。**未测量时不做任何判断。** ──────────
    LaunchedEffect(lazyState, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow {
            val info = lazyState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            ViewportProbe(
                // isScrollInProgress 在 LazyListState 上，不在 layoutInfo 上。
                inProgress = lazyState.isScrollInProgress,
                // ★ 两个条件都满足才算「可判断」：
                //   totalItemsCount > 0 排除「还没测到条目」；
                //   last != null 排除「测过但当前无可见项」。
                ready = info.totalItemsCount > 0 && last != null,
                lastIndex = last?.index ?: -1,
                total = info.totalItemsCount,
            )
        }.collect { probe ->
            if (!probe.ready) return@collect
            run.measured = true
            if (!measuredState.value) measuredState.value = true
            val atBottom = isAtBottom(
                ready = probe.ready,
                lastVisibleIndex = probe.lastIndex,
                totalItems = probe.total,
            )
            when {
                atBottom -> run.autoFollow = true
                // 只有「用户自己」拖离底部才关跟随；我们自己的滚动不算。
                probe.inProgress && !run.selfScroll -> run.autoFollow = false
            }
        }
    }

    // ── ② 内容变更 → 只发信号，绝不在这里滚 ──────────────────────
    LaunchedEffect(lazyState, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow {
            Triple(latestCount.value, latestVersion.value, measuredState.value)
        }.collect { (count, version, measured) ->
            val previousCount = run.lastCount
            val previousVersion = run.lastVersion
            val grew = shouldFollowContent(
                previousCount = previousCount,
                currentCount = count,
                previousVersion = previousVersion,
                currentVersion = version,
                measured = measured,
                autoFollow = run.autoFollow,
                initialAutoFollow = initialAutoFollow,
            )
            // 无论是否滚动都推进水位：否则「未测量时收到的计数」会被反复
            // 当成首次，测完之后又滚一次。
            if (measured) {
                run.lastCount = count
                run.lastVersion = version
            }
            if (grew) run.requestFollow()
        }
    }

    // ── ③ 弹簧循环。key 只有 lazyState：内容变化**永不**重启它 ────
    LaunchedEffect(lazyState, enabled) {
        if (!enabled) return@LaunchedEffect
        var seen = run.followSignal.value
        while (true) {
            // 挂起等待信号（不空转）。
            run.followSignal.first { it != seen }
            seen = run.followSignal.value
            // 信号到达时可能用户已经翻离底部（autoFollow=false）→ 跳过。
            if (!run.autoFollow) continue
            followToEnd(lazyState, run)
        }
    }
}

/**
 * 配 `Modifier.verticalScroll(state)` 使用。
 *
 * 语义与 [AutoFollowScrollLazy] 一致（只在内容变多时跟随），
 * 区别是用像素而非条目下标表达「内容变多」。
 */
@Composable
fun AutoFollowScroll(
    state: ScrollState,
    /** 内容版本号：内容变了就换一个值。通常传列表 size 或文本长度。 */
    itemCount: Int,
    /** 距底部多少像素算「到底」。 */
    thresholdPx: Int = 48,
    enabled: Boolean = true,
    initialAutoFollow: Boolean = true,
) {
    val run = remember { RunState(initialAutoFollow) }
    val measuredState = remember { mutableStateOf(false) }
    val latestCount = rememberUpdatedState(itemCount)

    LaunchedEffect(state, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow {
            Triple(state.isScrollInProgress, state.value, state.maxValue)
        }.collect { (inProgress, value, max) ->
            // maxValue <= 0 表示「还没测量出内容高度」。同上，不据此改状态。
            if (max <= 0) return@collect
            run.measured = true
            if (!measuredState.value) measuredState.value = true
            val atBottom = value >= max - thresholdPx
            when {
                atBottom -> run.autoFollow = true
                inProgress && !run.selfScroll -> run.autoFollow = false
            }
        }
    }

    LaunchedEffect(state, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow { latestCount.value to measuredState.value }
            .collect { (count, measured) ->
                val previous = run.lastCount
                val should = shouldFollowScroll(
                    previousCount = previous,
                    currentCount = count,
                    measured = measured,
                    autoFollow = run.autoFollow,
                    initialAutoFollow = initialAutoFollow,
                )
                if (measured) run.lastCount = count
                if (should) run.requestFollow()
            }
    }

    LaunchedEffect(state, enabled) {
        if (!enabled) return@LaunchedEffect
        var seen = run.followSignal.value
        while (true) {
            run.followSignal.first { it != seen }
            seen = run.followSignal.value
            if (!run.autoFollow) continue
            followToEnd(state, run)
        }
    }
}

/** 还没见过任何 itemCount 的哨兵值。 */
private const val NOT_SEEN = -1

/**
 * 判定「这次内容变化该不该滚动」。**纯函数，可单测。**
 *
 * 与 [shouldFollowScroll] 的区别：本函数把「版本号变大」也算作内容变多。
 * 两者都要，因为存在两种截然不同的增量：
 *
 * | 场景 | 条目数 | 版本号 |
 * |---|---|---|
 * | 新来一条消息 / 新一行日志 | +1 | 变 |
 * | **流式输出追加到同一条** | 不变 | **变** |
 *
 * 旧版只看第一行，于是第二行这类「原地长高」永远不触发滚动。
 *
 * @param previousVersion 上次见到的版本号；[NOT_SEEN] 表示还没见过
 * @param currentVersion 本次版本号
 */
internal fun shouldFollowContent(
    previousCount: Int,
    currentCount: Int,
    previousVersion: Int,
    currentVersion: Int,
    measured: Boolean,
    autoFollow: Boolean,
    initialAutoFollow: Boolean,
): Boolean {
    // 未测量：布局还没稳定，此时滚动会滚到不存在的下标上。
    if (!measured) return false
    if (currentCount <= 0) return false
    // 首次：只由调用方的 initialAutoFollow 决定。
    if (previousCount == NOT_SEEN) return initialAutoFollow
    val countGrew = currentCount > previousCount
    val versionGrew = currentVersion != previousVersion
    // ★ 核心：条目变多，或同条目内容变长，才算「有新内容」。
    // 切页 / 重组 / 旋转屏幕都不会让这两个量变化，因此都不滚。
    if (!countGrew && !versionGrew) return false
    return autoFollow
}

/**
 * 判定「这次 recomposition 该不该滚动」。**纯函数，可单测。**
 *
 * 只认条目数。保留它是为了既有测试与 `AutoFollowScroll`（ScrollState 版）
 * 的语义不变；聊天页那条「原地长高」的路径走 [shouldFollowContent]。
 */
internal fun shouldFollowScroll(
    previousCount: Int,
    currentCount: Int,
    measured: Boolean,
    autoFollow: Boolean,
    initialAutoFollow: Boolean,
): Boolean {
    // 未测量：布局还没稳定，此时滚动会滚到不存在的下标上。
    if (!measured) return false
    if (currentCount <= 0) return false
    // 首次：只由调用方的 initialAutoFollow 决定。
    if (previousCount == NOT_SEEN) return initialAutoFollow
    // ★ 核心：只认「内容变多」。条目数没变多 → 切页/重组/旋转都不滚。
    if (currentCount <= previousCount) return false
    return autoFollow
}

/**
 * 判定「视口是否已经到底」——只在**已测量**时才可信。
 *
 * 旧实现把「未测量（last == null）」也当成底部，正是切页误滚的根因。
 * 这里把两种情况分开，并把这个区分做成可测的纯函数。
 */
internal fun isAtBottom(
    ready: Boolean,
    lastVisibleIndex: Int,
    totalItems: Int,
): Boolean {
    if (!ready) return false
    if (totalItems <= 0) return false
    return lastVisibleIndex >= totalItems - 1
}

/** 一次视口探测的结果。 */
private data class ViewportProbe(
    val inProgress: Boolean,
    val ready: Boolean,
    val lastIndex: Int,
    val total: Int,
)

/**
 * 引擎的跨协程共享状态。
 *
 * 全部是普通字段（不是 Compose 状态）。理由：写入 → 触发重组 →
 * 若它出现在某个 effect 的 key 里就会重启协程 → 速度丢失。
 * 那是自激循环，且极难定位。`@Volatile`：三个 effect 可能落在不同线程。
 */
private class RunState(initialAutoFollow: Boolean) {
    /** 是否处于跟随状态。 */
    @Volatile
    var autoFollow: Boolean = initialAutoFollow

    /** 列表是否已完成过一次测量。未测量前不做任何判断。 */
    @Volatile
    var measured: Boolean = false

    /** 上一次见到的条目数；[NOT_SEEN] 表示还没见过。 */
    @Volatile
    var lastCount: Int = NOT_SEEN

    /** 上一次见到的内容版本号；[NOT_SEEN] 表示还没见过。 */
    @Volatile
    var lastVersion: Int = NOT_SEEN

    /**
     * 「当前滚动是我们自己发起的」。
     *
     * 必须有：`dispatchRawDelta` 执行期间 `isScrollInProgress` 为 true，
     * 不加区分的话我们自己的滚动会被判成「用户上滑」→ 关掉跟随 →
     * 下一次新增就不跟了（旧版「滚两下就不滚了」的死因）。
     */
    @Volatile
    var selfScroll: Boolean = false

    /**
     * 弹簧当前速度（px/s）。
     *
     * 跨帧保存：每帧积分的输入是上一帧的输出。若每帧从 `state.value` 重新取，
     * 速度就永远为 0，积分退化成「一阶追赶」（慢且会顿）。
     */
    @Volatile
    var springV: Float = 0f

    /** 内容变更信号。弹簧循环在它上面挂起等待，不空转。 */
    val followSignal = MutableStateFlow(0)

    /** 请求跑一轮跟随。 */
    fun requestFollow() {
        followSignal.value = followSignal.value + 1
    }
}

/**
 * 追到列表末尾（弹簧驱动 + 增量推进，照 pk-node）。
 *
 * ## 为什么每帧重算 remaining，而不是启动时算一次
 *
 * 聊天气泡/终端行在动画进行中仍在增高。启动时算出的剩余几帧后就过期了。
 * pk-node 原实现把 `target = scrollHeight - clientHeight` 写在 step 函数
 * **内部**，就是这个原因，这里照抄。
 *
 * ## 退出条件
 *
 * 剩余 < [SpringFollower.SPRING_REST_X] 且速度 < [SpringFollower.SPRING_REST_V]。
 * 只看剩余会在目标附近无限微振（速度还大但位置够了），表现为「到底后还在抖」。
 */
private suspend fun followToEnd(lazyState: LazyListState, run: RunState) {
    run.selfScroll = true
    var last = 0L
    try {
        while (true) {
            val info = lazyState.layoutInfo
            val remaining = remainingDeltaPx(info) ?: return
            val remainingF = remaining.toFloat()
            if (remainingF <= SpringFollower.SPRING_REST_X &&
                abs(run.springV) < SpringFollower.SPRING_REST_V
            ) {
                return
            }
            val now = withFrameNanos { it }
            val dt = if (last == 0L) {
                1f / 60f
            } else {
                ((now - last) / 1_000_000_000.0).toFloat()
                    .coerceIn(0.001f, SpringFollower.SPRING_MAX_DT)
            }
            last = now

            // 把「离底部还剩 remaining」当作弹簧锚点：x 恒为 0，target = remaining。
            // step 返回的 position 就是本帧应该前进的像素数。
            //
            // `remainingF` 而不是 `remaining`：`SpringFollower.step` 的
            // `x/v/target/dt` 全是 `Float`（它与 pk-node 的 JS 实现对齐，
            // 那里本来就是浮点）。传 Int 会因为 Kotlin 不做隐式数值提升而编不过。
            val step = SpringFollower.step(
                x = 0f,
                v = run.springV,
                target = remainingF,
                dt = dt,
            )
            run.springV = step.velocity

            // 触底后速度必须归零：LazyList 已经在末端吞掉滚动，
            // 不归零的话弹簧会以为还没到、速度一直累积。
            if (remainingF <= 0f) run.springV = 0f

            val advanced = lazyState.dispatchRawDelta(step.position)
            // 一点都没吃进去 = 已经到物理末端，直接结束，避免空转。
            if (advanced == 0f && remainingF <= SpringFollower.SPRING_REST_X) return
        }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
    } finally {
        run.selfScroll = false
        // 一轮结束从静止开始：下一次跟随不继承上一轮的残余速度。
        run.springV = 0f
    }
}

/**
 * 「离列表末尾还有多少像素」。
 *
 * 只有正数才有意义（负数 = 已经越过末尾，属于内容变短或测量抖动，按 0 处理）。
 *
 * ## 为什么末项没进视口时返回一个「肯定够大」的值
 *
 * 那时无法得知末项的真实高度与偏移（LazyColumn 只测量视口内）。
 * 返回 `viewportEndOffset`（一屏高度）作为下限，让弹簧继续推进；
 * 下一帧末项进入视口后就走精确分支了。返回 0 会让弹簧误判「已到底」并停下，
 * 表现为「长内容只滚一小段就不动了」。
 */
private fun remainingDeltaPx(info: LazyListLayoutInfo): Int? {
    if (info.totalItemsCount <= 0) return null
    val last = info.visibleItemsInfo.lastOrNull() ?: return null
    if (last.index != info.totalItemsCount - 1) {
        return info.viewportEndOffset.coerceAtLeast(1)
    }
    // 精确分支：末项底边 减去 视口内容区末端（扣掉底部 contentPadding）。
    val contentEnd = info.viewportEndOffset - info.afterContentPadding
    return (last.offset + last.size - contentEnd).coerceAtLeast(0)
}

/**
 * 追到末尾（`Modifier.verticalScroll` 版）。
 *
 * 与 Lazy 版的唯一区别是「剩余量」直接由 `maxValue - value` 给出；
 * 推进方式同样是增量（`dispatchRawDelta`），不再是每帧 `scrollTo` 硬落位。
 */
private suspend fun followToEnd(
    state: ScrollState,
    run: RunState,
) {
    run.selfScroll = true
    var last = 0L
    try {
        while (true) {
            val remaining = (state.maxValue - state.value).coerceAtLeast(0)
            // 退出判据只看「剩不到 1px 且速度已静止」。
            // 不用 thresholdPx：那是「视口算不算到底」的宽容度（48px），
            // 拿来当滚动终点会让最后 48px 永远不滚，看起来像少滚一截。
            if (remaining <= 1 && abs(run.springV) < SpringFollower.SPRING_REST_V) {
                return
            }
            val now = withFrameNanos { it }
            val dt = if (last == 0L) {
                1f / 60f
            } else {
                ((now - last) / 1_000_000_000.0).toFloat()
                    .coerceIn(0.001f, SpringFollower.SPRING_MAX_DT)
            }
            last = now

            val step = SpringFollower.step(
                x = 0f,
                v = run.springV,
                target = remaining.toFloat(),
                dt = dt,
            )
            run.springV = step.velocity
            if (remaining <= 0) run.springV = 0f

            val advanced = state.dispatchRawDelta(step.position)
            if (advanced == 0f && remaining <= SpringFollower.SPRING_REST_X) return
        }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
    } finally {
        run.selfScroll = false
        run.springV = 0f
    }
}
