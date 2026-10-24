package dev.praxis.core.model.settings

/**
 * 终端页自己的设置（第 12 项缺陷：终端里的「齿轮」原本跳到 Linux 环境页）。
 *
 * ## 为什么需要单独一份，而不是复用 Linux 环境页
 *
 * 这两个页面回答的是完全不同的问题：
 *  - **Linux 环境**：容器装没装、镜像用哪个、SSH / FRP 怎么连（宿主侧能力）；
 *  - **终端设置**：字多大、往上能翻多少行、首屏要不要那个字符画（纯观感）。
 *
 * 第 12 项报的就是这两件事被接到了同一个界面：「环境配置」胶囊与右边的齿轮
 * 都跳 Linux 环境页，于是齿轮看起来是个假按钮 —— 点哪里都是同一页。
 *
 * ## 为什么字号用 Float 的 sp 而不是 Int
 *
 * miuix `SliderPreference` 只收 `Float`。存成 Float 后：
 *  - 滑杆是连续手感（配合 `steps` 变成 1sp 一档，既连续又不出现 13.37sp）；
 *  - 不需要在 UI 层做 Float↔Int 来回转换（那种转换最容易在某一边漏掉取整）。
 *
 * 读盘时统一 [coerceIn] 一次，把「手改 DataStore / 降级安装」写进来的越界值拉回合法域，
 * 而不是让终端渲染层拿到 0sp（0sp 会让整屏文字不可见，且没有任何报错）。
 */
data class TerminalSettings(
    /**
     * 终端字号（sp）。
     *
     * 默认 13：13sp 等宽在主流手机上一行约 44 字符，与 80 列缓冲配合时
     * 长命令不至于频繁折行。
     */
    val fontSizeSp: Float = DEFAULT_FONT_SIZE_SP,

    /**
     * 回滚缓冲行数上限。
     *
     * 默认 1000：超出后最旧的行被丢弃。这个值直接决定内存占用
     * （每行 = 列数 × Cell，80 列的 1000 行约数 MB 量级），
     * 所以给上限而不是「无限」。
     */
    val scrollbackLines: Int = DEFAULT_SCROLLBACK_LINES,

    /**
     * 首屏是否写字符画 banner。
     *
     * 默认 true。关掉的人通常是想看到更干净的输出（或屏幕上放不下），
     * 这不影响任何功能 —— banner 只是往缓冲里喂几行文本。
     */
    val showBanner: Boolean = true,
) {
    /** 越界值拉回合法域（读盘后必调一次，见 [dev.praxis.core.data.settings.SettingsRepository]）。 */
    fun sanitized(): TerminalSettings = TerminalSettings(
        fontSizeSp = fontSizeSp.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP),
        scrollbackLines = scrollbackLines.coerceIn(MIN_SCROLLBACK_LINES, MAX_SCROLLBACK_LINES),
        showBanner = showBanner,
    )

    companion object {
        const val DEFAULT_FONT_SIZE_SP: Float = 13f

        /**
         * 字号下限 9sp：再小则等宽字体在 3x 屏上笔画粘连，看不清；
         * 上限 20sp：再大则一行放不下 `ls -l` 这种典型输出，反而更难读。
         */
        const val MIN_FONT_SIZE_SP: Float = 9f
        const val MAX_FONT_SIZE_SP: Float = 20f

        const val DEFAULT_SCROLLBACK_LINES: Int = 1000

        /** 下限 100 行：再少则 `dmesg` 这类输出一屏就滚没了。 */
        const val MIN_SCROLLBACK_LINES: Int = 100
        const val MAX_SCROLLBACK_LINES: Int = 5000
    }
}
