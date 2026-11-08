package dev.praxis.core.imagegen

/**
 * 生图槽位选的是**哪儿**出图（文档 §7.3）。
 *
 * ## 为什么不是「一个模型名」
 *
 * 本地模型 id 与服务商的 (provider, model) 是两种语义，硬塞进同一个字段就会出现
 * 「一个叫 flux 的本地模型和一条远端 flux-schnell 分不清」。分开之后，
 * 工具只管 `when (target)` 分发（文档 §5.4），UI 也能分开列出候选。
 *
 * 这个类型**不参与** `/generate` 的请求序列化 —— 它决定走哪条路，不是请求字段。
 */
sealed interface ImageGenTarget {

    /** 本地引擎（`127.0.0.1:8081`，文档 §3）。[modelId] 就是 `models/<id>` 的目录名。 */
    data class Local(val modelId: String) : ImageGenTarget

    /**
     * 远端 OpenAI 兼容的 images 端点（文档 §5.4）。
     *
     * [baseUrl] 存用户配置的原样地址（不在模块里拼路径 —— 各家版本前缀不同）。
     */
    data class Remote(
        val provider: String,
        val baseUrl: String,
        val model: String,
    ) : ImageGenTarget
}
