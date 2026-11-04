package dev.praxis.feature.terminal

// ## 为什么用 JUnit4 而不是 kotlin.test（2026-11-05）
//
// 一开始写的是 `kotlin.test.*`，CI 报 `Unresolved reference 'test'` ——
// `feature:terminal` 的 test 源集没有声明 kotlin-test 依赖。
// 同模块已有的 `TerminalViewModelTest` 用的是 JUnit4，这里保持一致，
// 不为一个测试文件去加一条新依赖。
//
// 注意 JUnit 的 `assertEquals` 参数顺序是 (message, expected, actual)，
// 与 kotlin.test 的 (expected, actual, message) **相反**。
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Utf8StreamDecoder] 的行为契约。
 *
 * ## 为什么值得单独测（2026-11-05）
 *
 * 真机反馈「终端有时会出现乱码字符 / 只是部分乱码」。
 * 根因是每个字节块被独立解码 —— 跨块的 UTF-8 字符会变成 `�`。
 * 这个类把它改成增量解码，而「跨块」这件事**在单测里才能稳定复现**：
 * 真机上取决于内核怎么切块，不可控。
 */
class Utf8StreamDecoderTest {

    @Test
    fun asciiPassesThrough() {
        val d = Utf8StreamDecoder()
        assertEquals("hello", d.decode("hello".toByteArray()))
    }

    /**
     * 核心用例：一个 3 字节的中文字符被切成两块。
     *
     * 「你」= E4 BD A0。第一块只给前两字节，第二块给第三字节。
     * 旧实现会得到 "??"（两个替换字符）；正确实现要得到 "你"。
     */
    @Test
    fun chineseCharSplitAcrossTwoChunksIsRestored() {
        val d = Utf8StreamDecoder()
        val bytes = "你".toByteArray(Charsets.UTF_8)
        assertEquals("前提：「你」是 3 字节", 3, bytes.size)

        val first = d.decode(bytes.copyOfRange(0, 2))
        val second = d.decode(bytes.copyOfRange(2, 3))

        // 第一块全是「半个字符」：按契约应返回空串，而不是替换字符。
        assertEquals("", first)
        assertEquals("你", second)
        assertTrue("不该出现替换字符", "\uFFFD" !in (first + second))
    }

    /** 前一个字符的后半 + 后一个字符，混在同一块里。 */
    @Test
    fun tailOfOneCharAndFullNextCharInSameChunk() {
        val d = Utf8StreamDecoder()
        val a = "你".toByteArray(Charsets.UTF_8)
        val b = "好".toByteArray(Charsets.UTF_8)

        d.decode(a.copyOfRange(0, 2))
        // 第二块 = 「你」的第 3 字节 + 完整的「好」
        val out = d.decode(a.copyOfRange(2, 3) + b)
        assertEquals("你好", out)
    }

    /** 逐字节喂：最极端的分块方式，也必须还原。 */
    @Test
    fun byteByByteFeedingReconstructsTheText() {
        val d = Utf8StreamDecoder()
        val text = "终端乱码测试 🚀"
        val sb = StringBuilder()
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            sb.append(d.decode(byteArrayOf(byte)))
        }
        assertEquals(text, sb.toString())
    }

    /** 空块返回空串（调用方据此跳过 ANSI 扫描）。 */
    @Test
    fun emptyChunkYieldsEmptyString() {
        val d = Utf8StreamDecoder()
        assertEquals("", d.decode(ByteArray(0)))
    }

    /**
     * 非法字节不能抛异常。
     *
     * 真终端里 `cat` 一个二进制文件是常态。抛异常会断掉整条输出流 ——
     * 那是把「显示一个替换字符」升级成「终端没反应」。
     */
    @Test
    fun malformedBytesAreReplacedNotThrown() {
        val d = Utf8StreamDecoder()
        // 0xFF 在 UTF-8 里永远非法
        val out = d.decode(byteArrayOf(0x41, 0xFF.toByte(), 0x42))
        assertTrue("合法前缀要保留：$out", out.startsWith("A"))
        assertTrue("合法后缀要保留：$out", out.endsWith("B"))
    }

    /**
     * 两个解码器互不影响（各自记住自己的半个字符）。
     *
     * ## 这条测试原本写错了（2026-11-05，CI #154 抓到）
     *
     * 我最初断言「b 只收到第 3 字节会得到替换字符」。那是对旧实现的
     * 猜测 —— 新实现会把不完整序列**存进 leftover**，所以返回的是**空串**，
     * 不是替换字符。
     *
     * 正确的判据是：b 拿到的字节不够一个字符，所以它什么都不输出；
     * 而 a 已经把前两字节记在自己身上，两者互不污染。
     */
    @Test
    fun instancesDoNotShareState() {
        val a = Utf8StreamDecoder()
        val b = Utf8StreamDecoder()
        val bytes = "你".toByteArray(Charsets.UTF_8)

        // a 收到前两字节：不够一个字符，返回空串，且记在自己身上。
        assertEquals("", a.decode(bytes.copyOfRange(0, 2)))
        // b 从零开始收到第 3 字节（续字节）：同样不够，返回空串。
        assertEquals("", b.decode(bytes.copyOfRange(2, 3)))

        // 关键断言：a 补上第 3 字节能还原「你」—— 说明它没被 b 干扰。
        //
        // 只断言这一条，不再猜 b 后续会输出什么：b 内部存的是**续字节**
        // 0xA0，而续字节作为起始字节是非法的，`safeLength` 会按单字节
        // 放行、解码成替换字符 —— 具体输出取决于实现细节，
        // 写进断言只会让测试变成「实现的快照」而不是「行为的契约」。
        // 这条测试要守的只有一件事：**两个实例的状态互不影响**。
        assertEquals("你", a.decode(bytes.copyOfRange(2, 3)))
    }
}
