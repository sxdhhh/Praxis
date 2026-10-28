package dev.praxis.data.plugin

import dev.praxis.core.plugin.PluginArchive
import java.io.File

/**
 * 把「已解压的包目录」当成 [PluginArchive] 读。
 *
 * ## 为什么需要它
 *
 * `PluginStorage.openArchive` 的契约是「打开某个包的归档读取器（目录实现：
 * 按目录读；zip 实现：按 zip 读）」。装到 `packages/<id>/` 之后包已经是
 * 一棵普通目录树了，不需要（也不应该）每次读取再压回 zip。
 *
 * 上层（`PluginArchiveReader`）只认 `entryNames()` / `readText()` /
 * `readBytes()` 三个方法，所以这里把文件树拍平成「相对路径 → File」。
 *
 * ## 路径用 `/` 而不是平台分隔符
 *
 * `PluginArchiveReader` 用 `split('/')` 算包根、拼相对路径。若这里返回
 * `packages/a/mypkg\main.js`（Windows 风格），在 Android 上虽然能跑，
 * 但相对路径匹配会全错。统一成正斜杠。
 */
class DirectoryPluginArchive(
    private val root: File,
) : PluginArchive {

    private val rootPath: String = root.absolutePath

    override fun entryNames(): Set<String> = buildSet {
        root.walkTopDown().forEach { file ->
            if (file.isFile) {
                add(file.absolutePath.removePrefix(rootPath).trimStart(File.separatorChar).replace(File.separatorChar, '/'))
            }
        }
    }

    override fun readText(path: String): String? =
        resolve(path)?.let { file -> runCatching { file.readText() }.getOrNull() }

    override fun readBytes(path: String): ByteArray? =
        resolve(path)?.let { file -> runCatching { file.readBytes() }.getOrNull() }

    // 目录没有需要释放的句柄；接口要求实现，保持空实现。
    override fun close() = Unit

    /**
     * 相对路径 → File，并防越界。
     *
     * 走 `canonicalPath` 比较前缀而不是只 `File(root, path)`：清单里
     * `main: ../../etc/passwd` 这种写法不该被读出来。
     */
    private fun resolve(path: String): File? {
        val normalized = PluginArchive.normalizePath(path) ?: return null
        val file = File(root, normalized)
        val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return null
        val rootCanonical = runCatching { root.canonicalPath }.getOrNull() ?: return null
        if (!canonical.startsWith(rootCanonical)) return null
        return file.takeIf { it.isFile }
    }
}
