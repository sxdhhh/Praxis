package dev.praxis.data.linux

import dev.praxis.core.linux.LinuxDistro
import dev.praxis.core.linux.LinuxExecResult
import dev.praxis.core.linux.LinuxStatus
import dev.praxis.core.linux.LinuxToolPort
import dev.praxis.core.tool.PermissionTier
import dev.praxis.core.tool.Tool
import dev.praxis.core.tool.ToolContext
import dev.praxis.core.tool.ToolResult
import dev.praxis.core.tool.ToolRisk
import dev.praxis.core.tool.ToolSpec
import org.json.JSONObject

/**
 * 供 AI 调用的 Linux（Kali）容器工具。
 *
 * ## user 的原话与它的两个落点
 *
 * > 「内置一个 kali linux 吧，然后让 ai 可以调用」
 *
 * 两件事都要真做到：
 *  1. **内置** = `LinuxDistro.Kali` 在代码里登记（rootfs 地址、源、体积），
 *     用户在设置页能直接选装，不需要自己找 ISO；
 *  2. **AI 可调用** = 这个文件。它把容器执行能力注册成工具，
 *     模型在对话里能真的跑 `nmap`、`python3`、`apt install`。
 *
 * 只做 1 不做 2，Kali 就只是个「用户能开个终端敲命令」的玩具；
 * 那与 user 要的「让 ai 可以调用」差着一个量级。
 *
 * ## 为什么是三个工具而不是一个
 *
 * 一个「万能 execute」会让模型无法表达意图，也没法在无参时拿到状态。
 * 拆开后各自职责单一，且**每个都能被独立授权/禁用**：
 *
 * | 工具 | 风险 | 用途 |
 * |---|---|---|
 * | `linux_status` | Safe | 问「装没装、能不能跑」，只读 |
 * | `linux_exec` | Dangerous | 真执行命令 |
 * | `linux_list_tools` | Safe | 查容器里有哪些安全工具可用（避免模型瞎猜命令名） |
 *
 * `linux_list_tools` 单独存在的理由很具体：模型经常假设
 * `msfconsole` 一定存在。先问一句「有什么」再决定跑什么，
 * 比让它在 131 MB 的 minimal 镜像里反复试错要省得多。
 *
 * ## 权限档位
 *
 * `linux_exec` 给 [PermissionTier.STANDARD] 而不是 ROOT：
 * 容器内部本来就是 root（proot 伪 root），但它**不碰宿主系统** ——
 * 真正危险的是 `:app` 的宿主 shell 工具。给 STANDARD 让普通用户也能用，
 * 同时用 [ToolRisk.Dangerous] 在界面上标红。
 */
class LinuxExecTool(
    private val port: LinuxToolPort,
) : Tool {

    override val spec: ToolSpec = ToolSpec(
        name = "linux_exec",
        description = """
            在设备的 Linux 容器（Kali / Ubuntu）里执行一条 shell 命令。

            适用：需要用 Linux 工具（nmap、curl、python3、grep、apt 等）完成的任务。
            不适用：读写手机上的文件（用文件工具）、执行 Android shell（用终端工具）。

            容器是 proot 伪 root，**不碰宿主系统**，但网络与容器内文件会真实改变。

            首次使用若容器未安装，可传 autoInstall=true 让本工具自动下载安装
            （Kali 约 131 MB，需联网，耗时几分钟）。不确定装没装时，先调 linux_status。
        """.trimIndent(),
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "command": {
                  "type": "string",
                  "description": "要执行的命令，交给容器内 /bin/sh -c"
                },
                "timeoutSeconds": {
                  "type": "integer",
                  "description": "超时秒数，默认 300。全端口扫描等长任务可调大"
                },
                "autoInstall": {
                  "type": "boolean",
                  "description": "容器未安装时是否自动安装（下载 131 MB，需联网）。默认 false"
                }
              },
              "required": ["command"]
            }
        """.trimIndent(),
        tier = PermissionTier.STANDARD,
        risk = ToolRisk.Dangerous,
    )

    override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
        val args = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return ToolResult("参数不是合法 JSON。", isError = true)

        val command = args.optString("command").trim()
        if (command.isEmpty()) return ToolResult("command 不能为空。", isError = true)

        val timeout = args.optLong("timeoutSeconds", LinuxToolPort.DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(1L, MAX_TIMEOUT_SECONDS)
        val autoInstall = args.optBoolean("autoInstall", false)

        val result: LinuxExecResult = port.run(
            command = command,
            timeoutSeconds = timeout,
            autoInstall = autoInstall,
        )
        return formatResult(command, result)
    }

    /**
     * 把执行结果转成给模型的文本。
     *
     * ## 为什么要带退出码与超时标记
     *
     * 只回输出的话，模型无法区分「命令成功但没有输出」与「命令失败了但没报错」。
     * 这两者后续动作完全不同（前者可以继续，后者要换命令）。
     *
     * 超时是**单独**标注的：它是工具层面的中断，不是命令自己的返回值，
     * 混成 `exit=124` 会让模型误以为是命令返回了 124。
     */
    private fun formatResult(command: String, result: LinuxExecResult): ToolResult {
        val body = buildString {
            appendLine("$ " + command)
            if (result.timedOut) {
                appendLine("[超时中断：命令未在限定时间内结束，已终止]")
            }
            if (result.output.isBlank()) {
                appendLine("[无输出]")
            } else {
                // 截断而不是全塞：一次 nmap 全端口扫描轻易几十万字符，
                // 灌进上下文会直接把后续几轮挤爆。
                if (result.output.length > LinuxToolPort.MAX_OUTPUT_CHARS) {
                    appendLine(result.output.take(LinuxToolPort.MAX_OUTPUT_CHARS))
                    appendLine(
                        "[输出已截断：共 ${result.output.length} 字符，" +
                            "只保留前 ${LinuxToolPort.MAX_OUTPUT_CHARS}]",
                    )
                } else {
                    append(result.output)
                }
            }
            appendLine()
            append("退出码：${result.exitCode}")
        }
        // 非零退出码算「业务失败」但不抛异常：让模型看到内容自己纠正。
        return ToolResult(body, isError = result.exitCode != 0 || result.timedOut)
    }

    private companion object {
        /** 上限 30 分钟：再长就应该换成后台任务，而不是占着一次工具调用。 */
        const val MAX_TIMEOUT_SECONDS = 1800L
    }
}

/**
 * 容器状态查询（只读）。
 *
 * 与 [LinuxExecTool] 分开的理由：模型在「不知道装没装」时需要的是一句事实，
 * 而不是去跑 `which nmap` 猜。这个工具**不触发任何安装**，无副作用。
 */
class LinuxStatusTool(
    private val port: LinuxToolPort,
) : Tool {

    override val spec: ToolSpec = ToolSpec(
        name = "linux_status",
        description = """
            查询设备上 Linux 容器（Kali / Ubuntu）的状态：是否已安装、能否执行命令。

            无副作用。在执行 linux_exec 之前不确定环境是否就绪时，先调这个。
        """.trimIndent(),
        parametersJson = """{"type":"object","properties":{},"required":[]}""",
        tier = PermissionTier.STANDARD,
        risk = ToolRisk.Safe,
    )

    override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
        val status: LinuxStatus = port.status()
        return ToolResult(
            buildString {
                appendLine("发行版：${status.distro.label}")
                appendLine("proot 二进制：" + if (status.prootAvailable) "就绪" else "缺失")
                appendLine("rootfs：" + if (status.installed) "已安装" else "未安装")
                appendLine("会话：" + if (status.running) "运行中" else "未运行")
                appendLine()
                append(
                    when {
                        !status.prootAvailable ->
                            "结论：不可用。APK 未打包 proot，Linux 环境无法启动。"
                        !status.installed ->
                            "结论：未安装。可调 linux_exec 并传 autoInstall=true 自动安装" +
                                "（Kali 约 131 MB，需联网）。"
                        else -> "结论：可用，可以直接跑命令。"
                    },
                )
            },
        )
    }
}

/**
 * 列出容器里可用的安全工具（只读）。
 *
 * ## 为什么需要它
 *
 * Kali **minimal** 只是 Kali 的一个子集：`nmap` 有，`msfconsole` 默认**没有**。
 * 模型若凭「Kali 应该有 msfconsole」的直觉去跑，会得到 `command not found`，
 * 然后它可能开始 `apt install metasploit-framework`（几百 MB）——
 * 而用户只是问了一句「查下这个 IP 的端口」。
 *
 * 先列后跑，能省掉这一类无谓的安装。
 */
class LinuxListToolsTool(
    private val port: LinuxToolPort,
) : Tool {

    override val spec: ToolSpec = ToolSpec(
        name = "linux_list_tools",
        description = """
            列出 Linux 容器里已安装的常用安全 / 运维工具，避免猜错命令名。

            无副作用。注意 Kali minimal 镜像只含部分工具，
            未出现在结果里的工具需要 apt install 才能用。
        """.trimIndent(),
        parametersJson = """{"type":"object","properties":{},"required":[]}""",
        tier = PermissionTier.STANDARD,
        risk = ToolRisk.Safe,
    )

    override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
        val status = port.status()
        if (!status.canRun) {
            return ToolResult(
                "容器尚不可用（未安装或缺少 proot）。先调 linux_status 查看详情。",
                isError = true,
            )
        }
        // 一次 `command -v` 批量探测：比逐个 `which` 少起几十个进程。
        val result = port.run(
            command = "for t in " + PROBE_TOOLS.joinToString(" ") +
                "; do command -v \$t >/dev/null 2>&1 && echo \$t; done",
            timeoutSeconds = 60L,
        )
        if (result.timedOut) return ToolResult("探测超时。", isError = true)

        val found = result.output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        return ToolResult(
            if (found.isEmpty()) {
                "一个常用工具都没检测到。可能镜像不完整，或需要用 apt install 逐个安装。"
            } else {
                "已安装（" + found.size + "）：" + found.joinToString("、")
            },
        )
    }

    private companion object {
        /**
         * 探测清单。
         *
         * 只放「值得先确认再决定要不要跑」的工具，不追求全量：
         * 清单越长，`command -v` 的循环越慢（每个都要 fork 一次）。
         */
        val PROBE_TOOLS = listOf(
            "nmap", "curl", "wget", "git", "python3", "pip3", "gcc", "make",
            "netcat", "socat", "ssh", "sshd", "mysql", "sqlmap", "hydra",
            "msfconsole", "john", "hashcat", "aircrack-ng", "tcpdump",
        )
    }
}

/** 按发行版给出「默认装哪个」——AI 调用不传发行版时用 Kali（user 的要求）。 */
internal fun defaultDistroForTool(): LinuxDistro = LinuxDistro.Kali