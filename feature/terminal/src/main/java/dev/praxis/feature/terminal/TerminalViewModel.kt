package dev.praxis.feature.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.praxis.core.terminal.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 回车换行。用码位拼接而不是转义字面量：生成/复制环节会把转义序列解成真实控制字符。 */
private val CRLF: String = 13.toChar().toString() + 10.toChar()

/**
 * 终端命令执行端口。
 *
 * 与 `feature:chat` 的 `ChatPersistence` 同一套思路：**端口在里、实现在外**。
 * `feature:terminal` 是纯 UI + 缓冲逻辑，不能碰 `ProcessBuilder`、不能碰 Android Context；
 * 真正怎么起 shell 由 `:app` 决定（本轮为 `:app/ShellCommandRunner`）。
 *
 * 好处：终端页的会话逻辑可以单测（给一个假 Runner 就行），而且以后换执行方式
 * （比如接 proot、接 adb 远端）只需要换实现，页面一行不动。
 */
interface CommandRunner {

    /**
     * 执行一条命令，把 stdout/stderr 边读边回调。
     *
     * 注意用回调而不是「返回 String」：交互式命令可能跑很久，等它结束才出字就不叫终端了。
     *
     * @param onOutput 收到一块输出（应为已解码的文本；ANSI 序列原样保留，由扫描器处理）。
     * @return 进程退出码。
     */
    suspend fun run(command: String, onOutput: (String) -> Unit): Int
}

/**
 * 终端页 ViewModel。
 *
 * ## 三个必要的边界
 *
 * 1. **缓冲在 VM 里，不在 Composable 里**：旋转屏幕、切页返回都不能丢已输出的内容。
 * 2. **输出回调要立刻同步进缓冲**：如果先把 chunk 攒到协程里再刷，用户会看到输出成块跳。
 *    `TerminalSession.feed` 是纯同步的，直接在 IO 线程调用即可。
 * 3. **同一时刻只允许一条命令**：终端页不是并发执行器，同时跑两条会让输出交错、
 *    提示符错位。命令未结束时再提交，直接忽略（并给出提示）。
 */
class TerminalViewModel(
    private val runner: CommandRunner,
    columns: Int = DEFAULT_COLUMNS,
    rows: Int = DEFAULT_ROWS,
) : ViewModel() {

    private val session = TerminalSession(columns = columns, rows = rows)

    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    private var running = false

    init {
        // 首屏给一条提示：终端页若一片空白，用户不知道能不能用。
        session.feed("Praxis Terminal" + CRLF)
        session.feed("输入命令后回车执行；输出支持 ANSI 颜色。" + CRLF)
        session.feed(CRLF)
        publish(input = "")
    }

    /** 输入框变化（不触发任何执行）。 */
    fun onInputChange(text: String) {
        _state.value = _state.value.copy(input = text)
    }

    /** 提交命令。 */
    fun submit(rawCommand: String) {
        val command = rawCommand.trim()
        if (command.isEmpty()) return

        if (running) {
            // 不排队：终端语义是「顺序交互」，悄悄排队会让用户以为没执行。
            _state.value = _state.value.copy(input = "")
            session.feed("? 上一条命令仍在执行" + CRLF)
            publish(input = "")
            return
        }

        running = true
        // 回显：把用户输入连同提示符写进缓冲，与真实终端一致。
        session.feed("$ " + command + CRLF)
        publish(input = "")

        viewModelScope.launch {
            val exit = withContext(Dispatchers.IO) {
                runCatching {
                    runner.run(command) { chunk ->
                        // 同步喂入：保证每一块都立刻可见。
                        session.feed(chunk)
                    }
                }.getOrElse { error ->
                    session.feed("执行失败: " + (error.message ?: error::class.simpleName) + CRLF)
                    -1
                }
            }

            if (exit != 0) {
                session.feed("[退出码 " + exit + "]" + CRLF)
            }
            running = false
            publish(input = "")
        }
    }

    /** 清屏（保留历史以外的内容能力：本轮直接清空）。 */
    fun clear() {
        session.reset()
        publish(input = "")
    }

    private fun publish(input: String) {
        _state.value = TerminalUiState.from(session, input)
    }

    companion object {
        /**
         * 默认列数。
         *
         * 取 80 而不是按屏宽算：终端程序的换行、表格对齐都依赖列数稳定，
         * 屏幕转一下列数变了，`ls` 的输出会整片重排。80 是通用默认值。
         */
        const val DEFAULT_COLUMNS = 80

        /** 默认行数。超过后进滚动历史。 */
        const val DEFAULT_ROWS = 200
    }
}
