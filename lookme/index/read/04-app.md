# 04 · :app（应用装配层）通读与审计

> 审计者：agent-5（只读 + 写文档）。审计时间：2026-11-16。基线：C:/creat-agent/main/Praxis。
> 结论优先：第 2 节是「这个模块到底是什么」，第 5 节是「哪里有问题（G 编号）」；第 4 节回答 lead 点名的 7 个问题。
> 本文引用格式：路径用 «»，标识符 / 代码片段用 「」。行号形如 «文件:行»。

## 1. 范围与文件清单（数量核对）

| 范围 | 目录里实际数量 | 我读完的数量 | 行数 |
|---|---|---|---|
| app/build.gradle.kts、lint.xml、proguard-rules.pro | 3 | 3 | 738 |
| app/src/main/AndroidManifest.xml | 1 | 1 | 215 |
| app/src/main/java/**（全部 .kt） | 86 | 86 | 21647 |
| app/src/main/res/**（xml） | 10 | 10 | 216 |
| app/src/main/cpp/**（CMakeLists.txt、pty.c） | 2 | 2 | 417 |
| app/schemas/dev.praxis.data.db.PraxisDatabase/**（json） | 9 | 9 | 3094 |
| app/src/test/**（.kt） | 12 | 12 | 3137 |
| app/src/androidTest/**（.kt） | 1 | 1 | 139 |
| app/src/main/assets/qnnlibs/**（.so） | 20 | 只做清单，未读内容 | 二进制 |
| app/src/main/jniLibs/arm64-v8a/**（.so） | 5 | 只做清单，未读内容 | 二进制 |
| **合计（非构建产物）** | **149** | **149（100%）** | **29603 行文本 + 25 个 .so** |
| app/.cxx/**（CMake/Ninja 构建产物） | 119 | 0（**不是源码**，未计入） | - |

- 数量核对：app/ 下总共 268 个文件 = 149（源码/资源/schema）+ 119（.cxx 构建产物）；目录里没有别的隐藏文件（已 readdir 核对）。
- 单测规模：12 个单测类共 **118 个 @Test**；androidTest 1 个类 2 个 @Test。
- 盘点过的 assets 清单（只列名，未读二进制）：qnnlibs 下有 libQnnSystem.so、libQnnHtp.so，以及 V68/V69/V73/V75/V79/V81 各一套 Htp + Skel + Stub（共 20 个）；jniLibs 只有 arm64-v8a：libproot.so、libproot-loader.so、libtalloc.so、libandroid-shmem.so、libstable_diffusion_core.so（5 个）。
- 为回答「内置家是否与上游一致」，**另只读**了仓库外的权威源 «C:/creat-agent/Agent2API/desktop-tauri/ui/preset-providers.js»（303 行，未被修改）。为解释一条已知红灯，另只读 «core/imagegen/src/main/.../ImageDownloadPlan.kt» 与 «ImageModelCatalog.kt»。

## 2. 模块职责（一段话）

**:app 不是「一个界面」，而是 Praxis 的组装层 + 全部 Android 侧实现**：它提供 Koin 的整个运行时图（**61 个单例**：58 个在 appModule、2 个在 gatewayModule、1 个在 networkModule）、Room 数据库与 14 条手写迁移、四个真出站 provider（OpenAiCompat / Anthropic / Responses / 网关路由）、proot 容器与 PTY 终端、本地生图引擎的进程与运行时、模型下载（前台服务）、插件 WebView 运行时、宿主 shell、溢出存储、以及单 Activity（MainActivity）+ 六个 Compose 装配文件（PraxisApp 2512 行是全部「谁需要什么」的唯一真相点）。**职责本身是清楚的、注释密度罕见地高**；问题几乎全部集中在三类：①「能力写好了但写入方/消费方在重构中被摘掉」（溢出存储、spillStore、gateway_model 启停）；②「同一份东西被实例化了两次」（ProotEnvPort、GatewayProvider 三份、OkHttpClient 两份）；③「单测/护栏把当前实现钉成了契约，而契约本身已经退化」（MessageContentGuardTest 把「不再截断」钉住、WiringGuardTest 只查注册不查写入方）。

## 3. 逐文件一表（124 个文本文件，一个不少）

### 3.1 构建 / 清单 / 资源 / 原生 / schema（25）

| 文件 | 职责 | 对外关键 API/类型 | 疑点 |
|---|---|---|---|
| build.gradle.kts | 版本号、签名、NDK（只 arm64 + debug 加 x86_64）、packaging useLegacyPackaging、noCompress tar.gz/tar.xz、lint 配置、把 schemas 挂进 androidTest assets、依赖清单、单测 4 分钟超时 | praxisVersionCode 覆盖、BuildConfig.DEFAULT_BASE_URL / API_KEY | ① 注释仍写「缺 6.json 不影响 —— 测试从 7 升到 8」，但实际 schema 只有 1/2/3/4/5/7/9/10/11，**12~16 一个都没有**，迁移测试无从覆盖新链（G6）；② 顶层注释说「内置 rootfs 在 assets/rootfs」，**仓库里没有 assets/rootfs 目录**（只有 qnnlibs），内置镜像缺失 → 只能走下载（G17） |
| lint.xml | 只压 mipmap-anydpi-v26 的 ObsoleteSdkInt，按路径精确限定 | - | 与 build.gradle.kts 的 lintConfig 一致，无问题 |
| proguard-rules.pro | release 保命规则：枚举/Room/Kotlin 元数据/Serializable/JNI/Compose/fan.**/miui.**/com.sevtinge.**/FTP+SLF4J 缺类 dontwarn | - | 244 行里 12 段都是「真机崩溃换来的」；唯一要注意的是它对 R8 高度依赖，任何新增反射入口都要补规则（未发现遗漏） |
| src/main/AndroidManifest.xml | 权限全集（含 MANAGE_EXTERNAL_STORAGE / QUERY_ALL_PACKAGES / SYSTEM_ALERT_WINDOW）、usesCleartextTraffic=true（有意放开明文，长注释解释）、三个 service（截屏 mediaProjection、模型下载 dataSync、通知监听）、FileProvider | - | ① 通知监听服务的注释被截屏的注释挤到上面，两段注释与声明错位（可读性问题）；② `enableOnBackInvokedCallback=true` 与 MainActivity 里「取宿主 NavigationEventDispatcherOwner」的机制强耦合，改动风险高（见 MainActivity 行） |
| res/values/strings.xml | 只有 app_name | - | 全部文案硬编码在 Kotlin 里（feature 层同样），无 strings 资源体系 |
| res/values/colors.xml | splash/window 过渡底色 praxis_window_background=#FFFBFE + 启动图标底色 | - | 与 Compose 真底色（莫奈取色）仍可能不同色 —— 注释承认是「差异最小」而非「一致」 |
| res/values-night/colors.xml | 深色过渡底色 #1C1B1F | - | - |
| res/values/themes.xml | Theme.Praxis（windowBackground 走实色、状态栏透明、lightStatusBar=true）+ Theme.Praxis.Starting（SplashScreen） | - | windowLightStatusBar 硬编码 true，靠 values-night 覆盖（已在 night 里处理） |
| res/values-night/themes.xml | 深色 parent 换 Material、lightStatusBar=false | - | - |
| res/xml/backup_rules.xml | Android 11- 全量排除备份 | - | 与 data_extraction_rules 同义，两个都写是对的 |
| res/xml/data_extraction_rules.xml | Android 12+ 云备份与设备迁移全排除 | - | - |
| res/xml/praxis_file_paths.xml | FileProvider 只暴露 cache/praxis_camera/ | - | 与 CameraCapture 的 authority 拼接一致（packageName + ".praxis.fileprovider"，manifest 用 applicationId 展开） |
| res/drawable/ic_launcher_foreground.xml | 矢量前景（白色 P 形） | - | - |
| res/mipmap-anydpi-v26/ic_launcher.xml | adaptive-icon（含 monochrome） | - | 目录名不能动（lint.xml 里解释过），无问题 |
| src/main/cpp/CMakeLists.txt | 编 libpty.so，链接 log，加 -Wl,-z,max-page-size=16384（16KB 页对齐） | - | 与 Pty.kt 的 System.loadLibrary("pty") 对应；只有 arm64 产物，x86_64 模拟器必然降级管道（已知且已在注释里说明） |
| src/main/cpp/pty.c | forkpty + execve 的 JNI；4 个导出 + 5 个「兼容别名」符号 | Java_dev_praxis_core_terminal_Pty_00024Companion_* 与不带 Companion 的别名 | ① 同一份库里有**两套命名**（Companion 与非 Companion），说明当初没在真机验证过；现在靠别名兜住，属技术债（G19）；② 子进程失败已补 LOGE（好）；③ tt.c_cc[VMIN]=1/VTIME=0 无问题 |
| schemas/1.json | v1：sessions(4) | - | 存在 v1 导出，但**代码里没有 MIGRATION_1_2**（全仓 grep 为 0 命中）→ 停在 v1 的设备升级到 16 会抛「A migration from 1 to 2 was required but not found」（G4） |
| schemas/2.json | v2：+messages(10) | - | - |
| schemas/3.json | v3：+memories(6) | - | - |
| schemas/4.json | v4：sessions +pinned | - | 迁移写的是 ADD COLUMN ... NOT NULL DEFAULT 0，而**导出 schema 里这一列 defaultValue=undefined**（9 个 json 全量核对，无任何列带 defaultValue）→ DDL 与期望 schema 的「默认值」不一致（Room 的比较规则决定这是否致命，未确认，G5） |
| schemas/5.json | v5：+character_cards(19) +prompt_tags(7) | - | - |
| schemas/7.json | v7：sessions(6)、messages(12) | - | 缺 6.json（当时未导出），迁移测试只能从 7 起跑 |
| schemas/9.json | v9：messages +reasoning、memories(7) | - | 缺 8.json |
| schemas/10.json | v10：排序键换 createdAt | - | 缺 12~16.json（G6） |
| schemas/11.json | v11：sessions +contextUsed/contextLimit | - | 同上；v11 是导出链的最后一站 |

### 3.2 src/main/java（86 个 .kt）

| 文件 | 职责 | 对外关键 API/类型 | 疑点 |
|---|---|---|---|
| dev/praxis/PraxisApplication.kt | 初始化顺序（Logger→Timber→Koin→自检）、CameraCapture.cleanStale、UiDiagnostics.install、MemoryMonitor.start、内存压力升档清 Markdown 缓存、onTrimMemory 转发 | PraxisApplication | ① Koin 只装 appModule/networkModule/gatewayModule 三个模块，feature 层一律走参数注入（符合纪律）；② 自检只查 systemPrompt，不查 apiKey/DB（覆盖面窄，非缺陷） |
| dev/praxis/MainActivity.kt | 单 Activity；splash 钉屏直到主题流发射；provide LocalNavigationEventDispatcherOwner（取宿主默认值，不自建）；ProvisionGate 闸门；PraxisApp(...) | MainActivity、ReadyState（死类，见疑点） | «MainActivity.kt:356» 的 private data class ReadyState 已无引用（注释承认「已无意义」）→ 死代码 |
| dev/praxis/ProvisionGate.kt | 读 provision 的 sp 判断是否已引导、按类名拉起 DefaultActivity、把引导期档位转存进 SettingsRepository | ProvisionGate.isDone/launch/importFromProvision | 只用 runBlocking 写一次档位；类名硬编码为字符串（刻意解耦），若上游改包名会静默失效（runCatching 兜住 → 档位不转存，用户不会知道） |
| dev/praxis/di/AppModule.kt（895 行） | **整个运行时图**：58 个 single（DB/DAO/设置/网关运行态/生图/终端/工具/插件/能力检测）+ 约 20 个工具注册 | appModule | 见 4.1；疑点：single<AiProvider>（:411）与 single<ToolContext>（:882）属死注册/冻结快照（G10/G11）；:597 availableSizes 恒空（G13） |
| dev/praxis/di/GatewayModule.kt | KeystoreCipher（默认别名，与设置页同一把密钥）+ GatewayAccountStore（注入 clock 便于拨钟测试） | gatewayModule | 网关凭证加密只有这一处，别名与 SettingsRepository 共用（有意，注释解释） |
| dev/praxis/di/NetworkModule.kt | 全局 OkHttpClient（15s/120s/120s，debug 挂 BASIC 日志，不设 proxy/证书） | networkModule | **全仓零消费者**（PraxisApp 自己 remember gatewayHttpClient）→ G10；注释仍是「Phase 1 先把约定定下来」的口气，已过期 |
| core/terminal/Pty.kt | JNI PTY 封装：start/destroy/resize/isCanonicalMode/availableBytes；libpty 不可用时 available=false 降级 | Pty、Pty.start、NoopProcess | NoopProcess.isAlive 覆写是必要的（默认实现会得到反的结论）；ptyMaster 为 -1 时各方法返回安全默认值，正确 |
| data/ChatRepository.kt | 会话/消息仓储：ensureSession（先查再插，不用 REPLACE）、appendMessage/variant（每会话一把 Mutex）、insertMessageAt（锚点 5 项校验，失败退化追加）、loadMessagesOnce（顺手推高时间戳水位）、删除/置顶/改标题全部走单条 UPDATE、loadContextUsage | ChatRepository（706 行） | ① 「guardContent」现在是**恒等函数**（«:96-105» 直接 return text），`spillStore` 参数**在整个文件中未被使用**（grep 确认）→ 256K 保护与落盘能力都被移除，CursorWindow 2MB 崩溃风险回归（G3）；② 历史 KDoc（appendLocks/insertMessageAt）与实际一致，无过期 |
| data/RoomChatPersistence.kt | ChatPersistence 的薄适配（190 行全是转发 + 字段映射） | RoomChatPersistence | 极少数「注释说必须覆写」的方法确实都覆写了（insertMessageAt/deleteMessage/updateSessionTitleIfMatches），无问题 |
| data/RoomMemoryStore.kt | MemoryStore 的 Room 实现；findSimilar 在内存做 Jaccard | RoomMemoryStore | spaceId 双向映射都在（历史坑已修），无问题 |
| data/MemoryPortImpl.kt | 记忆端口：recall（全库 + RRF）/remember/recent/setActiveSpace；异常一律兜成空 | MemoryPortImpl | activeSpace 用 @Volatile 字段而非 flow，切空间只影响本实例（当前只有一份，可接受） |
| data/FileSkillSource.kt | filesDir/skills/<dir>/*.md | FileSkillSource | 只扫一层（有理由），无问题 |
| data/ToolWorkflowPort.kt | 工作流调工具（绕过 ToolExecutor 的 ASK 交互） | ToolWorkflowPort | 空串归一为 {}，正确 |
| data/ai/OpenAiCompatProvider.kt | OpenAI 兼容出站：SSE 解析 + 非流式分支 + 图片数组 content + tool_calls 拼接/空 arguments 归一 + 内容拦截（200+content_filter）+ 自定义头校验 | OpenAiCompatProvider、emitNonStreaming（internal，可测） | 自定义头「后加不覆盖」是刻意（注释解释），但与「用户想换 Authorization 要留空 apiKey」这条隐含契约只写在注释里，UI 没提示 |
| data/ai/AnthropicProvider.kt | Messages 协议：x-api-key + anthropic-version、urlSuffix、toolTypeCustom | AnthropicProvider | 与 ResponsesProvider 有 ~80 行近乎逐字重复（结构冗余，但两份协议确实不同） |
| data/ai/ResponsesProvider.kt | Responses 协议 | ResponsesProvider | **内置 24 家里没有一家走 Responses**（20 家 OpenAiChat + 4 家 Anthropic），该分支只在「自定义家 id 被 ProviderProtocols.of 猜中」时可达（G14） |
| data/ai/SettingsBackedProvider.kt | 每次请求读设置：脱敏 → 网关判定（GatewayGate）→ CHAT 功能位 resolve → 节流（按配置缓存 RequestThrottle）→ OpenAiCompatProvider | SettingsBackedProvider、throttleFor | 节流器是 companion 静态 map（跨实例共享），语义正确但属全局可变状态，测试里要 resetThrottleCacheForTest；网关档位+总开关关闭时**直接抛错不回落**（刻意，注释解释） |
| data/ai/SettingsBackedTitleGenerator.kt | 标题生成（TITLE 功能位，非流式拼串） | SettingsBackedTitleGenerator | 注释说「不设 temperature 因为会被覆盖」——正确的自我认知，无问题 |
| data/ai/OkHttpModelCatalogFetcher.kt | /v1/models 拉清单，404/400/405 回退 /models | OkHttpModelCatalogFetcher | 回退只对以 /v1/models 结尾的地址成立；自定义家填了别名路径（如 /api/models）不会回退（可接受） |
| data/gateway/GatewayProvider.kt | 网关出站胶水：候选链 → RouterSession 换号 → 按协议选 provider → 用量/回放记录 | GatewayProvider、testModel | 见 4.3；核心疑点：跳过条件全是 `?: continue`（静默跳过，不写回放原因）、测试支路不吃 builtinModels（G1） |
| data/gateway/BuiltinProviders.kt | 24 家内置表（id/label/protocol/baseUrl/quirks） | BuiltinProviders.ids/label/baseUrl/protocol/quirks | 见 4.2；`needsClientEmulation` / `noAuth` 两个 quirks **没有任何消费点**（G12） |
| data/gateway/GatewayModelStore.kt | gateway_model 表读写（all/enabled/visibleModelIds/upsert/delete/clear） | GatewayModelStore | `enabled()` 只给 UI 用，**不参与选路**（PraxisApp/PraxisApp 都调 CandidateChain.of 而非 ofEntries，G2） |
| data/gateway/GatewayUsageStore.kt | 请求记录写入 + 报表聚合（今日/7/30 天，本地时区 minusDays(days-1)） | GatewayUsageStore | `tokensSince/countSince` 当前无调用点（报表只走 summarySince）→ 轻微死代码 |
| data/gateway/ProxyResolver.kt | 每账号出口：按 (id,host,port,user) 缓存 OkHttpClient | ProxyResolver.egressFor/clientFor/invalidate | `invalidate()` 全仓无调用点 —— PraxisApp 靠 remember(proxySettings) 重建 resolver 达到同样效果（依赖写法而非显式失效，脆弱但当前成立） |
| data/imagegen/EngineProcess.kt | 随包引擎进程管理（nativeLibraryDir 起进程、日志尾部 200 行、EngineLaunchRules 校验） | EngineProcess.start/stop/isRunning/logTail/engineFile | 单例 + 无超时守护：引擎自杀后 isRunning 立刻 false（正确）；但**进程崩溃时无自动重启**，用户必须手点（可接受，UI 有「重新检测」） |
| data/imagegen/QnnRuntimeInstaller.kt | 把 assets/qnnlibs 解到 filesDir/qnn/<stamp>/，整目录替换 + 完整性校验 | QnnRuntimeInstaller.install/isInstalled/coveredVersions | STAMP 常量写死 "qnn-3.0.0"，与随包引擎版本对齐靠人（注释说明）；换 QNN 忘改戳 → 新旧 .so 混用（已有注释警告） |
| data/imagegen/EngineLocator.kt | /health 探活（1s/2s 短超时，坏 JSON 归 MalformedHealth） | EngineLocator.probe | 端口硬编码 EngineProtocol.DEFAULT_PORT（与 EngineProcess.port() 各存一份，未共享，弱耦合） |
| data/imagegen/ImageGenClient.kt | NDJSON /generate 流式客户端，取消即 cancel 连接 | ImageGenClient（ImageGenRunner 实现） | readTimeout=0（无限）由用户取消兜底；`generate` 不是 suspend 而是返回 Flow，符合契约 |
| data/imagegen/ImageGenRunner.kt | 端口：一次生成 | ImageGenRunner | - |
| data/imagegen/ImageModelDownloadManager.kt | 单飞 + 进度 StateFlow + 标志位取消 + acknowledge | ImageModelDownloadManager | 取消用标志位（阻塞 IO 无法中断）——设计正确；job=null 与终态写入顺序有注释解释 |
| data/imagegen/ImageModelDownloadService.kt | 前台服务（dataSync）：把 manager 状态翻成通知，终态可划掉 | ImageModelDownloadService.start | ① onStartCommand 每次都 startAsForeground（幂等）；② **服务停止时只 watcher.cancel()，不 cancel manager 的下载** → 服务被杀后下载仍在 appScope 里继续（内存/流量），无提示；③ 通知没有 PendingIntent（点了没反应，属体验欠账） |
| data/imagegen/ModelDownloader.kt | 两种包型下载：zip 解压（zip-slip 防护 + 剥一层目录）/ 多文件 + 标记；文件级续传（重下删 .part）；长度校验；失败清理 | ModelDownloader.download（阻塞）、DownloadResult 四档 | ① `spaceWasUnknown` 语义清楚；② 并发用 runBlocking + Semaphore（阻塞 IO 的合理折中）；③ 已知红灯「中途失败会清理残留并让 isInstalled 保持为假」：静态阅读未见必然失败点（多文件包判据要求 marker 且 unet.bin 已落地不影响），**未确认红因**（本地不能跑 gradle） |
| data/imagegen/RemoteImageGenerator.kt | OpenAI 兼容 /v1/images/generations（只支持 base64 响应，url 形式返回 null） | RemoteImageGenerator / OkHttpRemoteImageGenerator / RemoteImageOutcome | url 形式明确不支持（会说清），无静默降级 |
| data/imagegen/ImageGatewayAccounts.kt | 远端生图走账号池的端口 + 回退全局 Key 的显式语义 | ImageGatewayAccounts / ImageGatewayAccount | 接口注释解释了「为什么回退」，无问题 |
| data/imagegen/GatewayImageAccountPool.kt | RoutePlanner 选一个号（不换号），baseUrl 三级优先 | GatewayImageAccountPool | 与 GatewayProvider 的 baseUrl 优先级一致（account → provider 表 → 内置）；report 更新同一个 RouteState（与文本共享） |
| data/imagegen/GalleryWriter.kt | MediaStore 写相册（10+ 才支持，9- 返回 Unsupported），IS_PENDING 两段写 | GalleryWriter.save | 失败会删半截条目，正确 |
| data/imagegen/GalleryImagePort.kt | feature:chat 的 ImageGalleryPort 实现（base64 解码 + 时间戳命名） | GalleryImagePort | - |
| data/imagegen/NetworkStatus.kt | Wi-Fi/以太网判定（NetworkCapabilities） | NetworkStatus.isOnUnmeteredWifi | - |
| data/linux/ProotEnvPort.kt（1917 行） | 设置页侧的 proot 环境：内置资产优先/多镜像下载（逐条原因）/tar 格式魔数探测/xz 纯 Java 预解/硬链接识别与排除+补副本/扁平化/目录骨架/权限归一化/原子替换/exec（argv 双候选）/startSession/liveSession/usage/clearSharedTmp/resetEnvironment/FTP 起停/环境设置读写/detectInstalledDistros | ProotEnvPort、TarFormat、parseHardLinkLine（internal 可测） | ① 单文件 1917 行、职责 10+ 项，是 :app 最重的类（可维护性）；② 「PROOT_LOADER 必须留在 nativeLibraryDir」与 ProotLoaderStaging 的注释互为依据链，改一处就崩；③ applyEnv 与 ptyEnvironment 两份环境靠「逐字一致」维持（无共享函数）→ 潜在分叉（G19） |
| data/linux/ProotTerminalSessionPort.kt（1312 行） | AI 的 6 个终端会话工具后端：PTY 优先 + 管道降级、哨兵行协议（$? 先存 + pwd 落盘）、ScreenTap 环形缓冲 + pending 队列（2000 / 4000 上限）、CR/DEAD 内部标记、cwd 刷新、resize/writeBytes/outputFlow | ProotTerminalSessionPort、SENTINEL、isExpectedShutdown | ① 哨兵协议天然不支持全屏程序（已在注释里承认）；② `closing` 集合的自清逻辑写得比 v1 稳（process.isAlive 判据）；③ ScreenTap 用 java.lang.Object 做 lock 的理由正确 |
| data/linux/ProotLoaderStaging.kt | loader 原地取 nativeLibraryDir（两个历史名）+ 依赖库按运行时名铺到 filesDir/proot/lib 并设执行位 | ProotLoaderStaging.stage/stageLibs/DEPENDENCY_LIBS/stale | 依赖库铺在 filesDir 是「库」不是「可执行文件」，SELinux 允许（loader 才必须原地），这个区分在注释里讲清了 |
| data/linux/ProotPtySessionAdapter.kt | TerminalSessionPort → PtySessionPort 形状转换；保留 lastError；sessionNames 过滤 closed | ProotPtySessionAdapter | open 里 create 幂等 + resize 失败不报错，正确 |
| data/linux/LinuxToolPortImpl.kt | AI 的 linux_exec 后端：proot 检查 → autoInstall（互斥）→ repairPermissions → execMutex 串行执行 → 收输出 | LinuxToolPortImpl | ① `spillStore`（lazy，:51）**从未使用**，:195 又把 output 直接给原串（注释自相矛盾：「不再落盘、不再截断」与字段注释「超限落盘」并存）→ G3；② status() 的 running 恒 false（LinuxStatus 里这个字段对 AI 永远是「未运行」，而实际上 AI 会话可能正在跑） |
| data/linux/LinuxTools.kt | linux_exec / linux_status / linux_list_tools 三个工具定义（含 description 里写死边界） | LinuxExecTool / LinuxStatusTool / LinuxListToolsTool、defaultDistroForTool | 输出不截断（用户要求），但 LinuxToolPortImpl 有 2×MAX_OUTPUT_CHARS 的软上限（以「不再截断」注释为准，实际靠 onLine 侧限长）→ 与工具侧「不截断」的声明不完全一致（G3 的次生面） |
| data/linux/PraxisFtpServer.kt | 进程内 Apache FtpServer（被动端口段、外部 IPv4、只读/并发/不限速） | PraxisFtpServer.start/stop/isRunning | 无 vsftpd 的 SELinux 死路已在注释里记录；FTP 生命周期与进程一致（刻意） |
| data/memory/MemoryMonitor.kt | 15s 轮询 + trim level TTL(60s) + 升档判定 + 只在变化时打日志 | MemoryMonitor.sample/pressure/snapshot/onTrimMemory | 非单例就会丢 trim（AppModule 是 single，正确） |
| data/memory/MemoryPortQueryAdapter.kt | MemoryPort → MemoryQueryPort 适配 | MemoryPortQueryAdapter | - |
| data/memory/MemoryTools.kt | search_memory / save_memory 工具 | SearchMemoryTool / SaveMemoryTool / MemoryQueryPort | - |
| data/plugin/FilePluginStorage.kt | 包目录布局 / 三段改名安装 / SharedPreferences 存启停与来源 / zip 剥一层 + 越界防护 / id sanitize | FilePluginStorage | ① installFrom 里 old.deleteRecursively() 在 rename 成功后才做，顺序正确；② sanitize 会把中文 id 变下划线，两个不同包可能撞名（未确认是否会被上游 id 规则排除） |
| data/plugin/DirectoryPluginArchive.kt | 已装包目录 → PluginArchive（entryNames/readText/readBytes，canonicalPath 防越界） | DirectoryPluginArchive | entryNames 每次全量 walkTopDown（包大时慢，但只在预检/装载时用） |
| data/plugin/TempDirZipArchive.kt | zip → cache 临时目录 → 按目录读，close 删除 | TempDirZipArchive.unzipInto | 与 FilePluginStorage.unzip 是**两份几乎相同的解压实现**（注释承认刻意不复用），属重复代码 |
| data/plugin/PluginMarketApi.kt | 只在 static.operit.app 拉列表 + 作者直链下载；fetchEntry/trackDownload 明确保留但会失败/空转 | PluginMarketApi | 列表 645KB/页，用 ManifestParser 解析 JSON（复用而非引 JSON 库，合理） |
| data/plugin/MarketOriginCodec.kt | PluginOrigin ↔ 字符串（| 分隔 + %7C 转义） | MarketOriginCodec | 未知前缀返回 null（不回落 Local），正确 |
| data/plugin/PraxisWebViewPluginRuntime.kt | 一包一 WebView；bootstrap 脚本 + 模块包装 + require 走 FileBridge 反射桥 | PraxisWebViewPluginRuntime（PluginScriptRuntime 实现） | ① 全部在 Dispatchers.Main 执行（WebView 限制）；② 每个包常驻一个 WebView，装载多个包时内存开销随包数线性增长（无上限/无 LRU） |
| data/shell/AndroidHostShellPort.kt | root → shizuku → app 三通道；stdout/stderr 分线程抽干；超时 destroyForcibly | AndroidHostShellPort、HostShellResult/HostShellChannel | hasRoot 不设长超时（1.2s）是刻意；Shizuku 走反射（不引依赖），合理 |
| data/shell/HostShellTools.kt | host_shell / host_shell_channels（档位 ROOT） | HostShellTool / HostShellChannelsTool | 回执里写明通道 + 分 stdout/stderr + 超时不写成 exit 124，质量高 |
| data/spill/SpillStore.kt | 溢出存储：saveText（随机定位符、预览首尾）/read（白名单校验）/prune | SpillStore | **saveText 全仓零调用**（grep 确认）→ 定位符永远不存在，read_spill 成了死工具（G3） |
| data/spill/ReadSpillTool.kt | read_spill 工具（STANDARD/Safe） | ReadSpillTool | 工具本身正确，但上游没有写入方（G3）；feature:chat 的 ChatWiringGuardTest 只断言「已注册」不查写入方 |
| data/terminal/DataStoreTerminalSnapshot.kt | 终端快照 v1 行式编码（T/L/S/H/I/A 前缀 + Cursor 校验） | DataStoreTerminalSnapshot | 版本头机制正确；flatten 把换行压成空格（多行历史命令会变形，已在注释承认） |
| data/terminal/TerminalSessionRegistry.kt | 记住 AI 建的会话名（StringSet，幂等） | TerminalSessionRegistry.remember/names/forget | forget 全仓无调用点（关闭会话时不摘名，靠下次 create 幂等兜住，轻微） |
| data/terminal/ShellCommandRunner.kt | 降级用 shell（/system/bin/sh -c，cwd=/，块读，超时强杀，cancel 先 destroy） | ShellCommandRunner | 非 PTY 的老问题（无 isatty）已在注释里承认；现在终端页优先 PTY，这条只在降级时用 |
| data/db/PraxisDatabase.kt | @Database(version=16, 10 实体)；14 条手写迁移；withCharacterTransaction | PraxisDatabase + MIGRATION_* | 见 4.5；另外 KDoc 顺序错乱（13_14 的注释挂在 14_15 上方、8_9 的注释在 MIGRATION_8_9 之前但描述的是 v9，:63-71 有明显串行） |
| data/db/SessionEntity.kt | sessions（pinned/displayOrder/contextUsed/contextLimit，索引 (pinned,displayOrder)） | SessionEntity | 索引名靠 Room 自动命名，与迁移里手写的一致（已核对） |
| data/db/SessionDao.kt | observeAll（pinned DESC, displayOrder ASC）/upsert（REPLACE）/find/touch/setPinned/setContextUsage/setTitle/setTitleIfMatches | SessionDao | **upsert 仍是 REPLACE**（只被 ensureSession 在「不存在」时调用，安全），KDoc 三次警告这条坑，防回归意识强 |
| data/db/MessageEntity.kt | messages（variantGroupId/variantIndex/reasoning，索引 (sessionId,createdAt)、(sessionId,variantGroupId)） | MessageEntity | 外键 CASCADE；索引与 MIGRATION_9_10/6_7 手写名一致 |
| data/db/MessageDao.kt | observeSession ORDER BY createdAt,id；更新只改单列；shiftOrderIndexFrom；变体三方法 | MessageDao | `insertAll`/`update`/`countInSession` 无生产调用点（潜在死方法） |
| data/db/MemoryEntity.kt / MemoryDao.kt | memories（spaceId，三索引）；all(spaceId?) 用 :spaceId IS NULL OR ... | MemoryEntity/MemoryDao | spaceCounts/clearSpace 无生产调用点（记忆页改用 SettingsRepository 的 spaces） |
| data/chat/ChatImagePreparer.kt | 两趟解码 + inSampleSize + 精修 + JPEG85 + >1.2MB 降质量；关闭压缩则原字节 | ChatImagePreparer.prepare | `TARGET_LONG_EDGE` 常量只作文档（实际走 policy.maxEdge），轻微冗余 |
| data/chat/SettingsPendingQueueStore.kt | PendingQueueStore 的 DataStore 实现 | SettingsPendingQueueStore | - |
| data/custom/CustomToolLoader.kt | .tool 文本格式解析器 + 目录扫描（不递归） | CustomToolFileParser.parse / CustomToolSource | 解析器对 `param:` 的 required 判定是「第二段含『必填』」，默认 true（漏写就是必填，方向正确） |
| data/backup/BackupExporter.kt | 整库导出（wal_checkpoint 后复制 + manifest）；恢复明确不做 | BackupExporter、BackupResult | 只导出不恢复（文档承诺的「恢复」缺失，属已知未完成，非缺陷） |
| data/attachment/CameraCapture.kt | FileProvider 目标文件 + 24h 清理 | CameraCapture.newTarget/cleanStale | cleanStale 现在有调用点（PraxisApplication），历史「写了没人调」已修 |
| data/attachment/ScreenCapture.kt | MediaProjection 一次性截图（Activity 授权 + Service 抓帧 + 30 次轮询 + stop） | ScreenCapture / *Activity / *Service | 用 Thread.sleep 轮询（在 Service 主线程！onStartCommand 里 sleep 最多 600ms × 30 = 会 ANR 风险）→ 见 G16 |
| data/attachment/LocationReader.kt | lastKnownLocation 三 provider 兜底，输出人类可读文本 | LocationReader.read | - |
| data/attachment/PackageInfoReader.kt | 已装应用元信息（不读 APK） | PackageInfoReader.readAll/format | - |
| data/attachment/PraxisNotificationListener.kt | 通知监听服务 + 静态 readActive（只读标题/正文/包名） | PraxisNotificationListener.readActive | 无实例缓存（刻意，避免陈旧 Service 引用） |
| data/attachment/SpeechReader.kt | 系统 TTS（异步初始化、4000 字分段、必须 shutdown） | SpeechReader / isAvailable | 生命周期由 ChatTab 的 DisposableEffect 管，正确 |
| tool/GenerateImageTool.kt | generate_image：目标分派（本地/远端）、参数校验、OOM 降档重试一次、count 逐张 seed 递增、失败带 saved | GenerateImageTool | ① `availableSizes` 由 AppModule 传 `{ emptyList() }` → invalid_size 分支**永不触发**（而 GenerateImageToolTest 专门测了它）→ 死分支（G13）；② 远端不返回 seed（诚实置 null），好 |
| tool/SetImageGenConfigTool.kt | set_image_gen_config：边界规则委托 core（ImageGenConfigRules）、只改传了的字段、diff 回报 | SetImageGenConfigTool | 只允许切本地目标（远端要用户自己选）——与 4.7 的取舍一致 |
| tool/ListImageGenModelsTool.kt | list_image_gen_models：本地清单 + installed + current | ListImageGenToolsTool | remote 恒空数组 + 一句说明（不编数据），好 |
| ui/PraxisApp.kt（2512 行） | 全应用装配 + 目的地分派 + 所有 Flow 收集 + 所有 lambda 接线 | PraxisApp | 见 4.4；核心疑点：三处构造 GatewayProvider（参数各不相同）、linuxEnvPort 与 Koin 里的 ProotEnvPort 是两份、probe 不吃 builtinModels |
| ui/ChatSidebar.kt | 侧栏：会话列表 / 多选批量置顶删除 / justDeletedId 守卫 / 插件入口 | ChatSidebar | **单条删除入口不可达**：`pendingDelete` 只在 4 处被置 null，从未被赋值成 session.id（长按改成进多选），于是 :434 那条「确认删除」按钮的 visible 恒 false（G9） |
| ui/shell/MainShellPage.kt | 底栏 + HorizontalPager（beyondViewportPageCount=1）+ 侧栏进度驱动的缩放/位移/圆角 + LocalBottomBarInset 唯一来源 | MainShellPage | 常量 64dp/12dp/28dp 与 FloatingBottomBar 内部值靠注释「必须同步改」，无编译期约束 |
| ui/shell/ChatSessionsPage.kt | 会话列表二级页（点两次删除、置顶、新建并 popNow） | ChatSessionsPage | 与侧栏是两套删除实现（有意，注释解释「两个入口各修一半最坏」） |
| ui/tab/ChatTab.kt（840 行） | 对话 Tab 容器：restoreIfNeeded（含 ON_RESUME 补一次）、TTS 下滑沿触发、附件七件套（图/文件/位置/通知/截屏/拍照/记忆/应用）、模型池三参、底部设置条全部回调 | ChatTab | ① 附件面板 8 个回调全传（历史「传了没人用」已修）；② 截屏在协程里 30s 轮询（IO 线程 OK） |

### 3.3 测试（12 单元 + 1 仪器）

| 文件 | 职责 | 断言要点 | 疑点 |
|---|---|---|---|
| test/.../ui/WiringGuardTest.kt（1429 行） | 38 条源码级护栏 | 见 4.8 逐条 | 依赖文本匹配（重命名即失效，注释里承认）；多条断言把「当前实现形状」当契约 |
| test/.../data/AnchoredInsertTest.kt | 中间插入锚点 5 种情形 | 拒绝时退化追加、正常取中点、头/尾各 ±1 | FakeDao 大量 TODO()（刻意，走错路径就炸） |
| test/.../data/MessageContentGuardTest.kt | 「正文守卫」 | 4 例：短内容原样、无 spill 不截断、**超长整段入库**、流式不截断 | **类 KDoc 与断言互相矛盾**：开头写「库里任何一条正文都不超过 MAX_STORED_CHARS」，4 例却在钉「原样入库」→ 把「不再截断」固化成契约，等于给 G3 上锁 |
| test/.../data/MemoryPortImplTest.kt | 记忆端口 6 例 | recall 相关性/limit/吞异常/空库，remember 透传/吞异常 | - |
| test/.../data/ToolWorkflowPortTest.kt | 工作流端口 7 例 | 未知工具、空参归 {}、异常转文本、档位现取 | - |
| test/.../data/ai/OpenAiCompatProviderNonStreamingTest.kt | 非流式 5 例 | 200+content_filter 抛 CONTENT_BLOCKED；已有正文则不抛；缺 choices 报 PROTOCOL | - |
| test/.../data/imagegen/ModelDownloaderTest.kt | 下载器 9 例 | 单 zip/嵌套目录/多文件/空间不足/中途失败/zip-slip/进度/取消/体积未知 | 「中途失败…isInstalled 保持为假」是 STATE.md 记录的红灯，**红因未确认** |
| test/.../data/imagegen/ImageModelDownloadManagerTest.kt | 编排 5 例 | 单飞、Completed、Transport 失败、取消≠失败、acknowledge | runBlocking 里补 Unit 的坑已写在注释 |
| test/.../data/linux/HardLinkParseTest.kt | 硬链接行解析 12 例 | GNU ` link to ` 与 toybox ` -> ` 两套措辞、符号链接/目录/文件名含 linkto 不误判 | - |
| test/.../data/linux/ProotLoaderStagingTest.kt | loader 两决策点 12 例 | 优先短横线、认下划线、都无返回 null、stale 三判据、只查源码里不许拷到 filesDir、talloc→.so.2 | 两条是「读源码断言」（无法真正跑 stage()） |
| test/.../data/linux/SessionShutdownTest.kt | isExpectedShutdown 4 例 | 关闭标记才吞异常，真故障必须可见 | - |
| test/.../tool/GenerateImageToolTest.kt | 生图工具 10 例 | 参数/无模型/引擎不可用/invalid_size/count 越界/成功/seed 递增/第二张失败带 saved/空图 | 其中 invalid_size 那条在生产里**永远走不到**（G13） |
| androidTest/.../db/PraxisDatabaseMigrationTest.kt | MIGRATION_7_8 验证 2 例 | 表重建为空、三索引齐全；第二例断言「不写 spaceId 时为 ''」 | 第二例**逻辑上必然失败**：migration 建的是 `spaceId TEXT NOT NULL` 且无 DEFAULT，INSERT 省略该列会触发 NOT NULL 约束（G6） |

### 3.4 二进制（只做清单，未读内容）

- assets/qnnlibs/：20 个 .so —— libQnnSystem、libQnnHtp，以及 V68/V69/V73/V75/V79/V81 每个的 Htp + Skel + Stub（+ 4 个带 Stub 的小文件）。由 QnnRuntimeInstaller 全量解到 filesDir/qnn/qnn-3.0.0 供引擎 --lib_dir 使用。
- jniLibs/arm64-v8a/：libproot.so（proot 本体改名）、libproot-loader.so（PROOT_LOADER）、libtalloc.so（运行时名 libtalloc.so.2）、libandroid-shmem.so、libstable_diffusion_core.so（随包生图引擎，CC BY-NC 4.0，仅自用）。**只有 arm64**，x86_64 模拟器必然降级（PTY 与引擎都不可用）。
## 4. 关键机制深挖

### 4.1 DI 完整接线图（Q1）：61 个单例，谁提供什么、三个生命周期对象

入口：«PraxisApplication.kt:90-93» startKoin 只装 **3 个模块**（appModule / networkModule / gatewayModule）；feature 层一律不吃 Koin（由 :app 传参数），这是全项目纪律。

**appModule（58 个 single，«di/AppModule.kt»）按用途分组：**

| 组 | 提供的类型（注册行） | 谁消费 |
|---|---|---|
| 存储 | PraxisDatabase(:22)、gatewayUsageDao(:101)、SessionDao(:177)、MessageDao(:178)、MemoryDao(:179)、CharacterCardDao(:182)、PromptTagDao(:185)、GatewayAccountStore（gatewayModule:26）、KeystoreCipher（gatewayModule:24） | 几乎所有人；GatewayAccountStore 被 PraxisApp / GatewayImageAccountPool / GatewayProvider 共用 |
| 设置 | SettingsRepository(:398) + 三个共用同一 DataStore 文件的适配器：TerminalSnapshotPort(:379)、TerminalSessionRegistry(:391)、PendingQueueStore(:406) | 全应用唯一配置源 |
| 网关运行态 | RouteState(:107)、RouteReplayLog(:149)、GatewayUsageStore(:171)、GatewayModelStore(:463) | **文本请求与生图共用同一个 RouteState**（:138 的 ImageGatewayAccounts 也吃它）→ 「冷却/熔断共享」这条设计成立 |
| 生图 | QnnRuntimeInstaller(:120)、EngineProcess(:129)、ImageGatewayAccounts(:138)、CoroutineScope(:452)、ModelDownloader(:457)、EngineLocator(:459)、ImageGenRunner(:461)、ImageGenProgressBus(:468)、RemoteImageGenerator(:469)、ImageModelDownloadManager(:472)、ImageGalleryPort(:152) | PraxisApp 的设置页 + GenerateImageTool（工具侧另有一份 lambda 装配，见 4.7） |
| 会话/聊天 | ChatRepository(:357)、ChatPersistence(:369)、MessageTimestampAllocator(:356)、SpillStore(:344)、MemoryPort(:321)、MemoryStore(:213)、MemoryRetriever(:216)、MemoryWritePipeline(:217)、AiProvider(:411) | ChatViewModel 经 :app 参数拿；**AiProvider 无任何消费者（G10）** |
| 终端/Linux | ProotEnvPort(:446)、TerminalSessionPort(:524)、PtySessionPort(:532)、TerminalSnapshotPort(:379)、LinuxEnv 相关（ProotEnvPort 内的 FTP） | AI 工具 + 终端页；**PraxisApp 自己又 new 了一份 ProotEnvPort（G2）** |
| 工具 | ToolRegistry(:539，内含 20+ 工具注册)、ToolExecutor(:878)、ToolPermissionState(:881)、ToolContext(:882) | ChatViewModel / 工作流 |
| 插件 | PluginStorage(:233)、PluginRepository（:247，同时注册 externalArchiveFactory 钩子）、MarketApi(:271)、PluginScriptRuntime(:285)、PluginRuntimeHost(:290)、PluginActivation(:298)、SkillSource(:220)、SkillRegistry(:223) | PraxisApp 启动时 activateAll |
| 其它 | CapabilityDetector(:892)、CapabilityRequester(:893)、ModelCatalogFetcher(:438)、MemoryMonitor(:430)、BackupExporter(:157)、WorkflowToolPort(:313)、WorkflowEngine(:319) | 设置页 / 权限页 |
| networkModule | OkHttpClient（15s/120s/120s，debug 挂 BASIC 日志） | **零消费（G10）** |

**三个关键对象的生命周期（都在 :app 以 single 持有）：**

| 对象 | 创建 | 启动/使用 | 停止/释放 | 真实风险 |
|---|---|---|---|---|
| **RouteState**（:107） | Koin 单例，构造时 breaker enabled=false、failureThreshold=3、openMillis=60s | 每次请求前由 GatewayProvider/GatewayImageAccountPool 读；设置页改策略时 «PraxisApp.kt:367-369» 用 updateBreakerConfig 推入（不重建，避免清运行态） | **从不持久化**：进程死即清空（设计如此，注释写明） | 无（唯一要注意的是它跨文本/生图共享，任何「重置」都会同时影响两条路） |
| **QnnRuntimeInstaller**（:120） | 无状态，只持有 assets 与目标根 | 用户点「启动引擎」时 «PraxisApp.kt:2044» install()：整目录解到 filesDir/qnn/qnn-3.0.0，幂等，stamp 变更即整目录重建 | 不释放（文件常驻） | 换 QNN 忘改 STAMP → 新旧 .so 混用；磁盘占用约 100+MB 且**没有清理入口** |
| **EngineProcess**（:129） | 持有 Process? + 日志尾部 200 行 + stopping 标志 | onStartEngine(:2027-2071) → EngineLaunchRules.validate → ProcessBuilder（nativeLibraryDir）→ 守护线程收日志；isRunning 由 Process.isAlive 判 | onStopEngine(:2072-2076) 调 stop()（destroy） | **没有随 Application 生命周期回收**：进程被杀（用户划掉/系统回收）时引擎子进程不会自动死 → 端口 8081 被孤儿占用，下次 start() 只能报 StartFailed（G15） |

### 4.2 BuiltinProviders 的 24 家（Q2）＋ 与上游逐字段核对

表在 «BuiltinProviders.kt:78-158»，顺序即上游目录顺序；协议由表决定（:168「表里有就按表，没有才按 id 猜」）。我把它与仓库外权威源 «Agent2API/desktop-tauri/ui/preset-providers.js»（303 行）**逐条比对了 id / 名称 / 协议 / baseUrl / quirks**：

| # | id | label | protocol | baseUrl | quirks | 与上游一致 |
|---|---|---|---|---|---|---|
| 1 | openai | OpenAI | OpenAiChat | https://api.openai.com/v1 | 无 | ✅ |
| 2 | anthropic | Anthropic | AnthropicMessages | https://api.anthropic.com | headers=Anthropic-Beta | ✅ |
| 3 | openrouter | OpenRouter | OpenAiChat | https://openrouter.ai/api/v1 | headers=HTTP-Referer+X-Title | ✅ |
| 4 | huggingface | HuggingFace | OpenAiChat | https://router.huggingface.co/v1 | 无 | ✅ |
| 5 | nvidia | NVIDIA | OpenAiChat | https://integrate.api.nvidia.com/v1 | 无 | ✅ |
| 6 | deepseek | DeepSeek | OpenAiChat | https://api.deepseek.com | 无 | ✅（上游另有 Anthropic 端点提示，未搬） |
| 7 | glm | GLM Coding | AnthropicMessages | https://api.z.ai/api/anthropic | BETA headers + ?beta=true | ✅ |
| 8 | glm-cn | GLM 中国 | OpenAiChat | https://open.bigmodel.cn/api/coding/paas/v4 | 无 | ✅ |
| 9 | minimax | Minimax Coding | AnthropicMessages | https://api.minimax.io/anthropic | BETA + ?beta=true + anthropicToolType | ✅ |
| 10 | minimax-cn | Minimax 中国 | AnthropicMessages | https://api.minimaxi.com/anthropic | 同上 | ✅ |
| 11 | siliconflow | SiliconFlow | OpenAiChat | https://api.siliconflow.com/v1 | 无 | ✅ |
| 12 | volcengine-ark | 火山方舟 | OpenAiChat | https://ark.cn-beijing.volces.com/api/coding/v3 | 无 | ✅ |
| 13 | alicode | Alibaba | OpenAiChat | https://coding.dashscope.aliyuncs.com/v1 | 无 | ✅ |
| 14 | alicode-intl | Alibaba Coding | OpenAiChat | https://coding-intl.dashscope.aliyuncs.com/v1 | 无 | ✅ |
| 15 | alims-intl | Alibaba Studio | OpenAiChat | https://dashscope-intl.aliyuncs.com/compatible-mode/v1 | 无 | ✅ |
| 16 | alitp-intl | Alibaba Token Plan | OpenAiChat | https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1 | 无 | ✅ |
| 17 | xiaomi-tokenplan | 小米 MiMo (Token Plan) | OpenAiChat | https://token-plan-sgp.xiaomimimo.com/v1 | 无 | ✅ |
| 18 | opencode-go | OpenCode Go | OpenAiChat | https://opencode.ai/zen/go/v1 | 无 | ✅（上游注明 Go 面**不要**伪装，一致） |
| 19 | opencode-zen | OpenCode Zen | OpenAiChat | https://opencode.ai/inference/v1 | noAuth=true、needsClientEmulation=true（**未实现**） | ⚠️ 上游是 clientEmulation='opencode'（默认开启，能用）；Praxis 只标记不实现 → 这家在 Praxis 上「暂不能连」 |
| 20 | clinepass | Cline Pass | OpenAiChat | https://api.cline.bot/api/v1 | 9 个 Cline 伪装头 | ✅ 与上游 head 表逐字相同 |
| 21 | ollama-local | Ollama（本地） | OpenAiChat | http://localhost:11434/v1 | noAuth=true | ✅ |
| 22 | sub2api | Sub2API | OpenAiChat | null（自建） | 无 | ✅ |
| 23 | newapi | NewAPI | OpenAiChat | null（自建） | 无 | ✅ |
| 24 | sensenova | 商汤日日新 | OpenAiChat | https://api.sensenova.cn/compatible-mode/v1 | 无 | ✅ |

结论（可直接用于移植对账）：**24 家的 id/顺序/label/协议/baseUrl 与上游 100% 对齐**（22 家有确定地址，2 家自建），quirks 的 headers / urlSuffix / anthropicToolType 也逐字一致；**唯一「有而未搬」的是 opencode-zen 的 clientEmulation**。上游还有三类字段 Praxis 完全没搬：`balance`（6 家余额查询方案：deepseek/zhipu/zai/opencode/sub2api/new_api/sensenova）、`hint`（含「另一协议端点的地址」这类可操作信息）、`placeholder`/`icon`。协议分布：OpenAiChat 20 家、AnthropicMessages 4 家、**Responses 0 家**（G14）。

### 4.3 GatewayProvider 一次请求的完整流程（Q3）

入口：AiProvider.stream(request) → streamInternal(request, isTest=false)（«GatewayProvider.kt:126-127»）。测试走 testModel(model) → streamInternal(probe, isTest=true)（:140-168）。

```
streamInternal(request, isTest)
 ├─ 1. rules = modelRules()                                   // 每次现取，用户改映射下一句生效
 ├─ 2. chain = CandidateChain.of(
 │        requestedModel = request.model,
 │        nativeModels = store.nativeModels() + builtinModels(),  // 自定义家(表) + 内置家(设置里拉下来的清单)
 │        mappings = rules.toRoutingMappings(),
 │        defaultProvider = null,
 │        groups = groups())                                    // 路由组 §5.5
 ├─ 3. 空候选 → 抛 INVALID_REQUEST「模型 X 没有对应任何上游」(明确，不静默)
 ├─ 4. views = store.routingViews(); providers = store.providers()   // 快照
 ├─ 5. session = RouterSession(candidates, accounts={views}, state=RouteState,
 │        needsImages = 任一消息带图, budget=FailoverBudget, policy=policy())
 ├─ 6. tokenBudget = policy().toTokenBudget()；enabled 时 session.setBudget(budget, accountSpend(windowStart))
 └─ 7. while(true) {                                           // ← 换号循环在这里
        attempt = session.next() ?: break                        // 决策来自 core:gateway
        view     = views[id]            ?: continue              // 静默跳过（不记原因）
        credential = store.credentialOf(id).getOrNull() ?: continue
        apiKey   = (credential as? ApiKey)?.apiKey ?: continue   // OAuth 账号一律跳过
        baseUrl  = view.baseUrlOverride ?: providers[provider].baseUrl ?: providerBaseUrl(provider) ?: continue
        protocol = BuiltinProviders.protocol(provider)            // 表优先，回退 ProviderProtocols.of
        client   = clientFor(accountId)                           // ProxyResolver（每账号出口）
        quirks   = BuiltinProviders.quirks(provider); record = providers[provider]
        headers  = customHeaders + (record?.decodeHeaders(extraHeadersJson) ?: quirks.headers)
        urlSuffix= record?.urlSuffix ?: quirks.urlSuffix
        provider = when(protocol) { Anthropic → AnthropicProvider(toolTypeCustom=quirks.anthropicToolType)
                                    Responses → ResponsesProvider   // 无来源可达
                                    OpenAiChat → OpenAiCompatProvider }
        try {
          provider.stream(request.copy(model = attempt.wireModel)).collect { emitted = true; 记 Usage; emit(it) }
          session.onSuccess(attempt); record(成功); replay(成功); return@flow
        } catch (AiException e) {
          if (e.kind == CANCELLED) throw e                       // 用户取消不算失败
          session.onFailure(attempt, e.kind.toFailureKind(), e.retryAfterMillis)
          if (emitted) throw e                                   // ★ 已吐过字绝不再换号
        }
      }
      候选耗尽 → 抛 NETWORK「网关没有可用的账号（这次请求已经试过所有候选）」
外层 catch(非 CANCELLED) → record(失败, STATUS_NO_RESPONSE) + replay(Exhausted/NoCandidate/GaveUp 的人话) → rethrow
```

- **候选模型怎么拼**：`store.nativeModels()` 只扫 gateway_provider（自定义家），`builtinModels()` 是设置里的 BuiltinModelsCodec 结果（内置家拉下来的清单）。少一半就是「账号在、选不出模型」——这正是 KDoc 里点名过的历史事故。
- **重试/切换在哪**：本类不实现重试策略，只有「循环 + session.next()」；退避/熔断/限额/粘性/轮数上限全在 core:gateway 的 RouterSession + RouteState + FailoverBudget（budget 由 :app 用 GatewaySystemSettings.maxSwitches 构造）。唯一的本地规则是「已 emitted 则不再换号」。
- **测试模型走哪条支路**：同一条 streamInternal，只是 request 是「ping」+ maxTokens=1，isTest=true 让 record 记成测试请求（报表排除、日志页包含）。失败判定靠「收到过任何事件 = 通」。**但 «PraxisApp.kt:790-821» 构造的测试实例没传 builtinModels**，也没传脱敏规则 → 见 G1/G18。
- **与文档的偏差（自身注释已承认）**：AiErrorKind 没有「体积超限/内容拦截/令牌过期」的完整分类（实际上 core 已有 CONTENT_BLOCKED/TOKEN_EXPIRED，此段注释过期）。

### 4.4 PraxisApp 的网关与生图参数传递链（Q4）

**网关有三个 GatewayProvider 构造点，参数互不相同：**

| 参数 | A 聊天（:1103-1143） | B 标题生成（:1349-1388） | C 模型测试 probe（:790-821） |
|---|---|---|---|
| store / state | 同一个 gatewayAccountStore / gatewayRouteState | 同 | 同 |
| modelRules | gatewayModelRules（真规则） | **空 GatewayModelRules()**（注释说「还没接」，实际早已持久化 → 注释过期） | gatewayModelRules |
| policy | { gatewayRoutingPolicy } | **未传（用默认顺序策略）** | { gatewayRoutingPolicy } |
| recordUsage / accountSpend / replayLog / groups / budget / debugRawTraffic / clientFor / client | 全传 | 全传 | 全传 |
| **builtinModels** | ✅ 传（gatewayBuiltinModels 摊平） | ✅ 传 | ❌ **未传** |
| 脱敏（sanitizeRules） | 由外层 SettingsBackedProvider 施加 | 同（但 B 的 provider 也是 SettingsBackedProvider） | **绕过 SettingsBackedProvider，无脱敏** |

→ 同一条「生产链路」被复制了三份，其中两份已经漂移（G1/G8/G18）。

**生图链路（哪儿拿什么、谁用什么）：**

| 数据/能力 | 在 PraxisApp 里 | 传给的 Composable / 消费者 |
|---|---|---|
| imageGenCatalog / installedIds / installedModels | remember(imageGenSocModel/imageGenRefreshTick) 读文件系统 | SettingsHost（模型库、起引擎判断） |
| imageGenAvailability（设备门控） | ImageGenAvailabilityRules.decide(ABIS, SOC_MODEL) | SettingsHost + GenerateImageTool（工具侧再算一次） |
| imageGenTargetDecoded / CurrentTarget | ImageGenTargetCodec.decode(imageGenTargetJson)（坏内容保留原文、不当没配） | SettingsHost 选择器 + onStartEngine |
| engineBundledInstalled / engineRunning / engineLaunchMessage | engineProcess.isInstalled/start/stop/logTail | SettingsHost 的引擎卡片 |
| engineQnnInstalled / engineQnnCoverage | qnnRuntimeInstaller.isInstalled/coveredVersions | 同上 |
| 下载 | onDownloadImageGenModel → ImageModelDownloadService.start；onCancel → manager.cancel；state 订阅 manager.state | SettingsHost |
| 默认画布/采样、输出（自动存相册）、下载设置 | 三个 Codec decode + 三个 setXxx 回写 | SettingsHost；**工具侧另从 SettingsRepository 读同一份** |
| saveImage（落盘 + 自动存相册） | «AppModule.kt:598-639» | GenerateImageTool |
| availableSizes | «AppModule.kt:597» = { emptyList() } | GenerateImageTool（→ 该校验分支永不触发，G13） |

**「传了但没人用」的实证清单（逐条 grep 过）：**

1. «AppModule.kt:597» availableSizes = { emptyList() } → ImageGenParamsRules.validate 恒通过（G13）。
2. «PraxisApp.kt:537-547» linuxEnvPort 是 remember 出来的**另一份** ProotEnvPort，且只有它被注入 envSettingsStore；Koin 那个 single 的 envSettingsStore 永远为 null（G2）。
3. «PraxisApp.kt:339-352»（模型页候选）与 «PraxisApp.kt:441-460»（路由页队列预测）都用 gatewayAccountStore.nativeModels()（不含内置家）→ 这两处对内置家都是空的（G1 的同源问题）。
4. SettingsHost 的 **122 个参数在 SettingsHost.kt 内全部被引用**（词频最小 5 次，做过核对）→ 不存在「完全没人读」的形参；但 onTestGatewayModel 的实现把 provider 参数丢弃成 `_`（只按 model 测），与「按家测试」的语义不符。
5. «AppModule.kt:411» single<AiProvider>、«NetworkModule.kt:22» 的 OkHttpClient 在**全仓零消费者**（G10）。
6. «ChatRepository.kt:57» spillStore、«LinuxToolPortImpl.kt:51» spillStore 均未被使用（G3）。
7. «PraxisApp.kt:771» routeReplayEntries 与 :2128 gatewayReplayEntries 两条路径都在读 RouteReplayLog（前者算出来却只用于 remember 的 key 依赖，属于「算了没用」）。

### 4.5 PraxisDatabase 迁移链逐个核对（Q6）

@Database(version = 16)，10 个实体。builder 在 «AppModule.kt:36-98»，注册了 14 条：

| 迁移 | 内容 | 结论 |
|---|---|---|
| MIGRATION_2_3（:89） | 建 memories + 2 索引 | 与 v3 schema 一致 |
| MIGRATION_3_4（:121） | sessions +pinned NOT NULL DEFAULT 0 | DDL 带 DEFAULT，schema 无 defaultValue（G5） |
| MIGRATION_4_5（:154） | 建 character_cards(19) + prompt_tags(7) + 4 索引 | 与 v5 schema 一致（列名逐字核对） |
| MIGRATION_5_6（:220） | sessions +displayOrder DEFAULT 0 → 回填 -createdAt → 建 (pinned,displayOrder) 索引 | 与 SessionEntity 索引声明一致 |
| MIGRATION_6_7（:276） | messages +variantGroupId/variantIndex（可空）+ (sessionId,variantGroupId) 索引 | 与 v7 一致 |
| MIGRATION_7_8（:310） | **DROP memories 重建**（+spaceId、三索引） | 与 v8 一致；「故意删数据」有用户授权记录在 KDoc |
| MIGRATION_8_9（:350） | messages +reasoning（可空） | 与 v9 一致 |
| MIGRATION_9_10（:386） | 摘要行 createdAt 回填（前一条）+ 换索引 (sessionId,createdAt) | 与 v10 一致；回填 SQL 有 EXISTS 保护，正确 |
| MIGRATION_10_11（:431） | sessions +contextUsed/contextLimit DEFAULT 0 | 与 v11 一致；同样踩 G5 的 DEFAULT 张力 |
| MIGRATION_11_12（:547） | 建 gateway_account + gateway_provider | 与 core:data 的实体逐列核对一致（含 credentialsCipher NOT NULL） |
| MIGRATION_12_13（:600） | 建 gateway_model（21 列） | 与 GatewayModelEntity 一致（能力位全可空） |
| MIGRATION_13_14（:490） | 建 gateway_request（17 列）+ gateway_daily（7 列，复合主键） | 一致；注意 KDoc 顺序错位（13_14 的说明挂在 14_15 上方） |
| MIGRATION_14_15（:479） | gateway_provider +extraHeadersJson/urlSuffix（TEXT NOT NULL DEFAULT ''） | 一致 |
| MIGRATION_15_16（:471） | gateway_provider +modelsFetchedAt（NOT NULL DEFAULT 0） | 与 v16 实体一致 |

**缺口（三个，都真）：**
1. **缺 1→2**：schemas/1.json 存在（v1 真的发布过），但全仓 grep `MIGRATION_1_2` 零命中，builder 也没注册 → 停在 v1 的设备升级时会抛「A migration from 1 to 2 was required but not found」（G4）。
2. **schema 文件只到 11**：导出的只有 1/2/3/4/5/7/9/10/11（缺 6、8、12、13、14、15、16）→ MigrationTestHelper 只能覆盖 **7→8 一条**，12→16 这一段（含 hand-written 的全部门户表）**没有任何自动校验**（G6）。
3. **androidTest 第二例逻辑上必然失败**：«PraxisDatabaseMigrationTest.kt:122-137» 在 7→8 之后 INSERT 时省略 spaceId，而迁移建的表是 `spaceId TEXT NOT NULL` 且无 DEFAULT → SQLite 直接报 NOT NULL 约束（G6）。

另：9 个 schema 文件里**没有任何一列带 defaultValue**，而 4 条迁移写了 `DEFAULT 0`/`DEFAULT ''`。两处 KDoc 的说法互相矛盾（MIGRATION_3_4 说「必须写 DEFAULT」，WiringGuardTest 的 spaceIdColumn_hasNoDefaultValue 说「写了就崩」）。真实判定取决于 Room 的 TableInfo 比较规则对「实体来源 vs 数据库来源」是否跳过 defaultValue —— **未确认**，需要在有设备时用 androidTest 一次性定案（G5）。

### 4.6 终端：两套启动器 + 哨兵协议 + PTY（:app 最复杂的运行时）

```
用户/AI 两条入口，同一个 ProotEnvPort 取 LinuxEnvironment
├─ 一次性命令：ProotEnvPort.exec(env, cmd) → ProotCommand.execArgs → argv 双候选（别名/原名）
│    env 来自 prootEnv()：HOME/TERM/LANG/PATH/PROOT_TMP_DIR/PROOT_NO_SECCOMP/PROOT_LOADER/LD_LIBRARY_PATH
└─ 会话：ProotTerminalSessionPort.create(name)
     ├─ args = ProotCommand.loginArgs(env)（尾部是 rootfs 里探测到的登录 shell + -l）
     ├─ 先试 PTY：Pty.start(argv, ptyEnvironment(...), filesDir, 60x80) → 200ms 存活检查 → 采纳/记原因并降级
     ├─ 降级：ProcessBuilder(argv) + applyEnv(pb, env)（e.clear() 后重建，防继承 Android PATH）
     ├─ 泵线程：按字节 read → 遇 \n/\r 成行 → tap.emitRaw（终端页）+ tap.offer（哨兵队列）
     │    半行 flush 前做 safeFlushLength（UTF-8 边界，修乱码）
     ├─ 命令：<cmd> ; __praxis_rc=$? ; pwd > /tmp/.praxis_cwd ; echo "__PRAXIS_DONE__:$__praxis_rc"
     │    读侧：pollLine 直到 SENTINEL / DEAD_MARKER(会话死) / 超时(先 Ctrl+C 不销毁会话)；跳过 CR_MARKER
     └─ 关会话：先登记 closing（让泵线程把 read interrupted 当正常）→ 写 exit → PTY destroy（先发信号再关 fd）
```

- 屏幕快照：ScreenTap 保留最后 2000 行（不含 NUL 包裹的内部标记），供 get_terminal_session_screen；pending 队列上限 4000（防 `yes` 打爆堆）。
- 「AI 的会话 vs 用户的 tab」：ProotPtySessionAdapter.sessionNames() 把同一份会话表按名字透给终端页，TerminalSessionRegistry 持久化名字以便进程重启后重建 —— 三处一致性靠「同一个 ProotTerminalSessionPort 单例」，这也是 G2 里「PraxisApp 又 new 了一份 ProotEnvPort」最伤的地方（终端页与 AI 共用的是 Koin 那份 TerminalSessionPort，但设置页用的是另一份 ProotEnvPort）。

### 4.7 本地生图：从下载到出图的完整数据流

```
设置页点下载 → ImageModelDownloadService.start(context, id)        // 前台服务 dataSync
  → manager.start(model)：单飞 + 门控（仅 Wi-Fi / 坏配置）→ ImageDownloadPlanner.plan
  → ModelDownloader.download（runBlocking + Semaphore 并发；zip 解压带 zip-slip 与剥一层目录；多文件写 marker）
  → manager.state = Completed(modelId)
PraxisApp LaunchedEffect(imageGenDownloadState)                    // :652-679
  → imageGenRefreshTick++（重算 installedIds/installedModels）
  → ImageGenTargetSelection.afterInstall（装完自动选中；配置坏了不动原值）
用户点「启动引擎」→ onStartEngine(:2027)
  → target = 当前 Local 目标；model = imageGenInstalledModels[modelId]
  → EngineLaunchRules.requiresQnn(backendType) ? qnnRuntimeInstaller.install() → libDir
  → spec = EngineLaunchRules.specFor(model, enginePath, modelDir, libDir)
  → engineProcess.start(spec) → 失败时给「userMessage + logTail(6)」
AI 调 generate_image → GenerateImageTool.execute
  ├─ target() 为 Local → probe() 探活 → 设备门控 → runner.generate 逐张（OOM 降一档重试一次）
  │    → saveImage 落 filesDir/images/gen_<ts>.jpg → 读 ImageOutputSettings.autoSave → GalleryWriter
  └─ target() 为 Remote → gatewayAccounts.pick(provider, model) 取池账号（key+baseUrl）
       → 失败回退 apiKey() 全局 Key → OkHttpRemoteImageGenerator（只吃 base64 响应）
       → report(accountId, success, kind) 把结果写回共享 RouteState
```

### 4.8 WiringGuardTest 的 38 条断言（Q5，改动前必读）

文件：«app/src/test/java/dev/praxis/ui/WiringGuardTest.kt»（1429 行，38 个 @Test，另有 30s 单例超时 Rule）。全部是**读源码文本**的静态断言。

| # | 行 | 方法 | 断言（简） |
|---|---|---|---|
| 1 | 82 | chatTab_passesModelPoolToChatScreen | ChatTab 必须传 models / activeModelIndex / onSelectModelIndex / onRemoveAttachment |
| 2 | 103 | chatScreen_forwardsModelPoolToSettingsBar | ChatScreen→ChatSettingsBar 三个具名实参 |
| 3 | 122 | chatScreen_passesOnDismissToAttachmentPanel | 附件面板必须有 onDismiss 外点关闭 |
| 4 | 135 | memorySpaces_areWiredAcrossAllLayers | repo/route/screen/host/app 五层都接 spaces + 新建入口 |
| 5 | 172 | spaceIdColumn_hasNoDefaultValue | PraxisDatabase.kt 里不允许出现「spaceid 且 default」的代码行 |
| 6 | 204 | terminalScreen_isWiredToRealPty | ptyPort 传入 + onRawKey + addTab→startPtySession + submit 有 PTY 分支 |
| 7 | 248 | chatRestore_restoresToolIdentity | 恢复路径必须 tool = if (role == BubbleRole.Tool)、必须 unwrapToolSummary |
| 8 | 286 | bottomSheets_shareSameComponent | AttachmentPanel 用 OverlayDropdownPopup，禁自建 MiuixAnimatedPopup/CellView 与硬编码尺寸 |
| 9 | 335 | chatSettingsBar_allSectionsCollapsedByDefault | 五个分区必须 mutableStateOf(false) |
| 10 | 359 | chatSettingsBar_modelListIsSecondLevelFold | showModelList + FoldableContent |
| 11 | 391 | modelScreen_usesSettingsSectionHeader | ModelScreen 用 SettingsSectionHeader（≥7 个），禁 SmallTitle |
| 12 | 427 | chatProvider_honoursFunctionSlotChatOverride | SettingsBackedProvider 必须 resolve(CHAT) 并用其 baseUrl/model 与未配置判据 |
| 13 | 455 | chatTab_modelNameMatchesRuntimeResolution | ChatTab 用 chatDisplayModel，禁 modelSettings.activeModel 做显示名 |
| 14 | 476 | summaryModel_followsChatOverride | PraxisApp 必须有 forSummary = global.copy(...) 且 resolve 用 forSummary |
| 15 | 503 | customHeaders_are_actually_sent | OpenAiCompatProvider 收 customHeaders 并逐条 addHeader + isValidHeaderName；SettingsBackedProvider 必须透传 |
| 16 | 536 | requestThrottle_isWiredIntoRequestPath | 读 modelAdvancedSettings、发前 acquire/tryAcquire、finally permit?.close()、throttles 缓存、换配置 throttles.clear() |
| 17 | 576 | advancedSettings_roundTrip | repo 有 Flow 与三个 setter、请求头加密存储（cipher.encrypt）、ModelRoute 三处保存 |
| 18 | 599 | modelScreen_hasCustomHeadersAndQueueSections | 两个分区标题 + rpm/concurrency 进 onSave + dirty 判据含 RPM |
| 19 | 629 | modelPicker_hasSelectAllScopedToFiltered | 全选/取消全选只作用于 filtered |
| 20 | 648 | functionModelScreen_hasModelPicker | 功能位可用 ModelSelectDialog 且写回单值、拉取用功能位 baseUrl |
| 21 | 674 | modelSelectDialog_isSingleValueAndClickToPick | 存在 + 点击即选中 + 用 MiuixAnimatedDialog |
| 22 | 687 | modelScreen_headerSectionUsesFoldableContent | 请求头分区必须 FoldableContent(visible=headersExpanded)，禁 if 硬切 |
| 23 | 713 | attachmentPanel_usesAnimatedDropdown | OverlayDropdownPopup + show=visible + onDismissFinished |
| 24 | 738 | linuxEnv_runningStateSurvivesViewModelRecreation | 端口有 liveSession，refresh/start 都接管既有会话；实现侧有 isShellSession |
| 25 | 769 | terminal_reportsContainerSessionFailure | 端口暴露 lastError、适配器留住异常、ViewModel 打印「[当前不是 Linux 容器]」与「宿主环境」 |
| 26 | 820 | scrollRail_doesNotShareAxisWithNavigator | 回底按钮用 offset(y = maxHeight * 0.12f)，禁 96dp，BoxWithConstraints；轨道已删、scrollToItem(last) 仍在 |
| 27 | 881 | distroFamily_isActuallyConsumedByInstallScripts | 两段式：枚举带 packageManager + 每个成员填 family；出现第二种族后三处 apt-get 必须已改 |
| 28 | 957 | chatTitleGenerator_isWiredAndActuallyTriggered | 端口在 + 触发点必须是 `if (isFirstUserMessage) { persistScope.launch {` + :app 真传实现 + generate 实现还在 |
| 29 | 1018 | terminalCopy_usesSystemSelectionOverWholeBuffer | SelectionContainer + buildAnnotatedScreen，禁 LazyColumn items，禁自研选择模型 |
| 30 | 1065 | prootExecutable_neverComesFromAppDataDir | 禁 val alias = File(、launchArgs 两参签名、两启动器都设 PROOT_LOADER、staging 不铺别名、必须从 nativeLibraryDir 取 |
| 31 | 1135 | chatSidebar_deleteCurrentSessionGuardIsCorrect | 补行守卫必含 `currentSessionId != justDeletedId`、删除处记标记、标记能自清、LaunchedEffect key 三参 |
| 32 | 1180 | rootfsExtract_actuallyUsesHardLinkParser | listHardLinkEntries 必须调 parseHardLinkLine(line)?.let |
| 33 | 1199 | sessionReadInterruption_sharesOnePredicate | 两个分支都走 isExpectedShutdown，禁内联旧判据 |
| 34 | 1225 | scrollToBottom_visibilityGoesThroughPredicate | 走 shouldShowScrollToBottom、禁 visible = showWhenIdle、isAtBottom 被调用 |
| 35 | 1263 | prootChild_mustGetLibraryPath | 管道/PTY/ProotEnvPort 三条路径都设 LD_LIBRARY_PATH |
| 36 | 1302 | cornerRadius_goesThroughToken | **全仓**（app/core/feature/ui/preview）扫描 RoundedCornerShape(数字.dp) 必须为 0 |
| 37 | 1351 | appScaffold_backButtonHasBackground | AppScaffold 用 FloatingIconButton；按钮有 background/border/drawWithContent |
| 38 | 1402 | sidebarScrim_darkensInsteadOfLightening | 遮罩必须 Color.Black.copy(alpha=SIDEBAR_DIM_ALPHA)、禁 onSurface、alpha 跟随 progress |

**这些护栏的性质与使用注意：**
- 全部是「源码文本匹配」，重命名/换写法即失效（类 KDoc 明确承认这是可接受的代价）。
- 有 4 条是「读**别的模块**的源码」（#1/#2/#3/#6/#7/#12/#24/#25/#28/#29 涉及 feature:chat / feature:settings / feature:terminal / core:linux / ui:design）→ 跨模块重构会在这里红。
- #36 是唯一的**全仓扫描**（cornerRadius），任何新增 .kt 里的 `RoundedCornerShape(12.dp)` 都会立刻红。
- **护栏的盲区**（这是本轮审计最该记的一条）：它们只检查「能力是否注册/是否被调用」，不检查「有没有人往里写数据」。所以 `read_spill` 已注册（feature:chat 的 ChatWiringGuardTest:529-535 也只断言这点）、`GatewayModelStore.enabled` 已读、`builtinModels` 参数已存在 —— 看上去全绿，实际全断（G1/G3/G7）。
## 5. 缺口 / 疑点清单（G1～G22）

排序原则：先「挡住验收线」，再「会静默失效」，最后「债务与可维护性」。

### G1（严重·挡住验收线）模型测试的 GatewayProvider 没有内置家清单 → 内置家的「测试连接」必然失败
- **证据**：«PraxisApp.kt:790-821» 构造 gatewayProbeProvider 时没有传 builtinModels（对比 :1122 与 :1368 两处生产实例都传了）；而候选链依赖 store.nativeModels() + builtinModels()（«GatewayProvider.kt:182»），前者只扫 gateway_provider（自定义家）。所以对内置家调用 testModel() 时 chain 为空 → 抛「模型 X 没有对应任何上游」（:188-196）。
- **影响**：GOAL 验收线是「内置家能拉清单 → 建账号 → 选模型 → 真发出请求」。拉清单能过（走 OkHttpModelCatalogFetcher，不经 GatewayProvider），但「验证这家通了没」这个 UI 动作对 24 家全部失败，且错误像「模型名写错」，会把排查引向数据而不是接线。此外 probe 的 remember key（:790-798）不含 gatewayBuiltinModels，补参数时必须一起补 key。
- **建议**：把三处 GatewayProvider 的构造收敛成 :app 内唯一工厂；补 builtinModels 与 key；加护栏断言「每处 GatewayProvider( 之后必须出现 builtinModels =」。

### G2（严重·静默分叉）ProotEnvPort 被实例化两份：Koin 一份、PraxisApp 一份
- **证据**：«AppModule.kt:446» single { ProotEnvPort(androidContext()) }；«PraxisApp.kt:537-539» 又 remember(localContext) { ProotEnvPort(localContext) }，并在 :2435 把这一份传给 SettingsHost（Linux 环境页），:546 只给这一份注入 envSettingsStore；而 AI 的 3 个 linux 工具（AppModule:710）与 TerminalSessionPort（:524）用 Koin 那份。
- **影响**：① 设置页看到的运行中会话/磁盘占用/发行版探测与 AI·终端看到的不是同一份（两份 sessions 列表）；② Koin 那份的 envSettingsStore 恒为 null → loadEnvSettings 恒返回默认值；③ 这正是 ProotEnvPort KDoc 里点名的「AI 说装好了、设置页显示未安装」形态。
- **建议**：PraxisApp 改成 koinInject<ProotEnvPort>() 并删掉 remember 那份（或反过来在 AppModule 构造时注入 store）；加护栏断言 PraxisApp.kt 里不出现 ProotEnvPort( 的构造。

### G3（严重·功能已死）溢出存储三条写入路径全部退化为空，read_spill 成了死工具；CursorWindow 保护同时被移除
- **证据**：① «ChatRepository.kt:96-105» guardContent 直接 return text（KDoc 承认「不再截断」），构造参数 spillStore（:57）全文件未使用；② «LinuxToolPortImpl.kt:51» 的 lazy spillStore 全文件未使用，:195 直接 val output = raw；③ SpillStore.saveText 全仓 grep 零调用；④ 仍注册着 read_spill（AppModule:874）与单例 SpillStore（:344）。
- **影响**：① read_spill 留在工具列表里，模型可能去调，永远返回「找不到定位符」；② 数据库读侧风险回归：MessageDao.observeSession 是 SELECT *，CursorWindow 默认 2MB，一条超长正文会让「进会话即崩」重现（这正是当初引入 spillStore 的原因，KDoc 写得很清楚）；③ MessageContentGuardTest 的 4 条断言把「原样入库」钉成契约，类 KDoc 却仍写「库里任何一条正文都不超过 MAX_STORED_CHARS」——测试与文档互相矛盾，等于给这次退化上锁。
- **建议**：先定产品口径（用户 2026-11-07 的「去掉所有省略」是显示层要求，不等于库里不许防）；若保留不截断，必须补读侧防护（分页或 substr 投影），并把 read_spill 与 SpillStore 一起删掉或真正接回写入方；同时修正该测试的 KDoc。

### G4（严重·升级即崩）迁移链缺 1→2
- **证据**：schemas/1.json 存在（v1 真发布过）；全仓 grep MIGRATION_1_2 零命中；«AppModule.kt:36-98» 的 addMigrations 从 2→3 起。
- **影响**：停在 v1 的早期设备升级到 v16 时，Room 第一次开库抛 IllegalStateException: A migration from 1 to 2 was required but not found，表现与注释里记载的 12→13 事故相同（界面出来约 3 秒后崩）。
- **建议**：补 MIGRATION_1_2 并注册；或确认无 v1 存量后删掉 1.json 并写清依据（现在两者并存，语义矛盾）。

### G5（中等·未确认）迁移 DDL 的 DEFAULT 与导出 schema 的 defaultValue 不一致
- **证据**：MIGRATION_3_4 / 5_6 / 10_11 / 15_16 写 DEFAULT 0（或空串）；9 个 schema json 里没有任何一列带 defaultValue（已逐列核对）；两处 KDoc 互相矛盾（«PraxisDatabase.kt:111-120» 说必须写，«WiringGuardTest.kt:155-183» 说写了会崩）；护栏只覆盖 spaceId 一列。
- **影响**：若该 Room 版本对「实体期望值 vs 数据库实际值」也严格比较 defaultValue，这几条迁移会让所有老用户升级即崩；若不比较则无害，但现有护栏的论证是错的。两种结论都必须在设备上定案，不能只读代码。
- **建议**：在设备/模拟器上跑一次 3→16 与 10→16 的 MigrationTestHelper（需先补 6/8/12…16.json，见 G6）；在此之前把两条矛盾 KDoc 改成「实测结论待补」。

### G6（中等·护栏失效）迁移测试只覆盖 7→8，第二条用例逻辑上必然失败；schema 12～16 未导出
- **证据**：app/schemas 只到 11.json；«PraxisDatabaseMigrationTest.kt» 两个 @Test 都只跑 MIGRATION_7_8；第二条（:122-137）INSERT 时省略 spaceId，而 MIGRATION_7_8 建的是 spaceId TEXT NOT NULL 且无 DEFAULT（«PraxisDatabase.kt:313-322»）→ SQLite 必然报 NOT NULL 约束失败。
- **影响**：① 12→16 这段（4 条手写迁移、全部网关表）零自动校验，而它正是历史上两次「启动即崩」的区域；② androidTest 在 CI 里不跑（CI 只跑 :app:testDebugUnitTest），这条红灯只在有人手动连设备时炸，容易被误判成新引入的问题。
- **建议**：该用例改成显式插入 spaceId（或按 G5 结论给 DEFAULT）；补 12～16 的 ksp 导出与对应 MigrationTest；CI 增加 nightly androidTest。

### G7（中等·与 01-gateway.md G2 重叠）gateway_model 的 enabled 不参与选路
- **证据**：GatewayModelStore.enabled/visibleModelIds 只被 UI 用（«PraxisApp.kt:328-352»）；真正的候选链在 «GatewayProvider.kt:178» 与 «PraxisApp.kt:442» 都调 CandidateChain.of（不带 enabled 过滤），而 core:gateway 提供了 ofEntries。
- **影响**：用户在模型管理页关掉某模型，选路仍会选中它 → 假开关，违反 GOAL 铁律 1。
- **建议**：两处改调 ofEntries（并入 enabled 集合）或合并 GatewayModelRules.disabled；补一条护栏。

### G8（中等）第三处 GatewayProvider（标题生成）不吃 policy 与 modelRules
- **证据**：«PraxisApp.kt:1349-1388» 的 modelRules 传的是 { GatewayModelRules() }（注释「持久化还没接」已过期，:268 就有 gatewayModelRulesJson），且完全没有 policy 参数 → 用默认顺序策略。
- **影响**：① 标题生成不遵守用户的选路策略/粘性，可能落到与主对话不同的账号（症状是「标题经常失败而正文正常」）；② 禁用模型对标题生成无效。
- **建议**：与 G1 一起收敛到唯一工厂。

### G9（中等）ChatSidebar 的「单条确认删除」入口不可达
- **证据**：«ChatSidebar.kt:118» 的 pendingDelete 只在 :403/:413/:449 被置 null；长按（:408-415）已改成「进多选」，从未 pendingDelete = session.id；于是 :434 的 MiuixTextActionButton(visible = pendingDelete == session.id) 永远不可见，:430-433 还在解释「为什么不用弹窗」。
- **影响**：侧栏只剩多选批量删除一条路；单条删除要多点一次，与 KDoc 描述的交互不符；hint 写「长按会话可删除」，实际长按是进多选 —— 界面与注释都在说谎。无数据风险。
- **建议**：要么删掉 pendingDelete 与那段按钮（承认只保留多选），要么恢复待删入口；同步改 hint。

### G10（低·但很典型）两处 DI 注册全仓零消费者
- **证据**：«AppModule.kt:411» single<AiProvider> { OpenAiCompatProvider(BuildConfig.DEFAULT_BASE_URL, BuildConfig.API_KEY) } 无任何 koinInject/get 取用；«NetworkModule.kt:22» 的 single OkHttpClient 同样零消费（PraxisApp 自己 remember 了 gatewayHttpClient）。
- **影响**：① 读代码的人会以为 AI provider 走 BuildConfig，实际生产走 SettingsBackedProvider + 网关；② BuildConfig.API_KEY 这条「调试用 Key」是死路径，让「密钥只在设置页」看起来没兑现；③ networkModule 的 30 行注释（Phase 1 全局约定）已与事实脱节。
- **建议**：删掉两处注册（或让 GatewayProvider 默认 client 从这里取）；一并删掉无人消费的 API_KEY buildConfigField。

### G11（低）single<ToolContext> 是首次解析那一刻的冻结快照
- **证据**：«AppModule.kt:882-885» single<ToolContext> { state.snapshot() }；PraxisApp 组合期取一次传给 ChatViewModel。
- **影响**：改档位后这个 bean 不变。当前 ChatViewModel 另有 permissionSnapshotProvider（每批现取）→ 大概率无害；但只要有人直接用这个 bean 就会出现「改档位不生效」，且与 KDoc「保证一批调用用同一份档位」说法混淆（单例范围是整个进程）。
- **建议**：改 factory 或删除该注册，统一走 permissionSnapshotProvider。

### G12（低·影响 2 家可用性）quirks 两个字段无消费点；上游另有 4 类字段未搬
- **证据**：BuiltinQuirks.noAuth / needsClientEmulation 在 :app 与 core 全仓无使用点（只有赋值与 UI 文案）；GatewayProvider 鉴权固定走 (credential as? ApiKey)?.apiKey（:253），没有「无需鉴权」旁路。
- **影响**：① OpenCode Zen 在上游开箱可用（clientEmulation 补匿名凭证 + 会话头 + 桩工具），在 Praxis 上只能「暂不能连」——24 家里唯一的功能缺口；② Ollama（本地）虽标 noAuth，仍要在账号里存一个 Key 才不会被 `?: continue` 跳过（账号侧是否允许空 Key 未确认）→ 可能出现「本地 Ollama 永远选不中」。
- **建议**：要么实现 noAuth 旁路（跳过 apiKey 且不写 Authorization），要么 UI 明说「需填任意 Key」；clientEmulation 建议在 GOAL 里明确标为「不移植」。

### G13（低·假开关）availableSizes 恒空 → generate_image 的 invalid_size 分支永不触发
- **证据**：«AppModule.kt:595-597» 传 availableSizes = { emptyList() }（注释承认「暂时只做全局范围」）；GenerateImageTool :157-164 因此恒通过；而 GenerateImageToolTest :111-120 专门测了这个分支。
- **影响**：NPU 上不受支持的尺寸会被引擎静默回落 512（工具注释自己写着），用户拿到尺寸不对的图而没有任何提示；设置页「默认画布」与模型档位之间没有任何一致性校验。
- **建议**：把 core:imagegen 的模型档位接进 AppModule（或工具里按 backendType 查表），让这个分支活过来。

### G14（低）Responses 协议在网关里无任何可达来源
- **证据**：24 家内置家里 OpenAiChat 20 + Anthropic 4 + Responses 0；ResponsesProvider 只能由 ProviderProtocols.of(providerId) 对自定义家猜出来（«GatewayProvider.kt:288-315» 的 when 分支）。
- **影响**：ResponsesProvider（131 行 + 其 assembler）是「为将来准备的分支」，但没有任何数据能触发它；容易让审计误以为三协议都支持。
- **建议**：在内置表里显式标注哪家支持 Responses（若上游有），或把该分支标注「当前不可达」并去掉对应测试期望。

### G15（低·真实风险）进程级资源没有随 Application 生命周期回收
- **证据**：EngineProcess.start 用 ProcessBuilder 起引擎，无 shutdown hook（«EngineProcess.kt:82-112»）；ProotEnvPort.stopAllSessions 只在 resetEnvironment 里调（«ProotEnvPort.kt:1646»），PraxisApplication 没调；ImageModelDownloadService.onDestroy 只 cancel 观察者，不 cancel manager 的下载（«ImageModelDownloadService.kt:120-125»）。
- **影响**：① 引擎/容器在用户「用完直接划掉 App」后可能成为孤儿进程 → 下次启动端口占用，EngineProcess.start 只能报 StartFailed（用户看到「引擎起不来」）；② 下载服务被系统停掉后下载仍在 appScope 里跑（无通知无进度），用户以为停了。
- **建议**：启动时探活并接管/清理孤儿端口；引擎加随父进程退出语义；服务 onDestroy 显式 manager.cancel()。

### G16（低）ScreenCaptureService 在主线程 sleep 轮询抓帧
- **证据**：«ScreenCapture.kt:241-247» while (image == null && tries < 30) { acquireLatestImage(); Thread.sleep(20) } 运行在 Service.onStartCommand（主线程），最坏 600ms；随后 :262-264 还有两次 createBitmap + copyPixelsFromBuffer（全屏 ARGB，1080p 约 8MB）。
- **影响**：点截屏时界面可能冻一下，低端机上接近 ANR 级卡顿。
- **建议**：captureOnce 移到 HandlerThread 或协程 IO，onStartCommand 只负责 startForeground 后投递任务。

### G17（低·文档与产物不符）build.gradle.kts 承诺的「内置 rootfs」在仓库里不存在
- **证据**：«build.gradle.kts:258-281» 详细解释为什么 assets/rootfs 的 tar.gz/tar.xz 要 noCompress；ProotEnvPort.copyBuiltInAsset 也是「内置优先、缺失才下载」；但 app/src/main/assets 下只有 qnnlibs，没有 rootfs 目录。
- **影响**：Ubuntu/Kali 安装必然走下载（内置镜像那套「不碰网络」的收益完全没拿到）；noCompress 对不存在的文件无意义；KDoc 里「用户实测下不了 Ubuntu 后要求内置」这条需求实际未交付。
- **建议**：要么把镜像放进 assets，要么删掉 noCompress 与相关注释，并在 GOAL/STATE 里把「内置 rootfs」标为未完成。

### G18（低）GatewayProvider 两处注释与实现不符
- **证据**：① «GatewayProvider.kt:284-287» 注释写「额外头覆盖用户自定义头」，实现是 customHeaders + extraHeaders 后用 addHeader 追加（OkHttp 不覆盖）→ 同名头会出现两条；② 注释说测试「走同一条链路」，但测试实例绕过 SettingsBackedProvider，因此不做脱敏（脱敏在 SettingsBackedProvider 里）。
- **影响**：① 用户自定义 X-Title 又选 OpenRouter 时请求带两个 X-Title，个别网关会 400；② 测试通过≠生产通过（脱敏维度）。
- **建议**：把「覆盖」实现成真正的 set；测试路径要么也过 sanitize，要么 UI 上说明「测试不脱敏」。

### G19（债务）:app 的三处结构性重复 / 超长
- **证据**：① «ProotEnvPort.kt» 1917 行、10+ 职责；② applyEnv（ProotTerminalSessionPort:1048）与 ptyEnvironment（:966）是两份手写环境，靠「逐字一致」维持（KDoc 明说分叉过一次）；③ pty.c 里 5 个 JNI 名称各有 Companion / 非 Companion 两套（9 个导出）；④ TempDirZipArchive.extract 与 FilePluginStorage.unzip 是两份几乎相同的解压；⑤ PraxisApp 里三份 GatewayProvider（约 40 行 × 3）。
- **影响**：每次动「起 proot 的环境变量」或「解压规则」都要人肉同步两处，历史事故已证明会漏；JNI 双符号让「哪个名字是活的」无法静态确认。
- **建议**：抽 ProotEnvironment.of(env) 单一来源、抽 unzip 工具函数、抽 GatewayProviderFactory；pty.c 在真机确认后用单一命名重编。

### G20（债务·死代码清单，便于下一轮清理）
- **证据（全部 grep 确认无生产消费者）**：GatewayUsageStore.tokensSince/countSince、MessageDao.insertAll/update/countInSession、MemoryDao.spaceCounts/clearSpace、ProxyResolver.invalidate、TerminalSessionRegistry.forget、MainActivity 里的 ReadyState、LinuxToolPortImpl.spillStore、ChatRepository.spillStore、AppModule 的 single<AiProvider>、networkModule 的 OkHttpClient、SpillStore.saveText。
- **影响**：这些是「看起来在用」的噪声；其中 G3/G10 已单列。
- **建议**：一次性清掉，或加 @Deprecated 注明保留原因。

### G21（已知未完成）备份只有导出没有恢复；gateway_daily 是死表
- **证据**：BackupExporter 的 KDoc 明确「恢复故意不做」，AppModule 只提供 BackupExporter；GatewayDailyEntity 在 :app 无任何写入点（只注册了 gatewayUsageDao）。
- **影响**：与 01-gateway.md 的 G8 同源；对用户只是「能导出个文件」，与「整库备份」的命名不符。
- **建议**：设置页改名为「导出备份文件（暂不支持恢复）」，或补恢复流程（需真机验证 WAL/关闭库）。

### G22（债务）版本号双源
- **证据**：«build.gradle.kts:10-18» 的 PRAXIS_VERSION_CODE/NAME 与 build-plugins 里 PraxisBuildConfig 的常量，注释要求同步改。
- **影响**：发版漏改一处会「包内 versionName 与 CI 标签不一致」，且不报错。
- **建议**：改为从 gradle.properties 或 version catalog 单一读取。

---

## 6. 我没有做的 / 未确认的事项（如实标注）

1. **没有跑任何构建、单测、lint、gradle、真机/模拟器**（受本轮纪律约束）。因此 :app 的 118 个单测是否全绿，以及 STATE.md 记录的「ModelDownloaderTest 中途失败…」红灯的**真实红因**，都未验证；静态阅读未发现必然失败点（多文件包判据要求 marker，失败时不写 marker；partPaths 会被 cleanStale 清掉），故标「未确认」。
2. **Room 的 defaultValue 比较规则未确认**（G5）：需要一次 MigrationTestHelper 实测才能定论，不靠记忆下结论。
3. **androidTest 第二条用例必然失败**是纯静态推理（NOT NULL 无默认值 + INSERT 省略该列），未实跑（需设备）。
4. **二进制 .so 内容未读**（按指令只做清单）；libstable_diffusion_core.so 能否被 EngineProcess 真起起来、QNN 各版本覆盖哪些机型，属真机验证项。
5. **core:data 的 GatewayAccountStore 是否允许空 apiKey**（G12 的次生问题）未读（不在范围）；SettingsHost 的 122 个参数我只做了「是否被引用」的词频核对，未逐个核对语义。
6. 上游 preset 的 balance / hint 是否需要移植属产品决策，未给结论。

---

**一句话总结**：:app 的 124 个文本文件我都读完了（另一个不少，含 9 个 schema、pty.c、10 个 res xml、12 个单测与 1 个 androidTest）。这个模块的实现质量与注释密度在同类项目里属上乘，但**接线层出现了系统性退化**：能力都在、单测能过、护栏全绿，却有 4 处「能力写了没人写数据 / 写了没人读」（溢出存储、gateway_model.enabled、builtinModels 的第三处构造、noAuth 旁路），2 处「同一个东西实例化两次」（ProotEnvPort、OkHttpClient/AiProvider），1 处「升级即崩」（缺 1→2 迁移）。建议下一轮先做 G1/G2/G3 三件（都在 app 内，改动小、收益直接对应 GOAL 的两条验收线），再补 G4/G6 的迁移护栏。

