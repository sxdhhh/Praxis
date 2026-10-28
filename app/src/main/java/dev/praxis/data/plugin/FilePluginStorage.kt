package dev.praxis.data.plugin

import android.content.Context
import android.content.SharedPreferences
import dev.praxis.core.plugin.PluginArchive
import dev.praxis.core.plugin.PluginOrigin
import dev.praxis.core.plugin.PluginStorage
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * 基于应用私有目录的 [PluginStorage]。
 *
 * ## 目录布局
 *
 * filesDir/plugins/packages/<packageId>/ 存放一个已解压的包；
 * 同级的 .staging-<id> 是安装中的临时目录，.old-<id> 是待删的旧版本；
 * plugin_state.xml 存启停开关、子包开关与来源。
 *
 * 用私有目录而不是外部存储：插件是**会被执行的东西**，放在别的应用能写的
 * 位置等于把代码注入点交出去。
 *
 * ## 为什么状态存 SharedPreferences 而不是塞进包目录
 *
 * 包目录会被「更新」整体替换（先删后改名）。状态写在包里就会随更新丢失，
 * 用户装个新版发现「我刚关掉的包又自己开了」。状态是**本机事实**，
 * 必须活得比包文件久。
 *
 * ## 为什么 installFrom 要做三段改名
 *
 * 直接往目标目录解压，中途失败（磁盘满 / zip 损坏 / 进程被杀）会留下
 * **半装的包**：清单在、脚本缺一半。下次加载它会抛一堆莫名的 undefined，
 * 而用户看到的是「这个包坏了」却删不干净。
 *
 * 顺序：解到 .staging-<id> → 校验清单能读 → 旧目录改名 .old-<id> →
 * staging 改名到目标 → 删 .old。任何一步失败都只剩 staging，清掉即可。
 */
class FilePluginStorage(
    context: Context,
) : PluginStorage {

    private val root = File(context.filesDir, ROOT_DIR)
    private val packages = File(root, PACKAGES_DIR)
    private val prefs: SharedPreferences =
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    init {
        packages.mkdirs()
    }

    override val packagesRoot: String get() = packages.absolutePath

    /**
     * 已安装包 id 列表。
     *
     * 过滤掉 .staging- / .old- 前缀：它们是安装过程中的中间态，
     * 出现在列表里会让用户看到两个同名包并试图删掉其中一个。
     */
    override fun listInstalledIds(): List<String> =
        packages.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    override fun readManifestText(packageId: String): String? {
        val dir = dirOf(packageId).takeIf { it.isDirectory } ?: return null
        // 两种清单都试：manifest.hjson 在 Operit 生态里更常见，先查它少一次 stat。
        for (name in MANIFEST_NAMES) {
            val file = File(dir, name)
            if (file.isFile) {
                return runCatching { file.readText() }.getOrNull()
            }
        }
        // 一层子目录（id/子目录/manifest.*）：与 PluginArchiveReader 的兼容策略
        // 保持一致，否则「能装的包装完读不出清单」。
        //
        // 这里必须用显式双层 for 而不是 forEach { ... return }：
        // forEach 的 lambda 里 return 只能是带标签的局部返回，不写标签时
        // 那行根本不编译（CI 报过 'return' is prohibited here）。
        val children = dir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            .orEmpty()
        for (child in children) {
            for (name in MANIFEST_NAMES) {
                val file = File(child, name)
                if (file.isFile) {
                    return runCatching { file.readText() }.getOrNull()
                }
            }
        }
        return null
    }

    override fun openArchive(packageId: String): PluginArchive? {
        val dir = dirOf(packageId)
        if (!dir.isDirectory) return null
        return DirectoryPluginArchive(dir)
    }

    override fun installFrom(sourcePath: String, expectedId: String): Result<String> =
        runCatching {
            val source = File(sourcePath)
            if (!source.exists()) error("源文件不存在：" + sourcePath)

            val target = dirOf(expectedId)
            val staging = File(packages, STAGING_PREFIX + expectedId)
            val old = File(packages, OLD_PREFIX + expectedId)

            // 上一轮失败可能留下 staging，先清掉（不清会让解压遇到同名文件）。
            staging.deleteRecursively()
            old.deleteRecursively()
            if (!staging.mkdirs()) error("无法创建临时目录：" + staging.absolutePath)

            try {
                if (source.isDirectory) {
                    source.copyRecursively(staging, overwrite = true)
                } else {
                    unzip(source, staging)
                }

                // 校验：清单必须能读出来。读不到说明源包本身有问题 ——
                // 装一个读不出清单的包等于装了个死目录，不如直接失败。
                if (!hasManifest(staging)) {
                    error("包里没有 " + MANIFEST_NAMES.joinToString(" 或 "))
                }

                if (target.exists()) {
                    if (!target.renameTo(old)) error("无法移走旧版本：" + target.absolutePath)
                }
                if (!staging.renameTo(target)) {
                    // 换回旧版本，别把用户已有的包搞丢。
                    old.renameTo(target)
                    error("无法安装到：" + target.absolutePath)
                }
                old.deleteRecursively()
                // 新装的包默认启用（用户装它就是想用）。
                setEnabled(expectedId, true)
                expectedId
            } catch (t: Throwable) {
                staging.deleteRecursively()
                throw t
            }
        }

    override fun remove(packageId: String): Boolean {
        val dir = dirOf(packageId)
        val deleted = !dir.exists() || dir.deleteRecursively()
        // 状态也要清：留着会让「重装同名包」继承上次的启停与来源，
        // 而用户以为装的是一张白纸。
        prefs.edit()
            .remove(keyEnabled(packageId))
            .remove(keyDisabledSubpackages(packageId))
            .remove(keyOrigin(packageId))
            .apply()
        return deleted
    }

    override fun exists(packageId: String): Boolean = dirOf(packageId).isDirectory

    override fun isEnabled(packageId: String): Boolean =
        prefs.getBoolean(keyEnabled(packageId), true)

    override fun setEnabled(packageId: String, enabled: Boolean) {
        prefs.edit().putBoolean(keyEnabled(packageId), enabled).apply()
    }

    override fun disabledSubpackages(packageId: String): Set<String> =
        prefs.getStringSet(keyDisabledSubpackages(packageId), emptySet())?.toSet().orEmpty()

    override fun setSubpackageEnabled(packageId: String, subpackageId: String, enabled: Boolean) {
        val current = disabledSubpackages(packageId).toMutableSet()
        if (enabled) current.remove(subpackageId) else current.add(subpackageId)
        prefs.edit().putStringSet(keyDisabledSubpackages(packageId), current).apply()
    }

    /**
     * 读来源。
     *
     * 存成 MarketOriginCodec 的编码串（而不是结构化的几个键）：
     * 来源种类会随市场演进增加（内置 / 本地 / 市场 / 开发），用一条串
     * 能让「未知种类」也有地方落，不会因为多一个分支就丢数据。
     */
    override fun readOrigin(packageId: String): PluginOrigin? {
        val raw = prefs.getString(keyOrigin(packageId), null) ?: return null
        return MarketOriginCodec.decode(raw)
    }

    override fun writeOrigin(packageId: String, origin: PluginOrigin) {
        prefs.edit().putString(keyOrigin(packageId), MarketOriginCodec.encode(origin)).apply()
    }

    private fun dirOf(packageId: String): File = File(packages, sanitize(packageId))

    /**
     * 包 id 转安全目录名。
     *
     * id 来自第三方清单，可能出现 ../ 空格 中文。直接当目录名会让
     * installFrom 有机会写到 packages 目录之外（路径穿越）。这里只保留
     * ASCII 字母数字与 ._- ，其余映射成下划线，并去掉开头的点
     * （避免与 .staging- 前缀混淆）。
     */
    private fun sanitize(packageId: String): String {
        val cleaned = buildString {
            packageId.forEach { ch ->
                val ok = (ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') ||
                    ch == '.' || ch == '_' || ch == '-'
                append(if (ok) ch else '_')
            }
        }
        return cleaned.trimStart('.').ifEmpty { "pkg" }
    }

    /** 包目录里是否有可读清单（根目录或唯一的一层子目录）。 */
    private fun hasManifest(dir: File): Boolean {
        if (MANIFEST_NAMES.any { File(dir, it).isFile }) return true
        val children = dir.listFiles()?.filter { it.isDirectory }.orEmpty()
        for (child in children) {
            if (MANIFEST_NAMES.any { File(child, it).isFile }) return true
        }
        return false
    }

    /**
     * 解压 zip 到 destination。
     *
     * ## 剥掉「所有条目都在同一层目录下」的那一层
     *
     * 大量第三方包是把整个工程目录打成 zip，条目全是 mypkg/manifest.hjson、
     * mypkg/main.js。不剥的话装完会变成 packages/id/mypkg/manifest.hjson ——
     * 虽然 readManifestText 兼容一层子目录，但脚本里的相对路径（./lib/x.js）
     * 会全错。
     *
     * 只在**所有**条目都在同一层下时才剥：混着 mypkg/a.js 与 README.md 的包
     * 不能剥，否则 README 会跑到父目录里去。
     */
    private fun unzip(source: File, destination: File) {
        val stripPrefix = runCatching {
            ZipFile(source).use { zip ->
                val names = zip.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .map { it.name.replace(SEP_CHAR, '/') }
                    .toList()
                val firstSegments = names.filter { it.contains('/') }
                    .map { it.substringBefore('/') }
                    .toSet()
                if (names.isNotEmpty() && names.all { it.contains('/') } && firstSegments.size == 1) {
                    firstSegments.first() + "/"
                } else {
                    null
                }
            }
        }.getOrNull()

        val rootPath = destination.canonicalPath
        ZipInputStream(source.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                var name = entry.name.replace(SEP_CHAR, '/')
                if (stripPrefix != null && name.startsWith(stripPrefix)) {
                    name = name.removePrefix(stripPrefix)
                }
                if (name.isNotBlank()) {
                    val outFile = File(destination, name)
                    // 二次越界检查：zip 条目可以是 ../../x 或绝对路径。
                    if (outFile.canonicalPath.startsWith(rootPath)) {
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out -> zip.copyTo(out) }
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun keyEnabled(id: String) = "enabled:" + id

    private fun keyDisabledSubpackages(id: String) = "disabledSub:" + id

    private fun keyOrigin(id: String) = "origin:" + id

    companion object {
        private const val ROOT_DIR = "plugins"
        private const val PACKAGES_DIR = "packages"
        private const val STATE_PREFS = "plugin_state"
        private const val STAGING_PREFIX = ".staging-"
        private const val OLD_PREFIX = ".old-"

        /** 反斜杠字符。用常量而不是字面量，避免转义在多层写入中被吃掉。 */
        private const val SEP_CHAR = '\\'

        /** 清单候选文件名，与 PluginArchiveReader 保持一致。 */
        private val MANIFEST_NAMES = listOf("manifest.hjson", "manifest.json")
    }
}
