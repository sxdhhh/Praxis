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
 * 模型目录与安装态的规格（文档 §8.1–§8.4）。
 *
 * 两件事最容易错：**档位后缀选错**（下到跑不动的包）与**安装态判错**
 * （半成品包被当成装好了，引擎在加载阶段报一个很难懂的错误）。
 */
class ImageModelCatalogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── 档位 ──

    @Test
    fun `精确匹配按表返回`() {
        assertEquals("8gen1", ChipsetTier.suffixFor("SM8450"))
        assertEquals("8gen2", ChipsetTier.suffixFor("SM8650"))
        assertEquals("8gen2", ChipsetTier.suffixFor("qcs8550"))
    }

    @Test
    fun `认得的骁龙型号不精确匹配时回落 min`() {
        assertEquals("min", ChipsetTier.suffixFor("SM9999"))
    }

    @Test
    fun `非骁龙型号没有档位`() {
        assertNull(ChipsetTier.suffixFor("MT6989"))
        assertNull(ChipsetTier.suffixFor(null))
        assertNull(ChipsetTier.suffixFor("  "))
    }

    @Test
    fun `下载路径用的档位在无档位时回落 min`() {
        assertEquals("min", ChipsetTier.downloadSuffix("MT6989"))
        assertEquals("8gen2", ChipsetTier.downloadSuffix("SM8650"))
    }

    // ── 目录 ──

    @Test
    fun `内置目录的 NPU 包路径带档位后缀`() {
        val models = ImageModelCatalog.builtin("SM8650")
        val anything = models.first { it.id == "anythingv5" }
        assertTrue(anything.fileUri.endsWith("_8gen2.zip"))
        assertEquals("sd15npu", anything.backendType)
    }

    @Test
    fun `CPU 包路径不带档位`() {
        val cpu = ImageModelCatalog.builtin("SM8650").first { it.id == "anythingv5cpu" }
        assertFalse(cpu.fileUri.contains("8gen"))
        assertTrue(cpu.runOnCpu)
    }

    @Test
    fun `没有芯片信息时按 min 档位生成而不是空路径`() {
        val models = ImageModelCatalog.builtin(null)
        assertTrue(models.first { it.id == "anythingv5" }.fileUri.endsWith("_min.zip"))
    }

    // ── 安装态 ──

    @Test
    fun `目录不存在时未安装`() {
        val root = tmp.newFolder("models")
        val model = ImageModelCatalog.builtin("SM8650").first()
        assertFalse(ImageModelCatalog.isInstalled(root, model))
    }

    @Test
    fun `单 zip 包只要目录非空就算装好`() {
        val root = tmp.newFolder("models")
        val model = ImageModelCatalog.builtin("SM8650").first { it.id == "anythingv5cpu" }
        val dir = File(root, model.id).apply { mkdirs() }
        assertFalse("空目录不算装好", ImageModelCatalog.isInstalled(root, model))
        File(dir, "unet.mnn").writeText("x")
        assertTrue(ImageModelCatalog.isInstalled(root, model))
    }

    @Test
    fun `多文件包缺标记文件时未安装`() {
        val root = tmp.newFolder("models")
        val model = ImageModel(
            id = "z_image_turbo",
            name = "Z-Image Turbo",
            fileUri = "x",
            backendType = "zimage",
            ditKind = "zimage",
            packageFiles = listOf("remote/a.bin|a.bin"),
        )
        val dir = File(root, model.id).apply { mkdirs() }
        File(dir, "a.bin").writeText("weight")
        assertFalse(ImageModelCatalog.isInstalled(root, model))
        File(dir, "ZIMAGE").writeText("")
        assertTrue(ImageModelCatalog.isInstalled(root, model))
    }

    @Test
    fun `多文件包缺某个文件或文件为空时未安装`() {
        val root = tmp.newFolder("models")
        val model = ImageModel(
            id = "qwen_image_2_1",
            name = "Qwen Image 2.1",
            fileUri = "x",
            backendType = "qwen21",
            ditKind = "qwen21",
            packageFiles = listOf("remote/a.bin|a.bin", "remote/b.bin|b.bin"),
        )
        val dir = File(root, model.id).apply { mkdirs() }
        File(dir, "QWEN_IMAGE_2_1").writeText("")
        File(dir, "a.bin").writeText("w")
        assertFalse("少了 b.bin", ImageModelCatalog.isInstalled(root, model))
        File(dir, "b.bin").writeText("")
        assertFalse("b.bin 是空文件也不能算装好", ImageModelCatalog.isInstalled(root, model))
        File(dir, "b.bin").writeText("w")
        assertTrue(ImageModelCatalog.isInstalled(root, model))
    }

    @Test
    fun `多文件包的本地文件名取竖线右侧`() {
        val model = ImageModel(
            id = "m",
            name = "m",
            fileUri = "x",
            backendType = "zimage",
            ditKind = "zimage",
            packageFiles = listOf("remote/dir/weight.bin|weight.bin"),
        )
        assertEquals("weight.bin", model.localNameOf(model.packageFiles.first()))
    }

    @Test
    fun `标记文件名与上游一致`() {
        assertEquals("ZIMAGE", ImageModelCatalog.markerFileName("zimage"))
        assertEquals("QWEN_IMAGE_2_1", ImageModelCatalog.markerFileName("qwen21"))
        assertEquals("", ImageModelCatalog.markerFileName("unknown"))
    }
}
