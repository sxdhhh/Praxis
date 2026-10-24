package dev.praxis.ui.widgets.markdown

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Markdown 解析结果缓存（跨组合生效）。
 *
 * ## 为什么 `MarkdownText` 里的 `remember(text)` 不够
 *
 * `remember` 的作用域是**当前 composition**。消息条目滚出视口后
 * LazyColumn 会销毁它的组合，滚回来时重新组合 —— `remember` 里的结果就没了，
 * 于是每次滚回来看一条长回答都要重新解析一遍全文。
 *
 * 对已完成的长消息，这个成本被反复支付：滚动、输入框聚焦/失焦、
 * 队列面板展开收起、长按菜单开关，每次都会触发重新组合。
 *
 * ## 为什么用 LRU 而不是无限 Map
 *
 * 无限 Map 会随会话变长单调增长，最终把内存吃光（每条长回答的块列表
 * 是几十个对象）。而「用户最近看的那几条」才是真正会被反复重组的，
 * 久远条目的缓存命中率接近 0。
 *
 * ## 为什么 key 用完整文本
 *
 * 用 hashCode 会有碰撞风险 —— 碰撞时返回**错误的渲染结果**，
 * 这比重新解析一次严重得多（而且几乎无法排查）。用全文做 key 的代价
 * 是 Map 里多存一份 String 引用（不是拷贝，Java 字符串是不可变共享的）。
 *
 * ## 线程安全
 *
 * 解析在 `Dispatchers.Default` 上做，多个条目并发解析时会同时访问；
 * 组合阶段读缓存也在主线程。所以整个访问必须同步。
 * 用 `synchronized` 而不是 `ConcurrentHashMap`：这里还有 LRU 的
 * 「读写顺序」语义（get 也要改动链表），`ConcurrentHashMap` 保证不了。
 */
object MarkdownParseCache {

    /** 最多缓存多少条。与 `MarkdownParseCachePolicy.MAX_ENTRIES` 保持一致。 */
    private const val MAX_ENTRIES = 16

    /**
     * 后台解析的并发上限。
     *
     * ## 为什么必须限并发（快速滑动会一次涌进十几条）
     *
     * `LaunchedEffect` 在每个新进入视口的条目上各起一个协程，都往
     * `Dispatchers.Default` 投任务。快速滑动时一屏可能同时涌入 10+ 条长消息，
     * 而 `Dispatchers.Default` 的线程数 = CPU 核数（低端机 4~6）。
     *
     * 结果是：解析任务把 Default 池占满，**同一时间只该干活的那几条**
     * 在互相争抢，每条的完成时间都被拉长 —— 观感是「滑过去之后，format
     * 迟迟不出来，然后突然一起变成 Markdown」。
     *
     * 用 2 个并发是刻意取的保守值：
     *  - 单条 8000 字解析约几毫秒，2 条并行已能追上手指的滑动速度；
     *  - 留出 CPU 给界面渲染本身（滚动、动画、输入法）——
     *    解析抢走渲染的 CPU 才是最难忍的卡。
     */
    private val parseDispatcher = java.util.concurrent.Executors
        .newFixedThreadPool(2) { runnable ->
            java.lang.Thread(runnable, "praxis-md-parse").apply { isDaemon = true }
        }
        .asCoroutineDispatcher()

    /**
     * 用 `LinkedHashMap(accessOrder = true)` 实现 LRU：
     * 每次 `get` 会把该项移到末尾，`removeEldestEntry` 在超限时淘汰最旧的。
     */
    private val cache = object : LinkedHashMap<String, List<MdBlock>>(
        /* initialCapacity = */ 16,
        /* loadFactor = */ 0.75f,
        /* accessOrder = */ true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MdBlock>>): Boolean =
            size > MAX_ENTRIES
    }

    /** 命中则返回缓存，否则调用 [parse] 并写入。 */
    fun getOrParse(text: String, parse: (String) -> List<MdBlock>): List<MdBlock> {
        synchronized(cache) {
            cache[text]?.let { return it }
        }
        // 解析放在锁外：解析可能耗时几毫秒，持锁会阻塞主线程的读。
        // 代价是极端并发下同一文本可能被解析两次 —— 结果相同，可接受。
        val parsed = parse(text)
        synchronized(cache) { cache[text] = parsed }
        return parsed
    }

    /**
     * 在**受控并发**的后台线程上解析（异步路径专用）。
     *
     * ## 为什么入口放在这里而不是让调用方自己 `withContext`
     *
     * 并发上限（[parseDispatcher] 的 2 个线程）必须由**所有异步解析共用**
     * 才有意义。若各调用点自己 `withContext(Dispatchers.Default)`，
     * 每个条目都往默认池投任务，快速滑动时十几条一起抢 CPU，
     * 限并发等于没做。
     *
     * ## 为什么要先查缓存
     *
     * 同一段文本可能被两条不同路径同时请求（例如上下快速来回滑，
     * 同一个气泡被反复组合）。解析前再查一次，能把重复解析挡掉。
     *
     * 解析完**写回缓存**：这条消息滚出视口后再滚回来，直接命中，
     * 不必再解析。
     */
    suspend fun parseAsync(text: String, parse: (String) -> List<MdBlock>): List<MdBlock> {
        peek(text)?.let { return it }
        val parsed = withContext(parseDispatcher) { parse(text) }
        // 竞态：两个协程同时解析同一文本时，后写的覆盖先写的 ——
        // 结果相同（纯函数），无副作用。
        put(text, parsed)
        return parsed
    }

    /** 清空（会话切换时调用，避免把上一个会话的缓存留着占内存）。 */
    fun clear() {
        synchronized(cache) { cache.clear() }
    }

    /**
     * **只查不解析**：命中返回结果，未命中返回 null。
     *
     * ## 为什么需要它（异步解析的前提）
     *
     * [getOrParse] 在未命中时会**同步执行** `parse` —— 那正是主线程卡顿的来源。
     * 而「异步解析」需要先把「查缓存」和「解析」拆开：
     *
     * ```
     * 组合阶段： peek(text)          ← 命中就直接渲染，未命中返回 null
     * 后台协程： parse(text) + put   ← 不阻塞主线程
     * ```
     *
     * 只有拆开，长消息第一次进入视口时才不会在组合阶段跑解析。
     *
     * ## 为什么 peek 也要动 LRU 顺序
     *
     * 要让「查了并用到」的条目被保留下来（LRU 语义是「最近使用」，
     * 查了就是使用）。否则连续 peek 一批旧条目后，它们仍按插入顺序
     * 排在最前，容易被先淘汰 —— 那会让「刚看过的反而先被清掉」。
     * `LinkedHashMap(accessOrder = true)` 的 `get` 已经自带这个行为。
     */
    fun peek(text: String): List<MdBlock>? = synchronized(cache) { cache[text] }

    /** 把已解析的结果写入缓存（异步解析完成后调用）。 */
    fun put(text: String, blocks: List<MdBlock>) {
        synchronized(cache) { cache[text] = blocks }
    }
}