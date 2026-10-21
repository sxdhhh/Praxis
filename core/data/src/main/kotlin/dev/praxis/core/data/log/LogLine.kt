package dev.praxis.core.data.log

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 一行运行日志的**文本协议**（写入端与解析端的唯一契约）。
 *
 * 为什么要有独立的一个对象：日志文件是「写」和「读」两处代码的共享格式，
 * 一旦两边各写一份正则，改一边忘另一边就会出现「日志页永远空着」这种
 * 最难查的失效（数据在磁盘上、UI 说没有）。把格式收在一处，
 * 再用单测做 round-trip，读写不可能不同步。
 *
 * ## 行格式（照搬 apixiaoyuan `WeLogger` + `LogsScreen.RUN_LOG_REGEX`）
 *
 * ```
 * 2026-10-21 10:36:22.123 E/Praxis ChatViewModel: 上游返回 500
 * └──────┬──────────────┘ │ └──┬─┘ └────┬────┘  └──────┬──────┘
 *      时间戳            级别  logger 调用方 tag      正文
 * ```
 *
 *  - 时间戳 `yyyy-MM-dd HH:mm:ss.SSS`
 *  - 级别 单个字符 `V D I W E A F`（A = assert，F = fatal）
 *  - `/` 之前是 logger 自身 TAG（常量 `Praxis`），之后是调用方 tag
 *  - 正文可以包含换行（异常堆栈）；**续行不带时间戳前缀**，
 *    解析时折叠进上一条，这样一张卡片就是「一条日志 + 它的堆栈」。
 */
object LogLine {

    /** logger 自身 TAG，写在级别之后、调用方 tag 之前。 */
    const val LOGGER_TAG: String = "Praxis"

    /** 日志文件名前缀：`praxis-yyyy-MM-dd.log`。 */
    const val FILE_PREFIX: String = "praxis-"

    /** 时间戳格式，解析端按同一 pattern 校验。 */
    const val TIMESTAMP_PATTERN: String = "yyyy-MM-dd HH:mm:ss.SSS"

    val timestampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern(TIMESTAMP_PATTERN)

    /** 文件名里的日期格式。 */
    val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /**
     * 渲染一行（不含结尾换行；正文里的换行原样保留）。
     *
     * @param callerTag 调用方 tag，可为 null（写成字面 `null`，与上游一致 ——
     *   空字符串会让正则的 `[^:]*` 匹配空段，解析出的 tag 变成空串而不是缺省，
     *   两者在 UI 上表现不同）。
     */
    fun format(
        level: Char,
        callerTag: String?,
        message: String,
        timestamp: LocalDateTime,
    ): String = buildString {
        append(timestampFormatter.format(timestamp))
        append(' ')
        append(level)
        append('/')
        append(LOGGER_TAG)
        append(' ')
        append(callerTag)
        append(": ")
        append(message)
    }
}

/**
 * 解析后的一条日志（对应 UI 里的一张卡片）。
 *
 * [time] / [level] / [tag] 为 null 表示「这一行没有元数据」：
 * 文件开头孤立行、或被手工追加的内容。UI 对 null 只显示正文，不画色块。
 */
data class LogEntry(
    val time: String?,
    val level: Char?,
    val tag: String?,
    val message: String,
)

/**
 * 把日志文件全文切成 [LogEntry] 列表。
 *
 * 规则（与上游 `parseRunLog` 一致，但顺序上更严格）：
 *  1. 命中 [MATCHER] 的行 → 开新卡片；
 *  2. 不命中且**已有卡片** → 视为上一条的续行（堆栈、多行消息）；
 *  3. 不命中且**尚无卡片** → 单独一张无元数据卡片。
 *
 * 第 3 条不能省：日志文件可能被截断成「从堆栈中间开始」，
 * 那些行既没有前一条可挂，也不该被丢掉。
 */
object LogParser {

    /**
     * 分组：1=日期 2=时间(含毫秒) 3=级别 4=logger tag 5=调用方 tag 6=正文。
     *
     * `([^:]*)` 只匹配到第一个冒号，正文里的冒号由 `(.*)` 兜住 ——
     * 消息里出现 `http://` 或 `key: value` 都不会把 tag 吃掉。
     */
    private val MATCHER = Regex(
        """^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2}\.\d{3}) ([VDIWEAF])/(\S+)\s+([^:]*): (.*)$""",
    )
    /**
     * tag 归一。
     *
     * 三种输入都要落到 null，否则 UI 会出现「有一个叫 null 的 tag」：
     * 空串（`^` 之后立刻是冒号）、字面 `null`（写端把 null 拼进字符串的结果）、
     * 纯空白（tag 里有不可见字符）。
     */
    private fun normalizeTag(raw: String): String? =
        raw.trim().takeUnless { it.isEmpty() || it == "null" || it == "NULL" }

    fun parse(text: String): List<LogEntry> {
        val out = ArrayList<LogEntry>()
        // ## 为什么要先 trimEnd
        //
        // Kotlin 的 `lineSequence()` 底层是 `splitToSequence("\r\n","\n","\r")`，
        // 与 Java 的 `split` 不同 —— **它保留末尾空串**。
        // 日志文件每条记录都以 `\n` 结尾，于是序列末尾永远多出一个 `""`，
        // 被下面的续行分支并进最后一条日志，导致最后一张卡片正文多一个换行。
        //
        // 真机表现：uiautomator dump 里最后一条的 text 带 `&#10;`，卡片比别的
        // 高出一行。这不是显示小问题 —— 它会让「日志顺序」看起来像有一条空记录。
        //
        // 注意只 trim 换行，不 trim 空格：正文的前导缩进（堆栈）必须保留。
        for (line in text.trimEnd('\n', '\r').lineSequence()) {
            // 只有「文件还没有任何卡片」时才忽略空行；否则空行是续行的一部分
            // （堆栈里本来就有空行，丢掉会让缩进结构断裂）。
            if (line.isEmpty() && out.isEmpty()) continue

            val m = MATCHER.matchEntire(line)
            if (m != null) {
                val groups = m.groupValues
                out.add(
                    LogEntry(
                        time = groups[2],
                        level = groups[3].firstOrNull(),
                        tag = normalizeTag(groups[5]),
                        message = groups[6],
                    ),
                )
            } else if (out.isNotEmpty()) {
                val prev = out.removeAt(out.lastIndex)
                out.add(
                    prev.copy(
                        message = if (prev.message.isEmpty()) line else prev.message + "\n" + line,
                    ),
                )
            } else {
                out.add(LogEntry(time = null, level = null, tag = null, message = line))
            }
        }
        return out
    }
}
