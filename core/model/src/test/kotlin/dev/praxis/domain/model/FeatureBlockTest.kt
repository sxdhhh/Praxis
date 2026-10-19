package dev.praxis.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 功能清单是 Praxis 与 Operit 之间的「功能契约」，改动它等于改产品面。
 * 这里把几条不变量钉住，防止后续加域/加块时悄悄破坏路由。
 */
class FeatureBlockTest {

    @Test
    fun `every domain declares at least one block`() {
        PraxisDomain.entries.forEach { domain ->
            assertTrue(
                "域 ${domain.id} 没有任何功能块",
                domain.blocks.isNotEmpty(),
            )
        }
    }

    @Test
    fun `block ids are globally unique`() {
        val duplicates = PraxisDomain.entries
            .flatMap { it.blocks }
            .groupBy { it.id }
            .filterValues { it.size > 1 }
            .keys

        assertTrue("功能块 id 重复：$duplicates", duplicates.isEmpty())
    }

    @Test
    fun `blockOf resolves known id and rejects unknown id`() {
        assertNotNull(PraxisDomain.CHAT.blockOf("sessions"))
        assertEquals("会话列表", PraxisDomain.CHAT.blockOf("sessions")?.title)
        assertNull(PraxisDomain.CHAT.blockOf("not-a-block"))
    }

    @Test
    fun `domain lookup round-trips through stable id`() {
        PraxisDomain.entries.forEach { domain ->
            assertEquals(domain, PraxisDomain.byId(domain.id))
        }
        assertNull(PraxisDomain.byId(null))
        assertNull(PraxisDomain.byId("chat-v2"))
    }

    @Test
    fun `domain ids stay unique after future edits`() {
        val ids = PraxisDomain.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertFalse(ids.contains(""))
    }
}