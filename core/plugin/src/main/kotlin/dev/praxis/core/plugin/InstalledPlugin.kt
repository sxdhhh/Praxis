package dev.praxis.core.plugin

/**
 * 一个已安装的插件（工具包）。
 *
 * ## 与「清单」的区别
 *
 * [PluginManifest] 是**包里写着什么**；本类是**本机装成了什么**：
 * 装在哪、启没启用、哪些子包开着、从哪个市场来的、有没有装坏。
 *
 * 两者分开的价值：清单是第三方文件（可能随时被作者改得不兼容），
 * 而安装态是本机事实。混成一个类后，「用户关掉了这个包」
 * 会被下次读清单时覆盖成默认启用 —— 这是最常见的「设置不生效」来源。
 */
data class InstalledPlugin(
    val manifest: PluginManifest,
    /** 包在磁盘上的根目录绝对路径。 */
    val installPath: String,
    /** 是否已启用（用户总开关）。 */
    val enabled: Boolean,
    /** 有哪个子包被关掉（id 集合）。空集表示全按清单默认。 */
    val disabledSubpackageIds: Set<String> = emptySet(),
    /** 来源（市场 / 本地导入 / 内置）。 */
    val origin: PluginOrigin = PluginOrigin.Local,
    /** 安装时间。 */
    val installedAt: Long = 0L,
    /** 加载期遇到的问题（致命项会阻止加载，但仍显示在列表里让用户能删掉）。 */
    val problems: List<ManifestProblem> = emptyList(),
) {
    val id: String get() = manifest.id

    val title: String get() = manifest.title

    /** 是否真的可用（启用 + 无致命问题）。 */
    val isActive: Boolean get() = enabled && problems.none { it is ManifestProblem.Fatal }

    /**
     * 第一个致命问题的可读原因；没有问题时为 null。
     *
     * 放在领域模型而不是 UI：详情页、列表、日志三处都要用，
     * 各写一遍必然出现「列表说 A 坏了、详情说没事」。
     */
    val failureText: String?
        get() = (problems.firstOrNull { it is ManifestProblem.Fatal } as? ManifestProblem.Fatal)
            ?.let { "${it.field}：${it.message}" }

    /** 警告文本（装得上但有问题的那类）。 */
    val warningsText: List<String>
        get() = problems.filterIsInstance<ManifestProblem.Warning>()
            .map { "${it.field}：${it.message}" }

    /** 某个子包现在是否启用。 */
    fun isSubpackageEnabled(subpackageId: String): Boolean {
        if (!enabled) return false
        if (subpackageId in disabledSubpackageIds) return false
        val declared = manifest.subpackages.firstOrNull { it.id == subpackageId }
        return declared?.enabledByDefault ?: true
    }

    /** 当前应加载的子包（启用中的）。 */
    val activeSubpackages: List<PluginSubpackage>
        get() = if (!enabled) {
            emptyList()
        } else {
            manifest.subpackages.filter { isSubpackageEnabled(it.id) }
        }
}

/**
 * 插件来源。
 *
 * ## 为什么要记来源
 *
 * 三个真实需求：
 *
 * 1. **更新**：只有「从市场装的」才能按市场 id 检查新版本；
 *    本地导入的没有远端对应物，检查了也只会得到 404。
 * 2. **删除确认文案**：「内置包删掉后重启会恢复」与
 *    「本地导入的删了就没了」必须说清，否则用户会反复删同一个包。
 * 3. **审计**：「这个包是从哪来的」在用户报告问题时是第一个要问的。
 */
sealed interface PluginOrigin {

    /** 随 APK 打包的内置包。删掉后重启会重新出现。 */
    data object BuiltIn : PluginOrigin

    /** 用户从本地文件（.toolpkg / 目录）导入。 */
    data object Local : PluginOrigin

    /**
     * 从在线市场安装。
     *
     * [marketId] 是市场的条目 id（不是 toolpkg id）——
     * 两者在市场上不一定相同（作者可以改名后重新发布）。
     * [version] 记录**安装时的版本**，用于「有更新」判断。
     */
    data class Market(
        val marketId: String,
        val version: String,
        val installedFrom: String = "",
    ) : PluginOrigin

    /** 开发调试：从工作目录直连（不复制）。 */
    data class Development(val note: String = "") : PluginOrigin

    /** 给 UI 的一句话来源说明。 */
    fun describe(): String = when (this) {
        BuiltIn -> "内置"
        Local -> "本地导入"
        is Market -> "在线市场" + if (version.isNotBlank()) "（$version）" else ""
        is Development -> "开发调试"
    }

    /** 能否检查更新。 */
    val canCheckUpdates: Boolean get() = this is Market
}