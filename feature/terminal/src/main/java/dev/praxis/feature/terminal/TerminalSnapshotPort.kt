package dev.praxis.feature.terminal

/**
 * 终端会话状态持久化端口（**进程重启后恢复**）。
 *
 * ## 为什么需要它（2026-10-24）
 *
 * 用户报「重启软件后会话内容全部显示不正常」。聊天页那一侧有 Room 落盘，
 * 而终端页**完全没有持久化**：`TerminalViewModel` 的会话、缓冲、命令历史
 * 全在内存里，杀进程就归零。表现是「昨天跑的 `apt install` 记录没了」，
 * 而 Operit 的终端会保留 scrollback。
 *
 * ## 为什么端口在 `feature:terminal`、实现在 `:app`
 *
 * 与 `CommandRunner`、`ChatPersistence` 同一套纪律：feature 模块不能碰
 * `Context` / DataStore / 文件系统，否则单测要起 Robolectric。
 *
 * ## 为什么存「文本快照」而不是 `TerminalBuffer` 的单元格
 *
 * 一个 200×80 的屏幕上万格，每格还要带 `CellStyle`（颜色/粗体/斜体…）。
 * 序列化它会得到一个几百 KB 的 JSON，且**恢复价值很低**：用户真正在意的是
 * 「看到过什么输出」，不是「第 37 列第 12 格是 `#C83232` 的粗体」。
 *
 * 所以快照只保留**文本行**（含 ANSI 序列吗？不带 —— 恢复时按默认样式重放，
 * 颜色丢失，但内容完整）。这是有意的取舍：换来的是几 KB 的存储，
 * 且恢复逻辑只是 `feed(text)`，不需要任何逆向构造。
 *
 * ## 为什么只存活动会话的屏幕 + 每个会话的历史
 *
 * 多会话的 scrollback 全存会让快照线性增长（每个会话 200 行 × N 个会话）。
 * 现在存：每个会话的**可见屏幕**（`rows` 行）+ 命令历史 + 标题。
 * 够用，且总量可控（4 个会话 × 200 行 × 80 字符 ≈ 64KB 上限）。
 */
interface TerminalSnapshotPort {

    /**
     * 保存快照。实现方负责决定存哪里（DataStore / 文件 / 数据库）。
     *
     * 不做成 `suspend` 也可以，但保留 `suspend` 是为了给实现方自由
     * （DataStore 读写本身就是 suspend）。
     */
    suspend fun save(snapshot: TerminalSnapshot)

    /**
     * 读回快照。返回 null 表示没有存档（首次启动）。
     *
     * ## 为什么读失败要返回 null 而不是抛异常
     *
     * 恢复失败不该阻止用户用终端 —— 大不了从空会话开始。
     * 抛异常会让 `init` 崩掉整个页面。
     */
    suspend fun load(): TerminalSnapshot?

    /** 清空存档（用户点「重置终端」时用）。 */
    suspend fun clear()
}

/**
 * 一份终端快照。
 *
 * @param tabs 各会话的存档，按顺序即标签顺序。
 * @param activeIndex 上次活动的标签下标；越界时恢复方会夹到合法范围。
 */
data class TerminalSnapshot(
    val tabs: List<TabSnapshot> = emptyList(),
    val activeIndex: Int = 0,
)

/**
 * 单个会话的存档。
 *
 * @param title 标签名（`Local` / `Local 2` …）。用户没改名的能力，但保留字段
 *   是为了将来支持重命名时不改结构。
 * @param screenLines 可见屏幕的行文本（已去掉行尾空白，不含 ANSI）。
 * @param history 命令历史（旧的在前）。
 * @param input 上次未提交的输入框内容 —— 用户敲了一半被杀进程，回来还在。
 */
data class TabSnapshot(
    val title: String,
    val screenLines: List<String> = emptyList(),
    val history: List<String> = emptyList(),
    val input: String = "",
)
