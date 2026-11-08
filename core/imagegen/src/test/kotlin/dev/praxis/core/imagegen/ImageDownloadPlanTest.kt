package dev.praxis.core.imagegen

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 下载计划与空间预检（文档 §8.3/§8.4）。
 *
 * 这里守的是两个「用户等很久之后才发现」的失败：空间不够、以及失败残留把下次预检搞成假的「不够」。
 */
class ImageDownloadPlanTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun zipModel(size: String = "2GB") = ImageModel(
        id = "anythingv5cpu",
        name = "AnythingV5 (CPU)",
        fileUri = "xororz/sd-mnn/resolve/main/AnythingV5.zip",
        backendType = "sd15cpu",
        approximateSize = size,
    )

    private fun multiFileModel() = ImageModel(
        id = "z_image_turbo",
        name = "Z-Image Turbo",
        fileUri = "x",
        backendType = "zimage",
        ditKind = "zimage",
        approximateSize = "8GB",
        packageFiles = listOf(
            "xororz/zimage/resolve/main/unet.bin|unet.bin",
            "xororz/zimage/resolve/main/clip.bin|clip.bin",
        ),
    )

    // ── 体积解析 ──

    @Test
    fun `体积文本按单位解析`() {
        assertEquals(2L * 1024 * 1024 * 1024, ModelSize.parse("2GB"))
        assertEquals((1.5 * 1024 * 1024 * 1024).toLong(), ModelSize.parse("1.5GB"))
        assertEquals(800L * 1024 * 1024, ModelSize.parse("800 MB"))
        assertEquals(512L * 1024, ModelSize.parse("512KB"))
    }

    @Test
    fun `解析不出来时返回 null 而不是 0`() {
        assertNull(ModelSize.parse("未知"))
        assertNull(ModelSize.parse(""))
        assertNull(ModelSize.parse(null))
        assertNull(ModelSize.parse("2TB"))
    }

    // ── 计划 ──

    @Test
    fun `单 zip 包只有一个目标且本地名取自 URL 末段`() {
        val plan = ImageDownloadPlanner.plan(tmp.newFolder("models"), zipModel())
        assertTrue(plan.isZip)
        assertEquals(1, plan.targets.size)
        assertEquals("AnythingV5.zip", plan.targets[0].localName)
        assertEquals("", plan.markerFileName)
        assertEquals(File(tmp.root, "models/anythingv5cpu"), plan.targetDir)
    }

    @Test
    fun `多文件包每个文件一个目标且带标记文件名`() {
        val plan = ImageDownloadPlanner.plan(tmp.newFolder("models"), multiFileModel())
        assertFalse(plan.isZip)
        assertEquals(2, plan.targets.size)
        assertEquals("unet.bin", plan.targets[0].localName)
        assertEquals("ZIMAGE", plan.markerFileName)
        assertEquals("xororz/zimage/resolve/main/clip.bin", plan.targets[1].remotePath)
    }

    @Test
    fun `part 路径与临时文件都在模型目录下`() {
        val plan = ImageDownloadPlanner.plan(tmp.newFolder("models"), multiFileModel())
        assertEquals(
            listOf("unet.bin.part", "clip.bin.part"),
            plan.partPaths.map { it.name },
        )
        assertTrue(plan.zipTempFile.name.endsWith(".part"))
        assertEquals("z_image_turbo_extract", plan.extractTempDir.name)
    }

    @Test
    fun `所需空间来自 approximateSize`() {
        val plan = ImageDownloadPlanner.plan(tmp.newFolder("models"), zipModel("3GB"))
        assertEquals(3L * 1024 * 1024 * 1024, plan.requiredBytes)
    }

    // ── 空间预检 ──

    @Test
    fun `空间充足时通过`() {
        val decision = SpaceCheck.decide(requiredBytes = 1_000_000_000, freeBytes = 2_000_000_000)
        assertEquals(SpaceDecision.Enough, decision)
    }

    @Test
    fun `留了三成余量`() {
        // 需要 1GB：1.3GB 够，1.29GB 不够（解压要同时占住压缩包与展开内容）。
        assertEquals(SpaceDecision.Enough, SpaceCheck.decide(1_000_000_000, 1_300_000_000))
        val tight = SpaceCheck.decide(1_000_000_000, 1_290_000_000)
        assertTrue(tight is SpaceDecision.Insufficient)
    }

    @Test
    fun `空间不足时给出差额`() {
        val decision = SpaceCheck.decide(requiredBytes = 1_000_000_000, freeBytes = 500_000_000)
        assertTrue(decision is SpaceDecision.Insufficient)
        val insufficient = decision as SpaceDecision.Insufficient
        assertEquals(1_300_000_000, insufficient.requiredBytes)
        assertEquals(800_000_000, insufficient.shortfallBytes)
    }

    @Test
    fun `体积未知时不当作充足`() {
        val decision = SpaceCheck.decide(requiredBytes = null, freeBytes = 10_000_000_000)
        assertTrue("未知必须单独一档", decision is SpaceDecision.Unknown)
        assertEquals(10_000_000_000, (decision as SpaceDecision.Unknown).freeBytes)
    }

    // ── 清理 ──

    @Test
    fun `清理清单覆盖所有 part 与临时解压目录`() {
        val plan = ImageDownloadPlanner.plan(tmp.newFolder("models"), multiFileModel())
        val stale = DownloadCleanup.staleFiles(plan).map { it.name }
        assertTrue(stale.contains("unet.bin.part"))
        assertTrue(stale.contains("clip.bin.part"))
        assertEquals(listOf("z_image_turbo_extract"), DownloadCleanup.staleDirs(plan).map { it.name })
    }
}
