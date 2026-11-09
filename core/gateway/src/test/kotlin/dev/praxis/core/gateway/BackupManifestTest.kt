package dev.praxis.core.gateway

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 备份清单与恢复判定（文档 组十六）。 */
class BackupManifestTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")

    private fun millisAt(hour: Int, minute: Int, second: Int): Long =
        LocalDateTime.of(2026, 11, 9, hour, minute, second)
            .atZone(shanghai).toInstant().toEpochMilli()

    @Test
    fun `文件名带可读时间戳`() {
        assertEquals(
            "praxis-backup-20261109-153045.db",
            BackupManifestCodec.fileNameFor(millisAt(15, 30, 45), shanghai),
        )
    }

    @Test
    fun `文件名按字典序就是按时间序`() {
        val earlier = BackupManifestCodec.fileNameFor(millisAt(9, 0, 0), shanghai)
        val later = BackupManifestCodec.fileNameFor(millisAt(15, 0, 0), shanghai)
        assertTrue(earlier < later)
    }

    @Test
    fun `清单往返`() {
        val manifest = BackupManifest(
            createdAt = 1_700_000_000_000L,
            databaseVersion = 14,
            fileName = "praxis-backup-20261109-153045.db",
            sizeBytes = 4096L,
        )
        assertEquals(manifest, BackupManifestCodec.decode(BackupManifestCodec.encode(manifest)))
    }

    @Test
    fun `坏清单返回 null 而不是半个对象`() {
        // 恢复是破坏性操作：清单读不全就必须拒绝，不能"尽量恢复"。
        assertNull(BackupManifestCodec.decode("不是 JSON"))
        assertNull(BackupManifestCodec.decode("""{"createdAt":123}"""))
        assertNull(BackupManifestCodec.decode("""{"databaseVersion":14,"fileName":"x"}"""))
    }

    @Test
    fun `同版本或更旧的备份可以恢复`() {
        val manifest = BackupManifest(1L, databaseVersion = 14, fileName = "x", sizeBytes = 1)
        assertEquals(RestoreVerdict.Ok, BackupManifestCodec.canRestore(manifest, 14))
        assertEquals(RestoreVerdict.Ok, BackupManifestCodec.canRestore(manifest, 15))
    }

    @Test
    fun `更新的备份被拒绝并带上版本号`() {
        // 降级恢复会让 Room 打不开库，而用户只会看到「恢复失败」。
        val manifest = BackupManifest(1L, databaseVersion = 15, fileName = "x", sizeBytes = 1)
        val verdict = BackupManifestCodec.canRestore(manifest, 14)
        assertTrue(verdict is RestoreVerdict.TooNew)
        assertEquals(15, (verdict as RestoreVerdict.TooNew).backupVersion)
        assertEquals(14, verdict.appVersion)
    }
}
