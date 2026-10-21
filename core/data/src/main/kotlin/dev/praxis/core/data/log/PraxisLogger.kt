package dev.praxis.core.data.log

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * 异步落盘日志器（移植自 apixiaoyuan 的 `WeLogger`，API 与行为保持一致）。
 *
 * ## 为什么必须异步 + 有界
 *
 * 日志调用点在主线程（Compose 事件、ViewModel）。直接 `FileWriter.write` 会让
 * 一次慢 IO 卡住一帧；不设上限的队列在磁盘写不动时会把内存吃光（OOM 比丢日志惨得多）。
 * 所以：**有界队列 + 单独 daemon 线程批量写 + 满了就丢并计数**。
 *
 * ## 与上游的差异（只有三处，都是包名/业务差异）
 *
 *  - TAG 从 `AutoOral` 改为 `Praxis`，文件名前缀 `praxis-`（见 [LogLine]）。
 *  - 日志目录仍取 `filesDir/logs`，但**必须显式 [init] 注入 Context**：
 *    上游靠反射 `currentApplication()` 兜底，那是 LSPosed 注入场景需要；
 *    Praxis 是普通 App，在 `Application.onCreate` 里注入一行就够，反射兜底只会
 *    在单测里抛无意义异常。
 *  - 走 [_writeRecord] 时用 [LogLine.format] 渲染，不再就地拼字符串 ——
 *    读写共用一份格式（见 [LogLine] 的注释）。
 *
 * ## 线程模型
 *
 * ```
 * 调用方线程 ──offer──> ArrayBlockingQueue(2048, 其中 128 留给 E/W/A)
 *                              │
 *                     writer thread: take + drainTo(batch, 63)
 *                              │
 *                     FileWriter(praxis-yyyy-MM-dd.log)
 * ```
 *
 * writer 每批结束 flush 一次；写 E/W/A 后立即 flush（崩溃前的错误必须已经在磁盘上）。
 * `flush()` 用 CountDownLatch 在队列里插一道屏障，等到屏障被执行才返回。
 */
object PraxisLogger {

    /** logcat 里的 TAG。 */
    const val TAG: String = LogLine.LOGGER_TAG

    /** 单条消息超过该长度就分片，避免一行写爆某些日志阅读器/分享通道。 */
    private const val CHUNK_SIZE = 4000

    /** 分片上限：超过就只写头部 + 截断提示，不把一条消息写成几千行。 */
    private const val MAX_CHUNKS = 200

    private const val QUEUE_CAPACITY = 2048

    /**
     * 给 E/W/A 预留的容量。
     *
     * 语义：**重要性级别可以越过这条线**，普通级别一旦剩余容量低于该值就直接丢弃。
     * 效果是「应用疯狂刷 D/V 时，一条 E 仍然进得去」—— 排障时最需要的那几条不会被刷掉。
     */
    private const val RESERVED_IMPORTANT_CAPACITY = 128

    private const val BATCH_SIZE = 64
    private const val FLUSH_TIMEOUT_MILLIS = 3000L

    /** 保留天数：按文件名日期清理，早于 `今天 - 3 天` 的删掉。 */
    private const val RETAIN_DAYS = 3L

    private sealed interface WriteTask {
        data class Record(
            val level: Char,
            val tag: String?,
            val msg: String,
            val throwable: Throwable?,
            val timestamp: LocalDateTime,
        ) : WriteTask

        class Flush(val completed: CountDownLatch) : WriteTask
    }

    private val writeQueue = ArrayBlockingQueue<WriteTask>(QUEUE_CAPACITY, true)
    private val droppedRecords = AtomicLong()

    private val writerThread = Thread(::runWriter, "Praxis-Logger").apply {
        isDaemon = true
        start()
    }

    private var writer: FileWriter? = null
    private var currentLogDate: LocalDate? = null

    // ========== Context / 目录解析 ==========

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedLogsDir: File? = null

    /**
     * 注入 Application Context。必须在第一次写日志之前调用
     * （`PraxisApplication.onCreate` 最合适）。
     *
     * 注入会清空目录缓存 —— 允许在测试或多进程场景里重复调用、
     * 让后续日志落到新 Context 的 filesDir 下。
     */
    fun init(context: Context) {
        appContext = context.applicationContext ?: context
        cachedLogsDir = null
    }

    private fun resolveLogsDir(): File? {
        cachedLogsDir?.let { return it }
        val ctx = appContext ?: return null
        val dir = runCatching {
            File(ctx.filesDir, "logs").let { d ->
                if (!d.isDirectory && !d.mkdirs()) null else d
            }
        }.getOrNull() ?: return null
        cachedLogsDir = dir
        return dir
    }

    // ========== 文件句柄 / 轮转 ==========

    private fun getOrRotateWriter(logDate: LocalDate): FileWriter? {
        if (writer != null && currentLogDate == logDate) return writer

        // 跨天：先关旧句柄再开新的。关失败也不能影响开新文件。
        writer?.runCatching { close() }
        writer = null
        currentLogDate = null

        val logsDir = resolveLogsDir() ?: return null
        deleteOldLogs(logsDir)

        val logFile = File(logsDir, "${LogLine.FILE_PREFIX}${LogLine.dateFormatter.format(logDate)}.log")
        return runCatching {
            FileWriter(logFile, /* append = */ true).also {
                writer = it
                currentLogDate = logDate
            }
        }.getOrNull()
    }

    private fun deleteOldLogs(logsDir: File) {
        runCatching {
            val threshold = LocalDate.now().minusDays(RETAIN_DAYS)
            val regex = Regex("""${Regex.escape(LogLine.FILE_PREFIX)}(\d{4}-\d{2}-\d{2})\.log""")
            logsDir.listFiles()?.forEach { file ->
                val match = regex.matchEntire(file.name) ?: return@forEach
                val date = runCatching {
                    LocalDate.parse(match.groupValues[1], LogLine.dateFormatter)
                }.getOrNull() ?: return@forEach
                if (date.isBefore(threshold)) file.delete()
            }
        }
    }

    // ========== writer 线程 ==========

    private fun runWriter() {
        val batch = ArrayList<WriteTask>(BATCH_SIZE)
        while (!Thread.currentThread().isInterrupted) {
            val first = try {
                writeQueue.take()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            batch += first
            writeQueue.drainTo(batch, BATCH_SIZE - 1)

            var dirty = false
            batch.forEach { task ->
                when (task) {
                    is WriteTask.Record -> {
                        // 丢弃计数要写在**下一条成功的记录之前**，
                        // 这样「哪一段时间丢了日志」在时间线上是准确的。
                        writeDroppedNotice(task.timestamp)
                        val written = writeRecord(task)
                        dirty = written || dirty
                        if (written && task.level.isImportant()) {
                            // 错误必须当场落盘：崩溃可能就是下一行代码。
                            flushWriter()
                            dirty = false
                        }
                    }

                    is WriteTask.Flush -> {
                        try {
                            writeDroppedNotice(LocalDateTime.now())
                            flushWriter()
                        } finally {
                            // 必须 countDown：调用方在带超时地 await，
                            // 漏掉它会白等 3 秒（每次打开日志页都卡 3 秒）。
                            task.completed.countDown()
                        }
                        dirty = false
                    }
                }
            }
            if (dirty) flushWriter()
            batch.clear()
        }
    }

    private fun writeRecord(record: WriteTask.Record): Boolean {
        val w = getOrRotateWriter(record.timestamp.toLocalDate()) ?: return false
        return runCatching {
            w.write(LogLine.format(record.level, record.tag, record.msg, record.timestamp))
            if (record.throwable != null) {
                w.write("\n")
                w.write(Log.getStackTraceString(record.throwable))
            }
            w.write("\n")
            true
        }.getOrElse {
            // 文件写失败不能抛给调用方（日志器不能成为新的崩溃源），
            // 退回 logcat —— 至少 logcat 里还能看到。
            Log.e(TAG, "写日志文件失败", it)
            false
        }
    }

    private fun writeDroppedNotice(timestamp: LocalDateTime) {
        val count = droppedRecords.getAndSet(0)
        if (count == 0L) return
        writeRecord(
            WriteTask.Record(
                level = 'W',
                tag = "PraxisLogger",
                msg = "队列满，丢弃 $count 条日志（普通级别在剩余容量低于 " +
                    "$RESERVED_IMPORTANT_CAPACITY 时不入队）",
                throwable = null,
                timestamp = timestamp,
            ),
        )
    }

    private fun flushWriter() {
        writer?.runCatching { flush() }
    }

    private fun enqueue(record: WriteTask.Record) {
        val hasRoom = record.level.isImportant() ||
            writeQueue.remainingCapacity() > RESERVED_IMPORTANT_CAPACITY
        if (!hasRoom || !writeQueue.offer(record)) {
            droppedRecords.incrementAndGet()
        }
    }

    /**
     * 等队列排空并 flush 当前文件句柄。**阻塞**调用，只在明确的同步点用
     * （崩溃处理、日志页读取当前文件前）。
     */
    fun flush() {
        // 自锁防护：writer 线程内部（比如 writeDroppedNotice 间接）调 flush 时，
        // 若也去 offer 屏障再 await，屏障排在它自己后面 → 永远等不到。
        if (Thread.currentThread() === writerThread) {
            writeDroppedNotice(LocalDateTime.now())
            flushWriter()
            return
        }

        val completed = CountDownLatch(1)
        val enqueued = try {
            writeQueue.offer(WriteTask.Flush(completed), FLUSH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!enqueued) {
            Log.w(TAG, "日志 flush 屏障入队超时")
            return
        }

        val finished = try {
            completed.await(FLUSH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) Log.w(TAG, "日志 flush 等待超时")
    }

    // ========== 供日志页读取的公共访问器 ==========

    /** 日志目录（`filesDir/logs`），首次访问时创建。未 [init] 时为 null。 */
    val logsDir: File?
        get() = resolveLogsDir()

    /**
     * 全部运行日志文件，**新文件在前**。
     *
     * 先 [flush]：当前正在写的文件可能还在缓冲区里，
     * 不 flush 就读会看到「半天前的日志」——用户会以为日志不更新。
     */
    val allLogFiles: List<File>
        get() {
            flush()
            val dir = resolveLogsDir() ?: return emptyList()
            val regex = Regex("""${Regex.escape(LogLine.FILE_PREFIX)}\d{4}-\d{2}-\d{2}\.log""")
            return runCatching {
                dir.listFiles()
                    ?.filter { it.isFile && regex.matches(it.name) }
                    ?.sortedByDescending { it.name }
                    ?: emptyList()
            }.getOrDefault(emptyList())
        }

    /**
     * 清空全部运行日志。
     *
     * ## 必须先关句柄再删文件
     *
     * 在 Android/Linux 上删除一个「仍被打开的」文件不会失败：目录项没了，
     * 而 writer 手里的 fd 依旧指向那个 inode，后续写入全部落在**已不可见的文件**上。
     * 用户看到「点了清空，页也空了」，过一会新日志又不出现 —— 因为新日志
     * 写进了一个再也没人能打开的文件。所以顺序固定：
     * flush -> close -> 删文件 -> 置空 currentLogDate（下次写自动重建文件）。
     *
     * @return 实际删掉的文件数。
     */
    fun clearLogs(): Int {
        flush()
        writer?.runCatching { close() }
        writer = null
        currentLogDate = null

        val dir = resolveLogsDir() ?: return 0
        val regex = Regex("""${Regex.escape(LogLine.FILE_PREFIX)}\d{4}-\d{2}-\d{2}\.log""")
        var deleted = 0
        dir.listFiles()?.forEach { file ->
            if (file.isFile && regex.matches(file.name) && file.delete()) deleted++
        }
        return deleted
    }

    // ========== 写入口 ==========

    fun v(tag: String?, msg: String) = log('V', tag, msg)
    fun d(tag: String?, msg: String) = log('D', tag, msg)
    fun i(tag: String?, msg: String) = log('I', tag, msg)
    fun w(tag: String?, msg: String) = log('W', tag, msg)
    fun e(tag: String?, msg: String) = log('E', tag, msg)

    fun v(tag: String?, msg: String, t: Throwable) = log('V', tag, msg, t)
    fun d(tag: String?, msg: String, t: Throwable) = log('D', tag, msg, t)
    fun i(tag: String?, msg: String, t: Throwable) = log('I', tag, msg, t)
    fun w(tag: String?, msg: String, t: Throwable) = log('W', tag, msg, t)
    fun e(tag: String?, msg: String, t: Throwable) = log('E', tag, msg, t)

    /**
     * 统一写入口。
     *
     * 同时写 logcat 与队列：logcat 给 `adb logcat` 现场排查（尤其是「日志器自己没起来」
     * 这种没法落盘的情况），文件给用户自己看。
     */
    fun log(level: Char, tag: String?, msg: String, throwable: Throwable? = null) {
        val line = "$tag: $msg"
        when (level) {
            'V' -> Log.v(TAG, line, throwable)
            'D' -> Log.d(TAG, line, throwable)
            'I' -> Log.i(TAG, line, throwable)
            'W' -> Log.w(TAG, line, throwable)
            'E' -> Log.e(TAG, line, throwable)
            else -> Log.println(Log.INFO, TAG, line)
        }
        enqueue(WriteTask.Record(level, tag, msg, throwable, LocalDateTime.now()))
    }

    // ========== 分片 ==========

    /**
     * 超长消息分片写入（比如整个 HTTP 响应体）。
     *
     * 为什么需要：单行几十万字符会让日志页的 `Text` 排版卡死，
     * 也可能被分享通道截断。分片后每片都是一个独立日志行。
     */
    fun logChunked(level: Char, tag: String, msg: String) {
        if (msg.length <= CHUNK_SIZE) {
            log(level, tag, msg)
            return
        }

        val len = msg.length
        val chunkCount = (len + CHUNK_SIZE - 1) / CHUNK_SIZE
        if (chunkCount > MAX_CHUNKS) {
            val head = msg.substring(0, CHUNK_SIZE)
            log(level, tag, "[chunked] 过长（$len 字符 / $chunkCount 片），只保留首片：\n$head")
            log(level, tag, "[chunked] 已截断，完整内容请直接写文件。")
            return
        }

        val timestamp = LocalDateTime.now()
        var i = 0
        var part = 1
        while (i < len) {
            val end = min(i + CHUNK_SIZE, len)
            // 所有分片共用同一个时间戳：它们在用户看来是同一条日志。
            enqueue(
                WriteTask.Record(
                    level = level,
                    tag = tag,
                    msg = "[$part/$chunkCount] ${msg.substring(i, end)}",
                    throwable = null,
                    timestamp = timestamp,
                ),
            )
            Log.println(level.toLogcatPriority(), TAG, "$tag: [$part/$chunkCount]")
            i += CHUNK_SIZE
            part++
        }
    }

    // ========== 辅助 ==========

    /** 当前调用栈（`at pkg.Class.method(File.kt:123)` 形式），便于在日志里标注位置。 */
    val currentStackTrace: String
        get() = Thread.currentThread().stackTrace
            .drop(2) // 去掉 getStackTrace 与 getter 自身
            .joinToString("\n") { "at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }

    private fun Char.isImportant(): Boolean = this == 'E' || this == 'W' || this == 'A'

    private fun Char.toLogcatPriority(): Int = when (this) {
        'V' -> Log.VERBOSE
        'D' -> Log.DEBUG
        'I' -> Log.INFO
        'W' -> Log.WARN
        'E' -> Log.ERROR
        'A' -> Log.ASSERT
        else -> Log.INFO
    }
}