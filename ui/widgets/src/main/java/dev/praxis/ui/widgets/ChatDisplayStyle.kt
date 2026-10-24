package dev.praxis.ui.widgets

/**
 * 对话列表的显示风格。
 *
 * ## 来源
 *
 * 用户 2026-10-22：「用 operit 的那种对话显示我觉得会好一点」。
 *
 * 取证 Operit 后确认：它的 `ChatStyle` 枚举有 **CURSOR / BUBBLE** 两种，
 * 且 `AIChatScreen.kt:195-199` 的映射是
 *
 * ```kotlin
 * when (chatStyleSetting) {
 *     CHAT_STYLE_BUBBLE -> ChatStyle.BUBBLE
 *     else -> ChatStyle.CURSOR          // ← 默认走这里
 * }
 * ```
 *
 * 也就是说 **Operit 的默认是 CURSOR，BUBBLE 要用户手动去设置里开**。
 * 用户说「那种对话显示」，指的就是默认那一套。
 *
 * ## 两种形态的差异（不是配色差异）
 *
 * | | [Bubble] | [Cursor] |
 * |---|---|---|
 * | 用户消息 | 右对齐、`primary` 底、18dp 圆角、最大 300dp | **占满整行**、`surfaceContainerHigh` 底、8dp 圆角、左上角「提问」标签 |
 * | 助手消息 | 左对齐、`surfaceContainerHigh` 底、18dp 圆角、最大 360dp | **无底色**、无圆角容器、直接铺在页面上、左上角「回复」标签 |
 * | 观感 | 聊天软件（微信 / iMessage） | IDE 对话面板（Cursor / Copilot Chat） |
 *
 * 关键差别是**助手消息有没有「容器」**。Cursor 风格把助手正文直接铺在页面上，
 * 所以长回复和大代码块不必在灰底圆角块里反复折行，代码块自己的背景
 * （Markdown 渲染出来的）也不会与气泡底色叠成「灰块套深灰块」——
 * 这是它读长回复更舒服的实际原因。
 *
 * ## 为什么落在 `ui:widgets` 而不是 `feature:chat`
 *
 * 设置页（`feature:settings` 的外观页）也要引用这个枚举来渲染选择器，
 * 而 `feature:chat` 与 `feature:settings` 之间**没有也不该有**横向依赖。
 * 两者都依赖 `ui:widgets`，所以放这里，谁都能用且不产生 feature 间耦合。
 */
enum class ChatDisplayStyle {
    /** 传统气泡：用户蓝底右对齐，助手灰底左对齐。 */
    Bubble,

    /**
     * Operit 默认风格：用户全宽卡片 +「提问」标签，助手无容器 +「回复」标签。
     *
     * 这是本项目的默认值（与 Operit 的默认保持一致）。
     */
    Cursor;

    companion object {
        /**
         * 从持久化字符串还原。
         *
         * 未知值一律回落 [Cursor] —— 与 Operit 的 `else -> CURSOR` 一致。
         * **不要**回落 [Bubble]：那会让「存了个坏值」表现为「样式自己变回旧样子」，
         * 比直接保留默认更难查。
         */
        fun fromStorage(raw: String?): ChatDisplayStyle = when (raw) {
            "bubble" -> Bubble
            else -> Cursor
        }
    }

    /**
     * 存盘用的字符串（与 [fromStorage] 对称）。
     *
     * 写成**成员函数**而不是 `companion` 里的接收者扩展：后者在
     * `ChatDisplayStyle.Bubble.toStorage()` 这种写法下要靠隐式导入才可见，
     * 换一个文件调用就会「找不到符号」，而错误信息不会指向这里。
     */
    fun toStorage(): String = when (this) {
        Bubble -> "bubble"
        Cursor -> "cursor"
    }
}