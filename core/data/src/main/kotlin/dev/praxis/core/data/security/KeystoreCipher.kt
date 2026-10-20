package dev.praxis.core.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 封装的服务商密钥加解密器。
 *
 * ## 为什么不用 EncryptedSharedPreferences
 *
 * 那个库依赖 Tink，在多 ROM（尤其改过 Security Provider 的国产系统）出现过
 * 初始化直接抛 `GeneralSecurityException` 的案例，后果是**整个设置页打不开**。
 * 一个「为了安全」的组件让 App 起不来，是不可接受的失效模式。
 *
 * 这里只用手写 20 行的 Keystore + AES/GCM：
 *  - 密钥由系统 Keystore 生成并保管，**从不离开** Keystore（应用只能拿句柄）；
 *  - 即便整个 data/data 被 root dump，没 Keystore 内的密钥也解不出明文；
 *  - 无第三方依赖。
 *
 * ## 失败时降级而非崩溃
 *
 * [encrypt] / [decrypt] 失败返回 null。调用方（[SettingsRepository]）据此：
 *  - 加密失败 → 存明文（老行为），功能可用；
 *  - 解密失败 → 视为「没有密钥」，让用户重填。
 *
 * 宁可退回可用状态，也不要让一个加密组件把 App 拦在门外。
 *
 * ## IV 必须每次随机
 *
 * GCM 的致命错误是 IV 重用（同一密钥 + 同一 IV 会泄露密钥流）。
 * 所以每次加密都让系统生成随机 IV，把它与密文一起存。
 */
class KeystoreCipher(
    private val alias: String = DEFAULT_ALIAS,
) {

    /** 加密。失败返回 null（调用方降级存明文）。 */
    fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        ENCODER.encodeToString(iv) + SEPARATOR +
            ENCODER.encodeToString(encrypted)
    }.getOrNull()

    /** 解密。失败返回 null（调用方视作「无密钥」）。 */
    fun decrypt(encoded: String): String? = runCatching {
        val sep = encoded.indexOf(SEPARATOR)
        if (sep <= 0) return@runCatching null

        val iv = DECODER.decode(encoded.substring(0, sep))
        val payload = DECODER.decode(encoded.substring(sep + 1))

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        String(cipher.doFinal(payload), Charsets.UTF_8)
    }.getOrNull()

    /** 取或建密钥。Keystore 内已存在则复用。 */
    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // 不要求用户认证：应用启动后自动可用。
                // 要求解锁才能读会让「后台自动重试」类场景全部失效。
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "praxis_api_key_v1"

        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        /** 存 `base64(iv):base64(ciphertext)`，用冒号分隔（Base64 不含冒号）。 */
        private const val SEPARATOR = ":"

        /**
         * 用 JDK 标准 Base64 而不是 android.util.Base64。
         *
         * 后者在纯 JVM 单测里是 stub，调用直接抛 "not mocked"，
         * 导致 [looksEncrypted] 永远返回 false —— 判据无法被测试锁住。
         * java.util.Base64 自 Android API 26 起可用（本项目 minSdk 28）。
         */
        private val ENCODER: Base64.Encoder = Base64.getEncoder()
        private val DECODER: Base64.Decoder = Base64.getDecoder()

        /**
         * 判断一个已存值是否是本类产生的密文。
         *
         * 用于旧数据迁移：v1 直接存明文，v2 起存密文。
         * 判据是「含分隔符且两段都能 Base64 解出」；太弱的判据会把
         * 恰好含冒号的明文密钥误当密文。
         */
        fun looksEncrypted(value: String): Boolean {
            val sep = value.indexOf(SEPARATOR)
            if (sep <= 0 || sep == value.length - 1) return false
            return runCatching {
                DECODER.decode(value.substring(0, sep))
                DECODER.decode(value.substring(sep + 1))
                true
            }.getOrDefault(false)
        }
    }
}
