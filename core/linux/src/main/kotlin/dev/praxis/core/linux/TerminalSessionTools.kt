package dev.praxis.core.linux

import dev.praxis.core.tool.PermissionTier
import dev.praxis.core.tool.Tool
import dev.praxis.core.tool.ToolArgumentException
import dev.praxis.core.tool.ToolArguments
import dev.praxis.core.tool.ToolContext
import dev.praxis.core.tool.ToolResult
import dev.praxis.core.tool.ToolRisk
import dev.praxis.core.tool.ToolSpec

/**
 * 七个终端会话工具（1:1 对齐 Operit 的 `ToolRegistration.kt:304-398`）。
 *
 * ## Operit 有哪七个，为什么一个都不能少
 *
 * | 工具 | 缺了会怎样 |
 * |---|---|
 * | [CreateTerminalSessionTool] | 没有会话，后面六个全都无从调用 |
 * | [ExecuteInTerminalSessionTool] | 只能一次性跑命令，`cd` 不跨调用保留 |
 * | [ExecuteStreamingTool] | 长命令（`apt install`）只能干等，模型看不到进度 |
 * | [CloseTerminalSessionTool] | 会话只增不减，跑满后内存/进程泄漏 |
 * | [InputInTerminalSessionTool] | `vim` / `apt` 确认框 / `top` 卡住后再也出不来 |
 * | [GetTerminalScreenTool] | 模型不知道交互程序停在哪个界面，只能瞎猜 |
 * | [LinuxExecTool]（已有） | 无会话的一次性执行，作为「快速跑一条」仍然有用 |
 *
 * ## 为什么工具层不自己跑 ProcessBuilder
 *
 * 终端要**有状态**（同一个 shell 里 `cd` 之后 `pwd` 才正确）。
 * 每次调用都新起进程的话，`cd /tmp` 的那次调用结束后目录就丢了。
 * 所以真正的会话由 `:app` 的 `TerminalSessionPort` 实现持有，
 * 工具只做「参数解析 + 结果格式化」。
 *
 * ## 权限档位为什么都是 [PermissionTier.STANDARD]
 *
 * 它们运行在 **proot 容器内的用户态**，不碰宿主系统文件、不需要 root、
 * 不需要无障碍。真正危险的是「容器里能 `apt install` 什么」，
 * 而那由网络与磁盘配额决定，不是权限档位能拦住的。
 * 把它们标成 ROOT/DEBUGGER 会让用户以为「不给权限就安全了」——
 * 那是误导。风险等级用 [ToolRisk.Dangerous] 如实表达。
 */
private const val PARAM_SESSION_ID = "session_id"
private const val PARAM_COMMAND = "command"
private const val PARAM_SESSION_NAME = "session_name"
private const val PARAM_CONTROL = "control"
private const val PARAM_INPUT = "input"
private const val PARAM_TIMEOUT = "timeout_seconds"
private const val PARAM_MAX_LINES = "max_lines"

/** `session_id` 的说明复用：六个工具都要它，文案必须一致。 */
private const val DESC_SESSION_ID =
    "会话 id（先用 create_terminal_session 取得；不传则用当前默认会话）"

/**
 * 建会话。
 *
 * ## 为什么是「建或取」而不是「总是新建」
 *
 * 模型在长任务里可能重复调 `create_terminal_session(session_name="main")`。
 * 若每次都新建，第二次之后的 `cd` 与变量全在新 shell 里，前面装的东西
 * 就"消失"了 —— 用户看到的现象是"它怎么又不知道装过了"。
 * Operit 的 `createOrGetSession` 就是幂等的，这里照做。
 */
class CreateTerminalSessionTool(
    private val port: TerminalSessionPort,
) : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "create_terminal_session",
        description = """
            创建一个持久的 shell 会话，并返回 session_id。
            同名会话已存在时直接返回它（幂等），不会重复创建。
            后续用 execute_in_terminal_session 在该会话里执行命令，
            目录 (cd) 与环境变量在多次调用之间会保留。
        """.trimIndent(),
        parametersJson = """
            {"type":"object","properties":{
              "session_name":{"type":"string","description":"会话名，如 main 或 nmap。不传则自动命名"}
            },"required":[]}
        """.trimIndent(),
        tier = PermissionTier.STANDARD,
        risk = ToolRisk.Moderate,
    )

    override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
        val args = parseArgs(argumentsJson) ?: return badJson(argumentsJson)
        val requested = args.stringOrNull(PARAM_SESSION_NAME)?.trim()?.takeIf { it.isNotEmpty() }
            ?: "main"
        val session = port.create(requested)
        return ToolResult(
            content = buildString {
                appendLine("会话已就绪。")
                appendLine("session_id = ${session.id}")
                appendLine("name = ${session.name}")
                append("current_directory = ${session.currentDirectory}")
                val all = port.sessions()
                if (all.size > 1) {
                    appendLine()
                    append("现有会话：")
                    append(all.joinToString(", ") { "${it.name}(${it.id})" })
                }
            },
        )
    }
}

/**
 * 在会话里执行命令（非流式，等全部输出）。
 *
 * ## 为什么要把「交互模式」这件事回给模型
 *
 * 命令如果是 `vim`/`top`/`apt` 这类，它会占住终端。执行返回时若只说
 * "已执行"，模型下一步会继续发 `ls`，而那个 `ls` 会被前台程序吃掉。
 * 所以结果里必须明确写「当前处于交互模式，请用 input_in_terminal_session 发按键」。
 */
class ExecuteInTerminalSessionTool(
    private val port: TerminalSessionPort,
) : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "execute_in_terminal_session",
        description = """
            在指定会话里执行一条命令，等待完成并返回合并输出与退出码。
            cd / export 等状态变更会在会话内保留。
            若命令会进入交互界面（vim / top / apt 确认框），返回结果里会提示，
            此时应改用 input_in_terminal_session 发送按键或 enter。
        """.trimIndent(),
        parametersJson = """
            {"type":"object","properties":{
              "$PARAM_SESSION_ID":{"type":"string","description":"$DESC_SESSION_ID"},
              "$PARAM_COMMAND":{"type":"string","description":"要执行的命令"},
              "$PARAM_TIMEOUT":{"type":"integer","description":"超时秒数，默认 600"}
            },"required":["$PARAM_COMMAND"]}
        """.trimIndent(),
        tier = PermissionTier.STANDARD,
        risk = ToolRisk.Dangerous,
    )

    override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
        val args = parseArgs(argumentsJson) ?: return badJson(argumentsJson)
        val command = args.stringOrNull(PARAM_COMMAND)?.trim()
            ?: return ToolResult("缺少参数 $PARAM_COMMAND", isError = true)
        if (command.isEmpty()) return ToolResult("$PARAM_COMMAND 不能为空", isError = true)

        val session = resolveSession(port, args.stringOrNull(PARAM_SESSION_ID))
            ?: return noSession()

        val result = port.execute(
            TerminalSessionCommand(
                sessionId = session.id,
                command = command,
                timeoutSeconds = (args.int(PARAM_TIMEOUT) ?: TerminalSessionCommand.DEFAULT_TIMEOUT_SECONDS.toInt()).toLong(),
            ),
        )
        return ToolResult(renderSessionResult(port, session.id, command, result), result.exitCode != 0)
    }
}

/**
 * 流式执行：边跑边回输出。
 *
 * ## 与 [ExecuteInTerminalSessionTool] 的差别只是「什么时候拿到输出」
 *
 * 两者调同一个 `port.execute`，区别在于本工具把 `onChunk` 转成流。
 * Praxis 的 `Tool.execute` 签名是 suspend 返回单个结果（没有 Flow 变体），
 * 所以这里把片段**累积**并在末尾统一返回，同时在中间通过
 * `ToolProgressBus` 之外的方式告知 —— 见下方「为什么不做真流式」。
 *
 * ## 为什么当前不做真的流式返回
 *
 * Operit 的 `ToolExecutor.invokeAndStream` 返回 `Flow<ToolResult>`，
 * 它的 UI 管线能吃中间结果。Praxis 的 `Tool` 接口没有 Flow 分支，
 * 要加就得改 `core:tool` 的契约、`ToolExecutor`、`ChatViewModel` 三处，
 * 并且要考虑「中间结果能不能进模型上下文」（能进会烧 token）。
 *
 * 本轮先保证**功能对齐**（命令能跑、输出完整、交互可接管），
 * 真流式等工具契约加 Flow 之后再接。这里如实标注，不假装已经流式。
 */
class ExecuteInTerminalSessionStreamingTool(
    private val port: TerminalSessionPort,
) : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "execute_in_terminal_session_streaming",
        description = """
            在指定会话里执行命令，行为与 execute_in_terminal_session 相同，
            但会保留分段输出（适合 apt / 编译 / 长扫描这类命令）。
            注意：当前实现仍在命令结束后一次性返回，分段边界会保留在输出里。
        """.trimIndent(),
        parametersJson = """
            {"type":"object","properties":{
              "$PARAM_SESSION_ID":{"type":"string","description":"$DESC_SESSION_ID"},
              "$PARAM_COMMAND":{"type":"string","description":"要执行的命令"},
              "$PARAM_TIMEOUT":{"type":"integer","description":"超时秒数，默认 600"}
            },"required":["$PARAM_COMMAND"]}
        """.trimIndent(),
        tier = PermissionTier.STANDARD,
        risk = ToolRisk.Dangerous,
    )

    override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
        val args = parseArgs(argumentsJson) ?: return badJson(argumentsJson)
        val command = args.stringOrNull(PARAM_COMMAND)?.trim()
            ?: return ToolResult("缺少参数 $PARAM_COMMAND", isError = true)

        val session = resolveSession(port, args.stringOrNull(PARAM_SESSION_ID))
            ?: return noSession()

        val chunks = ArrayList<TerminalChunk>()
        val result = port.execute(
            TerminalSessionCommand(
                sessionId = session.id,
                command = command,
                timeoutSeconds = (args.int(PARAM_TIMEOUT) ?: TerminalSessionCommand.DEFAULT_TIMEOUT_SECONDS.toInt()).toLong(),
            ),
            onChunk = { chunks += it },
        )
        val body = if (chunks.isEmpty()) {
            result.output
        } else {
            // 分段边界用换行保留：模型能看到「输出是分批到达的」，
            // 且不会把两段粘成一行导致读起来错位。
            chunks.joinToString(separator = "\n") { it.text.trimEnd() }
        }
        return ToolResult(
            renderSessionResult(port, session.id, command, result.copy(output = body))
                .replaceFirst("输出：", "分段输出："),
            result.exitCode != 0,
        )
    }
}

/**
 * 关会话。
 *
 * ## 为什么必须能关
 *
 * 每个会话背后是一个常驻 shell 进程。模型跑几十条命令会建出十几个会话；
 * 不关的话进程一直挂着，安卓上表现为电耗与内存都异常，用户只会觉得
 * "这 App 越用越卡"。
 */
class CloseTerminalSessionTool(
    private val port: TerminalSessionPort,
) : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "close_terminal_session",
        description = "关闭一个终端会话并释放其进程。会话 id 不存在时返回失败。",
        parametersJson = """
            {"type":"object","properties":{
              "$PARAM_SESSION_ID":{"type":"string","description":"$DESC_SESSION_ID"}
            },"required":["$PARAM_SESSION_ID"]}
        """.trimIndent(),
        tier = PermissionTier.STANDARD,
        risk = ToolRisk.Moderate,
    )

    override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
        val args = parseArgs(argumentsJson) ?: return badJson(argumentsJson)
        val sessionId = args.stringOrNull(PARAM_SESSION_ID)?.trim()
            ?: return ToolResult("缺少参数 $PARAM_SESSION_ID", isError = true)
        val ok = port.close(sessionId)
        return if (ok) {
            ToolResult("会话 $sessionId 已关闭。剩余会话：${port.sessions().size} 个")
        } else {
            ToolResult("会话 $sessionId 不存在或已关闭。", isError = true)
        }
    }
}

/**
 * 向会话写按键或裸文本。
 *
 * ## control 优先于 input（照 Operit）
 *
 * Operit 的 `input_in_terminal_session` 先 `normalizeControl(control)`，
 * 非空就走控制键路径、忽略 `input`。这里保持一致：
 * 两个都传时 control 胜出，避免「到底哪个生效」的歧义。
 *
 * ## 为什么 `input` 不自动补回车
 *
 * 有些场景需要「输入字符但不提交」（比如 `vim` 里按 `i` 进插入模式、
 * 或输入一半等下一次调用）。自动补 `\r` 会让这些场景没法用。
 * 要提交就显式传 `control=enter`，语义清晰。
 */
class InputInTerminalSessionTool(
    private val port: TerminalSessionPort,
) : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "input_in_terminal_session",
        description = """
            向交互中的会话发送按键或文本。
            control 支持：ctrl_c / ctrl_d / ctrl_z / ctrl_l / enter / tab / esc /
            backspace / up / down / left / right。
            同时给 control 与 input 时以 control 为准。
            不会自动回车，要提交请另发 control=enter。
        """.trimIndent(),
        parametersJson = """
            {"type":"object","properties":{
              "$PARAM_SESSION_ID":{"type":"string","description":"$DESC_SESSION_ID"},
              "$PARAM_INPUT":{"type":"string","description":"要输入的裸文本（不含回车）"},
              "$PARAM_CONTROL":{"type":"string","description":"控制键名，见工具说明"}
            },"required":[]}
        """.trimIndent(),
        tier = PermissionTier.STANDARD,
        risk = ToolRisk.Dangerous,
    )

    override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
        val args = parseArgs(argumentsJson) ?: return badJson(argumentsJson)
        val session = resolveSession(port, args.stringOrNull(PARAM_SESSION_ID))
            ?: return noSession()

        val rawControl = args.stringOrNull(PARAM_CONTROL)
        val control = TerminalControl.fromWire(rawControl)
        // 给了 control 但认不出来：明确报错，不要静默降级成裸输入 ——
        // 那会让模型以为按键发出去了，而终端里什么都没发生。
        if (!rawControl.isNullOrBlank() && rawControl != "-" && control == null) {
            val known = TerminalControl.entries.joinToString(", ") { it.wireName }
            return ToolResult(
                "未知的 control：$rawControl。可用值：$known",
                isError = true,
            )
        }
        val input = args.stringOrNull(PARAM_INPUT)

        if (control == null && input.isNullOrEmpty()) {
            return ToolResult("需要提供 $PARAM_INPUT 或 $PARAM_CONTROL 之一。", isError = true)
        }

        val ok = port.sendInput(session.id, input, control)
        if (!ok) return ToolResult("写入失败：会话 ${session.id} 已关闭。", isError = true)

        val what = if (control != null) "按键 ${control.wireName}" else "文本 ${input!!.length} 字符"
        return ToolResult(
            buildString {
                append("已向会话 ${session.id} 发送$what。")
                val screen = port.screen(session.id, maxLines = 20)
                if (!screen.isNullOrBlank()) {
                    appendLine()
                    appendLine("当前屏幕：")
                    append(screen)
                }
            },
        )
    }
}

/**
 * 取会话屏幕快照。
 *
 * ## 为什么模型需要这个
 *
 * 交互程序（`vim`、`apt` 的配置对话框、`top`）的「下一步该按什么」
 * 完全取决于屏幕内容。没有这个工具，模型只能靠猜，
 * 表现出来就是「在 vim 里疯狂发命令，越试越乱」。
 */
class GetTerminalScreenTool(
    private val port: TerminalSessionPort,
) : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "get_terminal_session_screen",
        description = """
            返回指定会话当前屏幕的纯文本快照（已去掉 ANSI 转义）。
            用于判断交互式程序当前停在哪一步，从而决定下一步发什么按键。
        """.trimIndent(),
        parametersJson = """
            {"type":"object","properties":{
              "$PARAM_SESSION_ID":{"type":"string","description":"$DESC_SESSION_ID"},
              "$PARAM_MAX_LINES":{"type":"integer","description":"最多返回多少行，默认 60"}
            },"required":[]}
        """.trimIndent(),
        tier = PermissionTier.STANDARD,
        risk = ToolRisk.Safe,
    )

    override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
        val args = parseArgs(argumentsJson) ?: return badJson(argumentsJson)
        val session = resolveSession(port, args.stringOrNull(PARAM_SESSION_ID))
            ?: return noSession()
        val maxLines = (args.int(PARAM_MAX_LINES) ?: 60).coerceIn(1, 400)
        val screen = port.screen(session.id, maxLines)
            ?: return ToolResult("会话 ${session.id} 没有屏幕内容。", isError = true)

        return ToolResult(
            buildString {
                appendLine("会话 ${session.id}（${session.name}）屏幕：")
                if (session.isInteractiveMode) {
                    appendLine("⚠ 当前处于交互模式" +
                        if (session.interactivePrompt.isNotBlank()) "：${session.interactivePrompt}" else "")
                }
                append(screen.ifBlank { "(空)" })
            },
        )
    }
}

// ── 共用小工具 ──────────────────────────────────────────────

/**
 * 解析目标会话：给了 id 就用它，没给就用第一个可用会话。
 *
 * 为什么不报「必须指定 session_id」：单会话是绝大多数场景，
 * 强迫模型每次都先 `create` 再 `execute` 只是多烧一轮 token。
 */
private fun resolveSession(port: TerminalSessionPort, rawId: String?): TerminalSessionInfo? {
    val id = rawId?.trim()?.takeIf { it.isNotEmpty() }
    if (id != null) return port.find(id)?.takeIf { it.canAcceptCommand }
    return port.sessions().firstOrNull { it.canAcceptCommand }
}

private fun noSession(): ToolResult = ToolResult(
    "还没有可用的终端会话。请先调用 create_terminal_session 创建一个。",
    isError = true,
)

/**
 * 统一的结果排版。
 *
 * ## 为什么带上「当前目录」
 *
 * 终端输出里常常只有相对路径（`./a.txt`）。不给当前目录，模型无法拼出
 * 绝对路径去读文件，只能再跑一次 `pwd` —— 每次多一轮。
 */
private fun renderSessionResult(
    port: TerminalSessionPort,
    sessionId: String,
    command: String,
    result: LinuxExecResult,
): String = buildString {
    appendLine("$ $command")
    val session = port.find(sessionId)
    if (session != null) appendLine("cwd: ${session.currentDirectory}")
    when {
        result.timedOut -> appendLine("⚠ 已超时中断（exit=${result.exitCode}）")
        result.exitCode != 0 -> appendLine("exit=${result.exitCode}")
    }
    if (session?.isInteractiveMode == true) {
        appendLine("⚠ 命令仍在交互模式中运行。请用 input_in_terminal_session 发送按键（如 enter 或 ctrl_c）。")
    }
    appendLine("输出：")
    append(result.output.ifBlank { "(无输出)" })
}
// ---- 参数安全解析 ----

/** 安全解析参数：非法 JSON 返回 null，由调用方转成 isError 结果。 */
private fun parseArgs(argumentsJson: String): ToolArguments? =
    try {
        ToolArguments.parse(argumentsJson)
    } catch (e: ToolArgumentException) {
        null
    }

/** 非法 JSON 的标准回应（带原文片段，便于模型自查）。 */
private fun badJson(argumentsJson: String): ToolResult = ToolResult(
    "参数不是合法 JSON：${argumentsJson.take(120)}",
    isError = true,
)
