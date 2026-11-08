package dev.praxis.di

import dev.praxis.core.data.gateway.GatewayAccountStore
import dev.praxis.core.data.security.KeystoreCipher
import dev.praxis.data.db.PraxisDatabase
import org.koin.dsl.module

/**
 * 网关的接线（文档 §10 的「实现与接线（:app）」）。
 *
 * ## 凭证加密器的密钥别名
 *
 * 这里用 KeystoreCipher 的默认别名 —— 与 createSettingsRepository 里的那个
 * **同一把密钥**。有意如此：两处都是「本机自用的小秘密」，再开一把只会多一处
 * 要备份的东西，而没有任何安全收益。
 *
 * ## 时钟为什么在这里注入
 *
 * GatewayAccountStore 不自己调 System.currentTimeMillis()：注入之后它的
 * 落库规则可以在单测里拨钟验证（见 core:data 的那 9 例）。
 */
val gatewayModule = module {

    single { KeystoreCipher() }

    single {
        val db = get<PraxisDatabase>()
        val cipher = get<KeystoreCipher>()
        GatewayAccountStore(
            accountDao = db.gatewayAccountDao(),
            providerDao = db.gatewayProviderDao(),
            encrypt = cipher::encrypt,
            decrypt = cipher::decrypt,
            clock = { System.currentTimeMillis() },
        )
    }
}
