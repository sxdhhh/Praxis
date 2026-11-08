package dev.praxis.core.imagegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 参数默认值与合法范围（文档 §5.1 / §4.2）。
 *
 * ## 为什么尺寸要按「模型档位」校验（文档 §5.1 末段）
 *
 * SD1.5 的 NPU 只能跑 patch 覆盖到的档位，请求一个没有 patch 的尺寸，
 * 引擎会**静默回落 512 且不报错**。不在这里拦，用户拿到的是一张尺寸不对的图，
 * 而不是一句「这个尺寸没有」。
 */
class ImageGenParamsTest {

    /** 断言「一定是 invalid_size」，并顺手把类型收窄，省掉每个用例里的显式转换。 */
    private fun invalidSize(
        p: ImageGenParams,
        availableSizes: List<Pair<Int, Int>> = emptyList(),
    ): ImageGenError.InvalidSize {
        val error = ImageGenParamsRules.validate(p, availableSizes)
        assertTrue("期望 invalid_size，实际是 " + error, error is ImageGenError.InvalidSize)
        return error as ImageGenError.InvalidSize
    }

    @Test
    fun 默认值照文档4_2() {
        val p = ImageGenParams(prompt = "cat")

        assertEquals("", p.negative)
        assertEquals(512, p.width)
        assertEquals(512, p.height)
        assertEquals(20, p.steps)
        assertEquals(7f, p.cfg)
        assertEquals(1f, p.denoise)
        assertEquals(1, p.previewStride)
        assertNull(p.scheduler)
        assertNull(p.seed)
        assertFalse(p.showPreview)
        assertFalse(p.useOpenCL)
    }

    @Test
    fun 范围常量照文档5_1() {
        assertEquals(256, ImageGenParamsRules.WIDTH_RANGE.first)
        assertEquals(1024, ImageGenParamsRules.WIDTH_RANGE.last)
        assertEquals(256, ImageGenParamsRules.HEIGHT_RANGE.first)
        assertEquals(1024, ImageGenParamsRules.HEIGHT_RANGE.last)
        assertEquals(1, ImageGenParamsRules.STEPS_RANGE.first)
        assertEquals(60, ImageGenParamsRules.STEPS_RANGE.last)
        assertEquals(1f, ImageGenParamsRules.CFG_RANGE.start)
        assertEquals(20f, ImageGenParamsRules.CFG_RANGE.endInclusive)
        assertEquals(0f, ImageGenParamsRules.DENOISE_RANGE.start)
        assertEquals(1f, ImageGenParamsRules.DENOISE_RANGE.endInclusive)
    }

    @Test
    fun 合法参数返回null() {
        assertNull(ImageGenParamsRules.validate(ImageGenParams(prompt = "cat")))
    }

    @Test
    fun 边界值本身合法() {
        assertNull(ImageGenParamsRules.validate(ImageGenParams(prompt = "a", width = 256, height = 256)))
        assertNull(ImageGenParamsRules.validate(ImageGenParams(prompt = "a", width = 1024, height = 1024)))
        assertNull(ImageGenParamsRules.validate(ImageGenParams(prompt = "a", steps = 1)))
        assertNull(ImageGenParamsRules.validate(ImageGenParams(prompt = "a", steps = 60)))
        assertNull(ImageGenParamsRules.validate(ImageGenParams(prompt = "a", cfg = 1f)))
        assertNull(ImageGenParamsRules.validate(ImageGenParams(prompt = "a", cfg = 20f)))
        assertNull(ImageGenParamsRules.validate(ImageGenParams(prompt = "a", denoise = 0f)))
    }

    @Test
    fun 宽度低于下限报invalid_size() {
        val error = invalidSize(ImageGenParams(prompt = "a", width = 128))

        assertEquals(ImageGenError.CODE_INVALID_SIZE, error.code)
        assertEquals(EngineProtocol.RequestField.WIDTH, error.parameter)
        assertEquals("128", error.requested)
        assertNotNull(error.detail)
    }

    @Test
    fun 高度超过上限报invalid_size() {
        assertEquals(
            EngineProtocol.RequestField.HEIGHT,
            invalidSize(ImageGenParams(prompt = "a", height = 2048)).parameter,
        )
    }

    @Test
    fun 步数与CFG越界也被拦住() {
        assertEquals(
            EngineProtocol.RequestField.STEPS,
            invalidSize(ImageGenParams(prompt = "a", steps = 0)).parameter,
        )
        assertEquals(
            EngineProtocol.RequestField.CFG,
            invalidSize(ImageGenParams(prompt = "a", cfg = 25f)).parameter,
        )
    }

    @Test
    fun cfg不是有限数也被拦住() {
        assertEquals(
            EngineProtocol.RequestField.CFG,
            invalidSize(ImageGenParams(prompt = "a", cfg = Float.NaN)).parameter,
        )
    }

    @Test
    fun 去噪强度超界也被拦住() {
        assertEquals(
            EngineProtocol.RequestField.DENOISE_STRENGTH,
            invalidSize(ImageGenParams(prompt = "a", denoise = 1.5f)).parameter,
        )
    }

    @Test
    fun 预览关闭时不校验stride() {
        // 关预览时 stride 根本不写进请求，拦它只会是噪音。
        assertNull(ImageGenParamsRules.validate(ImageGenParams(prompt = "a", showPreview = false, previewStride = 0)))
    }

    @Test
    fun 预览开启时stride为0被拦住() {
        assertEquals(
            EngineProtocol.RequestField.SHOW_DIFFUSION_STRIDE,
            invalidSize(ImageGenParams(prompt = "a", showPreview = true, previewStride = 0)).parameter,
        )
    }

    @Test
    fun 档位一致时通过() {
        val sizes = listOf(512 to 512, 512 to 768, 768 to 512)

        assertNull(ImageGenParamsRules.validate(ImageGenParams(prompt = "a", width = 512, height = 768), sizes))
    }

    @Test
    fun 模型没有这一档时报错并列出可用档位() {
        val sizes = listOf(512 to 512, 512 to 768)

        val error = invalidSize(ImageGenParams(prompt = "a", width = 640, height = 640), sizes)

        assertEquals(sizes, error.availableSizes)
        assertEquals("640x640", error.requested)
        assertTrue("文案里要有可用档位：" + error.detail, error.detail!!.contains("512x768"))
    }

    @Test
    fun 档位清单为空时只做全局范围校验() {
        // 还没读到模型清单（或 CPU 后端不限档位）时，不能凭空空转成「全部不合法」。
        assertNull(ImageGenParamsRules.validate(ImageGenParams(prompt = "a", width = 1024, height = 256), emptyList()))
    }

    @Test
    fun 全局范围先于档位判定() {
        // 64 既不在全局范围也可能不在档位里；先报全局范围，提示更准确（不带档位清单）。
        val error = invalidSize(
            ImageGenParams(prompt = "a", width = 64, height = 64),
            listOf(512 to 512),
        )

        assertEquals("64", error.requested)
        assertTrue(error.availableSizes.isEmpty())
    }

    @Test
    fun 空提示词不由本规则拦() {
        // prompt 的必填由工具的参数 schema 管（文档 §5.1 的 required）；这里只管数值。
        assertNull(ImageGenParamsRules.validate(ImageGenParams(prompt = "")))
    }

    @Test
    fun 档位文案可读() {
        assertEquals(
            "512x512 / 512x768",
            ImageGenParamsRules.formatSizes(listOf(512 to 512, 512 to 768)),
        )
    }

    @Test
    fun 错误码互不相同且是文档里的词() {
        val codes = listOf(
            ImageGenError.NoModel().code,
            ImageGenError.Oom().code,
            ImageGenError.UnsupportedDevice().code,
            ImageGenError.EngineStartFailed().code,
            ImageGenError.InvalidSize().code,
            ImageGenError.ToolBusy().code,
            ImageGenError.Remote().code,
        )

        assertEquals("错误码是 ToolResult 的分类键，重复就等于丢了一档", codes.size, codes.toSet().size)
        assertEquals(
            listOf("no_model", "oom", "unsupported_device", "engine_start_failed", "invalid_size", "tool_busy", "remote"),
            codes,
        )
        assertEquals("模型 x 未安装", ImageGenError.NoModel(modelId = "x").detail)
        assertNull(ImageGenError.NoModel().detail)
    }
}
