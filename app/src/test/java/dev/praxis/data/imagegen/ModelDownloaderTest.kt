package dev.praxis.data.imagegen

import dev.praxis.core.imagegen.ImageDownloadPlan
import dev.praxis.core.imagegen.ImageDownloadPlanner
import dev.praxis.core.imagegen.ImageModel
import dev.praxis.core.imagegen.ImageModelCatalog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 下载器的落地行为（文档 §8.3/§8.4）。
 *
 * 这里能验证的是**下载之后的每一步**：解压、剥目录、改名去掉 `.part`、写标记、失败清理、
 * zip-slip 防护。不能验证的是真实网络（openStream 被换成假流）。
 */
class ModelDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun zipModel() = ImageModel(
        id = "anythingv5cpu",
        name = "AnythingV5 (CPU)",
        fileUri = "xororz/sd-mnn/resolve/main/AnythingV5.zip",
        backendType = "sd15cpu",
        approximateSize = "1MB",
    )

    private fun multiModel() = ImageModel(
        id = "z_image_turbo",
        name = "Z-Image Turbo",
        fileUri = "x",
        backendType = "zimage",
        ditKind = "zimage",
        approximateSize = "1MB",
        packageFiles = listOf(
            "remote/unet.bin|unet.bin",
            "remote/clip.bin|clip.bin",
        ),
    )

    @Test
    fun `单 zip 包解压到模型目录并把压缩包删掉`() {
        val root = tmp.newFolder("models")
        val plan = ImageDownloadPlanner.plan(root, zipModel())
        val bytes = zipOf("unet.mnn" to "weights", "tokenizer.txt" to "tok")
        val downloader = ModelDownloader(baseUrl = "") { ByteArrayInputStream(bytes) }

        val result = downloader.download(plan) { }

        assertTrue(result is DownloadResult.Completed)
        assertTrue(File(plan.targetDir, "unet.mnn").isFile)
        assertTrue(File(plan.targetDir, "tokenizer.txt").isFile)
        assertFalse("压缩包必须删掉", plan.zipTempFile.exists())
        assertTrue("装好后 isInstalled 必须为真", ImageModelCatalog.isInstalled(root, zipModel()))
    }

    @Test
    fun `包内多一层同名目录时剥掉`() {
        // 很多包是 AnythingV5/unet.mnn 结构；不剥的话引擎找不到权重。
        val root = tmp.newFolder("models")
        val plan = ImageDownloadPlanner.plan(root, zipModel())
        val bytes = zipOf("AnythingV5/unet.mnn" to "w", "AnythingV5/tok.txt" to "t")
        ModelDownloader(baseUrl = "") { ByteArrayInputStream(bytes) }.download(plan) { }

        assertTrue(File(plan.targetDir, "unet.mnn").isFile)
        assertFalse(File(plan.targetDir, "AnythingV5").exists())
    }

    @Test
    fun `多文件包逐个下载后写标记文件`() {
        val root = tmp.newFolder("models")
        val plan = ImageDownloadPlanner.plan(root, multiModel())
        val downloader = ModelDownloader(baseUrl = "") { url ->
            ByteArrayInputStream((if (url.endsWith("unet.bin")) "u" else "c").toByteArray())
        }

        val result = downloader.download(plan) { }

        assertTrue(result is DownloadResult.Completed)
        assertTrue(File(plan.targetDir, "unet.bin").isFile)
        assertTrue(File(plan.targetDir, "clip.bin").isFile)
        assertTrue(File(plan.targetDir, "ZIMAGE").isFile)
        assertTrue(plan.partPaths.none { it.exists() })
        assertTrue(ImageModelCatalog.isInstalled(root, multiModel()))
    }

    @Test
    fun `空间不足时一个字节都不写`() {
        val root = tmp.newFolder("models")
        val base = ImageDownloadPlanner.plan(root, zipModel())
        // 直接造一个巨额需求：本机磁盘不可能有这么多空闲。
        val plan = base.copy(requiredBytes = 1_000_000_000_000_000_000L)
        var opened = false
        val downloader = ModelDownloader(baseUrl = "") { opened = true; ByteArrayInputStream(ByteArray(0)) }

        val result = downloader.download(plan) { }

        assertTrue(result is DownloadResult.InsufficientSpace)
        assertFalse("预检失败时不该发请求", opened)
        assertTrue(plan.partPaths.none { it.exists() })
    }

    @Test
    fun `中途失败会清理残留并让 isInstalled 保持为假`() {
        val root = tmp.newFolder("models")
        val plan = ImageDownloadPlanner.plan(root, multiModel())
        val downloader = ModelDownloader(baseUrl = "") { url ->
            if (url.endsWith("clip.bin")) throw IOException("网络断了") else ByteArrayInputStream("u".toByteArray())
        }

        val result = downloader.download(plan) { }

        assertTrue(result is DownloadResult.Failed)
        assertTrue("残留必须清掉，否则下次预检误判", plan.partPaths.none { it.exists() })
        assertFalse(ImageModelCatalog.isInstalled(root, multiModel()))
    }

    @Test
    fun `zip 内越界路径被拒绝而不是写到目录外`() {
        val root = tmp.newFolder("models")
        val plan = ImageDownloadPlanner.plan(root, zipModel())
        val bytes = zipOf("../evil.txt" to "pwn")

        val result = ModelDownloader(baseUrl = "") { ByteArrayInputStream(bytes) }.download(plan) { }

        assertTrue(result is DownloadResult.Failed)
        assertFalse("越界文件不能落到模型目录外", File(root, "evil.txt").exists())
        assertFalse(ImageModelCatalog.isInstalled(root, zipModel()))
    }

    @Test
    fun `进度回调给出已下载字节且总量未知时不编造比例`() {
        val root = tmp.newFolder("models")
        val base = ImageDownloadPlanner.plan(root, multiModel())
        val plan = base.copy(requiredBytes = null)
        val progress = mutableListOf<DownloadProgress>()
        val downloader = ModelDownloader(baseUrl = "") { ByteArrayInputStream(ByteArray(4096)) }

        downloader.download(plan) { progress += it }

        assertTrue(progress.isNotEmpty())
        assertTrue(progress.maxOf { it.bytesDownloaded } >= 4096L)
        assertTrue("总量未知时 fraction 必须是 null", progress.all { it.fraction == null })
    }

    @Test
    fun `体积未知时结果里明确标注而不是当成预检通过`() {
        val root = tmp.newFolder("models")
        val base = ImageDownloadPlanner.plan(root, zipModel())
        val plan = base.copy(requiredBytes = null)
        val bytes = zipOf("unet.mnn" to "w")

        val result = ModelDownloader(baseUrl = "") { ByteArrayInputStream(bytes) }.download(plan) { }

        assertTrue(result is DownloadResult.Completed)
        assertTrue("必须标出「空间无法预估」", (result as DownloadResult.Completed).spaceWasUnknown)
    }
}
