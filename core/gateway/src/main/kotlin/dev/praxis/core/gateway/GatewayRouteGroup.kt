package dev.praxis.core.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 路由组里的一员：某家的某个上游模型。 */
data class RouteGroupMember(val provider: String, val model: String)

/**
 * 路由组（文档 §5.5）：把跨家的几个组合并成**一个对外名**。
 *
 * ## 它解决什么
 *
 * 用户想「这个模型尽量走 A 家，A 不行就走 B 家」，而现在只能靠映射一条条写，
 * 且每条都要带家名。组把这件事收成一句：fast = openai/gpt-4o-mini + deepseek/deepseek-chat。
 *
 * ## 与映射的关系（文档没写，这里定优先级）
 *
 * 两者都是「别名 → 上游」的形状。同名时**组优先**：组是更明确的「对外名」概念，
 * 而且它天然多对多。这条写进 CandidateChain 的 KDoc。
 */
data class GatewayRouteGroup(
    val name: String,
    val members: List<RouteGroupMember> = emptyList(),
)

/** 组校验的失败原因。 */
enum class GatewayRouteGroupError {
    MissingName,
    NameWithWhitespace,
    NoMembers,
    MemberMissingProvider,
    MemberMissingModel,
    DuplicateName,
}

/** 组的校验与归一化（纯函数）。 */
object GatewayRouteGroupRules {

    fun validate(
        draft: GatewayRouteGroup,
        existing: List<GatewayRouteGroup> = emptyList(),
    ): GatewayRouteGroupError? {
        val name = draft.name.trim()
        if (name.isEmpty()) return GatewayRouteGroupError.MissingName
        if (name.any { it.isWhitespace() }) return GatewayRouteGroupError.NameWithWhitespace
        if (draft.members.isEmpty()) return GatewayRouteGroupError.NoMembers
        if (draft.members.any { it.provider.isBlank() }) {
            return GatewayRouteGroupError.MemberMissingProvider
        }
        if (draft.members.any { it.model.isBlank() }) {
            return GatewayRouteGroupError.MemberMissingModel
        }
        // 同名（忽略大小写）视为重复：匹配本身忽略大小写，
        // 允许 Fast 与 fast 并存会让「哪个生效」变成看顺序。
        if (existing.any { it.name.equals(name, ignoreCase = true) }) {
            return GatewayRouteGroupError.DuplicateName
        }
        return null
    }

    fun normalize(draft: GatewayRouteGroup): GatewayRouteGroup = GatewayRouteGroup(
        name = draft.name.trim(),
        members = draft.members
            .map { RouteGroupMember(it.provider.trim(), it.model.trim()) }
            .filter { it.provider.isNotEmpty() && it.model.isNotEmpty() },
    )
}

/** 校验失败的人话。 */
fun GatewayRouteGroupError.userMessage(): String = when (this) {
    GatewayRouteGroupError.MissingName -> "要起一个对外名（用户请求它时用它）"
    GatewayRouteGroupError.NameWithWhitespace -> "对外名里不能有空格"
    GatewayRouteGroupError.NoMembers -> "至少要加一个成员（家 + 模型）"
    GatewayRouteGroupError.MemberMissingProvider -> "每个成员都要填家（provider）"
    GatewayRouteGroupError.MemberMissingModel -> "每个成员都要填上游模型名"
    GatewayRouteGroupError.DuplicateName -> "已经有同名的组了（改名或先删掉那个）"
}

/** 组的编解码（存设置键 gateway_route_groups）。 */
object GatewayRouteGroupCodec {

    fun encode(groups: List<GatewayRouteGroup>): String = buildJsonArray {
        groups.forEach { group ->
            addJsonObject {
                put("name", group.name)
                put("members", buildJsonArray {
                    group.members.forEach { member ->
                        addJsonObject {
                            put("provider", member.provider)
                            put("model", member.model)
                        }
                    }
                })
            }
        }
    }.toString()

    /**
     * @param text null/空白 = 没配过（返回 null）。
     * 坏内容返回 failure —— 与其它设置同一口径：读不出来就说读不出来，
     * 不悄悄当成空列表（那会让用户以为组被删了）。
     */
    fun decode(text: String?): Result<List<GatewayRouteGroup>>? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val array = Json.parseToJsonElement(text).jsonArray
            array.map { element ->
                val root: JsonObject = element.jsonObject
                val name = root["name"]?.jsonPrimitive?.contentOrNull ?: error("组缺 name")
                val members = root["members"]?.jsonArray?.map { member ->
                    val memberObject = member.jsonObject
                    val provider = memberObject["provider"]?.jsonPrimitive?.contentOrNull
                        ?: error("成员缺 provider")
                    val model = memberObject["model"]?.jsonPrimitive?.contentOrNull
                        ?: error("成员缺 model")
                    RouteGroupMember(provider, model)
                } ?: emptyList()
                GatewayRouteGroup(name = name, members = members)
            }
        }
    }

    /**
     * 组名命中时的候选（忽略大小写，与 CandidateChain 同口径）。
     *
     * @return null = 这个名字不是组（调用方继续走映射 / 原生承载）。
     */
    fun candidatesFor(
        requestedModel: String,
        groups: List<GatewayRouteGroup>,
    ): List<ModelCandidate>? {
        val requested = requestedModel.trim()
        if (requested.isEmpty()) return null
        val group = groups.firstOrNull { it.name.equals(requested, ignoreCase = true) }
            ?: return null
        return group.members.map { member ->
            ModelCandidate(provider = member.provider, wireModel = member.model)
        }
    }
}
