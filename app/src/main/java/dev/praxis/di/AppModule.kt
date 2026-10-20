package dev.praxis.di

import androidx.room.Room
import dev.praxis.BuildConfig
import dev.praxis.data.db.PraxisDatabase
import dev.praxis.core.data.settings.SettingsRepository
import dev.praxis.core.data.settings.createSettingsRepository
import dev.praxis.core.ai.AiProvider
import dev.praxis.core.tool.PermissionTier
import dev.praxis.core.tool.ToolContext
import dev.praxis.core.tool.ToolExecutor
import dev.praxis.core.tool.ToolRegistry
import dev.praxis.core.tool.builtin.FileTools
import dev.praxis.data.ai.OpenAiCompatProvider
import dev.praxis.data.db.SessionDao
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule = module {

    single {
        // ## 从 v3 起**不再用破坏性迁移**（包括 debug）
        //
        // 之前 debug 用 fallbackToDestructiveMigration 是为了省迁移脚本，
        // 但 v3 增加了长期记忆表：记忆一旦被清空就永久丢失，用户无从得知
        // 「为什么它忘了我上个月说的事」。这类数据不能靠「开发期偷懒」来换便利。
        //
        // 所以两个构建类型都走显式迁移：schema 改了却没写迁移，就让它启动即崩，
        // 在开发阶段暴露，而不是在用户设备上静默清数据。
        Room.databaseBuilder(
            androidContext(),
            PraxisDatabase::class.java,
            "praxis.db",
        )
            .addMigrations(PraxisDatabase.MIGRATION_2_3)
            .build()
    }

    single<SessionDao> { get<PraxisDatabase>().sessionDao() }
    single<dev.praxis.data.db.MessageDao> { get<PraxisDatabase>().messageDao() }
    single<dev.praxis.data.db.MemoryDao> { get<PraxisDatabase>().memoryDao() }

    // 记忆存储端口（实现在 :app，契约在 core:memory）。
    single<dev.praxis.core.memory.MemoryStore> {
        dev.praxis.data.RoomMemoryStore(get())
    }
    single { dev.praxis.core.memory.MemoryRetriever() }
    single { dev.praxis.core.memory.MemoryWritePipeline(get()) }
    // 聊天层只拿单向端口（只管读），不让它写记忆。
    single<dev.praxis.feature.chat.MemoryPort> {
        dev.praxis.data.MemoryPortImpl(
            store = get(),
            retriever = get(),
        )
    }

    // 会话/消息持久化。Repository 负责 orderIndex 与 updatedAt 的成对维护，
    // ChatPersistence 只是给 feature:chat 的端口适配。
    single {
        dev.praxis.data.ChatRepository(
            sessionDao = get(),
            messageDao = get(),
        )
    }
    single<dev.praxis.feature.chat.ChatPersistence> {
        dev.praxis.data.RoomChatPersistence(get())
    }

    // 设置存储：主题等持久化都走这里（DataStore，非 Room）。
    single { createSettingsRepository(androidContext()) }

    // AI provider：key 与 baseUrl 走 buildConfig（真实 Key 由设置页写入后续接入）。
    single<AiProvider> {
        OpenAiCompatProvider(
            baseUrl = BuildConfig.DEFAULT_BASE_URL,
            apiKey = BuildConfig.API_KEY,
        )
    }

    // 工具层：文件工具挂到应用私有目录下，沙箱边界由 FileTools 负责。
    single {
        val root = java.io.File(androidContext().filesDir, "workspace")
        root.mkdirs()
        ToolRegistry().apply {
            registerAll(*FileTools(root).all().toTypedArray())
        }
    }
    single { ToolExecutor(get<ToolRegistry>()) }
    // 权限状态：运行时可改（设置页切档位 / 关工具）。
    // ToolContext 由 snapshot() 提供，保证「一批调用用同一份档位」。
    single { dev.praxis.core.tool.ToolPermissionState(PermissionTier.STANDARD) }
    single<ToolContext> {
        val state: dev.praxis.core.tool.ToolPermissionState = get()
        state.snapshot()
    }
}
