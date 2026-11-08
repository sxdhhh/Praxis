package dev.praxis.core.imagegen

import java.io.File

/**
 * 模型体积文本的解析（`approximateSize`：`"2GB"` / `"1.5GB"` / `"800MB"`）。
 *
 * ## 为什么它是必需的，而不是「显示用」
 *
 * 空间预检要用它。而预检失败必须能告诉用户**差多少** ——
 * 只说「空间不足」，用户只能靠猜卸载哪个应用。
 */
object ModelSize {

    private val PATTERN = Regex("^\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(GB|MB|KB|B)\\s*$", RegexOption.IGNORE_CASE)

    /** 解析不出来返回 null —— 调用方据此走「无法预估」而不是当成 0。 */
    fun parse(text: String?): Long? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val match = PATTERN.matchEntire(raw) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].uppercase()
        val factor = when (unit) {
            "B" -> 1L
            "KB" -> 1024L
            "MB" -> 1024L * 1024
            else -> 1024L * 1024 * 1024
        }
        return (value * factor).toLong()
    }
}

/** 一个要下载的远端文件与它的本地落点。 */
data class DownloadTarget(val remotePath: String, val localName: String)

/**
 * 一次模型下载的计划（文档 §8.3/§8.4）。
 *
 * 纯数据 + 纯函数产物：要下哪些文件、落到哪、装好后写什么标记、失败时清什么。
 * 真正发 HTTP、解压、起前台服务的是 :app（那一层没法在本地验证）。
 */
data class ImageDownloadPlan(
    val modelId: String,
    /** 模型目录 `models/<id>`（不存在则创建）。 */
    val targetDir: File,
    /** true = 单 zip（下整包再解压）；false = 多文件（逐个下，不占双份空间）。 */
    val isZip: Boolean,
    /** 要下载的清单。zip 时只有一条（那个压缩包）。 */
    val targets: List<DownloadTarget>,
    /** zip 的临时文件名（下载完解压后删）。 */
    val zipTempName: String,
    /** 解压用的临时子目录名。 */
    val extractTempName: String,
    /** 多文件包的完成标记；zip 包为空串（判据是目录非空）。 */
    val markerFileName: String,
    /** 由 `approximateSize` 解析出的所需空间；null = 无法预估。 */
    val requiredBytes: Long?,
) {
    /** 每个目标的 `.part` 路径（中断残留、失败清理都用它）。 */
    val partPaths: List<File> get() = targets.map { File(targetDir, it.localName + PART_SUFFIX) }

    val zipTempFile: File get() = File(targetDir, zipTempName)
    val extractTempDir: File get() = File(targetDir, extractTempName)

    companion object {
        /** 未完成下载的临时后缀。去掉它才表示这个文件下好了。 */
        const val PART_SUFFIX: String = ".part"
    }
}

/** 组装下载计划。 */
object ImageDownloadPlanner {

    /**
     * @param modelsRoot 模型根目录（`<root>/models` 的父级由调用方保证，这里只拼 `<id>`）
     */
    fun plan(modelsRoot: File, model: ImageModel): ImageDownloadPlan {
        val dir = File(modelsRoot, model.id)
        val zip = !model.isMultiFile
        val targets = if (zip) {
            listOf(
                DownloadTarget(
                    remotePath = model.fileUri,
                    localName = model.fileUri.substringAfterLast('/'),
                ),
            )
        } else {
            model.packageFiles.map { entry ->
                DownloadTarget(
                    remotePath = entry.substringBefore('|'),
                    localName = model.localNameOf(entry),
                )
            }
        }
        return ImageDownloadPlan(
            modelId = model.id,
            targetDir = dir,
            isZip = zip,
            targets = targets,
            // 必须与下载时真正写出的 .part 同名（<远端文件名>.part），
            // 否则失败清理删不掉它，下次预检会把残留算进占用。
            zipTempName = if (zip) {
                targets.first().localName + ImageDownloadPlan.PART_SUFFIX
            } else {
                ""
            },
            extractTempName = model.id + "_extract",
            markerFileName = if (zip) "" else ImageModelCatalog.markerFileName(model.ditKind),
            requiredBytes = ModelSize.parse(model.approximateSize),
        )
    }
}

/** 空间预检结论。 */
sealed interface SpaceDecision {

    /** 空间充足。 */
    data object Enough : SpaceDecision

    /**
     * 空间不足。必须把**差额**带给用户 —— 只说「空间不足」等于让人猜。
     */
    data class Insufficient(
        val requiredBytes: Long,
        val freeBytes: Long,
    ) : SpaceDecision {
        val shortfallBytes: Long get() = (requiredBytes - freeBytes).coerceAtLeast(0L)
    }

    /**
     * 体积未知（`approximateSize` 解析不出来），**不当作充足**。
     *
     * 这一档必须存在：把「不知道」并进 [Enough] 就是兜底式设计 ——
     * 用户会在下载到一半时才发现空间不够，而那时已经没有回头路了。
     */
    data class Unknown(val freeBytes: Long) : SpaceDecision
}

/**
 * 下载前的空间预检（文档 §8.4 第 1 条）。
 *
 * ## 为什么留 30% 余量
 *
 * 单 zip 包在解压时要**同时**占住压缩包与展开后的内容；多文件包的裸权重也普遍比压缩包大。
 * 只按包体大小判断，会在解压到一半时失败 —— 那时用户已经等了很久。
 */
object SpaceCheck {

    const val SAFETY_FACTOR: Double = 1.3

    fun decide(requiredBytes: Long?, freeBytes: Long): SpaceDecision {
        if (requiredBytes == null || requiredBytes <= 0L) return SpaceDecision.Unknown(freeBytes)
        val needed = (requiredBytes * SAFETY_FACTOR).toLong()
        return if (freeBytes >= needed) {
            SpaceDecision.Enough
        } else {
            SpaceDecision.Insufficient(requiredBytes = needed, freeBytes = freeBytes)
        }
    }
}

/**
 * 失败/取消后的清理清单（文档 §8.4 第 2 条）。
 *
 * 不清理的后果不是「占点空间」，而是**下次预检误判空间不足** ——
 * 用户会看到一个自相矛盾的现象：明明刚删了模型，还是提示装不下。
 */
object DownloadCleanup {

    fun staleFiles(plan: ImageDownloadPlan): List<File> =
        plan.partPaths + listOfNotNull(
            plan.zipTempFile.takeIf { plan.zipTempName.isNotEmpty() },
        )

    /** 需要整目录删除的临时解压目录。 */
    fun staleDirs(plan: ImageDownloadPlan): List<File> = listOf(plan.extractTempDir)
}
