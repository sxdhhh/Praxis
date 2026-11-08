package dev.praxis.core.imagegen

/**
 * 生图失败的分类（文档 §5.2 的失败表 + §5.1 的 invalid_size）。
 *
 * ## 为什么必须分类，而不是一句「失败了」
 *
 * 模型看到笼统的失败只会拿同样的参数重试；看到 [Oom] 才会自己把尺寸调小，
 * 看到 [NoModel] 才会去提示用户装模型。这与网关那边「错误分类决定重试策略」
 * 是同一条经验（见 :core:gateway 的 FailurePolicy）。
 *
 * ## 为什么只有一个「参数不合法」档
 *
 * 契约只有这 7 个分类，而参数越界在文档里对应的错误码就是 `invalid_size`（文档 §5.1：
 * 「不合法就返回 invalid_size 并列出可用档位」）。所以 [InvalidSize] 承载**全部**参数
 * 越界，具体是哪一项写在 [InvalidSize.parameter] 里，人读的句子写在 [detail] 里。
 */
sealed interface ImageGenError {

    /** 稳定错误码，直接进 ToolResult 的 JSON（文档 §5.2 的 `error` 字段）。 */
    val code: String

    /** 给人/模型看的一句话补充；null = 由上层按 [code] 出默认文案。 */
    val detail: String? get() = null

    /** 没装模型（文档 §5.2：`{"ok":false,"error":"no_model","hint":"先在设置→生图里下载一个模型"}`）。 */
    data class NoModel(val modelId: String? = null) : ImageGenError {
        override val code: String get() = ImageGenError.CODE_NO_MODEL
        override val detail: String?
            get() = modelId?.takeIf { it.isNotBlank() }?.let { "模型 " + it + " 未安装" }
    }

    /** 显存/内存不足（文档 §12：OOM 时自动降一档尺寸重试一次，再失败才报错）。 */
    data class Oom(override val detail: String? = null) : ImageGenError {
        override val code: String get() = ImageGenError.CODE_OOM
    }

    /** 设备不支持（ABI / SoC / 系统版本门控，文档 §9）。 */
    data class UnsupportedDevice(override val detail: String? = null) : ImageGenError {
        override val code: String get() = ImageGenError.CODE_UNSUPPORTED_DEVICE
    }

    /** 引擎起不来或没就绪（`nativeLibraryDir` 里没有二进制、端口被占、`/health` 一直不通）。 */
    data class EngineStartFailed(override val detail: String? = null) : ImageGenError {
        override val code: String get() = ImageGenError.CODE_ENGINE_START_FAILED
    }

    /**
     * 参数越界：尺寸不在模型档位里，或采样参数不在文档 §5.1 的范围内。
     *
     * @param parameter 越界的那一项，取值取自 [EngineProtocol.RequestField]。
     * @param requested 收到的值（尺寸写成 `512x768`）。
     * @param availableSizes 当前模型可用档位；空 = 只做了全局范围校验。
     */
    data class InvalidSize(
        val parameter: String = "",
        val requested: String = "",
        val availableSizes: List<Pair<Int, Int>> = emptyList(),
        override val detail: String? = null,
    ) : ImageGenError {
        override val code: String get() = ImageGenError.CODE_INVALID_SIZE
    }

    /** 引擎正忙（上一次生成还没结束，或正在换模型重启，文档 §8.6 的 `engine_starting`）。 */
    data class ToolBusy(override val detail: String? = null) : ImageGenError {
        override val code: String get() = ImageGenError.CODE_TOOL_BUSY
    }

    /** 远端服务商侧失败（HTTP 状态码 / 网络，文档 §5.4 的另一条路）。 */
    data class Remote(val statusCode: Int? = null, override val detail: String? = null) : ImageGenError {
        override val code: String get() = ImageGenError.CODE_REMOTE
    }

    companion object {
        const val CODE_NO_MODEL: String = "no_model"
        const val CODE_OOM: String = "oom"
        const val CODE_UNSUPPORTED_DEVICE: String = "unsupported_device"
        const val CODE_ENGINE_START_FAILED: String = "engine_start_failed"
        const val CODE_INVALID_SIZE: String = "invalid_size"
        const val CODE_TOOL_BUSY: String = "tool_busy"
        const val CODE_REMOTE: String = "remote"
    }
}
