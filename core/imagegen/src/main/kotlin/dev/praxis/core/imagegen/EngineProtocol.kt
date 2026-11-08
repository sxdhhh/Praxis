package dev.praxis.core.imagegen

/**
 * 引擎 HTTP 契约的**唯一出处**（文档 `docs/PORTING-IMAGEGEN.md` §3）。
 *
 * ## 为什么要有这个文件
 *
 * 这些字符串是 Praxis 与一个**独立原生进程**之间的全部约定：端点、请求字段名、NDJSON
 * 消息字段名。散在各处时，改错一个下划线的表现是「HTTP 200 但没有图」，排查代价远高于
 * 维护一张常量表。文档 §3.2 已经把字段名逐字列好了 —— 这里就是那张表的代码形态。
 *
 * ## 版本差异
 *
 * 文档 §1.5：2.8.1 与 3.0.0-alpha.7 的契约并不一致（`reference_images` 只在新版有）。
 * 这里只放**两版共有**的字段；能力位按版本探测，不做编译期假定。
 */
object EngineProtocol {

    /** 引擎默认只听本机回环（文档 §3.1：`--port` 默认 8081）。 */
    const val LOOPBACK: String = "127.0.0.1"

    /** 默认端口。文档 §1.2：A/B/C 三种形态共用 127.0.0.1:8081。 */
    const val DEFAULT_PORT: Int = 8081

    const val PATH_HEALTH: String = "/health"
    const val PATH_GENERATE: String = "/generate"
    const val PATH_TOKENIZE: String = "/tokenize"
    const val PATH_UPSCALE: String = "/upscale"
    const val PATH_FINISHED: String = "/finished"

    /** 形如 `http://127.0.0.1:8081`（无尾斜杠）。 */
    fun baseUrl(port: Int = DEFAULT_PORT): String = "http://" + LOOPBACK + ":" + port

    /** 就绪探测。 */
    fun healthUrl(port: Int = DEFAULT_PORT): String = baseUrl(port) + PATH_HEALTH

    /** 出图入口；响应是 **NDJSON 流**，不是 SSE（文档 §3.3）。 */
    fun generateUrl(port: Int = DEFAULT_PORT): String = baseUrl(port) + PATH_GENERATE

    /** 分词（提示词长度预检）。 */
    fun tokenizeUrl(port: Int = DEFAULT_PORT): String = baseUrl(port) + PATH_TOKENIZE

    /** 超分（`--upscaler_mode` 时的主要入口）。 */
    fun upscaleUrl(port: Int = DEFAULT_PORT): String = baseUrl(port) + PATH_UPSCALE

    /** 引擎侧任务结束标记（Praxis 可忽略，见文档 §3.4）。 */
    fun finishedUrl(port: Int = DEFAULT_PORT): String = baseUrl(port) + PATH_FINISHED

    /**
     * `POST /generate` 请求体的字段名（文档 §3.2 全表，**逐字，不得改名**）。
     *
     * 表里 19 个名字一个不少地列在这里 —— 包括本模块暂时不写的那些
     * （`image` / `mask` / `aspect_ratio` / `ultrafix` / `tile_size`，属文档 P5 的图生图与超分）。
     * 「字段名只有一个来源」这件事对未用到的字段同样成立：写 P5 的人不该去别处抄字符串。
     */
    object RequestField {
        const val PROMPT: String = "prompt"
        const val NEGATIVE_PROMPT: String = "negative_prompt"
        const val STEPS: String = "steps"
        const val CFG: String = "cfg"
        const val WIDTH: String = "width"
        const val HEIGHT: String = "height"
        const val SCHEDULER: String = "scheduler"
        const val SEED: String = "seed"
        const val DENOISE_STRENGTH: String = "denoise_strength"
        const val USE_OPENCL: String = "use_opencl"
        const val PREVIEW_FORMAT: String = "preview_format"
        const val SHOW_DIFFUSION_PROCESS: String = "show_diffusion_process"
        const val SHOW_DIFFUSION_STRIDE: String = "show_diffusion_stride"
        const val ASPECT_RATIO: String = "aspect_ratio"
        const val IMAGE: String = "image"
        const val MASK: String = "mask"
        const val ULTRAFIX: String = "ultrafix"
        const val TILE_SIZE: String = "tile_size"
        const val OUTPUT_FORMAT: String = "output_format"

        /** 文档 §3.2 全表的字段名，按文档顺序。测试用它钉住「一个都没漏、一个都没改名」。 */
        val ALL: List<String> = listOf(
            PROMPT, NEGATIVE_PROMPT, STEPS, CFG, WIDTH, HEIGHT, SCHEDULER, SEED,
            DENOISE_STRENGTH, USE_OPENCL, PREVIEW_FORMAT, SHOW_DIFFUSION_PROCESS,
            SHOW_DIFFUSION_STRIDE, ASPECT_RATIO, IMAGE, MASK, ULTRAFIX, TILE_SIZE,
            OUTPUT_FORMAT,
        )
    }

    /** NDJSON 每行 JSON 里的字段名（文档 §3.3）。 */
    object MessageField {
        const val TYPE: String = "type"
        const val PROGRESS: String = "progress"
        const val IMAGE: String = "image"
        const val SEED: String = "seed"
        const val WIDTH: String = "width"
        const val HEIGHT: String = "height"
    }

    /** `type` 的取值（文档 §3.3：只有这两种）。 */
    object MessageType {
        const val PROGRESS: String = "progress"
        const val COMPLETE: String = "complete"
    }

    /** 图像编码。预览与完成图都走 jpeg（文档 §12：开了预览也强制 jpeg）。 */
    object ImageFormat {
        const val JPEG: String = "jpeg"
    }

    /**
     * `GET /health` 响应里会被读到的字段。
     *
     * 只有这一个键有意义（判定见 [EngineHealthCodec]）；其余字段不建模，
     * 原样活在 [EngineHealth.raw] 里 —— 文档没给 /health 的 schema，不猜。
     */
    object HealthField {
        const val STATUS: String = "status"
    }
}
