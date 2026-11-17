package dev.praxis.core.gateway.adapters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 登录流程判定：把网关三类响应翻成 UI 状态。 */
class LoginFlowRulesTest {

    private fun method(
        index: Int = 0,
        manual: Boolean = false,
        input: String = "url",
    ) = LoginMethod(methodIndex = index, label = "方式" + index, manualCallback = manual, callbackInput = input)

    @Test
    fun `done 且非 pending 才算成功`() {
        // 只判 done 会把「pending 里也带 done」误判成成功 —— 这是网关响应的真实形状。
        val phase = LoginFlowRules.next(
            wait = LoginWait(done = true, pending = false, accountId = "acc-1"),
            method = method(), state = "s1", elapsedMs = 1000L,
        )
        assertEquals(LoginPhase.Succeeded("acc-1"), phase)
    }

    @Test
    fun `done 但 pending 仍算等待`() {
        val phase = LoginFlowRules.next(
            wait = LoginWait(done = true, pending = true),
            method = method(), state = "s1", elapsedMs = 1000L,
        )
        assertTrue(phase is LoginPhase.Waiting)
    }

    @Test
    fun `done 带 error 是失败`() {
        val phase = LoginFlowRules.next(
            wait = LoginWait(done = true, pending = false, error = "授权被拒绝"),
            method = method(), state = "s1", elapsedMs = 1000L,
        )
        assertEquals(LoginPhase.Failed("授权被拒绝"), phase)
    }

    @Test
    fun `超过五分钟判超时且文案给出下一步`() {
        val phase = LoginFlowRules.next(
            wait = LoginWait(done = false, pending = true),
            method = method(manual = true), state = "s1",
            elapsedMs = LoginFlowRules.TIMEOUT_MS + 1,
        )
        assertTrue(phase is LoginPhase.Failed)
        val message = (phase as LoginPhase.Failed).message
        assertTrue("要告诉用户粘回调地址：" + message, message.contains("回调"))
        assertTrue("要提到浏览器与网关不同机：" + message, message.contains("地址栏"))
    }

    @Test
    fun `需要粘回调的方式在 20 秒后主动给入口`() {
        val phase = LoginFlowRules.next(
            wait = LoginWait(done = false, pending = true, hint = "请粘贴回调"),
            method = method(manual = true), state = "s1",
            elapsedMs = LoginFlowRules.MANUAL_HINT_AFTER_MS + 1,
        )
        assertTrue(phase is LoginPhase.NeedCallback)
        val need = phase as LoginPhase.NeedCallback
        assertEquals("s1", need.state)
        assertEquals("请粘贴回调", need.hint)
        assertFalse("url 型不需要 code", need.wantsCode)
    }

    @Test
    fun `code 型回调 wantsCode 为真`() {
        val phase = LoginFlowRules.next(
            wait = LoginWait(done = false, pending = true),
            method = method(manual = true, input = "code"), state = "s1",
            elapsedMs = LoginFlowRules.MANUAL_HINT_AFTER_MS + 1,
        )
        assertTrue((phase as LoginPhase.NeedCallback).wantsCode)
    }

    @Test
    fun `不需要粘回调的方式永远不进 NeedCallback`() {
        val phase = LoginFlowRules.next(
            wait = LoginWait(done = false, pending = true),
            method = method(manual = false), state = "s1",
            elapsedMs = LoginFlowRules.MANUAL_HINT_AFTER_MS + 1,
        )
        assertTrue(phase is LoginPhase.Waiting)
    }

    @Test
    fun `methods 解析真实响应形状`() {
        val body = """
        {
          "methods": [
            {"methodIndex":0,"label":"网页登录","manualCallback":true,"callbackInput":"url"},
            {"methodIndex":3,"label":"粘贴令牌","manualCallback":false,"callbackInput":"code","localOnly":true}
          ],
          "instructions": "选一种方式"
        }
        """.trimIndent()
        val parsed = LoginMethods.parse(body)
        assertEquals(2, parsed.methods.size)
        assertEquals(0, parsed.methods[0].methodIndex)
        assertTrue(parsed.methods[0].manualCallback)
        assertEquals(3, parsed.methods[1].methodIndex)
        assertEquals("code", parsed.methods[1].callbackInput)
        assertTrue(parsed.methods[1].localOnly)
        assertEquals("选一种方式", parsed.instructions)
    }

    @Test
    fun `没有 methods 时返回空清单而不是抛`() {
        assertEquals(0, LoginMethods.parse("{}").methods.size)
    }

    @Test
    fun `wait 解析 session 里的账号 id`() {
        val body = """{"done":true,"session":{"accountId":"acc-9"}}"""
        val wait = LoginWait.parse(body)
        assertTrue(wait.done)
        assertEquals("acc-9", wait.accountId)
    }

    @Test
    fun `wait 解析 pending 里的 hint`() {
        val body = """{"done":false,"pending":{"hint":"在浏览器里完成授权"}}"""
        val wait = LoginWait.parse(body)
        assertFalse(wait.done)
        assertEquals("在浏览器里完成授权", wait.hint)
    }
}