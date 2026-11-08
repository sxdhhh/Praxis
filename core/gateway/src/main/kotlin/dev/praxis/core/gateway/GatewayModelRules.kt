package dev.praxis.core.gateway

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * 网关的 modelRules（文档 8.3）。
 *
 * 五个数组逐字段同名，直接反序列化即可。
 */
@Serializable
data class GatewayModelRules(
    val disabled: List<GatewayDisabledModel> = emptyList(),
    val mappings: List<GatewayMappingRule> = emptyList(),
    val custom: List<GatewayCustomModel> = emptyList(),
    val capabilities: List<GatewayModelCapability> = emptyList(),
    /**
     * 网关的内部去重标记（形如 provider/modelId）。
     *
     * 文档 8.3 明确：导入时可忽略，但**导出时必须保留** —— 否则回灌到网关
     * 会重复播种同一批模型。
     */
    val seeded: List<String> = emptyList(),
) {

    /** 转成候选链能吃的映射列表（字段名对齐 7.1）。 */
    fun toRoutingMappings(): List<ModelMapping> = mappings.map { rule ->
        ModelMapping(
            alias = rule.alias,
            target = rule.target,
            provider = rule.provider,
            reasoning = rule.reasoning,
            enabled = rule.enabled,
        )
    }

    /** 某家某模型是否被用户显式禁用。 */
    fun isDisabled(provider: String, id: String): Boolean = disabled.any { item ->
        item.provider == provider && item.id == id && !item.enabled
    }
}

/** 一条禁用规则。 */
@Serializable
data class GatewayDisabledModel(
    val id: String = "",
    val provider: String = "",
    val enabled: Boolean = false,
    val reasoning: String? = null,
)

/** 一条映射规则：对外别名 -> 上游真名。 */
@Serializable
data class GatewayMappingRule(
    val alias: String = "",
    val target: String = "",
    val provider: String? = null,
    val reasoning: String? = null,
    val enabled: Boolean = true,
)

/** 用户自定义的模型条目。 */
@Serializable
data class GatewayCustomModel(
    val provider: String = "",
    val id: String = "",
)

/**
 * 一条能力位声明。
 *
 * values 用 [JsonElement] 而不是 Boolean：文档只举了 supportsImages 一个键，
 * 直接用 Boolean 会让「网关多写一个非布尔键」导致整个导入失败 —— 那是一个
 * 我们现在还看不见的领域。
 */
@Serializable
data class GatewayModelCapability(
    val provider: String = "",
    val id: String = "",
    val values: Map<String, JsonElement> = emptyMap(),
) {

    /**
     * 图片能力位。
     *
     * @return null = 缺键或不是布尔，即**未知**。调用方按文档 7.2「缺键放行」处理。
     */
    fun supportsImages(): Boolean? =
        (values[KEY_SUPPORTS_IMAGES] as? JsonPrimitive)?.booleanOrNull

    companion object {
        const val KEY_SUPPORTS_IMAGES: String = "supportsImages"
    }
}

/**
 * modelRules 的读写。
 *
 * ## 为什么导出要 encodeDefaults = true
 *
 * 默认值不写会让导出丢东西：disabled 条目里 enabled=false 正好等于默认值，
 * 不写出来这条规则就从「显式禁用」退化成「没有这条」。导入导出互为逆运算的
 * 前提是**显式的值都要写出来**，哪怕它等于默认值。
 */
object GatewayModelRulesCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun parse(text: String): Result<GatewayModelRules> = runCatching {
        json.decodeFromString(GatewayModelRules.serializer(), text)
    }

    fun encode(rules: GatewayModelRules): String =
        json.encodeToString(GatewayModelRules.serializer(), rules)
}
