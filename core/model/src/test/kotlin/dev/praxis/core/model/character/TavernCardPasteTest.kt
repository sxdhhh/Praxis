package dev.praxis.core.model.character

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「把整张卡粘进系统提示词框」这条路径必须能用（2026-11-07）。
 *
 * ## 它守的是什么
 *
 * 真实故障：用户把一整张 Operit 卡的 JSON 粘进了设置页的「系统提示词」。
 * 那个框存纯文本，于是模型收到的是一坨 JSON —— JSON 是数据不是指令，
 * 人设一句都没生效，而且没有任何报错可查。用户只说「不奏效」。
 *
 * [TavernCardImporter.promptFromCardJson] 就是那道闸：像卡就按卡解释，
 * 不像就返回 null 让调用方用原文。
 */
class TavernCardPasteTest {

    /** Operit 卡：人设写在 extensions.operit.character_card.characterSetting 里。 */
    private val operitCard = """
        {
          "spec": "chara_card_v2",
          "spec_version": "2.0",
          "data": {
            "name": "测试鲸鱼",
            "description": "描述",
            "personality": "",
            "first_mes": "",
            "mes_example": "",
            "scenario": "",
            "creator_notes": "",
            "system_prompt": "",
            "post_history_instructions": "",
            "alternate_greetings": [],
            "tags": [],
            "creator": "",
            "character_version": "",
            "extensions": {
              "operit": {
                "schema": "operit_character_card_v1",
                "character_card": {
                  "name": "测试鲸鱼",
                  "characterSetting": "【身份】人设甲：白米饭算力补给",
                  "otherContentChat": "【背景故事】人设乙：鲸鳍拖鞋",
                  "advancedCustomPrompt": "【核心定位】人设丙：待机省电"
                }
              }
            }
          }
        }
    """.trimIndent()

    @Test
    fun `Operit 卡的载荷会被组装成提示词`() {
        val prompt = TavernCardImporter.promptFromCardJson(operitCard)
        assertNotNull("整张 Operit 卡必须能被识别", prompt)
        assertTrue("characterSetting 要进来", prompt!!.contains("人设甲"))
        assertTrue("otherContentChat 要进来", prompt.contains("人设乙"))
        assertTrue("advancedCustomPrompt 要进来", prompt.contains("人设丙"))
    }

    @Test
    fun `V1 老格式的 system_prompt 会被折进提示词`() {
        // V1 卡没有 extensions.operit，走 fromPlainTavern 降级映射：
        // system_prompt -> otherContentChat 的【系统提示词】小节。
        val v1 = """{"name":"旧卡","system_prompt":"旧格式人设XYZ"}"""
        val prompt = TavernCardImporter.promptFromCardJson(v1)
        assertNotNull("V1 卡也要认得", prompt)
        assertTrue("system_prompt 不能丢：$prompt", prompt!!.contains("旧格式人设XYZ"))
    }

    @Test
    fun `普通规则文本不当成卡`() {
        assertNull(
            "「永远用中文」这类规则不能被当成卡",
            TavernCardImporter.promptFromCardJson("永远用中文回答，不要编造事实"),
        )
    }

    @Test
    fun `没有名字的 JSON 不当成卡`() {
        assertNull(
            "缺名字的 JSON 解析不出卡，应返回 null 而不是抛异常",
            TavernCardImporter.promptFromCardJson("""{"foo":1,"bar":"baz"}"""),
        )
    }

    @Test
    fun `空串与纯空白安全`() {
        assertNull(TavernCardImporter.promptFromCardJson(""))
        assertNull(TavernCardImporter.promptFromCardJson("   "))
    }
}
