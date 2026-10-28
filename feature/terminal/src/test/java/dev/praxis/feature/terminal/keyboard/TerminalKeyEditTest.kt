package dev.praxis.feature.terminal.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 虚拟键盘编辑层的单元测试。
 *
 * ## 为什么这一层值得单独测
 *
 * 14 个键里有 6 个涉及**游标状态机**（历史游标、光标位置、修饰符消耗）。
 * 它们在 Compose 里写就只能靠手工点，而手工点极难覆盖：
 *
 *  - 「历史翻到最旧再往旧按」会不会越界？
 *  - 「没在浏览时按 ↓」会不会跳到最旧那条？
 *  - 「写了半句 → ↑ 看历史 → ↓ 回来」半句还在不在？
 *  - 「按词左移」在标点后会不会卡住不动？
 *
 * 这些都是**一次性写对、之后永不回归**的问题，正是单测该锁住的东西。
 * 纯 Kotlin（无 Android 依赖），`./gradlew :feature:terminal:test` 即可跑。
 */
class TerminalKeyEditTest {

    // ── 字面量插入 ────────────────────────────────────────────

    @Test
    fun `斜杠插入到光标处而不是末尾`() {
        // 光标在 "cd " 之后（位置 3），插入 "/" 应得到 "cd /"。
        val state = TerminalKeyInput(text = "cd ", cursor = 3)
        val result = applyTerminalKey(TerminalKeyId.Slash, state, emptyList())

        assertEquals("cd /", result.text)
        // 光标跟到插入内容之后，否则连按两次会把字符插到反序。
        assertEquals(4, result.cursor)
    }

    @Test
    fun `横杠插入到文本中间`() {
        val state = TerminalKeyInput(text = "lsa", cursor = 2)
        val result = applyTerminalKey(TerminalKeyId.Dash, state, emptyList())

        assertEquals("ls-a", result.text)
        assertEquals(3, result.cursor)
    }

    @Test
    fun `Tab 插入两个空格`() {
        // cursor 必须显式给：`TerminalKeyInput` 的默认 cursor 是 0（行首），
        // 不给就会插到开头变成「  a」。真实 UI 永远传 `text.length`（光标在末尾）。
        val result = applyTerminalKey(
            TerminalKeyId.Tab,
            TerminalKeyInput(text = "a", cursor = 1),
            emptyList(),
        )

        assertEquals("a  ", result.text)
        assertEquals(3, result.cursor)
    }

    // ── 光标跳转与平移 ────────────────────────────────────────

    @Test
    fun `Home 与 End 分别到行首行尾`() {
        val state = TerminalKeyInput(text = "git status", cursor = 4)

        assertEquals(0, applyTerminalKey(TerminalKeyId.Home, state, emptyList()).cursor)
        assertEquals(
            "git status".length,
            applyTerminalKey(TerminalKeyId.End, state, emptyList()).cursor,
        )
    }

    @Test
    fun `左右移动一格且不越界`() {
        val head = TerminalKeyInput(text = "abc", cursor = 0)
        // 已在最左，再左移仍是 0（不能变成 -1）。
        assertEquals(0, applyTerminalKey(TerminalKeyId.Left, head, emptyList()).cursor)

        val tail = TerminalKeyInput(text = "abc", cursor = 3)
        // 已在最右，再右移仍是 3（不能越界）。
        assertEquals(3, applyTerminalKey(TerminalKeyId.Right, tail, emptyList()).cursor)
    }

    @Test
    fun `修饰符激活时方向键按词移动`() {
        // "git commit -m" 长度 13。从末尾按词左移应跳过 " -m" 到 "commit" 之后。
        val text = "git commit -m"
        val state = TerminalKeyInput(
            text = text,
            cursor = text.length,
            modifiers = TerminalModifiers(ctrl = true),
        )
        val result = applyTerminalKey(TerminalKeyId.Left, state, emptyList())

        // "git commit" 长度为 10，但光标停在 "commit" 之后的空格处 → 11。
        // （`-m` 是独立的一个词，跳过它要先退过 "-m" 再退过那个空格。）
        assertEquals(11, result.cursor)
        // 用一次即清：修饰符不该留到下一次按键。
        assertFalse(result.modifiers.any)
    }

    @Test
    fun `按词右移跳过空白再跳过一个词`() {
        val text = "git commit"
        // 光标停在 "git" 之后（位置 3，紧贴空格）。
        // 若实现不先跳空白，会原地不动（左边是字符、再左是空白）。
        val state = TerminalKeyInput(
            text = text,
            cursor = 3,
            modifiers = TerminalModifiers(alt = true),
        )
        val result = applyTerminalKey(TerminalKeyId.Right, state, emptyList())

        assertEquals(text.length, result.cursor)
    }

    @Test
    fun `按词左移在连续空白处只跳一次`() {
        val text = "a    b"
        val state = TerminalKeyInput(
            text = text,
            cursor = text.length,
            modifiers = TerminalModifiers(ctrl = true),
        )
        // 从末尾跳到 "b" 之前 = 5（"a    " 的长度）。
        assertEquals(5, applyTerminalKey(TerminalKeyId.Left, state, emptyList()).cursor)
    }

    // ── 修饰符是开关，不是一次性 ──────────────────────────────

    @Test
    fun `按 CTRL 打开再按一次关闭`() {
        val first = applyTerminalKey(TerminalKeyId.Ctrl, TerminalKeyInput(), emptyList())
        assertTrue(first.modifiers.ctrl)

        val second = applyTerminalKey(TerminalKeyId.Ctrl, first, emptyList())
        assertFalse(second.modifiers.ctrl)
    }

    @Test
    fun `CTRL 与 ALT 可以同时激活`() {
        val state = applyTerminalKey(
            TerminalKeyId.Alt,
            applyTerminalKey(TerminalKeyId.Ctrl, TerminalKeyInput(), emptyList()),
            emptyList(),
        )
        assertTrue(state.modifiers.ctrl)
        assertTrue(state.modifiers.alt)
    }

    // ── ESC 清空 ──────────────────────────────────────────────

    @Test
    fun `ESC 清空文本并复位所有编辑态`() {
        val state = TerminalKeyInput(
            text = "rm -rf /",
            cursor = 3,
            modifiers = TerminalModifiers(ctrl = true),
            historyIndex = 2,
            draft = "x",
        )
        val result = applyTerminalKey(TerminalKeyId.Escape, state, listOf("a", "b", "c"))

        assertEquals("", result.text)
        assertEquals(0, result.cursor)
        assertFalse(result.modifiers.any)
        assertEquals(-1, result.historyIndex)
    }

    // ── 历史：这一段最容易错 ──────────────────────────────────

    @Test
    fun `历史为空时方向键不改变状态`() {
        val state = TerminalKeyInput(text = "abc", cursor = 1)
        val up = applyTerminalKey(TerminalKeyId.HistoryUp, state, emptyList())
        val down = applyTerminalKey(TerminalKeyId.HistoryDown, state, emptyList())

        assertEquals("abc", up.text)
        assertEquals("abc", down.text)
    }

    @Test
    fun `向上翻到最新一条并把光标放到行尾`() {
        val history = listOf("ls", "pwd")
        val result = applyTerminalKey(TerminalKeyId.HistoryUp, TerminalKeyInput(), history)

        assertEquals("pwd", result.text)
        // 光标必须在末尾：翻出历史后用户通常要改一改就回车，
        // 光标停在中段会让人按半天右箭头。
        assertEquals(3, result.cursor)
        assertEquals(1, result.historyIndex)
    }

    @Test
    fun `向上翻到最旧后不再越界`() {
        val history = listOf("ls", "pwd")
        // 翻两次到最旧。
        var state = applyTerminalKey(TerminalKeyId.HistoryUp, TerminalKeyInput(), history)
        state = applyTerminalKey(TerminalKeyId.HistoryUp, state, history)
        assertEquals("ls", state.text)

        // 再翻一次仍是 "ls"（不能变成 null 崩溃或跳到别处）。
        state = applyTerminalKey(TerminalKeyId.HistoryUp, state, history)
        assertEquals("ls", state.text)
        assertEquals(0, state.historyIndex)
    }

    @Test
    fun `没在浏览历史时按向下不动`() {
        // 这是最容易被写错的一条：若写成 -1 + 1 = 0，
        // 用户按 ↓ 会突然跳到**最旧**那条，方向反了且毫无预兆。
        val history = listOf("stale-old", "newer")
        val result = applyTerminalKey(TerminalKeyId.HistoryDown, TerminalKeyInput(), history)

        assertEquals("", result.text)
        assertEquals(-1, result.historyIndex)
    }

    @Test
    fun `翻历史再回来时还原用户自己写的内容`() {
        val history = listOf("ls", "pwd")
        val draft = "echo hel"

        // 写了半句后向上翻。
        var state = applyTerminalKey(
            TerminalKeyId.HistoryUp,
            TerminalKeyInput(text = draft, cursor = draft.length),
            history,
        )
        assertEquals("pwd", state.text)

        // 再向下翻过末尾：应还原半句，而不是清空。
        state = applyTerminalKey(TerminalKeyId.HistoryDown, state, history)

        assertEquals(draft, state.text)
        assertEquals(draft.length, state.cursor)
        assertEquals(-1, state.historyIndex)
    }

    @Test
    fun `PGUP 一次跳五条`() {
        val history = (1..10).map { "c$it" }
        val result = applyTerminalKey(TerminalKeyId.HistoryPageUp, TerminalKeyInput(), history)

        // 起点是 size=10（末尾之后一格），-5 → 5 → "c6"。
        assertEquals("c6", result.text)
        assertEquals(5, result.historyIndex)
    }

    @Test
    fun `PGUP 到顶时夹到最旧一条`() {
        val history = (1..3).map { "c$it" }
        val result = applyTerminalKey(TerminalKeyId.HistoryPageUp, TerminalKeyInput(), history)

        assertEquals("c1", result.text)
        assertEquals(0, result.historyIndex)
    }

    @Test
    fun `PGDN 一次跳五条`() {
        val history = (1..10).map { "c$it" }
        // 两次 PGUP 到最旧（索引 0），再 PGDN +5 → 索引 5 → "c6"。
        var state = applyTerminalKey(TerminalKeyId.HistoryPageUp, TerminalKeyInput(), history)
        state = applyTerminalKey(TerminalKeyId.HistoryPageUp, state, history)
        assertEquals("c1", state.text)
        assertEquals(0, state.historyIndex)

        state = applyTerminalKey(TerminalKeyId.HistoryPageDown, state, history)
        assertEquals("c6", state.text)
        assertEquals(5, state.historyIndex)
    }

    // ── 布局约束 ──────────────────────────────────────────────

    @Test
    fun `默认布局是两行七列`() {
        assertEquals(2, DefaultTerminalKeyLayout.rows.size)
        DefaultTerminalKeyLayout.rows.forEach { row ->
            assertEquals(7, row.size)
        }
    }

    @Test
    fun `默认布局每个键身份唯一`() {
        // 重复身份会让 `when` 分支只有第一个生效（第二个永远点不到）。
        // 这在手工写 14 个枚举值的时候是真会发生的笔误。
        val ids = DefaultTerminalKeyLayout.rows.flatten().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `行数不对时构造失败`() {
        // 布局是编译期常量，写错应该立刻炸，而不是显示成奇怪的键盘。
        TerminalKeyLayout(rows = listOf(listOf(TerminalKey(TerminalKeyId.Dash, "-"))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `列数不对时构造失败`() {
        TerminalKeyLayout(
            rows = listOf(
                List(7) { TerminalKey(TerminalKeyId.Dash, "-") },
                List(6) { TerminalKey(TerminalKeyId.Dash, "-") },
            ),
        )
    }
}