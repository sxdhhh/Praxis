package dev.praxis.core.imagegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `generate_image` 结果的解析（聊天层据此把图挂进气泡）。
 *
 * 守的是「失败结果不能被当成有图」与「坏条目不能拖垮整条」。
 */
class GenerateImageResultCodecTest {

    @Test
    fun `单张图的成功结果`() {
        val json = """{"ok":true,"model":"anythingv5","count":1,"path":"/d/gen_1.jpg","images":[{"path":"/d/gen_1.jpg","seed":42,"width":512,"height":512}]}"""
        val parsed = GenerateImageResultCodec.parse(json)
        assertEquals(1, parsed?.size)
        assertEquals("/d/gen_1.jpg", parsed!![0].path)
        assertEquals(42L, parsed[0].seed)
        assertEquals(512, parsed[0].width)
    }

    @Test
    fun `多张图按顺序返回`() {
        val json = """{"ok":true,"count":2,"images":[{"path":"/a.jpg","seed":1},{"path":"/b.jpg","seed":2}]}"""
        val parsed = GenerateImageResultCodec.parse(json)
        assertEquals(listOf("/a.jpg", "/b.jpg"), parsed?.map { it.path })
    }

    @Test
    fun `只有顶层 path 时也能用`() {
        val json = """{"ok":true,"path":"/only.jpg"}"""
        assertEquals(listOf("/only.jpg"), GenerateImageResultCodec.parse(json)?.map { it.path })
    }

    @Test
    fun `失败结果不返回图片`() {
        val json = """{"ok":false,"error":"engine_start_failed","message":"引擎不可用"}"""
        assertNull("失败结果不能被当成有图", GenerateImageResultCodec.parse(json))
    }

    @Test
    fun `非 JSON 与空串返回 null`() {
        assertNull(GenerateImageResultCodec.parse("工具跑完了"))
        assertNull(GenerateImageResultCodec.parse(""))
        assertNull(GenerateImageResultCodec.parse("{"))
    }

    @Test
    fun `成功但没有任何路径时返回 null`() {
        assertNull(GenerateImageResultCodec.parse("""{"ok":true}"""))
    }

    @Test
    fun `images 数组里的坏条目被跳过而不是整条失败`() {
        val json = """{"ok":true,"images":[{"path":""},{"path":"/good.jpg"}]}"""
        assertEquals(listOf("/good.jpg"), GenerateImageResultCodec.parse(json)?.map { it.path })
    }

    @Test
    fun `ok 缺失时按失败处理`() {
        assertTrue(GenerateImageResultCodec.parse("""{"path":"/x.jpg"}""") == null)
    }
}
