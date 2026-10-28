package dev.praxis.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `MIGRATION_7_8` 的验证测试（护栏 G2）。
 *
 * ## 为什么必须有这个测试
 *
 * v8 加 `spaceId` 列时，建表语句一度写成
 * `\`spaceId\` TEXT NOT NULL DEFAULT ''`。那一行在静态审阅里看起来完全正确，
 * 但它会让应用**启动即崩**：
 *
 *  - Room 在 `build()` 时会拿「实体生成的 schema」与「实际库的 schema」比对，
 *    连 `defaultValue` 都比；
 *  - 本项目所有实体字段的 Kotlin 默认值**不会**变成 SQL `DEFAULT`
 *    （`app/schemas` 下 7 个导出文件里没有任何一列带 defaultValue），
 *    于是迁移建出来的表与 Room 期望的表不一致；
 *  - 结果是 `IllegalStateException: Migration didn't properly handle: memories`，
 *    在 `PraxisDatabase` 第一次被打开时抛出 —— 用户看到的是启动闪退。
 *
 * 这类错误**只能**用 `MigrationTestHelper` 抓住：它用真实 SQLite 跑一遍
 * 迁移，再让 Room 校验结果。纯 JVM 单测（`test/`）里的 SQLite 是假的，
 * 校验不到这一步。
 *
 * ## 为什么从 7 起
 *
 * `app/schemas/dev.praxis.data.db.PraxisDatabase/` 现有 1/2/3/4/5/7.json，
 * **缺 6.json**（当时没导出）。迁移测试需要「起始版本的 schema 文件」，
 * 所以只能用 7。这不影响覆盖目标：要验的就是 7 → 8。
 *
 * ## `runMigrationsAndValidate` 在做什么
 *
 * 1. 用 `7.json` 建一个 v7 的库；
 * 2. 跑 `MIGRATION_7_8`；
 * 3. 拿「实体当前生成的 schema」校验结果，不一致就 fail。
 *
 * 所以只要有人把迁移语句写歪（多一个 DEFAULT、少一个索引、列顺序错），
 * 这个测试立刻红 —— 不必等装机。
 */
@RunWith(AndroidJUnit4::class)
class PraxisDatabaseMigrationTest {

    private val DB_NAME = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PraxisDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    /**
     * 7 → 8 能跑通，且迁移后 `memories` 可用。
     */
    @Test
    fun migrate7To8_createsMemoriesWithSpaceId() {
        // 1) 造出 v7 的库。
        helper.createDatabase(DB_NAME, 7).apply { close() }

        // 2) 跑迁移 + 让 Room 校验 schema。
        //    校验失败会直接抛异常，不需要额外断言。
        helper.runMigrationsAndValidate(
            DB_NAME,
            8,
            true,
            PraxisDatabase.MIGRATION_7_8,
        ).use { db ->
            // 3) 迁移是「故意删表重建」：老记忆一律丢弃（用户明确要求不迁移）。
            db.query("SELECT COUNT(*) FROM memories").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("迁移后 memories 应为空表", 0, cursor.getInt(0))
            }
            // 4) 新列必须存在且可写：写一行带 spaceId 的记录再读回来。
            db.execSQL(
                "INSERT INTO memories (content, tags, createdAt, lastUsedAt, hitCount, spaceId) " +
                    "VALUES ('probe', '', 1, 1, 0, 'work')",
            )
            db.query("SELECT spaceId FROM memories").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("work", cursor.getString(0))
            }
            // 5) 三个索引都要在（少一个 Room 校验也会报，这里额外确认语义）。
            db.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'memories'",
            ).use { cursor ->
                val names = buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
                assertTrue(
                    "缺少 index_memories_lastUsedAt，实际=$names",
                    names.contains("index_memories_lastUsedAt"),
                )
                assertTrue(
                    "缺少 index_memories_createdAt，实际=$names",
                    names.contains("index_memories_createdAt"),
                )
                assertTrue(
                    "缺少 index_memories_spaceId_lastUsedAt，实际=$names",
                    names.contains("index_memories_spaceId_lastUsedAt"),
                )
            }
        }
    }

    /**
     * 迁移后 `spaceId` 的默认值必须是空串（而非 NULL）。
     *
     * 列声明是 `TEXT NOT NULL` 且实体字段默认 `""`，所以插入时不写
     * `spaceId` 也应当成立 —— 若不成立，说明有人给列加了 `DEFAULT NULL`
     * 或者把 `NOT NULL` 去掉了，两种都会让 `MemoryItem.spaceId` 变成 null。
     */
    @Test
    fun migrate7To8_spaceIdDefaultsToEmptyNotNull() {
        helper.createDatabase(DB_NAME, 7).apply { close() }
        helper.runMigrationsAndValidate(DB_NAME, 8, true, PraxisDatabase.MIGRATION_7_8)
            .use { db ->
                db.execSQL(
                    "INSERT INTO memories (content, tags, createdAt, lastUsedAt, hitCount) " +
                        "VALUES ('no-space', '', 1, 1, 0)",
                )
                db.query("SELECT spaceId FROM memories").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    // 未写 spaceId 时应得到空串（不是 null）。
                    assertEquals("", cursor.getString(0))
                }
            }
    }
}
