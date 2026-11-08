package dev.praxis.core.imagegen

import java.io.File

/**
 * 一个可下载的生图模型（文档 §8.1 的目录条目）。
 *
 * 字段名刻意与引擎/上游目录一致：`backendType` 直接喂给 `--type`，
 * `packageFiles` 决定「单 zip 还是多文件」，两者都不做二次翻译，
 * 否则排查时要对着三套命名来回猜。
 */
data class ImageModel(
    val id: String,
    val name: String,
    val description: String = "",
    /** 相对下载源的路径；内置条目按芯片档位生成（见 [ChipsetTier]）。 */
    val fileUri: String,
    /** 引擎 `--type` 的取值（sd15cpu / sd15npu / sdxl / anima）。 */
    val backendType: String,
    /** 默认画布边长。 */
    val generationSize: Int = 512,
    val approximateSize: String = "1GB",
    val runOnCpu: Boolean = false,
    val isSdxl: Boolean = false,
    val isAnima: Boolean = false,
    /** 非空 = DiT 系（ZIMAGE / KLEIN / QWEN_IMAGE_2_1 / KREA_2_TURBO）。 */
    val ditKind: String = "",
    /**
     * 多文件包：每条形如 `远端路径|本地文件名`。空 = 单 zip。
     *
     * 大包（DiT）必须走多文件：整包解压要同时占住压缩包与展开后的空间，
     * 而多文件让中断后**按文件粒度**续传（文档 §8.3）。
     */
    val packageFiles: List<String> = emptyList(),
    /** 用户自己导入的模型。 */
    val isCustom: Boolean = false,
) {
    val isDit: Boolean get() = ditKind.isNotEmpty()
    val isMultiFile: Boolean get() = packageFiles.isNotEmpty()

    /** 多文件包里的本地文件名（从 `远端|本地` 里取本地侧）。 */
    fun localNameOf(packageEntry: String): String =
        packageEntry.substringAfter('|', packageEntry.substringAfterLast('/'))
}

/**
 * 芯片档位 -> 下载文件名的后缀（文档 §8.2）。
 *
 * ## 为什么必须按档位选
 *
 * NPU 包与芯片强绑定：把 8gen2 的包下到 8gen1 上，引擎起不来（或跑出错图）。
 * 上游的做法是一张**精确匹配表**，匹配不到再按 `SM` 前缀回落 `min`。
 * 照抄固定后缀 = 在别的机型上下载到跑不动的包。
 *
 * 表里之所以大量条目都是 `8gen2`：那是同一代 NPU 驱动的一批型号，
 * 真正的分水岭在 8gen1 与 8gen2 之间。
 */
object ChipsetTier {

    private val SUFFIXES: Map<String, String> = mapOf(
        "SM8475" to "8gen1",
        "SM8450" to "8gen1",
        "SM8550" to "8gen2",
        "SM8550P" to "8gen2",
        "QCS8550" to "8gen2",
        "QCM8550" to "8gen2",
        "SM8650" to "8gen2",
        "SM8650P" to "8gen2",
        "SM8750" to "8gen2",
        "SM8750P" to "8gen2",
        "SM8850" to "8gen2",
        "SM8850P" to "8gen2",
        "SM8735" to "8gen2",
        "SM8845" to "8gen2",
        "SM8950" to "8gen2",
        "SM8975" to "8gen2",
    )

    /** 兜底档位：任何 `SM*` 型号认得但不精确匹配时用它。 */
    const val FALLBACK: String = "min"

    /**
     * @return 档位后缀；非骁龙（型号不以 `SM` 开头）返回 null —— 那类设备只能走 CPU 包。
     */
    fun suffixFor(soc: String?): String? {
        val model = soc?.trim()?.uppercase()
        if (model.isNullOrEmpty()) return null
        SUFFIXES[model]?.let { return it }
        return if (model.startsWith("SM")) FALLBACK else null
    }

    /** 下载路径里用的档位（认得型号就用它，否则 `min`）。 */
    fun downloadSuffix(soc: String?): String = suffixFor(soc) ?: FALLBACK
}

/**
 * 内置模型目录 + 安装态判定（文档 §8.1–§8.4）。
 *
 * ## 目录是**数据**，不是逻辑
 *
 * 下面只放了四条作为种子（覆盖三种后端：SD1.5 NPU / SD1.5 CPU / SDXL），
 * 完整清单是纯数据补充 —— 加一条不需要改任何代码。之所以不一次抄全：
 * 上游目录（CC BY-NC）里的模型数量与地址会随其发布变化，
 * 复制一份进来就成了「我们这边也有个会过期的副本」。
 * 详见 docs/PORTING-IMAGEGEN.md §8.1。
 */
object ImageModelCatalog {

    /**
     * 内置目录。`soc` 用于拼 NPU 包的档位后缀。
     *
     * @param soc `Build.SOC_MODEL` 的原值（例如 `SM8650`）；null 时按 `min` 档位生成。
     */
    fun builtin(soc: String?): List<ImageModel> {
        val tier = ChipsetTier.downloadSuffix(soc)
        return listOf(
            // ── SD1.5 NPU（QD）：包名带档位 ──
            ImageModel(
                id = "anythingv5",
                name = "AnythingV5",
                description = "动漫风 SD1.5，NPU 加速，体积小、出图快",
                fileUri = "xororz/sd-qnn/resolve/main/AnythingV5_qnn2.28_" + tier + ".zip",
                backendType = "sd15npu",
                generationSize = 512,
                approximateSize = "2GB",
            ),
            ImageModel(
                id = "qteamix",
                name = "QteaMix",
                description = "写实/半写实 SD1.5，NPU 加速",
                fileUri = "xororz/sd-qnn/resolve/main/QteaMix_qnn2.28_" + tier + ".zip",
                backendType = "sd15npu",
                generationSize = 512,
                approximateSize = "2GB",
            ),
            // ── SD1.5 CPU（MNN）：没有档位，任何设备可跑 ──
            ImageModel(
                id = "anythingv5cpu",
                name = "AnythingV5 (CPU)",
                description = "同一模型的 CPU 版：慢一些，但不挑芯片",
                fileUri = "xororz/sd-mnn/resolve/main/AnythingV5.zip",
                backendType = "sd15cpu",
                generationSize = 512,
                runOnCpu = true,
                approximateSize = "2GB",
            ),
            // ── SDXL（QD）：地址里是固定的 8gen3，不是档位 ──
            ImageModel(
                id = "cyber_realistic_v10",
                name = "Cyber Realistic v10",
                description = "写实风 SDXL，需 8 Gen 3 及以上的 NPU",
                fileUri = "xororz/sdxl-qnn/resolve/main/cyber_realistic_v10_qnn2.28_8gen3.zip",
                backendType = "sdxl",
                generationSize = 1024,
                isSdxl = true,
                approximateSize = "7GB",
            ),
        )
    }

    /**
     * DiT 包的完成标记文件名。
     *
     * 名字与上游一致（引擎/安装脚本都认它）；空串 = 这条不是 DiT。
     */
    fun markerFileName(ditKind: String): String = when (ditKind) {
        "zimage" -> "ZIMAGE"
        "klein" -> "KLEIN"
        "qwen21" -> "QWEN_IMAGE_2_1"
        "krea2turbo" -> "KREA_2_TURBO"
        else -> ""
    }

    /**
     * 是否已安装（文档 §8.4）。
     *
     * ## 判据分两种，不是「一律看标记文件」
     *
     * - **多文件包（DiT）**：标记文件必须存在，且 `packageFiles` 里每个本地文件都非空。
     *   少一个文件时引擎会在加载阶段报一个很难懂的错误，所以这里就要拦住。
     * - **单 zip 包（SD1.5/SDXL）**：上游的判据是「目录存在且非空」—— 解压成功后
     *   目录里必然有文件，而解压**失败**时上游会把目录删掉。所以没有标记文件也不会误判。
     *
     * 我原先在文档里把「标记文件」写成唯一判据，那是把 DiT 的规则套到了所有包上；
     * 这里按上游的真实行为分开处理，并在文档里同步了这条更正。
     */
    fun isInstalled(modelsRoot: File, model: ImageModel): Boolean {
        val dir = File(modelsRoot, model.id)
        if (!dir.isDirectory) return false

        if (model.isMultiFile) {
            val marker = markerFileName(model.ditKind)
            if (marker.isEmpty() || !File(dir, marker).isFile) return false
            return model.packageFiles.all { entry ->
                val local = File(dir, model.localNameOf(entry))
                local.isFile && local.length() > 0L
            }
        }

        // 单 zip：目录非空即视为装好（与上游同一口径）。
        val children = dir.listFiles()
        return !children.isNullOrEmpty()
    }
}
