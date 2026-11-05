package dev.praxis.core.plugin

/**
 * 一个 Skill（技能包）。
 *
 * ## 为什么 Skill 是 Praxis 的第一个扩展层
 *
 * 设计文档里三层的优先级是 Skill(P1) > MCP(P2) > ToolPkg(P3)。
 * Skill 是纯 Markdown：启用后正文拼进系统提示词，**没有任何运行时执行**。
 * 这一层出问题的后果是「提示词写得不好」，而不是「代码在你设备上乱跑」。
 *
 * ToolPkg 不做：它需要 QuickJS + 19 类钩子 + Compose DSL，
 * Operit 那边 PackageManager 就 167KB。一次搬不完，且风险量级完全不同。
 */
data class Skill(
    /** 稳定标识。取 frontmatter 的 name；缺失时用目录名。 */
    val name: String,
    /** 一句话说明。模型靠它判断「这个技能现在用不用得上」。 */
    val description: String,
    /** Markdown 正文（已剥掉 frontmatter）。 */
    val body: String,
    /** 来源目录（排查「这个技能从哪来的」用）。 */
    val folder: String,

    /**
     * 是否禁止**模型自己**调用（frontmatter 的 `disable-model-invocation`）。
     *
     * true 时该技能不进模型可见的技能清单 —— 只有用户显式 `/技能名` 才会加载。
     * 适用于「有副作用、不能让 AI 自作主张执行」的技能。
     */
    val disableModelInvocation: Boolean = false,

    /**
     * 是否允许**用户**显式调用（frontmatter 的 `user-invocable`，默认 true）。
     *
     * false 时 `/技能名` 不生效，且不进候选列表。
     * 适用于「只想作为背景知识常驻、不希望被单独触发」的技能。
     */
    val userInvocable: Boolean = true,
) {

    /**
     * 该技能能否被模型看见（进入自动注入的技能清单）。
     *
     * ## 两个开关的组合语义（照 Harness 的四象限）
     *
     * | disable-model-invocation | user-invocable | 模型可用 | 用户可用 |
     * |---|---|---|---|
     * | false | true | ✅ | ✅ |
     * | true | true | ❌ | ✅ |
     * | false | false | ✅ | ❌ |
     * | true | false | ❌ | ❌（等于停用） |
     */
    val modelInvocable: Boolean get() = !disableModelInvocation
    /**
     * 拼进系统提示词的片段。
     *
     * 用二级标题 + description：模型需要先看到「这是什么时候用的」，
     * 才会去读正文。只给正文会让它把技能当成普通背景知识。
     */
    fun toPromptSection(): String = buildString {
        append("## ").append(name).append('\n')
        if (description.isNotEmpty()) {
            append(description).append('\n')
        }
        append('\n')
        append(body.trim())
    }
}

/** Skill 扫描结果。 */
data class SkillScanResult(
    val skills: List<Skill> = emptyList(),
    /** 被跳过的文件与原因。不静默丢弃。 */
    val skipped: List<SkillSkip> = emptyList(),
)

data class SkillSkip(
    val file: String,
    val reason: String,
)
