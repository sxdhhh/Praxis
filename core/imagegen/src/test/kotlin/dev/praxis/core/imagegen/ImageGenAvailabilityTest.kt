package dev.praxis.core.imagegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设备门控 / 降档 / OOM 识别（文档 §9、§12）。 */
class ImageGenAvailabilityTest {

    // ── 门控 ──

    @Test
    fun `arm64 加认得出的芯片 → 可走 NPU`() {
        val result = ImageGenAvailabilityRules.decide(listOf("arm64-v8a", "armeabi-v7a"), "SM8650")
        assertEquals(ImageGenAvailability.NpuCapable, result)
    }

    @Test
    fun `arm64 但芯片认不出 → 只走 CPU 包，不是不支持`() {
        // 编一份「一定支持」的白名单会让本来能用的设备被挡在门外。
        val result = ImageGenAvailabilityRules.decide(listOf("arm64-v8a"), "天玑9300")
        assertTrue(result is ImageGenAvailability.CpuOnly)
        assertEquals("天玑9300", (result as ImageGenAvailability.CpuOnly).soc)
    }

    @Test
    fun `认得出前缀但不在表里 → 用兜底档位，仍算 NPU 可用`() {
        assertEquals(
            ImageGenAvailability.NpuCapable,
            ImageGenAvailabilityRules.decide(listOf("arm64-v8a"), "SM9999"),
        )
    }

    @Test
    fun `不是 arm64 → 明确不支持并说清原因`() {
        val result = ImageGenAvailabilityRules.decide(listOf("x86_64"), "SM8650")
        assertTrue(result is ImageGenAvailability.Unsupported)
        assertTrue((result as ImageGenAvailability.Unsupported).reason.contains("arm64"))
    }

    @Test
    fun `ABI 列表为空也不崩`() {
        assertTrue(ImageGenAvailabilityRules.decide(emptyList(), "SM8650") is ImageGenAvailability.Unsupported)
    }

    @Test
    fun `ABI 写法不完全一致也能认出来`() {
        assertEquals(
            ImageGenAvailability.NpuCapable,
            ImageGenAvailabilityRules.decide(listOf("ARM64-V8A"), "SM8550"),
        )
    }

    // ── 降档 ──

    @Test
    fun `两个方向都缩一半并对齐 64`() {
        assertEquals(256 to 256, ImageGenDownscale.smaller(512, 512))
        assertEquals(384 to 256, ImageGenDownscale.smaller(768, 512))
    }

    @Test
    fun `只有一个方向能缩时只缩它`() {
        // 512×256：高度已在下限，只缩宽度得到 256×256（面积仍然变小）。
        assertEquals(256 to 256, ImageGenDownscale.smaller(512, 256))
        assertEquals(256 to 256, ImageGenDownscale.smaller(256, 512))
    }

    @Test
    fun `已经最小就不能再降`() {
        // 上层据此直接报错，而不是无限重试。
        assertNull(ImageGenDownscale.smaller(256, 256))
        assertNull(ImageGenDownscale.smaller(200, 200))
    }

    @Test
    fun `非 64 倍数也能降出合法尺寸`() {
        val (w, h) = ImageGenDownscale.smaller(300, 300)!!
        assertEquals(0, w % 64)
        assertEquals(0, h % 64)
        assertTrue(w >= ImageGenDownscale.MIN_DIMENSION)
        assertTrue(w < 300)
    }

    // ── OOM 识别 ──

    @Test
    fun `认得各种 OOM 措辞`() {
        assertTrue(ImageGenErrorClassifier.isOutOfMemory("CUDA out of memory"))
        assertTrue(ImageGenErrorClassifier.isOutOfMemory("Failed to allocate buffer"))
        assertTrue(ImageGenErrorClassifier.isOutOfMemory("INSUFFICIENT MEMORY for model"))
        assertTrue(ImageGenErrorClassifier.isOutOfMemory("显存不足"))
        assertTrue(ImageGenErrorClassifier.isOutOfMemory("内存不足，无法分配"))
    }

    @Test
    fun `普通错误不会被误判成 OOM`() {
        // 误判的代价是「该报错的被当成可重试」，比漏判更糟。
        assertFalse(ImageGenErrorClassifier.isOutOfMemory("HTTP 500"))
        assertFalse(ImageGenErrorClassifier.isOutOfMemory("model not found"))
        assertFalse(ImageGenErrorClassifier.isOutOfMemory(null))
        assertFalse(ImageGenErrorClassifier.isOutOfMemory(""))
    }
}
