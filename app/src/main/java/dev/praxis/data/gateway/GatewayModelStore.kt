package dev.praxis.data.gateway

import dev.praxis.core.data.gateway.GatewayModelDao
import dev.praxis.core.data.gateway.GatewayModelEntity

/**
 * `gateway_model` 表的读写层（v13 新增的那张结构化条目表）。
 *
 * ## 为什么现在才写它
 *
 * 表在 P2 落库时建的（v12→v13），但一直没有消费者 —— 那等于一张死表：
 * 建了、迁移了、永远没数据。模型页的候选清单原先只来自「账号声明的目录名」，
 * 用户手动管过的模型无处可存。
 *
 * ## 只读全量与启用两档
 *
 * 与 DAO 一一对应，不做额外判断：过滤与合并的语义在 `core:gateway` 的
 * [dev.praxis.core.gateway.GatewayModelOptions] 里（纯函数、有单测）。
 * 这一层只负责把 Room 的结果交出去。
 */
class GatewayModelStore(private val dao: GatewayModelDao) {

    suspend fun all(): List<GatewayModelEntity> = dao.all()

    suspend fun enabled(): List<GatewayModelEntity> = dao.enabled()

    /** 结构化条目里可选的「对外模型 id」（只取启用的）。 */
    suspend fun visibleModelIds(): List<String> =
        dao.enabled().map { entity -> entity.id }

    suspend fun upsertAll(entries: List<GatewayModelEntity>) = dao.upsertAll(entries)

    suspend fun clear() = dao.clear()
}
