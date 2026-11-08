package dev.praxis.core.gateway

/**
 * 账号列表的一行（界面模型）。
 *
 * ## 为什么放在 core:gateway 而不是 UI 模块
 *
 * 它要被三处用到：:app（从数据库读出来组装）、feature:gateway（渲染）、
 * 以及测试。若定义在 feature:gateway，:app 得依赖 feature 模块的类型；
 * 若定义在 :app，feature:gateway 又得反过来依赖 :app。放在领域层最省事，
 * 而且它本身就是纯数据，没有任何 UI 依赖。
 */
data class GatewayAccountRow(
    val id: String,
    val name: String,
    /** 家名（已翻译成展示名）。 */
    val providerLabel: String,
    val status: GatewayAccountStatus,
) {
    companion object {

        /**
         * 由账号字段与运行期状态组装一行。
         *
         * @param wireModels 该账号承载的上游模型；空表示还不知道（限额不判定）
         */
        fun from(
            id: String,
            name: String,
            providerLabel: String,
            enabled: Boolean,
            wireModels: List<String>,
            state: RouteState,
        ): GatewayAccountRow = GatewayAccountRow(
            id = id,
            name = name,
            providerLabel = providerLabel,
            status = GatewayAccountStatusResolver.resolve(
                accountId = id,
                enabled = enabled,
                wireModels = wireModels,
                state = state,
            ),
        )
    }
}
