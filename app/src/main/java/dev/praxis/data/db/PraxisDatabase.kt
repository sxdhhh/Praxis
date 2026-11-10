package dev.praxis.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.praxis.core.data.character.CharacterCardEntity
import dev.praxis.core.data.character.PromptTagEntity
import dev.praxis.core.data.gateway.GatewayAccountEntity
import dev.praxis.core.data.gateway.GatewayDailyEntity
import dev.praxis.core.data.gateway.GatewayModelEntity
import dev.praxis.core.data.gateway.GatewayRequestEntity
import dev.praxis.core.data.gateway.GatewayProviderEntity

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
        GatewayAccountEntity::class,
        GatewayProviderEntity::class,
        GatewayModelEntity::class,
        // v14：请求记录 + 日聚合（文档 6.1）。报表与 token 预算的数据源。
        GatewayRequestEntity::class,
        GatewayDailyEntity::class,
    ],
    version = 16,
    exportSchema = true,
)
abstract class PraxisDatabase : RoomDatabase(), dev.praxis.core.data.character.CharacterDatabase {

    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao

    // ── 角色系统（实体与 DAO 定义在 :core:data）───────────────
    override abstract fun characterCardDao(): dev.praxis.core.data.character.CharacterCardDao
    override abstract fun promptTagDao(): dev.praxis.core.data.character.PromptTagDao

    // ── 网关账号池（实体与 DAO 定义在 :core:data）───────────────
    abstract fun gatewayAccountDao(): dev.praxis.core.data.gateway.GatewayAccountDao
    abstract fun gatewayProviderDao(): dev.praxis.core.data.gateway.GatewayProviderDao
    abstract fun gatewayModelDao(): dev.praxis.core.data.gateway.GatewayModelDao

    /** 请求记录与日聚合（P3 报表 / token 预算）。 */
    abstract fun gatewayUsageDao(): dev.praxis.core.data.gateway.GatewayUsageDao

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

        /**
         * v8：记忆加多空间（spaceId）。
         *
         * ## 这条迁移是「故意删除」，且是用户明确要求的
         *
         * 本项目从 v3 起禁用破坏性迁移（见 AppModule 的注释），
         * 理由是「记忆被静默清空用户无从得知」。
         * 但那条规则防的是**意外**丢数据；这里是**故意**丢，且写在
         * 迁移里、可审计、可回溯。两者性质不同：前者不可控，
         * 后者是一个明确的产品决定。
         *
         * ## 为什么是 DROP + CREATE 而不是 ALTER ADD COLUMN
         *
         * 只加一列（默认空串）本来就能保留旧数据，但用户明确要求
         * 不迁移、直接删。所以这里确实是重建表。
         *
         * ## 索引名必须与 `MemoryEntity` 的 `@Index` 逐字一致
         *
         * Room 启动时会比对实体声明与实际 schema。差一个字符
         * 就报「迁移没做对」，启动即崩。
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `memories`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memories` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`tags` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`lastUsedAt` INTEGER NOT NULL, " +
                        "`hitCount` INTEGER NOT NULL, " +
                        "`spaceId` TEXT NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_lastUsedAt` ON `memories` (`lastUsedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_createdAt` ON `memories` (`createdAt`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memories_spaceId_lastUsedAt` " +
                        "ON `memories` (`spaceId`, `lastUsedAt`)",
                )
            }
        }

        /**
         * v9：messages 新增 `reasoning`（模型思考过程）。
         *
         * ## 为什么这次可以用最简单的 ADD COLUMN
         *
         * 新列可空、默认 NULL，而 NULL 就是老数据的**正确**语义 ——
         * 老消息确实没有思考记录。所以：
         *
         *  - 不需要重建表（v8 那次 DROP+CREATE 是因为要删数据，性质不同）；
         *  - 不需要回填；
         *  - 不需要新索引（不会按 reasoning 查询或排序）。
         *
         * ## NULL 而不是空串
         *
         * 与 v7 的 variantGroupId 同一个理由：「没有思考」只该有一种表示。
         * 用空串的话 UI 得同时判 `isNullOrEmpty`，忘判就会画出一个空的
         * 折叠块，看起来像坏了。
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `reasoning` TEXT DEFAULT NULL")
            }
        }
        /**
         * v10：排序键从 orderIndex 换成 createdAt（2026-11-07，R6/R7）。
         *
         * ## 为什么需要回填
         *
         * R6 把排序键换成了 createdAt。绝大多数历史数据没问题 ——
         * createdAt 是写入时的 now()，本来就和 orderIndex 同序。
         *
         * 唯一例外是**摘要行**：它由 insertMessageAt 插入，
         * orderIndex 是插入位置，而 createdAt 被写成了当时的 now()
         * = 整个会话最新。切到按 createdAt 排序后，这些摘要会跳到末尾。
         *
         * ## 回填成什么值
         *
         * 设成「它前一条消息的 createdAt」。这样：
         *  - 与前后两条同属一个时间点，顺序由 id 兜底；
         *  - 摘要的 id 比前一条大（后插入），所以排在它之后 —— 正确；
         *  - **不动其它行的时间**，用户看到的消息时间不会变。
         *
         * ## 为什么不用「基准时间 + 序号」统一重排
         *
         * 那会把整段对话压成连续毫秒，用户手机上显示的
         * 「3 天前 / 昨天 / 刚刚」会全部变成同一秒 ——
         * 为了修一个位置问题，破坏了全部时间语义。
         *
         * ## 为什么索引也必须一起换
         *
         * Room 会拿 exportSchema 生成的期望 schema 与实际库比对。
         * 版本号变了但索引没换，启动直接报
         * Migration didn't properly handle，应用起不来。
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ① 摘要行回填。EXISTS 保护「插在开头」的情况：
                //    此时没有前一条，子查询返回 NULL，而 createdAt 是 NOT NULL。
                db.execSQL(
                    "UPDATE `messages` SET `createdAt` = (" +
                        "SELECT `m2`.`createdAt` FROM `messages` AS `m2` " +
                        "WHERE `m2`.`sessionId` = `messages`.`sessionId` " +
                        "AND `m2`.`orderIndex` < `messages`.`orderIndex` " +
                        "ORDER BY `m2`.`orderIndex` DESC LIMIT 1) " +
                        "WHERE `messages`.`role` = 'summary' " +
                        "AND EXISTS (" +
                        "SELECT 1 FROM `messages` AS `m3` " +
                        "WHERE `m3`.`sessionId` = `messages`.`sessionId` " +
                        "AND `m3`.`orderIndex` < `messages`.`orderIndex`)",
                )

                // ② 换索引：排序键变了，(sessionId, orderIndex) 不再被使用。
                db.execSQL("DROP INDEX IF EXISTS `index_messages_sessionId_orderIndex`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_sessionId_createdAt` " +
                        "ON `messages` (`sessionId`, `createdAt`)",
                )
            }
        }

        /**
         * v11：sessions 加 contextUsed / contextLimit（2026-11-07，R10）。
         *
         * ## 为什么可以只 ADD COLUMN
         *
         * 两列都是非空整数、默认 0，而 0 正是老数据的**正确**语义：
         * 「还不知道这个会话用了多少上下文」。不需要回填、不需要重建表。
         *
         * ## 默认值必须写进 DDL
         *
         * Room 会把建表语句与期望 schema 逐字比对。Kotlin 侧写了 `= 0`
         * 而 DDL 里漏掉 `DEFAULT 0`，启动就会报
         * `Migration didn't properly handle` —— 应用直接起不来。
         *
         * ## 为什么不做成 destructive
         *
         * sessions 是 messages 的外键父表（`ON DELETE CASCADE`），
         * 删表重建等于清空用户全部聊天记录。两个默认 0 的列不值得这个代价。
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `contextUsed` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `contextLimit` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v13 -> v14：新增 `gateway_request`（请求记录）与 `gateway_daily`（日聚合）。
         *
         * ## 为什么又是手写，而不是 AutoMigration
         *
         * 与 12->13 同一个原因：`autoMigrations` 里那一条是 `11 -> 13`，Room 不会替
         * 停在 13 的设备补出 13->14；而要改用两段 AutoMigration，又要求 `13.json`
         * 在 schema 目录里，它只在真正构建过 v13 时才生成。
         *
         * ## 两张表都**只加不改**
         *
         * 没有列变更、没有数据搬迁，所以手写 DDL 的风险只在「与实体逐列对齐」。
         * 对齐规则同 12->13：列名、类型、NOT NULL、主键逐字对齐实体定义。
         *
         * ## 真机升级要单独验
         *
         * 迁移正确性只有真机切换版本才暴露（Room 打开库时按 identity hash 校验）。
         * 上次 12->13 就是这样发现问题的 —— 所以这一条上线前必须在真机上跑一次
         * 「旧版本 -> 新版本」的升级，而不只是全新安装。
         */
        /**
         * 14 -> 15：自定义提供商加「额外请求头」与「URL 后缀」两列。
         *
         * 来自上游预设的 quirks（preset-providers.js）。不加这两列就没法照抄
         * 那些家：Cline 的 `X-CLIENT-TYPE` 缺了一律 403，GLM / MiniMax 的
         * Claude 兼容端点要求 `?beta=true`。默认空串 = 与旧行为一致。
         */
        /**
         * 15 -> 16：自定义提供商加「上次成功拉清单的时间」。
         *
         * 文档 §5.4.1 第 17 条要的「快照 + 缓存」：modelsJson 是快照，
         * 这一列是它的时间戳。默认 0 = 从没成功过（一律算不新鲜）。
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `gateway_provider` ADD COLUMN `modelsFetchedAt` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `gateway_provider` ADD COLUMN `extraHeadersJson` TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "ALTER TABLE `gateway_provider` ADD COLUMN `urlSuffix` TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `gateway_request` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`ts` INTEGER NOT NULL, " +
                        "`model` TEXT NOT NULL, " +
                        "`upstreamModel` TEXT, " +
                        "`providerId` TEXT, " +
                        "`accountId` TEXT, " +
                        "`status` INTEGER NOT NULL, " +
                        "`error` TEXT, " +
                        "`attempts` INTEGER NOT NULL, " +
                        "`promptTokens` INTEGER NOT NULL, " +
                        "`completionTokens` INTEGER NOT NULL, " +
                        "`cacheReadTokens` INTEGER NOT NULL, " +
                        "`credit` REAL, " +
                        "`durationMs` INTEGER NOT NULL, " +
                        "`firstResponseMs` INTEGER, " +
                        "`client` TEXT, " +
                        "`isTest` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `gateway_daily` (" +
                        "`day` TEXT NOT NULL, " +
                        "`model` TEXT NOT NULL, " +
                        "`providerId` TEXT NOT NULL, " +
                        "`requests` INTEGER NOT NULL, " +
                        "`failures` INTEGER NOT NULL, " +
                        "`tokens` INTEGER NOT NULL, " +
                        "`cacheRead` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`day`, `model`, `providerId`))",
                )
            }
        }

        /**
         * v11 -> v12：新增 `gateway_account` 与 `gateway_provider` 两张表。
         *
         * ## 为什么它从「自动迁移」改成了手写（2026-11-09）
         *
         * 原来是一条 `AutoMigration(11, 13)` 一步到位。那时没问题：当前版本就是 13。
         * 这次把版本升到 14 之后，KSP 直接报：
         *
         * ```
         * Schema '13.json' required for migration was not found at the schema out folder
         * ```
         *
         * 原因：自动迁移是「拿起点 schema 算出终点」，而 13 已经不是当前版本了，
         * Room 不会再去生成 13.json，也就没东西可算。
         *
         * 于是改成全程手写：11->12、12->13、13->14 各一条。手写链**完全不依赖 schema 文件**，
         * 也就不会再被「某个中间版本的 json 不在仓库里」卡住。
         *
         * 风险仍在「与实体逐列对齐」：列名、类型、NOT NULL、主键都要逐字对上，
         * 否则运行期打开库时报 identity hash 不匹配（编译期看不出来）。
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `gateway_account` (" +
                        "`id` TEXT NOT NULL, " +
                        "`providerId` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`baseUrlOverride` TEXT, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "`priority` INTEGER NOT NULL, " +
                        "`maxConcurrent` INTEGER NOT NULL, " +
                        "`credentialsCipher` TEXT NOT NULL, " +
                        "`expiringAt` INTEGER, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `gateway_provider` (" +
                        "`id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`baseUrl` TEXT NOT NULL, " +
                        "`modelsJson` TEXT NOT NULL, " +
                        "`mappingsJson` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
            }
        }

        /**
         * v12 -> v13：新增 `gateway_model`（结构化模型条目）。
         *
         * ## 为什么这条必须手写，而不能继续靠 [Database.autoMigrations]
         *
         * `AutoMigration(11, 13)` 只覆盖「**从 11 直接升到 13**」的设备。Room **不会**
         * 把它拆成 11->12、12->13 两步。于是装过 v12 那一版的设备（真机上就是这种情况）
         * 打开数据库时报：
         *
         * ```
         * IllegalStateException: A migration from 12 to 13 was required but not found
         * ```
         *
         * 表现为「启动约 3 秒后崩溃」—— 界面已经画出来了，第一条查询才去开库。
         *
         * 改成两段 AutoMigration 也不行：那要求 `12.json` 在 schema 目录里，而它只在
         * 真正构建过 v12 时才会生成。所以这一步手写建表 SQL，11->13 的自动迁移保留。
         *
         * ## SQL 必须与实体逐列一致
         *
         * Room 打开库时用 identity hash 校验表结构。列名、类型、NOT NULL 有一处不对，
         * 表现不是「数据静默丢失」，而是启动即崩并打印期望/实际的结构差异 —— 严格比宽松安全。
         * 列的来源是 `GatewayModelEntity`（列顺序、可空性逐字对齐）。
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `gateway_model` (" +
                        "`provider` TEXT NOT NULL, " +
                        "`id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "`credits` REAL, " +
                        "`supportsToolCall` INTEGER, " +
                        "`supportsImages` INTEGER, " +
                        "`supportsVideo` INTEGER, " +
                        "`supportsReasoning` INTEGER, " +
                        "`supportsImageGeneration` INTEGER, " +
                        "`supportsImageEditing` INTEGER, " +
                        "`inputModalities` TEXT NOT NULL, " +
                        "`maxInputTokens` INTEGER, " +
                        "`maxOutputTokens` INTEGER, " +
                        "`reasoningEfforts` TEXT NOT NULL, " +
                        "`reasoningDefault` TEXT, " +
                        "`reasoningFixed` TEXT, " +
                        "`reasoningSource` TEXT, " +
                        "PRIMARY KEY(`provider`, `id`))",
                )
            }
        }
    }
}