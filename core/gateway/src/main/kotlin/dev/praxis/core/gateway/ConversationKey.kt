package dev.praxis.core.gateway

/**
 * 会话粘性键（文档 7.3）。
 *
 * 规则只有一句：显式头优先，否则内容哈希。
 *
 * ## 为什么必须返回 null 而不是编一个兜底键
 *
 * 没有显式头、也没有内容种子时，这条请求**本来就没有粘性可言**。此时返回一个
 * 常量或随机值，会让「粘性」这个机制在它最不该生效的地方生效：前者把所有无头
 * 请求钉在同一个账号上，后者让每次请求都换一个键，等于没有粘性却还在维护表。
 */
object ConversationKey {

    /** 显式线程头。文档举的例子就是它。 */
    const val THREAD_ID_HEADER: String = "AH-Thread-Id"

    /** 显式头来源的前缀（便于日志里一眼分辨粘性从哪来）。 */
    private const val PREFIX_HEADER = "h:"
    private const val PREFIX_CONTENT = "c:"

    private const val FNV_OFFSET = -3750763034362895579L // 0xcbf29ce484222325 的有符号表示
    private const val FNV_PRIME = 1099511628211L         // 0x100000001b3

    /**
     * @param headers 请求头。名字**大小写不敏感**（HTTP 头本就如此）
     * @param contentSeed 内容哈希的种子（通常是本次请求的首条内容）
     * @return null = 这条请求没有可用的粘性键
     */
    fun of(headers: Map<String, String>, contentSeed: String?): String? {
        val explicit = headers.entries
            .firstOrNull { it.key.equals(THREAD_ID_HEADER, ignoreCase = true) }
            ?.value
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (explicit != null) return PREFIX_HEADER + explicit

        val seed = contentSeed?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return PREFIX_CONTENT + fnv1a(seed)
    }

    /**
     * FNV-1a 64 位。
     *
     * 选它而不是 String.hashCode()：后者只有 32 位且分布很差，粘性表里几条
     * 不同会话就可能撞在一起 —— 撞了的表现是「两个对话抢同一个账号」，
     * 极难排查。这里只需要稳定与够散，不需要密码学强度。
     */
    private fun fnv1a(text: String): String {
        var hash = FNV_OFFSET
        text.toByteArray(Charsets.UTF_8).forEach { byte ->
            hash = hash xor (byte.toLong() and 0xffL)
            hash *= FNV_PRIME
        }
        return java.lang.Long.toHexString(hash)
    }
}
