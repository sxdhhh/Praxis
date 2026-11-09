package dev.praxis.tool

import dev.praxis.core.imagegen.ImageGenConfigChange
import dev.praxis.core.imagegen.ImageGenConfigDiff
import dev.praxis.core.imagegen.ImageGenConfigRules
import dev.praxis.core.imagegen.ImageGenDefaults
import dev.praxis.core.imagegen.ImageGenDefaultsRules
import dev.praxis.core.imagegen.ImageGenTarget
import dev.praxis.core.imagegen.ImageOutputSettings
import dev.praxis.core.tool.PermissionTier
import dev.praxis.core.tool.Tool
import dev.praxis.core.tool.ToolContext
import dev.praxis.core.tool.ToolFailureStage
import dev.praxis.core.tool.ToolResult
import dev.praxis.core.tool.ToolRisk
import dev.praxis.core.tool.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `set_image_gen_config`：让 AI 改**持久**配置（文档 §5.6）。
 *
 * ## 为什么与 `generate_image` 分开
 *
 * 用户会说「以后都画大一点」「步数别那么高」—— 那是持久配置，不是这一次的参数。
 * 混在一个工具里，模型很容易用一次性参数悄悄改掉用户的全局设置。
 *
 * ## 安全边界不在这个文件里
 *
 * 「凭据 / 下载源 / 引擎来源 / 模型增删不许 AI 改」这条边界写在
 * [ImageGenConfigRules]（core，12 例单测）。这里只负责调用它 ——
 * 边界写在实现文件里就只能靠编译保证「写对了」，而写错的表现是漏洞，不会报错。
 */
class SetImageGenConfigTool(
    private val readDefaults: suspend () -> ImageGenDefaults,
    private val writeDefaults: suspend (ImageGenDefaults) -> Unit,
    private val readTarget: suspend () -> ImageGenTarget?,
    private val writeTarget: suspend (ImageGenTarget) -> Unit,
    /** 已安装模型的 id：切到本机没有的模型要明确拒绝。 */
    private val installedModelIds: suspend () -> Set<String>,
    /** 输出设置（自动存相册）。文档 §7.1 的输出分区。 */
    private val readOutput: suspend () -> ImageOutputSettings = { ImageOutputSettings() },
    private val writeOutput: suspend (ImageOutputSettings) -> Unit = {},
) : Tool {

    override val spec: ToolSpec = ToolSpec(
        name = "set_image_gen_config",
        description = "修改生图的持久配置（默认画布/采样/负向提示词、切换到已安装的本地模型）。" +
            "只传要改的字段。返回改前改后的 diff，请据此回复用户。" +
            "凭据、下载源、引擎来源、模型的增删删不在本工具范围内。",
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
        val keys = args.keys.toList()

        // ① 边界：命中即整次拒绝，不做部分应用（半改一半比不改更难收拾）。
        val notAllowed = ImageGenConfigRules.notAllowedKeys(keys)
        if (notAllowed.isNotEmpty()) {
            return failure(
                code = "not_allowed",
                message = "这些设置不许由 AI 修改：" + notAllowed.joinToString("、"),
                hint = "请用户自己在「设置 → 本地生图」里改",
                stage = ToolFailureStage.Denied,
            )
        }

        val notImplemented = ImageGenConfigRules.notImplementedKeys(keys)
        val unknown = keys.filter { !ImageGenConfigRules.isKnownKey(it) }

        // ② 默认参数：只应用传了的那几项。
        val before = readDefaults()
        var after = before
        args.int("width")?.let { after = after.copy(width = it) }
        args.int("height")?.let { after = after.copy(height = it) }
        args.int("steps")?.let { after = after.copy(steps = it) }
        args.float("cfg")?.let { after = after.copy(cfg = it) }
        args.text("negative")?.let { after = after.copy(negative = it) }
        args.text("scheduler")?.let { value ->
            after = after.copy(scheduler = value.takeIf { it.isNotBlank() })
        }

        ImageGenDefaultsRules.validate(after)?.let { reason ->
            return failure("invalid_config", reason, stage = ToolFailureStage.Argument)
        }

        // ③ 目标：只收本地目标。
        val targetBefore = readTarget()
        var targetAfter = targetBefore
        args.objectOrNull("target")?.let { raw ->
            val kind = raw.text("kind")?.lowercase()
            if (kind != "local") {
                return failure(
                    code = "not_allowed",
                    message = "只能切到已安装的本地模型；远端目标请用户在设置页选",
                    stage = ToolFailureStage.Denied,
                )
            }
            val modelId = raw.text("modelId")?.takeIf { it.isNotBlank() }
                ?: return failure("invalid_target", "target.modelId 不能为空")
            if (modelId !in installedModelIds()) {
                return failure(
                    code = "invalid_target",
                    message = "本机没有安装模型 " + modelId,
                    hint = "先用 list_image_gen_models 看有哪些已安装的模型",
                    stage = ToolFailureStage.Argument,
                )
            }
            targetAfter = ImageGenTarget.Local(modelId)
        }

        // ④ 输出开关：autoSave 已实现，直接写。
        val outputBefore = readOutput()
        var outputAfter = outputBefore
        val outputKeys = keys.filter { ImageGenConfigRules.isSupportedOutputKey(it) }
        if (outputKeys.isNotEmpty()) {
            val raw = args[outputKeys.first()]?.jsonPrimitive?.contentOrNull
            val enabled = raw?.toBooleanStrictOrNull()
                ?: return failure(
                code = "invalid_config",
                message = outputKeys.first() + " 要是 true 或 false",
            )
            outputAfter = outputAfter.copy(autoSave = enabled)
        }

        // ⑤ 写。顺序：先默认参数，再目标，最后输出开关 —— 中途失败时不会出现半套配置。
        if (after != before) writeDefaults(after)
        if (outputAfter != outputBefore) writeOutput(outputAfter)
        if (targetAfter != targetBefore && targetAfter != null) writeTarget(targetAfter)

        return ToolResult(
            content = changedJson(
                before = before,
                after = after,
                targetBefore = targetBefore,
                targetAfter = targetAfter,
                outputBefore = outputBefore,
                outputAfter = outputAfter,
                notImplemented = notImplemented,
                unknown = unknown,
            ),
        )
    }

    private fun changedJson(
        before: ImageGenDefaults,
        after: ImageGenDefaults,
        targetBefore: ImageGenTarget?,
        targetAfter: ImageGenTarget?,
        outputBefore: ImageOutputSettings,
        outputAfter: ImageOutputSettings,
        notImplemented: List<String>,
        unknown: List<String>,
    ): String {
        val changes = ImageGenConfigDiff.defaults(before, after).toMutableList()
        ImageGenConfigDiff.target(targetBefore, targetAfter)?.let { changes += it }
        if (outputBefore.autoSave != outputAfter.autoSave) {
            changes += ImageGenConfigChange(
                field = "autoSave",
                from = outputBefore.autoSave.toString(),
                to = outputAfter.autoSave.toString(),
            )
        }
        return buildJsonObject {
            put("ok", true)
            put("changed", buildJsonArray {
                changes.forEach { change ->
                    add(buildJsonObject {
                        put("field", change.field)
                        put("from", change.from)
                        put("to", change.to)
                    })
                }
            })
            if (changes.isEmpty()) {
                put("note", "这些字段本来就是目标值，没有任何改动")
            }
            // 没做 / 不认识的键都如实说出来，让模型能对用户讲清楚。
            if (notImplemented.isNotEmpty()) {
                put("ignored_not_implemented", notImplemented.joinToString("、"))
            }
            if (unknown.isNotEmpty()) {
                put("ignored_unknown", unknown.joinToString("、"))
            }
        }.toString()
    }

    private fun failure(
        code: String,
        message: String,
        hint: String? = null,
        stage: ToolFailureStage = ToolFailureStage.Argument,
    ): ToolResult {
        val payload = buildJsonObject {
            put("ok", false)
            put("error", code)
            put("message", message)
            hint?.let { put("hint", it) }
        }.toString()
        return ToolResult(content = payload, isError = true, stage = stage)
    }

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.float(key: String): Float? = this[key]?.jsonPrimitive?.floatOrNull

    private fun JsonObject.objectOrNull(key: String): JsonObject? =
        runCatching { this[key]?.jsonObject }.getOrNull()

    companion object {
        private val SCHEMA: String = """
            {
              "type": "object",
              "properties": {
                "target":    { "type": "object", "description": "{\"kind\":\"local\",\"modelId\":\"...\"}" },
                "width":     { "type": "integer", "minimum": 256, "maximum": 1024 },
                "height":    { "type": "integer", "minimum": 256, "maximum": 1024 },
                "steps":     { "type": "integer", "minimum": 1, "maximum": 60 },
                "cfg":       { "type": "number",  "minimum": 1, "maximum": 20 },
                "scheduler": { "type": "string", "enum": ["dpm", "euler", "euler_a", "lcm"] },
                "negative":  { "type": "string" }
              },
              "required": []
            }
        """.trimIndent()
    }
}
