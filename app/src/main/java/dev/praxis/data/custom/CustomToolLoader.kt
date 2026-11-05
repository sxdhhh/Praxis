package dev.praxis.data.custom

import dev.praxis.core.tool.custom.CustomToolDefinition
import dev.praxis.core.tool.custom.CustomToolParam
import dev.praxis.core.tool.custom.CustomToolStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 从 `.praxis/tools/<name>.tool` 文件加载自定义工具定义。
 *
 * ## 为什么用纯文本而不是 JSON
 *
 * 与技能文件（SKILL.md）同一条取舍：用户/AI 手写的配置要**能读能改**，
 * 且不引 JSON 库。
 *
 * ## 格式
 *
 * ```
 * name: backup_project
 * description: 把项目打包到备份目录
 * side-effects: true
 * param: target = string, 必填, 要备份的目录
 * step: linux_exec cmd=tar -czf /tmp/b.tgz {{target}}
 * step: copy_file from=/tmp/b.tgz to=/backup/
 * ```
 *
 * 每行一个指令。`step:` 的键值用空格分隔（值里可以有空格 ——
 * 只有**第一个** `=` 之前的才是键）。
 */
object CustomToolFileParser {

    fun parse(text: String): CustomToolDefinition? {
        var name = ""
        var description = ""
        var sideEffects = false
        val params = LinkedHashMap<String, CustomToolParam>()
        val steps = mutableListOf<CustomToolStep>()

        text.replace("\r\n", "\n").lines().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            when {
                line.startsWith("name:") -> name = line.removePrefix("name:").trim()
                line.startsWith("description:") ->
                    description = line.removePrefix("description:").trim()
                line.startsWith("side-effects:") ->
                    sideEffects = line.removePrefix("side-effects:").trim() == "true"
                line.startsWith("param:") -> {
                    val body = line.removePrefix("param:").trim()
                    val eq = body.indexOf('=')
                    if (eq > 0) {
                        val key = body.substring(0, eq).trim()
                        val rest = body.substring(eq + 1).trim()
                        val parts = rest.split(',').map { it.trim() }
                        params[key] = CustomToolParam(
                            type = parts.getOrNull(0)?.ifBlank { "string" } ?: "string",
                            required = parts.getOrNull(1)?.contains("必填") ?: true,
                            description = parts.getOrNull(2) ?: "",
                        )
                    }
                }
                line.startsWith("step:") -> {
                    val body = line.removePrefix("step:").trim()
                    val firstSpace = body.indexOf(' ')
                    val op = if (firstSpace < 0) body else body.substring(0, firstSpace)
                    val argsText = if (firstSpace < 0) "" else body.substring(firstSpace + 1)
                    val args = LinkedHashMap<String, String>()
                    // 键值以空格分隔；每个片段只按**第一个** `=` 切，
                    // 值里因此可以包含 `=`（比如 sed 表达式）。
                    argsText.split(' ').filter { it.isNotBlank() }.forEach { chunk ->
                        val eq = chunk.indexOf('=')
                        if (eq > 0) {
                            args[chunk.substring(0, eq)] = chunk.substring(eq + 1)
                        }
                    }
                    if (op.isNotBlank()) steps += CustomToolStep(op = op, args = args)
                }
            }
        }

        if (name.isBlank()) return null
        return CustomToolDefinition(
            name = name,
            description = description,
            params = params,
            steps = steps,
            hasSideEffects = sideEffects,
        )
    }
}

/** 扫描目录下的所有 `.tool` 文件。 */
class CustomToolSource(private val dir: File) {

    /**
     * 列出全部定义；解析失败的文件被跳过并在第二个返回值里说明原因。
     *
     * ## 为什么不递归
     *
     * 与技能扫描同一条纪律：递归会把备份目录、示例目录里的 `.tool`
     * 也扫进来，那些不该被装载。
     */
    suspend fun list(): Pair<List<CustomToolDefinition>, List<String>> =
        withContext(Dispatchers.IO) {
            if (!dir.isDirectory) return@withContext emptyList<CustomToolDefinition>() to
                emptyList<String>()
            val defs = mutableListOf<CustomToolDefinition>()
            val skipped = mutableListOf<String>()
            dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".tool") }
                ?.sortedBy { it.name }
                ?.forEach { file ->
                    val parsed = runCatching { CustomToolFileParser.parse(file.readText()) }
                        .getOrNull()
                    if (parsed == null) {
                        skipped += file.name + "：解析失败或缺少 name"
                    } else {
                        defs += parsed
                    }
                }
            defs to skipped
        };
}
