package dev.praxis.data.backup

import dev.praxis.core.gateway.BackupManifest
import dev.praxis.core.gateway.BackupManifestCodec
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一次备份的结果。 */
sealed interface BackupResult {

    data class Done(val file: File, val manifest: BackupManifest) : BackupResult

    data class Failed(val detail: String) : BackupResult
}

/**
 * 整库备份的**导出**（文档 组十六）。
 *
 * ## 为什么先做导出、恢复要单独想
 *
 * 导出是**只读 + 写一个新文件**：做错了最坏结果是「备份文件不能用」，原始数据完好。
 * 恢复是**覆盖当前库**：WAL 没合并、Room 还开着、写了一半断电，任何一种都是用户数据丢失。
 * 而这三样**只能真机验证** —— 所以在没有真机验证之前，恢复不交付（明确标注未实现，
 * 而不是给一个"大概能用"的版本）。
 *
 * ## WAL 必须先合并
 *
 * Room 默认开 WAL：最近的写入可能还在 `praxis.db-wal` 里，主文件是旧的。
 * 只复制主文件会得到一个「少了最后几条数据」的备份 —— 而且看不出少了什么。
 * 所以先 `wal_checkpoint(TRUNCATE)` 把 WAL 并回主文件。
 */
class BackupExporter(
    /** 当前库文件（`context.getDatabasePath("praxis.db")`）。 */
    private val databaseFile: File,
    /** 备份输出目录。 */
    private val outputDir: File,
    /** 执行 `PRAGMA wal_checkpoint(TRUNCATE)`；由调用方接上真实库。 */
    private val checkpoint: suspend () -> Unit,
    /** 当前库的 `user_version`。 */
    private val databaseVersion: suspend () -> Int,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    suspend fun export(): BackupResult = withContext(Dispatchers.IO) {
        try {
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                return@withContext BackupResult.Failed("无法创建备份目录：" + outputDir.absolutePath)
            }
            if (!databaseFile.exists()) {
                return@withContext BackupResult.Failed("数据库文件不存在：" + databaseFile.absolutePath)
            }
            checkpoint()

            val now = clock()
            val name = BackupManifestCodec.fileNameFor(now, zone)
            val target = File(outputDir, name)
            databaseFile.copyTo(target, overwrite = true)

            val manifest = BackupManifest(
                createdAt = now,
                databaseVersion = databaseVersion(),
                fileName = name,
                sizeBytes = target.length(),
            )
            File(outputDir, BackupManifestCodec.MANIFEST_NAME)
                .writeText(BackupManifestCodec.encode(manifest))

            BackupResult.Done(target, manifest)
        } catch (error: Exception) {
            BackupResult.Failed(error.message ?: "备份失败")
        }
    }
}
