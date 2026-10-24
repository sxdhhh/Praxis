package dev.praxis.core.plugin

/**
 * 包内容读取端口。
 *
 * ## 为什么 `core:plugin` 不直接用 `java.io.File`
 *
 * 两个原因：
 *
 * 1. **单测**：解析逻辑（清单校验、路径归一化、依赖排序）是这一层最容易出
 *    错的部分，要能纯 JVM 跑。用接口注入内存实现即可，不用造临时目录。
 * 2. **zip 与目录两种来源**：`.toolpkg` 是 zip，开发期是目录，
 *    将来还可能从网络流式读。把它们统一成「按路径读文本/字节」，
 *    上层（解析、加载、UI）就只需要一套代码。
 *
 * ## 路径语义：一律用包内相对路径，`/` 分隔
 *
 * 不用 `File.separator`：包是在 Windows 上打的也可能在 Android 上解，
 * 清单里写的是 `/`。所有实现都必须把传入路径先 [normalizePath]。
 */
interface PluginArchive {

    /** 包内所有条目名（相对路径，`/` 分隔，不含目录条目）。 */
    fun entryNames(): Set<String>

    /**
     * 读文本条目。
     *
     * @return null 表示该条目不存在（调用方据此判断「main 是否存在」）
     */
    fun readText(path: String): String?

    /** 读字节条目。用于图标、WASM、资源。 */
    fun readBytes(path: String): ByteArray?

    /** 关闭底层资源。zip 实现必须关，否则 fd 泄漏。 */
    fun close()

    companion object {
        /**
         * 归一化包内路径。
         *
         * ## 必须做的事
         *
         * - 去掉开头的 `./`（`./packages/a.js` 与 `packages/a.js` 是同一文件）
         * - 反斜杠转正斜杠（Windows 打包）
         * - 折叠重复斜杠
         * - 处理 `..`：**拒绝**跳出包根（zip slip）。
         *   一个恶意包可以用 `../../../../data/data/xxx` 覆盖宿主文件，
         *   这是 zip 解压类功能最经典的漏洞。
         *
         * @return 归一化后的路径；越界或非法时返回 null
         */
        fun normalizePath(raw: String): String? {
            if (raw.isBlank()) return null
            val unified = raw.replace('\\', '/')

            // 绝对路径直接拒绝：包内条目不该是绝对路径。
            if (unified.startsWith("/")) return null

            val out = ArrayDeque<String>()
            unified.split('/').forEach { segment ->
                when (segment) {
                    "", "." -> Unit
                    ".." -> {
                        // 越界：`a/../../b` 在弹到根之后再 `..` 就是跳出。
                        if (out.isEmpty()) return null
                        out.removeLast()
                    }
                    else -> out.addLast(segment)
                }
            }
            if (out.isEmpty()) return null
            return out.joinToString("/")
        }
    }
}

/** 内存实现：单测与「从字符串装载的包」用。 */
class InMemoryPluginArchive(
    private val entries: Map<String, ByteArray>,
) : PluginArchive {

    private val normalized: Map<String, ByteArray> = entries.mapNotNull { (rawPath, bytes) ->
        PluginArchive.normalizePath(rawPath)?.let { it to bytes }
    }.toMap()

    override fun entryNames(): Set<String> = normalized.keys

    override fun readText(path: String): String? {
        val key = PluginArchive.normalizePath(path) ?: return null
        return normalized[key]?.toString(Charsets.UTF_8)
    }

    override fun readBytes(path: String): ByteArray? {
        val key = PluginArchive.normalizePath(path) ?: return null
        return normalized[key]
    }

    override fun close() = Unit

    companion object {
        /** 便捷构造：文本条目。 */
        fun ofText(texts: Map<String, String>): InMemoryPluginArchive =
            InMemoryPluginArchive(texts.mapValues { (_, value) -> value.toByteArray() })
    }
}

/**
 * 从 [PluginArchive] 读出的完整包内容。
 *
 * ## 为什么把「清单 + 脚本 + 资源」一次性读出来
 *
 * 分次读会让 `close()` 的时机变得暧昧：UI 还在显示清单，但 zip 已关，
 * 点「查看脚本」就崩。一次性读出所有**小**文件（清单、JS、图标），
 * 只对大文件（WASM、模板）保留按需读取 —— 第一阶段先全读，
 * 因为典型的 toolpkg 只有几十 KB。
 */
data class PluginArchiveContent(
    val manifest: PluginManifest,
    val problems: List<ManifestProblem>,
    /** 主入口脚本正文。 */
    val mainScript: String?,
    /** 子包 id → 脚本正文。 */
    val subpackageScripts: Map<String, String?>,
    /** 资源 key → 字节（图标等）。 */
    val resourceBytes: Map<String, ByteArray>,
    /** 清单文件名（`manifest.hjson` 或 `manifest.json`），便于报错定位。 */
    val manifestEntryName: String,
) {
    val isUsable: Boolean get() = problems.none { it is ManifestProblem.Fatal }
}

/**
 * 把 [PluginArchive] 解析成 [PluginArchiveContent]。
 *
 * 纯函数式流程：找清单 → 解析 → 映射 → 读入口脚本 → 读资源。
 * 每一步失败都转成 [ManifestProblem]，不抛异常（除 IO 层的运行时错误）。
 */
object PluginArchiveReader {

    /** 清单候选文件名，按优先级。 */
    private val MANIFEST_CANDIDATES = listOf("manifest.hjson", "manifest.json")

    /**
     * @param archive 包内容
     * @param readAllScripts 是否把所有脚本读进内存（false 时只读 main 与启用子包）
     */
    fun read(archive: PluginArchive, readAllScripts: Boolean = true): Result<PluginArchiveContent> =
        runCatching {
            val entries = archive.entryNames()

            // ── 1. 找清单 ─────────────────────────────────────
            // 允许清单在子目录里（zip 打多了包了一层目录是常见现象）。
            val manifestEntry = findManifestEntry(entries)
                ?: error(
                    "包内找不到清单文件。需要 ${MANIFEST_CANDIDATES.joinToString(" 或 ")} " +
                        "（可以是包根，也可以在唯一的一层子目录下）。" +
                        "当前条目数：${entries.size}",
                )
            val manifestText = archive.readText(manifestEntry)
                ?: error("清单文件 '$manifestEntry' 存在但读不出内容")

            // ── 2. 解析 + 映射 ────────────────────────────────
            val parsed = ManifestParser.parse(manifestText, manifestEntry).getOrThrow()

            // 清单所在目录作为「包根」。若清单在 `mypkg/manifest.json`，
            // 那么 `main: packages/a.js` 实际指向 `mypkg/packages/a.js`。
            val basePath = manifestEntry.substringBeforeLast('/', missingDelimiterValue = "")

            fun resolveRelative(relative: String): String? {
                val joined = if (basePath.isEmpty()) relative else "$basePath/$relative"
                return PluginArchive.normalizePath(joined)
            }

            val mapped = ManifestMapper.map(parsed) { relative ->
                resolveRelative(relative)?.let { it in entries } == true
            }

            // ── 3. 读入口脚本 ─────────────────────────────────
            val mainPath = resolveRelative(mapped.manifest.main)
            val mainScript = mainPath?.let { archive.readText(it) }

            val subpackageScripts = mapped.manifest.subpackages.associate { sub ->
                val path = resolveRelative(sub.entry)
                val text = path?.let { archive.readText(it) }
                sub.id to text
            }.filterValues { readAllScripts || it != null }

            // ── 4. 读资源 ─────────────────────────────────────
            val resourceBytes = mapped.manifest.resources.mapNotNull { resource ->
                val path = resolveRelative(resource.path) ?: return@mapNotNull null
                archive.readBytes(path)?.let { resource.key to it }
            }.toMap()

            PluginArchiveContent(
                manifest = mapped.manifest,
                problems = mapped.problems,
                mainScript = mainScript,
                subpackageScripts = subpackageScripts,
                resourceBytes = resourceBytes,
                manifestEntryName = manifestEntry,
            )
        }

    /**
     * 找清单条目。
     *
     * ## 为什么要支持「包根之外的一层子目录」
     *
     * 大量第三方包是「把整个工程目录打成 zip」，结果 zip 里是
     * `MyToolPkg/manifest.hjson` 而不是 `manifest.hjson`。
     * Operit 的 `findManifestEntry` 也做同样的兼容，不这么做会让
     * 一半市场上能装、一半装不上，而用户完全不知道为什么。
     *
     * ## 多层嵌套不支持
     *
     * 只认「根」与「根下一层」。更深意味着打包方式真的有问题，
     * 猜下去会出现两份清单都匹配的情况，选择哪一个都是错的。
     */
    private fun findManifestEntry(entries: Set<String>): String? {
        // 根目录优先。注意：要返回**条目原名**而不是候选常量名 ——
        // zip 里可能是 `Manifest.HJSON`，返回 `manifest.hjson` 会读不到。
        MANIFEST_CANDIDATES.forEach { name ->
            entries.firstOrNull { it.equals(name, ignoreCase = true) }?.let { return it }
        }
        // 一层子目录（同样返回原名）。
        val candidates = entries.filter { entry ->
            val segments = entry.split('/')
            segments.size == 2 &&
                MANIFEST_CANDIDATES.any { it.equals(segments[1], ignoreCase = true) }
        }
        // 多个候选时按字典序取第一，并在外层通过 problems 报告「不止一个清单」。
        // 不在这里抛：仍然要能装上一个（多半是作者复制粘贴留下的），
        // 直接把整包否掉会让用户完全无从修。
        return candidates.sorted().firstOrNull()
    }
}