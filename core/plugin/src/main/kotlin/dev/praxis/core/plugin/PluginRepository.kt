package dev.praxis.core.plugin

/**
 * 插件安装的持久化端口。
 *
 * ## 为什么要一个端口而不是直接操作文件
 *
 * 安装过程涉及三类完全不同的数据：
 *
 * | 数据 | 存在哪 |
 * |---|---|
 * | 包文件本身 | 磁盘目录（zip 解压后） |
 * | 启停状态 | 偏好设置（少量键值） |
 * | 源标记 | 包内 metadata 文件 |
 *
 * 三者的事务性没法统一（文件系统没有事务），所以由实现方决定策略：
 * 先落盘、再写状态、最后写标记 —— 中途崩溃最坏是「装了但没启用」，
 * 用户点一下启用即可，不会出现「状态说启用了但文件不在」的崩溃路径。
 *
 * 这个接口也是单测的注入点：内存实现即可验证安装逻辑，
 * 不需要真实文件系统。
 */
interface PluginStorage {

    /** 已安装包目录的绝对路径（包目录是它的子目录）。 */
    val packagesRoot: String

    /** 列出所有已安装包的目录名（= 包 id，已转义为安全文件名）。 */
    fun listInstalledIds(): List<String>

    /** 读某个包目录下的清单文本。null 表示目录不存在或没有清单。 */
    fun readManifestText(packageId: String): String?

    /** 打开某个包的归档读取器（目录实现：按目录读；zip 实现：按 zip 读）。 */
    fun openArchive(packageId: String): PluginArchive?

    /**
     * 从 [sourcePath] 安装（zip 文件或目录），落到 `<packagesRoot>/<packageId>`。
     *
     * ## 必须先解到临时目录再原子替换
     *
     * 直接往目标目录解压的话，中途失败（磁盘满、zip 损坏、取消）会留下
     * **半装的包**：清单在、脚本缺一半。下次启动加载它会抛各种奇怪的
     * `undefined`，而用户看到的是「这个包坏了」却删不干净（文件被占）。
     *
     * 正确顺序：解到 `.staging-<id>` → 校验清单能读 → 删旧目录（改名成
     * `.old-<id>`）→ 新目录改名到目标 → 删 `.old`。
     *
     * @return 落盘后的包 id
     */
    fun installFrom(sourcePath: String, expectedId: String): Result<String>

    /** 删除包目录。@return true 表示确实删掉了 */
    fun remove(packageId: String): Boolean

    /** 包目录是否真实存在。 */
    fun exists(packageId: String): Boolean

    // ── 启停状态 ──────────────────────────────────────────────

    fun isEnabled(packageId: String): Boolean

    fun setEnabled(packageId: String, enabled: Boolean)

    /** 被单独关掉的子包 id。 */
    fun disabledSubpackages(packageId: String): Set<String>

    fun setSubpackageEnabled(packageId: String, subpackageId: String, enabled: Boolean)

    // ── 来源 ──────────────────────────────────────────────────

    fun readOrigin(packageId: String): PluginOrigin?

    fun writeOrigin(packageId: String, origin: PluginOrigin)
}

/**
 * 插件仓库：读写已安装包的**状态视图**。
 *
 * ## 与 [PluginStorage] 的分工
 *
 * Storage 管「文件与键值」，Repository 管「语义」：
 *
 * - 扫目录 → 读清单 → 合并启停状态 → 产出 `List<InstalledPlugin>`
 * - 安装（调 Storage.installFrom + 写来源）
 * - 卸载（保护内置包、清关联状态）
 * - 更新检查（比对市场版本与本地版本）
 *
 * 这样上层（ViewModel / 加载器）只需要 `plugins: StateFlow<List<InstalledPlugin>>`，
 * 不用关心磁盘长什么样。
 */
class PluginRepository(
    private val storage: PluginStorage,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * 扫描所有已安装包。
     *
     * ## 为什么扫描不做缓存
     *
     * 包数量是「几十」量级，读几十个小清单在后台线程是毫秒级。
     * 加缓存就带来「缓存什么时候失效」的问题：用户手动复制了一个包进去、
     * 或从市场装完但没走我们的 install 路径，缓存就会给出过期列表。
     * 宁可每次扫。
     *
     * ## 单个包坏了不影响其它包
     *
     * 每个包独立 `runCatching`。一个包的清单是坏的语法（比如作者用 Tab
     * 缩进但又混了奇怪字符），不能让整个扩展中心打不开 ——
     * 那会让用户连「删掉那个坏包」都做不到。
     */
    fun scanAll(): List<InstalledPlugin> = storage.listInstalledIds()
        .mapNotNull { packageId -> loadOne(packageId) }
        // 稳定顺序：启用优先，然后按标题、id。UI 列表不该随目录遍历顺序跳动。
        .sortedWith(
            compareByDescending<InstalledPlugin> { it.enabled }
                .thenBy { it.title.lowercase() }
                .thenBy { it.id },
        )

    /** 读单个包。目录不存在或清单全丢时返回 null。 */
    fun loadOne(packageId: String): InstalledPlugin? {
        val manifestText = storage.readManifestText(packageId) ?: return null

        val parsed = ManifestParser.parse(manifestText, packageId).getOrElse { error ->
            // 语法级失败：造一个「占位包」让用户能在列表里看到并删除，
            // 而不是让它消失在列表里却占着磁盘。
            return InstalledPlugin(
                manifest = PluginManifest(id = packageId, displayName = LocalizedText.ofZh(packageId)),
                installPath = "${storage.packagesRoot}/$packageId",
                enabled = false,
                origin = storage.readOrigin(packageId) ?: PluginOrigin.Local,
                installedAt = now(),
                problems = listOf(
                    ManifestProblem.Fatal(
                        field = "manifest",
                        message = "清单语法错误：${error.message ?: "无法解析"}",
                    ),
                ),
            )
        }

        val mapped = ManifestMapper.map(parsed) { relative ->
            // 目录实现下「文件是否存在」交给 storage 判断比较麻烦，
            // 这里用归档读取（目录实现会给出条目列表）。
            storage.openArchive(packageId)?.use { archive ->
                val candidates = archive.entryNames()
                val normalized = PluginArchive.normalizePath(relative)
                normalized != null && candidates.any { it == normalized }
            } ?: false
        }

        // 清单里的 id 与目录名不一致时：以**目录名**为准。
        // 目录名是安装时按 id 生成的；若清单被作者改了 id，
        // 按清单来会出现「列表里两个包同 id」。这里记一条警告。
        val problems = mapped.problems.toMutableList()
        if (mapped.manifest.id.isNotBlank() && mapped.manifest.id != packageId) {
            problems += ManifestProblem.Warning(
                field = "toolpkg_id",
                message = "清单 id '${mapped.manifest.id}' 与安装目录 '$packageId' 不一致，" +
                    "已按目录名识别。可能是手动改过文件。",
            )
        }

        val effectiveManifest = if (mapped.manifest.id.isBlank()) {
            mapped.manifest.copy(id = packageId)
        } else {
            mapped.manifest
        }

        return InstalledPlugin(
            manifest = effectiveManifest,
            installPath = "${storage.packagesRoot}/$packageId",
            // ## 有致命问题时强制未启用
            //
            // 否则会出现「开关显示开着、但工具根本加载不出来」的状态：
            // 用户看到开关是开的，就会以为功能该有，然后发现工具列表里没有，
            // 再试着重启应用 —— 全是白费。
            //
            // 强制 false 后，开关显示关闭 + 旁边写着失败原因，
            // 用户至少知道「它没生效，是因为这里坏了」。
            enabled = storage.isEnabled(packageId) && problems.none {
                it is ManifestProblem.Fatal
            },
            disabledSubpackageIds = storage.disabledSubpackages(packageId),
            origin = storage.readOrigin(packageId) ?: PluginOrigin.Local,
            installedAt = now(),
            problems = problems,
        )
    }

    // ── 安装 ──────────────────────────────────────────────────

    /**
     * 安装一个包。
     *
     * ## 顺序：先解析校验，再落盘
     *
     * 反过来（先落盘再解析）会出现「解析失败但文件已经在包目录里」，
     * 用户看到列表里多了一个红色错误项，删了再装还是错 —— 体验极差。
     * 所以先用归档读取器预检：
     *
     * 1. 能读出清单（语法对）
     * 2. 清单没有致命问题（id/main/api_version 齐）
     * 3. 才调 `installFrom` 落盘
     *
     * @param sourcePath zip 文件或目录
     * @param origin 来源（市场安装时带 marketId 与版本）
     * @param expectedId 预期的包 id；为空时用清单里的 id。
     *   **市场安装必须传**：服务端给的 id 是权威的，
     *   不能由包自己声明（否则可以装成另一个已装包，覆盖别人的文件）。
     */
    fun install(
        sourcePath: String,
        origin: PluginOrigin = PluginOrigin.Local,
        expectedId: String? = null,
    ): Result<InstalledPlugin> = runCatching {
        val preview = inspect(sourcePath).getOrThrow()
        val manifestId = preview.manifest.id.trim()
        val targetId = (expectedId?.trim()?.takeIf { it.isNotEmpty() } ?: manifestId)

        require(targetId.isNotEmpty()) { "包没有声明 toolpkg_id，无法安装" }
        require(!targetId.contains('/') && !targetId.contains('\\')) {
            "包 id '$targetId' 含有路径分隔符，拒绝安装（会写到包目录之外）"
        }

        // ## 市场 id 与包 id 不一致时怎么办
        //
        // 允许（作者改名重发是常见事），但要在大括号里说清为什么**不**纠正：
        // `marketId` 是「市场条目 id」，`manifest.id` 是「包自身 id」，
        // 两者本就不是一个东西。用市场 id 覆盖包 id 会破坏依赖声明
        // （其它包 `requires` 的是 manifest.id）。所以各存各的，
        // 更新时按 marketId 查、加载时按 manifest.id 认。
        val installedId = storage.installFrom(sourcePath, targetId).getOrThrow()
        storage.writeOrigin(installedId, origin)
        // 新装包默认按清单的 enabled_by_default 决定启停。
        // 但**不在这里写**：`isEnabled` 的实现应在「没有显式记录」时回落清单默认，
        // 这样以后改默认值能对已装包也生效（除了用户显式关过的）。
        loadOne(installedId) ?: error("安装后读不到包（文件可能已被删除）")
    }

    /** 预检：只解析不落盘。[sourcePath] 为 zip 或目录。 */
    fun inspect(sourcePath: String): Result<PluginArchiveContent> = runCatching {
        val archive = openExternalArchive(sourcePath)
            ?: error("无法打开 '$sourcePath'（既不是 zip 也不是含清单的目录）")
        archive.use { PluginArchiveReader.read(it).getOrThrow() }
    }.mapCatching { content ->
        if (!content.isUsable) {
            val fatal = content.problems.first { it is ManifestProblem.Fatal } as ManifestProblem.Fatal
            error("清单不可用（${fatal.field}）：${fatal.message}")
        }
        content
    }

    // ── 卸载 / 启停 ───────────────────────────────────────────

    /**
     * 卸载。
     *
     * @return 失败原因；null 表示成功
     *
     * ## 为什么未安装也返回成功
     *
     * `remove` 对不存在的目录返回 false。但用户点「卸载」时它可能
     * 已经被别的路径删掉了（比如开发时手动 `rm -rf`）——
     * 此时报「卸载失败」纯粹是噪音。只有**删不动**（权限/被占用）才该报错。
     */
    fun uninstall(packageId: String): String? {
        if (!storage.exists(packageId)) return null
        val removed = storage.remove(packageId)
        return if (removed) null else "删除包目录失败：可能被占用或没有权限"
    }

    fun setEnabled(packageId: String, enabled: Boolean) {
        storage.setEnabled(packageId, enabled)
    }

    fun setSubpackageEnabled(packageId: String, subpackageId: String, enabled: Boolean) {
        storage.setSubpackageEnabled(packageId, subpackageId, enabled)
    }

    // ── 更新 ──────────────────────────────────────────────────

    /**
     * 判断某个已装包相对市场条目是否有更新。
     *
     * ## 为什么用版本比较而不是「有更新就提示」
     *
     * 市场里的 `version` 可能低于本地（用户装了作者私下发的测试版）。
     * 那种情况下提示「可更新」会让用户更新后**降级**功能。
     * 所以只在 `remote > local` 时提示。
     *
     * @return 可更新的新版本号；null 表示无更新或无法比较
     */
    fun findUpdate(installed: InstalledPlugin, remoteVersion: String): String? {
        if (installed.origin !is PluginOrigin.Market) return null
        val local = SemVer.parseOrNull(installed.manifest.version) ?: return null
        val remote = SemVer.parseOrNull(remoteVersion) ?: return null
        return if (remote > local) remote.toString() else null
    }

    /**
     * 打开外部归档（zip 文件 / 目录）。
     *
     * ## 为什么放在 companion 而不是 storage
     *
     * 「导入一个还没装进来的包」发生在**安装之前**，那时它还不是
     * `packagesRoot` 下的子目录，不属于 storage 的职责范围。
     * 这里默认返回 null，由平台层（Android 的 `:app`）提供真实实现
     * 通过 [externalArchiveFactory] 注入。
     */
    private fun openExternalArchive(sourcePath: String): PluginArchive? =
        externalArchiveFactory?.invoke(sourcePath)

    companion object {
        /**
         * 外部归档工厂。
         *
         * 默认 null：`core:plugin` 是纯 JVM，不能直接依赖 `java.util.zip`
         * 之外的文件语义差异（也不需要）—— 但 Android 侧的目录/SAF 访问
         * 必须由 `:app` 提供。设为可变是为了让 `:app` 在启动时注册一次。
         *
         * 单测可以直接替换成内存实现。
         */
        @Volatile
        var externalArchiveFactory: ((String) -> PluginArchive?)? = null
    }
}

/** `use` 扩展：让 [PluginArchive] 支持 try-with-resources 风格。 */
private inline fun <T : PluginArchive, R> T.use(block: (T) -> R): R =
    try {
        block(this)
    } finally {
        runCatching { close() }
    }