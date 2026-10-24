package dev.praxis.core.plugin

/**
 * 扩展中心的状态快照。
 *
 * ## 为什么一个类装四类扩展
 *
 * 用户看到的是一张列表（工具包 / 技能 / MCP / 沙盒包混排）。如果把四类
 * 分成四个 StateFlow，UI 层要 `combine` 四次，而且**排序**会变得很别扭：
 * 「按启用优先、再按名字」需要跨四类比较。
 *
 * 所以在 ViewModel 层合并成一个快照。四类各自的读写仍然分开
 * （见 [ExtensionSources]）。
 */
data class ExtensionSnapshot(
    val entries: List<ExtensionEntry> = emptyList(),
    /** 加载顺序问题（依赖缺失 / 版本不满足 / 环）。 */
    val loadOrder: PluginLoadOrder.Ordering? = null,
    val loading: Boolean = true,
    /** 一次性提示。消费后置空。 */
    val message: String? = null,
) {
    val isEmpty: Boolean get() = entries.isEmpty()

    /** 按类型分组，便于 UI 做分区（而不是一长条混排）。 */
    fun grouped(): Map<ExtensionEntry.Kind, List<ExtensionEntry>> =
        entries.groupBy { it.kind }

    /** 某个包是否已安装（市场列表页用于显示「已安装」）。 */
    fun installedVersionOf(pluginId: String): String? =
        entries.firstOrNull {
            it.kind == ExtensionEntry.Kind.ToolPackage && it.id == pluginId
        }?.version

    /** 可用的工具总数（四类里有 toolCount 的那些）。 */
    val totalToolCount: Int
        get() = entries.sumOf { if (it.toolCount > 0) it.toolCount else 0 }
}

/**
 * 四类扩展的数据来源。
 *
 * ## 为什么用接口聚合而不是一个 God Repository
 *
 * 四类扩展的来源完全不同（zip 目录 / Markdown 目录 / 网络配置 / 又是 zip），
 * 生命周期也不同（MCP 需要连接、其它不需要）。合成一个大类会让
 * 「只想用 skill 的地方被迫拖上网络与 zip 依赖」。
 *
 * 这里只定义**聚合读取**，各自的具体操作由持有者直接调用自己的实现。
 */
interface ExtensionSources {

    /** 已安装的工具包 / 沙盒包。 */
    suspend fun installedPlugins(): List<InstalledPlugin>

    /**
     * 技能（全部，不带过滤）。
     *
     * ## 为什么不由本方法做启停过滤
     *
     * 启停状态存在另外的键值里（见 `SkillRegistry` 的启用集合）。
     * 如果这里收一个 `enabledNames` 参数，就会有两种错误实现：
     *
     * - 数据源自己过滤：调用方拿不到「已禁用但存在」的技能，
     *   扩展中心里就永远显示不出「你装了这个技能但关掉了」；
     * - 数据源不过滤、只是忽略参数：参数成了摆设，后人会以为它有用。
     *
     * 所以：数据源只负责**列出全部**，过滤由 `buildExtensionSnapshot` 做
     * （它拿到 `skillEnabled` 集合，能同时产出「启用/未启用」两态）。
     */
    suspend fun skills(): List<Skill>

    /** MCP 服务器配置。 */
    suspend fun mcpServers(): List<McpServerConfig>

    /** MCP 已连上的工具数（key = 服务器名）。未连接的服务器不出现。 */
    suspend fun mcpToolCounts(): Map<String, Int>
}

/**
 * 把四条来源合并成一份快照。
 *
 * 纯函数（输入全部显式传入），因此可以纯 JVM 单测排序与分组。
 *
 * ## 排序规则
 *
 * 1. **可用的在前**（`isUsable`）：坏的沉到底部，不干扰正常浏览
 * 2. 同类内按标题
 * 3. 类型顺序：工具包 → 沙盒包 → MCP → 技能
 *
 * 为什么类型顺序固定而不是按数量：用户第一眼该看到「能给我加能力的」
 * （工具包），技能是纯文本、放最后。
 */
fun buildExtensionSnapshot(
    installedPlugins: List<InstalledPlugin>,
    skills: List<Skill>,
    skillEnabled: Set<String>,
    mcpServers: List<McpServerConfig>,
    mcpToolCounts: Map<String, Int>,
    loadOrder: PluginLoadOrder.Ordering?,
    loading: Boolean = false,
    message: String? = null,
): ExtensionSnapshot {
    val pluginEntries = installedPlugins.map { plugin ->
        val tools = plugin.manifest.subpackages
            .filter { plugin.isSubpackageEnabled(it.id) }
            .size
        plugin.toExtensionEntry().copy(
            // 工具数用「启用中的子包数」近似 —— 真实工具数要等运行时注册后才知道。
            // 用 -1 而不是 0：0 会被显示成「0 个工具」，让用户以为包是空的。
            toolCount = if (tools > 0) tools else -1,
        )
    }

    val skillEntries = skills.map { it.toExtensionEntry(it.name in skillEnabled) }

    val mcpEntries = mcpServers.map { config ->
        config.toExtensionEntry(toolCount = mcpToolCounts[config.name] ?: -1)
    }

    // ## 沙盒包：同一个包的「另一个视角」
    //
    // Operit 里 UI 与工具是同一个包装载出来的两种注册结果。我们把它们
    // 在扩展中心拆成两个分组，因为**用户的需求不同**：
    // 「我想让 AI 多会做一件事」（工具）与「我想点开一个界面」（沙盒）是两件事。
    //
    // 代价是同一个包名出现两次。这是有意为之，但必须在 UI 上标明
    // 「来自同一个包」，否则用户会以为装了两份、然后删掉试一次 —— 全没了。
    //
    // 所以沙盒条目的 key 用 `sandbox:<id>` 区分，description 前缀说明来源。
    val sandboxEntries = installedPlugins
        .filter { it.manifest.providesUi() }
        .map { plugin ->
            val baseDescription = plugin.manifest.description.resolveZh()
            ExtensionEntry(
                key = "${ExtensionEntry.Kind.SandboxPackage.storage}:${plugin.id}",
                kind = ExtensionEntry.Kind.SandboxPackage,
                id = plugin.id,
                title = plugin.title,
                description = if (baseDescription.isBlank()) {
                    "与工具包「${plugin.title}」是同一个包，提供可交互界面"
                } else {
                    "与工具包「${plugin.title}」是同一个包 · $baseDescription"
                },
                version = plugin.manifest.version,
                authors = plugin.manifest.authors,
                enabled = plugin.enabled,
                origin = plugin.origin,
                failure = plugin.failureText,
                toolCount = -1,
                iconKey = plugin.manifest.logo,
            )
        }

    val kindOrder = listOf(
        ExtensionEntry.Kind.ToolPackage,
        ExtensionEntry.Kind.SandboxPackage,
        ExtensionEntry.Kind.McpServer,
        ExtensionEntry.Kind.Skill,
    )

    val entries = (pluginEntries + sandboxEntries + mcpEntries + skillEntries)
        .sortedWith(
            // 可用优先 → 类型顺序 → 标题
            compareByDescending<ExtensionEntry> { it.isUsable }
                .thenBy { kindOrder.indexOf(it.kind) }
                .thenBy { it.title.lowercase() },
        )

    return ExtensionSnapshot(
        entries = entries,
        loadOrder = loadOrder,
        loading = loading,
        message = message,
    )
}