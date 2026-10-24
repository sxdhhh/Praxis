package dev.praxis.core.plugin

/**
 * 内存版 [PluginStorage]：单测用。
 *
 * ## 为什么单测需要它而不是造临时目录
 *
 * 安装逻辑要验证的是「预检 → 落盘 → 写来源 → 回读」这条链，
 * 而不是文件系统本身。用内存实现后：
 *
 * - 测试不依赖磁盘状态，可并发跑；
 * - 能精确构造「半装状态」「清单指向不存在文件」这类难复现的场景；
 * - 失败时不需要清理残留目录。
 *
 * ## 它刻意不实现真正的文件语义
 *
 * `installFrom` 只做「把预置内容放进 map」，不做 zip 解压。
 * 需要测 zip 解析时用 `InMemoryPluginArchive` 直接喂数据给
 * `PluginArchiveReader`，那才是解析逻辑的测试点。
 */
class InMemoryPluginStorage(
    override val packagesRoot: String = "/mem/packages",
) : PluginStorage {

    /** 包 id → 包内容（相对路径 → 文本）。 */
    private val contents = LinkedHashMap<String, MutableMap<String, String>>()
    private val enabled = mutableMapOf<String, Boolean>()
    private val disabledSubs = mutableMapOf<String, MutableSet<String>>()
    private val origins = mutableMapOf<String, PluginOrigin>()

    /**
     * 预置一个包（模拟「已安装」）。
     *
     * @param enabled 是否启用；null 表示不写显式记录（走清单默认）
     */
    fun seed(
        manifestText: String,
        files: Map<String, String> = emptyMap(),
        enabled: Boolean? = null,
        origin: PluginOrigin? = null,
    ) {
        val id = extractId(manifestText) ?: error("预置包的清单里没有 toolpkg_id")
        val all = mutableMapOf("manifest.hjson" to manifestText)
        all.putAll(files)
        contents[id] = all
        enabled?.let { this.enabled[id] = it }
        origin?.let { origins[id] = it }
    }

    override fun listInstalledIds(): List<String> = contents.keys.toList()

    /**
     * 直接写入包内容，**不做 id 校验**。
     *
     * 用于模拟「用户手动塞进一个坏包」或「清单被外部改坏」——
     * 那正是最需要验证「坏包仍可列出、仍可删除」的场景。
     * [seed] 会先解析清单取 id，坏清单在那一步就失败了，走不到这里。
     */
    fun putRaw(packageId: String, files: Map<String, String>) {
        contents[packageId] = files.toMutableMap()
    }

    override fun readManifestText(packageId: String): String? =
        contents[packageId]?.get("manifest.hjson")

    override fun openArchive(packageId: String): PluginArchive? {
        val files = contents[packageId] ?: return null
        return InMemoryPluginArchive.ofText(files)
    }

    /**
     * 安装：从 [sourcePath] 里的预置内容复制到包目录。
     *
     * 测试用的约定：`sourcePath` 是 `mem://<seedKey>`，指向 [seeds]。
     */
    override fun installFrom(sourcePath: String, expectedId: String): Result<String> = runCatching {
        val files = seeds[sourcePath] ?: error("测试未预置 sourcePath=$sourcePath")
        require(files.containsKey("manifest.hjson")) { "预置内容里没有 manifest.hjson" }
        contents[expectedId] = files.toMutableMap()
        expectedId
    }

    override fun remove(packageId: String): Boolean {
        val removed = contents.remove(packageId) != null
        enabled.remove(packageId)
        disabledSubs.remove(packageId)
        origins.remove(packageId)
        return removed
    }

    override fun exists(packageId: String): Boolean = contents.containsKey(packageId)

    override fun isEnabled(packageId: String): Boolean =
        enabled[packageId] ?: true

    override fun setEnabled(packageId: String, enabled: Boolean) {
        this.enabled[packageId] = enabled
    }

    override fun disabledSubpackages(packageId: String): Set<String> =
        disabledSubs[packageId]?.toSet().orEmpty()

    override fun setSubpackageEnabled(packageId: String, subpackageId: String, enabled: Boolean) {
        val set = disabledSubs.getOrPut(packageId) { mutableSetOf() }
        if (enabled) set.remove(subpackageId) else set.add(subpackageId)
    }

    override fun readOrigin(packageId: String): PluginOrigin? = origins[packageId]

    override fun writeOrigin(packageId: String, origin: PluginOrigin) {
        origins[packageId] = origin
    }

    // ── 安装源（模拟外部 zip/目录）────────────────────────────

    private val seeds = mutableMapOf<String, Map<String, String>>()

    /** 预置一个「安装源」。之后用 `install(sourcePath = "<key>")` 即可。 */
    fun seedSource(key: String, files: Map<String, String>) {
        seeds["mem://$key"] = files
    }

    /** 便捷：把「安装源」写成标准 key。 */
    fun sourcePathOf(key: String): String = "mem://$key"

    private fun extractId(manifestText: String): String? =
        ManifestParser.parse(manifestText).getOrNull()
            ?.asMapping()?.get("toolpkg_id")?.asText()?.trim()
            ?.takeIf { it.isNotEmpty() }
}