package dev.praxis.core.imagegen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * `POST /generate` 的请求序列化（文档 §3.2）。
 *
 * ## 为什么逐字断言字段名
 *
 * 字段名错了不会报错，只会「HTTP 200 但没有图」或者「参数被静默忽略」：
 * 引擎用 nlohmann/json 的 `.value()` 取值，缺键就走默认值，改名等于回到默认。
 * 也就是说**改名是一种静默失效**，只有把字面量钉在测试里才挡得住。
 *
 * 同一个理由下另外两条也钉住：可选字段**省略**（而不是写 null）、
 * 数值**原样**写（而不是「等于默认值就省掉」）。
 */
class ImageGenRequestTest {

    private fun jsonOf(p: ImageGenParams): JsonObject =
        Json.parseToJsonElement(ImageGenRequest.toJson(p)).jsonObject

    private fun JsonObject.primitive(key: String): JsonPrimitive = this[key] as JsonPrimitive

    @Test
    fun 文档3_2的字段名逐字不变() {
        val expected = listOf(
            "prompt", "negative_prompt", "steps", "cfg", "width", "height",
            "scheduler", "seed", "denoise_strength", "use_opencl", "preview_format",
            "show_diffusion_process", "show_diffusion_stride", "aspect_ratio",
            "image", "mask", "ultrafix", "tile_size", "output_format",
        )

        assertEquals("字段名就是引擎契约，改名等于请求静默失效", expected, EngineProtocol.RequestField.ALL)
    }

    @Test
    fun 端点与默认端口是文档3_1的值() {
        assertEquals(8081, EngineProtocol.DEFAULT_PORT)
        assertEquals("http://127.0.0.1:8081", EngineProtocol.baseUrl())
        assertEquals("http://127.0.0.1:8081/health", EngineProtocol.healthUrl())
        assertEquals("http://127.0.0.1:8081/generate", EngineProtocol.generateUrl())
        assertEquals("http://127.0.0.1:9099/generate", EngineProtocol.generateUrl(9099))
    }

    @Test
    fun 必填字段与固定字段都写进JSON() {
        val json = jsonOf(ImageGenParams(prompt = "a cat"))

        val expected = listOf(
            "prompt", "negative_prompt", "steps", "cfg", "width", "height",
            "denoise_strength", "use_opencl", "preview_format", "show_diffusion_process",
            "output_format",
        )
        assertEquals(expected, json.keys.toList())
    }

    @Test
    fun 数值原样写入不被默认值污染() {
        val p = ImageGenParams(prompt = "cat", width = 640, height = 768, steps = 33, cfg = 6.5f, denoise = 0.65f)
        val json = jsonOf(p)

        assertEquals(640, json.primitive("width").int)
        assertEquals(768, json.primitive("height").int)
        assertEquals(33, json.primitive("steps").int)
        assertEquals(6.5f, json.primitive("cfg").float)
        assertEquals(0.65f, json.primitive("denoise_strength").float)
    }

    @Test
    fun 省略scheduler时不写进JSON() {
        // null 的语义是「用引擎默认调度器」，写成 null 或空串都会变成另一个值。
        assertFalse(jsonOf(ImageGenParams(prompt = "cat")).containsKey("scheduler"))
    }

    @Test
    fun 空白scheduler也当作省略() {
        assertFalse(jsonOf(ImageGenParams(prompt = "cat", scheduler = "   ")).containsKey("scheduler"))
    }

    @Test
    fun 写了scheduler就原样写入() {
        assertEquals("euler_a", jsonOf(ImageGenParams(prompt = "cat", scheduler = "euler_a")).primitive("scheduler").content)
    }

    @Test
    fun 省略seed时不写进JSON() {
        assertFalse(jsonOf(ImageGenParams(prompt = "cat")).containsKey("seed"))
    }

    @Test
    fun seed为0也必须写进JSON() {
        // 0 是合法种子。把它当「缺省」省掉，用户就再也复现不了这张图了。
        assertEquals(0L, jsonOf(ImageGenParams(prompt = "cat", seed = 0L)).primitive("seed").long)
    }

    @Test
    fun seed原样写入() {
        assertEquals(2001048L, jsonOf(ImageGenParams(prompt = "cat", seed = 2001048L)).primitive("seed").long)
    }

    @Test
    fun 关闭预览时不写stride且不推帧() {
        val json = jsonOf(ImageGenParams(prompt = "cat"))

        assertFalse("关预览时 stride 没有意义，不该出现在请求里", json.containsKey("show_diffusion_stride"))
        assertEquals(false, json.primitive("show_diffusion_process").boolean)
        assertEquals("jpeg", json.primitive("preview_format").content)
    }

    @Test
    fun 开启预览时写stride且强制jpeg() {
        // 文档 §12：开了预览也强制 jpeg —— 原始 RGB 一帧约 12 MB。
        val json = jsonOf(ImageGenParams(prompt = "cat", showPreview = true, previewStride = 5))

        assertEquals(true, json.primitive("show_diffusion_process").boolean)
        assertEquals(5, json.primitive("show_diffusion_stride").int)
        assertEquals("jpeg", json.primitive("preview_format").content)
    }

    @Test
    fun 完成图固定要jpeg() {
        // NdjsonReader 把 complete 的 image 当 base64 解；output_format 不是 jpeg 就解错。
        assertEquals("jpeg", jsonOf(ImageGenParams(prompt = "cat")).primitive("output_format").content)
    }

    @Test
    fun P5的字段一个都不出现() {
        val json = jsonOf(ImageGenParams(prompt = "cat"))

        listOf("image", "mask", "aspect_ratio", "ultrafix", "tile_size").forEach { key ->
            assertFalse("未实现的字段不该写进请求：" + key, json.containsKey(key))
        }
    }

    @Test
    fun 提示词里的引号换行与中文原样穿过JSON() {
        val prompt = "a \"cat\"\n第二行，中文"

        assertEquals(prompt, jsonOf(ImageGenParams(prompt = prompt)).primitive("prompt").content)
    }

    @Test
    fun 空提示词照写不省略() {
        assertEquals("", jsonOf(ImageGenParams(prompt = "")).primitive("prompt").content)
    }

    @Test
    fun 负向提示词默认写空串() {
        assertEquals("", jsonOf(ImageGenParams(prompt = "cat")).primitive("negative_prompt").content)
    }
}
