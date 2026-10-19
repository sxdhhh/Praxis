package dev.praxis.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * 应用数据库。
 *
 * version 2：新增 messages 表（v1 只有 sessions）。
 * 迁移策略见 AppModule：debug 用破坏性迁移，release 严格 —— 开发期不写迁移脚本，
 * 上线后一旦 schema 变了却没迁移就直接抛异常暴露问题，而不是静默清空用户数据。
 */
@Database(
    entities = [SessionEntity::class, MessageEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class PraxisDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
}
