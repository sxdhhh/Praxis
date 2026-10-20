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
}
