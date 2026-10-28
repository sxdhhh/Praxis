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
            .addMigrations(
                PraxisDatabase.MIGRATION_2_3,
                PraxisDatabase.MIGRATION_3_4,
                // v5：角色卡 + 提示词标签。角色卡里是用户手写的人设，
                // 丢一张就等于让他重写几千字，所以同样不给破坏性迁移。
                PraxisDatabase.MIGRATION_4_5,
                // v6：sessions 加 displayOrder（对齐 Operit 的会话排序）。
                //
                // ## 为什么这一步也必须是显式迁移
                //
                // 它只是「加一列 + 回填」，看起来可以直接删库重建 ——
                // 但 sessions 表是**消息的外键父表**（`ON DELETE CASCADE`），
                // 破坏性迁移等于把用户全部聊天记录清空。
                //
                // 回填逻辑（`displayOrder = -createdAt`）保证了升级后
                // 列表顺序与升级前一致，用户察觉不到这次 schema 变动。
                PraxisDatabase.MIGRATION_5_6,
                // v7：messages 加 variantGroupId / variantIndex（消息变体）。
                //
                // ## 为什么也必须是显式迁移
                //
                // messages 表存的是用户全部聊天记录，删库重建 = 全丢。
                // 这次只加两个可空列 + 一个索引，**不需要回填**（NULL 就是
                // 老消息的正确值，见 `MIGRATION_6_7` 的 KDoc）。
                PraxisDatabase.MIGRATION_6_7,

                PraxisDatabase.MIGRATION_7_8,
            )
            .build()
    }
    single<SessionDao> { get<PraxisDatabase>().sessionDao() }
    single<dev.praxis.data.db.MessageDao> { get<PraxisDatabase>().messageDao() }
    single<dev.praxis.data.db.MemoryDao> { get<PraxisDatabase>().memoryDao() }

    // 角色系统 DAO（实体与 DAO 定义在 :core:data，库在 :app）。
    single<dev.praxis.core.data.character.CharacterCardDao> {
        get<PraxisDatabase>().characterCardDao()
    }
    single<dev.praxis.core.data.character.PromptTagDao> {
        get<PraxisDatabase>().promptTagDao()
    }

    /**
     * 角色仓储。
     *
     * 用 [dev.praxis.core.data.settings.createCharacterRepository] 而不是
     * 直接 `get<DataStore<Preferences>>()`：DataStore 从未按类型注册过
     * （它是 Context 的私有扩展属性），按类型注入会在启动时报「找不到定义」。
     * 工厂函数内部复用与设置页同一个 DataStore 实例，
     * 避免 `multiple DataStores active for the same file` 崩溃。
     *
     * ## 为什么显式写类型
     *
     * 不写类型时 Koin 用**推断类型**注册。推断出的类型是对的，但
     * `koinInject<CharacterRepository>()` 在多个实现（比如将来加一个
     * 无 Room 的降级实现）时会变得有歧义。显式声明让注册表一眼可读：
     * 「这个类型由这一行提供」，而不是靠读 lambda 返回值去猜。
     */
    single<dev.praxis.core.data.character.CharacterRepository> {
        dev.praxis.core.data.settings.createCharacterRepository(
            context = androidContext(),
            database = get<PraxisDatabase>(),
        )
    }

    // 记忆存储端口（实现在 :app，契约在 core:memory）。
    single<dev.praxis.core.memory.MemoryStore> {
        dev.praxis.data.RoomMemoryStore(get())
    }
    single { dev.praxis.core.memory.MemoryRetriever() }
    single { dev.praxis.core.memory.MemoryWritePipeline(get()) }

    // 技能：文件读取实现在 :app，解析/注册在 core:plugin。
    single<dev.praxis.core.plugin.SkillSource> {
        dev.praxis.data.FileSkillSource(androidContext())
    }
    single { dev.praxis.core.plugin.SkillRegistry(get()) }

    // ── 扩展包（工具包）────────────────────────────────────────
    //
    // ## 为什么 storage 与 repository 都注册
    //
    // `PluginRepository` 只依赖 `PluginStorage` 接口（纯 JVM 可测），而
    // 真实存储是 Android 文件系统 —— 由 :app 提供实现并注册一次。
    // 两处都需要：设置页要 repository，插件市场安装完也只需要 repository；
    // 但外部归档读取需要 storage 的 `packagesRoot` 找下载目录。
    single<dev.praxis.core.plugin.PluginStorage> {
        dev.praxis.data.plugin.FilePluginStorage(androidContext())
    }

    //
    // ## 外部归档工厂在这里注册（2026-10-29）
    //
    // `androidContext()` 是 `Scope` 的扩展函数，只能在 `single {}` 的 lambda
    // 里调（那时 receiver 是 Scope）。放在模块级的 `run { }` 里会编译失败：
    // 「None of the following candidates is applicable」—— 两个候选都因为
    // receiver 类型不匹配被排除。
    //
    // 挂在 PluginRepository 的工厂里是最自然的位置：`install` 唯一的调用方
    // 就是它，而工厂只跑一次。
    single {
        val ctx = androidContext()
        dev.praxis.core.plugin.PluginRepository.externalArchiveFactory = { path ->
            val file = java.io.File(path)
            if (file.isDirectory) {
                dev.praxis.data.plugin.DirectoryPluginArchive(file)
            } else {
                // zip 不能直接交给 DirectoryPluginArchive（它按文件树读）。
                //
                // 这里绝不能用 `installFrom`：那会把包真的装进 packages/<id>/，
                // 而「预检一个包」的前提就是**不落库**。误用一次会让「用户取消
                // 安装」也留下一个包。
                dev.praxis.data.plugin.TempDirZipArchive.unzipInto(ctx.cacheDir, file)
            }
        }
        dev.praxis.core.plugin.PluginRepository(get())
    }

    // 在线市场：默认启用。
    // ## 为什么可以默认开
    //
    // 它只在用户点开「扩展包」页时才发请求（不乱连），且只访问公开 CDN。
    // 需要隐私模式的用户可以在后续加开关 —— 但默认关会让这个功能
    // 装完就是死的，与「不做一个永远转圈的详情页」是同一条取舍。
    single<dev.praxis.core.plugin.MarketApi> { dev.praxis.data.plugin.PluginMarketApi() }

    // 导入外部归档（.toolpkg / 目录）：`PluginRepository.install` 用它预检。
    //
    // ## 为什么在这里注册成全局可变字段
    //
    // `core:plugin` 是纯 JVM，不能依赖 Android 的文件/SAF 语义，所以它把
    // 「打开一个还没装进来的包」留成工厂钩子。:app 启动时注册一次，
    // 单测里可以换成内存实现。
    // 工作流：工具端口由 :app 桥接（core:workflow 不依赖 core:tool）。
    single<dev.praxis.core.workflow.WorkflowToolPort> {
        dev.praxis.data.ToolWorkflowPort(
            registry = get(),
            contextProvider = { get<dev.praxis.core.tool.ToolPermissionState>().snapshot() },
        )
    }
    single { dev.praxis.core.workflow.WorkflowEngine(get()) }
    // 聊天层只拿单向端口（只管读），不让它写记忆。
    single<dev.praxis.feature.chat.MemoryPort> {
        dev.praxis.data.MemoryPortImpl(
            store = get(),
            retriever = get(),
            pipeline = get(),
        )
    }

    // 会话/消息持久化。Repository 负责 orderIndex 与 updatedAt 的成对维护，
    // ChatPersistence 只是给 feature:chat 的端口适配。
    single {
        dev.praxis.data.ChatRepository(
            sessionDao = get(),
            messageDao = get(),
            // 中间插入（摘要）需要 withTransaction，所以要把库实例传进去。
            db = get(),
        )
    }
    single<dev.praxis.feature.chat.ChatPersistence> {
        dev.praxis.data.RoomChatPersistence(get())
    }
    /**
     * 终端会话快照端口（进程重启后恢复 scrollback 与命令历史）。
     *
     * 显式写类型 + 用 `createAppPreferencesStore` 拿全局 DataStore：
     * 后者保证与设置页复用同一个 `praxis_settings` 文件，
     * 否则会因「同文件两个 DataStore」在启动时抛异常。
     */
    single<dev.praxis.feature.terminal.TerminalSnapshotPort> {
        dev.praxis.data.terminal.DataStoreTerminalSnapshot(
            dataStore = dev.praxis.core.data.settings.createAppPreferencesStore(androidContext()),
        )
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

    // 内存监控：全局单例。
    //
    // ## 为什么必须是 single 而不是每次取新的
    //
    // 它持有 `lastTrimLevel`（来自 `onTrimMemory` 的状态）与一个轮询 job。
    // 若每次 `get()` 都新建一个实例：
    //  - `Application.onTrimMemory` 转发到实例 A 的 trim level，而
    //    聊天页订阅的是实例 B —— 系统明确的回收请求被静默丢弃；
    //  - 每进一次聊天页就多一个轮询协程（泄漏）。
    //
    // `androidContext()` 而不是 `get<Context>()`：Koin 的 androidContext
    // 拿的是 Application context，正好是这个场景需要的（不需要 Activity）。
    single { dev.praxis.data.memory.MemoryMonitor(androidContext()) }

    // 模型列表拉取：设置页「拉取」按钮的后端。
    //
    // 单独一个 single 而不是在设置页 new 一个：它内部持有一个 OkHttpClient
    // （连接池 + 线程池）。每次进设置页 new 一个会白丢连接池，且
    // 频繁创建线程池在低端机上可观。这里与 OpenAiCompatProvider 分开也是
    // 有意的 —— 那个的 readTimeout 是 0（为 SSE 长连接），拉列表不能用它。
    single<dev.praxis.core.ai.ModelCatalogFetcher> {
        dev.praxis.data.ai.OkHttpModelCatalogFetcher()
    }

    // Linux 容器执行端口（proot）。
    //
    // 单独一个 single 而不是在设置页与工具层各 new 一个：
    // 两处必须操作**同一份** rootfs 与会话表，否则「AI 装的用户在设置页看不到」。
    single { dev.praxis.data.linux.ProotEnvPort(androidContext()) }

    // ## 终端会话端口：必须 single（2026-10-29）
    //
    // 有两个消费者：
    //  - AI 的 6 个会话工具（create/execute/input/screen/close）；
    //  - 终端页（用户直接敲键盘）。
    //
    // 若各建一份，`live` 这张会话表就有两份：AI 在 `t1` 里装包，用户在终端页
    // 敲 `ls` 却在另一个空 shell —— 两边的 `cd`、环境变量、后台进程全部
    // 互不可见。共用同一个实例才是「同一个容器」。
    single<dev.praxis.core.linux.TerminalSessionPort> {
        dev.praxis.data.linux.ProotTerminalSessionPort(
            context = androidContext(),
            envPort = get<dev.praxis.data.linux.ProotEnvPort>(),
        )
    }

    /** 终端页用的端口形状（字节流 + 逐键 + 尺寸），包装上面同一个实例。 */
    single<dev.praxis.feature.terminal.PtySessionPort> {
        dev.praxis.data.linux.ProotPtySessionAdapter(
            delegate = get<dev.praxis.core.linux.TerminalSessionPort>(),
        )
    }

    // 工具层：文件工具挂到应用私有目录下，沙箱边界由 FileTools 负责。
    single {
        val root = java.io.File(androidContext().filesDir, "workspace")
        root.mkdirs()
        ToolRegistry().apply {
            registerAll(*FileTools(root).all().toTypedArray())

            // ## Linux 容器工具（AI 可调用 Kali）
            //
            // user 的要求是「内置一个 kali linux，然后让 ai 可以调用」。
            // 这些工具就是「可调用」那一半：模型能在容器里真跑 nmap / python3 / apt。
            //
            // 与设置页共用同一个 ProotEnvPort 实例（见下方 single）：
            // AI 装好的环境，用户在设置页能看到「已安装」；反之也成立。
            // 各建一份会得到「AI 说装好了，设置页显示未安装」这种用户无法理解的状态。
            run {
                val linuxPort = dev.praxis.data.linux.LinuxToolPortImpl(
                    context = androidContext(),
                    envPort = get<dev.praxis.data.linux.ProotEnvPort>(),
                    // 默认 Kali。
                    distro = dev.praxis.core.linux.LinuxDistro.Kali,
                )
                registerAll(
                    dev.praxis.data.linux.LinuxStatusTool(linuxPort),
                    dev.praxis.data.linux.LinuxListToolsTool(linuxPort),
                    dev.praxis.data.linux.LinuxExecTool(linuxPort),
                )
            }
            // ## 终端会话 7 工具（1:1 对齐 Operit）
            //
            // 与上面的 `linux_exec` 并存，不是替换：
            //  - `linux_exec` = 无会话、一次性执行（快速跑一条）；
            //  - 这 7 个 = 有状态会话（`cd` 跨调用保留、可发按键、可看屏幕）。
            //
            // 会话端口用 `ProotTerminalSessionPort`，它复用同一个
            // `ProotEnvPort` 取环境 —— 与设置页看到的是同一份 rootfs。
            run {
                // `LinuxEnvPort` 在 Koin 里没有单独绑定（设置页那边是直接 new
                // `ProotEnvPort` 的）。这里用同一个 `ProotEnvPort` single，
                // 保证「AI 装好的环境设置页能看到」——两份实例会得到两份 rootfs 视图。
                // 取上面的 single，不要自己 new：两份实例 = 两张会话表（见上）。
                val sessionPort = get<dev.praxis.core.linux.TerminalSessionPort>()
                registerAll(
                    dev.praxis.core.linux.CreateTerminalSessionTool(sessionPort),
                    dev.praxis.core.linux.ExecuteInTerminalSessionTool(sessionPort),
                    dev.praxis.core.linux.ExecuteInTerminalSessionStreamingTool(sessionPort),
                    dev.praxis.core.linux.CloseTerminalSessionTool(sessionPort),
                    dev.praxis.core.linux.InputInTerminalSessionTool(sessionPort),
                    dev.praxis.core.linux.GetTerminalScreenTool(sessionPort),
                )
            }
            // ## 宿主 Android shell 工具（移植 Operit 的 SuperAdmin 能力）
            //
            // 与上面两组的分界（模型最容易搞混的地方）：
            //  - `linux_exec` / 终端会话 = 在 **proot 容器**里跑（nmap / apt / python3）；
            //  - `host_shell` = 在 **手机系统**上跑（pm / am / settings / dumpsys）。
            //
            // 容器里没有 `pm`，手机上也没有 `apt`。少任何一边，模型都会
            // 用错工具然后开始尝试「在容器里装 Android 工具链」这种歪路。
            //
            // 档位是 ROOT（见 HostShellTool 的 KDoc）：不到 ROOT 的用户
            // 连工具都看不到，而不是发出去再被拒。
            run {
                // 两个工具**共用同一个 Port**：`hasRoot()` 会真的跑一次 `su -c id`，
                // 建成两个实例等于每次调用探活两遍（用户看到的是「点了没反应」）。
                val hostShell = dev.praxis.data.shell.AndroidHostShellPort(androidContext())
                registerAll(
                    dev.praxis.data.shell.HostShellTool(hostShell),
                    dev.praxis.data.shell.HostShellChannelsTool(hostShell),
                )
            }
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

    // ── 系统授权检测（2026-10-22 新增）──────────────────────────────
    //
    // 独立 single 而不是在权限页里 new：检测结果被多处读
    // （权限页、将来的工具执行前置检查），而且它持有 StateFlow，
    // 每次 new 会让各页面各自持有一份不同步的状态。
    single { dev.praxis.core.data.permission.CapabilityDetector(androidContext()) }
    single { dev.praxis.core.data.permission.CapabilityRequester(androidContext()) }
}
