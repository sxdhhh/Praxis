package dev.praxis.core.data.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 只测 [KeystoreCipher.looksEncrypted]。
 *
 * 为什么不测 encrypt/decrypt：它们要真实 Android Keystore，
 * 纯 JVM 跑不起来（Robolectric 也不提供 Keystore 实现）。
 * 而那些逻辑的失败已被「返回 null + 调用方降级」包住，
 * 真正需要锁死的是 **旧数据判据**——判错会导致明文密钥被当密文送去解密，
 * 结果是用户的 key 凭空消失。
 */
class KeystoreCipherTest {

    @Test
    fun `value with separator and valid base64 looks encrypted`() {
        // 模拟 iv:payload 形状（两段都是合法 Base64）。
        assertTrue(KeystoreCipher.looksEncrypted("AAAAAAAAAAAAAAAA:BBBBBBBB"))
    }

    @Test
    fun `plain api key is not treated as encrypted`() {
        // 典型真实密钥形状：sk-xxx，无分隔符。
        assertFalse(KeystoreCipher.looksEncrypted("sk-abcdef1234567890"))
        assertFalse(KeystoreCipher.looksEncrypted("any-plain-value"))
    }

    @Test
    fun `trailing or leading separator is not encrypted`() {
        assertFalse(KeystoreCipher.looksEncrypted(":payload"))
        assertFalse(KeystoreCipher.looksEncrypted("iv:"))
        assertFalse(KeystoreCipher.looksEncrypted(":"))
    }

    @Test
    fun `non base64 halves are not encrypted`() {
        // 含分隔符但内容不是 Base64（例如带空格/中文的明文）。
        assertFalse(KeystoreCipher.looksEncrypted("不是 base64:也不是"))
    }

    @Test
    fun `empty string is not encrypted`() {
        assertFalse(KeystoreCipher.looksEncrypted(""))
    }
}
