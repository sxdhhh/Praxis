package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 会话粘性表：键→账号、TTL、容量、空键。 */
class StickyAccountStoreTest {

    @Test
    fun `存取往返`() {
        val store = StickyAccountStore()
        store.put("c:abc", "acct-1")
        assertEquals("acct-1", store.get("c:abc"))
    }

    @Test
    fun `过期后取不到且条目被清`() {
        var clock = 0L
        val store = StickyAccountStore(ttlMillis = 1000L, now = { clock })
        store.put("k", "a")
        clock = 500L
        assertEquals("a", store.get("k"))
        clock = 1001L
        assertNull("超时必须取不到", store.get("k"))
        // 再放一个新条目：过期的那个已被清掉，不会撑表。
        store.put("k2", "b")
        assertEquals(1, store.size())
    }

    @Test
    fun `超限淘汰最旧`() {
        val store = StickyAccountStore(ttlMillis = Long.MAX_VALUE, maxEntries = 2)
        store.put("k1", "a")
        store.put("k2", "b")
        store.put("k3", "c")
        assertNull("最旧的被淘汰", store.get("k1"))
        assertEquals("b", store.get("k2"))
        assertEquals("c", store.get("k3"))
    }

    @Test
    fun `空键与空账号直接忽略`() {
        val store = StickyAccountStore()
        store.put(null, "a")
        store.put("  ", "a")
        store.put("k", "  ")
        assertEquals(0, store.size())
        assertNull(store.get(null))
        assertNull(store.get("k"))
    }

    @Test
    fun `同一键覆盖为最新账号`() {
        val store = StickyAccountStore()
        store.put("k", "a")
        store.put("k", "b")
        assertEquals("b", store.get("k"))
        assertEquals(1, store.size())
    }
}