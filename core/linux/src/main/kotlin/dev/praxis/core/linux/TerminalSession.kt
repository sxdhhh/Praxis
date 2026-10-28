package dev.praxis.core.linux

/**
 * 终端会话句柄与状态模型。
 *
 * ## 来源
 *
 * 照 Operit `terminal/src/main/java/.../data/TerminalModels.kt` 与
 * `TerminalManager` 的会话语义重写。用户要求「终端功能 1:1，UI 用我们自己的」，
 * 所以这里只搬**语义**（会话有 id、有名字、有当前目录、有交互模式），
 * 不搬它的 AIDL / Service / Compose 那套。
 *
 * ## 为什么这几个字段是必需的
 *
 * 一条终端会话不是「一条命令」——它是**一个有状态的东西**：
 *
 * | 字段 | 缺了会怎样 |
 * |---|---|
 * | [id] | 模型无法区分「在哪个会话里跑」，多会话直接退化成单会话 |
 * | [name] | 用户看到一排 `sess_1` / `sess_2`，分不清哪个在干嘛 |
 * | [currentDirectory] | 每次都得先 `cd`，且 `ls` 结果让人不知道自己在哪 |
 * | [isInteractiveMode] | vim/top/apt 的确认框会卡住，模型不知道该发按键还是发命令 |
 * | [lastExitCode] | 「上一条命令成功了吗」要靠猜 |
 */
data class TerminalSessionInfo(
    /** 会话 id。模型在后续调用里用它指定「在哪个会话执行」。 */
    val id: String,
    /** 用户/模型给的名字（如 `main`、`nmap`）。 */
    val name: String,
    /** 会话内当前工作目录（容器内路径）。 */
    val currentDirectory: String = "/root",
    /**
     * 是否处于交互模式（前台跑着 vim / top / less / apt 确认框）。
     *
     * 真终端里这个状态是从屏幕内容推断的（提示符 / alternate screen）；
     * 这里由「最近一次命令是否以 & 或交互型命令启动」以及
     * [TerminalSessionCommand.classifyInteractive] 共同判定。
     */
    val isInteractiveMode: Boolean = false,
    /** 交互模式下检测到的提示符文本（用于告诉模型「它在等什么」）。 */
    val interactivePrompt: String = "",
    /** 上一条命令的退出码；还没跑过为 null。 */
    val lastExitCode: Int? = null,
    /** 是否已被关闭。关闭后的会话不再接受命令。 */
    val closed: Boolean = false,
) {
    /** 能否接受新命令。 */
    val canAcceptCommand: Boolean get() = !closed
}

/**
 * 在会话里执行一条命令的请求。
 *
 * ## 为什么把 `commandId` 单独拎出来
 *
 * 同一条命令可能被执行两次（模型重试），而输出要能对上号。
 * Operit 的 `sendCommand(command, commandId)` 就是这个用途；
 * 没有它，流式输出分不清是哪一次调用的产物。
 */
data class TerminalSessionCommand(
    val sessionId: String,
    val command: String,
    /** 调用方给的幂等 id；用于把流式输出归属到某一次调用。 */
    val commandId: String? = null,
    /** 超时秒数。0 表示不超时（交互式前台进程由 [TerminalControl] 接管）。 */
    val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) {
    companion object {
        /** 默认超时：10 分钟。够 `apt install` 与常规扫描，又不至于挂死。 */
        const val DEFAULT_TIMEOUT_SECONDS: Long = 600L

        /**
         * 判断这条命令是否会把终端推进交互模式。
         *
         * ## 为什么需要这个判断
         *
         * 模型发一条 `vim a.txt`，终端会停在编辑器里等按键。此时再发
         * `ls` 是**错的** —— 那个 `ls` 会被 vim 当作按键序列吃进去。
         * 正确的做法是先 `:q!`（通过 [TerminalControl]）退出编辑模式。
         *
         * 所以执行前要能识别这类命令，把 `isInteractiveMode` 置起来，
         * 并在工具描述里告诉模型「现在需要发 control 而不是 command」。
         *
         * ## 判据为什么用「命令名 + 参数」而不是抓提示符
         *
         * 抓提示符要完整 ANSI 屏幕解析（Operit 用 946 行的
         * `AnsiTerminalEmulator` 做这件事）。命令名判断是它的**子集**，
         * 覆盖常见的十来个交互程序，剩下没覆盖到的最坏表现是
         * 「模型多试一次」，而不是崩溃。
         */
        fun classifyInteractive(command: String): Boolean {
            val trimmed = command.trim()
            if (trimmed.isEmpty()) return false
            // 后台运行不是交互。
            if (trimmed.endsWith("&")) return false
            // 取第一个词作为程序名（去掉 sudo 前缀）。
            val firstWord = trimmed.split(Regex("\\s+"))
                .firstOrNull { it.isNotBlank() && it != "sudo" }
                ?.substringAfterLast('/')
                ?: return false
            return firstWord in INTERACTIVE_PROGRAMS
        }

        /**
         * 常见「会把终端占住」的程序。
         *
         * 清单来自 Operit `TerminalManager` 的交互模式判定与实测：
         * 编辑器、分页器、监控器、以及需要确认的包管理器。
         */
        private val INTERACTIVE_PROGRAMS = setOf(
            // 编辑器
            "vi", "vim", "nvim", "nano", "emacs", "ed",
            // 分页器
            "less", "more", "most",
            // 监控 / 全屏 TUI
            "top", "htop", "btop", "atop", "watch",
            // 交互式 shell 与 REPL
            "bash", "sh", "zsh", "fish", "python", "python3", "node", "irb", "sqlite3", "mysql", "psql", "redis-cli",
            // 需要键盘交互的包管理 / 配置
            "apt", "apt-get", "dpkg", "dpkg-reconfigure", "raspi-config",
            // 网络交互
            "ftp", "sftp", "telnet", "ssh", "nc", "ncat", "tmux", "screen",
        )
    }
}

/**
 * 发给交互式会话的「按键」。
 *
 * ## 为什么要枚举而不是让模型直接发字符串
 *
 * 模型发 `"\"\\u0003\""` 这种转义极易出错（多一层 JSON 转义、少一层都不对）。
 * 枚举把「语义」暴露出去，由实现方翻译成真实字节序列（见 [TerminalControl]）。
 *
 * ## 优先级：显式指定的 control 优先于裸输入
 *
 * Operit 的 `input_in_terminal_session` 同时支持 `input`（裸文本）与
 * `control`（控制键），且 `control` 优先（`normalizeControl` 先归一化，
 * 非空就走控制键路径）。这里保持一致。
 */
enum class TerminalControl(
    /** 模型看到的名字（工具参数里写这个）。 */
    val wireName: String,
) {
    /** Ctrl+C：中断当前前台进程。 */
    CtrlC("ctrl_c"),

    /** Ctrl+D：EOF。交互 shell 里相当于 `exit`。 */
    CtrlD("ctrl_d"),

    /** Ctrl+Z：挂起当前进程。 */
    CtrlZ("ctrl_z"),

    /** Ctrl+L：清屏（保留当前输入行）。 */
    CtrlL("ctrl_l"),

    /** 回车。用于确认 `apt` 的 `[Y/n]`。 */
    Enter("enter"),

    /** 上方向键：历史命令。 */
    ArrowUp("up"),

    /** 下方向键。 */
    ArrowDown("down"),

    /** 左方向键。 */
    ArrowLeft("left"),

    /** 右方向键。 */
    ArrowRight("right"),

    /** Tab：补全。 */
    Tab("tab"),

    /** Esc：退出插入模式 / 关闭对话框。 */
    Esc("esc"),

    /** 退格。 */
    Backspace("backspace");

    companion object {
        /** 从模型给的字符串还原；不认识返回 null（由调用方决定报错还是当裸输入）。 */
        fun fromWire(raw: String?): TerminalControl? {
            val key = raw?.trim()?.lowercase() ?: return null
            if (key.isEmpty() || key == "-") return null
            return entries.firstOrNull { it.wireName == key }
        }
    }
}

/**
 * [TerminalControl] → 真实要写入 PTY 的字节序列。
 *
 * ## 为什么单拎一个对象而不是写在枚举里
 *
 * 这些都是 ANSI/VT100 控制序列，是「终端协议」层的知识，不是 UI 概念。
 * 放枚举里会让枚举同时承担「模型词汇表」和「字节协议」两个职责，
 * 而改协议时不该动模型看到的名字。
 *
 * ## 序列来源
 *
 * C0 控制字符用码位数值构造（`1.toChar()`），不写成 `"\u0001"` 字面量：
 * 源码里这类转义极易在复制/生成环节变成真实控制字符，文件看起来是空的，
 * 编译器报 invalid character literal（本项目的 `AnsiScanner` 已经踩过这个坑）。
 */
object TerminalControlSequence {
    /** Ctrl+C（ETX，0x03）。 */
    val CTRL_C: String = 3.toChar().toString()

    /** Ctrl+D（EOT，0x04）。 */
    val CTRL_D: String = 4.toChar().toString()

    /** Ctrl+Z（SUB，0x1A）。 */
    val CTRL_Z: String = 26.toChar().toString()

    /** Ctrl+L（FF，0x0C）。 */
    val CTRL_L: String = 12.toChar().toString()

    /** 回车（CR，0x0D）。终端里 CR 才是「回车」，LF 只换行不下移。 */
    val ENTER: String = 13.toChar().toString()

    /** Tab（HT，0x09）。 */
    val TAB: String = 9.toChar().toString()

    /** 退格（DEL，0x7F）。注意不是 BS(0x08) —— 现代终端用 DEL 表示退格。 */
    val BACKSPACE: String = 127.toChar().toString()

    /** 方向键：CSI A/B/C/D。 */
    val ARROW_UP: String = "\u001b[A"
    val ARROW_DOWN: String = "\u001b[B"
    val ARROW_RIGHT: String = "\u001b[C"
    val ARROW_LEFT: String = "\u001b[D"

    /** Esc（0x1B）。 */
    val ESC: String = 27.toChar().toString()

    /** 把控制键翻译成字节序列。 */
    fun of(control: TerminalControl): String = when (control) {
        TerminalControl.CtrlC -> CTRL_C
        TerminalControl.CtrlD -> CTRL_D
        TerminalControl.CtrlZ -> CTRL_Z
        TerminalControl.CtrlL -> CTRL_L
        TerminalControl.Enter -> ENTER
        TerminalControl.Tab -> TAB
        TerminalControl.Esc -> ESC
        TerminalControl.Backspace -> BACKSPACE
        TerminalControl.ArrowUp -> ARROW_UP
        TerminalControl.ArrowDown -> ARROW_DOWN
        TerminalControl.ArrowLeft -> ARROW_LEFT
        TerminalControl.ArrowRight -> ARROW_RIGHT
    }
}

/**
 * 一次会话输出的一个片段（流式）。
 *
 * 与「最终结果字符串」分开：`execute_in_terminal_session_streaming`
 * 要在命令还没跑完时就往回吐字节，而普通版本只回最终合并结果。
 */
data class TerminalChunk(
    val sessionId: String,
    val commandId: String?,
    val text: String,
    /** 本片段是否来自 stderr。合并输出时无意义，单独返回时有用。 */
    val isErrorStream: Boolean = false,
)

/**
 * 会话管理器要暴露给工具层的能力。
 *
 * ## 为什么是接口而不是直接给实现
 *
 * `core:linux` 是纯 JVM 模块，拿不到 `Context`（PTY 要用 `nativeLibraryDir`、
 * proot 要 `filesDir`）。把接口留在这里、实现放 `:app`，
 * 工具层就能在 JVM 单测里用假实现跑通全部 7 个工具的分支。
 */
interface TerminalSessionPort {
    /** 现有会话快照。 */
    fun sessions(): List<TerminalSessionInfo>

    /** 按 id 找会话；不存在返回 null。 */
    fun find(sessionId: String): TerminalSessionInfo?

    /**
     * 新建会话；[name] 已存在时返回既有会话（幂等，照 Operit 的
     * `createOrGetSession` 语义 —— 模型重试不会造出两个同名会话）。
     */
    suspend fun create(name: String): TerminalSessionInfo

    /** 关闭会话。 */
    fun close(sessionId: String): Boolean

    /**
     * 在会话里执行命令，返回合并后的输出与退出码。
     *
     * @param onChunk 每收到一段输出回调（用于流式工具）。
     */
    suspend fun execute(
        command: TerminalSessionCommand,
        onChunk: ((TerminalChunk) -> Unit)? = null,
    ): LinuxExecResult

    /**
     * 向会话写入按键或裸文本。
     *
     * @param input 裸文本（`control` 为 null 时才用）
     * @param control 控制键（优先于 [input]）
     * @return 是否写入成功（会话不存在 / 已关闭时 false）
     */
    fun sendInput(
        sessionId: String,
        input: String?,
        control: TerminalControl?,
    ): Boolean

    /**
     * 取会话当前屏幕的纯文本快照。
     *
     * 用于模型「看看现在屏幕上是什么」—— 判断一个交互式程序停在哪一步。
     */
    fun screen(sessionId: String, maxLines: Int = 60): String?

    /**
     * 调整会话窗口大小（行 × 列）。
     *
     * ## 为什么这件事不能省略（2026-10-29）
     *
     * PTY 的窗口大小是内核里的一份状态，程序靠 `ioctl(TIOCGWINSZ)` 读它来
     * 决定画多少行、哪里折行。`forkpty` 只给一次初始值，之后**必须**由
     * 前端在键盘弹出、旋转、分屏时重新告知 —— 否则 `vim` / `top` / `less`
     * 会按 60×80 排版，而屏幕上实际只有半屏：表现为底部内容被切、
     * 换行位置错、`top` 的表头与数据对不上。
     *
     * ## 为什么放在端口接口而不是让 UI 直接拿 Pty
     *
     * `feature:terminal` 是纯 UI 模块，它不该知道 `Pty`（那是 `:app` 的
     * JNI 类）。加一个「告诉会话新尺寸」的能力，UI 就能在布局变化时调用，
     * 而实现方（真 PTY / 管道降级）各自处理：真 PTY 走 ioctl，管道降级
     * 直接返回 false（管道没有窗口概念）。
     *
     * @return 是否真的应用了（会话不存在、已关闭、或降级为管道时 false）
     */
    fun resize(sessionId: String, rows: Int, cols: Int): Boolean = false
}
