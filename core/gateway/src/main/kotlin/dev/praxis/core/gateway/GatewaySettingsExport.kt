package dev.praxis.core.gateway

/**
 * 把 Praxis 的设置**还原成网关原生的 config 片段**（导出，文档 §8.5）。
 *
 * ## 这个函数此前缺失，而缺它的代价很具体
 *
 * `GatewayConfigExporter` 一直都在（能把 [GatewayRoutingConfig] 写成网关的键），
 * 导入边也有（`GatewayConfigImporter`）。但**没人把 Praxis 自己的设置装进那个类型** ——
 * 也就是说：路由策略、超时、换号上限、排队、图片压缩、自动续写、debug 这些
 * 用户在 Praxis 里调过的值，**一个都导不出去**。
 *
 * 表现是「导出成功，但导出的文件里没有我改过的那些设置」—— 那种缺口不会报错，
 * 只有把文件拿去网关导入才会发现少了东西。
 *
 * ## 词表必须用网关的，不是我们的枚举名
 *
 * 文档 §6.4 的 `gateway_routing_strategy` 取值是 `order | roundRobin | weighted`，
 * `gateway_routing_sticky` 是 `auto | off | on`。写成 `Sequential` 那种 Kotlin 枚举名
 * 网关读不懂（它只做字符串比较）。
 */
object GatewaySettingsExport {

    /**
     * 策略词。
     *
     * Praxis 只有顺序与轮询两种 —— 网关那边的 `weighted` 我们**没有实现**，
     * 所以永远不会写出去（导出一个自己都不支持的值只会让回灌后的行为与本地不一致）。
     */
    fun strategyToken(strategy: RoutingStrategy): String = when (strategy) {
        RoutingStrategy.Sequential -> "order"
        RoutingStrategy.RoundRobin -> "roundRobin"
    }

    /**
     * 粘性词。
     *
     * Praxis 是一个布尔（开/关），网关的词表里 `auto` 才是「默认行为」，
     * 所以开 → `auto`、关 → `off`。不写 `on`：那是「强制总是粘」的语义，
     * 与我们的布尔不等价。
     */
    fun stickyToken(enabled: Boolean): String = if (enabled) "auto" else "off"

    /**
     * 组装导出用的 config 片段。
     *
     * 只填**我们真的有**的字段：`retryCount` / `retryIntervalSeconds` /
     * `noRetryStatusCodes` / `headersTimeoutSeconds` / `bodyTimeoutSeconds` /
     * `queueMaxWaits` 在 Praxis 里还没有对应设置，留 null。
     * `GatewayConfigExporter` 只写非空键 —— 所以那些键不会出现在文件里，
     * 网关导入时就会继续用它自己的默认值（而不是被写死的什么值）。
     */
    /**
     * 与默认值相同就写 null（= 不导出这个键）。
     *
     * ## 为什么这条是必须的
     *
     * 导出侧只写非空键，而 null 在**导入侧**的语义是「网关文件里原本没有这个键」。
     * 把没改过的设置写成 0/false，等于替用户做了一次他从未做过的选择：
     * 回灌到网关后，那些键从「跟随默认值」变成「显式值」，以后网关改默认值也影响不到它。
     */
    private fun <T> onlyIfChanged(value: T, default: T): T? =
        if (value == default) null else value

    fun toRoutingConfig(
        policy: GatewayRoutingPolicy,
        system: GatewaySystemSettings,
        /** 与什么比「算改过」。默认即出厂默认值；测试可注入。 */
        defaults: GatewaySystemSettings = GatewaySystemSettings(),
        policyDefaults: GatewayRoutingPolicy = GatewayRoutingPolicy(),
    ): GatewayRoutingConfig = GatewayRoutingConfig(
        // 这两个总是写：它们是「当前选择」，而网关需要一个值（词表见本文件头）。
        routingStrategy = strategyToken(policy.strategy),
        routingSticky = stickyToken(policy.stickyEnabled),
        breakerEnabled = onlyIfChanged(policy.breakerEnabled, policyDefaults.breakerEnabled),
        breakerThreshold = onlyIfChanged(
            policy.breakerFailureThreshold,
            policyDefaults.breakerFailureThreshold,
        ),
        // 秒：网关的键是 timeoutSeconds，我们的内部值是毫秒。
        breakerTimeoutSeconds = onlyIfChanged(
            (policy.breakerOpenMillis / 1000L).toInt(),
            (policyDefaults.breakerOpenMillis / 1000L).toInt(),
        ),
        breakerSuccessThreshold = onlyIfChanged(
            policy.breakerSuccessThreshold,
            policyDefaults.breakerSuccessThreshold,
        ),
        // 换号上限：网关那个键名是 CrossProviderCount（文档 8.4 特意点出）。
        retryCrossProviderCount = onlyIfChanged(system.maxSwitches, defaults.maxSwitches),
        connectTimeoutSeconds = onlyIfChanged(
            system.connectTimeoutSeconds,
            defaults.connectTimeoutSeconds,
        ),
        // 读超时在我们的语义里就是「两次数据之间」的上限，与网关的 streamIdle 同义。
        streamIdleTimeoutSeconds = onlyIfChanged(
            system.readTimeoutSeconds,
            defaults.readTimeoutSeconds,
        ),
        queueWaitSeconds = onlyIfChanged(system.queueWaitSeconds, defaults.queueWaitSeconds),
        imageCompressEnabled = onlyIfChanged(
            system.imageCompress.enabled,
            defaults.imageCompress.enabled,
        ),
        imageCompressMaxEdge = onlyIfChanged(
            system.imageCompress.maxEdge,
            defaults.imageCompress.maxEdge,
        ),
        imageCompressQuality = onlyIfChanged(
            system.imageCompress.quality,
            defaults.imageCompress.quality,
        ),
        imageCompressThresholdKb = onlyIfChanged(
            system.imageCompress.thresholdKb,
            defaults.imageCompress.thresholdKb,
        ),
        autoContinue = onlyIfChanged(system.autoContinue, defaults.autoContinue),
        autoContinueMaxRounds = onlyIfChanged(
            system.autoContinueMaxRounds,
            defaults.autoContinueMaxRounds,
        ),
        debugMode = onlyIfChanged(system.debugRawTraffic, defaults.debugRawTraffic),
    )
}
