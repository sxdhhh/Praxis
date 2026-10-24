package dev.praxis.core.plugin

/**
 * 插件清单（工具包清单）。
 *
 * ## 与 Operit `ToolPkgManifest` 的字段对照
 *
 * 逐字段对齐，**包括拼写**（`toolpkg_id`、`api_version`、`enabled_by_default`）：
 * 这些是写进第三方文件里的键，改名等于让所有现成包装不上。
 *
 * | 本字段 | Operit | 说明 |
 * |---|---|---|
 * | [schemaVersion] | `schema_version` | 清单自身格式版本，当前恒 1 |
 * | [id] | `toolpkg_id` | 包 id，也是市场里唯一键 |
 * | [version] | `version` | 包版本（`1.0.0`） |
 * | [apiVersion] | `api_version` | 依赖的宿主 API 版本 |
 * | [requires] | `requires` | 依赖的其它包 + 版本约束 |
 * | [main] | `main` | 主入口脚本（注册工具与 UI） |
 * | [displayName] | `display_name` | 显示名（支持多语言） |
 * | [description] | `description` | 简介（支持多语言） |
 * | [logo] | `logo` | 图标资源键或路径 |
 * | [authors] | `author` | 作者（单值或列表） |
 * | [enabledByDefault] | `enabled_by_default` | 装好即启用 |
 * | [subpackages] | `subpackages` | 子包（可独立启停的一组工具） |
 * | [resources] | `resources` | 资源清单（图标/模板等） |
 * | [wasmModules] | `wasm_modules` | WASM 模块（本版只解析不执行） |
 *
 * ## 为什么 `wasmModules` 只解析不执行
 *
 * Operit 里 WASM 用于「非 JS 语言写的插件逻辑」（AssemblyScript）。
 * 执行它需要 WASM 运行时（`wasmtime` 或 JNI 版 `wasm3`），
 * 那是又一条 native 依赖链。第一阶段先保证**装得上、看得见、不崩**，
 * 执行留到有真实包依赖它时再做 —— 否则为了一个没人用的字段引一整套 native。
 */
data class PluginManifest(
    val schemaVersion: Int = 1,
    val id: String,
    val version: String = "",
    val apiVersion: String = SUPPORTED_API_VERSION,
    val requires: List<PluginRequirement> = emptyList(),
    val main: String = "",
    val displayName: LocalizedText = LocalizedText.EMPTY,
    val description: LocalizedText = LocalizedText.EMPTY,
    val logo: String? = null,
    val authors: List<String> = emptyList(),
    val enabledByDefault: Boolean = true,
    val subpackages: List<PluginSubpackage> = emptyList(),
    val resources: List<PluginResource> = emptyList(),
    val wasmModules: List<PluginWasmModule> = emptyList(),
) {
    /** 界面上给用户看的名字。显示名缺失时回落 id（而不是「未命名」）。 */
    val title: String get() = displayName.resolveZh().ifBlank { id }

    /** 是否存在多个子包。单子包时 UI 可以不显示子包层级。 */
    val hasMultipleSubpackages: Boolean get() = subpackages.size > 1

    companion object {
        /**
         * 本宿主编译期支持的 API 版本。
         *
         * ## 为什么直接给 1.0.1 而不是「跟着 Operit 版本算」
         *
         * Operit 的 `ToolPkgApiCompatibility` 会按**它自己的版本号**
         * 决定支持 1.0.0 还是 1.0.1（1.0.1 从 Operit 1.12.1+4 起）。
         * 那套逻辑对 Praxis 没有意义 —— 我们没有「Operit 版本」这个变量。
         *
         * Praxis 直接实现 1.0.1 的**能力**（它的差异只有 Chat.call 的
         * 更严格校验），所以声明的支持集合就是 `[1.0.0, 1.0.1]`。
         * 这样两类现成包都能装。
         */
        const val SUPPORTED_API_VERSION: String = "1.0.1"

        /** 兼容清单里声明的最低版本。 */
        val SUPPORTED_API_VERSIONS: List<String> = listOf("1.0.0", "1.0.1")
    }
}

/**
 * 包依赖声明。
 *
 * [minVersion]/[maxVersion] 是 inclusive 区间；缺省表示不约束。
 * 依赖同时决定**加载顺序**：被依赖的先加载。
 */
data class PluginRequirement(
    val id: String,
    val description: String = "",
    val minVersion: String? = null,
    val maxVersion: String? = null,
) {
    /** 供 UI 显示的约束文本。两个都没写时返回 null（不显示）。 */
    val constraintText: String?
        get() = when {
            minVersion != null && maxVersion != null -> "$minVersion ~ $maxVersion"
            minVersion != null -> "≥ $minVersion"
            maxVersion != null -> "≤ $maxVersion"
            else -> null
        }

    /**
     * 校验目标版本是否满足约束。
     *
     * @return null 表示通过；否则是可读的失败原因
     */
    fun validate(targetVersion: String): String? {
        if (minVersion == null && maxVersion == null) return null
        val normalized = targetVersion.trim()
        if (normalized.isEmpty()) {
            return "依赖的包 '$id' 没有声明版本"
        }
        val target = SemVer.parseOrNull(normalized)
            ?: return "依赖的包 '$id' 版本号 '$normalized' 不是 major.minor.patch 格式"
        val min = minVersion?.let {
            SemVer.parseOrNull(it)
                ?: return "本包对 '$id' 的 min_version '$it' 不是合法版本号"
        }
        val max = maxVersion?.let {
            SemVer.parseOrNull(it)
                ?: return "本包对 '$id' 的 max_version '$it' 不是合法版本号"
        }
        return when {
            min != null && target < min -> "依赖的包 '$id' 版本 $normalized 低于最低要求 $minVersion"
            max != null && target > max -> "依赖的包 '$id' 版本 $normalized 高于最高要求 $maxVersion"
            else -> null
        }
    }
}

/** 子包：一个工具包内可独立启停的一组工具。 */
data class PluginSubpackage(
    val id: String,
    /** 相对包根的入口脚本路径。 */
    val entry: String,
    val displayName: LocalizedText = LocalizedText.EMPTY,
    val description: LocalizedText = LocalizedText.EMPTY,
    val enabledByDefault: Boolean = true,
)

/** 资源声明（图标、模板、内嵌脚本等）。 */
data class PluginResource(
    val key: String,
    val path: String,
    val mime: String = "",
)

/** WASM 模块声明（本版只解析，不执行，见 [PluginManifest] 的 KDoc）。 */
data class PluginWasmModule(
    val id: String,
    val path: String,
    val exports: List<String> = emptyList(),
    val sourceLanguage: String = "assemblyscript",
    val abi: String = "assemblyscript",
)

/**
 * 语义化版本。
 *
 * 只支持 `major.minor.patch`：清单里就是这么写的（operit 的
 * `ToolPkgPackageVersion` 同样是三段强制）。多支持预发布标签会让
 * 比较规则变得需要解释（`1.0.0-alpha` 与 `1.0.0` 谁新？），
 * 而对「插件依赖」这个场景没有价值。
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int =
        compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)$")

        fun parse(value: String): SemVer =
            parseOrNull(value) ?: throw IllegalArgumentException(
                "版本号必须是 major.minor.patch：'$value'",
            )

        fun parseOrNull(value: String): SemVer? {
            val match = PATTERN.matchEntire(value.trim()) ?: return null
            return SemVer(
                major = match.groupValues[1].toIntOrNull() ?: return null,
                minor = match.groupValues[2].toIntOrNull() ?: return null,
                patch = match.groupValues[3].toIntOrNull() ?: return null,
            )
        }
    }
}

/**
 * API 版本兼容判定。
 *
 * ## 判据是「清单声明的版本在我们的支持集合里」
 *
 * 不是「小于等于最大值」，而是**精确在集合内**。
 * 理由：API 版本不是单调递增的能力集合 —— 1.0.1 相对 1.0.0
 * 改的是 `Chat.call` 的**校验更严**（同一个错误输入从「能跑」变成「报错」）。
 * 所以「不知道的更高版本」不能假定兼容，宁可拒绝并说清支持哪些。
 */
object PluginApiCompatibility {

    /**
     * @return null 表示通过；否则是给用户看的拒绝原因
     */
    fun check(declared: String): String? {
        val normalized = declared.trim().ifEmpty { "1.0.0" }
        if (normalized in PluginManifest.SUPPORTED_API_VERSIONS) return null
        return "本包声明 api_version=$normalized，Praxis 支持 ${
            PluginManifest.SUPPORTED_API_VERSIONS.joinToString(", ")
        }。请升级插件或联系作者。"
    }
}