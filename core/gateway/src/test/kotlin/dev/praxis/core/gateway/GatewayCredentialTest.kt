package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 凭证模型与编解码（文档 6.1 的 credentialsCipher 形态）。
 *
 * ## 为什么「空凭证」必须报错
 *
 * 解不出来时如果返回一个空的 ApiKey，下游会认为「这条账号有凭证但内容是空」，
 * 于是 401 之后按「凭证过期」去续期、去标 needsRelogin —— 实际上数据本身就坏了，
 * 该做的是让用户重填。
 */
class GatewayCredentialTest {

    private fun accountFile(json: String): GatewayAccountFile =
        GatewayImporter.parse(json).getOrThrow()

    private val apiKeyFile = accountFile("""
        {
          "format": "agent2api-account",
          "id": "a1",
          "provider": "openai",
          "name": "工作号",
          "apiKey": "sk-1"
        }
    """.trimIndent())

    private val oauthFile = accountFile("""
        {
          "format": "agent2api-account",
          "id": "a2",
          "provider": "openai",
          "name": "OAuth 号",
          "accessToken": "at-1",
          "refreshToken": "rt-1"
        }
    """.trimIndent())

    @Test
    fun 有_apiKey_就是_API_Key_型() {
        assertEquals(GatewayCredential.ApiKey("sk-1"), apiKeyFile.toCredential().getOrThrow())
    }

    @Test
    fun 只有_accessToken_时是_OAuth_型() {
        assertEquals(
            GatewayCredential.OAuth(accessToken = "at-1", refreshToken = "rt-1"),
            oauthFile.toCredential().getOrThrow(),
        )
    }

    @Test
    fun 两种凭证都没有时报错并指出是哪个账号() {
        val empty = accountFile("""
            { "format": "agent2api-account", "id": "a3", "provider": "openai", "name": "空号" }
        """.trimIndent())

        val result = empty.toCredential()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("a3"))
    }

    @Test
    fun 编解码往返_API_Key() {
        val credential = GatewayCredential.ApiKey("sk-1")

        assertEquals(credential, GatewayCredentialCodec.decode(GatewayCredentialCodec.encode(credential)).getOrThrow())
    }

    @Test
    fun 编解码往返_OAuth_带刷新令牌() {
        val credential = GatewayCredential.OAuth(accessToken = "at", refreshToken = "rt")

        assertEquals(credential, GatewayCredentialCodec.decode(GatewayCredentialCodec.encode(credential)).getOrThrow())
    }

    @Test
    fun 编解码往返_OAuth_不带刷新令牌() {
        val credential = GatewayCredential.OAuth(accessToken = "at")

        val roundTripped = GatewayCredentialCodec.decode(GatewayCredentialCodec.encode(credential)).getOrThrow()

        assertEquals(credential, roundTripped)
        assertNull((roundTripped as GatewayCredential.OAuth).refreshToken)
    }

    @Test
    fun 坏数据报错而不是给出空凭证() {
        assertTrue(GatewayCredentialCodec.decode("not json").isFailure)
        assertTrue(GatewayCredentialCodec.decode("{}").isFailure)
        assertTrue(GatewayCredentialCodec.decode("""
            { "apiKey": "" }
        """.trimIndent()).isFailure)
    }
}
