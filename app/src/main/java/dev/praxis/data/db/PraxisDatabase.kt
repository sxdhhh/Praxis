package dev.praxis.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.praxis.core.data.character.CharacterCardEntity
import dev.praxis.core.data.character.PromptTagEntity

/**
 * 应用数据库。
 *
 * ## 版本历史
 *
 * - **v1**：只有 sessions
 * - **v2**：新增 messages（外键 CASCADE 到 sessions）
 * - **v3**：新增 memories（长期记忆，**不挂** session 外键——记忆是跨会话的）
 * - **v4**：sessions 新增 `pinned`（侧边栏置顶）
 * - **v5**：新增 character_cards 与 prompt_tags（角色卡系统）
 *
 * ## 迁移策略（比之前收紧）
 *
 * v2 时用「debug 破坏性迁移 / release 严格」；从 v3 起**两个都严格**：
 * 记忆一旦被清空，用户的长期上下文就没了，而且不可恢复。
 * 破坏性迁移只对聊天记录影响还能接受，对记忆不能接受，所以统一写迁移。
 *
 * v5 沿用严格策略：角色卡里是用户写的几千字人设，丢了不可能重写。
 */
@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        MemoryEntity::class,
        CharacterCardEntity::class,
        PromptTagEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class PraxisDatabase : RoomDatabase(), dev.praxis.core.data.character.CharacterDatabase {

    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao

    // ── 角色系统（实体与 DAO 定义在 :core:data）───────────────
    override abstract fun characterCardDao(): dev.praxis.core.data.character.CharacterCardDao
    override abstract fun promptTagDao(): dev.praxis.core.data.character.PromptTagDao

    /**
     * 事务入口。
     *
     * `withTransaction` 来自 room-ktx，它正确处理协程取消
     * （取消时会回滚并释放事务）—— 手写 begin/commit 在取消时会留下
     * 悬挂事务，把后续所有写操作锁死。
     */
    override suspend fun <T> withCharacterTransaction(block: suspend () -> T): T =
        withTransaction { block() }

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
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memories_lastUsedAt` " +
                        "ON `memories` (`lastUsedAt`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memories_createdAt` " +
                        "ON `memories` (`createdAt`)",
                )
            }
        }

        /**
         * v3 → v4：sessions 加 `pinned`。
         *
         * ## 为什么必须 `NOT NULL DEFAULT 0`
         *
         * `ALTER TABLE ADD COLUMN` 加一个 NOT NULL 列**必须**给 DEFAULT，
         * 否则 SQLite 直接报错（已有行无法填值）。
         * 而 Kotlin 侧 `pinned: Boolean = false` 对应的是 `INTEGER NOT NULL`，
         * 两边必须一致 —— 少写 DEFAULT 就是升级即崩。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `sessions` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * v4 → v5：建角色卡与提示词标签两张表。
         *
         * ## 为什么手写 SQL 而不是依赖 Room 自动迁移
         *
         * 自动迁移靠比对 schema 推导语句，但有三个陷阱：
         *
         * 1. **列顺序与类型必须与实体声明严格一致**，否则自动迁移会生成
         *    `CREATE TABLE ... AS SELECT` 的中间表，索引名对不上；
         * 2. `toolAccessConfigRaw` / `attachedTagIdsRaw` 在 Kotlin 侧带
         *    `@ColumnInfo(name=...)`，自动迁移生成的列名不一定与预期一致；
         * 3. 这一步只是**建新表**（不改旧表），手写最短且没有风险。
         *
         * ## 为什么 `NOT NULL` 列不写 `DEFAULT`
         *
         * 新建表时没有已有行要填，所以不写默认值是安全的。
         * 更要紧的是：**不能写**。Room 会拿 `exportSchema` 生成的期望 schema
         * 与实际库比对，`DEFAULT` 值不一致同样会判成 schema 不匹配
         * （报 `Migration didn't properly handle`，应用起不来）。
         * 实体类里没有 `@ColumnInfo(defaultValue = ...)`，SQL 里就不能写。
         *
         * 索引必须一起建：Room 启动时会校验 schema 与实际库是否一致，
         * 少建索引会在**升级后第一次打开数据库**时抛
         * `Migration didn't properly handle`，整个应用起不来。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `character_cards` (" +
                        "`id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`description` TEXT NOT NULL, " +
                        "`characterSetting` TEXT NOT NULL, " +
                        "`openingStatement` TEXT NOT NULL, " +
                        "`otherContentChat` TEXT NOT NULL, " +
                        "`otherContentVoice` TEXT NOT NULL, " +
                        "`attachedTagIds` TEXT NOT NULL, " +
                        "`advancedCustomPrompt` TEXT NOT NULL, " +
                        "`marks` TEXT NOT NULL, " +
                        "`chatModelBindingMode` TEXT NOT NULL, " +
                        "`chatModelConfigId` TEXT, " +
                        "`chatModelIndex` INTEGER NOT NULL, " +
                        "`memoryProfileBindingMode` TEXT NOT NULL, " +
                        "`memoryProfileId` TEXT, " +
                        "`toolAccessConfigRaw` TEXT NOT NULL, " +
                        "`isDefault` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_character_cards_updatedAt` " +
                        "ON `character_cards` (`updatedAt`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_character_cards_isDefault` " +
                        "ON `character_cards` (`isDefault`)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `prompt_tags` (" +
                        "`id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`description` TEXT NOT NULL, " +
                        "`promptContent` TEXT NOT NULL, " +
                        "`tagType` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_prompt_tags_tagType` " +
                        "ON `prompt_tags` (`tagType`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_prompt_tags_updatedAt` " +
                        "ON `prompt_tags` (`updatedAt`)",
                )
            }
        }

        /**
         * 5 → 6：给 `sessions` 加 `displayOrder`（对齐 Operit 的会话排序口径）。
         *
         * ## 为什么要加这一列（真实缺陷）
         *
         * 旧排序 `ORDER BY pinned DESC, updatedAt DESC` 让**列表顺序随说话而变**
         * —— 刚发过消息的会话跃到第一，上一个会话被挤下去。用户找会话时
         * 位置一直在动。Operit 把排序键（`displayOrder = -createdAt`）与
         * 活跃时间（`updatedAt`）分开，本迁移照此补齐。
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. 加列。SQLite 的 `ALTER TABLE ADD COLUMN` 必须带 `DEFAULT`
                //    （否则已有行没有值）。先给 0，下一步立刻覆盖。
                db.execSQL(
                    "ALTER TABLE `sessions` ADD COLUMN `displayOrder` INTEGER NOT NULL DEFAULT 0",
                )
                // 2. 回填 `-createdAt`，与实体默认值逐字一致。
                //
                //    ## 为什么必须回填而不是留 0
                //
                //    若全是 0，`ORDER BY displayOrder ASC` 对它们**没有区分度**，
                //    SQLite 会退化成按 rowid（插入顺序）返回 —— 老用户升级后
                //    列表会整体洗牌一次，观感是「会话被重排了」。
                //    回填后，迁移前后的可见顺序保持一致。
                db.execSQL("UPDATE `sessions` SET `displayOrder` = -`createdAt`")
                // 3. 建复合索引 `(pinned, displayOrder)`。
                //
                //    ## 为什么列顺序不能反
                //
                //    查询是「先按 pinned 分两块、块内按 displayOrder 排」。
                //    SQLite 的复合索引只对**前缀**生效，写成
                //    `(displayOrder, pinned)` 就帮不上这个查询。
                //
                //    索引名必须合 Room 的自动命名规则（`index_<表>_<列>`），
                //    否则启动时的 schema 校验会报
                //    `Migration didn't properly handle`，应用直接起不来。
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sessions_pinned_displayOrder` " +
                        "ON `sessions` (`pinned`, `displayOrder`)",
                )
            }
        }

        /**
         * 6 → 7：消息变体（对齐 Operit 的「重新生成不叠气泡」）。
         *
         * ## 为什么需要这次迁移
         *
         * 「重新生成」原本会再插一条 assistant 消息，重试三次就有四条并排的
         * 回答 —— 前面几条是垃圾，而且都会进上下文被送给模型。
         * 变体模型把同一位置的多次回答归到一组，只显示当前选中的那条。
         *
         * ## 两列都要可空且默认 NULL
         *
         * 老消息不属于任何变体组。若给 `NOT NULL DEFAULT ''`，
         * 「不属于任何组」就会和「组 id 是空串」混成同一种表示 ——
         * 查询时得多写一个 `AND variantGroupId != ''`，而且忘写就会
         * 把所有普通消息当成同一组。用 NULL 让语义只有一种。
         *
         * ## 为什么不用回填
         *
         * `displayOrder` 那次必须回填，是因为全 0 会让 `ORDER BY` 失去区分度。
         * 这里不同：NULL 本身就是正确的最终值（老消息确实没有变体），
         * 不需要任何回填。
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `variantGroupId` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `variantIndex` INTEGER DEFAULT NULL")
                // 索引名/列顺序必须与 `MessageEntity` 的 `@Index` 声明逐字一致，
                // 否则 Room 启动时的 schema 校验会判「迁移没做对」。
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_sessionId_variantGroupId` " +
                        "ON `messages` (`sessionId`, `variantGroupId`)",
                )
            }
        }
    }
}