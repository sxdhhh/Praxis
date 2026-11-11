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
    fun `3-0-0 的新类型都认得`() {
        // 自编 3.0.0 的 usage（从二进制里抽出）：多了 sdxlmnn 与 4 个 DiT 型。
        val types = EngineLaunchRules.KNOWN_TYPES
        listOf("sdxlmnn", "zimage", "klein", "qwen21", "krea2turbo").forEach { type ->
            assertTrue("应当认这个类型：" + type, type in types)
        }
    }

    @Test
    fun `sdxlmnn 是 MNN 不需要 QNN 库`() {
        assertTrue(!EngineLaunchRules.requiresQnn("sdxlmnn"))
        assertNull(EngineLaunchRules.validate(spec(type = "sdxlmnn")))
        assertTrue(!EngineLaunchRules.command(spec(type = "sdxlmnn")).contains("--lib_dir"))
    }

    @Test
    fun `DiT 型缺 libdit_engine 时要拦（本机自编那份就没产出它）`() {
        val error = EngineLaunchRules.validate(
            spec(type = "zimage", libDir = "/dit"),
            ditEngineAvailable = false,
        )
        assertEquals(EngineLaunchError.MissingDitEngine, error)
        assertTrue(error!!.userMessage().contains("DiT"))
        // 有它就能过（validate 不该顺手把 DiT 判死）。
        assertNull(
            EngineLaunchRules.validate(
                spec(type = "zimage", libDir = "/dit"),
                ditEngineAvailable = true,
            ),
        )
    }

    @Test
    fun `qnn_lib_dir 只在给了的时候才拼进命令`() {
        // 3.0.0 起才有这个参数（DiT 进程内放大用）。
        val without = EngineLaunchRules.command(spec(type = "zimage", libDir = "/dit"))
        assertTrue(!without.contains("--qnn_lib_dir"))
        val with = EngineLaunchRules.command(
            spec(type = "zimage", libDir = "/dit").copy(qnnLibDir = "/qnn"),
        )
        val index = with.indexOf("--qnn_lib_dir")
        assertTrue(index > 0)
        assertEquals("/qnn", with[index + 1])
        // 顺序：--lib_dir 在 --port 之前，--qnn_lib_dir 紧跟 --lib_dir 之后。
        assertTrue(with.indexOf("--lib_dir") < index)
        assertTrue(index < with.indexOf("--port"))
    }

    @Test
    fun `isDitType 只认那四个`() {
        assertTrue(EngineLaunchRules.isDitType("zimage"))
        assertTrue(EngineLaunchRules.isDitType("krea2turbo"))
        assertTrue(!EngineLaunchRules.isDitType("sdxl"))
        assertTrue(!EngineLaunchRules.isDitType("sd15cpu"))
    }

    @Test
    fun `每种错误都有人话`() {
        EngineLaunchError.entries.forEach { error ->
            assertTrue(error.userMessage().isNotBlank())
        }
    }
}
