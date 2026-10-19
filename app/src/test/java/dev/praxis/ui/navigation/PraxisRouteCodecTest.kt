package dev.praxis.ui.navigation

import dev.praxis.domain.model.PraxisDomain
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 路由编解码的往返测试。
 *
 * 这一层坏掉的后果是「旋转屏幕 / 进程被回收后回到错误页面」，
 * 真机上很难复现，只能靠单测钉住。
 *
 * 注意：直接测 [encodeRoute] / [decodeRoute] 这两个纯函数，
 * 不去碰 Compose 的 Saver 机制（那部分属于框架自身的行为）。
 */
class PraxisRouteCodecTest {

    @Test
    fun `home route round-trips`() {
        val original = PraxisRoute.Home(PraxisDomain.MEMORY)

        assertEquals(original, decodeRoute(encodeRoute(original)))
    }

    @Test
    fun `detail route round-trips with arg and title`() {
        val original = PraxisRoute.Detail(
            domain = PraxisDomain.SETTINGS,
            arg = "appearance",
            title = "外观",
        )

        assertEquals(original, decodeRoute(encodeRoute(original)))
    }

    @Test
    fun `encoded payload is a flat string list`() {
        val encoded = encodeRoute(PraxisRoute.Home(PraxisDomain.CHAT))

        assertEquals(2, encoded.size)
        assertEquals("home", encoded[0])
        assertEquals("chat", encoded[1])
    }

    @Test
    fun `unknown domain id falls back to chat`() {
        assertEquals(
            PraxisRoute.Home(PraxisDomain.CHAT),
            decodeRoute(listOf("home", "domain-was-renamed")),
        )
    }

    @Test
    fun `corrupted payload degrades instead of throwing`() {
        // 空列表、未知类型标记、detail 缺参数：都只降级，不抛异常。
        assertEquals(PraxisRoute.Home(PraxisDomain.CHAT), decodeRoute(emptyList()))
        assertEquals(
            PraxisRoute.Home(PraxisDomain.TOOLS),
            decodeRoute(listOf("unknown-kind", "tools")),
        )
        assertEquals(
            PraxisRoute.Detail(PraxisDomain.TOOLS, "", ""),
            decodeRoute(listOf("detail", "tools")),
        )
    }
}