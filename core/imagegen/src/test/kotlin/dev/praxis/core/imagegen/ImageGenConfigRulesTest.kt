package dev.praxis.core.imagegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `set_image_gen_config` 的边界与 diff（文档 §5.6）。 */
class ImageGenConfigRulesTest {

    // ── 越界键 ──

    @Test
    fun `凭据与下载源不许改`() {
        assertEquals(
            listOf("apiKey", "baseUrl"),
            ImageGenConfigRules.notAllowedKeys(listOf("apiKey", "steps", "baseUrl")),
        )
    }

    @Test
    fun `下划线写法也拦得住`() {
        // 模型很爱把 apiKey 写成 api_key；只认一种写法等于留了个绕过口子。
        assertEquals(1, ImageGenConfigRules.notAllowedKeys(listOf("api_key")).size)
        assertEquals(1, ImageGenConfigRules.notAllowedKeys(listOf("BASE_URL")).size)
    }

    @Test
    fun `删模型与下载模型一律拦下`() {
        assertEquals(2, ImageGenConfigRules.notAllowedKeys(listOf("deleteModel", "download_model")).size)
    }

    @Test
    fun `引擎来源不许改`() {
        assertEquals(1, ImageGenConfigRules.notAllowedKeys(listOf("engineSource")).size)
    }

    @Test
    fun `正常字段不被误拦`() {
        assertTrue(
            ImageGenConfigRules.notAllowedKeys(
                listOf("target", "width", "height", "steps", "cfg", "scheduler", "negative"),
            ).isEmpty(),
        )
    }

    // ── 还没实现的字段 ──

    @Test
    fun `输出分区的两个字段被识别为未实现`() {
        assertEquals(
            listOf("autoSave", "showPreview"),
            ImageGenConfigRules.notImplementedKeys(listOf("autoSave", "steps", "showPreview")),
        )
    }

    @Test
    fun `认识的键集合包含三类`() {
        assertTrue(ImageGenConfigRules.isKnownKey("steps"))
        assertTrue(ImageGenConfigRules.isKnownKey("autoSave"))
        assertTrue(ImageGenConfigRules.isKnownKey("apiKey"))
        assertTrue(!ImageGenConfigRules.isKnownKey("whatever"))
    }

    // ── diff ──

    @Test
    fun `diff 只列出真的变了的字段`() {
        val before = ImageGenDefaults(steps = 20, width = 512)
        val after = before.copy(steps = 30)
        val changes = ImageGenConfigDiff.defaults(before, after)
        assertEquals(1, changes.size)
        assertEquals("steps", changes[0].field)
        assertEquals("20", changes[0].from)
        assertEquals("30", changes[0].to)
    }

    @Test
    fun `没有任何变化时 diff 为空`() {
        val same = ImageGenDefaults()
        assertTrue(ImageGenConfigDiff.defaults(same, same).isEmpty())
    }

    @Test
    fun `目标 diff 用紧凑写法`() {
        val change = ImageGenConfigDiff.target(
            before = ImageGenTarget.Local("anythingv5"),
            after = ImageGenTarget.Remote("openai", "https://api.openai.com/v1", "gpt-image-1"),
        )
        assertEquals("target", change?.field)
        assertEquals("local:anythingv5", change?.from)
        assertEquals("remote:openai/gpt-image-1", change?.to)
    }

    @Test
    fun `目标没变时返回 null`() {
        val target = ImageGenTarget.Local("a")
        assertNull(ImageGenConfigDiff.target(target, target))
    }

    @Test
    fun `从没配过到有目标也算变化`() {
        val change = ImageGenConfigDiff.target(null, ImageGenTarget.Local("a"))
        assertEquals("local:a", change?.to)
        assertEquals("", change?.from)
    }
}
