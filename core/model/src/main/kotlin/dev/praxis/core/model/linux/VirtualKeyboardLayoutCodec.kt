package dev.praxis.core.model.linux

/**
 * 虚拟键盘布局的**可持久化形式**。
 *
 * ## 为什么不在 `core:model` 里直接用 `TerminalKeyLayout`
 *
 * `TerminalKeyLayout` / `TerminalKeyId` 住在 `feature:terminal`。
 * 而本模型要放进 DataStore，DataStore 的实现（`SettingsRepository`）在
 * `core:data`，`core:data` 依赖 `core:model`。
 *
 * 若把 `TerminalKeyLayout` 放进来（或反过来让 `core:model` 依赖
 * `feature:terminal`），依赖方向就变成 `core → feature` —— 那是分层反转，
 * 会让 `core:data` 间接依赖整个终端 UI 模块（含 Compose）。
 *
 * 所以持久化用**中性的字符串标签**：本项目里键盘布局本来就是
 * 「2 行 × 7 列 + 每格一个标签」，存成 14 个字符串完全够用，
 * 而 `feature:terminal` 负责把标签映射回 `TerminalKeyId`（它知道自己
 * 每个键的语义）。
 *
 * ## 为什么存标签而不是存枚举名
 *
 * 枚举名（`HistoryUp`）是**行为标识**，用户改的是**显示文本**，两者不是一回事：
 *  - 用户把 `PGUP` 改成「上翻」，语义仍是「历史前 5 条」；
 *  - 若存枚举名，用户改的标签没地方放，编辑器就成了假功能。
 *
 * 存标签后：14 个字符串 = 用户看到的 14 个键帽文字。
 * 行为由**位置**决定（第 i 格永远是那个语义），与标签无关 ——
 * 这正是「改标签不改变行为」的实现方式。
 *
 * ## 校验为什么这么严
 *
 * DataStore 里的值可能来自手改、降级安装、或将来改了列数。
 * 一个 13 个格子的布局会让 `weight(1f)` 那一行对不齐，
 * 而原因是「少了一个键」这种很难看出来的东西。所以读盘后必须
 * 完整校验，不合格就整体回退默认布局，而不是尽力修复
 * （半修好的布局比默认布局更让人困惑）。
 */
object VirtualKeyboardLayoutCodec {

    /** 行数（与 `TerminalKeyLayout.ROW_COUNT` 必须一致）。 */
    const val ROW_COUNT = 2

    /** 列数（与 `TerminalKeyLayout.COLUMN_COUNT` 必须一致）。 */
    const val COLUMN_COUNT = 7

    /** 单格标签的最大长度。超过则截断 —— 键帽只有 47dp 宽，长文本本来也显示不全。 */
    const val MAX_LABEL_LENGTH = 8

    /**
     * 默认标签（2×7，与 `DefaultTerminalKeyLayout` 逐字相同）。
     *
     * ## 为什么要在这里再写一份而不是引用
     *
     * 引用会形成 `core:model → feature:terminal` 的反向依赖（见文件头）。
     * 两份字面量重复是可以接受的代价：它们是**数据**，不是逻辑；
     * 而且两边都有单测会检查「默认布局是 2×7」，
     * 真正的漂移（一边改了另一边没改）会在 UI 上立刻看出来。
     */
    val DEFAULT_LABELS: List<String> = listOf(
        "ESC", "/", "-", "HOME", "↑", "END", "PGUP",
        "TAB", "CTRL", "ALT", "←", "↓", "→", "PGDN",
    )

    /**
     * 把拍平的标签列表规范化。
     *
     * ## 做四件事
     *
     *  1. **数量不对 → 整体回退默认**（理由见文件头）；
     *  2. **空格子 → 回退该格的默认值**。用户把标签清空不该得到一个不可见的键；
     *  3. **超长 → 截断**到 [MAX_LABEL_LENGTH]，避免把键帽撑变形；
     *  4. **换行/制表符 → 空格**。多行标签会让键帽高度变化，
     *     进而把整排按键顶歪（`Row` 高度取子项最大值）。
     *
     * @param raw 任意长度的标签列表（可能来自 DataStore 的脏数据）。
     */
    fun sanitize(raw: List<String>): List<String> {
        val expected = ROW_COUNT * COLUMN_COUNT
        if (raw.size != expected) return DEFAULT_LABELS
        return raw.mapIndexed { index, label ->
            val cleaned = label
                // 换行/制表/全角空格都换普通空格（见第 4 条）。
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .trim()
            when {
                cleaned.isEmpty() -> DEFAULT_LABELS[index]
                cleaned.length > MAX_LABEL_LENGTH -> cleaned.take(MAX_LABEL_LENGTH)
                else -> cleaned
            }
        }
    }

    /** 是否为默认布局（用于设置页显示「已自定义」状态）。 */
    fun isDefault(labels: List<String>): Boolean = labels == DEFAULT_LABELS
}