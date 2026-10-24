package dev.praxis.core.plugin

/**
 * 多语言文本。
 *
 * ## 为什么不用 `@Serializable` + 手写 serializer（Operit 的做法）
 *
 * Operit 的 `LocalizedText` 是「字符串或 {lang: string}」的双形态，
 * 靠自定义 serializer 处理。这里保留同一语义，但把解析逻辑放在
 * [LocalizedTextCodec] 而不是注解上 —— 因为清单可来自 hjson
 * （解析后是 `Map<String, Any>`），也可能是程序里构造的默认值，
 * 两条路径用同一个函数最稳，将来换序列化框架也不用改模型。
 */
data class LocalizedText(val values: Map<String, String>) {

    /** 取一个最合适的文本。空表返回空串。 */
    fun resolve(language: String = DEFAULT_LANGUAGE): String {
        if (values.isEmpty()) return ""
        // 优先精确匹配，其次语言前缀（zh-Hans → zh），最后回落 default 或第一条。
        values[language]?.takeIf { it.isNotBlank() }?.let { return it }
        val prefix = language.substringBefore('-')
        values[prefix]?.takeIf { it.isNotBlank() }?.let { return it }
        values[DEFAULT_LANGUAGE]?.takeIf { it.isNotBlank() }?.let { return it }
        return values.values.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    /** 中文优先（Praxis 的主要用户面是中文）。 */
    fun resolveZh(): String = resolve("zh")

    val isBlank: Boolean get() = resolveZh().isBlank() && resolve().isBlank()

    companion object {
        const val DEFAULT_LANGUAGE: String = "default"

        val EMPTY: LocalizedText = LocalizedText(emptyMap())

        fun of(text: String): LocalizedText =
            if (text.isEmpty()) EMPTY else LocalizedText(mapOf(DEFAULT_LANGUAGE to text))

        fun ofZh(text: String): LocalizedText =
            if (text.isEmpty()) EMPTY else LocalizedText(mapOf("zh" to text))
    }
}

/** 把「字符串 / 字符串列表 / 语言映射」三种形态统一成本地化文本。 */
object LocalizedTextCodec {

    /**
     * @param declaration 清单里声明的原始描述（`"x"`、`["x"]` 或 `{"zh":"x"}`）
     * @param declarationPath 出错时报告位置用，例如 `manifest.display_name`
     */
    fun parse(declaration: ManifestValue?, declarationPath: String): LocalizedText =
        when (declaration) {
            null -> LocalizedText.EMPTY
            is ManifestValue.Text -> LocalizedText.ofZh(declaration.value)
            is ManifestValue.Items -> {
                // 元素是 ManifestValue（不是 String），必须先 asText() 再判空：
                // 直接 isNotBlank() 会报 Unresolved reference（ManifestValue 没有该扩展）。
                val first = declaration.values
                    .map { it.asText() }
                    .firstOrNull { it.isNotBlank() }
                if (first == null) LocalizedText.EMPTY else LocalizedText.ofZh(first)
            }
            is ManifestValue.Mapping -> {
                val map = declaration.entries
                    .mapValues { (_, value) -> value.asText() }
                    .filterValues { it.isNotBlank() }
                if (map.isEmpty()) LocalizedText.EMPTY else LocalizedText(map)
            }
            else -> {
                // 不抛：清单的任何单个字段写错都不该让整个包装不上。
                // 但要说清是哪个字段被忽略了（日志里能查到）。
                require(declarationPath.isNotBlank()) { "internal: empty declaration path" }
                LocalizedText.EMPTY
            }
        }
}

/**
 * 清单值的统一中间表示。
 *
 * ## 为什么不用 `JsonElement`
 *
 * 清单可能是 `manifest.json`（真 JSON）也可能是 `manifest.hjson`
 * （允许注释与无引号键）。两者解析后的类型不同（`JsonElement` vs `Map`）。
 * 用一个极小的自有 ADT，两个解析器都产出同一种结构，
 * 校验与转换逻辑就只需要写一遍。
 */
sealed interface ManifestValue {

    data class Text(val value: String) : ManifestValue

    data class Number(val text: String) : ManifestValue

    data class Flag(val value: Boolean) : ManifestValue

    data class Items(val values: List<ManifestValue>) : ManifestValue

    data class Mapping(val entries: Map<String, ManifestValue>) : ManifestValue

    data object Null : ManifestValue

    fun asText(): String = when (this) {
        is Text -> value
        is Number -> text
        is Flag -> value.toString()
        else -> ""
    }

    fun asIntOrNull(): Int? = when (this) {
        is Number -> text.toIntOrNull()
        is Text -> value.trim().toIntOrNull()
        else -> null
    }

    /**
     * 转 Long。
     *
     * 与 [asIntOrNull] 分开而不是「先 Int 再 toLong」：文件大小、
     * 时间戳这类值会超过 `Int.MAX_VALUE`（2.1 GB），先用 Int 解析会静默变成 null。
     */
    fun asLongOrNull(): Long? = when (this) {
        is Number -> text.toLongOrNull()
        is Text -> value.trim().toLongOrNull()
        else -> null
    }

    fun asBooleanOrNull(): Boolean? = when (this) {
        is Flag -> value
        is Number -> when (text.trim()) {
            "1" -> true
            "0" -> false
            else -> null
        }
        is Text -> when (value.trim().lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
        else -> null
    }

    fun asList(): List<ManifestValue> = when (this) {
        is Items -> values
        // 单值也当单元素列表：清单里经常把 `author: "a"` 与
        // `author: ["a","b"]` 混着写（operit 的 StringOrStringListSerializer 就是为此存在）。
        Null -> emptyList()
        else -> listOf(this)
    }

    fun asMapping(): Map<String, ManifestValue> = when (this) {
        is Mapping -> entries
        else -> emptyMap()
    }
}
