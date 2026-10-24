package dev.praxis.feature.terminal.keyboard

/**
 * CTRL / ALT 修饰符状态。
 *
 * ## 为什么是两个独立布尔而不是一位枚举
 *
 * Operit 允许同时激活（`applyModifiers` 里先做 ctrl 映射、再给 alt 加前缀）。
 * 现实 POSIX 语义下 `Ctrl+Alt+X` 是 `ESC + Ctrl+X`，两者能叠加。
 * 用枚举 `None/Ctrl/Alt` 就表达不了同时激活，而在 PTY 接入后
 * 同时激活恰恰是 `Alt+方向`（vim 里按词跳）之外的真实组合。
 *
 * @param ctrl 激活后：方向键按词移动；PTY 接入后字符键转控制码。
 * @param alt 激活后：方向键按词移动；PTY 接入后字符键加 `ESC` 前缀。
 */
data class TerminalModifiers(
    val ctrl: Boolean = false,
    val alt: Boolean = false,
) {
    /** 是否任一修饰符激活。方向键的「按词移动」用它判。 */
    val any: Boolean get() = ctrl || alt

    /** 翻转 CTRL。 */
    fun toggleCtrl(): TerminalModifiers = copy(ctrl = !ctrl)

    /** 翻转 ALT。 */
    fun toggleAlt(): TerminalModifiers = copy(alt = !alt)

    /** 用完即清（一次性修饰符语义，同真实键盘的一次按键）。 */
    fun consumed(): TerminalModifiers = TerminalModifiers()
}

/**
 * 输入行的**完整可编辑状态**。
 *
 * ## 为什么不能只留一个 `String`
 *
 * 输入框的编辑键（`←` `→` `HOME` `END`）全部依赖**光标位置**。
 * 只有 `String` 时「左移一个字符」无从表达 —— 这正是旧实现
 * （只有 `BasicTextField` + `String`）无法加方向键的根本原因。
 *
 * 光标用 [Int] 而不是 Compose 的 `TextRange`：本层是纯 Kotlin，
 * 不依赖 Compose（见文件头「为什么纯逻辑」），UI 层再转 `TextRange`。
 *
 * ## 三个字段的分工
 *
 * | 字段 | 语义 |
 * |---|---|
 * | [text] | 当前输入内容 |
 * | [cursor] | 光标位置，取值 `0..text.length`（可等于长度＝行尾） |
 * | [modifiers] | 修饰符开关，用一次即清 |
 * | [historyIndex] | 正在浏览的历史下标；`-1` 表示没在浏览 |
 * | [draft] | 进入历史浏览前用户自己写的内容，退出浏览时还原 |
 *
 * ## [draft] 为什么必须存在
 *
 * 真实 shell 里，写了半句命令按 `↑` 看历史、再按 `↓` 回来，
 * 那半句**还在**。少了它，用户按两下方向键就丢掉了刚写的东西 ——
 * 这是极小但会让人当场骂人的缺陷。
 */
data class TerminalKeyInput(
    val text: String = "",
    val cursor: Int = 0,
    val modifiers: TerminalModifiers = TerminalModifiers(),
    val historyIndex: Int = -1,
    val draft: String = "",
) {
    init {
        require(cursor in 0..text.length) {
            "光标位置 $cursor 超出输入长度 ${text.length}"
        }
    }

    /** 把光标处的字面量插进去（替代选区：本层不做选区，见文件头）。 */
    fun insert(literal: String): TerminalKeyInput {
        val safeCursor = cursor.coerceIn(0, text.length)
        val next = text.substring(0, safeCursor) + literal + text.substring(safeCursor)
        return copy(
            text = next,
            cursor = safeCursor + literal.length,
            modifiers = modifiers.consumed(),
        )
    }

    /** 光标移动后重新校验边界（`coerceIn` 兜住历史/清空导致的长度变化）。 */
    fun moveCursor(to: Int): TerminalKeyInput =
        copy(cursor = to.coerceIn(0, text.length), modifiers = modifiers.consumed())
}

/**
 * 按键 → 输入状态的**纯函数**。
 *
 * ## 为什么是纯函数而不是 Composable 内的回调
 *
 * 每个键的行为都涉及光标、历史游标、修饰符消耗三方状态，写在 Composable 里
 * 会立刻变成「一屏回调 + 一堆 remember」，且**完全无法测试**（要跑 instrumentation）。
 * 抽成纯函数后 14 个键可以用普通 JUnit 逐一断言，包括最容易错的
 * 「历史越界」「按词移动边界」「修饰符是否被消耗」。
 *
 * ## 补全为什么不在这一层
 *
 * 这一层不碰文件系统，`TAB` 就是插空格（见 `DefaultTerminalKeyLayout` 的 KDoc）。
 * 将来接入持久会话后，补全的候选集也应当由**会话**提供而不是这里 —— 保持本层无副作用。
 *
 * @param key 按下的键。
 * @param state 当前输入状态。
 * @param history 历史命令（**旧的在前、新的在后**，与真实 shell 一致）。
 * @return 新的输入状态。同一个 state + 同一个 key 永远得到同一个结果。
 */
fun applyTerminalKey(
    key: TerminalKeyId,
    state: TerminalKeyInput,
    history: List<String>,
): TerminalKeyInput = when (key) {
    // ── 修饰符：只翻转自己，不消耗 ─────────────────────────
    //
    // ## 为什么修饰符键不调 `consumed()`
    //
    // 它是**开关**：按一下亮、再按一下灭。若在这里消耗自己，
    // 按下去的瞬间就灭了 —— 用户按 CTRL 后永远看不到它变亮，
    // 会以为按钮坏了（这是「开关型 vs 一次性」最容易搞混的地方）。
    TerminalKeyId.Ctrl -> state.copy(modifiers = state.modifiers.toggleCtrl())
    TerminalKeyId.Alt -> state.copy(modifiers = state.modifiers.toggleAlt())

    // ── ESC = 清空 ────────────────────────────────────────
    //
    // 真实终端里 ESC 是转义序列前缀，不显示任何东西。但在**输入行编辑**语境下
    // 用户对 ESC 的期待是「取消当前输入」（命令行编辑器的普遍行为）。
    // 选「清空」而不是「什么都不做」：一个按了完全无反应的键会被当成 bug。
    TerminalKeyId.Escape -> state.copy(
        text = "",
        cursor = 0,
        modifiers = state.modifiers.consumed(),
        historyIndex = -1,
        draft = "",
    )

    // ── 字面量 ────────────────────────────────────────────
    TerminalKeyId.Slash -> state.insert("/")
    TerminalKeyId.Dash -> state.insert("-")

    // ── TAB：两个空格（不补全，理由见 DefaultTerminalKeyLayout）──
    TerminalKeyId.Tab -> state.insert("  ")

    // ── 光标跳转 ──────────────────────────────────────────
    TerminalKeyId.Home -> state.moveCursor(0)
    TerminalKeyId.End -> state.moveCursor(state.text.length)

    // ── 光标平移（修饰激活时按词）─────────────────────────
    TerminalKeyId.Left -> state.moveCursor(
        if (state.modifiers.any) wordLeft(state.text, state.cursor) else state.cursor - 1,
    )
    TerminalKeyId.Right -> state.moveCursor(
        if (state.modifiers.any) wordRight(state.text, state.cursor) else state.cursor + 1,
    )

    // ── 历史 ──────────────────────────────────────────────
    TerminalKeyId.HistoryUp -> historyStep(state, history, delta = -1)
    TerminalKeyId.HistoryDown -> historyStep(state, history, delta = +1)

    // ## PGUP / PGDN 翻 5 条而不是 1 条
    //
    // 历史翻页的价值就是「快速跳一屏」。翻 1 条等于把 ↑/↓ 重复 5 次，
    // 那这两个键就没有存在意义。5 是 Operit 页大小与「拇指连点 5 次」的折中：
    // 更大会跳过用户想找的那条，更小则失去「翻页」的语义。
    TerminalKeyId.HistoryPageUp -> historyStep(state, history, delta = -HISTORY_PAGE_SIZE)
    TerminalKeyId.HistoryPageDown -> historyStep(state, history, delta = +HISTORY_PAGE_SIZE)
}

/**
 * 历史游标推进。
 *
 * ## 三种情况必须分开处理（这一段最容易写错）
 *
 * | 当前 | delta | 结果 |
 * |---|---|---|
 * | `-1`（没在浏览） | 负 | 记下 draft、跳到**最新一条** |
 * | `-1` | 正 | **不动** —— 本来就在最新的空行上，再往下没有内容 |
 * | 有效下标 | 任意 | 夹到 `0..lastIndex`；越过末尾则**还原 draft 并退出浏览** |
 *
 * 第二条容易被漏掉：若 `-1 + 1 = 0`，用户按 `↓` 会突然跳到**最旧**那条，
 * 方向反了且毫无预兆。
 *
 * ## 第一条为什么用 `history.size` 而不是 `lastIndex`（差一 bug，已修）
 *
 * 第一版写的是 `startIndex = lastIndex`，再 `+ delta(-1)` → 落在 `lastIndex - 1`，
 * 于是**第一次按 `↑` 拿到的是最旧那条**：
 *
 * ```
 * history = [ls, pwd]
 * 期望：按 ↑ → pwd（最近执行的）→ 再按 ↑ → ls
 * 实际：按 ↑ → ls（最旧）       → 再按 ↑ → 还是 ls（夹住了）
 * ```
 *
 * 用户会以为历史只存了一条。修法是把游标起点放在**末尾之后一格**
 * （`size`，等价于 `lastIndex + 1`），减一才落到 `lastIndex`。
 * 这与「光标从行尾往左退」是同一个直觉。
 *
 * @param delta 负数往旧、正数往新。
 */
private fun historyStep(
    state: TerminalKeyInput,
    history: List<String>,
    delta: Int,
): TerminalKeyInput {
    if (history.isEmpty()) return state

    // 没在浏览时不响应「往新」。
    if (state.historyIndex == -1 && delta > 0) return state

    // ## 起点：没在浏览时放在「末尾之后一格」（见 KDoc 的差一说明）
    //
    // 已在下标 `i` 时起点就是 `i`，加 delta 是正常的相对位移。
    val startIndex = if (state.historyIndex == -1) history.size else state.historyIndex
    val draft = if (state.historyIndex == -1) state.text else state.draft
    val target = startIndex + delta

    return if (target > history.lastIndex) {
        // 越过最新一条：回到空行并还原用户自己写的内容（真实 shell 行为）。
        state.copy(
            text = draft,
            cursor = draft.length,
            modifiers = state.modifiers.consumed(),
            historyIndex = -1,
            draft = "",
        )
    } else {
        val index = target.coerceIn(0, history.lastIndex)
        val line = history[index]
        state.copy(
            text = line,
            cursor = line.length,
            modifiers = state.modifiers.consumed(),
            historyIndex = index,
            draft = draft,
        )
    }
}

/**
 * 往左跳一个词：先跳过光标左边的空白，再跳到那个词的词首。
 *
 * 已经位于最左时不越界（返回 0）。
 *
 * ## 为什么「先跳空白」是必须的
 *
 * 光标通常紧贴某个词的末尾（`git commit` 后面）。若直接往左找空白边界，
 * 会立刻停在原地（左边就是字符、再左是空白），表现为「按一次没反应、按两次才动」。
 * 先跳过连续空白，再找下一个空白，才是「一次跳一个词」。
 */
private fun wordLeft(text: String, cursor: Int): Int {
    var i = cursor.coerceIn(0, text.length)
    while (i > 0 && text[i - 1].isWhitespace()) i--
    while (i > 0 && !text[i - 1].isWhitespace()) i--
    return i
}

/**
 * 往右跳一个词：先跳过光标右边的空白，再跳到那个词的词尾。
 *
 * 镜像 [wordLeft]。同样先跳空白，否则停在词中间。
 */
private fun wordRight(text: String, cursor: Int): Int {
    var i = cursor.coerceIn(0, text.length)
    val last = text.length
    while (i < last && text[i].isWhitespace()) i++
    while (i < last && !text[i].isWhitespace()) i++
    return i
}

/**
 * 翻页一次的条数。
 *
 * 见 `applyTerminalKey` 里 `HistoryPageUp/Down` 的说明：1 条没有意义
 * （等于重复按方向键），过大则会跳过目标。
 */
const val HISTORY_PAGE_SIZE: Int = 5