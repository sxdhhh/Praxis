package dev.praxis.core.plugin

/**
 * 技能文件读取端口。
 *
 * ## 为什么不让 core:plugin 直接读文件
 *
 * 纯 JVM 模块用 `java.io.File` 读得到「构建机上的文件」，但读不到
 * 「Android 应用私有目录 / SAF 授权的目录」。把它抽成端口后：
 *  - `core:plugin` 保持纯净，可以纯 JVM 单测（不用 Robolectric）；
 *  - `:app` 可以提供基于 `File` 的实现，也可以以后换成 SAF / 资产包。
 *
 * 与 `MemoryStore` / `WorkflowToolPort` 同一个口径：端口在里、实现在外。
 */
interface SkillSource {

    /** 列出所有待解析的技能文件（相对路径 + 文件名 + 内容）。 */
    suspend fun listSkillFiles(): List<SkillFile>
}

data class SkillFile(
    /** 目录名（用于 [Skill.folder]）。 */
    val folder: String,
    /** 文件名，如 `SKILL(先搜开源).md`。 */
    val name: String,
    val content: String,
)

/**
 * 技能注册表。
 *
 * ## 为什么按目录名去重（而不是按 name）
 *
 * 用户完全可能从两处拷来同一个技能（`skill/SKILL.md` 与 `skill-copy/SKILL.md`）。
 * 按 name 去重会「随机」保留一份，而按目录名去重是确定的，且用户能看出
 * 「这两个目录我留哪个」。
 */
class SkillRegistry(
    private val source: SkillSource,
) {
    private var cache: SkillScanResult = SkillScanResult()

    /** 已加载的技能（未 `reload` 前为空）。 */
    fun all(): List<Skill> = cache.skills

    /** 上次扫描时被跳过的文件。 */
    fun skipped(): List<SkillSkip> = cache.skipped

    /** 重新扫描。重复目录只保留第一份。 */
    suspend fun reload(): SkillScanResult {
        val files = runCatching { source.listSkillFiles() }.getOrDefault(emptyList())

        val skills = ArrayList<Skill>(files.size)
        val skipped = ArrayList<SkillSkip>(0)
        val seenFolders = HashSet<String>()

        for (file in files) {
            val key = file.folder.trim()
            if (key.isNotEmpty() && !seenFolders.add(key)) {
                skipped += SkillSkip(file.name, "与已加载的技能目录重复：$key")
                continue
            }

            if (file.content.isBlank()) {
                skipped += SkillSkip(file.name, "内容为空")
                continue
            }

            val (skill, warning) = SkillParser.parse(
                text = file.content,
                fileName = file.name,
                folder = file.folder,
            )

            skills += skill
            if (warning != null) skipped += SkillSkip(file.name, warning)
        }

        cache = SkillScanResult(skills = skills, skipped = skipped)
        return cache
    }

    /**
     * 把选中的技能拼成一段系统提示词。
     *
     * 空列表返回空串（调用方据此决定「不插 system」），
     * 而不是一个空的段落——空 system 会让模型困惑。
     */
    fun composePrompt(enabledNames: Set<String>): String {
        val selected = cache.skills.filter { it.name in enabledNames }
        if (selected.isEmpty()) return ""
        return selected.joinToString(separator = "\n\n") { it.toPromptSection() }
    }
}
