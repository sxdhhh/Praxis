package dev.praxis.core.plugin

/**
 * 把解析后的 [ManifestValue] 树映射成 [PluginManifest]。
 *
 * ## 设计原则：能装的都装上，装不了的说清为什么
 *
 * 清单来自第三方。这里区分两类问题：
 *
 * | 类型 | 例子 | 处理 |
 * |---|---|---|
 * | **致命** | 缺 `toolpkg_id`、`main` 指向不存在的文件 | 整包失败，[ManifestProblem.Fatal] |
 * | **可容忍** | 某个 `resources[3].mime` 写成数字 | 记一条 [ManifestProblem.Warning]，继续 |
 *
 * 不这样做的话，一个字段的怪写法会让用户完全装不上包，
 * 而错误信息只有一句「解析失败」—— 用户无法自查，我们无法复现。
 */
object ManifestMapper {

    /** 映射结果。 */
    data class ManifestMapping(
        val manifest: PluginManifest,
        /** 所有问题，按发现顺序。含致命项时 [manifest] 的 id 仍可用（便于报错定位）。 */
        val problems: List<ManifestProblem> = emptyList(),
    ) {
        val fatal: ManifestProblem.Fatal? get() = problems.filterIsInstance<ManifestProblem.Fatal>().firstOrNull()
        val isUsable: Boolean get() = fatal == null

        val warnings: List<ManifestProblem.Warning>
            get() = problems.filterIsInstance<ManifestProblem.Warning>()
    }

    /**
     * @param root 清单根（必须是 Mapping）
     * @param entryExists 判断清单里声明的相对路径是否真实存在于包内
     */
    fun map(
        root: ManifestValue,
        entryExists: (String) -> Boolean = { true },
    ): ManifestMapping {
        val fields = root.asMapping()
        val problems = mutableListOf<ManifestProblem>()

        if (fields.isEmpty()) {
            problems += ManifestProblem.Fatal(
                field = "manifest",
                message = "清单为空或根节点不是对象",
            )
            return ManifestMapping(PluginManifest(id = ""), problems)
        }

        // ── 必填：id ──────────────────────────────────────────
        val id = fields.text("toolpkg_id").trim()
        if (id.isEmpty()) {
            problems += ManifestProblem.Fatal(
                field = "toolpkg_id",
                message = "缺少 toolpkg_id（包的唯一标识，用于依赖与市场更新）",
            )
        }

        // ── 必填：main ────────────────────────────────────────
        val main = fields.text("main").trim()
        if (main.isEmpty()) {
            problems += ManifestProblem.Fatal(
                field = "main",
                message = "缺少 main（主入口脚本路径，没有它就无法注册任何工具）",
            )
        } else if (!entryExists(main)) {
            problems += ManifestProblem.Fatal(
                field = "main",
                message = "main 指向的文件 '${main}' 不存在于包内",
            )
        }

        // ── API 版本 ──────────────────────────────────────────
        val apiVersion = fields.text("api_version").trim().ifEmpty {
            PluginManifest.SUPPORTED_API_VERSION
        }
        PluginApiCompatibility.check(apiVersion)?.let { reason ->
            problems += ManifestProblem.Fatal(field = "api_version", message = reason)
        }

        // ── 可选字段：写错只警告 ──────────────────────────────
        val subpackages = fields.listOfMappings("subpackages").mapIndexedNotNull { index, item ->
            val subId = item.text("id").trim()
            val entry = item.text("entry").trim()
            when {
                subId.isEmpty() -> {
                    problems += ManifestProblem.Warning(
                        field = "subpackages[$index].id",
                        message = "子包缺少 id，已跳过",
                    )
                    null
                }
                entry.isEmpty() -> {
                    problems += ManifestProblem.Warning(
                        field = "subpackages[$index].entry",
                        message = "子包 '$subId' 缺少 entry，已跳过",
                    )
                    null
                }
                !entryExists(entry) -> {
                    // 子包入口不存在不算致命：整个容器仍可装载，
                    // 只是这个子包的工具注册会失败。把它标出来让用户知道。
                    problems += ManifestProblem.Warning(
                        field = "subpackages[$index].entry",
                        message = "子包 '$subId' 的入口 '$entry' 不存在，该子包将不可用",
                    )
                    null
                }
                else -> PluginSubpackage(
                    id = subId,
                    entry = entry,
                    displayName = LocalizedTextCodec.parse(
                        item["display_name"],
                        "subpackages[$index].display_name",
                    ),
                    description = LocalizedTextCodec.parse(
                        item["description"],
                        "subpackages[$index].description",
                    ),
                    enabledByDefault = item["enabled_by_default"]?.asBooleanOrNull() ?: true,
                )
            }
        }

        val requires = fields.listOfMappings("requires").mapIndexedNotNull { index, item ->
            val reqId = item.text("id").trim()
            if (reqId.isEmpty()) {
                problems += ManifestProblem.Warning(
                    field = "requires[$index].id",
                    message = "依赖项缺少 id，已跳过",
                )
                null
            } else {
                PluginRequirement(
                    id = reqId,
                    description = item.text("description").trim(),
                    minVersion = item.text("min_version").trim().takeIf { it.isNotEmpty() },
                    maxVersion = item.text("max_version").trim().takeIf { it.isNotEmpty() },
                )
            }
        }

        val resources = fields.listOfMappings("resources").mapIndexedNotNull { index, item ->
            val key = item.text("key").trim()
            val path = item.text("path").trim()
            if (key.isEmpty() || path.isEmpty()) {
                problems += ManifestProblem.Warning(
                    field = "resources[$index]",
                    message = "资源缺少 key 或 path，已跳过",
                )
                null
            } else {
                PluginResource(key = key, path = path, mime = item.text("mime").trim())
            }
        }

        val wasmModules = fields.listOfMappings("wasm_modules").mapIndexedNotNull { index, item ->
            val moduleId = item.text("id").trim()
            val path = item.text("path").trim()
            if (moduleId.isEmpty() || path.isEmpty()) {
                problems += ManifestProblem.Warning(
                    field = "wasm_modules[$index]",
                    message = "WASM 模块缺少 id 或 path，已跳过",
                )
                null
            } else {
                PluginWasmModule(
                    id = moduleId,
                    path = path,
                    exports = item["exports"]?.asList()?.map { it.asText() }
                        ?.filter { it.isNotBlank() }.orEmpty(),
                    sourceLanguage = item.text("source_language").trim()
                        .ifEmpty { "assemblyscript" },
                    abi = item.text("abi").trim().ifEmpty { "assemblyscript" },
                )
            }
        }

        val manifest = PluginManifest(
            schemaVersion = fields.text("schema_version").trim().toIntOrNull() ?: 1,
            id = id,
            version = fields.text("version").trim(),
            apiVersion = apiVersion,
            requires = requires,
            main = main,
            displayName = LocalizedTextCodec.parse(fields["display_name"], "display_name"),
            description = LocalizedTextCodec.parse(fields["description"], "description"),
            logo = fields.text("logo").trim().takeIf { it.isNotEmpty() },
            // author 允许写成 "a" 或 ["a","b"]（operit 的 StringOrStringListSerializer 就是为此）。
            authors = fields["author"]?.asList()
                ?.map { it.asText().trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty(),
            enabledByDefault = fields["enabled_by_default"]?.asBooleanOrNull() ?: true,
            subpackages = subpackages,
            resources = resources,
            wasmModules = wasmModules,
        )

        return ManifestMapping(manifest, problems)
    }

    // ── 便利读取 ─────────────────────────────────────────────

    private fun Map<String, ManifestValue>.text(key: String): String =
        this[key]?.asText().orEmpty()

    /**
     * 取对象数组。
     *
     * 兼容两种写法：标准 `[ {…}, {…} ]`，以及「单个对象不带数组」
     * （不少手写清单会图省事只写一个对象）。后者自动包成单元素列表 ——
     * 对 `subpackages` 来说这非常常见（只有一个子包）。
     */
    private fun Map<String, ManifestValue>.listOfMappings(
        key: String,
    ): List<Map<String, ManifestValue>> {
        val value = this[key] ?: return emptyList()
        return when (value) {
            is ManifestValue.Mapping -> listOf(value.entries)
            is ManifestValue.Items -> value.values
                .mapNotNull { (it as? ManifestValue.Mapping)?.entries }
            else -> emptyList()
        }
    }
}

/** 清单问题。 */
sealed interface ManifestProblem {

    val field: String
    val message: String

    /** 致命：整包装不上。 */
    data class Fatal(
        override val field: String,
        override val message: String,
    ) : ManifestProblem

    /** 警告：装得上，但某个字段被忽略或某个子包不可用。 */
    data class Warning(
        override val field: String,
        override val message: String,
    ) : ManifestProblem
}