package dev.praxis.core.imagegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 输出设置（文档 §7.1）。 */
class ImageOutputSettingsTest {

    @Test
    fun `默认不自动存相册`() {
        // 不打招呼就往用户相册里塞图是失礼的。
        assertEquals(false, ImageOutputSettings().autoSave)
    }

    @Test
    fun `往返`() {
        val settings = ImageOutputSettings(autoSave = true, showPreview = true)
        assertEquals(
            settings,
            ImageOutputSettingsCodec.decode(ImageOutputSettingsCodec.encode(settings))?.getOrNull(),
        )
    }

    @Test
    fun `没配过返回 null`() {
        assertNull(ImageOutputSettingsCodec.decode(null))
        assertNull(ImageOutputSettingsCodec.decode("  "))
    }

    @Test
    fun `缺字段时用默认值（schema 演进）`() {
        val decoded = ImageOutputSettingsCodec.decode("""{"autoSave":true}""")?.getOrNull()
        assertEquals(true, decoded?.autoSave)
        assertEquals(false, decoded?.showPreview)
    }

    @Test
    fun `类型错算坏内容`() {
        assertTrue(ImageOutputSettingsCodec.decode("""{"autoSave":"yes"}""")?.isFailure == true)
    }

    @Test
    fun `存相册的三种结果各自可区分`() {
        // 「这台机器做不到」与「写盘失败」要给不同的话（前者用户要换方式，后者可以重试）。
        val saved: GallerySaveResult = GallerySaveResult.Saved("content://x/1")
        val unsupported: GallerySaveResult = GallerySaveResult.Unsupported
        val failed: GallerySaveResult = GallerySaveResult.Failed("磁盘满")
        assertTrue(saved is GallerySaveResult.Saved)
        assertTrue(unsupported is GallerySaveResult.Unsupported)
        assertTrue(failed is GallerySaveResult.Failed)
    }
}
