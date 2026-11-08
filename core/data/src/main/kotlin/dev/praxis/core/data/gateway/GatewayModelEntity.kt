package dev.praxis.core.data.gateway

import androidx.room.Entity

/**
 * 结构化模型条目（文档 6.3，对应网关 catalog 的一行）。
 *
 * ## 主键为什么是 (provider, id)
 *
 * 网关的模型是按「家 x 模型」定位的：同一个 id 在不同家是两条记录
 * （openai/gpt-4o 与 某中转/gpt-4o 是两回事）。用 id 单独做主键会互相覆盖。
 *
 * ## 枚举与列表为什么存文本
 *
 * - 枚举存 name：省掉 TypeConverter 的注册，且未来给枚举加分支时旧数据仍然可读；
 * - 字符串列表用换行分隔：模态与思考等级都是简单标识（text / image / low …），
 *   不可能是任意字符串，换行足够且不需要 JSON 转义。
 *
 * 读回时若遇到不认识的枚举名，映射会**直接抛错**而不是退回默认值 —— 那是数据
 * 损坏，静默退回会让用户看到一条「类型不对但能跑」的模型。
 */
@Entity(tableName = "gateway_model", primaryKeys = ["provider", "id"])
data class GatewayModelEntity(
    val provider: String,
    val id: String,
    val name: String,
    /** ModelKind 的名字。 */
    val kind: String,
    /** ModelSource 的名字。 */
    val source: String,
    val enabled: Boolean,
    val credits: Double?,
    val supportsToolCall: Boolean?,
    val supportsImages: Boolean?,
    val supportsVideo: Boolean?,
    val supportsReasoning: Boolean?,
    val supportsImageGeneration: Boolean?,
    val supportsImageEditing: Boolean?,
    /** 换行分隔的模态列表。 */
    val inputModalities: String,
    val maxInputTokens: Int?,
    val maxOutputTokens: Int?,
    /** 换行分隔的思考等级列表。 */
    val reasoningEfforts: String,
    val reasoningDefault: String?,
    val reasoningFixed: String?,
    val reasoningSource: String?,
)
