package dev.praxis.tool

import dev.praxis.core.imagegen.ImageGenConfigDiff
import dev.praxis.core.imagegen.ImageGenTarget
import dev.praxis.core.imagegen.ImageModel
import dev.praxis.core.tool.PermissionTier
import dev.praxis.core.tool.Tool
import dev.praxis.core.tool.ToolContext
import dev.praxis.core.tool.ToolResult
import dev.praxis.core.tool.ToolRisk
import dev.praxis.core.tool.ToolSpec
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `list_image_gen_models`：只读，让模型先看再选（文档 §5.6）。
 *
 * ## 没有它会怎样
 *
 * 模型只能盲猜模型 id，或者反复试探 `invalid_target`。有了它，「换个模型」这类请求
 * 才能一次做对。所以它的 risk 是 [ToolRisk.Safe]、无参数、不需要征询。
 *
 * ## 为什么把「未安装」的模型也列出来
 *
 * 只列已安装的话，用户说「用那个动漫模型」而它还没下载时，模型无从解释为什么不行。
 * 列出全部 + `installed` 标记，模型才能说「那个还没下载，要先在模型库下」。
 */
class ListImageGenModelsTool(
    private val localModels: suspend () -> List<ImageModel>,
    private val installedIds: suspend () -> Set<String>,
    private val readTarget: suspend () -> ImageGenTarget?,
) : Tool {

    override val spec: ToolSpec = ToolSpec(
        name = "list_image_gen_models",
        description = "列出可用的生图模型（本机清单 + 是否已安装）与当前目标。换模型前先调它。",
        parametersJson = SCHEMA,
        tier = PermissionTier.STANDARD,
        risk = ToolRisk.Safe,
    )

    override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
        val installed = installedIds()
        val models = localModels()
        val current = readTarget()
        val payload = buildJsonObject {
            put("ok", true)
            put("local", buildJsonArray {
                models.forEach { model ->
                    add(buildJsonObject {
                        put("id", model.id)
                        put("name", model.name)
                        put("backend", model.backendType)
                        put("size", model.generationSize)
                        put("installed", model.id in installed)
                    })
                }
            })
            // 远端候选依赖各家 provider 的能力位，Praxis 现在拿不到可靠的这份数据。
            // 空数组 + 一句说明，而不是编几条出来。
            put("remote", buildJsonArray { })
            put("current", ImageGenConfigDiff.describe(current).ifEmpty { "none" })
            put("notes", "远端候选依赖各服务商的能力位，Praxis 尚未接入；远端目标请在设置页选")
        }.toString()
        return ToolResult(content = payload)
    }

    companion object {
        private val SCHEMA: String = """
            { "type": "object", "properties": {}, "required": [] }
        """.trimIndent()
    }
}
