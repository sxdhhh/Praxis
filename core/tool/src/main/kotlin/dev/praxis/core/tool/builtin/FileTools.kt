package dev.praxis.core.tool.builtin

import dev.praxis.core.tool.PermissionTier
import dev.praxis.core.tool.Tool
import dev.praxis.core.tool.ToolArguments
import dev.praxis.core.tool.ToolContext
import dev.praxis.core.tool.ToolResult
import dev.praxis.core.tool.ToolRisk
import dev.praxis.core.tool.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 文件系统工具（纯 JVM 版，用 java.io.File）。
 *
 * 为什么先做这一组而不是 shell/网络：
 *  - 读写文件是 Agent 最基础的能力，且**完全可测**（临时目录）；
 *  - java.io.File 在 JVM 与 Android 上行为一致，不需要 Robolectric；
 *  - 沙箱边界清晰：只允许访问给定的 [rootDir]。
 *
 * ## 沙箱边界
 *
 * 所有路径都相对于 [rootDir] 解析，并**拒绝逃逸**（`..` 越界）。
 * 这不是可选的加固 —— 没有它会变成「模型可以读整个设备」。
 */
class FileTools(
    private val rootDir: File,
) {

    fun all(): List<Tool> = listOf(
        ListDirTool(rootDir),
        ReadFileTool(rootDir),
        WriteFileTool(rootDir),
        DeleteFileTool(rootDir),
        // 2026-10-24 补：对齐 Operit 的文件工具面里最常用的四个。
        // 判据是「模型不用它就得绕一大圈」——
        //   file_exists → 否则要用 read_file 试探（浪费 token 或误判为失败）
        //   file_info   → 否则不知道文件多大，read_file 直接撞截断
        //   move_file   → 否则「改名」只能 write+delete（丢元数据、大文件翻倍写）
        //   copy_file   → 否则备份一个文件都做不到
        FileExistsTool(rootDir),
        FileInfoTool(rootDir),
        MoveFileTool(rootDir),
        CopyFileTool(rootDir),
    )
}

/** 解析并校验路径，越界直接拒绝。 */
internal fun resolveInRoot(rootDir: File, raw: String): File {
    val root = rootDir.canonicalFile

    // 先拒绝绝对路径与盘符路径。
    // 只靠 canonicalFile 做前缀比较是不够的：不同 JVM 对 `File(root, "/x")`
    // 的拼接行为不同（部分版本会直接把 /x 当相对段），前缀比较可能放行。
    if (raw.startsWith("/") || raw.startsWith("\\") || Regex("^[A-Za-z]:").containsMatchIn(raw)) {
        throw dev.praxis.core.tool.ToolArgumentException(
            "路径越界：$raw 不在允许的目录内",
        )
    }

    val target = File(root, raw).canonicalFile
    val rootPath = root.path
    val targetPath = target.path
    val inside = targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)
    if (!inside) {
        throw dev.praxis.core.tool.ToolArgumentException(
            "路径越界：$raw 不在允许的目录内",
        )
    }
    return target
}

private class ListDirTool(private val rootDir: File) : Tool {

    override val spec = ToolSpec(
        name = "list_dir",
        description = "列出目录内容。返回每行的格式为：类型 名称 大小。",
        parametersJson = """
            {"type":"object","properties":{"path":{"type":"string","description":"相对路径，留空表示根目录"}},"required":[]}
        """.trimIndent(),
        tier = PermissionTier.STANDARD,
        risk = ToolRisk.Safe,
    )

    override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
        val args = try {
            ToolArguments.parse(argumentsJson)
        } catch (e: dev.praxis.core.tool.ToolArgumentException) {
            // 工具自己保证「调用即返回结果」：直接调用方（测试、脚本）不能收到异常。
            return ToolResult("参数错误：${e.message}", isError = true)
        }
        val rel = args.stringOrNull("path").orEmpty()
        val dir = try {
            resolveInRoot(rootDir, rel)
        } catch (e: dev.praxis.core.tool.ToolArgumentException) {
            return ToolResult("参数错误：${e.message}", isError = true)
        }

        if (!dir.exists()) return ToolResult("目录不存在：$rel", isError = true)
        if (!dir.isDirectory) return ToolResult("不是目录：$rel", isError = true)

        // listFiles() 返回 Array<File>?；直接 `?: emptyList()` 会让推断退化成 Any，
        // 必须显式标成 List<File>。
        val children: List<File> =
            withContext(Dispatchers.IO) { dir.listFiles()?.toList() } ?: emptyList()
        if (children.isEmpty()) return ToolResult("（空目录）")

        val text = children
            .sortedBy { it.name }
            .joinToString("\n") { f ->
                val type = if (f.isDirectory) "dir " else "file"
                "$type ${f.name} ${if (f.isDirectory) "" else f.length().toString()}"
            }
        return ToolResult(text)
    }
}

private class ReadFileTool(private val rootDir: File) : Tool {

    /** 单次读取上限：防止模型一次拉爆上下文。 */
    private val maxBytes = 32_000

    override val spec = ToolSpec(
        name = "read_file",
        description = "读取文本文件内容。超过 32000 字节会截断并标注。",
        parametersJson = """
            {"type":"object","properties":{"path":{"type":"string","description":"相对路径"}},"required":["path"]}
        """.trimIndent(),
        tier = PermissionTier.STANDARD,
        risk = ToolRisk.Safe,
    )

    override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
        val args = try {
            ToolArguments.parse(argumentsJson)
        } catch (e: dev.praxis.core.tool.ToolArgumentException) {
            // 工具自己保证「调用即返回结果」：直接调用方（测试、脚本）不能收到异常。
            return ToolResult("参数错误：${e.message}", isError = true)
        }
        val rel = try {
            args.string("path")
        } catch (e: dev.praxis.core.tool.ToolArgumentException) {
            return ToolResult("参数错误：${e.message}", isError = true)
        }
        val file = try {
            resolveInRoot(rootDir, rel)
        } catch (e: dev.praxis.core.tool.ToolArgumentException) {
            return ToolResult("参数错误：${e.message}", isError = true)
        }

        if (!file.exists()) return ToolResult("文件不存在：$rel", isError = true)
        if (file.isDirectory) return ToolResult("这是目录，不是文件：$rel", isError = true)

        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        val truncated = bytes.size > maxBytes
        val slice = if (truncated) bytes.copyOf(maxBytes) else bytes
        val text = String(slice, Charsets.UTF_8)

        return ToolResult(
            if (truncated) "$text\n\n... (file content truncated at $maxBytes bytes, total ${bytes.size}) ..."
            else text,
        )
    }
}

private class WriteFileTool(private val rootDir: File) : Tool {

    override val spec = ToolSpec(
        name = "write_file",
        description = "写入文本文件，覆盖已有内容。父目录会自动创建。",
        parametersJson = """
            {"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}
        """.trimIndent(),
        tier = PermissionTier.STANDARD,
        risk = ToolRisk.Moderate,
    )

    override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
        val args = try {
            ToolArguments.parse(argumentsJson)
        } catch (e: dev.praxis.core.tool.ToolArgumentException) {
            // 工具自己保证「调用即返回结果」：直接调用方（测试、脚本）不能收到异常。
            return ToolResult("参数错误：${e.message}", isError = true)
        }
        val rel = try {
            args.string("path")
        } catch (e: dev.praxis.core.tool.ToolArgumentException) {
            return ToolResult("参数错误：${e.message}", isError = true)
        }
        val content = try {
            args.string("content")
        } catch (e: dev.praxis.core.tool.ToolArgumentException) {
            return ToolResult("参数错误：${e.message}", isError = true)
        }
        val file = try {
            resolveInRoot(rootDir, rel)
        } catch (e: dev.praxis.core.tool.ToolArgumentException) {
            return ToolResult("参数错误：${e.message}", isError = true)
        }

        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
        }
        return ToolResult("已写入 ${content.length} 个字符到 $rel")
    }
}

private class DeleteFileTool(private val rootDir: File) : Tool {

    override val spec = ToolSpec(
        name = "delete_file",
        description = "删除文件（不删目录）。",
        parametersJson = """
            {"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}
        """.trimIndent(),
        tier = PermissionTier.STANDARD,
        risk = ToolRisk.Dangerous,
    )

    override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
        val args = try {
            ToolArguments.parse(argumentsJson)
        } catch (e: dev.praxis.core.tool.ToolArgumentException) {
            // 工具自己保证「调用即返回结果」：直接调用方（测试、脚本）不能收到异常。
            return ToolResult("参数错误：${e.message}", isError = true)
        }
        val rel = try {
            args.string("path")
        } catch (e: dev.praxis.core.tool.ToolArgumentException) {
            return ToolResult("参数错误：${e.message}", isError = true)
        }
        val file = try {
            resolveInRoot(rootDir, rel)
        } catch (e: dev.praxis.core.tool.ToolArgumentException) {
            return ToolResult("参数错误：${e.message}", isError = true)
        }

        if (!file.exists()) return ToolResult("文件不存在：$rel", isError = true)
        if (file.isDirectory) return ToolResult("不允许删除目录：$rel", isError = true)

        val ok = withContext(Dispatchers.IO) { file.delete() }
        return if (ok) ToolResult("已删除 $rel") else ToolResult("删除失败：$rel", isError = true)
    }
}

/**
 * 文件是否存在（以及是文件还是目录）。
 *
 * ## 为什么值得单独一个工具
 *
 * 模型要写文件前常常先「探一下有没有」，用 `read_file` 探会拿到
 * 一整段内容（浪费 token）或一条「不存在」错误（与真正的失败混在一起）。
 * 这个工具只回答一个是非问题，且**不存在不是错误** ——
 * `isError = false` 让模型能正常读到「false」并继续决策。
 */
private class FileExistsTool(private val rootDir: File) : Tool {

    override val spec = ToolSpec(
        name = "file_exists",
        description = "检查路径是否存在。返回 exists=true/false 以及类型（file/dir）。" +
            "路径不存在不算错误。",
        parametersJson = """
            {"type":"object","properties":{"path":{"type":"string","description":"相对路径"}},"required":["path"]}
        """.trimIndent(),
        tier = PermissionTier.STANDARD,
        risk = ToolRisk.Safe,
    )

    override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
        val args = try {
            ToolArguments.parse(argumentsJson)
        } catch (e: dev.praxis.core.tool.ToolArgumentException) {
            return ToolResult("参数错误：${e.message}", isError = true)
        }
        val rel = try {
            args.string("path")
        } catch (e: dev.praxis.core.tool.ToolArgumentException) {
            return ToolResult("参数错误：${e.message}", isError = true)
        }
        val f = try {
            resolveInRoot(rootDir, rel)
        } catch (e: dev.praxis.core.tool.ToolArgumentException) {
            return ToolResult("参数错误：${e.message}", isError = true)
        }
        val type = when {
            !f.exists() -> "none"
            f.isDirectory -> "dir"
            else -> "file"
        }
        return ToolResult("exists=${f.exists()} type=$type path=$rel")
    }
}

/**
 * 文件/目录的元信息（大小、修改时间、是否目录）。
 *
 * 与 [FileExistsTool] 的分工：那个回答「有没有」，这个回答「多大、什么时候改的」。
 * 模型在「决定要不要读一个可能很大的文件」时需要的正是后者 ——
 * 先看大小再决定读不读，而不是先 `read_file` 撞上截断。
 */
private class FileInfoTool(private val rootDir: File) : Tool {

    override val spec = ToolSpec(
        name = "file_info",
        description = "查看文件或目录的大小、修改时间、是否目录。",
        parametersJson = """
            {"type":"object","properties":{"path":{"type":"string","description":"相对路径"}},"required":["path"]}
        """.trimIndent(),
        tier = PermissionTier.STANDARD,
        risk = ToolRisk.Safe,
    )

    override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
        val args = try {
            ToolArguments.parse(argumentsJson)
        } catch (e: dev.praxis.core.tool.ToolArgumentException) {
            return ToolResult("参数错误：${e.message}", isError = true)
        }
        val rel = try {
            args.string("path")
        } catch (e: dev.praxis.core.tool.ToolArgumentException) {
            return ToolResult("参数错误：${e.message}", isError = true)
        }
        val f = try {
            resolveInRoot(rootDir, rel)
        } catch (e: dev.praxis.core.tool.ToolArgumentException) {
            return ToolResult("参数错误：${e.message}", isError = true)
        }
        if (!f.exists()) return ToolResult("路径不存在：$rel", isError = true)

        val kind = if (f.isDirectory) "dir" else "file"
        // 目录的 `length()` 是 0 或文件系统相关的值，报出来会误导，
        // 所以目录只报条目数。
        val sizeLine = if (f.isDirectory) {
            val count = withContext(Dispatchers.IO) { f.listFiles()?.size } ?: 0
            "entries=$count"
        } else {
            "size=${f.length()}"
        }
        return ToolResult(
            "path=$rel\nkind=$kind\n$sizeLine\nlastModified=${f.lastModified()}",
        )
    }
}

/**
 * 移动 / 重命名。
 *
 * ## 为什么必须校验**两个**路径
 *
 * 只校验源路径就能把文件「移出沙箱」—— `to = ../etc/passwd` 会让
 * `renameTo` 写到根目录之外。所以两端都过 [resolveInRoot]。
 *
 * 这也是 Operit 的 `move_file` 必须做到的事（它那边同样有沙箱概念）。
 */
private class MoveFileTool(private val rootDir: File) : Tool {

    override val spec = ToolSpec(
        name = "move_file",
        description = "移动或重命名文件/目录。两端路径都必须在工作区内。",
        parametersJson = """
            {"type":"object","properties":{
              "from":{"type":"string","description":"源相对路径"},
              "to":{"type":"string","description":"目标相对路径"}
            },"required":["from","to"]}
        """.trimIndent(),
        tier = PermissionTier.STANDARD,
        risk = ToolRisk.Moderate,
    )

    override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
        val args = try {
            ToolArguments.parse(argumentsJson)
        } catch (e: dev.praxis.core.tool.ToolArgumentException) {
            return ToolResult("参数错误：${e.message}", isError = true)
        }
        val fromRel = try {
            args.string("from")
        } catch (e: dev.praxis.core.tool.ToolArgumentException) {
            return ToolResult("参数错误：${e.message}", isError = true)
        }
        val toRel = try {
            args.string("to")
        } catch (e: dev.praxis.core.tool.ToolArgumentException) {
            return ToolResult("参数错误：${e.message}", isError = true)
        }
        val from = try {
            resolveInRoot(rootDir, fromRel)
        } catch (e: dev.praxis.core.tool.ToolArgumentException) {
            return ToolResult("参数错误（from）：${e.message}", isError = true)
        }
        val to = try {
            resolveInRoot(rootDir, toRel)
        } catch (e: dev.praxis.core.tool.ToolArgumentException) {
            return ToolResult("参数错误（to）：${e.message}", isError = true)
        }
        if (!from.exists()) return ToolResult("源路径不存在：$fromRel", isError = true)

        val ok = withContext(Dispatchers.IO) {
            // 父目录不存在时 `renameTo` 会静默失败（返回 false 而不是异常），
            // 所以先建父目录，再把失败原因说清。
            to.parentFile?.mkdirs()
            from.renameTo(to)
        }
        return if (ok) {
            ToolResult("已移动：$fromRel → $toRel")
        } else {
            // 跨文件系统时 `renameTo` 会失败。提示用复制+删除，而不是只说「失败」。
            ToolResult(
                "移动失败：$fromRel → $toRel（可能是跨文件系统；可改用 copy_file + delete_file）",
                isError = true,
            )
        }
    }
}

/**
 * 复制文件。
 *
 * 与 [MoveFileTool] 同样必须校验两端路径。
 *
 * 只支持**文件**，不支持目录：递归复制目录要处理符号链接环、
 * 权限保留、部分失败回滚，那是 `cp -r` 的复杂度。
 * 模型需要时可以用带会话的终端工具跑 `cp -r`（那边已经有）。
 */
private class CopyFileTool(private val rootDir: File) : Tool {

    override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
        val args = try {
            ToolArguments.parse(argumentsJson)
        } catch (e: dev.praxis.core.tool.ToolArgumentException) {
            return ToolResult("参数错误：${e.message}", isError = true)
        }
        val fromRel = try {
            args.string("from")
        } catch (e: dev.praxis.core.tool.ToolArgumentException) {
            return ToolResult("参数错误：${e.message}", isError = true)
        }
        val toRel = try {
            args.string("to")
        } catch (e: dev.praxis.core.tool.ToolArgumentException) {
            return ToolResult("参数错误（to）：${e.message}", isError = true)
        }
        val from = try {
            resolveInRoot(rootDir, fromRel)
        } catch (e: dev.praxis.core.tool.ToolArgumentException) {
            return ToolResult("参数错误（from）：${e.message}", isError = true)
        }
        val to = try {
            resolveInRoot(rootDir, toRel)
        } catch (e: dev.praxis.core.tool.ToolArgumentException) {
            return ToolResult("参数错误（to）：${e.message}", isError = true)
        }
        if (!from.exists()) return ToolResult("源文件不存在：$fromRel", isError = true)
        if (from.isDirectory) {
            return ToolResult(
                "不支持复制目录：$fromRel（需要时用终端会话工具跑 cp -r）",
                isError = true,
            )
        }

        val error = withContext(Dispatchers.IO) {
            runCatching {
                to.parentFile?.mkdirs()
                from.copyTo(to, overwrite = true)
            }.exceptionOrNull()
        }
        return if (error == null) {
            ToolResult("已复制：$fromRel → $toRel（${from.length()} 字节）")
        } else {
            ToolResult("复制失败：${error.message}", isError = true)
        }
    }

    override val spec = ToolSpec(
        name = "copy_file",
        description = "复制文件（不支持目录）。目标已存在时覆盖。",
        parametersJson = """
            {"type":"object","properties":{
              "from":{"type":"string","description":"源相对路径"},
              "to":{"type":"string","description":"目标相对路径"}
            },"required":["from","to"]}
        """.trimIndent(),
        tier = PermissionTier.STANDARD,
        risk = ToolRisk.Moderate,
    )
}
