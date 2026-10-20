package dev.praxis.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 应用数据库。
 *
 * ## 版本历史
 *
 * - **v1**：只有 sessions
 * - **v2**：新增 messages（外键 CASCADE 到 sessions）
 * - **v3**：新增 memories（长期记忆，**不挂** session 外键——记忆是跨会话的）
 *
 * ## 迁移策略（比之前收紧）
 *
 * v2 时用「debug 破坏性迁移 / release 严格」；从 v3 起**两个都严格**：
 * 记忆一旦被清空，用户的长期上下文就没了，而且不可恢复。
 * 破坏性迁移只对聊天记录影响还能接受，对记忆不能接受，所以统一写迁移。
 */
@Database(
    entities = [SessionEntity::class, MessageEntity::class, MemoryEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class PraxisDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao

    companion object {
        /**
         * v2 → v3：建 memories 表。
         *
         * 必须手写 SQL 而不是靠 Room 自动迁移：
         * 自动迁移在某些列默认值上会自己拿主意，而这里每个字段的默认值都是有意为之的
         * （tags 用单元分隔符连接、hitCount 默认 0）。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memories` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`tags` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`lastUsedAt` INTEGER NOT NULL, " +
                        "`hitCount` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_lastUsedAt` ON `memories` (`lastUsedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_createdAt` ON `memories` (`createdAt`)")
            }
        }
    }
}
