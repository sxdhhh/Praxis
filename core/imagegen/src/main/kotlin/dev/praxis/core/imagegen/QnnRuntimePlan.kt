package dev.praxis.core.imagegen

/**
 * QNN 运行时（NPU）的解压计划（文档 §1.4 的「QNN 的正确做法」）。
 *
 * ## 为什么要把它当数据、而不是写死几条文件名
 *
 * QNN 的加载链是：`libQnnHtp.so`（加载器）→ 按 DSP 架构去拿 `libQnnHtpV{68,69,73,75,79,81}`
 * 那一套（每个版本三个文件：主库 + Skel + Stub）。**哪一套可用由设备的 Hexagon 架构决定**，
 * 而应用侧查不到那个版本号 —— 所以上游 Local Dream 的做法是：
 * APK 里带**全部**版本，运行时都解出来交给引擎，由引擎自己挑。
 *
 * 这里照抄同一策略：不猜设备是哪个版本，也就不会「猜错导致 NPU 起不来」
 * （那种错误的表现是引擎直接报 `Missing --lib_dir` 或加载失败，很难归因）。
 *
 * ## 与 libdit_engine（DiT）无关
 *
 * DiT 引擎是**另一个 CMake 工程**、另一个 `.so`（文档 §1.4.1），缺它只影响
 * Z-Image / FLUX.2 / Qwen Image 那几类模型；SD1.5 / SDXL 的主线不受影响。
 */
object QnnRuntimePlan {

    /** QNN 公共库：任何 NPU 类型都需要。 */
    val CORE_FILES: List<String> = listOf("libQnnHtp.so", "libQnnSystem.so")

    /** 引擎认的 Hexagon 版本（文档 §1.4 的一张表：V68..V81）。 */
    val HEXAGON_VERSIONS: List<Int> = listOf(68, 69, 73, 75, 79, 81)

    /** 某一套的三个文件名。 */
    fun filesFor(version: Int): List<String> = listOf(
        "libQnnHtpV" + version + ".so",
        "libQnnHtpV" + version + "Skel.so",
        "libQnnHtpV" + version + "Stub.so",
    )

    /** 全部需要的文件（20 个）。 */
    val ALL_FILES: List<String> = CORE_FILES + HEXAGON_VERSIONS.flatMap { filesFor(it) }

    /**
     * 这套运行时能不能用。
     *
     * 判据只有「公共库在不在」：某个 V## 缺了只影响对应架构的机型，
     * 不该让整条 NPU 路径被判死 —— 而公共库缺了，引擎会直接报错。
     */
    fun isUsable(presentFiles: List<String>): Boolean {
        val set = presentFiles.toSet()
        return CORE_FILES.all { it in set }
    }

    /**
     * 满足 [available] 的前提下，能覆盖到哪些 Hexagon 版本。
     *
     * 界面用它告诉用户「这套运行时覆盖 6 个架构」还是「只覆盖 2 个」——
     * 用户拿不到自己的 Hexagon 版本，能得到的只有这个范围。
     */
    fun coveredVersions(available: List<String>): List<Int> {
        val set = available.toSet()
        return HEXAGON_VERSIONS.filter { version -> filesFor(version).all { it in set } }
    }

    /** 缺哪些文件（UI 报错用；空 = 齐了）。 */
    fun missing(available: List<String>): List<String> {
        val set = available.toSet()
        return ALL_FILES.filter { it !in set }
    }
}
