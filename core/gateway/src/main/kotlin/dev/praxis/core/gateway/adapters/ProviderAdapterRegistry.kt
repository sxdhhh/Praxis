package dev.praxis.core.gateway.adapters

/**
 * 提供商适配器注册表：id → 实例。
 *
 * 各家 adapter 在 init 里注册自己（或由 DI 容器注入），
 * 这里只负责查找。
 */
object ProviderAdapterRegistry {

    private val adapters = mutableMapOf<String, ProviderAdapter>()

    fun register(adapter: ProviderAdapter) {
        adapters[adapter.id] = adapter
    }

    fun of(id: String): ProviderAdapter? = adapters[id]

    fun all(): List<ProviderAdapter> = adapters.values.toList()
}