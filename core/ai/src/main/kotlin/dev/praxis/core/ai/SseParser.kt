package dev.praxis.core.ai

/**
 * Server-Sent Events 增量解析器。
 *
 * ## 为何不用正则拆行
 *
 * 网络分片会在**任意字节**处切开 —— 一个 chunk 可能是 `dat`，下一个是 `a: {...}\n\n`。
 * 正则按行匹配会在边界处丢数据或拼错行。所以这里用字节级扫描 + 残留缓冲：
 * 逐字符喂入，遇 `\n\n`（或 `\r\n\r\n`）才切出一个完整事件。
 *
 * ## 为何状态放对象属性而不是局部变量
 *
 * 流式解析是**跨多次 feed 的连续过程**。如果每次 feed 都新建缓冲，
 * 半截事件就会被丢弃 —— 这正是「偶尔丢最后一个字符」类 bug 的根源。
 *
 * 本类非线程安全：一个流一个实例，不要跨协程共享。
 */
class SseParser {

    private val pending = StringBuilder()

    /**
     * 喂入一段文本，返回本次可以确定完整的 data 负载列表。
     *
     * 只关心 `data:` 字段（OpenAI 兼容协议）；`event:` / `id:` / `retry:` 一律忽略，
     * 因为 Praxis 的事件类型在 data 负载自身里（`choices[0].finish_reason` 等）。
     */
    fun feed(chunk: String): List<String> {
        pending.append(chunk)
        val out = mutableListOf<String>()

        while (true) {
            val end = indexOfEventBoundary(pending)
            if (end < 0) break

            val raw = pending.substring(0, end)
            pending.delete(0, end + boundaryLength(pending, end))
            extractData(raw)?.let(out::add)
        }
        return out
    }

    /** 流结束时调用：把残留的不完整事件提交出来（有些 provider 不补末尾空行）。 */
    fun finish(): List<String> {
        if (pending.isEmpty()) return emptyList()
        val raw = pending.toString()
        pending.clear()
        val data = extractData(raw)
        return if (data == null) emptyList() else listOf(data)
    }

    private fun extractData(raw: String): String? {
        // 一个事件可能有多行 data:，按 SSE 规范用 \n 拼接。
        val lines = raw.split('\n')
        val dataLines = lines.filter { it.startsWith("data:") }
        if (dataLines.isEmpty()) return null

        val joined = dataLines.joinToString("\n") { line ->
            // 去掉 "data:" 后紧跟的单个空格（规范允许有一个）。
            line.removePrefix("data:").removePrefix(" ")
        }.trim()

        if (joined.isEmpty()) return null
        // OpenAI 的结束标记，不是 JSON。
        if (joined == "[DONE]") return null
        return joined
    }

    private fun indexOfEventBoundary(sb: StringBuilder): Int {
        for (i in 0 until sb.length - 1) {
            if (sb[i] == '\n' && sb[i + 1] == '\n') return i
            if (sb[i] == '\r' && sb[i + 1] == '\n' && i + 3 < sb.length &&
                sb[i + 2] == '\r' && sb[i + 3] == '\n'
            ) {
                return i
            }
        }
        return -1
    }

    private fun boundaryLength(sb: StringBuilder, at: Int): Int =
        if (sb[at] == '\r') 4 else 2
}
