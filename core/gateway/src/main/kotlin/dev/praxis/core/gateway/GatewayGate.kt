package dev.praxis.core.gateway

/**
 * 一次出站请求该走哪条链路。
 *
 * 三个取值对应文档 §11 末尾那条回滚要求：
 * 「每阶段结束都要能回滚：gateway_enabled = false 时必须与现在的行为完全一致」。
 */
enum class OutboundRoute {

    /** 非网关档案 → 走既有的单配置路径（也就是移植前的行为）。 */
    SingleProfile,

    /** 网关档案且总开关打开 → 交给网关路由层。 */
    Gateway,

    /**
     * 网关档案但总开关**关闭**。
     *
     * 这一档刻意**不**回落到 [SingleProfile]：网关档案上没有 baseUrl / apiKey
     * （它们属于账号池），回落到单配置路径等于拿一个空地址发请求；
     * 更糟的是若将来某种「保底账号」被加进来，用户以为关了开关只是停用网关，
     * 实际请求却被悄悄从别的账号发了出去。
     *
     * 所以这一档的唯一结果是**明确报错**，由调用方拼一句可操作的话给用户。
     */
    GatewayDisabled,
}

/**
 * 总开关的判定（纯函数，无 Android）。
 *
 * ## 为什么单独一个函数
 *
 * 「关掉 = 今天的行为」是文档写死的验收条件。把它压进 Provider 的 if 里，
 * 就只能靠读代码确认；抽出来之后四种组合可以逐条断言，
 * 而且将来加第三种档案 kind 时这里会先红。
 */
object GatewayGate {

    /**
     * @param profileIsGateway 当前模型档案的 kind 是否为 Gateway
     * @param gatewayEnabled 用户的总开关（[dev.praxis.core.gateway.GatewayGate] 的输入，
     *   存储键 `gateway_enabled`，默认 true）
     */
    fun decide(profileIsGateway: Boolean, gatewayEnabled: Boolean): OutboundRoute = when {
        // 开关只管网关档案：用单配置档案的人不该被这个开关影响。
        !profileIsGateway -> OutboundRoute.SingleProfile
        gatewayEnabled -> OutboundRoute.Gateway
        else -> OutboundRoute.GatewayDisabled
    }
}
