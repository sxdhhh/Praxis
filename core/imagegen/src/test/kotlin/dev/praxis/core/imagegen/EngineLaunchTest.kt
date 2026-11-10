package dev.praxis.core.imagegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 引擎起法（文档 §3.1 的实测参数表 + 两个容易忽略的点）。 */
class EngineLaunchTest {

    private fun spec(
        type: String = "sd15cpu",
        libDir: String? = null,
        patch: String? = null,
    ) = EngineLaunchSpec(
        enginePath = "/data/app/lib/arm64/libstable_diffusion_core.so",
        type = type,
        modelDir = "/data/data/dev.praxis/files/models/anythingv5",
        libDir = libDir,
        patchFile = patch,
    )

    @Test
    fun `CPU 类型不带 lib_dir`() {
        // sd15cpu 是 MNN，不需要 QNN 运行时 —— 这正是「只内置引擎就能完全本地出图」的原因。
        val command = EngineLaunchRules.command(spec())
        assertTrue(!command.contains("--lib_dir"))
        assertEquals(
            listOf(
                "/data/app/lib/arm64/libstable_diffusion_core.so",
                "--type", "sd15cpu",
                "--model_dir", "/data/data/dev.praxis/files/models/anythingv5",
                "--port", "8081",
            ),
            command,
        )
    }

    @Test
    fun `QNN 类型必须给 lib_dir`() {
        // 缺了引擎直接报「Missing --lib_dir for QNN」。
        assertEquals(
            EngineLaunchError.MissingLibDir,
            EngineLaunchRules.validate(spec(type = "sd15npu")),
        )
        assertNull(
            EngineLaunchRules.validate(
                spec(type = "sd15npu", libDir = "/x"),
                libDirFiles = listOf("libQnnHtp.so", "libQnnSystem.so"),
            ),
        )
    }

    @Test
    fun `QNN 目录缺文件要拦（引擎的报错文案不指向文件名）`() {
        assertEquals(
            EngineLaunchError.IncompleteLibDir,
            EngineLaunchRules.validate(
                spec(type = "sd15npu", libDir = "/x"),
                libDirFiles = listOf("libQnnHtp.so"),
            ),
        )
    }

    @Test
    fun `sd15npu 非 512 画布必须有补丁`() {
        // 文档明写：缺了补丁引擎**静默回落 512**，所以要自己显式检查。
        assertEquals(
            EngineLaunchError.MissingPatch,
            EngineLaunchRules.validate(
                spec(type = "sd15npu", libDir = "/x"),
                needsPatch = true,
            ),
        )
        assertNull(
            EngineLaunchRules.validate(
                spec(type = "sd15npu", libDir = "/x", patch = "/p/512x768.patch"),
                needsPatch = true,
            ),
        )
    }

    @Test
    fun `needsPatch 只对 sd15npu 且非 512 成立`() {
        val npu = ImageModel(id = "m", name = "n", fileUri = "f", backendType = "sd15npu")
        val cpu = npu.copy(backendType = "sd15cpu")
        assertTrue(EngineLaunchRules.needsPatch(npu, 768))
        assertTrue(!EngineLaunchRules.needsPatch(npu, 512))
        assertTrue(!EngineLaunchRules.needsPatch(cpu, 768))
    }

    @Test
    fun `sdxl 与 anima 自动带 lowram`() {
        // 逐段加载释放：省内存、慢一些。少给只是峰值高，所以是建议不是错误。
        val sdxl = ImageModel(id = "s", name = "s", fileUri = "f", backendType = "sdxl", isSdxl = true)
        val accelerator = ImageModel(
            id = "a", name = "a", fileUri = "f", backendType = "anima", isAnima = true,
        )
        assertTrue(EngineLaunchRules.prefersLowRam(sdxl.backendType))
        assertTrue(EngineLaunchRules.prefersLowRam(accelerator.backendType))
        val command = EngineLaunchRules.command(
            EngineLaunchRules.specFor(
                model = sdxl,
                enginePath = "/e",
                modelDir = "/m",
                libDir = "/l",
            ),
        )
        assertTrue(command.contains("--lowram"))
    }

    @Test
    fun `模型目录为空要拦（否则引擎起来就报找不到文件）`() {
        assertEquals(
            EngineLaunchError.MissingModelDir,
            EngineLaunchRules.validate(spec(), modelDirEmpty = true),
        )
    }

    @Test
    fun `引擎文件不在要拦（形态 C 才有它）`() {
        assertEquals(
            EngineLaunchError.MissingEngineFile,
            EngineLaunchRules.validate(spec(), engineExists = false),
        )
    }

    @Test
    fun `类型与端口要拦`() {
        assertEquals(EngineLaunchError.UnknownType, EngineLaunchRules.validate(spec(type = "sd3")))
        assertEquals(
            EngineLaunchError.BadPort,
            EngineLaunchRules.validate(spec().copy(port = 0)),
        )
        val types = EngineLaunchRules.KNOWN_TYPES
        assertTrue(types.containsAll(listOf("sd15cpu", "sd15npu", "sdxl", "anima")))
    }

    @Test
    fun `每种错误都有人话`() {
        EngineLaunchError.entries.forEach { error ->
            assertTrue(error.userMessage().isNotBlank())
        }
    }
}
