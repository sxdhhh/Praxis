package dev.praxis.core.gateway

/**
 * 「网关对外模型清单」：用户在模型页能选的那些名字（文档 9.1 的 gatewayModels）。
 *
 * ## 两个来源合在一起
 *
 * - 各家的原生清单（provider 表的 modelsJson，即账号文件里的 models 数组）
 * - modelRules 里的映射别名（用户自己起的对外名）
 *
 * 两者都可能被用户直接请求，所以都要列出来。只列其中一个，用户就会遇到
 * 「配置里有、选择器里没有」或者反过来。
 *
 * ## 去重按大小写不敏感
 *
 * 文档 5.4：网关全链路 eq_ignore_ascii_case，别名与 id 只差大小写会互相遮蔽。
 * 既然匹配是大小写不敏感的，清单里也不该出现两个只差大小写的名字 —— 那会让
 * 用户以为自己有两条选择，实际是同一个。
 *
 * ## 顺序
 *
 * 原生清单在前（按各家声明顺序），映射别名在后（按配置顺序）。不排序：
 * 用户看到的是「这家有什么、我另外起了什么别名」，排序会把这种结构打散。
 */
object GatewayModelCatalog {

    fun visibleNames(
        nativeModels: List<NativeModel>,
        mappings: List<ModelMapping>,
    ): List<String> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<String>()

        fun add(name: String) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return
            val key = trimmed.lowercase()
            if (!seen.add(key)) return
            result += trimmed
        }

        nativeModels.forEach { add(it.id) }
        mappings.filter { it.enabled }.forEach { add(it.alias) }
        return result
    }
}
