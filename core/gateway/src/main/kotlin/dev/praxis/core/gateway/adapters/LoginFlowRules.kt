package dev.praxis.core.gateway.adapters

/** 登录流程的状态（UI 直接渲染这个）。 */
sealed interface LoginPhase {
    data object Idle : LoginPhase
    /** 正在向网关发起。 */
    data object Starting : LoginPhase
    /** 等用户在浏览器里授权（[url] 非空时用 H5 容器打开）。 */
    data class Waiting(
        val state: String,
        val url: String?,
        val hint: String?,
        val elapsedSeconds: Long,
    ) : LoginPhase
    /** 需要用户把回调地址/码粘回来。 */
    data class NeedCallback(
        val state: String,
        val wantsCode: Boolean,
        val hint: String?,
    ) : LoginPhase
    data class Succeeded(val accountId: String?) : LoginPhase
    data class Failed(val message: String) : LoginPhase
}

/**
 * 登录流程的**纯判定**部分：把网关的三类响应（methods / start / wait）
 * 翻译成 UI 状态。
 *
 * ## 为什么把它单独拿出来（不放 UI）
 *
 * 这里的每一条都是「踩过才知道」的语义，必须能被单测钉住：
 * - `done && !pending` 才算完成（只判 done 会把「pending 里也带 done」误判成成功）；
 * - 超时不是失败而是「等你太久了」：manualCallback 的家要提示粘回调；
 * - 取消与失败必须分开（用户点取消不该看到红字）。
 */
object LoginFlowRules {

    /** 轮询上限：5 分钟（与网关面板一致）。 */
    const val TIMEOUT_MS: Long = 5L * 60L * 1000L

    /** 默认轮询间隔（网关不给建议值时）。 */
    const val DEFAULT_POLL_MS: Long = 3000L

    /**
     * 一次 wait 结果 → 下一个状态。
     * @param method 发起时用的登录方式（决定超时文案与是否要粘回调）
     */
    fun next(
        wait: LoginWait,
        method: LoginMethod,
        state: String,
        elapsedMs: Long,
    ): LoginPhase {
        if (wait.done && !wait.pending) {
            val error = wait.error
            return if (error.isNullOrBlank()) {
                LoginPhase.Succeeded(wait.accountId)
            } else {
                LoginPhase.Failed(error)
            }
        }
        if (elapsedMs >= TIMEOUT_MS) {
            return LoginPhase.Failed(timeoutMessage(method))
        }
        if (method.manualCallback && elapsedMs >= MANUAL_HINT_AFTER_MS) {
            return LoginPhase.NeedCallback(
                state = state,
                wantsCode = method.callbackInput == "code",
                hint = wait.hint ?: method.instructions.ifBlank { null },
            )
        }
        return LoginPhase.Waiting(
            state = state,
            url = null,
            hint = wait.hint ?: method.instructions.ifBlank { null },
            elapsedSeconds = elapsedMs / 1000L,
        )
    }

    /** 超过这个时长还没完成、且该方式需要粘回调 → 主动把粘贴入口给出来。 */
    const val MANUAL_HINT_AFTER_MS: Long = 20_000L

    /**
     * 超时文案。必须给出**下一步** —— 只说「超时」用户不知道该干什么。
     * 走 loopback 回调的家，最常见原因不是忘了授权，而是浏览器与网关不在同一台机器上。
     */
    fun timeoutMessage(method: LoginMethod): String = when {
        method.callbackInput == "code" ->
            "登录等待超时（5 分钟）：请重新发起，并按提示粘贴本人已授权的令牌或一次性 code。"
        method.manualCallback ->
            "登录等待超时（5 分钟）：授权完成后的回调会落到浏览器那台机器上，网关收不到 —— " +
                "请复制浏览器地址栏里的完整回调地址，粘回这里重试。"
        else -> "登录等待超时（5 分钟）。请重新发起登录。"
    }

    /** 该方式要不要在界面上给「粘贴回调」输入框。 */
    fun needsCallbackInput(method: LoginMethod): Boolean = method.manualCallback

    /** 该方式要不要打开浏览器（有 authUrl 才需要）。 */
    fun needsBrowser(start: LoginStart): Boolean = !start.authUrl.isNullOrBlank()
}