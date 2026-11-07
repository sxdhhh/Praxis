package dev.praxis.core.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子包 METADATA 的解析与工具声明生成。
 *
 * 用例形状取自 docs/SCRIPT_DEV_GUIDE.md §3.1 的示例：
 * 脚本开头一段注释块里放 JSON，tools[] 里每个工具用**参数列表**声明，
 * 而模型侧要的是 JSON Schema —— 转换错了模型就看不到参数，或者看到一堆空参数。
 */
class ScriptMetadataParserTest {

    private val script = """
/*
METADATA
{
  "name": "windows_control",
  "display_name": { "zh": "Windows 控制", "en": "Windows Control" },
  "description": "通过 PC Agent 控制 Windows",
  "env": ["PC_AGENT_TOKEN"],
  "tools": [
    {
      "name": "list_windows",
      "description": "列出窗口",
      "parameters": [
        { "name": "filter", "description": "过滤词", "type": "string", "required": false },
        { "name": "limit", "description": { "zh": "上限" }, "type": "number", "required": true, "default": 20 }
      ]
    },
    { "name": "usage_advice", "description": "用法说明", "advice": true },
    { "description": "缺名字的工具" }
  ]
}
*/
function list_windows(args) { return "ok"; }
"""

    @Test
    fun 能解析出脚本级字段() {
        val metadata = ScriptMetadataParser.parse(script)!!.getOrThrow()

        assertEquals("windows_control", metadata.name)
        assertEquals("Windows 控制", metadata.displayName.resolveZh())
        assertEquals("Windows Control", metadata.displayName.resolve("en"))
        assertEquals(listOf("PC_AGENT_TOKEN"), metadata.env)
    }

    @Test
    fun 缺名字的工具被跳过() {
        val metadata = ScriptMetadataParser.parse(script)!!.getOrThrow()

        // list_windows + usage_advice；第三条没有 name，丢弃。
        assertEquals(2, metadata.tools.size)
        assertEquals("list_windows", metadata.tools[0].name)
        assertTrue("advice 要保留标记", metadata.tools[1].advice)
    }

    @Test
    fun advice_工具不进注册列表() {
        val metadata = ScriptMetadataParser.parse(script)!!.getOrThrow()
        val declarations = ScriptMetadataParser.toToolDeclarations("win", metadata)

        // 文档 §3.1：advice 工具是纯提示，不要求脚本里有同名函数，也不作为工具暴露。
        assertEquals(1, declarations.size)
        assertEquals("win:list_windows", declarations[0].name)
        assertEquals("list_windows", declarations[0].entryFunction)
    }

    @Test
    fun 参数列表转成_JSON_Schema() {
        val metadata = ScriptMetadataParser.parse(script)!!.getOrThrow()
        val schema = ScriptMetadataParser.toToolDeclarations("win", metadata)[0].parametersJson

        assertTrue("要有 object 类型：" + schema, schema.contains("\"type\":\"object\""))
        assertTrue("参数名要在：" + schema, schema.contains("\"filter\""))
        assertTrue("参数描述要在：" + schema, schema.contains("过滤词"))
        assertTrue("必填参数要进 required：" + schema, schema.contains("\"required\":[\"limit\"]"))
        assertTrue("默认值要带上：" + schema, schema.contains("\"default\":20"))
    }

    @Test
    fun 没有_METADATA_块返回_null() {
        // main 脚本通常没有 METADATA；这不是错误。
        assertNull(ScriptMetadataParser.parse("function registerToolPkg() { return true; }"))
    }

    @Test
    fun METADATA_不是合法_JSON_时返回失败() {
        val broken = "/* METADATA { \"name\": \"x\", */"
        val result = ScriptMetadataParser.parse(broken)

        assertNotNull("识别到块但不合法，要给出失败而不是静默", result)
        assertTrue(result!!.isFailure)
    }

    @Test
    fun 缺_name_时返回失败() {
        val result = ScriptMetadataParser.parse("/* METADATA { \"tools\": [] } */")

        assertNotNull(result)
        assertTrue(result!!.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("name"))
    }

    @Test
    fun 没有参数的工具生成空_schema() {
        val text = "/* METADATA { \"name\": \"x\", \"tools\": [ { \"name\": \"ping\" } ] } */"
        val metadata = ScriptMetadataParser.parse(text)!!.getOrThrow()
        val schema = ScriptMetadataParser.toToolDeclarations("x", metadata)[0].parametersJson

        assertTrue(schema.contains("\"properties\":{}"))
        // required 空也要存在：OpenAI 兼容的校验器会检查它的类型。
        assertTrue(schema.contains("\"required\":[]"))
    }
}
