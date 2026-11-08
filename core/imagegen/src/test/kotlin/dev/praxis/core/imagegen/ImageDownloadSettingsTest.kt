package dev.praxis.core.imagegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 下载源设置（文档 §7.2）。 */
class ImageDownloadSettingsTest {

    // ── 归一化 ──

    @Test
    fun `尾部斜杠会被补上`() {
        // fileUri 拼在 baseUrl 后面：少了斜杠就会拼成 https://github.comxororz/...
        assertEquals("https://github.com/", ImageDownloadSettingsRules.normalizeBaseUrl("https://github.com"))
        assertEquals("https://github.com/", ImageDownloadSettingsRules.normalizeBaseUrl("https://github.com/"))
        assertEquals("https://github.com/", ImageDownloadSettingsRules.normalizeBaseUrl("  https://github.com///  "))
    }

    @Test
    fun `空串回落到默认源`() {
        assertEquals(
            ImageDownloadSettings.DEFAULT_BASE_URL,
            ImageDownloadSettingsRules.normalizeBaseUrl("   "),
        )
    }

    // ── 校验 ──

    @Test
    fun `只接受 http 与 https`() {
        assertTrue(ImageDownloadSettingsRules.isValidBaseUrl("https://hf-mirror.com"))
        assertTrue(ImageDownloadSettingsRules.isValidBaseUrl("http://192.168.1.2:8080"))
        assertFalse(ImageDownloadSettingsRules.isValidBaseUrl("hf-mirror.com"))
        assertFalse(ImageDownloadSettingsRules.isValidBaseUrl("ftp://x"))
        assertFalse(ImageDownloadSettingsRules.isValidBaseUrl("https://"))
    }

    // ── 编解码 ──

    @Test
    fun `设置往返`() {
        val settings = ImageDownloadSettings(baseUrl = "https://hf-mirror.com/", wifiOnly = false)
        assertEquals(
            settings,
            ImageDownloadSettingsCodec.decode(ImageDownloadSettingsCodec.encode(settings))?.getOrNull(),
        )
    }

    @Test
    fun `没配过返回 null`() {
        assertNull(ImageDownloadSettingsCodec.decode(null))
        assertNull(ImageDownloadSettingsCodec.decode(""))
    }

    @Test
    fun `坏地址算坏内容而不是悄悄用默认源`() {
        // 静默回默认源会让用户以为「我换的镜像没生效」，其实是他填错了。
        assertTrue(
            ImageDownloadSettingsCodec
                .decode("""{"baseUrl":"hf-mirror.com","wifiOnly":true}""")
                ?.isFailure == true,
        )
    }

    @Test
    fun `wifiOnly 类型错算坏内容`() {
        assertTrue(
            ImageDownloadSettingsCodec
                .decode("""{"baseUrl":"https://x.com","wifiOnly":"yes"}""")
                ?.isFailure == true,
        )
    }

    @Test
    fun `缺 baseUrl 时用默认源（schema 演进）`() {
        val decoded = ImageDownloadSettingsCodec.decode("""{"wifiOnly":false}""")?.getOrNull()
        assertEquals(ImageDownloadSettings.DEFAULT_BASE_URL, decoded?.baseUrl)
        assertFalse(decoded?.wifiOnly == true)
    }

    @Test
    fun `默认值符合文档`() {
        // 默认仅 Wi-Fi：几 GB 的包走流量是能让人肉疼的失误。
        val defaults = ImageDownloadSettings()
        assertTrue(defaults.wifiOnly)
        assertEquals("https://github.com/", defaults.baseUrl)
    }
}
