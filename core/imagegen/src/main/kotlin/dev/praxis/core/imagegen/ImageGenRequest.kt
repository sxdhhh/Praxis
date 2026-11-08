package dev.praxis.core.imagegen

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `POST /generate` 的请求体（文档 §3.2 的字段全表）。
 *
 * ## 三条硬规则
 *
 * 1. **字段名逐字来自 [EngineProtocol.RequestField]**，这里不出现任何裸字符串；
 * 2. **可选字段省略时不写**：`scheduler` / `seed` 为 null 就不落进 JSON，
 *    引擎才会走「默认调度器 / 随机种子」。写成 null 或 0 都会改变语义
 *    （`seed = 0` 是一个固定种子，不是「随机」）；
 * 3. **数值原样写**：不做「等于默认值就省略」的优化 —— 那会让「这次步数 20」
 *    与「没传步数」在引擎侧变成两件事，日志里也对不上。
 *
 * ## 本模块不写的字段
 *
 * `image` / `mask`（图生图）、`aspect_ratio`（固定长边模式）、
 * `ultrafix` / `tile_size`（分块超分）都属文档 P5，这一轮只做文生图。
 * **不写等于引擎用默认值**，比写一个我们没验证过的 false 更安全；
 * 字段名依旧只在 [EngineProtocol.RequestField] 里维护。
 */
object ImageGenRequest {

    /** 请求体 JSON（紧凑单行，直接当 `POST` body）。 */
    fun toJson(p: ImageGenParams): String = toJsonObject(p).toString()

    /**
     * 结构化形态。键的顺序与文档 §3.2 一致，便于把日志和文档逐行对照。
     */
    fun toJsonObject(p: ImageGenParams): JsonObject = buildJsonObject {
        put(EngineProtocol.RequestField.PROMPT, p.prompt)
        put(EngineProtocol.RequestField.NEGATIVE_PROMPT, p.negative)
        put(EngineProtocol.RequestField.STEPS, p.steps)
        put(EngineProtocol.RequestField.CFG, p.cfg)
        put(EngineProtocol.RequestField.WIDTH, p.width)
        put(EngineProtocol.RequestField.HEIGHT, p.height)

        // 可选：null 就不写（引擎取默认 / 随机）。
        p.scheduler?.takeIf { it.isNotBlank() }?.let { put(EngineProtocol.RequestField.SCHEDULER, it) }
        p.seed?.let { put(EngineProtocol.RequestField.SEED, it) }

        put(EngineProtocol.RequestField.DENOISE_STRENGTH, p.denoise)
        put(EngineProtocol.RequestField.USE_OPENCL, p.useOpenCL)

        // 预览编码**强制 jpeg**：文档 §12 —— 原始 RGB 一帧约 12 MB，开预览会把内存打爆。
        put(EngineProtocol.RequestField.PREVIEW_FORMAT, EngineProtocol.ImageFormat.JPEG)
        put(EngineProtocol.RequestField.SHOW_DIFFUSION_PROCESS, p.showPreview)
        if (p.showPreview) {
            put(EngineProtocol.RequestField.SHOW_DIFFUSION_STRIDE, p.previewStride)
        }

        // 完成图也固定 jpeg：complete 的 image 我们按 base64 JPEG 解（见 ImageGenEvent）。
        put(EngineProtocol.RequestField.OUTPUT_FORMAT, EngineProtocol.ImageFormat.JPEG)
    }
}
