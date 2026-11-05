package dev.praxis.data.spill

import android.util.Log
import dev.praxis.core.linux.SpilledText
import dev.praxis.core.linux.SpillPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom

/**
 * 溢出存储：把过长的工具输出落到文件，只给模型一个定位符。
 *
 * ## 为什么需要它（2026-11-06）
 *
 * 旧实现超过 `MAX_OUTPUT_CHARS` 就**静默丢弃**剩余部分。后果不是「省了上下文」，
 * 而是模型永远看不到被丢掉的那段 —— 它会基于半截输出做判断，而用户
 * 和模型都不知道少了什么。
 *
 * 典型场景：`apt install` 装失败，真正的错误在最后几行；而前 16000 字符
 * 全是包列表。丢掉尾部等于丢掉了唯一的线索。
 *
 * ## 为什么落文件而不是留在内存
 *
 * 一次扫描输出几十 MB 是常态。留在内存里会让进程在低内存设备上被回收，
 * 且进程重启后模型手里的定位符就失效了。落文件后定位符跨进程仍然有效。
 *
 * ## 为什么定位符用随机串而不是内容哈希
 *
 * 内容哈希会让「相同输出」共享同一个定位符 —— 看似省空间，
 * 但用户删掉其中一条想清理时，另一条的定位符也会失效。
 * 随机串让每次保存都是独立的，删除互不影响。
 */
class SpillStore(
    /** 溢出文件目录（应用私有）。 */
    private val dir: File,
) {

    private val random = SecureRandom()

    /**
     * 保存文本，返回可交给模型的描述。
     *
     * ## 为什么先写文件再算预览
     *
     * 预览从**已落盘的内容**里切，保证「模型看到的片段」与
     * 「read_spill 取回的正文」是同一份。若先切预览再写文件，
     * 两者来自不同副本，中间任何异常都会让它们不一致。
     */
    suspend fun saveText(text: String): SpilledText? = withContext(Dispatchers.IO) {
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            val locator = newLocator()
            val file = fileOf(locator)
            file.writeText(text)
            val (head, tail) = SpillPolicy.makePreview(text)
            SpilledText(
                locator = locator,
                totalBytes = text.toByteArray(Charsets.UTF_8).size,
                totalLines = text.count { it == '\n' } + 1,
                previewHead = head,
                previewTail = tail,
            )
        }.getOrElse { e ->
            // 落盘失败时返回 null：调用方据此退回「截断」，
            // **不能假装保存成功** —— 那样模型会拿到一个取不回来的定位符。
            Log.e(TAG, "溢出保存失败", e)
            null
        }
    }

    /**
     * 按定位符取回正文。
     *
     * ## 为什么限定字符集
     *
     * 定位符来自模型（可能被它编造或篡改）。若直接拼进路径，
     * `../` 就能读到目录外任意文件。这里只接受我们自己生成的字符集，
     * 其余一律当「不存在」。
     */
    suspend fun read(locator: String): String? = withContext(Dispatchers.IO) {
        if (!isValidLocator(locator)) return@withContext null
        val file = fileOf(locator)
        if (!file.isFile) return@withContext null
        runCatching { file.readText() }.getOrNull()
    }

    /** 删除过期文件（保留最近 [keep] 个）。 */
    suspend fun prune(keep: Int = MAX_FILES) = withContext(Dispatchers.IO) {
        runCatching {
            val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
                ?: return@withContext
            files.drop(keep).forEach { it.delete() }
        }
    }

    private fun newLocator(): String {
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        return PREFIX + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun isValidLocator(locator: String): Boolean =
        locator.startsWith(PREFIX) &&
            locator.length == PREFIX.length + 16 &&
            locator.drop(PREFIX.length).all { it in '0'..'9' || it in 'a'..'f' }

    private fun fileOf(locator: String): File = File(dir, "$locator.txt")

    private companion object {
        const val TAG = "SpillStore"
        const val PREFIX = "spill-"
        /**
         * 最多保留的溢出文件数。
         *
         * 128 个够覆盖一整天的密集排查；再多会占满磁盘 ——
         * 手机存储通常只剩几个 GB，而单次扫描输出可能就是几十 MB。
         */
        const val MAX_FILES = 128
    }
}
