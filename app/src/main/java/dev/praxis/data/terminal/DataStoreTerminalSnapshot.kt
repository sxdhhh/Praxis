package dev.praxis.data.terminal

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.praxis.feature.terminal.TabSnapshot
import dev.praxis.feature.terminal.TerminalSnapshot
import dev.praxis.feature.terminal.TerminalSnapshotPort
import kotlinx.coroutines.flow.first

/**
 * [TerminalSnapshotPort] 的 DataStore 实现。
 *
 * ## 为什么不用 JSON
 *
 * | 方案 | 代价 |
 * |---|---|
 * | kotlinx.serialization（`@Serializable`） | 要给 `TabSnapshot` 加注解 → `feature:terminal` 得依赖 serialization 运行时 |
 * | 手写 JSON 拼装 | 要正确转义引号 / 反斜杠 / 换行 / 控制字符，终端输出里全都有 → 极易写出解析崩溃 |
 * | **逐行 + 前缀标记**（本实现） | 无依赖、无转义，每行自带类型标记，错位能立刻发现 |
 *
 * ## 为什么「长度前缀」的想法被放弃了
 *
 * 最初想用紧凑的「`<长度>\t<内容>`」格式：读时按长度切片，
 * 内容里出现制表符、中文、ANSI 残留都不破坏结构。
 *
 * 真正动手时发现问题在**解码端游标管理**：`i += 2` 会散落在嵌套
 * `buildList` 的内联 lambda 里，求值时机夹在「读 count」和「读内容」之间，
 * 任何一次顺序调整都会读错位 —— 而错位后**不会报错**，只会读出空串，
 * 表现为「恢复出来一片空白」这种最难查的症状。
 *
 * 改用「每条占一行 + 每行首字符是类型标记」，游标收敛进 [Cursor]，
 * 且每行都校验前缀，读错立刻抛异常（[load] 把异常吞成「没有存档」）。
 * 代价是存档每行多 1 字节标记，换来可验证性。
 *
 * ## 格式（版本 1）
 *
 * ```
 * v1
 * T<tabCount>
 * L<title>              ← 每个 tab 从这里开始
 * S<screenLineCount>
 * <line>                ← 屏幕行，一条一行（内容里的换行已剔除）
 * H<historyCount>
 * <command>             ← 命令历史，一条一行
 * I<input>              ← 未提交的输入框内容（单行）
 * A<activeIndex>        ← 全部 tab 之后
 * ```
 *
 * ## 为什么要带 `v1`
 *
 * 格式将来会变（比如要存颜色）。没有版本号时，旧存档被新代码读到会解析成垃圾。
 * 有版本号就能判成「不认识 → 当没存档」，用户从干净终端开始而不是看到乱码。
 */
class DataStoreTerminalSnapshot(
    private val dataStore: DataStore<Preferences>,
) : TerminalSnapshotPort {

    override suspend fun save(snapshot: TerminalSnapshot) {
        val text = encode(snapshot)
        dataStore.edit { prefs -> prefs[KEY] = text }
    }

    override suspend fun load(): TerminalSnapshot? {
        val text = runCatching { dataStore.data.first()[KEY] }.getOrNull() ?: return null
        if (text.isBlank()) return null
        // 解析失败一律当「没有存档」：宁可给用户一个干净终端，
        // 也不要因为他上次的存档是旧格式而让终端页崩掉。
        return runCatching { decode(text) }.getOrNull()
    }

    override suspend fun clear() {
        dataStore.edit { prefs -> prefs.remove(KEY) }
    }

    // ── 编码 ──────────────────────────────────────────────

    private fun encode(snapshot: TerminalSnapshot): String = buildString {
        appendLine(HEADER)
        appendLine("T" + snapshot.tabs.size)
        snapshot.tabs.forEach { tab ->
            appendLine("L" + flatten(tab.title))
            appendLine("S" + tab.screenLines.size)
            tab.screenLines.forEach { appendLine(flatten(it)) }
            appendLine("H" + tab.history.size)
            tab.history.forEach { appendLine(flatten(it)) }
            appendLine("I" + flatten(tab.input))
        }
        appendLine("A" + snapshot.activeIndex)
    }

    /**
     * 把可能含换行的内容压成单行。
     *
     * 历史命令可能被粘贴进多行（`for` 循环、heredoc）。丢掉换行会让命令变成
     * 不合法的一行；换成空格至少形态还在。屏幕行本来就是单行，这里是恒等变换。
     */
    private fun flatten(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ')

    // ── 解码 ──────────────────────────────────────────────

    /**
     * 读指针。
     *
     * ## 为什么单独一个类
     *
     * 上一版把游标写成局部 `var i` 并在嵌套 `buildList` 的内联 lambda 里自增。
     * `buildList` 是 `inline` 的，lambda 里的 `i += 1` 会直接改外层变量，
     * 但**执行时机**夹在「读 count」与「读内容」之间 —— 顺序一调整就读错位，
     * 且不报错。把推进收敛到 `next()` 一处，配合下面的前缀校验，
     * 任何错位都会立刻抛 `IllegalArgumentException`。
     */
    private class Cursor(private val lines: List<String>) {
        private var index = 0
        fun next(): String = lines.getOrElse(index++) { "" }
    }

    private fun decode(text: String): TerminalSnapshot {
        val cursor = Cursor(text.split('\n'))
        require(cursor.next() == HEADER) { "unknown snapshot version" }

        val tabCountLine = cursor.next()
        require(tabCountLine.startsWith("T")) { "expected T, got '$tabCountLine'" }
        val tabCount = tabCountLine.drop(1).trim().toIntOrNull() ?: 0

        val tabs = ArrayList<TabSnapshot>(tabCount)
        repeat(tabCount) {
            val titleLine = cursor.next()
            require(titleLine.startsWith("L")) { "expected L, got '$titleLine'" }
            val title = titleLine.drop(1)

            val screenCountLine = cursor.next()
            require(screenCountLine.startsWith("S")) { "expected S, got '$screenCountLine'" }
            val screenCount = screenCountLine.drop(1).trim().toIntOrNull() ?: 0
            val screen = ArrayList<String>(screenCount)
            repeat(screenCount) { screen += cursor.next() }

            val historyCountLine = cursor.next()
            require(historyCountLine.startsWith("H")) { "expected H, got '$historyCountLine'" }
            val historyCount = historyCountLine.drop(1).trim().toIntOrNull() ?: 0
            val history = ArrayList<String>(historyCount)
            repeat(historyCount) { history += cursor.next() }

            val inputLine = cursor.next()
            require(inputLine.startsWith("I")) { "expected I, got '$inputLine'" }
            val input = inputLine.drop(1)

            tabs += TabSnapshot(
                title = title,
                screenLines = screen,
                history = history,
                input = input,
            )
        }

        val activeLine = cursor.next()
        require(activeLine.startsWith("A")) { "expected A, got '$activeLine'" }
        val activeIndex = activeLine.drop(1).trim().toIntOrNull() ?: 0
        return TerminalSnapshot(tabs = tabs, activeIndex = activeIndex)
    }

    private companion object {
        /** 格式版本标记。改格式时必须同步改它，旧存档会被当「不认识」跳过。 */
        const val HEADER = "v1"

        val KEY = stringPreferencesKey("terminal_snapshot_v1")
    }
}