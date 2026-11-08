package dev.praxis.tool

import dev.praxis.core.imagegen.ImageGenEvent
import dev.praxis.core.imagegen.ImageGenParams
import dev.praxis.core.imagegen.ImageGenParamsRules
import dev.praxis.core.imagegen.ImageGenProgress
import dev.praxis.core.imagegen.ImageGenTarget
import dev.praxis.core.tool.PermissionTier
import dev.praxis.core.tool.Tool
import dev.praxis.core.tool.ToolContext
import dev.praxis.core.tool.ToolFailureStage
import dev.praxis.core.tool.ToolResult
import dev.praxis.core.tool.ToolRisk
import dev.praxis.core.tool.ToolSpec
import dev.praxis.core.imagegen.EngineAvailability
import dev.praxis.data.imagegen.ImageGenRunner
import dev.praxis.data.imagegen.RemoteImageGenerator
import dev.praxis.data.imagegen.RemoteImageOutcome
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * `generate_image`：本次生成的工具（文档 §5.1）。
 *
 * ## 参数的两种语义分得很清
 *
 * 本工具的参数**只对这一次生效**；要改默认值走 `set_image_gen_config`（文档 §5.6）。
 * 混在一起会让模型用一次性参数悄悄改掉用户的全局设置。
 *
 * ## 失败一律走 ToolResult，不抛异常
 *
 * 工具契约（core:tool）明确要求：失败是**正常业务结果**，要作为可读文本回传给模型，
 * 让它自己决定怎么纠正。抛异常只有「工具本身写错了」才允许。
 *
 * ## 错误码为什么分这么细
 *
 * 模型看到笼统的「失败了」只会重试同样的参数；看到 `invalid_size` 才会去改尺寸，
 * 看到 `no_model` 才会去问用户要装哪个模型。classification 是给模型的决策依据。
 *
 * ## count>1 时中途失败：stage 必须是 Execution
 *
 * 已经存下 1 张图再失败，重试会产生**重复的副作用**（多出一批图 + 多烧一遍算力）。
 * 按 [ToolFailureStage] 的定义，这种「可能已部分执行」必须标 Execution，
 * 让模型先确认而不是直接重来。
 */
class GenerateImageTool(
    private val runner: ImageGenRunner,
    /** 探活；注入成 lambda 是为了让工具在单测里不必起 HTTP。 */
    private val probe: suspend () -> EngineAvailability,
    /**
     * 当前选中的生图目标；null = 还没选。
     *
     * suspend：目标存在 `imagegen_target`（DataStore），读它是挂起操作。
     */
    private val target: suspend () -> ImageGenTarget?,
    /** 远端出图（文档 §5.4）。null = 未装配 —— 那条路会明确报装配问题。 */
    private val remoteGenerator: RemoteImageGenerator? = null,
    /** 远端要用的 API Key（跟随当前服务商配置）。 */
    private val apiKey: suspend () -> String? = { null },
    /** 该模型的可用分辨率档位；空 = 自由画布（不做档位校验）。 */
    private val availableSizes: () -> List<Pair<Int, Int>> = { emptyList() },
    /**
     * 进度上报（文档 §6.1）。默认不报：单测与无 UI 场景不需要它。
     *
     * 传 null 表示「结束了」—— 聊天层据此清掉进度条，不会留一个永远转的圈。
     */
    private val reportProgress: (ImageGenProgress?) -> Unit = {},
    /** 落盘：返回绝对路径。放成 lambda 让工具不碰 Context。 */
    private val saveImage: suspend (ByteArray) -> String,
) : Tool {

    override val spec: ToolSpec = ToolSpec(
        name = "generate_image",
        description = "在本机生成图片。用户要求画图/出图/生成图片时调用。" +
            "这些参数只对本次生效；要改默认值请用 set_image_gen_config。" +
            "返回图片的本地路径与 seed，不要编造画面内容。",
        parametersJson = SCHEMA,
        tier = PermissionTier.STANDARD,
        risk = ToolRisk.Moderate,
    )

    override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
        val args: JsonObject = try {
            Json.parseToJsonElement(argumentsJson).jsonObject
        } catch (error: Exception) {
            return failure("bad_arguments", "参数不是合法的 JSON 对象")
        }

        val prompt = args.text("prompt")?.takeIf { it.isNotBlank() }
            ?: return failure("bad_arguments", "prompt 不能为空")

        // 目标决定走哪条路（文档 §5.4）：本地引擎或远端的 images 端点。
        val chosen = target() ?: return failure(
            code = "no_model",
            message = "还没有选生图目标",
            hint = "在「设置 → 本地生图 → 更换目标」里选一个本地模型或服务商模型",
            stage = ToolFailureStage.Execution,
        )

        val count = args.int("count") ?: 1
        if (count !in 1..MAX_COUNT) {
            return failure("bad_arguments", "count 必须在 1~" + MAX_COUNT + " 之间")
        }

        val params = ImageGenParams(
            prompt = prompt,
            negative = args.text("negative").orEmpty(),
            width = args.int("width") ?: ImageGenParams.DEFAULT_WIDTH,
            height = args.int("height") ?: ImageGenParams.DEFAULT_HEIGHT,
            steps = args.int("steps") ?: ImageGenParams.DEFAULT_STEPS,
            cfg = args.float("cfg") ?: DEFAULT_CFG,
            scheduler = args.text("scheduler")?.takeIf { it.isNotBlank() },
            seed = args.long("seed"),
            showPreview = false,
        )

        // 先按当前模型的档位校验：NPU 上没 patch 的尺寸会被引擎**静默回落 512**，
        // 与其画出一张尺寸不对的图，不如让模型自己改参数。
        ImageGenParamsRules.validate(params, availableSizes())?.let { invalid ->
            return failure(
                code = invalid.code,
                message = invalid.detail ?: "参数不合法",
                hint = "可用尺寸：" + ImageGenParamsRules.formatSizes(availableSizes()),
                stage = ToolFailureStage.Argument,
            )
        }

        return when (chosen) {
            is ImageGenTarget.Local -> runLocal(chosen.modelId, params, count)
            is ImageGenTarget.Remote -> runRemote(chosen, params, count)
        }
    }

    /**
     * 本地引擎这条路（文档 §3）：先探活，再逐张收 NDJSON。
     *
     * 探活放在这里而不是注册时：引擎此刻有没有起来只有运行期知道，
     * 而「工具在列表里时有时无」比「调用后回一句不可用」难排查得多。
     */
    private suspend fun runLocal(
        modelId: String,
        params: ImageGenParams,
        count: Int,
    ): ToolResult {
        // 首次启动引擎要 3~10 秒，这段必须让用户看到「在动」（文档 §6.1）。
        reportProgress(ImageGenProgress(modelId, ImageGenProgress.Phase.Preparing))
        when (val availability = probe()) {
            is EngineAvailability.Unavailable -> {
                reportProgress(null)
                return failure(
                    code = "engine_start_failed",
                    message = "生图引擎不可用（" + availability.reason.name + "）",
                    hint = availability.detail,
                    stage = ToolFailureStage.Execution,
                )
            }
            is EngineAvailability.Available -> Unit
        }

        val saved = mutableListOf<SavedImage>()
        for (index in 0 until count) {
            // 指定了 seed 时逐张递增：否则「一次出 3 张」会得到 3 张一模一样的图。
            val attempt = params.copy(seed = params.seed?.plus(index.toLong()))
            var complete: ImageGenEvent.Complete? = null
            try {
                runner.generate(attempt).collect { event ->
                    when (event) {
                        is ImageGenEvent.Complete -> complete = event
                        is ImageGenEvent.Progress -> reportProgress(
                            ImageGenProgress(
                                targetLabel = modelId,
                                phase = ImageGenProgress.Phase.Running,
                                fraction = event.value,
                            ),
                        )
                    }
                }
            } catch (error: IOException) {
                return failure(
                    code = "engine_start_failed",
                    message = "生成失败：" + (error.message ?: "传输错误"),
                    saved = saved,
                    stage = ToolFailureStage.Execution,
                )
            }
            val result = complete ?: return failure(
                code = "engine_start_failed",
                message = "引擎没有返回 complete 消息",
                saved = saved,
                stage = ToolFailureStage.Execution,
            )
            if (result.bytes.isEmpty()) {
                return failure(
                    code = "engine_start_failed",
                    message = "引擎返回了 complete，但图是空的（base64 传输损坏）",
                    saved = saved,
                    stage = ToolFailureStage.Execution,
                )
            }
            reportProgress(ImageGenProgress(modelId, ImageGenProgress.Phase.Saving))
            val path = saveImage(result.bytes)
            saved += SavedImage(path, result.seed, result.width, result.height)
        }

        reportProgress(null)
        return ToolResult(content = successJson(modelId, saved))
    }

    /**
     * 远端这条路（文档 §5.4）：OpenAI 兼容的 images 端点，**没有进度**。
     *
     * 它的结果与本地落到同一种东西 —— 本地文件路径 + 一张图，所以后续链路
     * （缩略图进气泡）完全不用区分图从哪来。
     */
    private suspend fun runRemote(
        target: ImageGenTarget.Remote,
        params: ImageGenParams,
        count: Int,
    ): ToolResult {
        val generator = remoteGenerator ?: return failure(
            code = "remote_not_wired",
            message = "远端生图没有装配（构建配置问题）",
            stage = ToolFailureStage.Execution,
        )
        val key = apiKey()
        val saved = mutableListOf<SavedImage>()
        for (index in 0 until count) {
            val attempt = params.copy(seed = params.seed?.plus(index.toLong()))
            when (val outcome = generator.generate(target, key, attempt)) {
                is RemoteImageOutcome.Failure -> return failure(
                    code = remoteCodeFor(outcome.kind),
                    message = "远端生图失败：" + outcome.detail,
                    hint = remoteHintFor(outcome.kind),
                    saved = saved,
                    stage = ToolFailureStage.Execution,
                )
                is RemoteImageOutcome.Success -> {
                    outcome.images.forEach { bytes ->
                        val path = saveImage(bytes)
                        // 远端不返回 seed：只回传「请求里带的那个」，没带就是 null。
                        // 尺寸用请求里的值（那是我们自己定死的，可信）。
                        saved += SavedImage(
                            path = path,
                            seed = attempt.seed,
                            width = params.width,
                            height = params.height,
                        )
                    }
                }
            }
        }
        return ToolResult(content = successJson(target.model, saved))
    }

    /** 远端失败分类 -> 工具错误码（与本地同一套词，见文档 §5.4）。 */
    private fun remoteCodeFor(kind: dev.praxis.core.ai.AiErrorKind): String = when (kind) {
        dev.praxis.core.ai.AiErrorKind.QUOTA_LIMITED -> "quota_exceeded"
        dev.praxis.core.ai.AiErrorKind.RATE_LIMIT -> "quota_exceeded"
        dev.praxis.core.ai.AiErrorKind.CONTENT_BLOCKED -> "content_blocked"
        dev.praxis.core.ai.AiErrorKind.AUTH -> "no_api_key"
        dev.praxis.core.ai.AiErrorKind.TOKEN_EXPIRED -> "no_api_key"
        dev.praxis.core.ai.AiErrorKind.NETWORK -> "remote_unreachable"
        dev.praxis.core.ai.AiErrorKind.INVALID_REQUEST -> "invalid_request"
        else -> "remote_error"
    }

    private fun remoteHintFor(kind: dev.praxis.core.ai.AiErrorKind): String? = when (kind) {
        dev.praxis.core.ai.AiErrorKind.AUTH,
        dev.praxis.core.ai.AiErrorKind.TOKEN_EXPIRED,
        -> "在设置里检查该服务商的 API Key"
        dev.praxis.core.ai.AiErrorKind.QUOTA_LIMITED,
        dev.praxis.core.ai.AiErrorKind.RATE_LIMIT,
        -> "该服务商额度/速率受限，稍后再试或换一个模型"
        dev.praxis.core.ai.AiErrorKind.INVALID_REQUEST ->
            "确认所选模型支持生图（checkpoints 与 chat 模型不是一回事）"
        else -> null
    }

    /** 一次生成成功的回报：首图路径 + 全部图片 + 模型名。 */
    private fun successJson(model: String, images: List<SavedImage>): String = buildJsonObject {
        put("ok", true)
        put("model", model)
        put("count", images.size)
        // 顶层 path 是「这张图在哪」的最短回答，模型多数只需要它。
        put("path", images.first().path)
        put("images", buildJsonArray {
            images.forEach { image ->
                add(buildJsonObject {
                    put("path", image.path)
                    put("seed", image.seed)
                    put("width", image.width)
                    put("height", image.height)
                })
            }
        })
    }.toString()

    private fun failure(
        code: String,
        message: String,
        hint: String? = null,
        saved: List<SavedImage> = emptyList(),
        stage: ToolFailureStage = ToolFailureStage.Argument,
    ): ToolResult {
        val payload = buildJsonObject {
            put("ok", false)
            put("error", code)
            put("message", message)
            hint?.let { put("hint", it) }
            if (saved.isNotEmpty()) {
                // 已经存下的图必须报出来：否则用户会以为什么都没生成。
                put("saved", buildJsonArray {
                    saved.forEach { image -> add(buildJsonObject { put("path", image.path) }) }
                })
            }
        }.toString()
        return ToolResult(content = payload, isError = true, stage = stage)
    }

    /** 一张已落盘的图。 */
    /**
     * 一张已落盘的图。
     *
     * `seed` 可空：远端 images 端点不返回 seed，我们只知道「请求里带没带」。
     * 编一个 0 上去会让模型以为那是真实种子，进而用它去复现 —— 复现不出来。
     */
    private data class SavedImage(
        val path: String,
        val seed: Long?,
        val width: Int,
        val height: Int,
    )

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

    private fun JsonObject.float(key: String): Float? = this[key]?.jsonPrimitive?.floatOrNull

    companion object {
        /** 一次最多几张：再多就是替用户烧算力，且连出多张时手机发热明显。 */
        private const val MAX_COUNT: Int = 4

        /** cfg 默认值与 core 的 ImageGenParams 默认一致（7.0）。 */
        private const val DEFAULT_CFG: Float = 7f

        /** 文档 §5.1 的参数表。schema 用字符串直传给模型（core:tool 的约定）。 */
        private val SCHEMA: String = """
            {
              "type": "object",
              "properties": {
                "prompt":    { "type": "string", "description": "正向提示词（英文更佳）" },
                "negative":  { "type": "string", "description": "负向提示词，可选" },
                "width":     { "type": "integer", "minimum": 256, "maximum": 1024 },
                "height":    { "type": "integer", "minimum": 256, "maximum": 1024 },
                "steps":     { "type": "integer", "minimum": 1, "maximum": 60 },
                "cfg":       { "type": "number",  "minimum": 1, "maximum": 20 },
                "scheduler": { "type": "string", "enum": ["dpm", "euler", "euler_a", "lcm"] },
                "seed":      { "type": "integer", "description": "固定种子，可选" },
                "count":     { "type": "integer", "minimum": 1, "maximum": 4, "default": 1 }
              },
              "required": ["prompt"]
            }
        """.trimIndent()
    }
}
