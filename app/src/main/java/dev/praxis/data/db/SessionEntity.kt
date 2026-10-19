package dev.praxis.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一次会话。Phase 1 只落最小字段，后续移植 Operit 的 chat 域时按需扩展，
 * 不做「先建大表再废弃」的返工。
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)
