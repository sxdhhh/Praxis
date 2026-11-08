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
import kotlinx.coroutines.flow.first
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
                // v9：messages 加 reasoning。这条**定义早就存在却没挂上来**（2026-11-09 发现），
                // 结果停在 v8 的设备升级时同样会报「A migration from 8 to 9 was required but not found」。
                // 与 12->13 是同一类错误：迁移写好了，但没登记进 builder。
                PraxisDatabase.MIGRATION_8_9,
                // v9：messages 加 reasoning（模型思考过程）。
                //
                // 同样只加一个可空列，NULL 就是老消息的正确值，无需回填。
                // 但**仍必须是显式迁移** —— messages 存的是全部聊天记录。
                // v10：排序键改 createdAt（R6），索引跟着换。
                //
                // 这里必须显式迁移：messages 是全部聊天记录，
                // 破坏性迁移等于清空用户的对话。迁移里只回填摘要行的
                // createdAt，其余行的时间原样保留。
                PraxisDatabase.MIGRATION_9_10,
                // v11：sessions 加 contextUsed / contextLimit（R10）。
                // 两个默认 0 的列 —— 老数据等价于「还不知道用量」，
                // 与旧行为一致（旧实现在重启后同样是 0）。
                PraxisDatabase.MIGRATION_10_11,
                // v12 -> v13：新增 gateway_model。
                //
                // ## 为什么这条不能省（真机踩到过）
                //
                // `autoMigrations = [AutoMigration(11, 13)]` 只覆盖「从 11 直冲 13」的设备；
                // Room 不会自动拆成 11->12、12->13。装过 v12 那一版的设备因此打不开库，
                // 表现是「界面加载约 3 秒后崩溃」（第一条查询才去开库）。
                PraxisDatabase.MIGRATION_12_13,
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

    // 插件脚本运行时（WebView 实现）。
    //
    // ## 现在只绑定了，还没接编排
    //
    // 「启动时装载全部已启用包」这一步还没写：需要决定在哪个时机跑
    // （Application 起来？进扩展包页时？），以及失败如何呈现。本轮只把
    // 运行时做成可注入的，否则它是一段没有人能调用的死代码。
    //
    // ## 已知限制
    //
    // 见 PraxisWebViewPluginRuntime 的类注释：包内 require 目前不支持
    // （端口没有「相对路径 → 正文」的能力），所以只装单文件包能跑通。
    single<dev.praxis.core.plugin.PluginScriptRuntime> {
        dev.praxis.data.plugin.PraxisWebViewPluginRuntime(androidContext())
    }

    // 运行时宿主：按包记账工具与入口，卸载时据此释放。
    single {
        dev.praxis.core.plugin.PluginRuntimeHost(
            get<dev.praxis.core.tool.ToolRegistry>(),
            get<dev.praxis.core.plugin.PluginScriptRuntime>(),
        )
    }

    // 启动装载编排：把已启用的包装进运行时。
    single {
        dev.praxis.core.plugin.PluginActivation(
            get<dev.praxis.core.plugin.PluginRepository>(),
            get<dev.praxis.core.plugin.PluginRuntimeHost>(),
        )
    }

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
    /**
     * 溢出存储（单例，2026-11-07 提升）。
     *
     * ## 谁在用它
     *
     *  1. LinuxToolPortImpl —— 工具输出超长时落盘；
     *  2. read_spill 工具 —— 模型按定位符取回；
     *  3. ChatRepository.guardContent —— 消息正文超长时落盘。
     *
     * 三处本来就指向同一个目录、行为等价。提升为 single 的理由不是省内存，
     * 而是让「溢出目录在哪」只有一处定义 —— 散在三处的新建实例里，
     * 改目录时漏一个就会出现「存进去取不回来」。
     */
    single {
        dev.praxis.data.spill.SpillStore(
            java.io.File(androidContext().filesDir, "spill"),
        )
    }
    /**
     * 消息时间戳分配器（R6 起，排序键是 createdAt）。
     *
     * 必须是 single：ChatRepository 用它在写入时分配时间戳，
     * 而恢复历史时的「推高水位」也作用在**同一个**实例上。
     * 两个实例会让恢复推错对象，新消息仍可能排到历史之前。
     */
    single { dev.praxis.core.model.MessageTimestampAllocator() }
    single {
        dev.praxis.data.ChatRepository(
            sessionDao = get(),
            messageDao = get(),
            // 中间插入（摘要）需要 withTransaction，所以要把库实例传进去。
            db = get(),
            // 正文超长时落盘 + 截断（见 ChatRepository.guardContent）。
            spillStore = get(),
            // 时间戳分配器：与恢复水位共用同一个 single。
            timestampAllocator = get(),
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

    /**
     * 外部终端会话名登记表（AI 建的会话，进程重启后按名重建）。
     *
     * 与 [TerminalSnapshotPort] 用**同一个** DataStore 文件：
     * 同文件只能有一个 DataStore 实例，否则启动时抛异常。
     */
    single {
        dev.praxis.data.terminal.TerminalSessionRegistry(
            dataStore = dev.praxis.core.data.settings.createAppPreferencesStore(androidContext()),
        )
    }

    // 设置存储：主题等持久化都走这里（DataStore，非 Room）。
    single { createSettingsRepository(androidContext()) }

    /**
     * 待发队列的存档（按会话分键）。
     *
     * 与 `SettingsRepository` 共用同一个 DataStore：队列就是一小段文本，
     * 单独开一个 DataStore 只会多一份文件与一次初始化。
     */
    single<dev.praxis.feature.chat.PendingQueueStore> {
        dev.praxis.data.chat.SettingsPendingQueueStore(get())
    }

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

    // ── 本地生图：模型下载（docs/PORTING-IMAGEGEN.md §8）──
    //
    // 应用级作用域：下载要在离开设置页之后继续跑（前台服务接进来之前，至少不能被
    // 页面销毁连带取消）。用 SupervisorJob：一次下载失败不该影响后续任务。
    single {
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
        )
    }
    single { dev.praxis.data.imagegen.ModelDownloader() }
    // 探活：单例（设置页与工具共用同一个实例，避免两份超时配置各自为政）。
    single { dev.praxis.data.imagegen.EngineLocator() }
    // 生成客户端：按 ImageGenRunner 接口注册，工具只依赖接口。
    single<dev.praxis.data.imagegen.ImageGenRunner> { dev.praxis.data.imagegen.ImageGenClient() }
    // 网关结构化模型条目（gateway_model 表）：模型页候选的第二来源。
    single {
        dev.praxis.data.gateway.GatewayModelStore(get<dev.praxis.data.db.PraxisDatabase>().gatewayModelDao())
    }
    // 远端出图（OpenAI 兼容 /v1/images/generations）。
    // 生图进度总线（文档 §6.1）：工具往里写，聊天层从里读。
    single { dev.praxis.core.imagegen.ImageGenProgressBus() }
    single<dev.praxis.data.imagegen.RemoteImageGenerator> {
        dev.praxis.data.imagegen.OkHttpRemoteImageGenerator()
    }
    single {
        val settings = get<SettingsRepository>()
        val context = androidContext()
        // 设置读一次解一次：坏内容**不悄悄用默认源**（那会让用户以为换的镜像没生效），
        // 而是由闸门挡下并让他去设置页重设。
        suspend fun readDownloadSettings(): Result<dev.praxis.core.imagegen.ImageDownloadSettings>? =
            dev.praxis.core.imagegen.ImageDownloadSettingsCodec
                .decode(settings.imageDownloadSettingsJson.first())

        dev.praxis.data.imagegen.ImageModelDownloadManager(
            downloader = get(),
            // 模型目录与引擎认的路径一致：<files>/models/<id>/。
            modelsRoot = java.io.File(context.filesDir, "models"),
            scope = get(),
            downloadGuard = {
                val decoded = readDownloadSettings()
                if (decoded != null && decoded.isFailure) {
                    "下载设置读不出来（内容坏了），请到「本地生图 → 下载源」重设"
                } else {
                    val downloads = decoded?.getOrNull()
                        ?: dev.praxis.core.imagegen.ImageDownloadSettings()
                    if (downloads.wifiOnly &&
                        !dev.praxis.data.imagegen.NetworkStatus.isOnUnmeteredWifi(context)
                    ) {
                        "「仅 Wi-Fi 下载」开着，当前不在 Wi-Fi。" +
                            "可在「本地生图 → 下载源」里关掉，或切到 Wi-Fi"
                    } else {
                        null
                    }
                }
            },
            baseUrl = {
                // 闸门已挡住坏内容，这里取不到就用默认源（不会真正被用到）。
                readDownloadSettings()?.getOrNull()?.baseUrl
                    ?: dev.praxis.core.imagegen.ImageDownloadSettings.DEFAULT_BASE_URL
            },
        )
    }

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

            // ── 本地生图（docs/PORTING-IMAGEGEN.md §5.1）──
            //
            // 注册是**静态**的：引擎此刻有没有起来只有运行期才知道，
            // 所以不在这里判活 —— 工具自己会在执行时探活并回一句
            // 「引擎不可用（端口没人应答）」的分类错误。这比「工具消失」好排查。
            run {
                val settings = get<SettingsRepository>()
                val imageDir = java.io.File(androidContext().filesDir, "images")
                val locator = get<dev.praxis.data.imagegen.EngineLocator>()
                register(
                    dev.praxis.tool.GenerateImageTool(
                        runner = get(),
                        probe = { locator.probe() },
                        // 目标决定走哪条路（文档 §5.4）：本地引擎或远端 images 端点。
                        // 配置坏了（decode 返回 failure）时这里给 null —— 工具会回「还没选」，
                        // 而设置页会把「配置坏了」单独提示出来（两边显式不同，不互相掩盖）。
                        target = {
                            dev.praxis.core.imagegen.ImageGenTargetCodec
                                .decode(settings.imageGenTargetJson.first())
                                ?.getOrNull()
                        },
                        remoteGenerator = get(),
                        reportProgress = { progress -> get<dev.praxis.core.imagegen.ImageGenProgressBus>().publish(progress) },
                        apiKey = { settings.apiKey() },
                        // 设置页的画布/采样默认值：模型没说尺寸步数时就按它走。
                        imageDefaults = {
                            dev.praxis.core.imagegen.ImageGenDefaultsCodec
                                .decode(settings.imageGenDefaultsJson.first())
                                ?.getOrNull()
                                ?: dev.praxis.core.imagegen.ImageGenDefaults()
                        },
                        // 档位校验暂时只做全局范围（256~1024）：目录里每个模型只记了一个
                        // 默认尺寸，拿它当「唯一可用档位」会把 512x768 这类合法请求拦掉。
                        availableSizes = { emptyList() },
                        saveImage = { bytes ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                if (!imageDir.exists()) imageDir.mkdirs()
                                val file = java.io.File(
                                    imageDir,
                                    "gen_" + System.currentTimeMillis() + ".jpg",
                                )
                                file.writeBytes(bytes)
                                file.absolutePath
                            }
                        },
                    ),
                )

                // ── 让 AI 改持久配置 + 列出模型（文档 §5.6）──
                val imageModelsRoot = java.io.File(androidContext().filesDir, "models")
                val soc = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    android.os.Build.SOC_MODEL
                } else {
                    android.os.Build.HARDWARE
                }
                val imageGenDefaultsPort: suspend () -> dev.praxis.core.imagegen.ImageGenDefaults = {
                    dev.praxis.core.imagegen.ImageGenDefaultsCodec
                        .decode(settings.imageGenDefaultsJson.first())
                        ?.getOrNull()
                        ?: dev.praxis.core.imagegen.ImageGenDefaults()
                }
                val imageGenTargetPort: suspend () -> dev.praxis.core.imagegen.ImageGenTarget? = {
                    dev.praxis.core.imagegen.ImageGenTargetCodec
                        .decode(settings.imageGenTargetJson.first())
                        ?.getOrNull()
                }
                val installedModelIds: suspend () -> Set<String> = {
                    dev.praxis.core.imagegen.ImageModelCatalog.builtin(soc)
                        .filter { model ->
                            dev.praxis.core.imagegen.ImageModelCatalog
                                .isInstalled(imageModelsRoot, model)
                        }
                        .map { model -> model.id }
                        .toSet()
                }

                register(
                    dev.praxis.tool.SetImageGenConfigTool(
                        readDefaults = imageGenDefaultsPort,
                        writeDefaults = { updated ->
                            settings.setImageGenDefaultsJson(
                                dev.praxis.core.imagegen.ImageGenDefaultsCodec.encode(updated),
                            )
                        },
                        readTarget = imageGenTargetPort,
                        writeTarget = { updated ->
                            settings.setImageGenTargetJson(
                                dev.praxis.core.imagegen.ImageGenTargetCodec.encode(updated),
                            )
                        },
                        installedModelIds = installedModelIds,
                    ),
                )
                register(
                    dev.praxis.tool.ListImageGenModelsTool(
                        localModels = { dev.praxis.core.imagegen.ImageModelCatalog.builtin(soc) },
                        installedIds = installedModelIds,
                        readTarget = imageGenTargetPort,
                    ),
                )
            }

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
                // 登记表：AI 建的会话名要持久化，否则进程一死终端页就
                // 再也看不到它（见 `TerminalSessionRegistry` 的 KDoc）。
                val sessionRegistry = get<dev.praxis.data.terminal.TerminalSessionRegistry>()
                registerAll(
                    dev.praxis.core.linux.CreateTerminalSessionTool(
                        port = sessionPort,
                        onSessionCreated = { name -> sessionRegistry.remember(name) },
                    ),
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

            // ## 长期记忆改成「AI 自己搜/自己写」（2026-11-05）
            //
            // 用户原话：「长期记忆应该让 ai 自己去搜而不是直接放提示词里。
            // 每轮先搜记忆 -> 开工 -> 写回记忆」。
            //
            // 旧做法是每轮把 recall(最后一句) 拼进 system，问题是：
            //  - 搜得太早：发送那一刻还不知道模型这轮要做什么；
            //  - 搜错了没有补救：模型无法要求「换一批」。
            //
            // 改为两个工具后，检索时机与 query 由模型掌握。
            // 与聊天层共用**同一个** MemoryPortImpl：
            // 分开建两份实例会出现「工具写进去的记忆，附件面板看不到」。
            run {
                val memoryPort = get<dev.praxis.feature.chat.MemoryPort>()
                val queryPort = dev.praxis.data.memory.MemoryPortQueryAdapter(memoryPort)
                registerAll(
                    dev.praxis.data.memory.SearchMemoryTool(queryPort),
                    dev.praxis.data.memory.SaveMemoryTool(queryPort),
                )
            }

            // ## 自定义工具（L2：声明式 DSL + 原语白名单）（2026-11-06）
            //
            // 用户在 `files/tools/*.tool` 里写定义，启动时装载成真正的工具。
            //
            // 三条安全边界（都在 core:tool 的 CustomToolValidator 里）：
            //  1. op 只能取白名单内的原语 —— 没有任何方式拼出任意命令执行；
            //  2. 占位符必须已声明 —— 拼错不会静默做错事；
            //  3. 含写操作必须声明 hasSideEffects —— 否则失败时会被误判为可安全重试。
            //
            // 装载时**不执行任何东西**：只解析 + 校验 + 注册。
            // ## 启动期 IO 必须容错（2026-11-06 闪退审查）
            //
            // 这里是 Koin 的单例体：**任何异常都会炸掉整个 DI 图**，
            // App 直接闪退，而用户看到的只是「打开就退」。
            //
            // 自定义工具只是增强功能 —— 目录不可读、文件损坏都不该让 App 起不来。
            // 所以整段包 `runCatching`，失败就当成「没有自定义工具」。
            run {
                val loaded = runCatching {
                    val toolsDir = java.io.File(androidContext().filesDir, "tools")
                    toolsDir.mkdirs()
                    val source = dev.praxis.data.custom.CustomToolSource(toolsDir)
                    // `runBlocking` 在这里可接受：单例构造是同步的，而读取
                    // 只在启动时发生一次、目录通常为空（几毫秒）。
                    // 真正的问题是**异常**，不是耗时 —— 已由外层 runCatching 兜住。
                    kotlinx.coroutines.runBlocking { source.list() }
                }.getOrElse { e ->
                    dev.praxis.ui.design.diagnostics.UiDiagnostics.note(
                        "custom-tool",
                        "自定义工具加载失败（忽略）：${e.message ?: e::class.simpleName}",
                    )
                    emptyList<dev.praxis.core.tool.custom.CustomToolDefinition>() to
                        emptyList<String>()
                }
                val (defs, skipped) = loaded
                skipped.forEach {
                    dev.praxis.ui.design.diagnostics.UiDiagnostics.note(
                        "custom-tool",
                        "自定义工具跳过：$it",
                    )
                }
                // ## 不能在这里 `get<ToolExecutor>()`（2026-11-06 闪退根因）
                //
                // `ToolExecutor` 的构造参数是 `ToolRegistry`，而我们现在**正在**
                // 构造 `ToolRegistry`。在单例体内部再向 Koin 要 ToolExecutor，
                // 就形成 `ToolRegistry → ToolExecutor → ToolRegistry` 的环，
                // Koin 直接抛 `CircularDependencyException` —— **App 启动即崩**。
                //
                // 正解：让 runner 延迟取值。`CustomToolRunner` 只在**真正执行**
                // 自定义工具时才需要 executor，而那一定发生在启动之后。
                // 传一个 lambda，由它在调用那一刻向 Koin 要。
                val runner = dev.praxis.core.tool.custom.CustomToolRunner(
                    executorProvider = {
                        org.koin.core.context.GlobalContext.get().get()
                    },
                )
                defs.forEach { def ->
                    val issues = dev.praxis.core.tool.custom.CustomToolValidator.validate(def)
                    if (issues.any { it.isError }) {
                        // 不合法的定义**不注册**，且把原因记下来（用户能查到为什么没生效）。
                        issues.filter { it.isError }.forEach {
                            dev.praxis.ui.design.diagnostics.UiDiagnostics.note(
                                "custom-tool", "自定义工具 ${def.name} 未装载：${it.message}",
                            )
                        }
                        return@forEach
                    }
                    register(dev.praxis.core.tool.custom.CustomTool(def, runner))
                }
            }

            // ## 溢出存储的取回入口（2026-11-06）
            //
            // 长输出落盘后只给模型一个定位符。**没有这个工具，定位符就是
            // 一句空话** —— 模型知道「还有更多内容」却永远看不到。
            //
            // 与 `LinuxToolPortImpl` 里那份 `SpillStore` 指向同一个目录（懒创建），
            // 所以这里新建一个实例也能读到同样的文件。
            // 与 LinuxToolPortImpl、ChatRepository 共用同一个 single：
            // 三处指向同一目录，但只有一处定义目录（见上面 SpillStore 的注册）。
            registerAll(
                dev.praxis.data.spill.ReadSpillTool(get()),
            )
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
