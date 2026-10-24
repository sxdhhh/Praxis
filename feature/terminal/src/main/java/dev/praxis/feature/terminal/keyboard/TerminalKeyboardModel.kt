package dev.praxis.feature.terminal.keyboard

/**
 * 虚拟键盘上的一个**物理按键身份**。
 *
 * ## 为什么用枚举而不是直接用标签字符串
 *
 * 键帽标签是可以被用户改的（Operit 的 `VirtualKeyboardCustomizationDialog` 就是干这个），
 * 而**行为**不能跟着标签走 —— 用户把「PGUP」改成「上一页」，它仍然应当翻历史。
 *
 * 所以拆成两层：
 *  - [TerminalKeyId] 是行为身份（渲染层按它分支，`when` 穷举保证不漏）；
 *  - [TerminalKey.label] 是可改的显示文本。
 *
 * ## 为什么没有字母键
 *
 * 对齐 Operit：它的 2×7 布局里**没有任何字母**，字母靠系统输入法打。
 * 键盘只补「输入法打不出来、但命令行编辑必须有的键」（方向、跳转、历史、修饰符）。
 * 加一排字母会让人误以为可以脱离系统键盘使用，实际中文/符号都打不出来。
 */
enum class TerminalKeyId {
    /** 清空当前输入。 */
    Escape,

    /** 字面 `/`（路径输入高频）。 */
    Slash,

    /** 字面 `-`（选项前缀高频）。 */
    Dash,

    /** 光标移到行首。 */
    Home,

    /** 历史里更早的一条。 */
    HistoryUp,

    /** 光标移到行尾。 */
    End,

    /** 历史里更早的 5 条。 */
    HistoryPageUp,

    /** 插入两个空格（**不做补全**，见 [DefaultTerminalKeyLayout] 的说明）。 */
    Tab,

    /** 修饰符开关（激活后方向键按词移动）。 */
    Ctrl,

    /** 修饰符开关（与 [Ctrl] 当前同效，PTY 接入后才分化）。 */
    Alt,

    /** 光标左移（修饰激活时按词）。 */
    Left,

    /** 历史里更新的一条。 */
    HistoryDown,

    /** 光标右移（修饰激活时按词）。 */
    Right,

    /** 历史里更新的 5 条。 */
    HistoryPageDown,
}

/**
 * 一个键：行为身份 + 显示标签。
 *
 * @param id 行为身份（见 [TerminalKeyId]）。
 * @param label 键帽上显示的文字。改它不影响行为。
 */
data class TerminalKey(
    val id: TerminalKeyId,
    val label: String,
)

/**
 * 键盘布局：**固定 2 行 × 7 列**。
 *
 * ## 为什么行数列数是硬约束而不是任意形状
 *
 * 对齐 Operit（`VirtualKeyboardLayoutConfig` 里 `ROW_COUNT = 2` / `COLUMN_COUNT = 7`
 * 且构造函数 `require` 校验）。两条理由：
 *
 *  1. **拇指可达性**：2 行是单手拇指不用抬腕的极限；7 列在 360dp 宽屏上
 *     每键约 47dp，正好是「不会误触、又能塞下 PGUP 四个字母」的临界值；
 *  2. **等宽**：`Row` 里每键 `weight(1f)`，等宽前提是每行列数相同。
 *     允许行间列数不同就会出现「短行的键特别宽」，视觉上散架。
 *
 * 用 `require` 而不是静默修正：布局是**编译期常量**，写错了是开发者的错，
 * 应该立刻炸在启动时，而不是慢慢显示成一个奇怪的键盘。
 */
data class TerminalKeyLayout(
    val rows: List<List<TerminalKey>>,
) {
    init {
        require(rows.size == ROW_COUNT) {
            "虚拟键盘必须是 $ROW_COUNT 行，实际 ${rows.size} 行"
        }
        rows.forEachIndexed { index, row ->
            require(row.size == COLUMN_COUNT) {
                "第 ${index + 1} 行必须是 $COLUMN_COUNT 个键，实际 ${row.size} 个"
            }
        }
    }

    companion object {
        const val ROW_COUNT = 2
        const val COLUMN_COUNT = 7
    }
}

/**
 * 默认布局：**键位、标签、行列全部照抄 Operit**。
 *
 * ```
 * ESC    /      -     HOME   ↑     END   PGUP
 * TAB    CTRL   ALT   ←      ↓     →     PGDN
 * ```
 *
 * 来源：Operit `VirtualKeyboardConfigManager.defaultLayout()`。
 *
 * ## 为什么键位不改（哪怕有几个键在 Praxis 当前架构下用不上）
 *
 * 「1:1 对齐 Operit」是用户明确要求。而且这排键的**位置**是有逻辑的，
 * 换位置比换功能代价大得多：
 *
 *  - 左三格（ESC / TAB + 修饰符）是「控制区」——`ESC` 在最左上是肌肉记忆，
 *    `TAB` 在其下同列；`CTRL` / `ALT` 紧邻 `TAB` 右侧，是「按完修饰键后
 *    手指就停在旁边方向键上」的距离；
 *  - 右四格是「导航区」：`HOME`/`END` 与 `PGUP`/`PGDN` 夹住上下方向键，
 *    形成「上排 ↑ 在下排 ↓ 的正上方、HOME/END 在它两侧」的对称。
 *
 * ## 那几个键在 Praxis 里做什么（不是假按钮）
 *
 * | 键 | 当前行为 |
 * |---|---|
 * | `ESC` | 清空当前输入 |
 * | `/` `-` | 插入该字符 |
 * | `HOME` `END` | 光标到行首 / 行尾 |
 * | `←` `→` | 左/右移 1 字符；修饰激活时按词移动 |
 * | `↑` `↓` | 上 / 下一条历史 |
 * | `PGUP` `PGDN` | 历史前 / 后翻 5 条 |
 * | `TAB` | 插入两个空格 |
 * | `CTRL` `ALT` | 修饰符开关（激活态变色） |
 *
 * ## `TAB` 为什么只是插空格而不是补全
 *
 * 补全需要一个「能枚举当前 shell 文件系统」的数据源。Praxis 的终端是
 * **一次性进程模型**（`runner.run(command)` 跑完即结束，见 `TerminalViewModel` 的 KDoc），
 * 没有常驻 shell 会话可以问「当前目录有什么」。硬做一个基于 App 侧文件列表的
 * 假补全，会在容器环境里补出完全错误的路径 —— 那比不补更糟。
 *
 * 所以 `TAB` 老实插两个空格，等接入持久会话后再升级为真补全。
 */
val DefaultTerminalKeyLayout: TerminalKeyLayout = TerminalKeyLayout(
    rows = listOf(
        listOf(
            TerminalKey(TerminalKeyId.Escape, "ESC"),
            TerminalKey(TerminalKeyId.Slash, "/"),
            TerminalKey(TerminalKeyId.Dash, "-"),
            TerminalKey(TerminalKeyId.Home, "HOME"),
            TerminalKey(TerminalKeyId.HistoryUp, "↑"),
            TerminalKey(TerminalKeyId.End, "END"),
            TerminalKey(TerminalKeyId.HistoryPageUp, "PGUP"),
        ),
        listOf(
            TerminalKey(TerminalKeyId.Tab, "TAB"),
            TerminalKey(TerminalKeyId.Ctrl, "CTRL"),
            TerminalKey(TerminalKeyId.Alt, "ALT"),
            TerminalKey(TerminalKeyId.Left, "←"),
            TerminalKey(TerminalKeyId.HistoryDown, "↓"),
            TerminalKey(TerminalKeyId.Right, "→"),
            TerminalKey(TerminalKeyId.HistoryPageDown, "PGDN"),
        ),
    ),
)

/**
 * 用自定义标签构建布局，**行为身份仍按位置取自默认布局**。
 *
 * ## 为什么必须是「按位置」
 *
 * 这是本项目「改标签不改变行为」的实现点：
 *
 * ```
 * 默认：[ESC][/][-][HOME][↑][END][PGUP]
 * 用户改成：[退出][/][-][行首][上][行尾][上翻]
 * 行为：  Escape   Slash Dash Home  Up  End  PageUp   ← 完全不变
 * ```
 *
 * 若按标签去查身份（拿"上翻"去找 `TerminalKeyId`），用户一改就找不到、
 * 或者更糟 —— 两个格子写了同一个标签，第二个键的行为变成第一个。
 *
 * 所以身份只由**下标**决定：第 i 格永远是 `DEFAULT_FLAT[i].id`。
 *
 * ## 为什么不直接接受 `List<TerminalKey>`
 *
 * 因为调用方（设置页）手里只有标签字符串（DataStore 里存的就是标签，
 * 见 `VirtualKeyboardLayoutCodec` 的 KDoc）。让它去引用
 * `TerminalKeyId` 会把持久化模型和 UI 枚举绑在一起 ——
 * 那正是分层反转的另一种形式。
 *
 * @param labels 拍平的 14 个标签。数量不符时返回 [DefaultTerminalKeyLayout]
 *   （与 `VirtualKeyboardLayoutCodec.sanitize` 的兜底策略一致）。
 */
fun terminalKeyLayoutOf(labels: List<String>): TerminalKeyLayout {
    val defaultFlat = DefaultTerminalKeyLayout.rows.flatten()
    if (labels.size != defaultFlat.size) return DefaultTerminalKeyLayout

    val keys = defaultFlat.mapIndexed { index, defaultKey ->
        // 标签为空时回退默认：一个不可见的键帽等于功能丢失。
        val label = labels[index].ifBlank { defaultKey.label }
        TerminalKey(id = defaultKey.id, label = label)
    }

    return TerminalKeyLayout(
        rows = listOf(
            keys.take(TerminalKeyLayout.COLUMN_COUNT),
            keys.drop(TerminalKeyLayout.COLUMN_COUNT),
        ),
    )
}
