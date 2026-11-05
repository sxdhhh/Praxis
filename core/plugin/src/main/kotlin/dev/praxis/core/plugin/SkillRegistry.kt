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
        val selected = modelVisible(enabledNames)
        if (selected.isEmpty()) return ""
        return selected.joinToString(separator = "\n\n") { it.toPromptSection() }
    }

    /**
     * 模型可见的技能（已启用 + 未被 `disable-model-invocation` 禁止）。
     *
     * ## 为什么过滤放在注册表而不是调用处
     *
     * 调用点不止一个（自动注入、将来的技能工具、预览）。放在调用处意味着
     * 新增一个调用点就要记得再写一遍过滤 —— 漏掉的那个会把
     * 「用户明确禁止模型使用」的技能塞给模型，而这**不会报任何错**。
     *
     * 收敛到这里后，凡是要给模型看技能的地方都必须经过本方法。
     */
    fun modelVisible(enabledNames: Set<String>): List<Skill> =
        cache.skills.filter { it.name in enabledNames && it.modelInvocable }

    /**
     * 用户可显式调用的技能（`user-invocable` 未被关闭）。
     *
     * `/技能名` 与命令候选列表都用它，保证「列出来的都能调、能调的都在列」。
     */
    fun userInvocable(): List<Skill> = cache.skills.filter { it.userInvocable }

    /**
     * 按名字取一份技能的正文（`/技能名` 用）。
     *
     * ## 为什么每次从缓存重读而不是保存副本
     *
     * 缓存里的 `body` 是 `reload()` 时读进来的。用户改完正文点「重载」
     * 后，下一次调用就能拿到新内容 —— 不需要额外的失效机制。
     *
     * 返回 null 表示「不存在」或「不允许用户调用」，两种情况都不该注入。
     */
    fun bodyForUserInvocation(name: String): Skill? =
        cache.skills.firstOrNull { it.name == name && it.userInvocable }
}
