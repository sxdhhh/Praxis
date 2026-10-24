package dev.praxis.core.plugin

/**
 * 四类扩展的统一视图。
 *
 * ## 为什么要有这层「统一」
 *
 * 用户看到的是一个「扩展中心」，里面有：
 *
 * | 类型 | 是什么 | 运行期 |
 * |---|---|---|
 * | [Kind.ToolPackage] | JS 工具包（operit toolpkg） | WebView 里跑脚本，注册工具 |
 * | [Kind.Skill] | Markdown 技能 | 正文拼进系统提示词，**不执行代码** |
 * | [Kind.McpServer] | MCP 服务器 | 外部进程/HTTP，工具列表由服务端给 |
 * | [Kind.SandboxPackage] | 沙盒包（Compose DSL UI + 逻辑） | WebView 里跑，出页面 |
 *
 * 它们在存储上完全不同（zip / 目录 / 配置项），但如果 UI 层也分成四套列表，
 * 用户会以为装了四个东西：明明只是一个扩展中心。
 *
 * 所以：**存储分层，视图统一**。本类就是那个统一视图。
 * 注意它只承载「展示 + 启停」需要的信息，真正的装载各自走各自的实现。
 */
data class ExtensionEntry(
    /** 全局唯一键：`<kind>:<id>`。 */
    val key: String,
    val kind: Kind,
    val id: String,
    val title: String,
    val description: String,
    val version: String,
    val authors: List<String>,
    val enabled: Boolean,
    val origin: PluginOrigin,
    /** 装坏时的原因（致命问题）；正常为 null。 */
    val failure: String? = null,
    /** 该扩展提供的工具数量；**-1 表示不适用**（如 skill / 尚未加载完的包）。 */
    val toolCount: Int = -1,
    /** 图标资源键（在 [ExtensionIcons] 里查字节）。 */
    val iconKey: String? = null,
    /** 子项列表（子包 / 技能文件 / MCP 工具名）。空表示没有子层级。 */
    val children: List<ExtensionEntry> = emptyList(),
) {
    enum class Kind(val storage: String, val label: String) {
        ToolPackage("toolpkg", "工具包"),
        Skill("skill", "技能"),
        McpServer("mcp", "MCP"),
        SandboxPackage("sandbox", "沙盒包"),
        ;

        companion object {
            fun fromStorage(value: String?): Kind =
                entries.firstOrNull { it.storage == value } ?: ToolPackage
        }
    }

    val isUsable: Boolean get() = enabled && failure == null

    /** 副标题：来源 · 版本 · 工具数。空项自动跳过。 */
    fun subtitle(): String = buildList {
        add(origin.describe())
        if (version.isNotBlank()) add("v$version")
        if (toolCount >= 0) add("$toolCount 个工具")
        if (children.isNotEmpty()) add("${children.size} 个子项")
    }.joinToString(" · ")
}

/**
 * 扩展图标缓存。
 *
 * ## 为什么图标单独放
 *
 * 图标是 `ByteArray`，塞进 [ExtensionEntry] 会让它的 equals/hashCode
 * 变成按引用比较 —— Compose 的 `remember(key = entry)` 会因每次重建列表
 * 而全部失效，滚动时每帧重新解码图片。
 *
 * 单独一个 key → bytes 的表，UI 按 key 取，重建列表不影响图片缓存。
 */
class ExtensionIcons {
    private val icons = mutableMapOf<String, ByteArray>()

    fun put(key: String, bytes: ByteArray) {
        if (bytes.isNotEmpty()) icons[key] = bytes
    }

    fun get(key: String?): ByteArray? = key?.let { icons[it] }

    fun clear() = icons.clear()

    val size: Int get() = icons.size
}

/**
 * 把 [InstalledPlugin] 转成统一视图。
 *
 * 子包作为 children：单子包的包**不展开**子层级（列表里一行就够），
 * 多子包的才展开 —— 否则列表里一半的包下面挂着一个只有一个元素的子项。
 */
fun InstalledPlugin.toExtensionEntry(): ExtensionEntry {
    val fatal = problems.filterIsInstance<ManifestProblem.Fatal>().firstOrNull()
    return ExtensionEntry(
        key = "${ExtensionEntry.Kind.ToolPackage.storage}:$id",
        kind = ExtensionEntry.Kind.ToolPackage,
        id = id,
        title = title,
        description = manifest.description.resolveZh(),
        version = manifest.version,
        authors = manifest.authors,
        enabled = enabled,
        origin = origin,
        failure = fatal?.let { "${it.field}：${it.message}" },
        toolCount = -1,
        iconKey = manifest.logo,
        children = if (manifest.hasMultipleSubpackages) {
            manifest.subpackages.map { sub ->
                ExtensionEntry(
                    key = "${ExtensionEntry.Kind.ToolPackage.storage}:$id/${sub.id}",
                    kind = ExtensionEntry.Kind.ToolPackage,
                    id = "$id/${sub.id}",
                    title = sub.displayName.resolveZh().ifBlank { sub.id },
                    description = sub.description.resolveZh(),
                    version = manifest.version,
                    authors = manifest.authors,
                    enabled = isSubpackageEnabled(sub.id),
                    origin = origin,
                    failure = null,
                    toolCount = -1,
                )
            }
        } else {
            emptyList()
        },
    )
}