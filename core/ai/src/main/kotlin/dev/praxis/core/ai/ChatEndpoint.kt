package dev.praxis.core.ai

/**
 * 把用户填的 baseUrl 拼成真正的 Chat Completions 端点。
 *
 * ## 为什么单独抽出来
 *
 * 这段逻辑有三个真实的失败史，全部是「拼错地址 → 服务端 404 / 405，但错误信息
 * 是服务端的英文 JSON，用户看不出是地址问题」：
 *
 * 1. 用户照抄文档里的完整 URL（`.../v1/chat/completions`），旧实现再拼一遍，
 *    代理日志里出现 `POST /v1/chat/completions/v1/chat/completions → 404`。
 * 2. 接入服务商预设后，智谱的默认地址是 `.../api/paas/v4`，
 *    旧规则只认「正好以 `/v1` 结尾」，于是拼成 `.../v4/v1/chat/completions` → 404。
 *    这是最坏的情况：**选了预设反而不能用**。
 * 3. 阿里云百炼是 `.../compatible-mode/v1`，同样是不以 `/v1` 开头的一段路径。
 *
 * 抽到纯 JVM 模块的意义：这些分支必须被单测逐个钉住。放在
 * `OpenAiCompatProvider` 里（Android 模块 + OkHttp）就只能靠真机试。
 *
 * ## 规则（四种输入都对）
 *
 * | 输入 | 结果 |
 * |---|---|
 * | `https://api.x.com` | `https://api.x.com/v1/chat/completions` |
 * | `https://api.x.com/v1` | `https://api.x.com/v1/chat/completions` |
 * | `https://api.x.com/v1/chat/completions` | 原样 |
 * | `https://open.bigmodel.cn/api/paas/v4` | `.../api/paas/v4/chat/completions` |
 *
 * ## 判据为什么是「path 末尾是版本段」而不是白名单域名
 *
 * 中转站（one-api / new-api）的路径千奇百怪（`/api/v3`、`/proxy/v1`），
 * 白名单域名覆盖不到。而「最后一段是 `v` + 数字」这个判据既不依赖域名，
 * 也不会把 `/api/paas` 这种没有版本号的路径误判成版本段。
 */
object ChatEndpoint {

    /** OpenAI 兼容协议的对话端点后缀（不含版本段）。 */
    const val CHAT_COMPLETIONS_PATH: String = "/chat/completions"

    /**
     * 拼出真正的请求端点。
     *
     * 空串原样返回空串：调用方会先检查 `isConfigured`，这里不制造假地址
     * （返回 `"/v1/chat/completions"` 这种会让人以为是相对路径问题）。
     */
    fun resolve(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        // 已含完整端点：原样使用。用 endsWith 而不是 contains，
        // 避免把 `.../v1/chat/completions/foo` 这种拼错的地址「修好」——
        // 拼错就让服务端 404，比静默改写成另一个地址更容易排查。
        if (trimmed.endsWith(CHAT_COMPLETIONS_PATH)) return trimmed
        // 已含版本段：只补端点，不再插一层 `/v1`。
        if (endsWithVersionSegment(trimmed)) return trimmed + CHAT_COMPLETIONS_PATH
        // 裸域名：补 /v1 + 端点。
        return "$trimmed/v1$CHAT_COMPLETIONS_PATH"
    }

    /**
     * 判断 URL 的 path 是否已经以版本段结尾。
     *
     * 认的形式：`/v1`、`/v2`、`/v4`、`/v1beta`、`/api/v3`、`/compatible-mode/v1`。
     */
    fun endsWithVersionSegment(url: String): Boolean {
        // 去掉 scheme（`https://`）与 host，只留 path。
        // `substringAfter("://", url)` 在没有 scheme 时原样返回，正好兼容
        // `localhost:8080/v1` 这种不带 scheme 的输入。
        val path = url.substringAfter("://", url).substringAfter('/', "")
        if (path.isEmpty()) return false
        val lastSegment = path.substringAfterLast('/')
        if (!lastSegment.startsWith("v")) return false
        // `v` 后面必须紧跟数字：`/v1beta` 认，`/version` 不认。
        return lastSegment.drop(1).takeWhile { it.isDigit() }.isNotEmpty()
    }
}