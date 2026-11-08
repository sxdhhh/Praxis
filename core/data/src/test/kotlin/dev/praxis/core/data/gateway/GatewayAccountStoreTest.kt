package dev.praxis.core.data.gateway

import dev.praxis.core.gateway.GatewayCredential
import dev.praxis.core.gateway.GatewayImporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账号池落库（文档 8.6 幂等 + 8.7 安全）。
 *
 * ## 最要紧的两条
 *
 * 1. 写进库的必须是密文 —— 而且要能证明「失败时一条都不写」，
 *    否则用户会拿到一个写了一半的仓库。
 * 2. 重复导入不能覆盖用户的手动选择（停用、顺序）。
 */
class GatewayAccountStoreTest {

    private class FakeAccountDao : GatewayAccountDao {
        val rows = LinkedHashMap<String, GatewayAccountEntity>()
        override suspend fun all() = rows.values.toList()
        override suspend fun byId(id: String) = rows[id]
        override suspend fun allIds() = rows.keys.toList()
        override suspend fun upsert(account: GatewayAccountEntity) { rows[account.id] = account }
        override suspend fun delete(id: String) { rows.remove(id) }
        override suspend fun clear() { rows.clear() }
    }

    private class FakeProviderDao : GatewayProviderDao {
        val rows = LinkedHashMap<String, GatewayProviderEntity>()
        override suspend fun all() = rows.values.toList()
        override suspend fun byId(id: String) = rows[id]
        override suspend fun upsert(provider: GatewayProviderEntity) { rows[provider.id] = provider }
        override suspend fun delete(id: String) { rows.remove(id) }
    }

    private val accountDao = FakeAccountDao()
    private val providerDao = FakeProviderDao()
    private var now = 1000L

    private fun store(encrypt: (String) -> String? = { "enc:" + it }, decrypt: (String) -> String? = { it.removePrefix("enc:") }) =
        GatewayAccountStore(
            accountDao = accountDao,
            providerDao = providerDao,
            encrypt = encrypt,
            decrypt = decrypt,
            clock = { now },
        )

    private fun plan(vararg ids: String) = GatewayImporter.prepare(
        files = ids.associate { id ->
            id + ".json" to """
                {
                  "format": "agent2api-account",
                  "id": "$id",
                  "provider": "openai",
                  "name": "号-$id",
                  "apiKey": "sk-$id"
                }
            """.trimIndent()
        },
        existingIds = emptySet(),
        knownProviders = setOf("openai"),
    )

    @Test
    fun 落库的是密文不是明文() = runBlocking {
        store().applyImport(plan("a1")).getOrThrow()

        val row = accountDao.rows.getValue("a1")

        assertTrue(row.credentialsCipher.startsWith("enc:"))
        assertFalse("明文不该出现在库里", row.credentialsCipher.contains("sk-a1"))
    }

    @Test
    fun 加密失败时一条都不写() = runBlocking {
        val result = store(encrypt = { null }).applyImport(plan("a1", "a2"))

        assertTrue(result.isFailure)
        assertTrue("半途写入的仓库用户没法收拾", accountDao.rows.isEmpty())
        assertTrue(result.exceptionOrNull()!!.message!!.contains("a1"))
    }

    @Test
    fun 重复导入不覆盖用户的停用与顺序() = runBlocking {
        store().applyImport(plan("a1", "a2")).getOrThrow()
        // 用户之后手动停用 a1 并把它拖到第二位
        accountDao.upsert(accountDao.rows.getValue("a1").copy(enabled = false, priority = 7, createdAt = 42L))

        now = 2000L
        store().applyImport(plan("a1", "a2")).getOrThrow()

        val row = accountDao.rows.getValue("a1")
        assertFalse("手动停用不该被导入重新打开", row.enabled)
        assertEquals(7, row.priority)
        assertEquals("添加时间不该被导入改写", 42L, row.createdAt)
    }

    @Test
    fun 新账号按导入顺序给优先级() = runBlocking {
        store().applyImport(plan("b1", "b2")).getOrThrow()

        assertEquals(0, accountDao.rows.getValue("b1").priority)
        assertEquals(1, accountDao.rows.getValue("b2").priority)
    }

    @Test
    fun 能读回原凭证() = runBlocking {
        store().applyImport(plan("c1")).getOrThrow()

        assertEquals(
            GatewayCredential.ApiKey("sk-c1"),
            store().credentialOf("c1").getOrThrow(),
        )
    }

    @Test
    fun 解不开时点名账号而不是给空凭证() = runBlocking {
        store().applyImport(plan("d1")).getOrThrow()

        val result = store(decrypt = { null }).credentialOf("d1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("d1"))
    }

    @Test
    fun 没有这个账号时明确报错() = runBlocking {
        assertTrue(store().credentialOf("nobody").isFailure)
    }

    @Test
    fun 带地址的自定义家落库并保留模型清单() = runBlocking {
        val withProvider = """
            {
              "format": "agent2api-account",
              "id": "e1",
              "provider": "custom-abc",
              "name": "一号",
              "apiKey": "sk-e1",
              "customProviders": [
                { "id": "custom-abc", "name": "我的中转", "baseUrl": "https://x/v1", "models": ["m1", "m2"] }
              ]
            }
        """.trimIndent()

        val importPlan = GatewayImporter.prepare(
            files = mapOf("e.json" to withProvider),
            existingIds = emptySet(),
            knownProviders = setOf("openai"),
        )

        val stored = store().applyImport(importPlan).getOrThrow()

        assertEquals(listOf("custom-abc"), stored.providerIds)
        val row = providerDao.rows.getValue("custom-abc")
        assertEquals("https://x/v1", row.baseUrl)
        assertEquals("m1" + "\n" + "m2", row.modelsJson)
    }

    @Test
    fun 没有地址的自定义家不落库但被报出() = runBlocking {
        val noAddress = """
            {
              "format": "agent2api-account",
              "id": "f1",
              "provider": "custom-xyz",
              "name": "二号",
              "apiKey": "sk-f1",
              "customProviders": [ { "id": "custom-xyz", "name": "没地址的家" } ]
            }
        """.trimIndent()

        val importPlan = GatewayImporter.prepare(
            files = mapOf("f.json" to noAddress),
            existingIds = emptySet(),
            knownProviders = setOf("openai"),
        )

        val stored = store().applyImport(importPlan).getOrThrow()

        // 写进去只会在发请求时以更远的方式炸掉，所以不写、但要说清是哪一个。
        assertTrue(providerDao.rows.isEmpty())
        assertEquals(listOf("custom-xyz"), stored.providersWithoutBaseUrl)
    }
}
