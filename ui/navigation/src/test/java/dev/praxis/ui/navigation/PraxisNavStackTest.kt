package dev.praxis.ui.navigation

import dev.praxis.domain.model.PraxisDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import org.junit.Test

/**
 * 导航栈操作的纯函数测试。
 *
 * 不用 compose 测试框架：这些函数只碰 [NavBackStack]（本质是 SnapshotStateList），
 * 在纯 JVM 里直接跑即可，快且不 flaky。
 *
 * 四条关键保证：
 *  1. 压栈幂等（重复 key 会让 NavDisplay 直接抛异常）；
 *  2. 出栈保底一层（空栈会让 NavDisplay 抛异常）；
 *  3. 切域不累积 Home（否则连点底栏会把栈撑爆）；
 *  4. 未知 domainId 降级到对话域而不是抛。
 */
class PraxisNavStackTest {

    private fun stack() = navBackStackOf(PraxisRoutes.home(PraxisDomain.CHAT))

    @Test
    fun `push adds a new route`() {
        val s = stack()
        pushRoute(s, PraxisRoutes.detail(PraxisDomain.SETTINGS, "appearance", "外观"))

        assertEquals(2, s.size)
        assertEquals(
            PraxisRoutes.detail(PraxisDomain.SETTINGS, "appearance", "外观"),
            s.currentRoute(),
        )
    }

    @Test
    fun `push is idempotent for the same route`() {
        val s = stack()
        val target = PraxisRoutes.detail(PraxisDomain.SETTINGS, "appearance")

        pushRoute(s, target)
        pushRoute(s, target)

        // 双击设置项不该把同一个 key 入栈两次：miuix-nav 会以重复 contentKey 抛异常。
        assertEquals(2, s.size)
    }

    @Test
    fun `pop keeps at least one entry`() {
        val s = stack()

        popRoute(s)      // 栈深 1，不该动
        assertEquals(1, s.size)

        pushRoute(s, PraxisRoutes.detail(PraxisDomain.SETTINGS, "about"))
        popRoute(s)      // 栈深 2，退一层
        assertEquals(1, s.size)
        assertEquals(PraxisRoutes.home(PraxisDomain.CHAT), s.currentRoute())
    }

    @Test
    fun `switchDomain pops sub pages and reuses existing home`() {
        val s = stack()
        pushRoute(s, PraxisRoutes.detail(PraxisDomain.CHAT, "list", "会话列表"))
        pushRoute(s, PraxisRoutes.detail(PraxisDomain.CHAT, "s1", "会话"))

        switchDomain(s, PraxisDomain.CHAT.id)

        // 回到已有的 Chat Home，栈深收敛到 1，不新增 Home。
        assertEquals(1, s.size)
        assertEquals(PraxisRoutes.home(PraxisDomain.CHAT), s.currentRoute())
    }

    @Test
    fun `switchDomain replaces top when the target home is absent`() {
        val s = stack()

        switchDomain(s, PraxisDomain.SETTINGS.id)

        assertEquals(1, s.size)
        assertEquals(PraxisRoutes.home(PraxisDomain.SETTINGS), s.currentRoute())
    }

    @Test
    fun `switchDomain seeds an empty stack`() {
        val s = navBackStackOf()

        switchDomain(s, PraxisDomain.MEMORY.id)

        assertEquals(1, s.size)
        assertEquals(PraxisRoutes.home(PraxisDomain.MEMORY), s.currentRoute())
    }

    @Test
    fun `ensureSeeded fills an empty stack only`() {
        val empty = navBackStackOf()
        empty.ensureSeeded()
        assertEquals(1, empty.size)

        val filled = stack()
        filled.ensureSeeded()
        assertEquals(1, filled.size)
    }

    @Test
    fun `ensureSeeded is idempotent across repeated calls`() {
        // 生产接线点会在每次重组时执行，必须能安全重入。
        val s = navBackStackOf()
        repeat(5) { s.ensureSeeded() }

        assertEquals(1, s.size)
        assertEquals(PraxisRoutes.home(PraxisDomain.CHAT), s.currentRoute())
    }

    @Test
    fun `ensureSeeded honors a custom seed`() {
        val s = navBackStackOf()
        s.ensureSeeded(PraxisRoutes.home(PraxisDomain.SETTINGS))

        assertEquals(PraxisRoutes.home(PraxisDomain.SETTINGS), s.currentRoute())
    }

    @Test
    fun `ensureSeeded never overwrites a non-empty stack`() {
        // 关键：恢复出来的栈若只有一个子页，不能被 seed 顶掉。
        val s = navBackStackOf()
        val only = PraxisRoutes.detail(PraxisDomain.CHAT, "list", "会话列表")
        s.add(only)

        s.ensureSeeded()

        assertEquals(1, s.size)
        assertEquals(only, s.currentRoute())
    }

    @Test
    fun `currentRoute is null for a non-Praxis key`() {
        val s = navBackStackOf()
        assertNull(s.currentRoute())
    }

    @Test
    fun `unknown domain id degrades to chat`() {
        // 枚举被改名、或旧版本存的栈：不抛异常，回落对话域。
        val route = PraxisRoute.Detail(domainId = "domain-was-renamed", arg = "x")

        assertEquals(PraxisDomain.CHAT, route.domain)
        assertTrue(route.domainId == "domain-was-renamed")
    }

    @Test
    fun `route factory encodes domain as stable id`() {
        // 序列化里存 id 而不是枚举名：这是跨版本可恢复的前提。
        assertEquals("settings", PraxisRoutes.home(PraxisDomain.SETTINGS).domainId)
        assertEquals("chat", PraxisRoutes.detail(PraxisDomain.CHAT, "list").domainId)
    }
}
