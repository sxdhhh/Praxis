package dev.praxis.core.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 整库备份的清单（文档 §5.x 组十六「备份 / 恢复」）。
 *
 * ## 备份什么
 *
 * 整个 Room 库（账号、家、模型条目、请求记录、会话…）+ 这一份清单。
 * 数据存储（DataStore）里的设置**不在其中** —— 那是另一个文件，混进来会让
 * 「备份是数据库的快照」这个语义变模糊。
 *
 * ## 一条必须写进清单的警告
 *
 * 凭证是用 **Android Keystore 的设备绑定密钥**加密的。同一个 APK 装在同一台设备上、
 * Keystore 没被重置时才解得开；换设备或恢复出厂后，`credentialsCipher` 里的密文
 * 就是一堆无法解密的字节。**恢复方必须知道这一点** —— 否则他会以为「备份坏了」。
 */
data class BackupManifest(
    /** 生成时刻（毫秒）。 */
    val createdAt: Long,
    /** 备份时的数据库版本（`PRAGMA user_version`）。 */
    val databaseVersion: Int,
    /** 备份文件名（不含目录）。 */
    val fileName: String,
    /** 原始库文件字节数，给界面显示。 */
    val sizeBytes: Long,
)

/** 备份清单的编解码与文件名规则。 */
object BackupManifestCodec {

    /** 清单在 zip/目录里叫这个名字，恢复时靠它判断「这是我们的备份」。 */
    const val MANIFEST_NAME: String = "manifest.json"

    /**
     * 备份文件名：带时间戳，按文件名排序就是按时间排序。
     *
     * 用 `yyyyMMdd-HHmmss` 而不是毫秒时间戳：用户在文件管理器里能一眼看懂，
     * 而毫秒数字既不好读、也不能直接排序（长度相同倒也能排，但没必要）。
     */
    fun fileNameFor(epochMillis: Long, zone: java.time.ZoneId): String {
        val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .format(java.time.Instant.ofEpochMilli(epochMillis).atZone(zone))
        return "praxis-backup-" + stamp + ".db"
    }

    fun encode(manifest: BackupManifest): String = buildJsonObject {
        put("createdAt", manifest.createdAt)
        put("databaseVersion", manifest.databaseVersion)
        put("fileName", manifest.fileName)
        put("sizeBytes", manifest.sizeBytes)
    }.toString()

    /** @return null = 不是我们的清单或内容坏了（调用方据此拒绝恢复）。 */
    fun decode(text: String): BackupManifest? = runCatching {
        val root: JsonObject = Json.parseToJsonElement(text).jsonObject
        BackupManifest(
            createdAt = root["createdAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: error("缺 createdAt"),
            databaseVersion = root["databaseVersion"]?.jsonPrimitive?.intOrNull
                ?: error("缺 databaseVersion"),
            fileName = root["fileName"]?.jsonPrimitive?.contentOrNull
                ?: error("缺 fileName"),
            sizeBytes = root["sizeBytes"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
        )
    }.getOrNull()

    /** 恢复前的一致性检查：备份的库版本不能高于当前 App 的版本。 */
    fun canRestore(manifest: BackupManifest, currentDatabaseVersion: Int): RestoreVerdict {
        if (manifest.databaseVersion > currentDatabaseVersion) {
            return RestoreVerdict.TooNew(manifest.databaseVersion, currentDatabaseVersion)
        }
        return RestoreVerdict.Ok
    }
}

/** 恢复前的判定。 */
sealed interface RestoreVerdict {

    data object Ok : RestoreVerdict

    /**
     * 备份来自更新的 App。
     *
     * 降级恢复会让 Room 在打开库时报「库版本比代码期望的新」，那是**打不开**，
     * 而用户会看到「恢复失败」却不知道为什么。所以提前判、并说清版本号。
     */
    data class TooNew(val backupVersion: Int, val appVersion: Int) : RestoreVerdict
}
