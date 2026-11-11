package dev.praxis.data.imagegen

import android.content.res.AssetManager
import dev.praxis.core.imagegen.QnnRuntimePlan
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把随包的 QNN 运行时解出来给引擎用（文档 §1.4 的「QNN 的正确做法」）。
 *
 * ## 为什么必须解压，而不能直接用 assets
 *
 * 引擎要的是 `--lib_dir <目录>`：它按**文件路径**去 dlopen（`libQnnHtp.so`）
 * 并让 DSP 侧按名字找 Skel。assets 里的东西没有真实路径，所以必须落到磁盘。
 *
 * ## 版本戳目录（不是原地覆盖）
 *
 * 升级 App 时 QNN 会换版本，而**旧的 .so 会留在原地**：引擎可能 dlopen 到旧的那个，
 * 表现是「升级后 NPU 反而坏了」。所以目录名带戳，戳变了就整目录重建（文档 §1.4 同款）。
 *
 * ## 为什么整目录解而不是只解一套
 *
 * 应用侧查不到设备的 Hexagon 版本（那在 DSP 固件里）。上游 Local Dream 的做法是
 * 全解、由引擎自己挑 —— 见 [QnnRuntimePlan] 的说明。猜错版本的代价是「NPU 起不来」，
 * 而多拷贝几十 MB 的代价只是磁盘 —— 这个取舍不需要犹豫。
 */
class QnnRuntimeInstaller(
    private val assets: () -> AssetManager,
    /** 目标根目录（:app 传 `filesDir/qnn`）。 */
    private val targetRoot: () -> File,
    /** 内置 QNN 的版本戳；换了内置文件就要换它。 */
    private val stamp: String = STAMP,
    private val assetDir: String = ASSET_DIR,
) {

    fun runtimeDir(): File = File(targetRoot(), stamp)

    /** 已经解好且**关键文件齐**才算装好（半套不算 —— 引擎会报错）。 */
    fun isInstalled(): Boolean {
        val dir = runtimeDir()
        if (!dir.isDirectory) return false
        val names = dir.list()?.toList() ?: return false
        return QnnRuntimePlan.isUsable(names)
    }

    /** 已经覆盖到的 Hexagon 版本（界面展示用）。 */
    fun coveredVersions(): List<Int> {
        val dir = runtimeDir()
        if (!dir.isDirectory) return emptyList()
        return QnnRuntimePlan.coveredVersions(dir.list()?.toList() ?: emptyList())
    }

    /**
     * 解压（幂等）。
     *
     * @return 可用作 `--lib_dir` 的目录；失败时 Result.failure 带可展示的原因。
     */
    suspend fun install(): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            if (isInstalled()) return@runCatching runtimeDir()
            val dir = runtimeDir()
            // 版本戳变了：整个删掉重解，避免新旧 .so 混在一起。
            if (dir.exists()) dir.deleteRecursively()
            if (!dir.mkdirs()) error("建不了目录：" + dir.absolutePath)
            val manager = assets()
            val entries = manager.list(assetDir)?.toList()
                ?: error("APK 里没有 assets/" + assetDir + "（这个构建没带 QNN 运行时）")
            var written = 0
            entries.forEach { name ->
                val target = File(dir, name)
                manager.open(assetDir + "/" + name).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                written++
            }
            if (written == 0) error("assets/" + assetDir + " 是空的")
            if (!isInstalled()) {
                val names = dir.list()?.toList() ?: emptyList()
                error("QNN 运行时不全，缺：" + QnnRuntimePlan.missing(names).take(4).joinToString("、"))
            }
            dir
        }
    }

    companion object {
        /** assets 下的目录名（与 Local Dream 的 APK 一致）。 */
        const val ASSET_DIR: String = "qnnlibs"

        /**
         * 版本戳。
         *
         * 换内置 QNN 时改它 —— 例如 `"qnn-2.8.1"` → `"qnn-3.0.0"`。
         * 现在这版与随包的引擎同版本（都是从 2.8.1 官方 APK 提取的），
         * 两者契约一致（文档 §1.5 特意警告过不要混版本）。
         */
        const val STAMP: String = "qnn-3.0.0"
    }
}
