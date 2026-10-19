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
        // S5：破坏性迁移只在 debug 打开 —— 开发期改 schema 不用写迁移脚本；
        // release 保持严格，一旦 schema 变了却没迁移，装机时直接抛异常暴露问题，
        // 而不是静默把用户的会话表清空。
        val builder = Room.databaseBuilder(
            androidContext(),
            PraxisDatabase::class.java,
            "praxis.db",
        )
        if (BuildConfig.DEBUG) {
            builder.fallbackToDestructiveMigration(dropAllTables = true).build()
        } else {
            builder.build()
        }
    }

    single<SessionDao> { get<PraxisDatabase>().sessionDao() }
    single<dev.praxis.data.db.MessageDao> { get<PraxisDatabase>().messageDao() }

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
