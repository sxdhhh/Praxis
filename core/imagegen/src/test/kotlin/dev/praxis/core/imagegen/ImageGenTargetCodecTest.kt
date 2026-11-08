package dev.praxis.core.imagegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `imagegen_target` 的读写与「装完自动选中」（文档 §7.3）。
 *
 * 这里守的是两件容易做错的事：**解不开时不能当成没配过**、**用户明确选过的不能被覆盖**。
 */
class ImageGenTargetCodecTest {

    @Test
    fun `本地目标往返`() {
        val target = ImageGenTarget.Local("anythingv5")
        val text = ImageGenTargetCodec.encode(target)
        assertEquals(target, ImageGenTargetCodec.decode(text)?.getOrNull())
        assertTrue(text.contains("\"kind\":\"local\""))
    }

    @Test
    fun `远端目标往返并保留原样地址`() {
        val target = ImageGenTarget.Remote(
            provider = "openai",
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-image-1",
        )
        val text = ImageGenTargetCodec.encode(target)
        assertEquals(target, ImageGenTargetCodec.decode(text)?.getOrNull())
    }

    @Test
    fun `没配过返回 null 而不是失败`() {
        assertNull(ImageGenTargetCodec.decode(null))
        assertNull(ImageGenTargetCodec.decode(""))
        assertNull(ImageGenTargetCodec.decode("   "))
    }

    @Test
    fun `配过但内容坏了必须能看见`() {
        // 与「没配过」分开：坏了要提示用户，不能静默把他选的模型换掉。
        val broken = ImageGenTargetCodec.decode("{not json")
        assertTrue("坏内容应返回 failure", broken != null && broken.isFailure)
    }

    @Test
    fun `不认识的类型算坏内容`() {
        val unknown = ImageGenTargetCodec.decode("{\"kind\":\"cloud\"}")
        assertTrue(unknown != null && unknown.isFailure)
    }

    @Test
    fun `缺字段算坏内容`() {
        assertTrue(ImageGenTargetCodec.decode("{\"kind\":\"local\"}")!!.isFailure)
        assertTrue(ImageGenTargetCodec.decode("{\"kind\":\"remote\",\"provider\":\"x\"}")!!.isFailure)
    }

    // ── 装完自动选中 ──

    @Test
    fun `没配过时用新装的模型`() {
        val chosen = ImageGenTargetSelection.afterInstall(
            current = null,
            newModelId = "anythingv5",
            localModelExists = { true },
        )
        assertEquals(ImageGenTarget.Local("anythingv5"), chosen)
    }

    @Test
    fun `原模型目录没了时接管`() {
        val chosen = ImageGenTargetSelection.afterInstall(
            current = ImageGenTarget.Local("deleted_one"),
            newModelId = "anythingv5",
            localModelExists = { false },
        )
        assertEquals(ImageGenTarget.Local("anythingv5"), chosen)
    }

    @Test
    fun `原本地模型还在时不动用户的选择`() {
        val chosen = ImageGenTargetSelection.afterInstall(
            current = ImageGenTarget.Local("existing"),
            newModelId = "anythingv5",
            localModelExists = { true },
        )
        assertEquals(ImageGenTarget.Local("existing"), chosen)
    }

    @Test
    fun `选了远端时绝不被下载覆盖`() {
        val remote = ImageGenTarget.Remote("openai", "https://api.openai.com/v1", "gpt-image-1")
        val chosen = ImageGenTargetSelection.afterInstall(
            current = remote,
            newModelId = "anythingv5",
            localModelExists = { true },
        )
        assertEquals(remote, chosen)
    }
}
