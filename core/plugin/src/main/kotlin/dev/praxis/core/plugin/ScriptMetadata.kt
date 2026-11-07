package dev.praxis.core.plugin

/**
 * 子包脚本里的 METADATA 块（工具的唯一来源）。
 *
 * ## 出处
 *
 * docs/SCRIPT_DEV_GUIDE.md §3.1。脚本文件开头有一段注释块，形如：
 *
 *     （注释块开头）METADATA
 *     {
 *       "name": "...", "display_name": {...}, "description": "...",
 *       "env": [...], "tools": [
 *         { "name": "search_video", "description": "...",
 *           "parameters": [ { "name": "keyword", "description": "...",
 *                             "type": "string", "required": true } ] }
 *       ]
 *     }
 *     （注释块结尾）
 *
 * 提取表达式照 PackageManager.extractMetadataFromJs（见 [ScriptMetadataParser.METADATA_PATTERN]）。
 *
 * ## 为什么工具在这里，而不在 registerToolPkg()
 *
 * 文档 §3.2.3 写明：工具属于**子包**，注册名是 <subpackage_id>:<tool_name>。
 * 而 registerToolPkg() 只声明 UI 与钩子（§3.2.5 的示例里一个工具都没有）。
 * 之前 Praxis 假设存在一个 registerTool API —— 那个 API 并不存在。
 */
data class ScriptMetadata(
    /** 脚本唯一标识（METADATA.name）。 */
    val name: String,
    val displayName: LocalizedText = LocalizedText.EMPTY,
    val description: LocalizedText = LocalizedText.EMPTY,
    /** 声明依赖的环境变量名。 */
    val env: List<String> = emptyList(),
    val tools: List<ScriptTool> = emptyList(),
)

/** 一个脚本工具。 */
data class ScriptTool(
    /** 函数名；脚本里必须有同名函数（advice 工具除外）。 */
    val name: String,
    val description: LocalizedText = LocalizedText.EMPTY,
    val parameters: List<ScriptToolParameter> = emptyList(),
    /**
     * 纯提示工具：不要求在脚本里有同名函数实现。
     * 文档 §3.1：用于「仅提示/规则说明」场景，不作为可执行工具暴露。
     */
    val advice: Boolean = false,
)

/** 一个工具参数。 */
data class ScriptToolParameter(
    val name: String,
    val description: LocalizedText = LocalizedText.EMPTY,
    val type: String = "string",
    val required: Boolean = false,
    /** 默认值的 JSON 字面量（已序列化）；null 表示没写。 */
    val defaultJson: String? = null,
)

/**
 * METADATA 解析与工具声明生成。
 *
 * 纯函数：不碰文件系统，脚本正文进来、声明出去。这样工具契约能在单测里锁住，
 * 而不必等真机上「模型看不到工具」才发现。
 */
object ScriptMetadataParser {

    /** 与 PackageManager.extractMetadataFromJs 同一表达式。 */
    val METADATA_PATTERN = Regex("/\\*\\s*METADATA\\s*([\\s\\S]*?)\\*/")

    /**
     * @return null 表示脚本里没有 METADATA 块（不是错误：main 脚本就没有）
     */
    fun parse(scriptText: String): Result<ScriptMetadata>? {
        val match = METADATA_PATTERN.find(scriptText) ?: return null
        val body = match.groupValues[1]
        val root = ManifestParser.parse(body).getOrNull()
            ?: return Result.failure(IllegalArgumentException("METADATA 不是合法的 JSON/HJSON 对象"))
        val fields = root.asMapping()

        val name = fields.text("name").trim()
        if (name.isEmpty()) {
            return Result.failure(IllegalArgumentException("METADATA 缺少 name"))
        }

        val tools = fields.listOfMappings("tools").mapNotNull { item ->
            val toolName = item.text("name").trim()
            if (toolName.isEmpty()) return@mapNotNull null
            ScriptTool(
                name = toolName,
                description = LocalizedTextCodec.parse(item["description"], "tools[].description"),
                parameters = item.listOfMappings("parameters").mapNotNull { param ->
                    val paramName = param.text("name").trim()
                    if (paramName.isEmpty()) return@mapNotNull null
                    ScriptToolParameter(
                        name = paramName,
                        description = LocalizedTextCodec.parse(
                            param["description"],
                            "tools[].parameters[].description",
                        ),
                        type = param.text("type").trim().ifEmpty { "string" },
                        required = param["required"]?.asBooleanOrNull() ?: false,
                        defaultJson = param["default"]?.toJsonLiteral(),
                    )
                },
                advice = item["advice"]?.asBooleanOrNull() ?: false,
            )
        }

        return Result.success(
            ScriptMetadata(
                name = name,
                displayName = LocalizedTextCodec.parse(fields["display_name"], "display_name"),
                description = LocalizedTextCodec.parse(fields["description"], "description"),
                env = fields["env"]?.asList()?.map { it.asText().trim() }
                    ?.filter { it.isNotEmpty() }.orEmpty(),
                tools = tools,
            ),
        )
    }

    /**
     * 生成可注册的工具声明。
     *
     * ## 注册名带子包前缀
     *
     * 文档 §3.2.3：脚本中定义的工具会被注册为 <subpackage_id>:<tool_name>。
     * 容器下有多个子包时同名函数不罕见，不加前缀会互相覆盖。
     *
     * @param subpackageId 子包 id（不含容器前缀）
     */
    fun toToolDeclarations(subpackageId: String, metadata: ScriptMetadata): List<PluginToolDeclaration> {
        // ## 名字必须是合法的工具名（2026-11-07 线上事故）
        //
        // Operit 的内部注册名是 <子包>:<工具>，但 OpenAI 兼容接口只接受
        // [A-Za-z0-9_-]，带冒号的会被上游拒掉 —— 表现是整次请求 400
        // 「the tool name is invalid or duplicated」，用户看到的是
        // 「发不出去」，完全不知道是插件工具名的问题。
        //
        // 所以分隔符用下划线，并清洗掉其它非法字符。Praxis 没有对外契约
        // 依赖冒号，不需要保留两种名字。
        val used = mutableSetOf<String>()
        return metadata.tools
            .filter { !it.advice }
            .mapNotNull { tool ->
                val base = sanitizeToolName(subpackageId + "_" + tool.name)
                    ?: return@mapNotNull null
                // 清洗后可能撞名（a:b 与 a_b）。撞了就加序号，
                // 两个工具都还能用，而不是后者静默覆盖前者。
                var name = base
                var suffix = 2
                while (!used.add(name)) {
                    name = base.take(60) + "_" + suffix
                    suffix += 1
                }
                PluginToolDeclaration(
                    name = name,
                    description = tool.description.resolve().ifBlank { tool.name },
                    parametersJson = buildParametersSchema(tool.parameters),
                    entryFunction = tool.name,
                )
            }
    }

    /**
     * 清洗成合法的工具名：只留字母、数字、下划线、连字符，最长 64。
     *
     * @return null 表示清洗后什么都不剩（调用方据此丢弃这个工具）
     */
    fun sanitizeToolName(raw: String): String? {
        val mapped = raw.map { ch ->
            if (ch.isLetterOrDigit() || ch == '_' || ch == '-') ch else '_'
        }.joinToString("")
        val trimmed = mapped.trim('_').take(64)
        return trimmed.ifEmpty { null }
    }

    /**
     * 参数列表 → JSON Schema。
     *
     * 照 OpenAIProvider.buildSchemaFromStructured：required 恒存在（空也要有），
     * 因为 OpenAI 兼容的校验器会检查它的类型。
     */
    fun buildParametersSchema(parameters: List<ScriptToolParameter>): String {
        val required = parameters.filter { it.required }.map { it.name }
        return buildString {
            append("{\"type\":\"object\",\"properties\":{")
            parameters.forEachIndexed { index, param ->
                if (index > 0) append(',')
                append(quote(param.name))
                append(":{\"type\":")
                append(quote(param.type))
                append(",\"description\":")
                append(quote(param.description.resolve()))
                if (param.defaultJson != null) {
                    append(",\"default\":")
                    append(param.defaultJson)
                }
                append('}')
            }
            append("},\"required\":[")
            required.forEachIndexed { index, name ->
                if (index > 0) append(',')
                append(quote(name))
            }
            append("]}")
        }
    }

    private fun quote(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch < ' ') out.append(' ') else out.append(ch)
            }
        }
        out.append('"')
        return out.toString()
    }

    private fun Map<String, ManifestValue>.text(key: String): String =
        this[key]?.asText().orEmpty()

    private fun Map<String, ManifestValue>.listOfMappings(key: String): List<Map<String, ManifestValue>> {
        val value = this[key] ?: return emptyList()
        return when (value) {
            is ManifestValue.Mapping -> listOf(value.entries)
            is ManifestValue.Items -> value.values.mapNotNull { (it as? ManifestValue.Mapping)?.entries }
            else -> emptyList()
        }
    }

    /**
     * 只把标量转成 JSON 字面量。
     *
     * 结构体（Mapping / Items）给 null —— schema 里就没有 default，
     * 而不是塞一个猜出来的字符串。
     */
    private fun ManifestValue.toJsonLiteral(): String? = when (this) {
        is ManifestValue.Text -> quote(value)
        is ManifestValue.Number -> text.trim().toDoubleOrNull()?.let { text.trim() }
        is ManifestValue.Flag -> if (value) "true" else "false"
        ManifestValue.Null -> "null"
        is ManifestValue.Mapping -> null
        is ManifestValue.Items -> null
    }
}
