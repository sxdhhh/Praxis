package dev.praxis.core.imagegen

/**
 * 一次生图的参数（文档 §5.1 的参数表 + §4.2 的默认值）。
 *
 * ## 哪些东西不在这里
 *
 * - [ImageGenTarget]：目标（本地模型 / 远端服务商）是**持久配置**，不是请求字段；
 * - `count`（连出几张）：那是「同一个请求重复调用 N 次」，引擎没有这个参数；
 * - 图生图的 `image` / `mask` 与超分的 `ultrafix` / `tile_size`：文档 P5 的能力，
 *   本模块这一轮只做文生图（字段名仍归 [EngineProtocol.RequestField] 管）。
 *
 * ## 可选字段的 null 语义
 *
 * [scheduler] 与 [seed] 的 null 表示**不写进请求**（引擎自己取默认 / 随机）。
 * 注意 `seed = 0` 是合法种子，与 null 不是一回事 —— 序列化时不能合并处理。
 */
data class ImageGenParams(
    /** 正向提示词。必填；空串不在这里拦（工具的参数 schema 管 required）。 */
    val prompt: String,
    val negative: String = "",
    val width: Int = DEFAULT_WIDTH,
    val height: Int = DEFAULT_HEIGHT,
    val steps: Int = DEFAULT_STEPS,
    val cfg: Float = DEFAULT_CFG,
    /** 调度器。null = 用引擎默认（文档 §5.1：只在本地目标有效）。 */
    val scheduler: String? = null,
    /** 固定种子。null = 随机；complete 消息会回传实际值（文档 §3.2）。 */
    val seed: Long? = null,
    /** 是否推每步预览。默认关 —— 文档 §12：每步预览会让内存与带宽翻倍。 */
    val showPreview: Boolean = false,
    /** 每 N 步推一帧预览；只在 [showPreview] 为真时有意义。 */
    val previewStride: Int = DEFAULT_PREVIEW_STRIDE,
    /** 图生图的去噪强度；文生图为 1.0（文档 §3.2）。 */
    val denoise: Float = DEFAULT_DENOISE,
    /** OpenCL 后端开关（请求字段 `use_opencl`）。 */
    val useOpenCL: Boolean = false,
) {
    companion object {
        const val DEFAULT_WIDTH: Int = 512
        const val DEFAULT_HEIGHT: Int = 512
        const val DEFAULT_STEPS: Int = 20
        const val DEFAULT_CFG: Float = 7f
        const val DEFAULT_DENOISE: Float = 1f
        const val DEFAULT_PREVIEW_STRIDE: Int = 1
    }
}

/**
 * 参数的合法范围与校验（文档 §5.1）。
 *
 * ## 为什么尺寸必须单独校验（文档 §5.1 末段）
 *
 * SD1.5 的 NPU 只能跑 patch 覆盖到的档位；请求一个没有 patch 的尺寸，引擎会
 * **静默回落 512 且不报错**。所以这里必须挡在请求之前，并把可用档位一起返回，
 * 让人（或模型）能一次改对 —— 而不是画出一张尺寸不对的图。
 *
 * ## 越界统一落 invalid_size
 *
 * 契约只有 7 个失败分类，参数越界对应的错误码就是 `invalid_size`。
 * 具体越界的是哪一项写在 [ImageGenError.InvalidSize.parameter] 里，
 * 人读的句子在 [ImageGenError.InvalidSize.detail] 里，所以不会看不出是步数还是尺寸。
 */
object ImageGenParamsRules {

    /** 文档 §5.1：width 256~1024。 */
    val WIDTH_RANGE: IntRange = 256..1024

    /** 文档 §5.1：height 256~1024。 */
    val HEIGHT_RANGE: IntRange = 256..1024

    /** 文档 §5.1：steps 1~60。 */
    val STEPS_RANGE: IntRange = 1..60

    /** 文档 §5.1：cfg 1~20。 */
    val CFG_RANGE: ClosedFloatingPointRange<Float> = 1f..20f

    /** 文档 §5.1：denoise 0~1。 */
    val DENOISE_RANGE: ClosedFloatingPointRange<Float> = 0f..1f

    /** `show_diffusion_stride`：每 N 步一帧。范围文档没写，按「不超过总步数上限」取 1~60。 */
    val PREVIEW_STRIDE_RANGE: IntRange = 1..60

    /**
     * 校验一次生图的参数。
     *
     * @param availableSizes 当前模型可用档位（宽,高）。空 = 只做全局范围校验
     *   （例如 CPU 后端、或还没读到模型清单）。
     * @return 合法返回 null；不合法返回 [ImageGenError.InvalidSize]。
     */
    fun validate(
        p: ImageGenParams,
        availableSizes: List<Pair<Int, Int>> = emptyList(),
    ): ImageGenError? {
        if (p.width !in WIDTH_RANGE) {
            return outOfRange(EngineProtocol.RequestField.WIDTH, p.width, WIDTH_RANGE)
        }
        if (p.height !in HEIGHT_RANGE) {
            return outOfRange(EngineProtocol.RequestField.HEIGHT, p.height, HEIGHT_RANGE)
        }
        if (p.steps !in STEPS_RANGE) {
            return outOfRange(EngineProtocol.RequestField.STEPS, p.steps, STEPS_RANGE)
        }
        if (!p.cfg.isFinite() || p.cfg !in CFG_RANGE) {
            return outOfRange(EngineProtocol.RequestField.CFG, p.cfg.toString(), CFG_RANGE)
        }
        if (!p.denoise.isFinite() || p.denoise !in DENOISE_RANGE) {
            return outOfRange(EngineProtocol.RequestField.DENOISE_STRENGTH, p.denoise.toString(), DENOISE_RANGE)
        }
        // 预览关着的时候 stride 不参与请求（不写进 JSON），拦它只会是噪音。
        if (p.showPreview && p.previewStride !in PREVIEW_STRIDE_RANGE) {
            return outOfRange(
                EngineProtocol.RequestField.SHOW_DIFFUSION_STRIDE,
                p.previewStride,
                PREVIEW_STRIDE_RANGE,
            )
        }
        if (availableSizes.isNotEmpty() && availableSizes.none { it.first == p.width && it.second == p.height }) {
            val requested = p.width.toString() + "x" + p.height
            return ImageGenError.InvalidSize(
                parameter = EngineProtocol.RequestField.WIDTH,
                requested = requested,
                availableSizes = availableSizes,
                detail = "当前模型没有 " + requested + " 这一档；可用：" + formatSizes(availableSizes),
            )
        }
        return null
    }

    /** 把档位列表拼成 `512x512 / 512x768`，给提示文案用。 */
    fun formatSizes(sizes: List<Pair<Int, Int>>): String =
        sizes.joinToString(" / ") { it.first.toString() + "x" + it.second.toString() }

    private fun outOfRange(
        parameter: String,
        value: Any,
        range: ClosedRange<*>,
    ): ImageGenError.InvalidSize = ImageGenError.InvalidSize(
        parameter = parameter,
        requested = value.toString(),
        detail = parameter + " 必须在 " + range.start + "~" + range.endInclusive + " 之间，收到 " + value,
    )
}
