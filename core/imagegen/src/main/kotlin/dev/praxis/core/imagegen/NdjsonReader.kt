package dev.praxis.core.imagegen

import java.io.BufferedReader
import java.io.Reader
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * NDJSON 逐行读取与分发（文档 §3.3）。
 *
 * ## 这不是 SSE
 *
 * 端点返回的是**逐行 JSON**：没有 `data:` 前缀，也没有空行分隔的事件块。
 * 拿 SSE 解析器去读它，表现是「永远拿不到结果」（文档 §12 第一条坑）。
 * 这里也**不做一次性 `readBytes()`** —— 那会把整个生成过程的进度吃掉（同 §12）。
 *
 * ## 为什么写成纯函数
 *
 * [parseLine] / [parseLines] 只吃字符串、吐事件，不碰网络也不碰 Android，
 * 于是「半行截断」「超大 base64」「未知 type」这些线上难复现的情况都能在 JVM 单测里穷举。
 * 真正碰 IO 的只有 [read] 里的那一句 `BufferedReader.readLine()`。
 *
 * ## 容错：产不出事件就返回 null，永不抛
 *
 * 三种「不完整」输入都不该让整条流炸掉：
 *
 * 1. **半行截断**：连接断了或引擎被杀，最后一行只有半个 JSON —— 解析失败，丢弃；
 * 2. **未知 type**：引擎换代多出一种消息时，旧客户端应当忽略而不是崩；
 * 3. **空行**：直接跳过。
 *
 * 唯一的例外在 complete：base64 解不开时**仍然返回 complete 事件**，只是 `bytes` 为空。
 * 理由是把「图坏了」和「根本没有图」压成同一个 null 之后，调用方就再也分不清
 * 「引擎没出图」和「出了但传坏了」—— 前者该重试，后者该报错。
 */
object NdjsonReader {

    /**
     * 解析一行 NDJSON。
     *
     * @return [ImageGenEvent]，或 null（空行 / 坏 JSON / 不是对象 / 未知 type / 缺 progress 值）。
     */
    fun parseLine(line: String): ImageGenEvent? {
        val text = line.trim()
        if (text.isEmpty()) return null
        val obj = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        return when (stringOf(obj, EngineProtocol.MessageField.TYPE)) {
            EngineProtocol.MessageType.PROGRESS -> progress(obj)
            EngineProtocol.MessageType.COMPLETE -> complete(obj)
            else -> null
        }
    }

    /** 对一串已经读好的行做分发（单测与逐行日志回放用）。 */
    fun parseLines(lines: Sequence<String>): Sequence<ImageGenEvent> = lines.mapNotNull { parseLine(it) }

    /**
     * 流式读取：读一行 → 立刻分发 → 再读下一行。
     *
     * 返回的 Sequence 是**惰性**的：谁迭代谁触发读取，且只能消费一次
     * （底层 Reader 是一条流）。调用方拿到进度就能马上刷 UI，不必等生成结束。
     */
    fun read(reader: Reader): Sequence<ImageGenEvent> {
        val buffered = if (reader is BufferedReader) reader else BufferedReader(reader)
        return sequence {
            while (true) {
                val line = buffered.readLine() ?: break
                val event = parseLine(line)
                if (event != null) yield(event)
            }
        }
    }

    private fun progress(obj: JsonObject): ImageGenEvent? {
        val raw = floatOf(obj, EngineProtocol.MessageField.PROGRESS) ?: return null
        // 引擎实测给 0~1；夹一下是防御，不是契约 —— 越界的帧不该让进度条跑到 120%。
        if (!raw.isFinite()) return null
        val preview = stringOf(obj, EngineProtocol.MessageField.IMAGE)?.takeIf { it.isNotEmpty() }
        return ImageGenEvent.Progress(value = raw.coerceIn(0f, 1f), previewJpegBase64 = preview)
    }

    private fun complete(obj: JsonObject): ImageGenEvent.Complete = ImageGenEvent.Complete(
        bytes = decodeBase64(stringOf(obj, EngineProtocol.MessageField.IMAGE).orEmpty()),
        seed = longOf(obj, EngineProtocol.MessageField.SEED) ?: 0L,
        width = intOf(obj, EngineProtocol.MessageField.WIDTH) ?: 0,
        height = intOf(obj, EngineProtocol.MessageField.HEIGHT) ?: 0,
    )

    private fun stringOf(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.contentOrNull

    private fun floatOf(obj: JsonObject, key: String): Float? =
        (obj[key] as? JsonPrimitive)?.floatOrNull

    private fun longOf(obj: JsonObject, key: String): Long? =
        (obj[key] as? JsonPrimitive)?.longOrNull

    private fun intOf(obj: JsonObject, key: String): Int? =
        (obj[key] as? JsonPrimitive)?.intOrNull

    /**
     * base64 解码。失败返回空数组（见类注释：宁可给出「空的 complete」，
     * 也不要把它变成静默丢帧）。换行/空格先剔掉，兼容会把长串折行的实现。
     */
    private fun decodeBase64(base64: String): ByteArray {
        if (base64.isEmpty()) return ByteArray(0)
        val compact = base64.filterNot { it.isWhitespace() }
        return runCatching { Base64.getDecoder().decode(compact) }.getOrDefault(ByteArray(0))
    }
}
