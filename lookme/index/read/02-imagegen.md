# 02 · core:imagegen + app 生图实现 + 引擎资产（通读 + 审计）

- 通读范围：`core/imagegen/src（40 个 .kt）`、`app/src/main/java/dev/praxis/data/imagegen（14 个 .kt）`、
  `feature/settings 生图三页（3 个 .kt，只读确认 UI 契约）`、`docs/PORTING-IMAGEGEN.md 全文`、
  `app/src/main/jniLibs` 与 `app/src/main/assets/qnnlibs` 资产清单（只 stat/枚举，0 个内容读入）
- 方法：逐文件完整阅读（未抽样）；本地**未跑**任何构建/测试/真机（硬纪律）；本报告全部结论均为静态阅读结论
- 角色：只读 + 只写本文件；未修改任何仓库文件、未 git add/commit/push

---

## 0. 结论先行

1. **这个模块不是「AI 生图功能」，而是「给一个外部原生引擎当瘦客户端」**：Praxis 自己不推理，只负责
   「拼命令行起进程 → 探 /health → POST /generate → 逐行读 NDJSON → 落盘 / 进相册」。
   core:imagegen 放**协议与规则**（纯 Kotlin，16 个测试类都在 JVM 跑），:app 放**进程 / HTTP / 文件**。
2. **链路静态看是通的，但只有 sd15cpu 一条最干净**：引擎 `libstable_diffusion_core.so` 确实在
   `jniLibs/arm64-v8a`（11,976,888 B ≈ 11.4 MB），QNN 20 件 156.1 MB 确实在 `assets/qnnlibs`，
   且 `packaging { jniLibs { useLegacyPackaging = true } }` 保证它会被解到 `nativeLibraryDir` 后可 exec。
   但**没有 `libdit_engine.so`** → DiT 四种（zimage / klein / qwen21 / krea2turbo）**必然起不来**；
   而且内置模型目录里**一条 DiT 模型都没有**（只有 4 条种子：2×SD1.5-NPU、1×SD1.5-CPU、1×SDXL）。
3. **最严重的三个问题**（详见 §5）：
   - **G1 下载器「失败 / 取消」竞态**：任一文件失败会把整次下载归成 `Cancelled` 而不是 `Failed`。
     这既是 CI 红灯（`ModelDownloaderTest` 那条）最可能的原因，也是真机上「下载失败却提示已取消」的成因。
   - **G2「已安装」判据与临时文件同处一个目录**：`.part` 与解压临时目录 `<id>_extract` 都放在
     `models/<id>/` 内部，而单 zip 包的判据是「目录非空」→ **中断 / 半解压会被显示成「已安装」**。
   - **G4 启动参数缺 patch**：`--patch` 在生产路径**从不传入**（`PraxisApp.kt:2055-2061` 没给 `patchFile`），
     `sd15npu` 非 512 画布会**静默回落 512**（文档 §3.1 明确点出的坑），用户拿到尺寸不对的图且无任何报错。
4. **与文档最大的落差**在「形态 A/B 识别」「模型库（导入 / 删除 / 校验）」「分辨率档位校验」「换模型重启引擎」
   「历史入库」——全部**如实没做**（页面与代码里都写了没做），不是假控件。
5. UI 契约与 :app 注入参数**一一对得上**，路由两处都注册（`Routes.kt:238/246` ↔ `PraxisNavDisplay.kt:154/158`），
   没有「点了没反应」的入口。

---

## 1. 范围与文件清单（数量核对）

| 范围 | 目录里实际 | 我读了 | 结论 |
|---|---|---|---|
| `core/imagegen/src/**/*.kt` | **40**（main 24 + test 16） | **40** | 100% |
| `app/src/main/java/dev/praxis/data/imagegen/*.kt` | **14** | **14** | 100% |
| `app/src/test/java/dev/praxis/data/imagegen/*.kt`（为问题 4 取证） | **2** | **2** | 100% |
| `feature/settings` 含 ImageGen 的页面 | **3** | **3** | 100% |
| `app/src/main/java/dev/praxis/tool/GenerateImageTool.kt`（链路必要） | 1 | 1 | 100% |
| `SetImageGenConfigTool.kt` / `ListImageGenModelsTool.kt` | 2 | 0 | **未读**（属 WS-R4 的 app 全量） |
| `docs/PORTING-IMAGEGEN.md` | 1（44,784 B） | 1 | 全文 |
| `AndroidManifest.xml` / `app/build.gradle.kts`（取打包事实） | 2 | 2 | 定点 + 关键段 |
| 资产 `app/src/main/jniLibs` | 5 个文件 | stat 全部 | 0 个内容读入 |
| 资产 `app/src/main/assets/qnnlibs` | 20 个文件 | stat 全部 | 0 个内容读入 |

### 1.1 引擎资产实测（只 stat）

`jniLibs/arm64-v8a/`（5 个，合计 12,307,177 B ≈ 11.7 MB）

| 文件 | 字节 | 用途 |
|---|---|---|
| `libstable_diffusion_core.so` | 11,976,888 | **推理引擎本体（形态 C 的可执行文件）** |
| `libproot.so` | 264,129 | 容器（非生图） |
| `libproot-loader.so` | 18,136 | 容器（非生图） |
| `libandroid-shmem.so` | 14,432 | 容器（非生图） |
| `libtalloc.so` | 33,592 | 容器（非生图） |

**`libdit_engine.so` 不存在** → DiT 四类不可用（与文档 §1.4.1「未产出」一致）。

`assets/qnnlibs/`（20 个，合计 163,684,556 B ≈ 156.1 MB）

- 公共库 2：`libQnnHtp.so`（3,979,248 B）、`libQnnSystem.so`（4,068,024 B）
- 六套 Hexagon × 3 件：V68 / V69 / V73 / V75 / V79 / V81（`libQnnHtpV**` + `…Skel` + `…Stub`）
- 与 `QnnRuntimePlan.ALL_FILES`（`QnnRuntimePlan.kt:24-37`）**一一对上，没有漂移**。

打包事实：`app/build.gradle.kts:220-222` 的 `useLegacyPackaging = true` 强制 extractNativeLibs，
这是 API 29+ W^X 下引擎唯一能被 exec 的位置（`EngineProcess.kt:22-31` 的 KDoc 讲清了）；
ABI 只 `arm64-v8a`（`app/build.gradle.kts:83-84`）。
---

## 2. 模块职责（一段话）

**core:imagegen 是「引擎契约 + 纯规则」层**：定义 `/generate` 的 19 个请求字段名、NDJSON 的两种消息、
9 种引擎 `--type` 及其校验与命令行拼装、模型目录与芯片档位后缀、「已安装」判据与下载计划 / 空间预检 / 失败清理、
生图参数范围与 7 类失败码、设备门控（ABI / SoC）、NDJSON 解析、远端 OpenAI 兼容 images 协议、
以及「哪些设置不许 AI 改」。它不碰 Android，只碰 `java.io.File`，所以 16 个测试类都能在 JVM 跑。
**:app/data/imagegen 是「手脚」**：起引擎进程（`EngineProcess`）、解 QNN 运行时（`QnnRuntimeInstaller`）、
探活（`EngineLocator`）、流式出图（`ImageGenClient`）、模型下载（`ModelDownloader` + 前台服务 + 编排层）、
远端出图与网关账号池、写相册。**feature/settings 的三页**只负责把状态画出来、把用户动作回抛。

---

## 3. 逐文件一表

> 表中「路径」列写的是文件名，所在目录见小节标题：
> 3.1/3.2 为 `core/imagegen/src/{main,test}/kotlin/dev/praxis/core/imagegen/`，
> 3.3 为 `app/src/main/java/dev/praxis/data/imagegen/`，3.4 为 `feature/settings/src/main/java/dev/praxis/feature/settings/`。

### 3.1 core/imagegen 主源（24 个）

| 路径 | 职责 | 对外关键 API / 类型 | 疑点 |
|---|---|---|---|
| `EngineAvailability.kt` | 探活结果三态 | `EngineAvailability.Available(health,source?)`、`Unavailable(reason,detail)`、`ImageGenEngineSource` | 与 `EngineLaunch.kt` 的 `EngineSource` **重复定义同一语义**（G3）；`Available.source` 恒 null |
| `EngineHealth.kt` | `/health` 模型 + 保守解析 | `EngineHealth(raw,ok)`、`EngineHealthCodec.parse` | `ok` 不与 HTTP 码组合（:23-24 已声明）；无 `status` 即算就绪 |
| `EngineLaunch.kt` | 起引擎参数 / 校验 / 命令行 / 由模型推参数 | `EngineLaunchSpec`、`EngineLaunchError+userMessage()`、`EngineLaunchRules{KNOWN_TYPES,QNN_TYPES,DIT_TYPES,validate,command,specFor,needsPatch}` | 生产路径**不传** `patchFile` / `qnnLibDir`（G4）；`KNOWN_TYPES` 认 9 类但目录无对应模型 |
| `EngineProtocol.kt` | 端点与字段名唯一出处 | `EngineProtocol{DEFAULT_PORT,PATH_*,healthUrl,generateUrl,…}`、`RequestField.ALL(19)`、`MessageField`、`MessageType`、`ImageFormat` | 无 `reference_images`（2.8.1/3.0.0 差异，:12-16 已声明） |
| `GenerateImageResultCodec.kt` | 工具结果 JSON → 图路径 | `GeneratedImage`、`parse` | `{"ok":true,"images":[]}` 会回落顶层 `path`（:47-54） |
| `ImageDownloadPlan.kt` | 体积解析 / 目标 / 计划 / 空间预检 / 清理清单 | `ModelSize.parse`、`DownloadTarget`、`ImageDownloadPlan`、`ImageDownloadPlanner.plan`、`SpaceDecision`、`SpaceCheck`、`DownloadCleanup` | `extractTempDir` 落在**模型目录里**（:72）→ G2；清理不含「已 rename 的成品」→ G7 |
| `ImageDownloadSettings.kt` | 下载源 / 仅 Wi-Fi / 并发 + 归一化 + codec | `ImageDownloadSettings{baseUrl,wifiOnly,concurrency}`、`Rules{normalizeBaseUrl,normalizeConcurrency,isValidBaseUrl}`、`Codec` | 类 KDoc（:16-23）仍称「并发数没做」，与已实现的并发（`ModelDownloader.kt:108`）**自相矛盾**（G8） |
| `ImageDownloadState.kt` | 下载状态机 | `DownloadProgress`、`ImageDownloadState{Idle,Running,Completed,Failed(Reason),Cancelled}` | 无「清理失败」这一档（清理失败=静默） |
| `ImageGenAvailability.kt` | 设备门控 / 降档 / OOM 关键词 | `ImageGenAvailability{NpuCapable,CpuOnly,Unsupported}`、`Rules.decide`、`ImageGenDownscale`、`ImageGenErrorClassifier` | 只判 ABI + SoC，**不判空闲内存**（文档 §9） |
| `ImageGenConfigRules.kt` | `set_image_gen_config` 边界 + diff | `Rules{notAllowedKeys,notImplementedKeys,isKnownKey,isSupportedOutputKey}`、`ImageGenConfigChange`、`ImageGenConfigDiff` | `scheduler` 无枚举校验 |
| `ImageGenDefaults.kt` | 默认画布 / 采样 + codec | `ImageGenDefaults`、`Rules.validate`、`Codec` | `scheduler` 任意字符串可入库 |
| `ImageGenError.kt` | 7 类失败码 | `ImageGenError{NoModel,Oom,UnsupportedDevice,EngineStartFailed,InvalidSize,ToolBusy,Remote}`、`CODE_*` | 远端细分码（`no_api_key/quota_exceeded/…`）**不在 core**，由工具硬写（G9） |
| `ImageGenEvent.kt` | 流事件两种 | `Progress(value,previewJpegBase64?)`、`Complete(bytes,seed,width,height)` | 无错误事件（设计如此）；`Complete` 手写 `equals/hashCode`（:39-54） |
| `ImageGenParams.kt` | 参数 + 范围 / 档位校验 | `ImageGenParams`、`ImageGenParamsRules{validate,formatSizes,WIDTH_RANGE…}` | 档位判定靠调用方传 `availableSizes`，而 :app 传空 → **档位校验形同虚设**（G5） |
| `ImageGenProgressBus.kt` | 生图进度总线 | `ImageGenProgress{targetLabel,phase,fraction}`、`Phase{Preparing,Running,Saving}`、`ImageGenProgressBus{state,publish,clear}` | 单例单流，`count>1` 时无「第几张」信息 |
| `ImageGenRequest.kt` | `/generate` 序列化（键序=文档序） | `toJson` / `toJsonObject` | 不含 `image/mask/aspect_ratio/ultrafix/tile_size`（P5，:19-25） |
| `ImageModelCatalog.kt` | 目录 / 芯片档位 / 标记名 / 安装判据 | `ImageModel`、`ImageModelRequirement`、`ChipsetTier`、`ImageModelCatalog{builtin,markerFileName,isInstalled}` | 只 4 条种子；zip 判据=目录非空（与 G2 冲突）；`SM8750→8gen2` 等映射**无权威来源**（G10） |
| `ImageOutputSettings.kt` | 输出设置 + 存相册三态 | `ImageOutputSettings`、`Codec`、`GallerySaveResult{Saved,Unsupported,Failed}` | `showPreview` 字段在但**未实现**（UI 不摆、工具固定 false） |
| `NdjsonReader.kt` | NDJSON 逐行解析（纯函数） | `NdjsonReader{parseLine,parseLines,read}` | base64 坏掉返回**空字节**的 Complete（:110-114）→ 上层报 `engine_start_failed`，语义略偏（G13） |
| `ParallelProgress.kt` | 并发进度求和 | `ParallelProgress.aggregate` | `fileName` 并发下无意义（:31-32 已注明） |
| `QnnRuntimePlan.kt` | QNN 清单 / 覆盖版本 / 缺件 | `QnnRuntimePlan{ALL_FILES,filesFor,isUsable,coveredVersions,missing}` | `isUsable` 只看 2 个公共库 → 半套也算「可用」 |
| `RemoteImageProtocol.kt` | 远端请求 / 地址推导 / 响应 | `RemoteImageRequest.toJson`、`RemoteImageEndpoint.generateUrl`、`RemoteImagePayload`、`RemoteImageResponse{parse,errorMessage}` | `Url` 形式结果被丢弃（由 :app 决定） |
| `ImageGenTarget.kt` | 生图目标 sealed | `Local(modelId)`、`Remote(provider,baseUrl,model)` | `baseUrl` 存用户原文，与账号池地址可能不一致（工具里被覆盖） |
| `ImageGenTargetCodec.kt` | 目标编解码 + 装完自动选中 | `Codec{encode,decode}`、`ImageGenTargetSelection.afterInstall` | 注释强调「坏内容不能当没配过」，但 `PraxisApp.kt:628` 只取 `getOrNull()` → UI 显示「还没选」（G14） |

### 3.2 core/imagegen 测试（16 个，全部 JVM 可跑）

| 路径 | 覆盖什么 | 疑点 |
|---|---|---|
| `EngineLaunchTest.kt`（203 行） | 命令拼装、9 类型、QNN 目录、patch、DiT 拦截、`qnn_lib_dir` 顺序 | 只测纯函数，**没有一条测「生产路径真的传了 patch」**（G4 因此逃过） |
| `GenerateImageResultCodecTest.kt` | 单 / 多图、失败不算图、坏条目跳过 | 无 |
| `ImageDownloadPlanTest.kt` | 体积解析、两种包型、part 名、预检、清理清单 | 断言清理含 zip 本体，**不含「已 rename 的成品」**（G7 的缺口就藏在这） |
| `ImageDownloadSettingsTest.kt` | 归一化、校验、codec、默认源是 HF | 无 |
| `ImageGenAvailabilityTest.kt` | 门控、降档、OOM 关键词（含 `boom` 边界） | 无 |
| `ImageGenConfigRulesTest.kt` | 越界键、未实现键、diff | 无 |
| `ImageGenDefaultsTest.kt` | 默认值一致、越界算坏内容 | 无 |
| `ImageGenParamsTest.kt` | 参数范围 / 档位 / 错误码唯一性 | 档位用例都「传入 sizes」，**没测生产传空列表**（G5） |
| `ImageGenRequestTest.kt` | 19 字段名逐字、省略语义、强制 jpeg | 无（本模块质量最高的一组） |
| `ImageGenTargetCodecTest.kt` | 往返、坏内容、自动选中三规则 | 无 |
| `ImageModelCatalogTest.kt` | 档位、目录、两种安装判据、标记名 | **没有「解压中断 / 半解压 / 残留 .part」用例**（G2 逃过） |
| `ImageOutputSettingsTest.kt` | 默认 / 往返 / 坏内容 | 无 |
| `NdjsonReaderTest.kt`（217 行） | 半行、未知 type、超大 base64、CRLF、坏 base64 | 无 |
| `ParallelProgressTest.kt` | 求和、未知总量、夹到 1、并发归一化 | 无 |
| `QnnRuntimePlanTest.kt` | 20 件清单自洽、覆盖版本 | 只断言清单自洽，**不断言 assets 真有这些文件** |
| `RemoteImageProtocolTest.kt` | 地址推导、请求体、响应解析 | 无 |

### 3.3 app/data/imagegen（14 个）

| 路径 | 职责 | 对外关键 API / 类型 | 疑点 |
|---|---|---|---|
| `EngineLocator.kt` | 探 `127.0.0.1:8081/health`（1s / 2s 短超时） | `probe()`、`defaultClient()` | `Available.source` 恒 null（无 8808 `/info`、无 proot 探测）；**只认 8081** |
| `EngineProcess.kt` | 形态 C 起停 + 有界日志尾（200 行） | `engineFile/isInstalled/isRunning/port/start/stop/logTail`、`ENGINE_NAME`、`DIT_ENGINE_NAME` | `stop()` 只 `destroy()`（无宽限 / 无 `destroyForcibly`）；**无进程死亡回调** → UI `engineRunning` 会过期（G11）；`start` 非线程安全 |
| `ImageGenClient.kt` | `/generate` 流式读（OkHttp + 逐行） | `generate(params): Flow<ImageGenEvent>` | 非 2xx 抛 `IOException`（带正文供 OOM 分类）；逐行解析**自带一份**，未复用 `NdjsonReader.read`（两份等价实现） |
| `ImageGenRunner.kt` | 生成端口（便于工具单测） | `generate` | 无超时契约（靠 Flow 取消） |
| `RemoteImageGenerator.kt` | OpenAI 兼容 images 实现 | `RemoteImageOutcome`、`RemoteImageGenerator`、`OkHttpRemoteImageGenerator` | `url` 形式丢弃 → 报 PROTOCOL；`n` 固定 1 |
| `GalleryWriter.kt` | MediaStore 写相册（10+） | `save(context, bytes, displayName)` | 固定 `image/jpeg`；9 及以下 `Unsupported`（非失败） |
| `GalleryImagePort.kt` | `feature:chat.ImageGalleryPort` 实现 | `saveImage(base64,mime)` | 用 `android.util.Base64.DEFAULT`（宽松解码） |
| `ImageModelDownloadManager.kt` | 单飞 / StateFlow 进度 / 真取消 | `state`、`start`、`cancel`、`acknowledge` | **`acknowledge()` 无生产调用点**（G12）；取消 / 失败竞态（G1） |
| `ImageModelDownloadService.kt` | 前台服务下载 + 进度 / 终态通知 | `start(context, modelId)` | 只从 `builtin(soc)` 找模型；`collect` 永不结束（靠 onDestroy 取消） |
| `ModelDownloader.kt` | 下载 / 解压 / 剥目录 / 改名 / 写标记 / 清理 / zip-slip | `DownloadResult{Completed,InsufficientSpace,Failed,Cancelled}`、`download/downloadOne/extractZip/cleanStale`、`DEFAULT_BASE_URL` | **G1（abort + firstOrNull 竞态）**、**G2/G7（临时物与成品同目录、清理不彻底）**；`runBlocking` 嵌在 IO 线程里 |
| `NetworkStatus.kt` | Wi-Fi / 以太网判定 | `isOnUnmeteredWifi` | VPN / 多网络场景未验证（G15） |
| `QnnRuntimeInstaller.kt` | assets → `filesDir/qnn/<stamp>` 解压 | `runtimeDir/isInstalled/coveredVersions/install`、`STAMP="qnn-3.0.0"` | 旧 stamp 目录**不清理**；156 MB 拷贝**没有进度**（UI 只有一行文字） |
| `ImageGatewayAccounts.kt` | 远端账号池端口 | `pick(provider,model)`、`report(accountId,success,kind)` | `pick` 只取首个 attempt，不换号（已声明） |
| `GatewayImageAccountPool.kt` | 用 `RoutePlanner` 选号 + 回写运行态 | 实现上面两个方法 | 地址三档优先级（账号覆盖 > provider 表 > 内置家）；`store.providers()` 每次调用 |

### 3.4 feature/settings 生图三页（只读，确认 UI 契约）

| 路径 | 职责 | 关键契约 | 疑点 |
|---|---|---|---|
| `ImageGenRoute.kt`（888 行） | 「设置 → 本地生图」主页 | 入参 `probeEngine/socModel/engineBundledInstalled/engineRunning/engineQnn*/engineLaunchMessage/onStartEngine/onStopEngine/availability/installedModels/currentTarget/onSelectTarget/downloadSettings/imageDefaults/imageOutput` | **「启动内置引擎」只在 `engineBundledInstalled` 时画**（:209-223）；诊断区仍写死「引擎日志拿不到（等形态 B/C）」（:450-453），而形态 C 已有 `logTail` → **文案过期**（G16） |
| `ImageGenModelsRoute.kt`（225 行） | 模型库：清单 + 下载 / 取消 + 状态条 | `models/installedIds/state/onDownload/onCancel` | 无「删除模型」「校验」「导入」入口（文档 §7.5 缺三项）；DiT 徽标写「需要 DiT 引擎（本构建没有）」（:215-223），但清单里没有 DiT 条目 → 这段实际不显示 |
| `ImageGenTargetDialog.kt`（352 行） | 目标选择（本地 / 服务商 / 自定义远端） | `ImageGenRemoteModel(provider,model,baseUrl)` | 自定义远端 KDoc 说「用全局 API Key，不是账号池」（:276-279），而 `GenerateImageTool` **确实先问账号池**（:338）→ **注释过期**（G17） |

桥接与路由（核对通过）：`SettingsHost.kt:444` / `:918-949`（参数逐个透传）、
`Routes.kt:238`（`SettingsImageGen`）/ `:246`（`SettingsImageGenModels`）、
`PraxisNavDisplay.kt:154`（`swipeDismiss=None`）/ `:158`（`swipe`）—— 与文档 §7.0 ⑤ 一致。
---

## 4. 关键机制深挖

### 4.1 端到端：用户点「启动内置引擎」→ 出一张图

| # | 谁 | 做什么 | 证据 |
|---|---|---|---|
| 1 | UI | 「启动内置引擎」这一行**只在 `engineBundledInstalled == true` 时**才画；点它调 `onStartEngine` | `ImageGenRoute.kt:209-223` |
| 2 | feature→app 桥 | 参数逐个透传到页面 | `SettingsHost.kt:444`、`:926-949` |
| 3 | app 组合层 | 取当前 target（必须是 `Local`）→ 从**已安装**模型里找它；找不到提示「先去模型库下载」 | `PraxisApp.kt:2029-2035` |
| 4 | app | `EngineLaunchRules.requiresQnn(type)` 为真 → 先解 QNN 运行时拿 `--lib_dir`；`sd15cpu / sdxlmnn` 跳过（MNN 不需要 QNN） | `PraxisApp.kt:2039-2053` |
| 5 | QNN 安装器 | 把 `assets/qnnlibs` 20 件（156.1 MB）整目录解到 `filesDir/qnn/qnn-3.0.0/`；已齐则复用；缺公共库则明确报缺件 | `QnnRuntimeInstaller.kt:37`、`:40-45`、`:59-84`、`:88`、`:97` |
| 6 | app | `EngineLaunchRules.specFor(model, enginePath, modelDir=files/models/<id>, libDir)`，`lowram` 由类型决定 | `PraxisApp.kt:2055-2061`、`EngineLaunch.kt:207-227` |
| 7 | EngineProcess | 起前 `validate`：引擎文件 / 端口 / 类型 / **DiT 缺 .so 拦截** / 模型目录非空 / QNN 两件齐 / 要不要 patch | `EngineProcess.kt:65-80`、`EngineLaunch.kt:149-177` |
| 8 | EngineProcess | `ProcessBuilder(command).redirectErrorStream(true).start()`，守护线程收回 200 行日志尾 | `EngineProcess.kt:84-100` |
| 9 | EngineLaunch | 命令行形如 `<engine> --type sd15npu --model_dir … [--lib_dir …] --port 8081 [--patch …] [--lowram]` | `EngineLaunch.kt:185-199` |
| 10 | 引擎进程 | 在 `127.0.0.1:8081` 开 HTTP（默认只听回环，未传 `--listen_all`） | `EngineProtocol.kt:19-23`、`EngineLaunch.kt:53` |
| 11 | UI | 「重新检测」→ `EngineLocator.probe()` 打 `/health`（1s 连接 / 2s 读）；坏 JSON 归 `MalformedHealth`，无人应答归 `NotRunning` | `ImageGenRoute.kt:252-265`、`EngineLocator.kt:35-62`、`EngineHealth.kt:47-58` |
| 12 | 工具注册（静态） | `generate_image` 无条件注册，执行时才探活（「工具时有时无」更难排查） | `AppModule.kt:545-566`、`GenerateImageTool.kt:186-197` |
| 13 | 工具 | `Local` → `runLocal`：进度 Preparing → 探活 → 设备门控 → 逐张生成 → 落盘 | `GenerateImageTool.kt:178-243` |
| 14 | 客户端 | `POST /generate`（`ImageGenRequest.toJson`，19 个字段实际写 11 个），**读超时 0（无限）** | `ImageGenClient.kt:52-73`、`:100-104`、`ImageGenRequest.kt:34-58` |
| 15 | 客户端 | `readUtf8Line` 逐行 → `parseLine` → `emit`；未知 type 跳过；取消时 `finally call.cancel()` 真关连接 | `ImageGenClient.kt:76-87`、`NdjsonReader.kt:48-57` |
| 16 | 解析 | `progress` 夹到 0..1（可带预览 base64）；`complete` 解 base64 + `seed/width/height` | `NdjsonReader.kt:79-92` |
| 17 | 工具 | 空字节视为「图坏了」报错；否则 `saveImage(bytes)`；OOM 降一档尺寸**重试一次** | `GenerateImageTool.kt:228-239`、`:286-297` |
| 18 | app | 落盘 `filesDir/images/gen_<毫秒>.jpg`；若 `autoSave` 再写相册（失败只记日志，不影响出图） | `AppModule.kt:598-639` |
| 19 | 工具结果 | 回 `ok/model/path/images[{path,seed,width,height}]` 给模型（不带 base64） | `GenerateImageTool.kt:407-423` |
| 20 | 聊天层契约 | `GenerateImageResultCodec.parse` 解成路径列表；失败结果返回 null（避免挂破图） | `GenerateImageResultCodec.kt:41-55` |
| 21 | 长按保存 | `ImageGalleryPort` → `GalleryImagePort` → `GalleryWriter`（Pictures/Praxis，IS_PENDING 两段式） | `GalleryImagePort.kt:19-36`、`GalleryWriter.kt:34-72` |

**两个「不在 Praxis 手里」的环节必须点明**：

- **图片怎么挂进 assistant 气泡**（`ChatMessage.images` / `MessageBubble`）在 feature:chat，**不在本范围 → 未确认**它消费了 `GenerateImageResultCodec`；
- **引擎进程死了没人发现**：`engineRunning` 只在点启动/停止时更新（`PraxisApp.kt:2063`、`:2074`），崩溃后 UI 仍显示「停止引擎」（G11）。

### 4.2 EngineLaunchRules 的 9 种类型与 DiT 现状

`KNOWN_TYPES`（`EngineLaunch.kt:113-116`）＝ `sd15cpu / sd15npu / sdxl / sdxlmnn / anima / zimage / klein / qwen21 / krea2turbo`。

| 类型 | 需 QNN 库 | 内置模型条目 | 额外产物 | 现状判定 |
|---|---|---|---|---|
| `sd15cpu` | 否（MNN） | ✅ `anythingv5cpu`（`ImageModelCatalog.kt:156-165`） | 只要引擎 | **最可能真能跑的一条**（引擎 11.4 MB 在 jniLibs；未实机验证） |
| `sd15npu` | 是 | ✅ `anythingv5` / `qteamix`（:137-154） | QNN 20 件（✅ 在 assets） | 组件齐；但非 512 画布**没有 patch**（G4），且包名 `_qnn2.28_*` 与 3.0.0 引擎/runtime 的匹配**未验证**（G10） |
| `sdxl` | 是 | ✅ `cyber_realistic_v10`（:167-176） | QNN + 8 Gen 3+ 机型 | 组件齐；门控只到「SoC 认得出」，**不判 8 Gen 3**（G18） |
| `sdxlmnn` | 否 | ❌ 无 | — | 认得类型但**没有下载来源**，走不到 |
| `anima` | 是 | ❌ 无 | — | 同上（会自动带 `--lowram`） |
| `zimage / klein / qwen21 / krea2turbo` | 是（`--lib_dir` 指向 DiT 引擎） | ❌ 无 | **`libdit_engine.so`（不存在）** | **双重不可用**：`validate` 直接回 `MissingDitEngine`（:162-164），`EngineProcess` 用 `nativeLibraryDir/libdit_engine.so` 存在性判定（:71-73） |

结论：**DiT 缺的是 `libdit_engine.so`（要 Qualcomm Hexagon SDK 单独编，见文档 §1.4.1），不是 Hexagon 运行时**——
QNN 那 156 MB 已在包里，与它无关。三处 UI 都如实写了「需要 DiT 引擎（本构建没有）」（`ImageGenModelsRoute.kt:215-223`、`ImageGenTargetDialog.kt:137-145`、`EngineLaunch.kt:93-94`）。

### 4.3 下载链路：baseUrl / 包型 / isInstalled / 清理

**默认源**：`DEFAULT_BASE_URL = "https://huggingface.co/"`（`ImageDownloadSettings.kt:55`）、镜像 `MIRROR_BASE_URL = "https://hf-mirror.com/"`（:63）。
归一化强制补尾斜杠（:79-86），因为 `fileUri` 形如 `xororz/sd-qnn/resolve/main/x.zip`，少一个斜杠会拼成 `https://huggingface.coxororz/…`。
实际拼接：`ModelDownloader.kt:204`（`baseUrl + target.remotePath`）；同值常量在 :294；:app 注入设置值（`AppModule.kt:503-511`）。
历史坑：默认源曾写成 `github.com` → 必然 404 → 「点下载永远失败」（`ImageDownloadSettings.kt:45-54`、`ModelDownloader.kt:284-293`）。

**包型判定**（`ImageDownloadPlan.kt:86-120`）：

- 多文件 ⟺ `ImageModel.packageFiles` 非空（`ImageModelCatalog.kt:39`），每条 `远端路径|本地文件名`（`localNameOf` :42-43）；
- 单 zip ⟺ `packageFiles` 空，目标 = `fileUri` 末段（:91-95）；
- 标记文件名**只对多文件包**产生（:117 ← `markerFileName(ditKind)` `ImageModelCatalog.kt:185-191`）；
- 临时物：`zipTempName = <zip>.part`（zip）/ 空串（多文件）（:111-115）；`extractTempDir = <id>_extract`（:116）——**落在模型目录内部**。

**isInstalled**（`ImageModelCatalog.kt:206-222`）：多文件 = 标记文件是文件 + 每个本地文件存在且 `length() > 0`（:210-217）；单 zip = **目录存在且非空**（:219-221，与上游同口径）。

**清理**：`DownloadCleanup.staleFiles` = 全部 `.part` + （zip 时）压缩包本体（`ImageDownloadPlan.kt:187-192`）；`staleDirs` = `@_extract`（:195）。
调用三处：预检前（`ModelDownloader.kt:115-116`，否则残留让预检自相矛盾）、终态前（:157-159）、异常分支（:174-180）。

**会不会「下载完却显示未安装」？会，两条真路径：**

| 编号 | 路径 | 证据 |
|---|---|---|
| D1 | 多文件包的 `ditKind` 为空或不在 4 个已知 kind → `markerFileName` = 空串 → **标记永不写** → `isInstalled` 恒 false（重下完还是「未安装」） | `ImageModelCatalog.kt:185-191` + `ModelDownloader.kt:169-171` |
| D2 | 多文件包某文件**合法为 0 字节** → `length() > 0` 永假 → 永远未安装 | `ImageModelCatalog.kt:213-216` |

**反向（更糟）：「没下载完却显示已安装」三条：**

| 编号 | 路径 | 证据 |
|---|---|---|
| D3 | 下载/解压中途**进程被杀**（catch 跑不到）→ 目录留 `<id>_extract/` 或 `*.part` → 单 zip 判据「目录非空」成立 → **显示已安装**，引擎一启动就失败 | `ImageDownloadPlan.kt:72/116` + `ImageModelCatalog.kt:219-221` |
| D4 | `extractZip` **搬移阶段**抛错（`renameTo` 失败）→ 已搬进模型目录的文件**不在清理清单**（`staleFiles` 只含 part 与 zip）→ 半装包算已装 | `ModelDownloader.kt:271-275` + `ImageDownloadPlan.kt:187-192` |
| D5 | 用户手动删了目录里一部分文件 → 单 zip 仍「非空」→ 仍显示已安装 | `ImageModelCatalog.kt:219-221` |

中性风险：**芯片档位变化**（ROM 升级后 `Build.SOC_MODEL` 变）会让 `fileUri` 变而 `id` 不变 → 仍显示已安装，但盘上是另一档的包（`ImageModelCatalog.kt:104-112`、`:133-178`）。

### 4.4 NDJSON 容错与失败分类（含 OOM 降档）

- **写侧**三条硬规则：可选字段 `null` 就**不写**（`seed = 0` 必须写，`ImageGenRequest.kt:44`）、数值原样写、`preview_format/output_format` 强制 `jpeg`（:50、:57）；预览关时**不写** stride（:52-54）。`ImageGenRequestTest` 逐字钉住。
- **读侧**：`readUtf8Line` 逐行（`ImageGenClient.kt:77`）→ `parseLine`（`NdjsonReader.kt:48-57`）；半行 / 非 JSON / 未知 type / 缺 `progress` 一律返回 null 跳过（:70-77）；`complete` 的 base64 坏掉**仍产出事件**但 `bytes` 空（:110-114）。
- **失败分类**：HTTP 非 2xx → `IOException` **带正文前 300 字符**（`ImageGenClient.kt:64-72`），因为引擎把 OOM 措辞写在正文；工具用关键词判 OOM（词边界 + 中英文标记，`ImageGenAvailability.kt:96-123`），命中则 `ImageGenDownscale.smaller`（减半 + 64 对齐 + 下限 256，:68-84）降一档**重试一次**，再失败报 `oom`（`GenerateImageTool.kt:286-310`）。
- **一处语义偏差（G13）**：base64 坏 → `bytes` 空 → 工具报 `engine_start_failed`「引擎返回了 complete，但图是空的（base64 传输损坏）」（:228-235）。分类码与真实原因不符，模型会朝错误方向重试。

### 4.5 生图槽位分发（Local / Remote）与网关账号池

- 工具**只认 `imagegen_target`**：`Local` → 本机 8081；`Remote` → `/v1/images/generations`（`GenerateImageTool.kt:128-133`、`:166-169`）；
- 远端地址推导处理两种 baseUrl 形状，不重复补 `/v1`（`RemoteImageProtocol.kt:50-59`）；
- 远端**先问账号池**（`:338`），拿到就**用账号 baseUrl 覆盖**目标（`:341-343`），成功/失败都回报运行态（`:350-352`、`:362-364`）；池用**同一套** `RoutePlanner + RouteState`（`GatewayImageAccountPool.kt:31-57`），所以限流/退避/熔断对画图同样生效；池里没有则**显式**回退全局 Key（`ImageGatewayAccounts.kt:24-29`）；
- 远端 `n` 固定 1（`RemoteImageGenerator.kt:76`），`count>1` 由工具循环（`:345-378`）；远端不返回 seed，工具**不编造**（:369-374）。

### 4.6 下载器的并发与 abort（G1 的根因现场）

`ModelDownloader.download` 骨架（`:110-181`）：

1. 建目录 → `cleanStale` → `SpaceCheck`（:112-122）；
2. `runBlocking { coroutineScope { targets.map { async(IO) { gate.acquire(); downloadOne(...) } }.awaitAll().firstOrNull { it != null } } }`（:134-156），`gate = Semaphore(concurrency)`（:132）；
3. 任一文件失败 → `abort.set(true)`（:224）→ 其它文件下一轮**立刻返回 `Cancelled`**（:210）；
4. 终态由 `firstOrNull { it != null }` 决定 —— **按 target 顺序**，不按「谁是真原因」（:154）。

于是：**并发 > 1（设置默认 2）时，失败文件排在前面 → 显示「下载失败」；排在后面 → 显示「已取消」。**
这既是产品语义错误（用户没点取消），也是 §5 那条 CI 红灯最可能的机制。
---

## 5. 重点问题 4：`ModelDownloaderTest`「中途失败会清理残留并让 isInstalled 保持为假」的静态推理

> 前提：本地无法跑 `:app` 测试（Windows 上 AGP/SDK 配置不过）；CI run `43875229354` 的断言原文
> 在 `lookme/STATE.md` 里仍记作「待观察」—— 也就是说**我拿不到失败信息**，下面全部是静态推理。

### 5.1 用例的输入（引用原文）

- `ModelDownloaderTest.kt:52-63` 的 `multiModel()`：`id=z_image_turbo`、`backendType=zimage`、`ditKind=zimage`、
  `approximateSize="1MB"`、`packageFiles=["remote/unet.bin|unet.bin", "remote/clip.bin|clip.bin"]`；
- `:131-133` 的假流：url 以 `clip.bin` 结尾 → 抛 `IOException("网络断了")`；否则返回 1 字节；
- 调用 `downloader.download(plan) { }` → 走默认 `concurrency = 1`（`ModelDownloader.kt:108`）、默认 `baseUrl`、`shouldStop = { false }`；
- 由 `ImageDownloadPlanner.plan` 推出的计划（`ImageDownloadPlan.kt:86-120`）：`isZip=false`、`packageFiles` 两条 → 两个 target、
  `markerFileName="ZIMAGE"`（`ImageModelCatalog.kt:187`）、`zipTempName=""`、`extractTempDir=z_image_turbo_extract`、`requiredBytes=1MB`（远小于 CI 磁盘）。

### 5.2 逐条静态推演

**断言 2**（`:138` 残留必须清掉，`plan.partPaths` 都不存在）→ **应当通过**：
`partPaths` = `unet.bin.part` / `clip.bin.part`（`ImageDownloadPlan.kt:61`）；unet 成功时已 `rename` 去掉 `.part`（`ModelDownloader.kt:229-233`）；
clip 的 `.part` 压根没创建（`openStream` 在 `part.outputStream()` **之前**抛，`:204` → catch `:221`）；即便创建，失败分支也会 `cleanStale`（:175-176 → :280）。

**断言 3**（`:139` `isInstalled` 必须为假）→ **应当通过**：多文件分支要求标记 `ZIMAGE` 存在（`ImageModelCatalog.kt:212`），
而标记只在「所有文件都成功」之后才写（`ModelDownloader.kt:169-171`）；本次在 :157-159 就 `return` 了。
注意：unet.bin 那 1 字节**确实会留在目录里**（`cleanStale` 只清 `.part` / zip，不清已改名成品），但多文件判据**不看目录是否非空**，所以不影响这条断言。

**断言 1**（`:137` `result is DownloadResult.Failed`）→ **这是唯一有风险的一条**，取决于两个 target 谁先拿到 permit：

| 顺序 | 过程 | 得到的终态 | 断言 1 |
|---|---|---|---|
| A：unet 先 | unet 成功（结果 null）→ clip 抛 IOException → `Failed` → `awaitAll` = [null, Failed] | `Failed` | ✅ 通过 |
| B：clip 先 | clip 抛 → `abort.set(true)`（:224）→ unet 拿到 permit、建出 0 字节 `.part`，循环第一句读到 `abort` → 返回 `Cancelled`（:210）→ [Cancelled, Failed] | **`Cancelled`** | ❌ 失败 |

根因是 `:154` 的 `firstOrNull { it != null }` **按 target 顺序**取第一个非 null，而不是「优先取真失败」；
而 `Semaphore(1)` 只保证**排队 FIFO**，第一张 permit 归谁由 `async(Dispatchers.IO)` 的线程池调度决定——**代码里没有任何一处保证 A**。

### 5.3 结论与假设排序

**结论：三个断言在「顺序 A」下都应当通过；只有断言 1 建立在 A 成立这个未保证的前提上。**
所以「应当通过却失败」不是判据写错，而是**实现里存在一个真实竞态**被测试踩到了。

| 序 | 假设 | 支持证据 | 如何证伪/证实 |
|---|---|---|---|
| H1 ★最可能 | 顺序 B → `result` 是 `Cancelled` | `ModelDownloader.kt:131`（abort）、`:154`（firstOrNull）、`:210`（abort→Cancelled）；测试 `:137` | 把断言改成带 message：`assertTrue("实际=" + result, result is DownloadResult.Failed)`，CI 日志会把 message 打进 XML（workflow 已有 `grep -o 'message="[^"]*"'`） |
| H2 | 若 CI 跑的实现把 `concurrency` 传成 2（设置默认值）→ 竞态几乎必现 | `ImageDownloadSettings.kt:67`（默认 2）、`AppModule.kt:508-511`（注入设置值） | 核对 CI 的 commit sha 与本地 HEAD（`1e00132`）；测试直连 downloader 时 concurrency=1 |
| H3 | 空间预检判成 `InsufficientSpace` | `ModelDownloader.kt:117` 用 `targetDir.usableSpace`；需求仅 1.3 MB | 同上，message 里 `expected` 会是 InsufficientSpace |
| H4 | 断言 2 / 3 真的失败 → 我的静态推导不成立 | 需要 `isInstalled` / `cleanStale` 行为与本地不同 | 核对 CI 的 sha；看 XML 里是哪一行（@:138` 或 `:139`） |
| H5 | 测试基础设施（`runBlocking` + JUnit、TemporaryFolder 在容器 /tmp） | 同仓已有先例：`ImageModelDownloadManagerTest.kt:68-70` 记录过「runBlocking 块最后一句带值 → initializationError」 | 看失败是 `AssertionError` 还是 `initializationError` / 超时 |
| H6 | 共享状态 / 临时目录复用 | `ModelDownloader` 无可变静态字段；`ImageModelCatalog` 无状态；每方法独立 `TemporaryFolder` | **基本可排除** |

### 5.4 验证方法与修复方向（不改代码，只给动作）

**验证（成本从低到高）**
1. 给三条断言都补 message（`"实际=" + result`），一次 CI 即定案（workflow 已把 message 打进日志）；
2. 加一条**对称用例**：让**第一个** target 抛错、第二个正常 —— 顺序 A/B 都应得到 `Failed`；
   若它稳定绿而原用例红，就确证「顺序 + firstOrNull」是根因；
3. 把同样的编排逻辑搬进 `core:imagegen`（纯 JVM，本地可跑）做一次等价单测，避免以后只能靠 CI 猜；
4. 若怀疑 H4/H2，先核对 CI 的 sha 与本地 HEAD 是否同一个 commit。

**修复方向（按最小改动排序，我倾向 ①/②）**
- ① 让「因同伴失败而中止」有自己的语义：新增 `DownloadResult.AbortedByPeer`，或让 `downloadOne` 在 `@abort` 命中时返回**带原因**的 `Failed`；
- ② 保留 `Cancelled`，但终态取值改成「优先真失败」：先 `firstOrNull { it is Failed || it is InsufficientSpace }`，再回落任意非 null；
- ③ 只改测试放宽成 `Failed || Cancelled` —— **不推荐**：§4.6 已说明这在生产上就是错的语义（用户没点取消却看到「已取消」，而且真实原因被吞掉）。

---

## 6. 缺口 / 疑点清单

> 每条：**证据**（`路径:行号`）→ **影响** → **建议动作**。G1/G2/G4 是必须优先处理的。

### G1（P0）下载器的「失败 / 取消」竞态 —— 语义错误 + CI 红灯
- 证据：`ModelDownloader.kt:131`（`abort`）、`:154`（`firstOrNull` 按 target 顺序）、`:210`（abort 命中即 `Cancelled`）、`:224`（同伴失败 set abort）；测试 `ModelDownloaderTest.kt:137`。
- 影响：① 并发（设置默认 2）下用户没点取消却看到「已取消」，真实失败原因被吞；② `ImageModelDownloadState.Failed/Cancelled` 走不同 UI 分支（`ImageModelDownloadService.kt:102` vs `:107`），通知文案与日志都错；③ 那条 CI 红灯最可能的原因。
- 建议：见 §5.4 的 ①/②；配合补一条「第一个文件失败」的对称用例。

### G2（P0）「未装完」与「已安装」共用同一目录 —— 半成品被当成已装
- 证据：`ImageDownloadPlan.kt:72`（`extractTempDir`）、`:116`（`<id>_extract` 落在 `targetDir` 内）；`ModelDownloader.kt:246-248`（解压目录建在模型目录里）；`ImageModelCatalog.kt:219-221`（单 zip 判据 = 目录非空）。
- 影响：下载/解压被系统杀进程（几 GB 包很常见）→ 目录里剩 `_extract/` 或 `.part` → **显示已安装**，起引擎必失败，且用户「删不掉」（UI 没有删除按钮，见 G19）。
- 建议：临时物统一放 `filesDir/models/.incoming/<id>/`；并把 `isInstalled` 的单 zip 判据改成「非空 **且** 不含 `.part` / 不含 `@_extract`」。两条一起改才算真的挡住。

### G3（P2）两套「引擎来源」枚举，同一语义两个家
- 证据：`EngineAvailability.kt:4`（`ImageGenEngineSource`，UI 在用 `ImageGenRoute.kt:585-590`）与 `EngineLaunch.kt:12`（`EngineSource`，无调用点）。
- 影响：往任意一处加值时另一处不会报错，界面会显示「来源尚未识别」或者干脆漏一档。
- 建议：删 `EngineSource`，只留 core 里那个（UI 已依赖它）。

### G4（P0）`--patch` 在生产路径从不传入 → `sd15npu` 非 512 静默回落 512
- 证据：`PraxisApp.kt:2055-2061` 调 `specFor` 时**没给** `patchFile`；`EngineLaunch.kt:175` 只有 `needsPatch=true` 才拦，而**没有任何调用点把它置 true**；`EngineLaunchTest.kt` 只测纯函数；且资产里**没有任何 `.patch` 文件**（`jniLibs` / `assets` 枚举结果里没有）。
- 影响：用户在 NPU 模型上选 768×512 → 引擎静默画 512×512，无任何提示（文档 §3.1 专门警告过这条）。实际能力比 UI 承诺的窄。
- 建议：短期**在 UI/工具层把 sd15npu 的可用尺寸锁成 512×512** 并说明原因；中期把 `.patch` 作为模型包的一部分下载，并在 `specFor` 传 `patchFile`。

### G5（P1）分辨率档位校验形同虚设
- 证据：`AppModule.kt:595-597`：`availableSizes = { emptyList() }`；`ImageGenParams.kt:118` 只有非空才判档位；`GenerateImageTool.kt:157-164` 因此永远放行。
- 影响：文档 §5.1「不合法就返回 invalid_size 并列出可用档位」**没落地**；模型会以为 640×640 可用，实际被引擎静默改写或报错。
- 建议：给 `ImageModel` 加 `sizes` 字段（默认从 `generationSize` 派生 `[512,512]`），在 :app 注入真实档位。

### G6（P1）QNN 解压：无进度、旧 stamp 不清理
- 证据：`QnnRuntimeInstaller.kt:59-84`（156.1 MB 逐文件拷贝，无回调）；`:64` 只删**同名** stamp 目录；UI 只有一行文字（`ImageGenRoute.kt:2043-2044` 的 message）。
- 影响：首次起 NPU 引擎会有几十秒「只有一句『正在解压 QNN 运行时』」，用户容易以为卡死；升级换 stamp 后旧目录（156 MB）永久占空间。
- 建议：`install` 暴露进度（或至少 done/total 计数），起引擎期间给一个不确定进度条；新 stamp 时清掉兄弟目录。

### G7（P1）失败清理不覆盖「已搬进模型目录的成品」
- 证据：`DownloadCleanup.staleFiles`（`ImageDownloadPlan.kt:187-192`）只含 `.part` 与 zip 本体；`ModelDownloader.kt:271-275` 是逐条 `renameTo`，中途失败即抛。
- 影响：单 zip 解压搬移阶段失败 → 半装目录 + 目录非空 → 反向「已安装」（同 G2 的后果）。
- 建议：失败分支若属于「本次新建的模型目录」，直接 `targetDir.deleteRecursively()`；若目录是复用的，则按「解压前后文件清单差集」清。

### G8（P3）注释与实现矛盾（下载设置）
- 证据：`ImageDownloadSettings.kt:16-23` 仍写「并发数…放一个读不到的并发数，用户改了以为变快，其实没变」；但 `ModelDownloader.kt:108` 已实现并发、`AppModule.kt:508-511` 已注入、`ParallelProgress` 已聚合、UI 已有滑杆（`ImageGenRoute.kt:676-684`）。
- 影响：读者（含下一位智能体）会以为并发是死配置，重复劳动或误删。
- 建议：改注释，指向 `ImageDownloadSettings.concurrency` 的真实消费链。

### G9（P2）远端失败细分码不在 core
- 证据：`ImageGenError.kt:74-82` 只有 7 个码；`GenerateImageTool.kt:383-392` 另写 `quota_exceeded / content_blocked / no_api_key / remote_unreachable / invalid_request`。
- 影响：文档 §5.4 要求远端也分类，但这批码**没有单测**、不在「唯一出处」里，改一处不会连带另一处。
- 建议：把远端码并入 `ImageGenError` 常量区（core 可单测），工具只做映射。

### G10（P1）芯片档位映射与模型包版本都缺权威来源
- 证据：`ImageModelCatalog.kt:79-96`（`SM8750 → 8gen2`、`SM8450 → 8gen1`…共 16 条）**没有任何来源注释**；内置 `fileUri` 是 `_qnn2.28_*`（`:141`、`:150`、`:171`），而随包引擎/QNN 是 3.0.0（`QnnRuntimeInstaller.kt:97` `STAMP="qnn-3.0.0"`）。
- 影响：① 档位选错 → 下到跑不动的包；② 2.28 转换的权重配 3.0.0 引擎/QNN 运行时能否加载**未验证**（文档 §1.5 专门警告过混版本）。
- 建议：把映射的来源写进注释并在真机上按机型实测；模型包以「与引擎同源」为原则重新对齐（或至少在 UI 上标出「包版本 qnn2.28」）。

### G11（P1）引擎进程没有生命周期看护
- 证据：`EngineProcess.kt:115-119` 的 `stop()` 只有 `destroy()`（无宽限、无 `destroyForcibly`、无 `waitFor`）；无 `onExit` / 轮询；`PraxisApp.kt:2063`、`:2074` 只在点击时同步 `engineRunning`。
- 影响：① 引擎崩溃后 UI 仍显示「停止引擎」，用户无法重新启动（`start` 里 `isRunning()` 为假会重启，但按钮不会切回「启动」）；② 停引擎可能留僵尸进程/端口占用。
- 建议：`start` 里起一个 `waitFor` 监听线程更新「是否存活」的 StateFlow；`stop` 加 2 s 宽限再 `destroyForcibly`（文档 §8.6 要求宽限 2 s）。

### G12（P2）`acknowledge()` 无生产调用点 → 下载终态一直挂着
- 证据：`ImageModelDownloadManager.kt:145-148`；全仓 grep `acknowledge(` 只命中该文件与它的测试。
- 影响：模型库页的「下载完成：xxx」/失败红条会**永久显示**（直到进程重启或下一次下载），`StateCard` `ImageGenModelsRoute.kt:152-193` 一直画。
- 建议：模型库页在用户看清后用 `LaunchedEffect` 或「知道了」按钮调 `acknowledge()`。

### G13（P2）base64 损坏被报成 `engine_start_failed`
- 证据：`NdjsonReader.kt:110-114`（坏 base64 → 空字节，仍产出 Complete）；`GenerateImageTool.kt:228-235`（空字节 → `engine_start_failed`）。
- 影响：模型据此会去查「引擎为什么起不来」，而真实原因是传输/编码损坏（该重试或缩小尺寸）。
- 建议：新增 `invalid_image` 码（或复用 `remote` 的 protocol 语义），文案说清「引擎出了图但传坏了」。

### G14（P3）坏 `imagegen_target` 被 UI 当成「还没选」
- 证据：`ImageGenTargetCodec.kt:47-63` 明确区分 null（没配过）与 failure（坏了）；但 `PraxisApp.kt:628` 只取 `getOrNull()`，`ImageGenRoute.kt:564` 于是显示「还没选。装好第一个模型后会自动选中它。」。
- 影响：用户的配置坏了却被告知「还没选」，问题被掩盖；下一次下载还会「自动选中」覆盖他的选择（`PraxisApp.kt:660` 只在 failure 时不动，UI 却已经误导）。
- 建议：把 `Result.failure` 一路传到页面，单独显示「配置读不出来，请重选」。

### G15（P3）「仅 Wi-Fi」判定只看 transport
- 证据：`NetworkStatus.kt:18-26`（只看 WIFI / ETHERNET transport）。
- 影响：挂着 VPN 的手机（常见）默认网络是 VPN transport，可能被判成「不在 Wi-Fi」→ 下载被策略挡下（`AppState downloadGuard` `AppModule.kt:493-500`）。
- 建议：同时看 `TRANSPORT_VPN` 下的底层网络，或把「不可计费」判定改成 `NET_CAPABILITY_NOT_METERED`。

### G16（P3）UI 诊断文案过期（形态 C 已有日志尾）
- 证据：`ImageGenRoute.kt:450-453` 写「引擎日志：拿不到 —— 引擎是外部 App 起的…（等形态 B/C）」；但 C 形态已实现 `EngineProcess.logTail`（`:122-124`），失败时也已在 `:2067` 带 6 行给用户。
- 影响：自相矛盾的说明，让读者以为 C 没做。
- 建议：按 `engineBundledInstalled` 分支显示 `logTail`。

### G17（P3）注释过期（远端用的是哪个 Key）
- 证据：`ImageGenTargetDialog.kt:276-279` 说「用的是 Praxis 全局 API Key，**不是**网关账号池」；`GenerateImageTool.kt:338-343` 实际**先问池**、拿不到才回退全局。
- 影响：维护者会按注释判断「池没接」，改动方向错。
- 建议：改注释（并把「哪一步回退」写清）。

### G18（P1）门控不含「8 Gen 3+」与「空闲内存」
- 证据：`ImageGenAvailability.kt:32-47` 只判 ABI + SoC 能否识别档位；文档 §9 还要求 SDXL 需 8 Gen 3+、SD1.5 需 2~3 GB 空闲内存。
- 影响：8 Gen 1/2 用户会被允许下 SDXL 包（7 GB）并起引擎 → 大概率 OOM/加载失败，而 UI 一直说「NPU 可用」。
- 建议：按 `backendType` 加一条**说明性**提示（不硬挡），并在起引擎前做一次空闲内存预检；OOM 已有降档重试兜底（`GenerateImageTool.kt:286-297`）。

### G19（P2）模型库缺「删除 / 校验 / 导入」三件事
- 证据：`ImageGenModelsRoute.kt:111-124` 只有「下载」「取消」两种行操作；文档 §7.5 要求「取消 / 删除 / 设为当前 / 校验（有哈希时）」+ 已导入区。
- 影响：① 装坏了的模型（G2 的 D3/D4）**用户删不掉**；② 7 GB 包无法回收空间。
- 建议：先做「删除整个模型目录」这一条（成本最低，价值最高），校验与导入后置。

### G20（P3）进度总线缺「第几张」
- 证据：`ImageGenProgressBus.kt:12-27` 只有 `targetLabel/phase/fraction`；工具 `count` 可到 4（`GenerateImageTool.kt:135-138`）且循环里复用同一 targetLabel（`:277-283`）。
- 影响：`count=3` 时界面只会显示进度 0→1 三遍，看不出还剩几张。
- 建议：给 `ImageGenProgress` 加 `index/total`（可选字段，默认 0/1）。

---

## 7. 对照 `docs/PORTING-IMAGEGEN.md` 的缺口矩阵

| 文档节 | 要求 | 现状 | 证据 |
|---|---|---|---|
| §1.1 形态 A/B/C | 三种来源都能用 | **A 只能靠探测 8081**；B（proot 容器）**完全没做**；C 做了（起停 + 日志） | `EngineLocator.kt:35-62`（无 8808/info）、`EngineProcess.kt` |
| §1.2 `EngineSource` 三态 + 按 External→Container→Bundled 探测 | 未做（只有枚举，探测恒给 null） | `EngineAvailability.kt:26-33`、`EngineLocator.kt:48` |
| §1.3 「一键安装」状态机 | 部分：未连接时给了三条办法的**文字**，C 形态有按钮；B 没有实现 | `ImageGenRoute.kt:194-207` |
| §3.1 启动参数（含 `--patch` / `--lib_dir`） | `--lib_dir` 做了（自动解 QNN）；**`--patch` 没做**（G4）；`--no_img2img`/`--listen_all` 未用 | `PraxisApp.kt:2055-2061`、`EngineLaunch.kt:185-199` |
| §3.2/@3.3 请求/NDJSON | 做了，字段逐字钉死 | `ImageGenRequest.kt`、`NdjsonReader.kt` + 两组测试 |
| §3.4 `/health / /generate / /tokenize / /upscale / /finished` | 只用前两个；`tokenize` 有 URL 常量**无调用**（提示词长度预检没做） | `EngineProtocol.kt:25-47` |
| §4.1 模型布局（`models/<id>` + 同级 `embeddings/` + `.nomedia`） | 只有 `files/models/<id>`；**没有 `embeddings/`**、**没有 `.nomedia`**（grep 全仓 0 命中） | `PraxisApp.kt:581`、`AppModule.kt:484` |
| §4.3 历史（Room） | **没做**（无实体、无落库） | 全仓无 `image_gen` 表 |
| §5.1 参数表 + 档位校验 | 参数与范围做了；**档位校验空转**（G5） | `AppModule.kt:597` |
| §5.2 失败分类 7 类 | 做了（工具侧另有远端 7 码，G9） | `ImageGenError.kt`、`GenerateImageTool.kt:383-392` |
| §5.5 系统提示词回喂 | **未确认**（聊天层不在本范围） | — |
| §5.6 `set_image_gen_config`（带 diff、禁止改凭据/下载源/引擎来源） | core 规则做了且有 13 条测试；工具本体**我未读** | `ImageGenConfigRules.kt` + 测试 |
| §6.1 生成中进度 | 总线 + 三阶段做了；**每步预览没做**（工具固定 `showPreview=false`、无缩略图管线） | `ImageGenProgressBus.kt`、`GenerateImageTool.kt:152` |
| §7.0 UI 纪律（Miuix / 单 LazyColumn / 稳定 key / 弹窗常驻 + visible / 无写死颜色） | 基本遵守：`SettingsList` 单列表、`items(models, key = { it.id })`、`MiuixAnimatedDialog(visible=)`、只有 `MiuixTheme.colorScheme.*` | `ImageGenModelsRoute.kt:64`、`ImageGenRoute.kt:640`、`ImageGenTargetDialog.kt:83` |
| §7.1 页面分区 | 引擎/模型/画布采样/输出/诊断/下载源都做了；**「性能（后端 / 预热 / 并发）」与「诊断·启动耗时」没做**（C 形态下反而可做，见 G16） | `ImageGenRoute.kt:150-527` |
| §7.2 下载源 / 仅 Wi-Fi / 并发 / 代理 | 前三做了；**代理没做**（Praxis 无全局代理，页面明说） | `ImageGenRoute.kt:496-506` |
| §7.3 功能模型「生图」槽位 + 本地模型自动选入 | 槽位行与自动选入做了（含三条规则单测）；`FunctionSlot.IMAGE` 枚举本体**我未读** | `SettingsHost.kt:729-734`、`PraxisApp.kt:652-679` |
| §7.4 引擎来源卡片三态 | 两态（已连接 / 未检测到）；**「连接中」没有**（`ProbeUi.Probing` 只有一行文字）；来源徽标恒「尚未识别」 | `ImageGenRoute.kt:158-251` |
| §7.5 模型库（下载 / 取消 / 删除 / 设为当前 / 校验 / 已导入） | 只有下载 / 取消 + 底部许可证说明（G19） | `ImageGenModelsRoute.kt:111-146` |
| §8.4 ①空间预检 ②失败清理 ③两种安装判据 ④不边下边用 | ①②③④都做了，但②的覆盖面与③的单 zip 判据有洞（G2/G7） | `ModelDownloader.kt:117-122`、`ImageModelCatalog.kt:206-222` |
| §8.6 换模型必须重启引擎 | **没做**：切目标只写 DataStore（`PraxisApp.kt:2078-2084`），引擎仍跑旧 `--model_dir` | `PraxisApp.kt:2078-2084` |
| §9 硬件门控 | ABI + SoC 做了；**8 Gen 3+ 与空闲内存没做**（G18） | `ImageGenAvailability.kt:32-47` |
| §12 已知坑（NDJSON 不当 SSE / 不一次性读完 / 预览强制 jpeg / 换模型重启 / 端口他人占用 / 前台服务 / OOM 降档 / zip-slip） | 除「换模型重启」与「端口归属探测」外都做了 | `ImageGenClient.kt`、`ImageModelDownloadService.kt`、`ModelDownloader.kt:255-257` |
| §13 验收清单 | P0（A/B/C）、P3 的部分（下载/设置页）、P4 部分（门控/降档）可勾；**P0 的「形态 B」「8808 版本校验」、P1 的「档位 invalid_size」、P3 的「换模型重启」、P5 全部**不可勾 | 见上 |

---

## 8. 未确认项（我不猜）

1. **`libstable_diffusion_core.so` 到底是什么**：只 stat 到 11,976,888 B，按硬纪律**没有读内容**、没有 `file`/ELF 头校验 → 「它是不是 3.0.0 的 AArch64 可执行」**未确认**（`lookme/memory/round-00` 记为 3.0.0 / 11.42 MB，量级一致）。
2. **模型包（qnn2.28）与引擎/QNN 运行时（3.0.0）能否配合**：未实机、无文档依据 → 未确认（G10）。
3. **QNN 的 `dlopen` 是否允许从 `filesDir` 加载**：文档说 Local Dream 就是这么做的，我没在真机上验证 → 未确认。
4. **聊天层是否消费 `GenerateImageResultCodec`** 把图挂进 assistant 气泡（feature:chat，不在本范围）→ 未确认。
5. **`SetImageGenConfigTool` / `ListImageGenModelsTool`` 的实现细节（只看到 AppModule 的装配）→ 未读、未确认。
6. **`FunctionSlot.IMAGE` 是否真的加进枚举**（`FunctionModelSettings.kt` 未读；只看到 SettingsHost 有「生图」那行的接线）→ 未确认。
7. **`ChipsetTier` 的 16 条映射是否与上游一致**：无来源、无测试依据 → 未确认（G10）。
8. **本机 `:app` 测试不可跑**（Windows 上 AGP/SDK 配置不过，见 `lookme/memory/round-00`）→ 所有 `:app` 行为结论都来自**静态阅读**，没有一次运行证据。
