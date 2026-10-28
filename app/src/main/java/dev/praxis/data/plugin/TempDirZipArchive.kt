package dev.praxis.data.plugin

import dev.praxis.core.plugin.PluginArchive
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * 把 zip（`.toolpkg` / `.zip`）解到临时目录，读的时候按目录读，
 * [close] 时把临时目录删掉。
 *
 * ## 为什么是「解到目录」而不是流式读 zip
 *
 * `PluginArchiveReader` 会多次读同一条目（清单读一次、脚本读一次、
 * 资源按需读），而且会先 `entryNames()` 全量列举再回读。`ZipInputStream`
 * 是单向流，读完一遍就得重开；`ZipFile` 倒是能随机读，但它要求一个
 * 真实的文件句柄，而 `PluginArchive` 的生命周期由调用方的 `use {}` 管，
 * 两者混在一起要处理「句柄什么时候关」。
 *
 * 解到临时目录最简单：读全是 `File` 操作，`close` 只做一次
 * `deleteRecursively`，不会漏关句柄。代价是一次额外的磁盘写，包通常
 * 几百 KB 到几 MB，可以接受。
 *
 * ## 为什么目录建在 cacheDir 下
 *
 * 系统在磁盘紧张时会主动清 `cacheDir`。万一 `close()` 没被调用
 * （进程被杀），残留目录也不会永久占空间。
 */
class TempDirZipArchive private constructor(
    private val tempDir: File,
    private val delegate: DirectoryPluginArchive,
) : PluginArchive by delegate {

    override fun close() {
        tempDir.deleteRecursively()
    }

    companion object {
        /**
         * 解压 [zipFile] 到 `cacheDir/plugin-inspect-<nano>/` 并返回可读归档。
         *
         * 失败时自己清掉临时目录再抛出 —— 调用方拿不到对象就无法 `close`，
         * 不在这里清就会泄漏。
         */
        fun unzipInto(cacheDir: File, zipFile: File): PluginArchive {
            val tempDir = File(cacheDir, "plugin-inspect-" + System.nanoTime())
            if (!tempDir.mkdirs()) error("无法创建临时目录：${tempDir.absolutePath}")
            try {
                extract(zipFile, tempDir)
                return TempDirZipArchive(tempDir, DirectoryPluginArchive(tempDir))
            } catch (t: Throwable) {
                tempDir.deleteRecursively()
                throw t
            }
        }

        /**
         * 解压。
         *
         * 与 `FilePluginStorage.unzip` 同一套逻辑（剥一层公共目录 + 防越界），
         * 但不复用那个私有方法：这里的失败语义是「抛异常让上层 `runCatching`
         * 拿到」，那边是「返回 Result」。硬凑一个出来会让两边都变难读。
         */
        private fun extract(source: File, destination: File) {
            val stripPrefix = runCatching {
                ZipFile(source).use { zip ->
                    val names = zip.entries().asSequence()
                        .filterNot { it.isDirectory }
                        .map { it.name.replace(File.separatorChar, '/') }
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
                    var name = entry.name.replace(File.separatorChar, '/')
                    if (stripPrefix != null && name.startsWith(stripPrefix)) {
                        name = name.removePrefix(stripPrefix)
                    }
                    if (name.isNotBlank()) {
                        val outFile = File(destination, name)
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
    }
}
