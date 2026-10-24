package dev.praxis.data.shell

import dev.praxis.core.tool.PermissionTier
import dev.praxis.core.tool.Tool
import dev.praxis.core.tool.ToolContext
import dev.praxis.core.tool.ToolResult
import dev.praxis.core.tool.ToolRisk
import dev.praxis.core.tool.ToolSpec
import org.json.JSONObject

/**
 * `host_shell` —— 在**宿主 Android 系统**上执行 shell 命令。
 *
 * ## 与另外两个执行类工具的分界（必须说清，否则模型会乱用）
 *
 * | 工具 | 执行位置 | 身份 | 用途 |
 * |---|---|---|---|
 * | `host_shell`（本工具） | 手机 Android 系统 | root / Shizuku / app | `pm`、`am`、`settings`、`dumpsys`、读写系统文件 |
 * | `linux_exec` | proot 容器（Ubuntu/Kali） | 容器内伪 root | `nmap`、`apt`、`python3`、编译 |
 * | `execute_in_terminal_session` | 应用内终端会话 | app 或容器 | 需要保持会话状态（cd、环境变量）的连续操作 |
 *
 * 三者互不替代。模型最容易犯的错是「想装 apk 却调 linux_exec」——
 * 容器里没有 `pm`，会得到「命令不存在」，然后它可能开始尝试在容器里装
 * Android 工具链。所以在 description 里把边界写死。
 *
 * ## 为什么档位是 ROOT
 *
 * Operit 把这套通道叫「SuperAdmin」，它对应用户可见的最高档。Praxis 的
 * [PermissionTier.ROOT] 语义正是「系统级 shell 与包管理」——
 * 与 `host_shell` 的实际能力一一对应。
 *
 * 定 ROOT 而不是 STANDARD 的原因：这个工具能 `pm uninstall`、
 * 能改 `/data/system`、能 `reboot`。任何一个误用都是设备级的不可逆后果。
 * 定成 ROOT 后，用户权限档位不到 ROOT 时它**根本不会出现在工具列表里**
 * （见 `descriptorsFor` 的档位过滤），而不是「先发出去再拒绝」——
 * 后者会让模型反复尝试，浪费大量轮次。
 *
 * ## 为什么单独一个 `host_shell_channels`
 *
 * 模型在调用前需要知道「现在能用什么身份」。没有这个工具时它会猜，
 * 猜错就得到 `Permission denied`，然后开始尝试各种绕过（`su` 不存在时
 * 反复试不同 su 路径）。给它一个明确的探活工具，能省掉这些无效轮次。
 */
class HostShellTool(
    private val port: AndroidHostShellPort,
) : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "host_shell",
        description = """
            在**手机 Android 系统**上执行一条 shell 命令（不是 Linux 容器内）。
            适用：pm（装/卸/查应用）、am（启停 Activity）、settings（改系统设置）、
            dumpsys、getprop、读写 /data 或 /sdcard 下的文件、ip/ifconfig 查网络。
            不适用：nmap、apt、python3、gcc 等 Linux 工具 —— 那些用 linux_exec；
            需要 cd/环境变量保持的连续操作 —— 用 execute_in_terminal_session。
            身份自动按 root → Shizuku → 应用自身 依次尝试，回执里会写明实际用的是哪个。
            不确定当前有没有 root/Shizuku 时，先调 host_shell_channels 探活。
        """.trimIndent(),
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "command": {
                  "type": "string",
                  "description": "要执行的完整命令行，交给 sh -c（支持管道、重定向、变量展开）"
                },
                "timeoutSeconds": {
                  "type": "integer",
                  "description": "超时秒数，默认 15，上限 600。长任务（如备份大目录）可调大"
                },
                "channel": {
                  "type": "string",
                  "enum": ["root", "shizuku", "app"],
                  "description": "强制指定通道。省略则自动按 root→shizuku→app 尝试。少数 ROM 的 pm 在 root 下会拒绝当前用户操作，此时指定 shizuku"
                },
                "workingDir": {
                  "type": "string",
                  "description": "工作目录，默认 /"
                }
              },
              "required": ["command"]
            }
        """.trimIndent(),
        tier = PermissionTier.ROOT,
        risk = ToolRisk.Dangerous,
    )

    override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
        val args = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return ToolResult("参数不是合法 JSON。", isError = true)

        val command = args.optString("command").trim()
        if (command.isEmpty()) return ToolResult("command 不能为空。", isError = true)

        val timeout = args.optLong("timeoutSeconds", AndroidHostShellPort.DEFAULT_TIMEOUT_SECONDS)
        val workingDir = args.optString("workingDir").trim().ifEmpty { null }

        // `channel` 是字符串；非法值（模型偶尔会写 "su" / "adb"）按未指定处理，
        // 而不是报参数错误 —— 报错会让它重试一次同样写错的调用。
        val prefer = when (args.optString("channel").trim().lowercase()) {
            "root" -> HostShellChannel.ROOT
            "shizuku" -> HostShellChannel.SHIZUKU
            "app" -> HostShellChannel.APP
            else -> null
        }

        val result = port.execute(
            command = command,
            timeoutSeconds = timeout,
            preferChannel = prefer,
            workingDir = workingDir,
        )

        return ToolResult(format(command, result), isError = !result.isSuccess)
    }

    /**
     * 把执行结果收成一段模型能直接读的文本。
     *
     * ## 几个刻意的选择
     *
     *  - **写明通道**：同一条命令在 root 与 shizuku 下结果可能不同。不写清楚，
     *    模型会把「shizuku 下没有权限」误判成「命令本身错误」。
     *  - **stdout / stderr 分开**：这是模型判断「命令没输出」还是「命令报错」
     *    的唯一依据，合并就丢失了。
     *  - **超时不叫「exit 124」**：模型会把它当成「命令返回了 124」，
     *    从而去查命令手册而不是缩短任务。用明确的中文说明。
     *  - **空输出显式说明**：完全空的回执会让模型以为工具坏了、反复重试。
     */
    private fun format(command: String, result: HostShellResult): String = buildString {
        append("通道：").append(result.channel.label).append('\n')
        if (result.timedOut) {
            append("状态：超时（已强制结束进程，不代表命令本身有错，可能是任务太大或卡在输入）\n")
        } else {
            append("退出码：").append(result.exitCode).append('\n')
        }
        append("命令：").append(command).append('\n')

        if (result.stdout.isNotEmpty()) {
            append("\n--- 输出 ---\n").append(result.stdout).append('\n')
        }
        if (result.stderr.isNotEmpty()) {
            append("\n--- 错误输出 ---\n").append(result.stderr).append('\n')
        }
        if (result.stdout.isEmpty() && result.stderr.isEmpty() && !result.timedOut) {
            append("\n（命令没有产生任何输出）\n")
        }

        if (result.channel == HostShellChannel.APP && result.exitCode != 0) {
            append(
                "\n提示：本次在应用自身身份下执行，权限有限。" +
                    "若需要系统级操作，请先确认设备是否已 root 或 Shizuku 是否在运行。\n",
            )
        }
    }
}

/**
 * `host_shell_channels` —— 探活当前可用的宿主 shell 通道。
 *
 * 单独一个工具（而不是并进 `host_shell` 的返回）的理由：模型在**计划阶段**
 * 就需要这个信息。并进执行回执的话，它必须先发一条命令（可能有副作用）
 * 才能知道能不能用 root。
 *
 * 档位同样是 ROOT —— 它与 `host_shell` 是同一能力的两个入口，
 * 档位不同会出现「能探活但不能执行」这种无意义状态。
 */
class HostShellChannelsTool(
    private val port: AndroidHostShellPort,
) : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "host_shell_channels",
        description = """
            查询手机当前可用的 shell 身份通道（root / shizuku / app），不执行任何命令、
            无副作用。在准备执行系统级操作（pm、am、settings、读写 /data）之前先调它，
            可以避免用错身份导致 Permission denied 后反复重试。
        """.trimIndent(),
        parametersJson = """{"type":"object","properties":{},"required":[]}""",
        tier = PermissionTier.ROOT,
        risk = ToolRisk.Safe,
    )

    override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
        val available = port.availableChannels()
        val text = buildString {
            append("当前可用通道：")
            append(available.joinToString("、") { it.label })
            append('\n')
            when {
                HostShellChannel.ROOT in available ->
                    append("优先使用 root：可执行全部系统级操作（改 /data、卸载系统应用、reboot）。")
                HostShellChannel.SHIZUKU in available ->
                    append(
                        "没有 root，但有 Shizuku（ADB 级 uid 2000）：" +
                            "可用 pm/am/settings/dumpsys，但**不能**读写其他应用私有目录。",
                    )
                else ->
                    append(
                        "只有应用自身身份：几乎无法执行系统级操作。" +
                            "需要的话请让用户在设备上开启 root 或运行 Shizuku。",
                    )
            }
        }
        return ToolResult(text)
    }
}