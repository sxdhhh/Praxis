package dev.praxis.core.imagegen

/** 引擎从哪来（文档 §1.1 的三种形态）。 */
enum class ImageGenEngineSource {
    /** 形态 A：外部 App（Local Dream 的 host 模式）。 */
    External,
    /** 形态 B：Praxis 自带的 Linux 容器里。 */
    Container,
    /** 形态 C：随包内置（自用构建）。 */
    Bundled,
}

/**
 * 引擎探活结果。
 *
 * ## 为什么放在 core 而不是 :app
 *
 * 设置页（`:feature:settings`）要展示它。若定义在 `:app`，依赖方向就反了
 * （feature 不能依赖 app）。它本身是纯数据，放这里两边都能用。
 */
sealed interface EngineAvailability {

    /**
     * 引擎可用。
     *
     * [source] 为 null 表示**归属尚未探测** —— 形态 B/C 的识别（8808 控制面 `/info`、
     * 或 proot 进程存在性）还没实现，现在只知道「8081 上有人应答且是健康响应」。
     * 不硬塞一个 [ImageGenEngineSource.External]：那会让设置页显示一个没验证过的结论。
     */
    data class Available(
        val health: EngineHealth,
        val source: ImageGenEngineSource? = null,
    ) : EngineAvailability

    /** 不可用。 [detail] 是给人看的原因（HTTP 码 / 异常信息片段）。 */
    data class Unavailable(val reason: Reason, val detail: String? = null) : EngineAvailability {
        enum class Reason {
            /** 端口没人应答（最常见：引擎没起）。 */
            NotRunning,
            /** 应答了但 HTTP 非 2xx。 */
            HttpError,
            /** 应答了但 `/health` 不是可解析的健康响应。 */
            MalformedHealth,
        }
    }
}
