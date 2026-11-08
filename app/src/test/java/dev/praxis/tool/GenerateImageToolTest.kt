package dev.praxis.tool

import dev.praxis.core.imagegen.ImageGenEvent
import dev.praxis.core.imagegen.ImageGenParams
import dev.praxis.core.tool.PermissionTier
import dev.praxis.core.tool.ToolFailureStage
import dev.praxis.core.tool.ToolContext
import dev.praxis.data.imagegen.EngineAvailability
import dev.praxis.data.imagegen.ImageGenRunner
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `generate_image` 的失败分类与副作用语义（文档 §5.1/§5.2）。
 *
 * 这一层能验证的东西：**参数怎么被解释、失败怎么被分类、已存下的图有没有被报出来**。
 * 不能验证的：真实引擎的 NDJSON 时序（那要真机 + 真引擎）。
 */
class GenerateImageToolTest {

    private val context = object : ToolContext {
        override val tier = PermissionTier.STANDARD
    }

    /** 假 runner：按需返回若干 Progress + 一条 Complete，或抛 IOException。 */
    private class FakeRunner(
        private val bytes: ByteArray = byteArrayOf(1, 2, 3),
        private val seed: Long = 42L,
        private val failOnCall: Int? = null,
    ) : ImageGenRunner {
        val calls = mutableListOf<Long?>()
        override fun generate(params: ImageGenParams): Flow<ImageGenEvent> = flow {
            calls += params.seed
            if (failOnCall != null && calls.size == failOnCall) {
                throw IOException("boom")
            }
            emit(ImageGenEvent.Progress(0.5f, null))
            emit(ImageGenEvent.Complete(bytes = bytes, seed = seed, width = 512, height = 512))
        }
    }

    // 显式标注 suspend + 父类型：`{ ... }` 会被推断成 `() -> Available`，
    // 赋给 `suspend () -> EngineAvailability` 的默认值不成立（CI 抓到的编译错）。
    private val available: suspend () -> EngineAvailability = {
        EngineAvailability.Available(dev.praxis.core.imagegen.EngineHealth("{}", true))
    }

    private fun tool(
        runner: ImageGenRunner,
        probe: suspend () -> EngineAvailability = available,
        modelId: () -> String? = { "anythingv5" },
        sizes: () -> List<Pair<Int, Int>> = { emptyList() },
        saved: MutableList<ByteArray> = mutableListOf(),
    ) = GenerateImageTool(
        runner = runner,
        probe = probe,
        modelId = modelId,
        availableSizes = sizes,
        saveImage = { bytes -> saved += bytes; "/tmp/img" + saved.size + ".jpg" },
    )

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `参数不是 JSON 时按参数错返回而不是抛异常`() = runTest {
        val result = tool(FakeRunner()).execute("not-json", context)
        assertTrue(result.isError)
        assertEquals(ToolFailureStage.Argument, result.stage)
        assertEquals("bad_arguments", json(result.content)["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `prompt 为空按参数错返回`() = runTest {
        val blank = tool(FakeRunner()).execute("{\"prompt\":\"   \"}", context)
        assertEquals("bad_arguments", json(blank.content)["error"]!!.jsonPrimitive.content)
        val missing = tool(FakeRunner()).execute("{}", context)
        assertEquals("bad_arguments", json(missing.content)["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `没有选模型时返回 no_model 且标 Execution`() = runTest {
        val result = tool(FakeRunner(), modelId = { null }).execute("{\"prompt\":\"猫\"}", context)
        assertTrue(result.isError)
        assertEquals("no_model", json(result.content)["error"]!!.jsonPrimitive.content)
        assertEquals(ToolFailureStage.Execution, result.stage)
    }

    @Test
    fun `引擎不可用时返回 engine_start_failed`() = runTest {
        val result = tool(
            FakeRunner(),
            probe = { EngineAvailability.Unavailable(EngineAvailability.Unavailable.Reason.NotRunning, "连接被拒") },
        ).execute("{\"prompt\":\"猫\"}", context)
        assertEquals("engine_start_failed", json(result.content)["error"]!!.jsonPrimitive.content)
        assertEquals("连接被拒", json(result.content)["hint"]!!.jsonPrimitive.content)
    }

    @Test
    fun `尺寸不在档位时返回 invalid_size 并给出可用档位`() = runTest {
        val result = tool(
            FakeRunner(),
            sizes = { listOf(512 to 512, 512 to 768) },
        ).execute("{\"prompt\":\"猫\",\"width\":640,\"height\":640}", context)
        assertEquals("invalid_size", json(result.content)["error"]!!.jsonPrimitive.content)
        assertTrue(json(result.content)["hint"]!!.jsonPrimitive.content.contains("512"))
        assertEquals(ToolFailureStage.Argument, result.stage)
    }

    @Test
    fun `count 越界按参数错返回`() = runTest {
        val result = tool(FakeRunner()).execute("{\"prompt\":\"猫\",\"count\":9}", context)
        assertEquals("bad_arguments", json(result.content)["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `成功时返回 ok 与首图路径`() = runTest {
        val saved = mutableListOf<ByteArray>()
        val result = tool(FakeRunner(), saved = saved).execute("{\"prompt\":\"猫\"}", context)
        assertFalse(result.isError)
        val body = json(result.content)
        assertEquals(true, body["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("/tmp/img1.jpg", body["path"]!!.jsonPrimitive.content)
        assertEquals(1, body["count"]!!.jsonPrimitive.int)
        assertEquals(1, saved.size)
    }

    @Test
    fun `count 为 2 时逐张生成且 seed 递增`() = runTest {
        val runner = FakeRunner()
        val result = tool(runner).execute("{\"prompt\":\"猫\",\"count\":2,\"seed\":100}", context)
        assertFalse(result.isError)
        assertEquals(2, json(result.content)["count"]!!.jsonPrimitive.int)
        assertEquals(listOf<Long?>(100L, 101L), runner.calls)
    }

    @Test
    fun `第二张失败时把已存下的图报出来并标 Execution`() = runTest {
        val result = tool(FakeRunner(failOnCall = 2)).execute("{\"prompt\":\"猫\",\"count\":2}", context)
        assertTrue(result.isError)
        assertEquals(ToolFailureStage.Execution, result.stage)
        val body = json(result.content)
        assertEquals("engine_start_failed", body["error"]!!.jsonPrimitive.content)
        assertEquals(1, body["saved"]!!.jsonArray.size)
    }

    @Test
    fun `引擎返回空图时按失败处理并带上已存下的图`() = runTest {
        val result = tool(FakeRunner(bytes = ByteArray(0))).execute("{\"prompt\":\"猫\"}", context)
        assertTrue(result.isError)
        assertEquals("engine_start_failed", json(result.content)["error"]!!.jsonPrimitive.content)
    }
}
