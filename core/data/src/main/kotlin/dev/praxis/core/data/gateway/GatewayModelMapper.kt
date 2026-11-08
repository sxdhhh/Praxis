package dev.praxis.core.data.gateway

import dev.praxis.core.gateway.ModelEntry
import dev.praxis.core.gateway.ModelKind
import dev.praxis.core.gateway.ModelSource

/** 换行分隔的字符串列表编解码。 */
private fun List<String>.encodeLines(): String = joinToString("\n")

private fun String.decodeLines(): List<String> =
    split('\n').filter { it.isNotEmpty() }

/** 结构化条目 -> 存储形态。 */
fun ModelEntry.toEntity(): GatewayModelEntity = GatewayModelEntity(
    provider = provider,
    id = id,
    name = name,
    kind = kind.name,
    source = source.name,
    enabled = enabled,
    credits = credits,
    supportsToolCall = supportsToolCall,
    supportsImages = supportsImages,
    supportsVideo = supportsVideo,
    supportsReasoning = supportsReasoning,
    supportsImageGeneration = supportsImageGeneration,
    supportsImageEditing = supportsImageEditing,
    inputModalities = inputModalities.encodeLines(),
    maxInputTokens = maxInputTokens,
    maxOutputTokens = maxOutputTokens,
    reasoningEfforts = reasoningEfforts.encodeLines(),
    reasoningDefault = reasoningDefault,
    reasoningFixed = reasoningFixed,
    reasoningSource = reasoningSource,
)

/**
 * 存储形态 -> 结构化条目。
 *
 * 枚举名对不上时抛错：那是数据损坏或版本回退，不是「用默认值继续」的场景。
 * 静默退回会让用户看到一条类型不对但能跑的模型，问题被藏起来。
 */
fun GatewayModelEntity.toModelEntry(): ModelEntry = ModelEntry(
    provider = provider,
    id = id,
    name = name,
    kind = enumValueOf<ModelKind>(kind),
    source = enumValueOf<ModelSource>(source),
    enabled = enabled,
    credits = credits,
    supportsToolCall = supportsToolCall,
    supportsImages = supportsImages,
    supportsVideo = supportsVideo,
    supportsReasoning = supportsReasoning,
    supportsImageGeneration = supportsImageGeneration,
    supportsImageEditing = supportsImageEditing,
    inputModalities = inputModalities.decodeLines(),
    maxInputTokens = maxInputTokens,
    maxOutputTokens = maxOutputTokens,
    reasoningEfforts = reasoningEfforts.decodeLines(),
    reasoningDefault = reasoningDefault,
    reasoningFixed = reasoningFixed,
    reasoningSource = reasoningSource,
)
