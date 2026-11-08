package dev.praxis.core.imagegen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `imagegen_target` 的编解码（文档 §7.3 的数据形状）。
 *
 * ## 为什么不直接序列化 sealed interface
 *
 * 带 `@Serializable` 的 sealed 会带上类名做区分键，而那个键会**写进用户的 DataStore**：
 * 以后改类名（哪怕只是改名）就读不出来了。这里显式写一个 `kind` 字段，
 * 名字由我们自己定，且写死了就不动。
 *
 * ## 解不开时返回 Failure 而不是 null
 *
 * null 表示「用户从来没配过」，与「配过但内容坏了」是两件事：
 * 前者该自动选一个，后者必须让用户看见（否则会静默把他选的模型换掉）。
 */
object ImageGenTargetCodec {

    private const val KIND_LOCAL = "local"
    private const val KIND_REMOTE = "remote"

    fun encode(target: ImageGenTarget): String = when (target) {
        is ImageGenTarget.Local -> buildJsonObject {
            put("kind", KIND_LOCAL)
            put("modelId", target.modelId)
        }

        is ImageGenTarget.Remote -> buildJsonObject {
            put("kind", KIND_REMOTE)
            put("provider", target.provider)
            put("baseUrl", target.baseUrl)
            put("model", target.model)
        }
    }.toString()

    /**
     * @param text `null` = 没配过（返回 null）；解析失败 = [Result.failure]
     */
    fun decode(text: String?): Result<ImageGenTarget>? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val root = Json.parseToJsonElement(text).jsonObject
            when (val kind = root.str("kind")) {
                KIND_LOCAL -> ImageGenTarget.Local(
                    modelId = root.str("modelId") ?: error("local 目标缺少 modelId"),
                )
                KIND_REMOTE -> ImageGenTarget.Remote(
                    provider = root.str("provider") ?: error("remote 目标缺少 provider"),
                    baseUrl = root.str("baseUrl") ?: error("remote 目标缺少 baseUrl"),
                    model = root.str("model") ?: error("remote 目标缺少 model"),
                )
                else -> error("不认识的目标类型：" + (kind ?: "null"))
            }
        }
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
}

/**
 * 下载完成之后，生图目标要不要跟着换（文档 §7.3 的那段 `when`）。
 *
 * ## 三条规则，一条都不多
 *
 * | 现状 | 结果 | 为什么 |
 * |---|---|---|
 * | 没配过 | 用新模型 | 第一个装好的模型直接可用，不用再点一次 |
 * | 指向本地某个模型，但那个目录没了 | 用新模型 | 原选择已经失效，接管比让用户对着错模型报错好 |
 * | 其它（选了远端、或本地模型还在） | **不动** | 用户明确选过的东西不能被一次下载覆盖 |
 *
 * 第三条是关键：`Remote` 是用户手动配的，自动改成 `Local` 等于把他改回本地出图。
 */
object ImageGenTargetSelection {

    fun afterInstall(
        current: ImageGenTarget?,
        newModelId: String,
        /** 判断某个本地模型目录是否还在（注入以便单测不必碰文件系统）。 */
        localModelExists: (String) -> Boolean,
    ): ImageGenTarget {
        if (current == null) return ImageGenTarget.Local(newModelId)
        if (current is ImageGenTarget.Local && !localModelExists(current.modelId)) {
            return ImageGenTarget.Local(newModelId)
        }
        return current
    }
}
