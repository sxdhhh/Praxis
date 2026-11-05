package dev.praxis.core.tool.custom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自定义工具定义的校验。
 *
 * ## 为什么这条链是整个 L2 的安全边界
 *
 * 声明式 DSL 的全部安全性都建立在「define 阶段严格校验」上：
 * 原语白名单挡住了任意行为，占位符校验挡住了「拼错但静默做错事」，
 * hasSideEffects 校验挡住了「失败时被误判为可安全重试」。
 *
 * 这些校验错了**不会报任何运行时错误**，只会让危险定义被装载进去。
 */
class CustomToolValidatorTest {

    private fun def(
        name: String = "backup",
        desc: String = "备份项目",
        params: Map<String, CustomToolParam> = mapOf("target" to CustomToolParam()),
        steps: List<CustomToolStep> = listOf(
            CustomToolStep("read_file", mapOf("path" to "{{target}}")),
        ),
        sideEffects: Boolean = false,
    ) = CustomToolDefinition(
        name = name,
        description = desc,
        params = params,
        steps = steps,
        hasSideEffects = sideEffects,
    )

    private fun errors(d: CustomToolDefinition) =
        CustomToolValidator.validate(d).filter { it.isError }

    @Test
    fun `合法定义无错误`() {
        assertTrue(errors(def()).isEmpty());
    }

    @Test
    fun `名称必须小写字母开头`() {
        assertFalse(errors(def(name = "Backup")).isEmpty());
        assertFalse(errors(def(name = "1backup")).isEmpty());
        assertFalse(errors(def(name = "a-b")).isEmpty());
    }

    /** 与内置工具重名会**覆盖内置实现** —— 用户完全预料不到。 */
    @Test
    fun `不能与内置工具重名`() {
        val issues = errors(def(name = "linux_exec"));
        assertTrue("必须拒绝重名", issues.isNotEmpty());
        assertTrue(issues.first().message.contains("覆盖"));
    }

    /** 白名单外的原语一律拒绝 —— 这是「没有 RCE 面」的关键。 */
    @Test
    fun `白名单外原语被拒绝`() {
        val bad = def(
            steps = listOf(CustomToolStep("rm_rf", mapOf("path" to "{{target}}"))),
        );
        assertTrue(errors(bad).isNotEmpty());
    }

    @Test
    fun `所有支持的原语都能通过`() {
        ToolPrimitives.SUPPORTED.forEach { op ->
            val one = def(
                steps = listOf(CustomToolStep(op, mapOf("path" to "{{target}}"))),
                sideEffects = true,
            );
            assertTrue("原语 $op 被认为非法", errors(one).isEmpty());
        }
    }

    /**
     * 引用未声明的占位符必须报错。
     *
     * 拼错的占位符**不会报运行时错误** —— 原语只会收到一个字面量
     * `{{targt}}`，然后安静地做错事。
     */
    @Test
    fun `未声明的占位符被拒绝`() {
        val bad = def(
            steps = listOf(CustomToolStep("read_file", mapOf("path" to "{{targt}}"))),
        );
        assertTrue(errors(bad).isNotEmpty());
    }

    /**
     * 含写操作却声明 hasSideEffects=false 必须报错。
     *
     * 它的后果很具体：失败时框架会把它当作「可安全重试」，
     * 而写操作重试会让副作用翻倍。
     */
    @Test
    fun `写操作必须声明副作用`() {
        val bad = def(
            steps = listOf(CustomToolStep("write_file", mapOf("path" to "{{target}}"))),
            sideEffects = false,
        );
        assertTrue(errors(bad).isNotEmpty());
    }

    @Test
    fun `读操作无需声明副作用`() {
        val ok = def(
            steps = listOf(CustomToolStep("read_file", mapOf("path" to "{{target}}"))),
            sideEffects = false,
        );
        assertTrue(errors(ok).isEmpty());
    }

    @Test
    fun `空步骤被拒绝`() {
        assertTrue(errors(def(steps = emptyList())).isNotEmpty());
    }

    @Test
    fun `步骤数超上限被拒绝`() {
        val many = List(CustomToolValidator.MAX_STEPS + 1) {
            CustomToolStep("read_file", mapOf("path" to "{{target}}"))
        };
        assertTrue(errors(def(steps = many)).isNotEmpty());
    }

    /** 空描述只是告警，不该拒绝装载。 */
    @Test
    fun `空描述仅为告警`() {
        val issues = CustomToolValidator.validate(def(desc = ""));
        assertTrue("不该有错误", issues.none { it.isError });
        assertTrue("应有告警", issues.any { !it.isError });
    }
}
