package dev.praxis.feature.chat

/**
 * 会话标题生成端口。
 *
 * ## 为什么是端口（接口）而不是直接在 ViewModel 里调 provider
 *
 * 与 [MemoryPort] / [ChatPersistence] 同一个理由：`feature:chat` 不该知道
 * 「模型怎么配」「用哪个功能位的模型」。这些都在 `:app` 与 `core:data` 里。
 *
 * 更实际的好处：`ChatViewModel` 的单测不需要起一个假的 provider +
 * DataStore，只要传一个 lambda 回来一个字符串。
 *
 * ## 为什么 `suspend` 且可能抛
 *
 * 标题生成是一次真实网络请求（可能几秒）。放在 UI 线程会卡死；
 * 用 suspend 让调用方自己选 dispatcher（实现方在 IO 上跑）。
 *
 * **允许抛**：调用方需要用 try/catch 把失败降级成「保留兜底标题」。
 * 若这里返回 null 表示失败，「网络断」与「模型返回空串」就分不开了 ——
 * 前者该静默重试，后者说明 prompt 有问题。
 */
fun interface ChatTitleGenerator {

    /**
     * 根据首条用户消息生成标题。
     *
     * @param userText 首条用户消息原文（已 trim 过，超长由实现方截断）
     * @return 已清洗的标题；模型返回空串时返回空串（调用方降级到兜底标题）
     */
    suspend fun generate(userText: String): String
}
