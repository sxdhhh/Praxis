package dev.praxis.core.data.character

/**
 * 角色系统的数据库访问收口接口。
 *
 * ## 为什么需要这层接口，而不是直接依赖 `PraxisDatabase`
 *
 * `PraxisDatabase` 定义在 `:app` 模块（它聚合 session/message/memory 三张表，
 * 都是 app 的实体）。依赖方向是 `:app → :core:data`，反过来不行。
 * 但 `CharacterRepository` 在 `:core:data` 里，它需要的只是
 * 「两个 DAO + 一个事务入口」，不需要知道整库有哪些表。
 *
 * 所以这里声明**最小接口**：
 *
 * - `:core:data` 只依赖这个接口，不依赖 app；
 * - `:app` 的 `PraxisDatabase` 实现它（三行代码）；
 * - 单测可以直接传一个假实现，不用起 Room。
 *
 * ## 为什么不直接把三个方法做成构造函数参数
 *
 * 那样 `CharacterRepository` 的构造签名会变成
 * `(cardDao, tagDao, transactionRunner)` —— 调用方每处都要凑三样东西，
 * 而且「事务」会退化成「调用方自己传一个 lambda」，
 * 极易出现「忘了包事务」的调用点。收成一个接口后，
 * 事务与 DAO 的配对关系被固定住，调用方无从写错。
 */
interface CharacterDatabase {
    fun characterCardDao(): CharacterCardDao

    fun promptTagDao(): PromptTagDao

    /**
     * 在一个数据库事务里执行 [block]。
     *
     * 实现方**必须**用 Room 的 `withTransaction`（或 `runInTransaction`），
     * 不能用「先 begin 再 commit」的手写版本 —— 协程取消时不走 finally，
     * 会留下悬挂事务把后续所有写操作锁死。
     */
    suspend fun <T> withCharacterTransaction(block: suspend () -> T): T
}