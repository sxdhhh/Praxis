package dev.praxis.core.imagegen

/**
 * 引擎来源（文档 §1.2 的三态）。
 *
 * ## 为什么是枚举而不是「有没有引擎」的布尔
 *
 * 三种形态的**启动者不同**（另一个 App / proot / 我们自己），可用性判定、
 * 失败提示、以及「该摆哪个按钮」都不一样。用布尔抹平之后，
 * 界面只能给出「没检测到引擎」这种没用的提示 —— 而那正是用户看到的现状。
 */
enum class EngineSource {
    /** A：外部 App 提供（Local Dream host 模式）。Praxis 不启动进程。 */
    External,

    /** B：容器内引擎，由 proot 启动，Praxis 只等端口就绪。 */
    Container,

    /** C：随包引擎，从 `nativeLibraryDir` 起进程（文档 §3.1「Praxis 的起法」）。 */
    Bundled,
}

/**
 * 起引擎要的全部参数（文档 §3.1 的实测参数表）。
 *
 * @param enginePath 引擎可执行文件路径（形态 C = `nativeLibraryDir/libstable_diffusion_core.so`）。
 * @param type 引擎 `--type`：`sd15cpu`（MNN/CPU）/ `sd15npu` / `sdxl` / `anima`（后三者是 QNN）。
 * @param modelDir 该模型的文件目录（各类型文件名固定，见文档 §4.1）。
 * @param libDir QNN 专用：含 `libQnnHtp.so` 与 `libQnnSystem.so` 的目录。
 * @param patchFile `sd15npu` 且非 512 画布时的 zstd 分辨率补丁。
 * @param lowram `sdxl` / `anima` 逐段加载释放（省内存、慢一些）。
 */
data class EngineLaunchSpec(
    val enginePath: String,
    val type: String,
    val modelDir: String,
    val libDir: String? = null,
    val port: Int = DEFAULT_PORT,
    val patchFile: String? = null,
    val lowram: Boolean = false,
    val listenAll: Boolean = false,
    val noImg2Img: Boolean = false,
) {
    companion object {
        /** 引擎默认端口（文档 §3.1：`--port <n>` 默认 8081）。 */
        const val DEFAULT_PORT: Int = 8081
    }
}

/** 起引擎前能查出来的问题。 */
enum class EngineLaunchError {
    MissingEngineFile,
    UnknownType,
    MissingModelDir,
    /** QNN 类型少了 `--lib_dir`（引擎会回「Missing --lib_dir for QNN」）。 */
    MissingLibDir,
    /** `--lib_dir` 里缺 `libQnnHtp.so` / `libQnnSystem.so`。 */
    IncompleteLibDir,
    /** `sd15npu` 非 512 画布却没有补丁文件。 */
    MissingPatch,
    BadPort,
}

fun EngineLaunchError.userMessage(): String = when (this) {
    EngineLaunchError.MissingEngineFile -> "引擎文件不在（形态 C 需要随包内置）"
    EngineLaunchError.UnknownType -> "这家引擎类型不认识（只认 sd15cpu / sd15npu / sdxl / anima）"
    EngineLaunchError.MissingModelDir -> "模型目录是空的：先把模型下载完"
    EngineLaunchError.MissingLibDir -> "QNN 类型必须给 --lib_dir（缺了引擎会直接报错）"
    EngineLaunchError.IncompleteLibDir -> "--lib_dir 里要同时有 libQnnHtp.so 与 libQnnSystem.so"
    EngineLaunchError.MissingPatch -> "这个分辨率需要补丁文件（引擎缺了它会**静默回落到 512**）"
    EngineLaunchError.BadPort -> "端口要在 1..65535 之间"
}

/**
 * 起引擎的规则（文档 §3.1 的两个「容易忽略的点」都在这里）。
 */
object EngineLaunchRules {

    /** QNN 类型：需要 `--lib_dir`。`sd15cpu` 是 MNN，纯 CPU 不需要 QNN 库。 */
    val QNN_TYPES: Set<String> = setOf("sd15npu", "sdxl", "anima")

    /** 引擎认的全部类型（文档 §3.1 的 usage）。 */
    val KNOWN_TYPES: Set<String> = setOf("sd15cpu", "sd15npu", "sdxl", "anima")

    /** QNN 目录里必须同时存在的两个文件。 */
    val REQUIRED_QNN_FILES: List<String> = listOf("libQnnHtp.so", "libQnnSystem.so")

    fun requiresQnn(type: String): Boolean = type in QNN_TYPES

    /**
     * 这个类型要不要 `--lowram`（sdxl / anima 逐段加载释放）。
     *
     * 不放进 [validate]：它是**建议**不是错误 —— 少给只是内存峰值高，
     * 引擎照样能跑。
     */
    fun prefersLowRam(type: String): Boolean = type == "sdxl" || type == "anima"

    /**
     * 起之前先查一遍。
     *
     * @param engineExists 引擎文件在不在（形态 C 检查 nativeLibraryDir）。
     * @param modelDirEmpty 模型目录是不是空的。
     * @param libDirFiles `--lib_dir` 目录里的文件名（null = 没给该参数）。
     * @param needsPatch 这个画布需要补丁（只有 `sd15npu` 非 512 时才为 true）。
     */
    fun validate(
        spec: EngineLaunchSpec,
        engineExists: Boolean = true,
        modelDirEmpty: Boolean = false,
        libDirFiles: List<String>? = null,
        needsPatch: Boolean = false,
    ): EngineLaunchError? {
        if (!engineExists) return EngineLaunchError.MissingEngineFile
        if (spec.port !in 1..65535) return EngineLaunchError.BadPort
        if (spec.type !in KNOWN_TYPES) return EngineLaunchError.UnknownType
        if (modelDirEmpty) return EngineLaunchError.MissingModelDir
        if (requiresQnn(spec.type)) {
            val dir = spec.libDir
            if (dir.isNullOrBlank()) return EngineLaunchError.MissingLibDir
            if (libDirFiles != null &&
                !REQUIRED_QNN_FILES.all { required -> libDirFiles.any { it == required } }
            ) {
                return EngineLaunchError.IncompleteLibDir
            }
        }
        if (needsPatch && spec.patchFile.isNullOrBlank()) return EngineLaunchError.MissingPatch
        return null
    }

    /**
     * 拼出真正的命令行（参数顺序与文档 §3.1 的示例一致）。
     *
     * 不在这里做的事：检查文件是否存在（那是 [validate] 与调用方的事）——
     * 纯函数只负责「参数怎么拼」，混进文件系统就不可能在单测里覆盖。
     */
    fun command(spec: EngineLaunchSpec): List<String> {
        val out = mutableListOf(spec.enginePath)
        out += listOf("--type", spec.type)
        out += listOf("--model_dir", spec.modelDir)
        spec.libDir?.takeIf { it.isNotBlank() }?.let { out += listOf("--lib_dir", it) }
        out += listOf("--port", spec.port.toString())
        spec.patchFile?.takeIf { it.isNotBlank() }?.let { out += listOf("--patch", it) }
        if (spec.lowram) out += "--lowram"
        if (spec.noImg2Img) out += "--no_img2img"
        if (spec.listenAll) out += "--listen_all"
        return out
    }

    /**
     * 从模型条目拼参数（把「模型知道的事」与「起法」接上）。
     *
     * `patchFile` 只在 `sd15npu` 且画布不是 512 时才带上 —— 引擎缺补丁时会
     * **静默回落 512**，所以那是调用方要显式检查的事（[validate] 的 needsPatch）。
     */
    fun specFor(
        model: ImageModel,
        enginePath: String,
        modelDir: String,
        libDir: String? = null,
        patchFile: String? = null,
        port: Int = EngineLaunchSpec.DEFAULT_PORT,
        listenAll: Boolean = false,
    ): EngineLaunchSpec = EngineLaunchSpec(
        enginePath = enginePath,
        type = model.backendType,
        modelDir = modelDir,
        libDir = libDir,
        port = port,
        patchFile = patchFile,
        lowram = prefersLowRam(model.backendType),
        listenAll = listenAll,
    )

    /** 这个模型在当前画布下是否需要补丁文件。 */
    fun needsPatch(model: ImageModel, size: Int): Boolean =
        model.backendType == "sd15npu" && size != 512
}
