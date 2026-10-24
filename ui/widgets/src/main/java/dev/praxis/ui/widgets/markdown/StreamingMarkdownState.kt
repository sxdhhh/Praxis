package dev.praxis.ui.widgets.markdown

/**
 * 流式 Markdown 的**增量**解析。
 *
 * ## 它解决的问题（真机缺陷，2026-10-22）
 *
 * 流式输出时每个 delta 都会把「截至目前的全文」交给 `MarkdownParser.parse`。
 * 一条 8000 字的回答被切成约 800 个 delta，于是总解析量是
 *
 * ```
 * 1 + 2 + ... + 800 个 delta × 平均 4000 字 ≈ 320 万字符
 * ```
 *
 * 也就是 **O(n²)**，且全部发生在主线程的组合阶段。表现是：
 * 回答越写越长，掉帧越明显 —— 用户描述为「越聊越卡」。
 *
 * ## 为什么可以增量
 *
 * Markdown 的块以**空行**分隔（围栏代码块内部除外）。所以「最后一个空行之前」
 * 的内容，其块结构已经不会再变了 —— 后续文本只会追加新的块，
 * 不会反过来修改前面已完成的块。
 *
 * 于是可以把它切成两段：
 *
 * ```
 * [ frozen：已确定，解析一次就冻结 ] [ tail：还在增长，每次重新解析 ]
 * ```
 *
 * 每来一个新 delta，只需要：
 *  1. 若新出现了空行，把「上次冻结位置 → 新切点」这一小段解析后追加进 frozen；
 *  2. 重新解析 tail（很短）。
 *
 * 单次成本从 O(n) 降到 O(delta)，总量从 O(n²) 降到 O(n)。
 *
 * ## 唯一的例外：围栏代码块
 *
 * 代码块内部可以有空行，那些空行**不是块边界**。所以切点必须验证
 * 「切点之前的围栏是平衡的（开合成对）」。不平就往前退到上一个空行，
 * 实在找不到就整段解析（保守回落到原来的行为）。
 *
 * ## 为什么不做成 Composable 状态
 *
 * 这个类是纯 Kotlin，不依赖 Compose。于是「切点选择」「冻结/追加」
 * 「文本变短时重置」这些边界都能用纯 JVM 单测钉住 ——
 * 而它们恰恰是最容易写错、又最不容易在屏幕上发现的部分
 * （错了只会表现为「表格偶尔缺一行」这种玄学）。
 */
internal class StreamingMarkdownState {

    /** 已冻结部分的原文长度（`text[0, frozenLength)` 的块已算好）。 */
    private var frozenLength: Int = 0

    /** 已冻结的块。 */
    private var frozenBlocks: List<MdBlock> = emptyList()

    /**
     * 计算当前文本的块列表。
     *
     * @param text 截至目前的**完整**文本
     */
    fun blocksFor(text: String): List<MdBlock> {
        // ── 文本变短：发生了替换而不是追加 ──────────────────────────
        //
        // 「重新生成」或用户编辑会让全文变短。此时旧的 frozen 完全失效
        // （它对应的是另一段文本），必须整体重置，否则会把上一次回答的
        // 前半段与新回答的后半段拼在一起。
        if (text.length < frozenLength) reset()

        // ── 找新的安全切点 ─────────────────────────────────────────
        val cut = stableCut(text)
        if (cut > frozenLength) {
            // 只解析「上次冻结位置 → 新切点」这一小段，追加进 frozen。
            val delta = text.substring(frozenLength, cut)
            frozenBlocks = if (delta.isBlank()) {
                frozenBlocks
            } else {
                frozenBlocks + MarkdownParser.parse(delta)
            }
            frozenLength = cut
        }

        // ── 尾部：还在增长，每次重新解析（它通常很短） ──────────────
        if (frozenLength >= text.length) return frozenBlocks
        val tail = text.substring(frozenLength)
        if (tail.isBlank()) return frozenBlocks
        return frozenBlocks + MarkdownParser.parse(tail)
    }

    /** 重置（新消息开始 / 文本被替换时调用）。 */
    fun reset() {
        frozenLength = 0
        frozenBlocks = emptyList()
    }

    /**
     * 找「可以安全冻结」的位置：从这里切开，前段与后段的块互不影响。
     *
     * 规则：
     *  - 候选 = 最后一个空行之后的位置；
     *  - 但候选之前的内容里，围栏代码块必须**开合成对**
     *    （否则那个空行在代码块内部，不是块边界）；
     *  - 逐个往前退，直到找到平衡点；一个都没有就返回 0（整段当 tail）。
     *
     * @return 切点（`text[0, cut)` 可冻结）。0 表示不冻结。
     */
    fun stableCut(text: String): Int {
        var searchEnd = text.length
        while (searchEnd > 0) {
            val idx = text.lastIndexOf("\n\n", searchEnd - 1)
            if (idx <= 0) return 0
            val cut = idx + 2
            // 只需检查「本次要新冻结的那一段」的围栏平衡 ——
            // 已经冻结过的部分在冻结时就是平衡的，不必重扫全文。
            // 这保证每次 delta 只扫新增内容，维持 O(delta)。
            if (fencesBalanced(text, frozenLength, cut)) return cut
            searchEnd = idx
        }
        return 0
    }

    /**
     * `text[from, to)` 范围内的围栏代码块是否开合成对。
     *
     * 只统计「以 ``` 或 ~~~ 开头的行」，与 `MarkdownParser.isFence` 同一判据。
     */
    private fun fencesBalanced(text: String, from: Int, to: Int): Boolean {
        var fenceCount = 0
        var lineStart = from
        while (lineStart < to) {
            var lineEnd = text.indexOf('\n', lineStart)
            if (lineEnd < 0 || lineEnd > to) lineEnd = to
            val line = text.substring(lineStart, lineEnd).trimStart()
            // 三种及以上的反引号或波浪号开头即视为围栏。
            if (line.startsWith("```") || line.startsWith("~~~")) fenceCount++
            lineStart = lineEnd + 1
        }
        return fenceCount % 2 == 0
    }
}