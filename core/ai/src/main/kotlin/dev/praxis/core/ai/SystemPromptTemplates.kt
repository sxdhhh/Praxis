package dev.praxis.core.ai

/**
 * 系统提示词的骨架模板（照搬 Operit 的占位符做法）。
 *
 * ## 为什么改成模板
 *
 * Operit 的 SystemPromptConfig.SYSTEM_PROMPT_TEMPLATE 只是一串占位符，
 * `getSystemPrompt` 逐段 `replace` 之后拼成**一条** system。
 *
 * 好处有两个，都是 Praxis 之前缺的：
 *
 *  1. **顺序只在一处定义**。之前五层顺序靠 `buildLayers` 里的 ①..⑤ 注释维系，
 *     改拼接顺序编译器不会报错；现在顺序在模板字符串里，看得见。
 *  2. **模板可整体替换**。用户想换掉整段脚手架（只留人设）时，不必改代码。
 *
 * ## 占位符必须全清
 *
 * [fill] 会把未提供的占位符替换成空串。漏一个就会把
 * `SKILLS_SECTION` 这种字面量发给模型 —— 它不会被当成占位符，
 * 而会被当成「这里应该有内容」，模型可能照着编。
 */
object SystemPromptTemplates {

    /** 用户规则。Praxis 独有的一层（Operit 没有），最高优先级。 */
    const val USER_RULES: String = "USER_RULES_SECTION"

    /** 人设 / 角色卡。对应 Operit 的 BEGIN_SELF_INTRODUCTION_SECTION。 */
    const val SELF_INTRODUCTION: String = "BEGIN_SELF_INTRODUCTION_SECTION"

    const val SKILLS: String = "SKILLS_SECTION"

    const val MEMORY: String = "MEMORY_SECTION"

    const val SUMMARY: String = "SUMMARY_SECTION"

    /** 全部占位符。新增一个要同时加进这里，否则 [fill] 不会清它。 */
    val ALL: List<String> = listOf(USER_RULES, SELF_INTRODUCTION, SKILLS, MEMORY, SUMMARY)

    /**
     * 出厂模板。
     *
     * 顺序：用户规则 → 人设 → 技能 → 记忆 → 摘要。
     * 用户规则在最前是 Praxis 的硬约束（见 [SystemPromptBuilder] 的类注释）；
     * 人设紧随其后，与 Operit「角色卡是 system 第一段」的语义对齐。
     */
    val DEFAULT: String = """
        $USER_RULES

        $SELF_INTRODUCTION

        $SKILLS

        $MEMORY

        $SUMMARY
    """.trimIndent()

    /**
     * 用 [sections] 填模板。
     *
     * 收尾把连续 3 个以上换行压成 2 个 —— 空段被清掉后会留下成串空行，
     * 部分模型会把它读成「此处有意留白」。
     */
    fun fill(template: String, sections: Map<String, String>): String {
        var out = template
        for (key in ALL) {
            out = out.replace(key, sections[key].orEmpty())
        }
        return out.replace(Regex("\n{3,}"), "\n\n").trim()
    }
}
