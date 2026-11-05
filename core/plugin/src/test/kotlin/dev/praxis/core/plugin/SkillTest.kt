package dev.praxis.core.plugin

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val REAL_SKILL = """---
name: search-before-build
description: 先搜开源再动手，找到就用或改，找不到再自己写。
---

# 先搜开源再动手

## 铁律

要写新代码前，先搜一遍有没有现成的。
"""

class SkillParserTest {

    @Test
    fun `parses frontmatter and body`() {
        val (skill, warning) = SkillParser.parse(REAL_SKILL, "SKILL.md", "skill")

        assertEquals("search-before-build", skill.name)
        assertTrue(skill.description.contains("先搜开源"))
        assertTrue(skill.body.startsWith("# 先搜开源再动手"))
        assertEquals("skill", skill.folder)
        assertNull(warning)
    }

    @Test
    fun `body excludes frontmatter`() {
        val (skill, _) = SkillParser.parse(REAL_SKILL, "SKILL.md", "f")

        // 关键：正文不能残留 name:/description:/--- 分隔符。
        assertTrue(skill.body.none { it == '-' } || !skill.body.contains("name:"))
        assertTrue(!skill.body.contains("description:"))
    }

    @Test
    fun `missing frontmatter falls back to file name`() {
        val text = """# 只有正文

没有 frontmatter"""
        val (skill, warning) = SkillParser.parse(text, "SKILL(先搜开源_别从零造轮子).md", "f")

        assertEquals("先搜开源_别从零造轮子", skill.name)
        assertTrue(warning!!.contains("frontmatter"))
        assertTrue(skill.body.contains("只有正文"))
    }

    @Test
    fun `missing description keeps skill usable but warns`() {
        val text = """---
name: only-name
---
正文"""
        val (skill, warning) = SkillParser.parse(text, "SKILL.md", "f")

        assertEquals("only-name", skill.name)
        assertEquals("", skill.description)
        assertTrue(warning!!.contains("description"))
    }

    @Test
    fun `description with colon is not split`() {
        // 实际技能描述里常带冒号，不能被解析成嵌套结构。
        val text = """---
name: x
description: 用法：先搜再写
---
正文"""
        val (skill, _) = SkillParser.parse(text, "SKILL.md", "f")

        assertEquals("用法：先搜再写", skill.description)
    }

    @Test
    fun `horizontal rule in body is not treated as frontmatter`() {
        // 正文里的 --- 不能被当成 frontmatter 开始。
        val text = """# 标题

---

分隔线之后的正文"""
        val (skill, warning) = SkillParser.parse(text, "SKILL.md", "f")

        assertTrue(warning!!.contains("frontmatter"))
        assertTrue(skill.body.contains("分隔线之后的正文"))
    }

    @Test
    fun `unclosed frontmatter is treated as body`() {
        val text = """---
name: x
没有闭合"""
        val (skill, warning) = SkillParser.parse(text, "SKILL.md", "f")

        assertTrue(warning!!.contains("frontmatter"))
        assertTrue(skill.body.contains("没有闭合"))
    }

    @Test
    fun `prompt section contains name and description`() {
        val (skill, _) = SkillParser.parse(REAL_SKILL, "SKILL.md", "f")
        val section = skill.toPromptSection()

        assertTrue(section.startsWith("## search-before-build"))
        assertTrue(section.contains("先搜开源"))
        assertTrue(section.contains("铁律"))
    }
}

class SkillRegistryTest {

    private class FakeSource(private val files: List<SkillFile>) : SkillSource {
        override suspend fun listSkillFiles(): List<SkillFile> = files
    }

    @Test
    fun `reload loads all skills`() = runTest {
        val registry = SkillRegistry(
            FakeSource(
                listOf(
                    SkillFile("a", "SKILL.md", REAL_SKILL),
                    SkillFile("b", "SKILL.md", """---
name: other
description: d
---
body"""),
                ),
            ),
        )

        val result = registry.reload()

        assertEquals(2, result.skills.size)
        assertEquals(2, registry.all().size)
    }

    @Test
    fun `duplicate folder only keeps first`() = runTest {
        val registry = SkillRegistry(
            FakeSource(
                listOf(
                    SkillFile("dup", "SKILL.md", REAL_SKILL),
                    SkillFile("dup", "SKILL-copy.md", REAL_SKILL),
                ),
            ),
        )

        val result = registry.reload()

        assertEquals(1, result.skills.size)
        assertTrue(result.skipped.any { it.reason.contains("重复") })
    }

    @Test
    fun `blank content is skipped with reason`() = runTest {
        val registry = SkillRegistry(FakeSource(listOf(SkillFile("e", "SKILL.md", "   "))))

        val result = registry.reload()

        assertTrue(result.skills.isEmpty())
        assertTrue(result.skipped.any { it.reason.contains("空") })
    }

    @Test
    fun `composePrompt only includes enabled skills`() = runTest {
        val registry = SkillRegistry(
            FakeSource(
                listOf(
                    SkillFile("a", "SKILL.md", REAL_SKILL),
                    SkillFile("b", "SKILL.md", """---
name: other
description: d2
---
body2"""),
                ),
            ),
        )
        registry.reload()

        val prompt = registry.composePrompt(setOf("other"))

        assertTrue(prompt.contains("other"))
        assertTrue(!prompt.contains("search-before-build"))
    }

    @Test
    fun `composePrompt returns empty when nothing enabled`() = runTest {
        val registry = SkillRegistry(FakeSource(listOf(SkillFile("a", "SKILL.md", REAL_SKILL))))
        registry.reload()

        // 空串而不是空段落：调用方据此决定不插 system。
        assertEquals("", registry.composePrompt(emptySet()))
    }
    // ── 两个调用开关（2026-11-06，照 Harness skills frontmatter）──────

    private fun parseWith(frontmatter: String): Skill {
        val text = "---\nname: s1\ndescription: 说明\n" + frontmatter + "---\n\n正文"
        return SkillParser.parse(text, "SKILL.md", "s1").first
    }

    /** 不写开关 = 两边都能用（绝大多数技能的期望）。 */
    @Test
    fun `缺省时模型与用户都可用`() {
        val skill = parseWith("")
        assertTrue(skill.modelInvocable)
        assertTrue(skill.userInvocable)
    }

    /** `disable-model-invocation: true` 只封模型，用户仍可用 `/名字` 调用。 */
    @Test
    fun `禁止模型调用但用户可调用`() {
        val skill = parseWith("disable-model-invocation: true\n")
        assertTrue(!skill.modelInvocable)
        assertTrue(skill.userInvocable)
    }

    /** `user-invocable: false` 只封用户，模型仍能在清单里看到。 */
    @Test
    fun `用户不可调用但模型可用`() {
        val skill = parseWith("user-invocable: false\n")
        assertTrue(skill.modelInvocable)
        assertTrue(!skill.userInvocable)
    }

    /**
     * 驼峰键名必须**不生效**（照 Harness：「会被本地解析器拒绝」）。
     *
     * 若这里生效了，就会出现两套写法并存 —— 而用户从别处拷来的
     * 驼峰写法「看起来也能用」，直到某次升级才发现行为不一致。
     */
    @Test
    fun `驼峰键名不生效`() {
        val skill = parseWith("disableModelInvocation: true\n")
        assertTrue("驼峰写法不该生效", skill.modelInvocable)
    }

    @Test
    fun `yes 与 no 也能识别`() {
        assertTrue(!parseWith("disable-model-invocation: yes\n").modelInvocable)
        assertTrue(!parseWith("user-invocable: no\n").userInvocable)
    }

    /** 无法识别的值回落到默认值，而不是把整份技能拒之门外。 */
    @Test
    fun `无法识别的布尔值回落默认`() {
        val skill = parseWith("disable-model-invocation: 也许\n")
        assertTrue("默认应为可用", skill.modelInvocable)
    }

    /**
     * `disable-model-invocation: true` 的技能**不能**出现在注入给模型的提示词里。
     *
     * 这是整个开关唯一真正要保证的效果：用户明确说了「不要让 AI 自己用」，
     * 那么自动注入就必须排除它。
     */
    @Test
    fun `禁止模型调用的技能不进提示词`() = runTest {
        val text = "---\nname: hidden\ndescription: 说明\ndisable-model-invocation: true\n---\n\n正文"
        val registry = SkillRegistry(FakeSource(listOf(SkillFile("hidden", "SKILL.md", text))))
        registry.reload()
        assertEquals("", registry.composePrompt(setOf("hidden")))
        assertTrue(registry.modelVisible(setOf("hidden")).isEmpty())
    }

    /** `user-invocable: false` 的技能不出现在用户候选列表里。 */
    @Test
    fun `用户不可调用的技能不进候选`() = runTest {
        val text = "---\nname: bg\ndescription: 说明\nuser-invocable: false\n---\n\n正文"
        val registry = SkillRegistry(FakeSource(listOf(SkillFile("bg", "SKILL.md", text))))
        registry.reload()
        assertTrue(registry.userInvocable().isEmpty())
        assertNull(registry.bodyForUserInvocation("bg"))
        // 但模型仍看得到（它只是不能被单独触发）。
        assertTrue(registry.composePrompt(setOf("bg")).contains("bg"))
    }

    /** 正常技能两边都可用。 */
    @Test
    fun `普通技能两边都可用`() = runTest {
        val registry = SkillRegistry(FakeSource(listOf(SkillFile("a", "SKILL.md", REAL_SKILL))))
        registry.reload()
        assertTrue(registry.composePrompt(setOf("search-before-build")).isNotEmpty())
        assertTrue(registry.userInvocable().isNotEmpty())
        assertTrue(registry.bodyForUserInvocation("search-before-build") != null)
    }
}
