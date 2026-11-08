package dev.praxis.core.imagegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 生图默认值（文档 §7.1 的画布/采样）。 */
class ImageGenDefaultsTest {

    @Test
    fun `默认值与参数层一致`() {
        val defaults = ImageGenDefaults()
        assertEquals(ImageGenParams.DEFAULT_WIDTH, defaults.width)
        assertEquals(ImageGenParams.DEFAULT_HEIGHT, defaults.height)
        assertEquals(ImageGenParams.DEFAULT_STEPS, defaults.steps)
        assertNull("默认不指定调度器", defaults.scheduler)
    }

    @Test
    fun `范围校验`() {
        assertNull(ImageGenDefaultsRules.validate(ImageGenDefaults(width = 768, height = 768, steps = 30)))
        assertTrue(ImageGenDefaultsRules.validate(ImageGenDefaults(width = 200)) != null)
        assertTrue(ImageGenDefaultsRules.validate(ImageGenDefaults(steps = 0)) != null)
        assertTrue(ImageGenDefaultsRules.validate(ImageGenDefaults(cfg = 0.5f)) != null)
    }

    @Test
    fun `CFG 是 NaN 时被拒`() {
        assertTrue(ImageGenDefaultsRules.validate(ImageGenDefaults(cfg = Float.NaN)) != null)
    }

    @Test
    fun `编解码往返`() {
        val defaults = ImageGenDefaults(
            width = 768,
            height = 512,
            steps = 30,
            cfg = 6.5f,
            negative = "blurry",
            scheduler = "euler",
        )
        assertEquals(defaults, ImageGenDefaultsCodec.decode(ImageGenDefaultsCodec.encode(defaults))?.getOrNull())
    }

    @Test
    fun `没配过返回 null`() {
        assertNull(ImageGenDefaultsCodec.decode(null))
        assertNull(ImageGenDefaultsCodec.decode("   "))
    }

    @Test
    fun `越界值算坏内容而不是静默夹到边界`() {
        // 静默夹会让用户以为「我设的 2000 生效了」。
        assertTrue(
            ImageGenDefaultsCodec.decode("""{"width":2000,"height":512,"steps":20,"cfg":7}""")
                ?.isFailure == true,
        )
    }

    @Test
    fun `缺字段时用默认值（schema 演进）`() {
        val decoded = ImageGenDefaultsCodec.decode("""{"steps":25}""")?.getOrNull()
        assertEquals(25, decoded?.steps)
        assertEquals(ImageGenParams.DEFAULT_WIDTH, decoded?.width)
    }

    @Test
    fun `类型错算坏内容`() {
        assertTrue(
            ImageGenDefaultsCodec.decode("""{"steps":"很多"}""")?.isFailure == true,
        )
    }

    @Test
    fun `空白负向提示词按空串处理`() {
        val decoded = ImageGenDefaultsCodec.decode("""{"negative":"   "}""")?.getOrNull()
        assertEquals("   ", decoded?.negative)
    }
}
