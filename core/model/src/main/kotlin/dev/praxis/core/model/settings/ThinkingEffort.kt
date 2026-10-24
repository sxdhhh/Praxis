package dev.praxis.core.model.settings

/**
 * 思考强度。
 *
 * ## 为什么定义在 `core:model` 而不是 `core:ai`
 *
 * 它被两处同时需要：
 *  - `ModelSettings.thinkingEffort`（用户设置）—— 属于 `core:model`；
 *  - `ChatRequest.thinkingEffort`（请求参数）—— 属于 `core:ai`。
 *
 * 而依赖方向是 `core:ai → core:model`（ai 依赖 model，不反向）。
 * 所以类型必须放在**被依赖的一侧**，否则 `core:model` 要反向依赖 `core:ai`，
 * 形成循环 —— 编译期直接失败（`Unresolved reference`）。
 *
 * `core:ai` 里保留了一个 `typealias ThinkingEffort`，所以
 * `dev.praxis.core.ai.ThinkingEffort` 这个旧写法仍然可用，
 * 不需要改动任何既有调用点。
 *
 * ## 为什么是四档而不是开关
 *
 * 各家参数名与取值域都不同：`reasoning_effort`（low/medium/high）、
 * `thinking_budget`（token 数）、`enable_thinking`（布尔）。
 * 抽象成四档由 provider 负责翻译，用户换服务商时不用重配，
 * 也不会因为填了对方不认识的取值直接 400。
 */
enum class ThinkingEffort {
    /** 不请求思考（多数服务端默认行为）。 */
    None,

    /** 少量思考：适合分类、改写这类短任务。 */
    Low,

    /** 中等：日常问答的折中。 */
    Medium,

    /** 高强度：复杂推理、多步规划，耗时与费用都更高。 */
    High,
}