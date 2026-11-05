package dev.praxis.core.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SpillPolicy] 的判定与切分。
 *
 * ## 为什么这条链必须钉住
 *
 * 旧实现超过上限**静默丢弃**剩余部分。后果不是「省了上下文」，
 * 而是模型基于半截输出做判断，而用户和模型都不知道少了什么。
 *
 * 典型场景：`apt install` 失败，真正的错误在最后几行，
 * 而前 16000 字符全是包列表 —— 丢掉尾部等于丢掉了唯一的线索。
 */
class SpillPolicyTest {

    @Test
    fun `未超阈值不溢出`() {
        assertFalse(SpillPolicy.shouldSpill("短输出"))
    }

    @Test
    fun `超阈值溢出`() {
        val long = "x".repeat(SpillPolicy.SPILL_THRESHOLD_CHARS)
        assertTrue(SpillPolicy.shouldSpill(long))
    }

    /**
     * 恰好等于阈值也要溢出。
     *
     * 阈值本身就是「已经太大了」的信号；差一个字符就整段塞进上下文
     * 没有意义，而走溢出路径的代价很小。
     */
    @Test
    fun `等于阈值也溢出`() {
        val exact = "x".repeat(SpillPolicy.SPILL_THRESHOLD_CHARS)
        assertTrue(SpillPolicy.shouldSpill(exact))
        assertFalse(SpillPolicy.shouldSpill("x".repeat(SpillPolicy.SPILL_THRESHOLD_CHARS - 1)))
    }

    /** 短文本整段返回，不做无意义切割。 */
    @Test
    fun `短文本不加省略标记`() {
        val text = "hello world"
        val (head, tail) = SpillPolicy.makePreview(text)
        assertEquals(text, head)
        assertEquals("", tail)
    }

    /**
     * 长文本必须**同时**保留首尾，并在中间写明省略了多少。
     *
     * 只留开头会让模型看不到失败原因；只留结尾会丢失「在干什么」的上下文。
     */
    @Test
    fun `长文本保留首尾并标注省略`() {
        val headPart = "HEAD-" + "a".repeat(100)
        val middle = "m".repeat(50_000)
        val tailPart = "b".repeat(100) + "-TAIL"
        val text = headPart + middle + tailPart
        val (head, tail) = SpillPolicy.makePreview(text, headChars = 200, tailChars = 200)
        assertTrue("开头片段丢失", head.contains("HEAD-"))
        assertTrue("中间省略未标注", head.contains("省略"))
        assertTrue("结尾片段丢失", tail.contains("-TAIL"))
    }

    @Test
    fun `标注里写明省略字符数`() {
        val text = "a".repeat(1000)
        val (head, _) = SpillPolicy.makePreview(text, headChars = 100, tailChars = 100)
        // 1000 - 100 - 100 = 800
        assertTrue("省略数不对：$head", head.contains("省略 800 字符"))
    }

    /** 结尾长度为 0 时只返回开头（部分调用方只要头）。 */
    @Test
    fun `结尾长度为零时只给开头`() {
        val text = "a".repeat(1000)
        val (head, tail) = SpillPolicy.makePreview(text, headChars = 100, tailChars = 0)
        assertEquals("", tail)
        assertTrue(head.contains("省略 900 字符"))
    }

    /**
     * 模型可见文本必须包含**定位符、工具名、规模**三样。
     *
     * 只说「已保存」是没用的 —— 模型不知道该调什么、也不知道定位符是什么。
     */
    @Test
    fun `模型文本含定位符与取回方式`() {
        val spilled = SpilledText(
            locator = "spill-1a2b3c4d5e6f7a8b",
            totalBytes = 12345,
            totalLines = 300,
            previewHead = "开头",
            previewTail = "结尾",
        )
        val text = spilled.toModelText()
        assertTrue("缺定位符", text.contains("spill-1a2b3c4d5e6f7a8b"))
        assertTrue("缺取回工具名", text.contains("read_spill"))
        assertTrue("缺规模信息", text.contains("300"))
        assertTrue("缺开头片段", text.contains("开头"))
        assertTrue("缺结尾片段", text.contains("结尾"))
    }
}
