package dev.praxis.core.plugin

/**
 * SKILL.md 解析器。
 *
 * ## 格式（实测自 Operit 的真实技能包）
 *
 * ```
 * ---
 * name: search-before-build
 * description: 一句话说明什么时候用它
 * ---
 *
 * # 标题
 *
 * 正文……
 * ```
 *
 * frontmatter 只认两行 `name:` / `description:`。不引 YAML 库的理由：
 *  - 技能包是**用户手写/从别人那拷来的**文本，格式越宽松越容易兼容；
 *  - 引入 YAML 解析器等于为两行键值对背一个依赖，还会把 `:` 后带冒号的
 *    description 解析成嵌套结构（实际内容里很常见）。
 *
 * ## 容错策略
 *
 * 缺 frontmatter、缺 name、缺 description 都**不失败**：
 *  - 缺 name → 用文件名（去掉 `SKILL` 前缀与扩展名）；
 *  - 缺 description → 空串，正文照用。
 *
 * 理由：一个写得不太规范的技能包，比一个完全不加载的技能包有用。
 * 但它必须出现在 [SkillScanResult.skipped] 里，让用户能去修。
 */
object SkillParser {

    private const val FRONTMATTER_FENCE = "---"

    /**
     * 解析一份 SKILL.md 文本。
     *
     * @param fileName 用于在缺 name 时兜底，不要带路径
     * @param folder 记录来源目录
     * @return 解析结果与可选的告警（告警非空表示「能用但不规范」）
     */
    fun parse(
        text: String,
        fileName: String,
        folder: String,
    ): Pair<Skill, String?> {
        val normalized = text.replace("\r\n", "\n")
        val (meta, body) = splitFrontmatter(normalized)

        val name = meta["name"]?.takeIf { it.isNotBlank() }
        val description = meta["description"]?.trim().orEmpty()

        val warning = when {
            meta.isEmpty() -> "缺少 frontmatter，已用文件名作标识"
            name == null -> "frontmatter 缺少 name，已用文件名作标识"
            description.isEmpty() -> "frontmatter 缺少 description，模型难以判断何时使用"
            else -> null
        }

        val skill = Skill(
            name = name ?: fallbackName(fileName),
            description = description,
            body = body.trim(),
            folder = folder,
        )
        return skill to warning
    }

    /**
     * 切出 frontmatter 与正文。
     *
     * 必须要求**第一行就是 `---`**：否则正文里常见的分隔线（Markdown 水平线）
     * 会被误当成 frontmatter 开始，把整篇正文吃掉。
     */
    private fun splitFrontmatter(text: String): Pair<Map<String, String>, String> {
        val lines = text.split("\n")
        if (lines.isEmpty() || lines[0].trim() != FRONTMATTER_FENCE) {
            return emptyMap<String, String>() to text
        }

        var closing = -1
        for (i in 1 until lines.size) {
            if (lines[i].trim() == FRONTMATTER_FENCE) {
                closing = i
                break
            }
        }
        if (closing < 0) {
            // 只有开头没有结尾：不当作 frontmatter（宁可把 `---` 当正文）。
            return emptyMap<String, String>() to text
        }

        val meta = LinkedHashMap<String, String>()
        for (i in 1 until closing) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            if (key.isNotEmpty()) meta[key] = value
        }

        val body = if (closing + 1 < lines.size) {
            lines.subList(closing + 1, lines.size).joinToString("\n")
        } else {
            ""
        }
        return meta to body
    }

    /**
     * 文件名 → 标识。
     *
     * Operit 的技能包文件名形如 `SKILL(先搜开源_别从零造轮子).md`，
     * 取括号内的内容比整串文件名可读得多。
     */
    private fun fallbackName(fileName: String): String {
        val base = fileName.substringBeforeLast('.')
        val inner = Regex("[（(](.+?)[）)]").find(base)?.groupValues?.getOrNull(1)
        return (inner ?: base).trim().ifEmpty { "unnamed-skill" }
    }
}
