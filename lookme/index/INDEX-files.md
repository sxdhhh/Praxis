# 全库文件索引（自动生成）

覆盖：文件清单 1251 个；本索引收录文本文件 961 个，跳过二进制/超大 290 个。
生成于 2026-11-16T13:26:59.572Z

格式：`路径`（行数 / KB）首行注释 + 顶层声明

---

## .github/workflows/build.yml  (575 行 / 27.1 KB)
> # 触发条件


## .github/workflows/preview.yml  (123 行 / 4.5 KB)
> # UI 预览渲染


## .github/workflows/release.yml  (194 行 / 7.2 KB)
> # 正式签名包发布：构建 signed release APK 并挂到 GitHub Release。


## README.md  (364 行 / 13.8 KB)
> # Praxis


## app/build.gradle.kts  (474 行 / 21.8 KB)
> * 版本号唯一定义点。
  - val PRAXIS_VERSION_CODE,val PRAXIS_VERSION_NAME,val localProps,fun localProp,val releaseKeystore: String?

## app/lint.xml  (20 行 / 0.7 KB)


## app/proguard-rules.pro  (244 行 / 10.5 KB)
> # Praxis 保留 Room / Compose 反射所需符号


## app/schemas/dev.praxis.data.db.PraxisDatabase/1.json  (49 行 / 1.4 KB)


## app/schemas/dev.praxis.data.db.PraxisDatabase/10.json  (503 行 / 15.5 KB)


## app/schemas/dev.praxis.data.db.PraxisDatabase/11.json  (515 行 / 15.9 KB)


## app/schemas/dev.praxis.data.db.PraxisDatabase/2.json  (152 行 / 4.5 KB)


## app/schemas/dev.praxis.data.db.PraxisDatabase/3.json  (220 行 / 6.5 KB)


## app/schemas/dev.praxis.data.db.PraxisDatabase/4.json  (226 行 / 6.7 KB)


## app/schemas/dev.praxis.data.db.PraxisDatabase/5.json  (444 行 / 13.5 KB)


## app/schemas/dev.praxis.data.db.PraxisDatabase/7.json  (482 行 / 14.8 KB)


## app/schemas/dev.praxis.data.db.PraxisDatabase/9.json  (503 行 / 15.5 KB)


## app/src/androidTest/java/dev/praxis/data/db/PraxisDatabaseMigrationTest.kt  (139 行 / 5.7 KB)
  - class PraxisDatabaseMigrationTest

## app/src/main/AndroidManifest.xml  (215 行 / 11.3 KB)


## app/src/main/cpp/CMakeLists.txt  (75 行 / 3.4 KB)
> # 输出名必须是 `pty`：Kotlin 侧 `System.loadLibrary("pty")` 按这个名字找。


## app/src/main/cpp/pty.c  (342 行 / 13.0 KB)
> * PTY（伪终端）JNI。


## app/src/main/java/dev/praxis/MainActivity.kt  (358 行 / 18.4 KB)
  - class MainActivity : ComponentActivity,private data class ReadyState

## app/src/main/java/dev/praxis/PraxisApplication.kt  (264 行 / 11.9 KB)
  - class PraxisApplication : Application

## app/src/main/java/dev/praxis/ProvisionGate.kt  (118 行 / 5.2 KB)
> * HyperCeiler 引导链（`:ui:provision`）的启动闸门。
  - internal object ProvisionGate

## app/src/main/java/dev/praxis/core/terminal/Pty.kt  (389 行 / 15.7 KB)
> * 伪终端（PTY）。
  - open class Pty,private class NoopProcess

## app/src/main/java/dev/praxis/data/ChatRepository.kt  (706 行 / 31.3 KB)
  - class ChatRepository

## app/src/main/java/dev/praxis/data/FileSkillSource.kt  (57 行 / 1.9 KB)
> * 基于应用私有目录的 [SkillSource]。
  - class FileSkillSource

## app/src/main/java/dev/praxis/data/MemoryPortImpl.kt  (92 行 / 3.4 KB)
> * [MemoryPort] 的实现：读全库 + RRF 检索。
  - class MemoryPortImpl

## app/src/main/java/dev/praxis/data/RoomChatPersistence.kt  (190 行 / 7.2 KB)
> * [ChatPersistence] 的 Room 实现。
  - class RoomChatPersistence

## app/src/main/java/dev/praxis/data/RoomMemoryStore.kt  (87 行 / 2.9 KB)
> * [MemoryStore] 的 Room 实现。
  - class RoomMemoryStore

## app/src/main/java/dev/praxis/data/ToolWorkflowPort.kt  (55 行 / 2.1 KB)
> * [WorkflowToolPort] 的实现：转给已注册的工具。
  - class ToolWorkflowPort

## app/src/main/java/dev/praxis/data/ai/AnthropicProvider.kt  (153 行 / 6.1 KB)
  - class AnthropicProvider

## app/src/main/java/dev/praxis/data/ai/OkHttpModelCatalogFetcher.kt  (167 行 / 7.3 KB)
  - class OkHttpModelCatalogFetcher

## app/src/main/java/dev/praxis/data/ai/OpenAiCompatProvider.kt  (592 行 / 28.5 KB)
  - class OpenAiCompatProvider

## app/src/main/java/dev/praxis/data/ai/ResponsesProvider.kt  (131 行 / 4.9 KB)
  - class ResponsesProvider

## app/src/main/java/dev/praxis/data/ai/SettingsBackedProvider.kt  (286 行 / 14.6 KB)
  - class SettingsBackedProvider

## app/src/main/java/dev/praxis/data/ai/SettingsBackedTitleGenerator.kt  (86 行 / 4.2 KB)
  - class SettingsBackedTitleGenerator

## app/src/main/java/dev/praxis/data/attachment/CameraCapture.kt  (79 行 / 3.1 KB)
> * 拍照落盘：给系统相机准备一个可写的 content:// 目标。
  - object CameraCapture

## app/src/main/java/dev/praxis/data/attachment/LocationReader.kt  (94 行 / 4.2 KB)
> * 读取当前设备位置，拼成一段可直接插进输入框的文字。
  - object LocationReader

## app/src/main/java/dev/praxis/data/attachment/PackageInfoReader.kt  (118 行 / 4.8 KB)
> * 读取已安装应用（「智能包」）的信息，拼成可插进输入框的文本。
  - object PackageInfoReader

## app/src/main/java/dev/praxis/data/attachment/PraxisNotificationListener.kt  (85 行 / 3.7 KB)
> * 读取当前活跃通知，拼成一段可插进输入框的文字。
  - class PraxisNotificationListener : NotificationListenerService

## app/src/main/java/dev/praxis/data/attachment/ScreenCapture.kt  (283 行 / 11.1 KB)
  - object ScreenCapture,class ScreenCaptureActivity : Activity,class ScreenCaptureService : Service

## app/src/main/java/dev/praxis/data/attachment/SpeechReader.kt  (105 行 / 3.6 KB)
> * 朗读助手：把一段文本交给系统 TTS 念出来。
  - class SpeechReader

## app/src/main/java/dev/praxis/data/backup/BackupExporter.kt  (77 行 / 3.0 KB)
  - sealed interface BackupResult,class BackupExporter

## app/src/main/java/dev/praxis/data/chat/ChatImagePreparer.kt  (142 行 / 6.1 KB)
  - object ChatImagePreparer

## app/src/main/java/dev/praxis/data/chat/SettingsPendingQueueStore.kt  (29 行 / 1.0 KB)
> * [PendingQueueStore] 的 DataStore 实现。
  - class SettingsPendingQueueStore

## app/src/main/java/dev/praxis/data/custom/CustomToolLoader.kt  (126 行 / 5.0 KB)
> * 从 `.praxis/tools/<name>.tool` 文件加载自定义工具定义。
  - object CustomToolFileParser,class CustomToolSource

## app/src/main/java/dev/praxis/data/db/MemoryDao.kt  (59 行 / 1.7 KB)
  - interface MemoryDao

## app/src/main/java/dev/praxis/data/db/MemoryEntity.kt  (56 行 / 2.0 KB)
> * 一条长期记忆。
  - data class MemoryEntity

## app/src/main/java/dev/praxis/data/db/MessageDao.kt  (209 行 / 9.1 KB)
  - interface MessageDao

## app/src/main/java/dev/praxis/data/db/MessageEntity.kt  (128 行 / 5.5 KB)
> * 一条消息。
  - data class MessageEntity

## app/src/main/java/dev/praxis/data/db/PraxisDatabase.kt  (629 行 / 30.6 KB)
  - abstract class PraxisDatabase : RoomDatabase

## app/src/main/java/dev/praxis/data/db/SessionDao.kt  (141 行 / 6.1 KB)
  - interface SessionDao

## app/src/main/java/dev/praxis/data/db/SessionEntity.kt  (107 行 / 4.2 KB)
> * 一次会话。Phase 1 只落最小字段，后续移植 Operit 的 chat 域时按需扩展，
  - data class SessionEntity

## app/src/main/java/dev/praxis/data/gateway/BuiltinProviders.kt  (176 行 / 7.9 KB)
  - data class BuiltinQuirks,object BuiltinProviders

## app/src/main/java/dev/praxis/data/gateway/GatewayModelStore.kt  (37 行 / 1.4 KB)
> * `gateway_model` 表的读写层（v13 新增的那张结构化条目表）。
  - class GatewayModelStore

## app/src/main/java/dev/praxis/data/gateway/GatewayProvider.kt  (408 行 / 19.1 KB)
  - class GatewayProvider,private fun decodeHeaders

## app/src/main/java/dev/praxis/data/gateway/GatewayUsageStore.kt  (114 行 / 4.5 KB)
> * 请求记录的写入层（文档 6.1 的 `gateway_request`）。
  - class GatewayUsageStore

## app/src/main/java/dev/praxis/data/gateway/ProxyResolver.kt  (75 行 / 3.1 KB)
  - class ProxyResolver

## app/src/main/java/dev/praxis/data/imagegen/EngineLocator.kt  (72 行 / 2.9 KB)
  - class EngineLocator

## app/src/main/java/dev/praxis/data/imagegen/EngineProcess.kt  (137 行 / 5.9 KB)
> * 形态 C：随包引擎的进程管理（文档 §1.1 的 C + §3.1 的起法）。
  - class EngineProcess

## app/src/main/java/dev/praxis/data/imagegen/GalleryImagePort.kt  (37 行 / 1.4 KB)
> * [dev.praxis.feature.chat.ImageGalleryPort] 的实现：把 base64 解回字节再交给 [GalleryWriter]。
  - class GalleryImagePort

## app/src/main/java/dev/praxis/data/imagegen/GalleryWriter.kt  (74 行 / 3.2 KB)
> * 把一张图写进系统相册（文档 §7.1 的「自动存相册」）。
  - object GalleryWriter

## app/src/main/java/dev/praxis/data/imagegen/GatewayImageAccountPool.kt  (67 行 / 2.9 KB)
  - class GatewayImageAccountPool

## app/src/main/java/dev/praxis/data/imagegen/ImageGatewayAccounts.kt  (51 行 / 1.7 KB)
> * 让远端生图**也走网关账号池**（文档 §5.1 的 Images 入口 + §7.2 的限流/熔断）。
  - data class ImageGatewayAccount,interface ImageGatewayAccounts

## app/src/main/java/dev/praxis/data/imagegen/ImageGenClient.kt  (107 行 / 4.5 KB)
  - class ImageGenClient

## app/src/main/java/dev/praxis/data/imagegen/ImageGenRunner.kt  (25 行 / 0.9 KB)
> * 「跑一次生成」这件事的端口。
  - interface ImageGenRunner

## app/src/main/java/dev/praxis/data/imagegen/ImageModelDownloadManager.kt  (150 行 / 6.4 KB)
  - class ImageModelDownloadManager

## app/src/main/java/dev/praxis/data/imagegen/ImageModelDownloadService.kt  (188 行 / 8.2 KB)
  - class ImageModelDownloadService : Service

## app/src/main/java/dev/praxis/data/imagegen/ModelDownloader.kt  (318 行 / 14.1 KB)
  - sealed interface DownloadResult,class ModelDownloader

## app/src/main/java/dev/praxis/data/imagegen/NetworkStatus.kt  (28 行 / 1.1 KB)
> * 当前网络类型（文档 §7.2 的「仅 Wi-Fi 下载」要它）。
  - object NetworkStatus

## app/src/main/java/dev/praxis/data/imagegen/QnnRuntimeInstaller.kt  (100 行 / 4.1 KB)
> * 把随包的 QNN 运行时解出来给引擎用（文档 §1.4 的「QNN 的正确做法」）。
  - class QnnRuntimeInstaller

## app/src/main/java/dev/praxis/data/imagegen/RemoteImageGenerator.kt  (132 行 / 5.0 KB)
  - sealed interface RemoteImageOutcome,interface RemoteImageGenerator,class OkHttpRemoteImageGenerator

## app/src/main/java/dev/praxis/data/linux/LinuxToolPortImpl.kt  (203 行 / 8.2 KB)
  - class LinuxToolPortImpl

## app/src/main/java/dev/praxis/data/linux/LinuxTools.kt  (274 行 / 11.6 KB)
  - class LinuxExecTool,class LinuxStatusTool,class LinuxListToolsTool,internal fun defaultDistroForTool

## app/src/main/java/dev/praxis/data/linux/PraxisFtpServer.kt  (176 行 / 7.4 KB)
  - class PraxisFtpServer

## app/src/main/java/dev/praxis/data/linux/ProotEnvPort.kt  (1917 行 / 90.7 KB)
  - class ProotEnvPort,enum class TarFormat,private class ProotSession

## app/src/main/java/dev/praxis/data/linux/ProotLoaderStaging.kt  (184 行 / 9.2 KB)
> * 把 proot 的 loader（与 proot 本体）铺到 `filesDir/proot/bin/`。
  - internal object ProotLoaderStaging

## app/src/main/java/dev/praxis/data/linux/ProotPtySessionAdapter.kt  (126 行 / 5.4 KB)
> * `TerminalSessionPort` → `PtySessionPort` 适配器。
  - class ProotPtySessionAdapter

## app/src/main/java/dev/praxis/data/linux/ProotTerminalSessionPort.kt  (1312 行 / 64.2 KB)
  - class ProotTerminalSessionPort,private class ScreenTap

## app/src/main/java/dev/praxis/data/memory/MemoryMonitor.kt  (238 行 / 9.2 KB)
  - class MemoryMonitor

## app/src/main/java/dev/praxis/data/memory/MemoryPortQueryAdapter.kt  (28 行 / 1.0 KB)
> * 把聊天层的 [MemoryPort] 适配成工具层的 [MemoryQueryPort]。
  - class MemoryPortQueryAdapter

## app/src/main/java/dev/praxis/data/memory/MemoryTools.kt  (131 行 / 5.5 KB)
> * 记忆工具的最小端口（避免工具层直接依赖 :app 的记忆实现）。
  - interface MemoryQueryPort,class SearchMemoryTool,class SaveMemoryTool

## app/src/main/java/dev/praxis/data/plugin/DirectoryPluginArchive.kt  (62 行 / 2.4 KB)
> * 把「已解压的包目录」当成 [PluginArchive] 读。
  - class DirectoryPluginArchive

## app/src/main/java/dev/praxis/data/plugin/FilePluginStorage.kt  (304 行 / 12.3 KB)
  - class FilePluginStorage

## app/src/main/java/dev/praxis/data/plugin/MarketOriginCodec.kt  (75 行 / 2.6 KB)
> * [PluginOrigin] 与存储字符串之间的编解码。
  - object MarketOriginCodec

## app/src/main/java/dev/praxis/data/plugin/PluginMarketApi.kt  (156 行 / 6.4 KB)
  - class PluginMarketApi

## app/src/main/java/dev/praxis/data/plugin/PraxisWebViewPluginRuntime.kt  (272 行 / 10.7 KB)
  - class PraxisWebViewPluginRuntime

## app/src/main/java/dev/praxis/data/plugin/TempDirZipArchive.kt  (109 行 / 4.5 KB)
> * 把 zip（`.toolpkg` / `.zip`）解到临时目录，读的时候按目录读，
  - class TempDirZipArchive private constructor

## app/src/main/java/dev/praxis/data/shell/AndroidHostShellPort.kt  (329 行 / 13.2 KB)
> * 宿主 Android shell 的执行结果。
  - data class HostShellResult,enum class HostShellChannel,class AndroidHostShellPort

## app/src/main/java/dev/praxis/data/shell/HostShellTools.kt  (205 行 / 9.7 KB)
> * `host_shell` —— 在**宿主 Android 系统**上执行 shell 命令。
  - class HostShellTool,class HostShellChannelsTool

## app/src/main/java/dev/praxis/data/spill/ReadSpillTool.kt  (65 行 / 2.5 KB)
> * 取回溢出存储里的完整输出。
  - class ReadSpillTool

## app/src/main/java/dev/praxis/data/spill/SpillStore.kt  (122 行 / 4.6 KB)
> * 溢出存储：把过长的工具输出落到文件，只给模型一个定位符。
  - class SpillStore

## app/src/main/java/dev/praxis/data/terminal/DataStoreTerminalSnapshot.kt  (170 行 / 7.3 KB)
  - class DataStoreTerminalSnapshot

## app/src/main/java/dev/praxis/data/terminal/ShellCommandRunner.kt  (153 行 / 6.9 KB)
> * 用 `sh -c` 执行命令。
  - class ShellCommandRunner

## app/src/main/java/dev/praxis/data/terminal/TerminalSessionRegistry.kt  (82 行 / 3.0 KB)
> * 记住「AI 建过哪些终端会话名」。
  - class TerminalSessionRegistry

## app/src/main/java/dev/praxis/di/AppModule.kt  (895 行 / 47.3 KB)
  - val appModule

## app/src/main/java/dev/praxis/di/GatewayModule.kt  (38 行 / 1.2 KB)
> * 网关的接线（文档 §10 的「实现与接线（:app）」）。
  - val gatewayModule

## app/src/main/java/dev/praxis/di/NetworkModule.kt  (39 行 / 1.3 KB)
> * 网络层单例。
  - val networkModule

## app/src/main/java/dev/praxis/tool/GenerateImageTool.kt  (496 行 / 22.5 KB)
  - class GenerateImageTool

## app/src/main/java/dev/praxis/tool/ListImageGenModelsTool.kt  (75 行 / 3.0 KB)
  - class ListImageGenModelsTool

## app/src/main/java/dev/praxis/tool/SetImageGenConfigTool.kt  (245 行 / 10.5 KB)
  - class SetImageGenConfigTool

## app/src/main/java/dev/praxis/ui/ChatSidebar.kt  (573 行 / 29.7 KB)
  - fun ChatSidebar,private fun SidebarSelectionActions

## app/src/main/java/dev/praxis/ui/PraxisApp.kt  (2512 行 / 136.8 KB)
  - fun PraxisApp,private const val DEFAULT_MODEL: String

## app/src/main/java/dev/praxis/ui/shell/ChatSessionsPage.kt  (316 行 / 15.2 KB)
  - fun ChatSessionsPage,private fun SessionRow

## app/src/main/java/dev/praxis/ui/shell/MainShellPage.kt  (431 行 / 21.3 KB)
  - private val BottomBarHeight: Dp,private val BottomBarBreath: Dp,private const val SIDEBAR_CONTENT_SCALE_DROP: Float,private val SIDEBAR_CONTENT_SHIFT_X: Dp,private val SIDEBAR_CONTENT_SHIFT_Y: Dp,private val SIDEBAR_CONTENT_CORNER_RADIUS: Dp,fun MainShellPage,private fun MainTab.titleZh,private fun SidebarButton,private fun ModelSwitcher

## app/src/main/java/dev/praxis/ui/tab/ChatTab.kt  (840 行 / 42.3 KB)
  - fun ChatTab

## app/src/main/res/drawable/ic_launcher_foreground.xml  (10 行 / 0.4 KB)


## app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml  (11 行 / 0.6 KB)


## app/src/main/res/values-night/colors.xml  (11 行 / 0.4 KB)


## app/src/main/res/values-night/themes.xml  (47 行 / 2.3 KB)
> ## 为什么必须单独有这一份（2026-10-23 补，「修白屏」的连带缺陷）


## app/src/main/res/values/colors.xml  (29 行 / 1.3 KB)
> ## 为什么必须有这个颜色，而不是继续用 transparent / white


## app/src/main/res/values/strings.xml  (4 行 / 0.1 KB)


## app/src/main/res/values/themes.xml  (48 行 / 2.4 KB)
> ## 为什么 windowBackground 不能是 transparent（2026-10-23 修白屏）


## app/src/main/res/xml/backup_rules.xml  (14 行 / 0.5 KB)


## app/src/main/res/xml/data_extraction_rules.xml  (25 行 / 1.0 KB)


## app/src/main/res/xml/praxis_file_paths.xml  (17 行 / 0.6 KB)


## app/src/test/java/dev/praxis/data/AnchoredInsertTest.kt  (164 行 / 6.8 KB)
  - class AnchoredInsertTest

## app/src/test/java/dev/praxis/data/MemoryPortImplTest.kt  (136 行 / 4.0 KB)
  - private class FakeStore,private fun item,class MemoryPortImplTest

## app/src/test/java/dev/praxis/data/MessageContentGuardTest.kt  (169 行 / 6.8 KB)
  - class MessageContentGuardTest

## app/src/test/java/dev/praxis/data/ToolWorkflowPortTest.kt  (158 行 / 5.1 KB)
  - private class FakeTool,private fun spec,private val ctx,class ToolWorkflowPortTest

## app/src/test/java/dev/praxis/data/ai/OpenAiCompatProviderNonStreamingTest.kt  (76 行 / 3.0 KB)
> * 非流式分支的两个新行为（文档 §7.4 的产出点）。
  - class OpenAiCompatProviderNonStreamingTest

## app/src/test/java/dev/praxis/data/imagegen/ImageModelDownloadManagerTest.kt  (118 行 / 4.6 KB)
  - class ImageModelDownloadManagerTest

## app/src/test/java/dev/praxis/data/imagegen/ModelDownloaderTest.kt  (201 行 / 7.7 KB)
  - class ModelDownloaderTest

## app/src/test/java/dev/praxis/data/linux/HardLinkParseTest.kt  (175 行 / 6.8 KB)
> * ③ Kali/Ubuntu rootfs 解压时「认硬链接条目」的解析规则。
  - class HardLinkParseTest

## app/src/test/java/dev/praxis/data/linux/ProotLoaderStagingTest.kt  (244 行 / 9.9 KB)
  - class ProotLoaderStagingTest

## app/src/test/java/dev/praxis/data/linux/SessionShutdownTest.kt  (101 行 / 3.4 KB)
> * ⑤ 容器会话读取中断：哪些异常算「正常收尾」，哪些必须报给用户。
  - class SessionShutdownTest

## app/src/test/java/dev/praxis/tool/GenerateImageToolTest.kt  (166 行 / 7.1 KB)
  - class GenerateImageToolTest

## app/src/test/java/dev/praxis/ui/WiringGuardTest.kt  (1429 行 / 66.1 KB)
> * 接线护栏：守住「参数声明了但调用点没传」这类静默失效。
  - class WiringGuardTest

## build-plugins/build.gradle.kts  (9 行 / 0.2 KB)


## build-plugins/settings.gradle.kts  (10 行 / 0.2 KB)


## build-plugins/src/main/kotlin/PraxisBuildConfig.kt  (10 行 / 0.2 KB)
  - object PraxisBuildConfig

## build-plugins/src/main/kotlin/praxis.android.application.gradle.kts  (34 行 / 0.7 KB)


## build-plugins/src/main/kotlin/praxis.android.compose.gradle.kts  (12 行 / 0.3 KB)
> // Compose 模块叠加配置：只开 buildFeatures.compose，不引入插件依赖。


## build-plugins/src/main/kotlin/praxis.android.library.gradle.kts  (43 行 / 1.0 KB)
> // Android library 模块共用配置。


## build-plugins/src/main/kotlin/praxis.kotlin.jvm.gradle.kts  (33 行 / 0.8 KB)
> // 纯 JVM 模块共用配置（core:model / core:common / core:ai 等）。


## core/ai/build.gradle.kts  (14 行 / 0.3 KB)


## core/ai/src/main/kotlin/dev/praxis/core/ai/AiContextBudget.kt  (110 行 / 5.3 KB)
> * 上下文预算：把一段消息列表裁到「模型能吃得下」的规模。
  - object AiContextBudget

## core/ai/src/main/kotlin/dev/praxis/core/ai/AiContract.kt  (290 行 / 11.4 KB)
> * AI 层契约：与具体 provider 无关，只描述「一次请求长什么样、回来的流长什么样」。
  - typealias ThinkingEffort,enum class ChatRole,data class ChatMessage,data class ChatImage,data class ToolCall,data class ToolResult,data class ChatRequest,data class ToolDescriptor,sealed interface AiStreamEvent,interface AiProvider,class AiException

## core/ai/src/main/kotlin/dev/praxis/core/ai/AiError.kt  (131 行 / 4.9 KB)
> * 统一错误模型。
  - enum class AiErrorKind,data class AiError

## core/ai/src/main/kotlin/dev/praxis/core/ai/AnthropicRequestBuilder.kt  (187 行 / 7.9 KB)
  - object AnthropicRequestBuilder

## core/ai/src/main/kotlin/dev/praxis/core/ai/AnthropicStreamAssembler.kt  (168 行 / 6.5 KB)
> * Anthropic Messages 的**流式组装**（文档 §5.1，`AnthropicStreamAssembler`）。
  - class AnthropicStreamAssembler

## core/ai/src/main/kotlin/dev/praxis/core/ai/ChatEndpoint.kt  (76 行 / 3.6 KB)
> * 把用户填的 baseUrl 拼成真正的 Chat Completions 端点。
  - object ChatEndpoint

## core/ai/src/main/kotlin/dev/praxis/core/ai/ChatImageCompression.kt  (109 行 / 4.5 KB)
> * 消息里图片的压缩策略（文档 §5.18 的「图片压缩：开关 / 长边 / 质量 / 阈值」）。
  - data class ChatImageCompression,sealed interface ChatImageCompressionDecision,object ChatImageCompressionRules

## core/ai/src/main/kotlin/dev/praxis/core/ai/CompactionPolicy.kt  (80 行 / 3.2 KB)
> * 上下文超预算时的确定性压缩策略。
  - object CompactionPolicy

## core/ai/src/main/kotlin/dev/praxis/core/ai/ContextAssembler.kt  (75 行 / 3.3 KB)
> * 把 [CompactionPolicy]（逐条压缩）与 [AiContextBudget]（整条丢弃）串成一步。
  - object ContextAssembler

## core/ai/src/main/kotlin/dev/praxis/core/ai/ContextCompactor.kt  (777 行 / 38.4 KB)
> * 摘要提示词与配置（1:1 移植 Operit `FunctionalPrompts`，`core/config/FunctionalPrompts.kt`）。
  - object SummaryPrompts,data class SummarySectionOverride,object ContextCompactor,data class CompactedContext,object CompactionApplier,internal val SUMMARY_PROMPT: String,internal val SUMMARY_PROMPT_EN: String,data class CompactionSettings

## core/ai/src/main/kotlin/dev/praxis/core/ai/MemoryPressure.kt  (402 行 / 16.4 KB)
> * 一次内存采样（纯数据，零 Android 依赖，可单测）。
  - data class MemorySnapshot,enum class MemoryPressure,object MemoryPressurePolicy,sealed interface CompactionTrigger

## core/ai/src/main/kotlin/dev/praxis/core/ai/ModelCatalog.kt  (225 行 / 9.5 KB)
> * 一个可选模型（对齐 Operit `ModelOption`）。
  - data class AiModelOption,object ModelCatalog,interface ModelCatalogFetcher

## core/ai/src/main/kotlin/dev/praxis/core/ai/OpenAiStreamAssembler.kt  (218 行 / 9.0 KB)
> * 把 OpenAI 兼容协议的 data 负载组装成 [AiStreamEvent]。
  - class OpenAiStreamAssembler,object ContentFilterReason

## core/ai/src/main/kotlin/dev/praxis/core/ai/OpenAiUsage.kt  (64 行 / 2.5 KB)
> * 上游 `usage` 对象的解析（OpenAI 兼容）。
  - object OpenAiUsage

## core/ai/src/main/kotlin/dev/praxis/core/ai/OutboundSanitizer.kt  (117 行 / 4.3 KB)
  - data class SanitizeRule,object OutboundSanitizer,object SanitizeRuleCodec

## core/ai/src/main/kotlin/dev/praxis/core/ai/RequestThrottle.kt  (172 行 / 6.7 KB)
> * 滑动窗口限流器。对齐 Operit 的 `SlidingWindowRateLimiter`。
  - class SlidingWindowRateLimiter,class RequestThrottle

## core/ai/src/main/kotlin/dev/praxis/core/ai/ResponsesRequestBuilder.kt  (129 行 / 5.7 KB)
> * OpenAI **Responses** 协议的出站编码（文档 §5.1，`ResponsesRequestBuilder`）。
  - object ResponsesRequestBuilder

## core/ai/src/main/kotlin/dev/praxis/core/ai/ResponsesStreamAssembler.kt  (143 行 / 5.4 KB)
> * OpenAI **Responses** 的流式组装（文档 §5.1，`ResponsesStreamAssembler`）。
  - class ResponsesStreamAssembler

## core/ai/src/main/kotlin/dev/praxis/core/ai/SanitizeSettings.kt  (86 行 / 3.2 KB)
  - data class SanitizeSettings,object SanitizeSettingsCodec,object SanitizeRuleLines

## core/ai/src/main/kotlin/dev/praxis/core/ai/SseParser.kt  (126 行 / 4.6 KB)
> * Server-Sent Events 增量解析器。
  - class SseParser

## core/ai/src/main/kotlin/dev/praxis/core/ai/SummaryCondenser.kt  (515 行 / 21.7 KB)
> * 摘要瘦身用的最小正则集（`ChatMarkupRegex` 在 `:feature:chat`，`core:ai` 不能反向依赖）。
  - internal object MarkupPatterns,object SummaryCondenser,internal sealed interface Segment

## core/ai/src/main/kotlin/dev/praxis/core/ai/SystemPromptBuilder.kt  (281 行 / 12.9 KB)
> * 系统提示词的分层与优先级。
  - enum class PromptPriority,data class SystemPromptSettings,object SystemPromptBuilder

## core/ai/src/main/kotlin/dev/praxis/core/ai/SystemPromptTemplates.kt  (73 行 / 2.5 KB)
> * 系统提示词的骨架模板（照搬 Operit 的占位符做法）。
  - object SystemPromptTemplates

## core/ai/src/main/kotlin/dev/praxis/core/ai/TokenEstimator.kt  (234 行 / 9.1 KB)
> * 上下文用量估算（1:1 移植 Operit `ChatUtils.estimateTokenCount`，
  - object TokenEstimator,class TokenCache

## core/ai/src/test/kotlin/dev/praxis/core/ai/AiContextBudgetTest.kt  (214 行 / 8.8 KB)
> * 上下文预算与装配器的行为。
  - class AiContextBudgetTest

## core/ai/src/test/kotlin/dev/praxis/core/ai/AiErrorTest.kt  (48 行 / 1.9 KB)
> * 错误归类表。
  - class AiErrorTest

## core/ai/src/test/kotlin/dev/praxis/core/ai/AnthropicRequestBuilderTest.kt  (177 行 / 6.8 KB)
  - class AnthropicRequestBuilderTest

## core/ai/src/test/kotlin/dev/praxis/core/ai/AnthropicStreamAssemblerTest.kt  (154 行 / 6.3 KB)
  - class AnthropicStreamAssemblerTest

## core/ai/src/test/kotlin/dev/praxis/core/ai/ChatEndpointTest.kt  (153 行 / 5.9 KB)
> * 端点拼接的回归护栏。
  - class ChatEndpointTest

## core/ai/src/test/kotlin/dev/praxis/core/ai/ChatImageCompressionTest.kt  (73 行 / 2.7 KB)
  - class ChatImageCompressionTest

## core/ai/src/test/kotlin/dev/praxis/core/ai/CompactionBenefitTest.kt  (92 行 / 3.3 KB)
> * 压缩的收益投影与可逆性。
  - class CompactionBenefitTest

## core/ai/src/test/kotlin/dev/praxis/core/ai/CompactionPolicyTest.kt  (64 行 / 1.9 KB)
> * 压缩策略的现行契约：**不截断**（2026-11-07，用户要求）。
  - class CompactionPolicyTest

## core/ai/src/test/kotlin/dev/praxis/core/ai/ContextCompactorTest.kt  (478 行 / 17.8 KB)
> * 上下文压缩与应用。
  - class ContextCompactorTest,private object ContextPromptsHelper,class TokenEstimatorTest,class TokenCacheTest

## core/ai/src/test/kotlin/dev/praxis/core/ai/MemoryPressurePolicyTest.kt  (308 行 / 12.4 KB)
> * 内存压力判定的回归护栏。
  - class MemoryPressurePolicyTest

## core/ai/src/test/kotlin/dev/praxis/core/ai/ModelCatalogTest.kt  (184 行 / 7.3 KB)
> * `ModelCatalog` 的回归测试。
  - class ModelCatalogTest

## core/ai/src/test/kotlin/dev/praxis/core/ai/OpenAiStreamAssemblerTest.kt  (249 行 / 9.9 KB)
> * 组装器的核心断言：工具调用必须**先累积完整再吐出**。
  - class OpenAiStreamAssemblerTest

## core/ai/src/test/kotlin/dev/praxis/core/ai/OpenAiToolCallProtocolTest.kt  (122 行 / 5.8 KB)
> * OpenAI 标准 toolcall 协议的组装行为锁定。
  - class OpenAiToolCallProtocolTest

## core/ai/src/test/kotlin/dev/praxis/core/ai/OpenAiUsageTest.kt  (59 行 / 2.1 KB)
  - class OpenAiUsageTest

## core/ai/src/test/kotlin/dev/praxis/core/ai/OutboundSanitizerTest.kt  (85 行 / 3.0 KB)
  - class OutboundSanitizerTest

## core/ai/src/test/kotlin/dev/praxis/core/ai/RequestThrottleQueueTest.kt  (73 行 / 2.7 KB)
  - class RequestThrottleQueueTest

## core/ai/src/test/kotlin/dev/praxis/core/ai/RequestThrottleTest.kt  (120 行 / 3.7 KB)
  - class RequestThrottleTest

## core/ai/src/test/kotlin/dev/praxis/core/ai/ResponsesRequestBuilderTest.kt  (157 行 / 5.9 KB)
  - class ResponsesRequestBuilderTest

## core/ai/src/test/kotlin/dev/praxis/core/ai/ResponsesStreamAssemblerTest.kt  (124 行 / 5.2 KB)
  - class ResponsesStreamAssemblerTest

## core/ai/src/test/kotlin/dev/praxis/core/ai/SanitizeSettingsTest.kt  (75 行 / 2.5 KB)
  - class SanitizeSettingsTest

## core/ai/src/test/kotlin/dev/praxis/core/ai/SseParserTest.kt  (108 行 / 3.4 KB)
> * SSE 解析器的边界行为。
  - class SseParserTest

## core/ai/src/test/kotlin/dev/praxis/core/ai/SummaryCondenserTest.kt  (188 行 / 7.6 KB)
> * 摘要输入瘦身（1:1 对齐 Operit `AIMessageManager.summarizeMemory` 的 condense*）。
  - class SummaryCondenserTest

## core/ai/src/test/kotlin/dev/praxis/core/ai/SystemPromptBuilderTest.kt  (132 行 / 5.4 KB)
> * 系统提示词分层与优先级（2026-11-07：改成单条 + 模板填充）。
  - class SystemPromptBuilderTest

## core/data/build.gradle.kts  (30 行 / 0.6 KB)
> // 网关领域模型（AccountView 等）。方向：data -> 领域，无环。


## core/data/src/main/AndroidManifest.xml  (7 行 / 0.4 KB)


## core/data/src/main/java/dev/praxis/core/data/permission/CapabilityDetector.kt  (418 行 / 20.4 KB)
  - class CapabilityDetector

## core/data/src/main/java/dev/praxis/core/data/permission/CapabilityRequester.kt  (304 行 / 13.6 KB)
> * 运行时权限申请的**最小桥接接口**。
  - fun interface PermissionLaunchBridge,class CapabilityRequester

## core/data/src/main/kotlin/dev/praxis/core/data/character/CharacterDao.kt  (145 行 / 5.6 KB)
  - interface CharacterCardDao,interface PromptTagDao

## core/data/src/main/kotlin/dev/praxis/core/data/character/CharacterDatabase.kt  (40 行 / 1.6 KB)
> * 角色系统的数据库访问收口接口。
  - interface CharacterDatabase

## core/data/src/main/kotlin/dev/praxis/core/data/character/CharacterEntities.kt  (246 行 / 9.6 KB)
  - data class CharacterCardEntity,data class PromptTagEntity,internal object SplitCodec,internal object ToolAccessCodec

## core/data/src/main/kotlin/dev/praxis/core/data/character/CharacterRepository.kt  (402 行 / 17.0 KB)
  - class CharacterRepository

## core/data/src/main/kotlin/dev/praxis/core/data/gateway/GatewayAccountEntity.kt  (37 行 / 1.2 KB)
> * 网关账号（对应网关的 account_store）。
  - data class GatewayAccountEntity

## core/data/src/main/kotlin/dev/praxis/core/data/gateway/GatewayAccountMapper.kt  (37 行 / 1.3 KB)
> * 账号实体 -> 选路视图。
  - fun GatewayAccountEntity.toAccountView

## core/data/src/main/kotlin/dev/praxis/core/data/gateway/GatewayAccountStore.kt  (325 行 / 13.4 KB)
  - data class StoredImport,class GatewayAccountStore,fun GatewayCustomProvider.toEntity

## core/data/src/main/kotlin/dev/praxis/core/data/gateway/GatewayDao.kt  (55 行 / 1.6 KB)
> * 网关账号表读写。
  - interface GatewayAccountDao,interface GatewayProviderDao

## core/data/src/main/kotlin/dev/praxis/core/data/gateway/GatewayModelDao.kt  (40 行 / 1.3 KB)
> * 结构化模型条目的读写。
  - interface GatewayModelDao

## core/data/src/main/kotlin/dev/praxis/core/data/gateway/GatewayModelEntity.kt  (49 行 / 1.8 KB)
> * 结构化模型条目（文档 6.3，对应网关 catalog 的一行）。
  - data class GatewayModelEntity

## core/data/src/main/kotlin/dev/praxis/core/data/gateway/GatewayModelMapper.kt  (65 行 / 2.2 KB)
  - private fun List<String>.encodeLines,private fun String.decodeLines,fun ModelEntry.toEntity,fun GatewayModelEntity.toModelEntry

## core/data/src/main/kotlin/dev/praxis/core/data/gateway/GatewayProviderEntity.kt  (45 行 / 1.7 KB)
> * 自定义提供商（对应网关的 customProviders）。
  - data class GatewayProviderEntity

## core/data/src/main/kotlin/dev/praxis/core/data/gateway/GatewayUsageEntities.kt  (174 行 / 6.5 KB)
> * 一条网关请求记录（文档 6.1 的 `gateway_request`）。
  - data class GatewayRequestEntity,data class GatewayDailyEntity,interface GatewayUsageDao,data class GatewayUsageSummaryRow,data class GatewayAccountTokensRow,data class GatewayUsageGroupRow

## core/data/src/main/kotlin/dev/praxis/core/data/log/LogLine.kt  (157 行 / 6.4 KB)
> * 一行运行日志的**文本协议**（写入端与解析端的唯一契约）。
  - object LogLine,data class LogEntry,object LogParser

## core/data/src/main/kotlin/dev/praxis/core/data/log/PraxisLogger.kt  (441 行 / 16.4 KB)
  - object PraxisLogger

## core/data/src/main/kotlin/dev/praxis/core/data/log/PraxisTimberTree.kt  (89 行 / 3.7 KB)
> * 把 Timber 的调用转发到 [PraxisLogger]（即「落在 logcat + 落盘」两条腿都走）。
  - class PraxisTimberTree

## core/data/src/main/kotlin/dev/praxis/core/data/security/KeystoreCipher.kt  (129 行 / 5.2 KB)
  - class KeystoreCipher

## core/data/src/main/kotlin/dev/praxis/core/data/settings/SettingsRepository.kt  (1743 行 / 77.7 KB)
  - class SettingsRepository,private inline fun <reified T : Enum<T>> String?.toEnumOr,private val KEY_THEME_MODE,private val KEY_COLOR_SOURCE,private val KEY_SEED_COLOR,private val KEY_PALETTE,private val KEY_COLOR_SPEC,private val KEY_PREDICTIVE_BACK,private val KEY_PREDICTIVE_BACK_DIRECTION,private val KEY_USE_MIUIX,private val KEY_CHAT_DISPLAY_STYLE,private val KEY_BASE_URL,private val KEY_MODEL,private val KEY_MODEL_INDEX,private val KEY_TEMPERATURE,private val KEY_MODEL_PROVIDER,private val KEY_API_KEY,private val KEY_FUNCTION_MODELS,private val KEY_CONTEXT_LIMIT,private val KEY_COMPACT_THRESHOLD,private val KEY_THINKING_EFFORT,private val KEY_CUSTOM_HEADERS,private val KEY_REQUEST_LIMIT_RPM,private val KEY_MAX_CONCURRENT,private val KEY_SYSTEM_PROMPT,private val KEY_GATEWAY_MODEL_RULES,private val KEY_GATEWAY_ENABLED,private val KEY_IMAGEGEN_TARGET,private val KEY_GATEWAY_ROUTING_POLICY,private val KEY_IMAGEGEN_DOWNLOAD,private val KEY_IMAGEGEN_DEFAULTS,private val KEY_IMAGEGEN_OUTPUT,private val KEY_GATEWAY_RETENTION_DAYS,private val KEY_GATEWAY_ROUTE_GROUPS,private val KEY_GATEWAY_BUILTIN_MODELS,private val KEY_GATEWAY_SYSTEM,private val KEY_GATEWAY_PROXY,private val KEY_GATEWAY_SANITIZE,private val KEY_PERSONA_PROMPT,private val KEY_LINUX_DISTRO,private val KEY_LINUX_MIRROR,private val KEY_LINUX_RELEASE,private val KEY_FTP_RUNTIME,private val KEY_TERMINAL_FONT_SIZE,private val KEY_TERMINAL_SCROLLBACK,private val KEY_TERMINAL_BANNER,private val KEY_LINUX_SHARED_TMP,private val KEY_VIRTUAL_KEYBOARD_LABELS,private val KEY_LAST_CHAT_SESSION,private val KEY_AUTO_MEMORY,private val KEY_DISABLED_SKILLS,private val KEY_DISABLED_TOOLS,private const val LABEL_SEPARATOR,private val KEY_PERMISSION_LEVEL,private val KEY_DISABLE_STREAM_OUTPUT,private val KEY_AUTO_READ_REPLIES,private val KEY_ACTIVE_MEMORY_SPACE,private val KEY_MEMORY_SPACES,private fun String?.toLevelOrNull,private val Context.praxisSettingsStore: DataStore<Preferences> by preferencesDataStore

## core/data/src/test/kotlin/dev/praxis/core/data/gateway/GatewayAccountMapperTest.kt  (64 行 / 2.0 KB)
  - class GatewayAccountMapperTest

## core/data/src/test/kotlin/dev/praxis/core/data/gateway/GatewayAccountStoreTest.kt  (230 行 / 8.3 KB)
  - class GatewayAccountStoreTest

## core/data/src/test/kotlin/dev/praxis/core/data/gateway/GatewayModelMapperTest.kt  (89 行 / 2.9 KB)
> * 结构化模型条目的存储映射（文档 6.3）。
  - class GatewayModelMapperTest

## core/data/src/test/kotlin/dev/praxis/core/data/log/LogParserTest.kt  (119 行 / 5.0 KB)
> * 锁死「写」和「读」共用同一份格式。
  - class LogParserTest

## core/data/src/test/kotlin/dev/praxis/core/data/security/KeystoreCipherTest.kt  (49 行 / 1.7 KB)
> * 只测 [KeystoreCipher.looksEncrypted]。
  - class KeystoreCipherTest

## core/data/src/test/kotlin/dev/praxis/core/data/settings/ModelProfilesTest.kt  (379 行 / 14.3 KB)
  - class ModelProfilesTest

## core/gateway/build.gradle.kts  (16 行 / 0.6 KB)
> // 网关导出文件是**规范 JSON**（字段名由网关定义），与清单那种 HJSON 不同，


## core/gateway/src/main/kotlin/dev/praxis/core/gateway/AiErrorMapping.kt  (44 行 / 1.9 KB)
> * AI 层的错误分类 -> 文档 7.4 的失败分类。
  - fun AiErrorKind.toFailureKind

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/AutoContinuePolicy.kt  (86 行 / 2.7 KB)
> * 自动续写配置（gateway_auto_continue + maxRounds）。
  - data class AutoContinueConfig,enum class FinishSignal,enum class AutoContinueStopReason,sealed interface AutoContinueDecision,object AutoContinuePolicy

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/BackupManifest.kt  (99 行 / 4.1 KB)
  - data class BackupManifest,object BackupManifestCodec,sealed interface RestoreVerdict

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/BuiltinModelsCodec.kt  (79 行 / 3.1 KB)
> * 内置家的**模型清单**（拉下来之后存这里）。
  - object BuiltinModelsCodec

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/CandidateChain.kt  (162 行 / 6.2 KB)
  - data class ModelCandidate,data class NativeModel,data class ModelMapping,data class CandidateChainResult,data class CaseCollision,object CandidateChain

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/ConversationKey.kt  (59 行 / 2.3 KB)
> * 会话粘性键（文档 7.3）。
  - object ConversationKey

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/CooldownKey.kt  (21 行 / 0.9 KB)
> * 冷却键：**账号 + 上游真名**。
  - data class CooldownKey

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/FailoverLoop.kt  (100 行 / 3.6 KB)
  - data class FailoverBudget,data class FailoverState,sealed interface FailoverStep,object FailoverLoop

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/FailurePolicy.kt  (149 行 / 4.9 KB)
> * 一次请求失败的分类。
  - enum class FailureKind,enum class FailoverAction,data class FailureDecision,object FailurePolicy

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/FinishSignalRules.kt  (35 行 / 1.3 KB)
> * 把上游的 `finish_reason` 映射成 [FinishSignal]（文档 §7.5）。
  - object FinishSignalRules

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/GatewayAccountDraft.kt  (93 行 / 3.8 KB)
> * 「手动添加一个账号」的草稿（文档 §9.3 的三级流程）。
  - data class GatewayAccountDraft,enum class GatewayAccountDraftError,object GatewayAccountDraftRules,fun GatewayAccountDraftError.userMessage

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/GatewayAccountFile.kt  (40 行 / 1.3 KB)
> * 网关账号文件（一个账号一个 JSON）。
  - const val ACCOUNT_FORMAT: String,data class GatewayAccountFile,data class GatewayCustomProvider

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/GatewayAccountRow.kt  (47 行 / 1.4 KB)
> * 账号列表的一行（界面模型）。
  - data class GatewayAccountRow

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/GatewayAccountStatus.kt  (128 行 / 4.2 KB)
  - enum class GatewayAccountState,enum class GatewayCooldownReason,data class GatewayAccountStatus,object GatewayAccountStatusResolver

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/GatewayConfigExporter.kt  (62 行 / 3.2 KB)
> * 把 [GatewayRoutingConfig] 写回网关原生的 config 片段（文档 8.5）。
  - object GatewayConfigExporter

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/GatewayConfigImporter.kt  (217 行 / 8.9 KB)
> * 从网关 config.json 读出来的路由/重试/超时配置。
  - data class GatewayRoutingConfig,data class GatewayConfigImportResult,object GatewayConfigImporter

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/GatewayCredential.kt  (111 行 / 4.1 KB)
> * 一个账号的凭证形态。
  - sealed interface GatewayCredential,object GatewayCredentialCodec,fun GatewayAccountFile.toCredential

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/GatewayDailyKey.kt  (30 行 / 1.1 KB)
> * 日聚合的键（文档 §5.7）。
  - object GatewayDailyKey

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/GatewayExportPlan.kt  (173 行 / 6.7 KB)
> * 导出产物里的一个文件。
  - data class GatewayExportFile,data class GatewayExportPlan,object GatewayExportPlanner

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/GatewayExportWriter.kt  (75 行 / 2.9 KB)
> * 一次导出实际写出去的东西。
  - data class GatewayExportResult,object GatewayExportWriter

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/GatewayExporter.kt  (65 行 / 2.2 KB)
> * 网关配置导出：领域模型 -> 网关原生 JSON。
  - object GatewayExporter

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/GatewayGate.kt  (53 行 / 2.0 KB)
> * 一次出站请求该走哪条链路。
  - enum class OutboundRoute,object GatewayGate

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/GatewayImportReport.kt  (31 行 / 1.0 KB)
> * 导入报告。**固定四段**：新增 / 更新 / 跳过 / 失败（文档 §8.6）。
  - data class GatewayImportReport

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/GatewayImporter.kt  (274 行 / 11.4 KB)
> * 一份可执行的导入计划：既能落库的东西，也是给用户看的报告。
  - data class GatewayImportPlan,object GatewayImporter

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/GatewayLoginMethods.kt  (88 行 / 3.5 KB)
  - enum class GatewayLoginType,data class GatewayLoginMethod,object GatewayLoginMethods

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/GatewayModelCatalog.kt  (47 行 / 1.7 KB)
> * 「网关对外模型清单」：用户在模型页能选的那些名字（文档 9.1 的 gatewayModels）。
  - object GatewayModelCatalog

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/GatewayModelCatalogCache.kt  (88 行 / 3.4 KB)
> * 模型清单刷新的缓存与「失败保留旧清单」规则（文档 §5.4.1 第 17 条）。
  - object GatewayModelCatalogCache

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/GatewayModelOptions.kt  (34 行 / 1.3 KB)
> * 模型页候选清单的合并（文档 §9.1）。
  - object GatewayModelOptions

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/GatewayModelRow.kt  (50 行 / 1.9 KB)
> * 模型管理页的一行（`gateway_model` 的展示视图）。
  - data class GatewayModelRow,object GatewayModelEntryDefaults

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/GatewayModelRules.kt  (123 行 / 3.7 KB)
> * 网关的 modelRules（文档 8.3）。
  - data class GatewayModelRules,data class GatewayDisabledModel,data class GatewayMappingRule,data class GatewayCustomModel,data class GatewayModelCapability,object GatewayModelRulesCodec

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/GatewayProviderDraft.kt  (81 行 / 3.3 KB)
> * 新建 / 修改一个自定义提供商（文档 §5.3）。
  - data class GatewayProviderDraft,enum class GatewayProviderDraftError,object GatewayProviderDraftRules,fun GatewayProviderDraftError.userMessage

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/GatewayQueue.kt  (69 行 / 2.7 KB)
> * 候选队列里的一条（文档 9.4 的「候选队列」：位次 · 账号 · 可用/跳过原因）。
  - data class GatewayQueueEntry,object GatewayQueue

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/GatewayReport.kt  (94 行 / 3.4 KB)
> * 报表里的一段时间（文档 §9.6 的「今日 / 近 7 天 / 近 30 天」）。
  - data class GatewayReportWindow,data class GatewayUsageSummary,data class GatewayUsageBucket,object GatewayReportFormat,data class GatewayRequestRecord

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/GatewayRetention.kt  (48 行 / 1.8 KB)
> * 请求记录的保留期（文档 §5.7 的数据保留）。
  - object GatewayRetention

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/GatewayRouteGroup.kt  (150 行 / 6.0 KB)
  - data class RouteGroupMember,data class GatewayRouteGroup,enum class GatewayRouteGroupError,object GatewayRouteGroupRules,fun GatewayRouteGroupError.userMessage,object GatewayRouteGroupCodec

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/GatewayRoutingPolicy.kt  (143 行 / 6.4 KB)
  - enum class RoutingStrategy,data class GatewayRoutingPolicy,object GatewayRoutingPolicyCodec

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/GatewaySettingsExport.kt  (125 行 / 5.7 KB)
> * 把 Praxis 的设置**还原成网关原生的 config 片段**（导出，文档 §8.5）。
  - object GatewaySettingsExport

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/GatewayStatusText.kt  (78 行 / 3.1 KB)
> * 状态的界面文案（文档 9.0 的三态灯 + 冷却倒计时）。
  - object GatewayStatusText

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/GatewaySystemSettings.kt  (182 行 / 8.8 KB)
  - data class GatewaySystemSettings,object GatewaySystemSettingsRules,object GatewaySystemSettingsCodec

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/GatewayTokenBudget.kt  (53 行 / 2.0 KB)
> * token 预算（文档 §7.2 的过滤链第 6 条「每账号/每家的 token 预算」）。
  - data class GatewayTokenBudget,object GatewayTokenBudgetRules

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/GatewayUsageDraft.kt  (76 行 / 2.5 KB)
> * 一条待落库的请求记录（文档 6.1 的 `gateway_request` 的领域视图）。
  - data class GatewayUsageDraft,object GatewayUsageRules

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/ModelEntry.kt  (106 行 / 3.7 KB)
  - enum class ModelKind,enum class ModelSource,data class ModelEntry,data class ModelEntryMigrationResult,object ModelEntryMigration

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/ProviderProtocols.kt  (82 行 / 3.2 KB)
  - enum class ProviderProtocol,object ProviderProtocols

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/ProxySettings.kt  (168 行 / 6.5 KB)
  - enum class ProxyNodeKind,data class ProxyNode,data class ProxySettings,enum class ProxyNodeError,fun ProxyNodeError.userMessage,object ProxyRules,object ProxySettingsCodec

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/RoutePlanner.kt  (266 行 / 10.6 KB)
> * 参与选路的账号视图。
  - data class AccountView,data class ProviderUsage,enum class SkipReason,data class PlannedAttempt,data class SkippedAttempt,data class RoutePlan,object RoutePlanner

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/RouteReplayLog.kt  (65 行 / 2.3 KB)
> * 一次路由决策的留痕（文档 §5.5 的「回放」，**降级为最近 N 条**）。
  - data class RouteReplayEntry,class RouteReplayLog

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/RouteState.kt  (193 行 / 7.2 KB)
> * 熔断四参（配置键见文档 6.4 的 gateway_breaker_*）。
  - data class BreakerConfig,class RouteState

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/RouterSession.kt  (224 行 / 8.5 KB)
> * 一次请求的结束方式。
  - sealed interface RouteOutcome,class RouterSession

## core/gateway/src/main/kotlin/dev/praxis/core/gateway/UpstreamErrorClassifier.kt  (119 行 / 4.9 KB)
> * 把一次上游失败判成 [AiErrorKind]（文档 §7.4 那张表的**产出点**）。
  - object UpstreamErrorClassifier

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/AiErrorMappingTest.kt  (62 行 / 2.0 KB)
> * AI 错误 -> 失败分类（文档 7.4 的落地）。
  - class AiErrorMappingTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/AutoContinuePolicyTest.kt  (105 行 / 3.0 KB)
> * 自动续写判定（文档 7.5）。
  - class AutoContinuePolicyTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/BackupManifestTest.kt  (70 行 / 2.6 KB)
  - class BackupManifestTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/BuiltinModelsCodecTest.kt  (48 行 / 1.8 KB)
  - class BuiltinModelsCodecTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/CandidateChainTest.kt  (241 行 / 7.9 KB)
> * 候选链生成（文档 7.1）。
  - class CandidateChainTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/ConversationKeyTest.kt  (74 行 / 2.1 KB)
> * 会话粘性键（文档 7.3）。
  - class ConversationKeyTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/FailoverLoopTest.kt  (115 行 / 3.3 KB)
> * 失败切换循环（文档 7.4 + 12 的换号上限）。
  - class FailoverLoopTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/FailurePolicyTest.kt  (121 行 / 4.2 KB)
> * 失败分类 -> 动作（文档 7.4 那张表逐行）。
  - class FailurePolicyTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/FinishSignalRulesTest.kt  (34 行 / 1.4 KB)
> // 很多中转站根本不发这个字段；当成截断会让每次正常回答都多跑一轮（花钱）。
  - class FinishSignalRulesTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/GatewayAccountDraftTest.kt  (90 行 / 2.8 KB)
  - class GatewayAccountDraftTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/GatewayAccountRowTest.kt  (72 行 / 2.1 KB)
> * 界面行模型的组装（cp：账号状态推导）。
  - class GatewayAccountRowTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/GatewayAccountStatusTest.kt  (148 行 / 4.8 KB)
> * 账号状态推导（文档 9.0 的三态灯 + 冷却倒计时）。
  - class GatewayAccountStatusTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/GatewayConfigExporterTest.kt  (106 行 / 3.4 KB)
> * config 片段的导出（文档 8.5 的「双向可逆」）。
  - class GatewayConfigExporterTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/GatewayConfigImporterTest.kt  (156 行 / 5.2 KB)
> * 网关 config.json 的键映射（文档 8.4）。
  - class GatewayConfigImporterTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/GatewayConfigRoundTripTest.kt  (98 行 / 4.2 KB)
> * config 片段的**往返**（文档 §8.5 的验收）。
  - class GatewayConfigRoundTripTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/GatewayCredentialTest.kt  (101 行 / 3.1 KB)
> * 凭证模型与编解码（文档 6.1 的 credentialsCipher 形态）。
  - class GatewayCredentialTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/GatewayDailyKeyTest.kt  (61 行 / 2.1 KB)
  - class GatewayDailyKeyTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/GatewayExportPlanTest.kt  (130 行 / 5.0 KB)
> * 导出计划的规格（文档 8.5）。
  - class GatewayExportPlanTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/GatewayExportWriterTest.kt  (80 行 / 2.9 KB)
> * 落盘层的规格。
  - class GatewayExportWriterTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/GatewayExporterTest.kt  (107 行 / 3.6 KB)
> * 导出的两条硬要求（文档 8.5）。
  - class GatewayExporterTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/GatewayGateTest.kt  (40 行 / 1.6 KB)
> * 总开关的四种组合（文档 §11 末尾的回滚要求）。
  - class GatewayGateTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/GatewayImporterBulkTest.kt  (137 行 / 4.9 KB)
> * 批量导出形状的导入（用户实测的那份：顶层 accounts 数组 + accessToken 型凭证）。
  - class GatewayImporterBulkTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/GatewayImporterTest.kt  (199 行 / 6.5 KB)
> * 网关账号导入的报告规则（文档 §8.2 / §8.6）。
  - class GatewayImporterTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/GatewayLoginMethodsTest.kt  (68 行 / 2.4 KB)
> // 它是唯一一定成功的方式：不依赖页面能否打开。
  - class GatewayLoginMethodsTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/GatewayModelCatalogCacheTest.kt  (71 行 / 2.4 KB)
  - class GatewayModelCatalogCacheTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/GatewayModelCatalogTest.kt  (74 行 / 2.3 KB)
> * 对外模型清单（文档 9.1 的 gatewayModels）。
  - class GatewayModelCatalogTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/GatewayModelOptionsTest.kt  (51 行 / 1.4 KB)
  - class GatewayModelOptionsTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/GatewayModelRulesTest.kt  (129 行 / 4.5 KB)
> * modelRules 的解析与回写（文档 8.3）。
  - class GatewayModelRulesTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/GatewayProviderDraftTest.kt  (89 行 / 2.8 KB)
  - class GatewayProviderDraftTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/GatewayQueueTest.kt  (93 行 / 2.6 KB)
> * 候选队列摊平（文档 9.4）。
  - class GatewayQueueTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/GatewayReportFormatTest.kt  (63 行 / 2.2 KB)
  - class GatewayReportFormatTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/GatewayRetentionTest.kt  (67 行 / 2.4 KB)
  - class GatewayRetentionTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/GatewayRouteGroupTest.kt  (126 行 / 4.6 KB)
  - class GatewayRouteGroupTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/GatewayRoutingPolicyTest.kt  (181 行 / 6.6 KB)
  - class GatewayRoutingPolicyTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/GatewaySettingsExportTest.kt  (124 行 / 5.1 KB)
  - class GatewaySettingsExportTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/GatewayStatusTextTest.kt  (110 行 / 3.7 KB)
> * 状态文案（文档 9.0 的三态灯 + 冷却倒计时）。
  - class GatewayStatusTextTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/GatewaySystemSettingsTest.kt  (147 行 / 5.1 KB)
  - class GatewaySystemSettingsTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/GatewayTokenBudgetTest.kt  (56 行 / 2.1 KB)
  - class GatewayTokenBudgetTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/GatewayUsageRulesTest.kt  (59 行 / 1.7 KB)
  - class GatewayUsageRulesTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/ModelEntryMigrationTest.kt  (94 行 / 3.0 KB)
> * 老模型池到结构化条目的迁移（文档 6.3）。
  - class ModelEntryMigrationTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/ProviderProtocolsTest.kt  (63 行 / 2.8 KB)
  - class ProviderProtocolsTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/ProxySettingsTest.kt  (123 行 / 4.6 KB)
  - class ProxySettingsTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/RoutePlannerBudgetTest.kt  (88 行 / 2.8 KB)
  - class RoutePlannerBudgetTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/RoutePlannerTest.kt  (252 行 / 8.1 KB)
> * 过滤与排序（文档 7.2 / 7.3）。
  - class RoutePlannerTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/RouteReplayLogTest.kt  (72 行 / 2.1 KB)
  - class RouteReplayLogTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/RouteStateTest.kt  (177 行 / 5.2 KB)
> * 路由运行期状态（文档 6.5 / 7.4）。
  - class RouteStateTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/RouterSessionTest.kt  (199 行 / 6.0 KB)
> * 请求路由状态机（文档 7.1 ~ 7.4 的落地）。
  - class RouterSessionTest

## core/gateway/src/test/kotlin/dev/praxis/core/gateway/UpstreamErrorClassifierTest.kt  (105 行 / 3.7 KB)
> * 三类错误的产出点（文档 §7.4）。
  - class UpstreamErrorClassifierTest

## core/goal/build.gradle.kts  (15 行 / 0.4 KB)
> // R14：测试里用了 runTest / TestDispatcher。本模块的测试从未在 CI 上编译过


## core/goal/src/main/kotlin/dev/praxis/core/goal/SessionGoal.kt  (165 行 / 5.7 KB)
> * 同会话目标的阶段。
  - enum class GoalPhase,data class PlanStep,data class SessionGoal,object SessionGoalOps

## core/goal/src/test/kotlin/dev/praxis/core/goal/SessionGoalTest.kt  (127 行 / 4.6 KB)
> * 目标状态机。
  - class SessionGoalTest

## core/hyperceiler-common/build.gradle.kts  (43 行 / 1.7 KB)
> // HyperCeiler `library/common` 的原样搬运。


## core/hyperceiler-common/src/main/AndroidManifest.xml  (20 行 / 0.8 KB)


## core/hyperceiler-common/src/main/java/com/sevtinge/hyperceiler/common/base/BaseActivity.java  (107 行 / 2.5 KB)
  - public abstract class BaseActivity extends AppCompatActivity

## core/hyperceiler-common/src/main/java/com/sevtinge/hyperceiler/common/base/BasePreferenceFragment.java  (127 行 / 3.4 KB)
  - public abstract class BasePreferenceFragment extends PreferenceFragment

## core/hyperceiler-common/src/main/java/com/sevtinge/hyperceiler/common/log/AndroidLog.kt  (166 行 / 4.8 KB)
> * Android 日志工具类
  - object AndroidLog

## core/hyperceiler-common/src/main/java/com/sevtinge/hyperceiler/common/log/LogConfigManager.kt  (120 行 / 3.9 KB)
> * This file is part of HyperCeiler.
  - object LogConfigManager

## core/hyperceiler-common/src/main/java/com/sevtinge/hyperceiler/common/log/LogLevelManager.kt  (71 行 / 2.1 KB)
> * This file is part of HyperCeiler.
  - object LogLevelManager

## core/hyperceiler-common/src/main/java/com/sevtinge/hyperceiler/common/log/LogStatusManager.java  (301 行 / 10.1 KB)
> * This file is part of HyperCeiler.
  - public class LogStatusManager

## core/hyperceiler-common/src/main/java/com/sevtinge/hyperceiler/common/log/LoggerHealthChecker.kt  (233 行 / 7.4 KB)
> * This file is part of HyperCeiler.
  - object LoggerHealthChecker

## core/hyperceiler-common/src/main/java/com/sevtinge/hyperceiler/common/log/LoggerUtils.kt  (27 行 / 0.9 KB)
  - internal object LoggerUtils

## core/hyperceiler-common/src/main/java/com/sevtinge/hyperceiler/common/log/XposedLog.kt  (160 行 / 4.4 KB)
> * Xposed 日志工具类
  - object XposedLog

## core/hyperceiler-common/src/main/java/com/sevtinge/hyperceiler/common/utils/AppLanguageHelper.java  (541 行 / 21.7 KB)
  - public class AppLanguageHelper

## core/hyperceiler-common/src/main/java/com/sevtinge/hyperceiler/common/utils/AppSettingsStore.java  (292 行 / 10.5 KB)


## core/hyperceiler-common/src/main/java/com/sevtinge/hyperceiler/common/utils/CrashIntentContract.java  (15 行 / 0.6 KB)


## core/hyperceiler-common/src/main/java/com/sevtinge/hyperceiler/common/utils/GetAppsDeviceConfig.java  (93 行 / 4.4 KB)


## core/hyperceiler-common/src/main/java/com/sevtinge/hyperceiler/common/utils/PermissionUtils.java  (71 行 / 2.6 KB)
> * This file is part of HyperCeiler.


## core/hyperceiler-common/src/main/java/com/sevtinge/hyperceiler/common/utils/PrefsBridge.java  (633 行 / 23.5 KB)
  - public class PrefsBridge

## core/hyperceiler-common/src/main/java/com/sevtinge/hyperceiler/common/utils/PrefsConfigurator.java  (42 行 / 1.7 KB)
  - public class PrefsConfigurator

## core/hyperceiler-common/src/main/java/com/sevtinge/hyperceiler/common/utils/PrefsDataStore.java  (77 行 / 2.0 KB)
> * 自定义 Preference 存储拦截器
  - public class PrefsDataStore extends PreferenceDataStore

## core/hyperceiler-common/src/main/java/com/sevtinge/hyperceiler/common/utils/ShellUtils.java  (333 行 / 11.3 KB)
> * This file is part of HyperCeiler.
  - public class ShellUtils

## core/hyperceiler-common/src/main/java/com/sevtinge/hyperceiler/common/utils/api/ProjectApi.java  (67 行 / 1.8 KB)
> * This file is part of HyperCeiler.
  - public class ProjectApi

## core/hyperceiler-common/src/main/java/com/sevtinge/hyperceiler/common/utils/prefs/PrefType.java  (10 行 / 0.1 KB)


## core/hyperceiler-common/src/main/java/com/sevtinge/hyperceiler/common/utils/prefs/PrefsChangeObserver.java  (205 行 / 7.4 KB)
  - public class PrefsChangeObserver extends ContentObserver implements SharedPreferences.OnSharedPreferenceChangeListener

## core/hyperceiler-common/src/main/java/com/sevtinge/hyperceiler/common/utils/shell/IResult.java  (16 行 / 0.3 KB)
  - public interface IResult

## core/hyperceiler-common/src/main/java/com/sevtinge/hyperceiler/common/utils/shell/ShellExec.java  (426 行 / 13.8 KB)
  - public class ShellExec

## core/hyperceiler-common/src/main/java/com/sevtinge/hyperceiler/common/utils/shell/ShellInit.java  (74 行 / 2.0 KB)
  - public class ShellInit

## core/hyperceiler-common/src/main/java/com/sevtinge/hyperceiler/common/utils/shell/ShellPackageManager.java  (43 行 / 1.5 KB)
  - public class ShellPackageManager

## core/hyperceiler-common/src/main/java/com/sevtinge/hyperceiler/common/view/CircleImageView.java  (351 行 / 10.3 KB)
> * This file is part of HyperCeiler.
  - public class CircleImageView extends ImageView

## core/hyperceiler-common/src/main/java/com/sevtinge/hyperceiler/common/view/CubicEaseOutInterpolater.java  (30 行 / 1.0 KB)
> * This file is part of HyperCeiler.
  - public class CubicEaseOutInterpolater implements Interpolator

## core/hyperceiler-common/src/main/java/com/sevtinge/hyperceiler/common/view/DraggableViewPager.java  (114 行 / 3.3 KB)
> * This file is part of HyperCeiler.
  - public class DraggableViewPager extends ViewPager

## core/hyperceiler-common/src/main/java/com/sevtinge/hyperceiler/common/widget/ItemView.java  (197 行 / 6.4 KB)
  - public class ItemView extends LinearLayout

## core/hyperceiler-common/src/main/java/com/sevtinge/hyperceiler/common/widget/SearchEditText.java  (40 行 / 0.9 KB)
  - public class SearchEditText extends ClearableEditText

## core/hyperceiler-common/src/main/java/com/sevtinge/hyperceiler/common/widget/SpinnerItemView.java  (105 行 / 3.5 KB)
  - public class SpinnerItemView extends ItemView

## core/hyperceiler-common/src/main/res/color/item_view_arrow_right_fill_color_dark.xml  (7 行 / 0.4 KB)


## core/hyperceiler-common/src/main/res/color/item_view_arrow_right_fill_color_light.xml  (7 行 / 0.4 KB)


## core/hyperceiler-common/src/main/res/color/item_view_summary_color_dark.xml  (5 行 / 0.3 KB)


## core/hyperceiler-common/src/main/res/color/item_view_summary_color_light.xml  (5 行 / 0.3 KB)


## core/hyperceiler-common/src/main/res/color/item_view_title_color_dark.xml  (5 行 / 0.3 KB)


## core/hyperceiler-common/src/main/res/color/item_view_title_color_light.xml  (5 行 / 0.3 KB)


## core/hyperceiler-common/src/main/res/drawable/bg_level_badge.xml  (7 行 / 0.2 KB)


## core/hyperceiler-common/src/main/res/drawable/item_view_background_dark.xml  (6 行 / 0.3 KB)


## core/hyperceiler-common/src/main/res/drawable/item_view_background_light.xml  (6 行 / 0.3 KB)


## core/hyperceiler-common/src/main/res/drawable/item_view_intent_arrow_right.xml  (12 行 / 0.7 KB)


## core/hyperceiler-common/src/main/res/layout/activity_base.xml  (13 行 / 0.4 KB)


## core/hyperceiler-common/src/main/res/layout/item_view.xml  (46 行 / 1.6 KB)


## core/hyperceiler-common/src/main/res/layout/item_view_widget_spinner.xml  (6 行 / 0.3 KB)


## core/hyperceiler-common/src/main/res/layout/search_stub.xml  (46 行 / 2.0 KB)


## core/hyperceiler-common/src/main/res/values-night/styles.xml  (4 行 / 0.1 KB)


## core/hyperceiler-common/src/main/res/values/attrs.xml  (35 行 / 1.2 KB)


## core/hyperceiler-common/src/main/res/values/colors.xml  (30 行 / 1.6 KB)


## core/hyperceiler-common/src/main/res/values/dimens.xml  (16 行 / 0.5 KB)


## core/hyperceiler-common/src/main/res/values/strings_app_language.xml  (21 行 / 1.4 KB)


## core/hyperceiler-common/src/main/res/values/styles.xml  (42 行 / 1.7 KB)


## core/imagegen/build.gradle.kts  (22 行 / 1.0 KB)
> // 引擎契约是**规范 JSON**（字段名由引擎定义，一个都不能改），所以用 kotlinx.serialization


## core/imagegen/src/main/kotlin/dev/praxis/core/imagegen/EngineAvailability.kt  (47 行 / 1.6 KB)
  - enum class ImageGenEngineSource,sealed interface EngineAvailability

## core/imagegen/src/main/kotlin/dev/praxis/core/imagegen/EngineHealth.kt  (60 行 / 2.3 KB)
> * `GET /health` 的响应模型（文档 §3.4 的就绪探测）。
  - data class EngineHealth,object EngineHealthCodec

## core/imagegen/src/main/kotlin/dev/praxis/core/imagegen/EngineLaunch.kt  (236 行 / 10.1 KB)
> * 引擎来源（文档 §1.2 的三态）。
  - enum class EngineSource,data class EngineLaunchSpec,enum class EngineLaunchError,fun EngineLaunchError.userMessage,object EngineLaunchRules

## core/imagegen/src/main/kotlin/dev/praxis/core/imagegen/EngineProtocol.kt  (117 行 / 5.1 KB)
> * 引擎 HTTP 契约的**唯一出处**（文档 `docs/PORTING-IMAGEGEN.md` §3）。
  - object EngineProtocol

## core/imagegen/src/main/kotlin/dev/praxis/core/imagegen/GenerateImageResultCodec.kt  (70 行 / 2.7 KB)
  - data class GeneratedImage,object GenerateImageResultCodec

## core/imagegen/src/main/kotlin/dev/praxis/core/imagegen/ImageDownloadPlan.kt  (197 行 / 7.2 KB)
> * 模型体积文本的解析（`approximateSize`：`"2GB"` / `"1.5GB"` / `"800MB"`）。
  - object ModelSize,data class DownloadTarget,data class ImageDownloadPlan,object ImageDownloadPlanner,sealed interface SpaceDecision,object SpaceCheck,object DownloadCleanup

## core/imagegen/src/main/kotlin/dev/praxis/core/imagegen/ImageDownloadSettings.kt  (146 行 / 6.3 KB)
> * 模型下载的设置（docs/PORTING-IMAGEGEN.md §7.2）。
  - data class ImageDownloadSettings,object ImageDownloadSettingsRules,object ImageDownloadSettingsCodec

## core/imagegen/src/main/kotlin/dev/praxis/core/imagegen/ImageDownloadState.kt  (51 行 / 1.6 KB)
> * 下载进度。
  - data class DownloadProgress,sealed interface ImageDownloadState

## core/imagegen/src/main/kotlin/dev/praxis/core/imagegen/ImageGenAvailability.kt  (125 行 / 4.9 KB)
> * 本机能不能跑本地生图（文档 §9 的「设备门控」，P4）。
  - sealed interface ImageGenAvailability,object ImageGenAvailabilityRules,object ImageGenDownscale,object ImageGenErrorClassifier

## core/imagegen/src/main/kotlin/dev/praxis/core/imagegen/ImageGenConfigRules.kt  (112 行 / 4.6 KB)
> * `set_image_gen_config` 的边界规则（文档 §5.6）。
  - object ImageGenConfigRules,data class ImageGenConfigChange,object ImageGenConfigDiff

## core/imagegen/src/main/kotlin/dev/praxis/core/imagegen/ImageGenDefaults.kt  (92 行 / 3.8 KB)
> * 生图的默认参数（文档 §7.1 的「画布」与「采样」两个分区）。
  - data class ImageGenDefaults,object ImageGenDefaultsRules,object ImageGenDefaultsCodec

## core/imagegen/src/main/kotlin/dev/praxis/core/imagegen/ImageGenError.kt  (84 行 / 4.0 KB)
> * 生图失败的分类（文档 §5.2 的失败表 + §5.1 的 invalid_size）。
  - sealed interface ImageGenError

## core/imagegen/src/main/kotlin/dev/praxis/core/imagegen/ImageGenEvent.kt  (57 行 / 2.1 KB)
> * `/generate` 流里的一条事件（文档 §3.3 的两种消息）。
  - sealed interface ImageGenEvent

## core/imagegen/src/main/kotlin/dev/praxis/core/imagegen/ImageGenParams.kt  (144 行 / 6.2 KB)
> * 一次生图的参数（文档 §5.1 的参数表 + §4.2 的默认值）。
  - data class ImageGenParams,object ImageGenParamsRules

## core/imagegen/src/main/kotlin/dev/praxis/core/imagegen/ImageGenProgressBus.kt  (60 行 / 1.9 KB)
> * 一次生图的进行中状态（文档 §6.1）。
  - data class ImageGenProgress,class ImageGenProgressBus

## core/imagegen/src/main/kotlin/dev/praxis/core/imagegen/ImageGenRequest.kt  (60 行 / 2.8 KB)
> * `POST /generate` 的请求体（文档 §3.2 的字段全表）。
  - object ImageGenRequest

## core/imagegen/src/main/kotlin/dev/praxis/core/imagegen/ImageGenTarget.kt  (30 行 / 1.1 KB)
> * 生图槽位选的是**哪儿**出图（文档 §7.3）。
  - sealed interface ImageGenTarget

## core/imagegen/src/main/kotlin/dev/praxis/core/imagegen/ImageGenTargetCodec.kt  (97 行 / 3.8 KB)
> * `imagegen_target` 的编解码（文档 §7.3 的数据形状）。
  - object ImageGenTargetCodec,object ImageGenTargetSelection

## core/imagegen/src/main/kotlin/dev/praxis/core/imagegen/ImageModelCatalog.kt  (224 行 / 8.7 KB)
> * 一个可下载的生图模型（文档 §8.1 的目录条目）。
  - data class ImageModel,object ImageModelRequirement,object ChipsetTier,object ImageModelCatalog

## core/imagegen/src/main/kotlin/dev/praxis/core/imagegen/ImageOutputSettings.kt  (70 行 / 2.7 KB)
> * 生图的输出设置（文档 §7.1 的「输出」分区）。
  - data class ImageOutputSettings,object ImageOutputSettingsCodec,sealed interface GallerySaveResult

## core/imagegen/src/main/kotlin/dev/praxis/core/imagegen/NdjsonReader.kt  (116 行 / 5.1 KB)
  - object NdjsonReader

## core/imagegen/src/main/kotlin/dev/praxis/core/imagegen/ParallelProgress.kt  (37 行 / 1.7 KB)
> * 并发下载时的进度聚合（纯函数，便于单测）。
  - object ParallelProgress

## core/imagegen/src/main/kotlin/dev/praxis/core/imagegen/QnnRuntimePlan.kt  (67 行 / 2.8 KB)
> * QNN 运行时（NPU）的解压计划（文档 §1.4 的「QNN 的正确做法」）。
  - object QnnRuntimePlan

## core/imagegen/src/main/kotlin/dev/praxis/core/imagegen/RemoteImageProtocol.kt  (109 行 / 4.0 KB)
> * OpenAI 兼容 images 端点的请求体（文档 §5.4）。
  - object RemoteImageRequest,object RemoteImageEndpoint,sealed interface RemoteImagePayload,object RemoteImageResponse

## core/imagegen/src/test/kotlin/dev/praxis/core/imagegen/EngineLaunchTest.kt  (203 行 / 7.2 KB)
  - class EngineLaunchTest

## core/imagegen/src/test/kotlin/dev/praxis/core/imagegen/GenerateImageResultCodecTest.kt  (67 行 / 2.3 KB)
> * `generate_image` 结果的解析（聊天层据此把图挂进气泡）。
  - class GenerateImageResultCodecTest

## core/imagegen/src/test/kotlin/dev/praxis/core/imagegen/ImageDownloadPlanTest.kt  (152 行 / 5.8 KB)
  - class ImageDownloadPlanTest

## core/imagegen/src/test/kotlin/dev/praxis/core/imagegen/ImageDownloadSettingsTest.kt  (108 行 / 3.8 KB)
> // ── 归一化 ──
  - class ImageDownloadSettingsTest

## core/imagegen/src/test/kotlin/dev/praxis/core/imagegen/ImageGenAvailabilityTest.kt  (119 行 / 4.5 KB)
> // ── 门控 ──
  - class ImageGenAvailabilityTest

## core/imagegen/src/test/kotlin/dev/praxis/core/imagegen/ImageGenConfigRulesTest.kt  (118 行 / 3.9 KB)
> // ── 越界键 ──
  - class ImageGenConfigRulesTest

## core/imagegen/src/test/kotlin/dev/praxis/core/imagegen/ImageGenDefaultsTest.kt  (81 行 / 2.6 KB)
  - class ImageGenDefaultsTest

## core/imagegen/src/test/kotlin/dev/praxis/core/imagegen/ImageGenParamsTest.kt  (209 行 / 7.6 KB)
> * 参数默认值与合法范围（文档 §5.1 / §4.2）。
  - class ImageGenParamsTest

## core/imagegen/src/test/kotlin/dev/praxis/core/imagegen/ImageGenRequestTest.kt  (162 行 / 6.2 KB)
  - class ImageGenRequestTest

## core/imagegen/src/test/kotlin/dev/praxis/core/imagegen/ImageGenTargetCodecTest.kt  (103 行 / 3.4 KB)
> * `imagegen_target` 的读写与「装完自动选中」（文档 §7.3）。
  - class ImageGenTargetCodecTest

## core/imagegen/src/test/kotlin/dev/praxis/core/imagegen/ImageModelCatalogTest.kt  (151 行 / 5.1 KB)
  - class ImageModelCatalogTest

## core/imagegen/src/test/kotlin/dev/praxis/core/imagegen/ImageOutputSettingsTest.kt  (55 行 / 1.8 KB)
  - class ImageOutputSettingsTest

## core/imagegen/src/test/kotlin/dev/praxis/core/imagegen/NdjsonReaderTest.kt  (217 行 / 7.6 KB)
> * NDJSON 解析与分发（文档 §3.3）。
  - class NdjsonReaderTest

## core/imagegen/src/test/kotlin/dev/praxis/core/imagegen/ParallelProgressTest.kt  (85 行 / 3.0 KB)
  - class ParallelProgressTest

## core/imagegen/src/test/kotlin/dev/praxis/core/imagegen/QnnRuntimePlanTest.kt  (62 行 / 2.2 KB)
  - class QnnRuntimePlanTest

## core/imagegen/src/test/kotlin/dev/praxis/core/imagegen/RemoteImageProtocolTest.kt  (117 行 / 3.7 KB)
> // ── 地址推导 ──
  - class RemoteImageProtocolTest

## core/linux/build.gradle.kts  (33 行 / 1.4 KB)
> // 只依赖 model（ApiProvider 式的纯数据枚举）与协程；不依赖任何 Android 类。


## core/linux/src/main/kotlin/dev/praxis/core/linux/FtpSettings.kt  (94 行 / 3.8 KB)
> * FTP 服务的设置（纯数据，可在 JVM 单测里构造）。
  - data class FtpSettings

## core/linux/src/main/kotlin/dev/praxis/core/linux/LinuxDiskUsage.kt  (303 行 / 13.5 KB)
> * Linux 环境的磁盘占用统计与清理（存储管理分区的数据源）。
  - object LinuxDiskUsage

## core/linux/src/main/kotlin/dev/praxis/core/linux/LinuxDistro.kt  (193 行 / 8.5 KB)
> * 可内置的发行版（容器根文件系统）。
  - enum class LinuxDistro,enum class DistroFamily,enum class KaliMirror

## core/linux/src/main/kotlin/dev/praxis/core/linux/LinuxEnvSettings.kt  (61 行 / 2.3 KB)
> * Linux 环境页需要**跨进程存活**的配置。
  - data class LinuxEnvSettings

## core/linux/src/main/kotlin/dev/praxis/core/linux/LinuxToolPort.kt  (83 行 / 3.4 KB)
> * 给 AI 用的 Linux 容器执行端口。
  - interface LinuxToolPort,data class LinuxStatus,data class LinuxExecResult

## core/linux/src/main/kotlin/dev/praxis/core/linux/ProotCommand.kt  (912 行 / 45.8 KB)
> * Linux 环境（proot 容器）的完整配置。
  - data class LinuxEnvironment,object ProotCommand

## core/linux/src/main/kotlin/dev/praxis/core/linux/RemoteAccess.kt  (325 行 / 14.1 KB)
> * SSH 服务器配置（容器内的 `openssh-server`）。
  - data class SshConfig,object SshdScript,data class FrpConfig,object FrpScript

## core/linux/src/main/kotlin/dev/praxis/core/linux/SpillPolicy.kt  (114 行 / 4.3 KB)
> * 长文本的溢出保存结果。
  - data class SpilledText,object SpillPolicy

## core/linux/src/main/kotlin/dev/praxis/core/linux/TerminalSession.kt  (395 行 / 15.7 KB)
> * 终端会话句柄与状态模型。
  - data class TerminalSessionInfo,data class TerminalSessionCommand,enum class TerminalControl,object TerminalControlSequence,data class TerminalChunk,interface TerminalSessionPort

## core/linux/src/main/kotlin/dev/praxis/core/linux/TerminalSessionTools.kt  (522 行 / 23.3 KB)
  - private const val PARAM_SESSION_ID,private const val PARAM_COMMAND,private const val PARAM_SESSION_NAME,private const val PARAM_CONTROL,private const val PARAM_INPUT,private const val PARAM_TIMEOUT,private const val PARAM_MAX_LINES,private const val DESC_SESSION_ID,class CreateTerminalSessionTool,class ExecuteInTerminalSessionTool,class ExecuteInTerminalSessionStreamingTool,class CloseTerminalSessionTool,class InputInTerminalSessionTool,class GetTerminalScreenTool,private fun resolveSession,private fun noSession,private fun renderSessionResult,private fun parseArgs,private fun badJson

## core/linux/src/main/kotlin/dev/praxis/core/linux/UbuntuMirror.kt  (204 行 / 7.5 KB)
> * 国内镜像源。
  - enum class UbuntuMirror,enum class UbuntuRelease,object UbuntuBaseImage

## core/linux/src/test/kotlin/dev/praxis/core/linux/AptSourcesRetireTest.kt  (137 行 / 5.1 KB)
> * ④ Ubuntu apt 源重复配置警告：换源后传统 `sources.list` 必须让位。
  - class AptSourcesRetireTest

## core/linux/src/test/kotlin/dev/praxis/core/linux/FtpSettingsTest.kt  (103 行 / 3.4 KB)
> * [FtpSettings] 的校验。
  - class FtpSettingsTest

## core/linux/src/test/kotlin/dev/praxis/core/linux/LinuxDiskUsageTest.kt  (260 行 / 9.9 KB)
> * 磁盘占用统计的单测。
  - class LinuxDiskUsageTest

## core/linux/src/test/kotlin/dev/praxis/core/linux/LinuxEnvSettingsTest.kt  (60 行 / 2.1 KB)
> * [LinuxEnvSettings] 与 [FtpSettings] 之间的转换。
  - class LinuxEnvSettingsTest

## core/linux/src/test/kotlin/dev/praxis/core/linux/ProotCommandTest.kt  (516 行 / 21.5 KB)
> * 镜像表与下载地址的护栏。
  - class UbuntuMirrorTest,class ProotCommandTest

## core/linux/src/test/kotlin/dev/praxis/core/linux/ProotLaunchArgsTest.kt  (108 行 / 4.2 KB)
> * [ProotCommand.launchArgs] 的契约。
  - class ProotLaunchArgsTest

## core/linux/src/test/kotlin/dev/praxis/core/linux/RemoteAccessTest.kt  (224 行 / 8.9 KB)
> * SSH 配置的护栏。
  - class SshConfigTest,class FrpConfigTest

## core/linux/src/test/kotlin/dev/praxis/core/linux/SpillPolicyTest.kt  (110 行 / 3.9 KB)
> * [SpillPolicy] 的判定与切分。
  - class SpillPolicyTest

## core/linux/src/test/kotlin/dev/praxis/core/linux/TerminalSessionToolsTest.kt  (371 行 / 15.5 KB)
  - class TerminalSessionToolsTest

## core/memory/build.gradle.kts  (11 行 / 0.3 KB)
> // 写入管线是 suspend 的，测试要用 runTest（结构并发、不 sleep）。


## core/memory/src/main/kotlin/dev/praxis/core/memory/MemoryContract.kt  (112 行 / 3.6 KB)
> * 一条长期记忆。
  - data class MemoryItem,data class MemoryQuery,data class MemoryHit,data class MemoryWriteResult,interface MemoryStore

## core/memory/src/main/kotlin/dev/praxis/core/memory/MemoryRetriever.kt  (202 行 / 8.5 KB)
> * 多路召回 + RRF 融合的检索器。
  - class MemoryRetriever

## core/memory/src/main/kotlin/dev/praxis/core/memory/MemorySearchConfig.kt  (345 行 / 14.9 KB)
> * 记忆检索的可调权重（对齐 Operit `MemorySearchConfig`）。
  - data class MemorySearchConfig,enum class MemoryScoreMode,data class MemoryEdge,class MemoryRanker,class MemorySearchPipeline

## core/memory/src/main/kotlin/dev/praxis/core/memory/MemoryWritePipeline.kt  (111 行 / 4.4 KB)
> * 记忆写入管线：抽取 → 去重/合并 → 落库。
  - class MemoryWritePipeline,fun jaccardSimilarity

## core/memory/src/test/kotlin/dev/praxis/core/memory/MemorySearchPipelineTest.kt  (170 行 / 6.9 KB)
> * 高级记忆检索的回归护栏（覆盖率乘子 / 图边传播 / 模式偏置）。
  - class MemorySearchPipelineTest

## core/memory/src/test/kotlin/dev/praxis/core/memory/MemoryTest.kt  (244 行 / 8.4 KB)
  - private class FakeStore,class MemoryRetrieverTest,class MemoryWritePipelineTest

## core/model/build.gradle.kts  (23 行 / 0.9 KB)
> // 角色卡的酒馆（SillyTavern）JSON 导入导出。


## core/model/src/main/kotlin/dev/praxis/core/model/MessageTimestampAllocator.kt  (80 行 / 3.1 KB)
> * 为聊天消息分配**严格单调递增**的时间戳。
  - class MessageTimestampAllocator

## core/model/src/main/kotlin/dev/praxis/core/model/character/CharacterCard.kt  (226 行 / 9.1 KB)
> * 角色卡（1:1 复刻 Operit 的 `CharacterCard`，字段语义逐条对齐）。
  - data class CharacterCard,enum class CharacterModelBindingMode,enum class CharacterMemoryBindingMode,data class CharacterToolAccessConfig,data class PromptTag,enum class PromptTagType,enum class PromptFunctionType

## core/model/src/main/kotlin/dev/praxis/core/model/character/CharacterPromptComposer.kt  (159 行 / 6.2 KB)
> * 角色卡提示词组装与工具过滤。
  - object CharacterPromptComposer,object CharacterToolFilter

## core/model/src/main/kotlin/dev/praxis/core/model/character/TavernCard.kt  (607 行 / 23.7 KB)
> * 酒馆（SillyTavern）角色卡 V2 格式。
  - data class TavernCharacterCard,data class TavernCharacterData,data class TavernExtensions,data class TavernDepthPrompt,data class TavernCharacterBook,data class TavernBookEntry,data class TavernChubExtension,data class OperitTavernExtension,data class OperitCharacterCardPayload,data class OperitAttachedTagPayload,data class OperitToolAccessPayload,data class TavernImportResult,object TavernCardImporter,object TavernCardExporter

## core/model/src/main/kotlin/dev/praxis/core/model/linux/LinuxPackageGroups.kt  (161 行 / 7.4 KB)
> * 一组可一键安装的软件包（对齐 Operit `SetupScreen` 的包分类卡片）。
  - data class LinuxPackageGroup,val BuiltInPackageGroups: List<LinuxPackageGroup>,fun buildPackageInstallScript

## core/model/src/main/kotlin/dev/praxis/core/model/linux/VirtualKeyboardLayoutCodec.kt  (98 行 / 4.4 KB)
> * 虚拟键盘布局的**可持久化形式**。
  - object VirtualKeyboardLayoutCodec

## core/model/src/main/kotlin/dev/praxis/core/model/permission/PraxisCapability.kt  (369 行 / 14.9 KB)
> * Praxis 需要的**运行时授权项**。
  - enum class PraxisCapability,enum class PraxisPermissionLevel,enum class CapabilityStatus,enum class GrantChannel,data class CapabilityState,data class CapabilitySnapshot

## core/model/src/main/kotlin/dev/praxis/core/model/settings/ApiProvider.kt  (143 行 / 5.8 KB)
> * 模型服务商预设。
  - enum class ApiProvider

## core/model/src/main/kotlin/dev/praxis/core/model/settings/FunctionModelSettings.kt  (197 行 / 8.3 KB)
> * 可单独指定模型的**功能位**（对齐 Operit 的 `FunctionType` 与它的「功能模型配置」页）。
  - enum class FunctionSlot,data class FunctionModelOverride,data class FunctionModelSettings,data class ResolvedModel

## core/model/src/main/kotlin/dev/praxis/core/model/settings/ModelAdvancedSettings.kt  (148 行 / 6.1 KB)
> * 自定义请求头的一条键值。
  - typealias CustomHeader,data class ModelAdvancedSettings

## core/model/src/main/kotlin/dev/praxis/core/model/settings/ModelProfile.kt  (107 行 / 4.1 KB)
> * 一个「模型配置」（= 一套 baseUrl + key + 模型池 + 参数）。
  - enum class ModelProfileKind,data class ModelProfile,data class ModelProfileSet

## core/model/src/main/kotlin/dev/praxis/core/model/settings/ModelSettings.kt  (187 行 / 7.7 KB)
> * 模型服务配置。
  - data class ModelSettings,fun parseModelList,fun joinModelList

## core/model/src/main/kotlin/dev/praxis/core/model/settings/TerminalSettings.kt  (74 行 / 3.1 KB)
> * 终端页自己的设置（第 12 项缺陷：终端里的「齿轮」原本跳到 Linux 环境页）。
  - data class TerminalSettings

## core/model/src/main/kotlin/dev/praxis/core/model/settings/ThemeSettings.kt  (138 行 / 4.1 KB)
> * 主题模式。System 跟随系统；显式亮/暗则强制。
  - enum class ThemeMode,enum class ColorSource,enum class PaletteStyle,enum class ColorSpecVersion,enum class PredictiveBackAnimation,enum class PredictiveBackExitDirection,data class ThemeSettings

## core/model/src/main/kotlin/dev/praxis/core/model/settings/ThinkingEffort.kt  (39 行 / 1.4 KB)
> * 思考强度。
  - enum class ThinkingEffort

## core/model/src/test/kotlin/dev/praxis/core/model/MessageTimestampAllocatorTest.kt  (75 行 / 2.5 KB)
> * 时间戳分配器（2026-11-07，R6）。
  - class MessageTimestampAllocatorTest

## core/model/src/test/kotlin/dev/praxis/core/model/character/TavernCardPasteTest.kt  (97 行 / 3.6 KB)
> * 「把整张卡粘进系统提示词框」这条路径必须能用（2026-11-07）。
  - class TavernCardPasteTest

## core/model/src/test/kotlin/dev/praxis/core/model/linux/LinuxPackageGroupsTest.kt  (119 行 / 5.1 KB)
> * 软件包组与安装脚本的单测。
  - class LinuxPackageGroupsTest

## core/model/src/test/kotlin/dev/praxis/core/model/linux/VirtualKeyboardLayoutCodecTest.kt  (163 行 / 6.1 KB)
> * 键帽标签规范化的单测。
  - class VirtualKeyboardLayoutCodecTest

## core/model/src/test/kotlin/dev/praxis/core/model/settings/ApiProviderPresetsTest.kt  (104 行 / 4.7 KB)
> * 服务商预设表的护栏。
  - class ApiProviderPresetsTest

## core/model/src/test/kotlin/dev/praxis/core/model/settings/FunctionModelSettingsTest.kt  (108 行 / 4.3 KB)
> * 功能级模型覆盖的解析语义。
  - class FunctionModelSettingsTest

## core/model/src/test/kotlin/dev/praxis/core/model/settings/ModelAdvancedSettingsTest.kt  (117 行 / 4.1 KB)
> * [ModelAdvancedSettings] 的序列化往返与边界。
  - class ModelAdvancedSettingsTest

## core/model/src/test/kotlin/dev/praxis/core/model/settings/ModelSettingsTest.kt  (142 行 / 5.6 KB)
> * 模型池语义的回归护栏（2026-10-24 引入，对齐 Operit 的 `modelName` + `modelIndex`）。
  - class ModelSettingsTest

## core/plugin/build.gradle.kts  (33 行 / 1.3 KB)
> // ## 为什么依赖 core:tool


## core/plugin/src/main/kotlin/dev/praxis/core/plugin/ComposeDslTree.kt  (222 行 / 9.0 KB)
> * compose_dsl 渲染树。
  - data class ComposeDslNode,data class ComposeDslRenderResult,object ComposeDslParser,object ComposeDslComponents

## core/plugin/src/main/kotlin/dev/praxis/core/plugin/ExtensionEntry.kt  (133 行 / 4.9 KB)
> * 四类扩展的统一视图。
  - data class ExtensionEntry,class ExtensionIcons,fun InstalledPlugin.toExtensionEntry

## core/plugin/src/main/kotlin/dev/praxis/core/plugin/ExtensionSnapshot.kt  (176 行 / 7.0 KB)
> * 扩展中心的状态快照。
  - data class ExtensionSnapshot,interface ExtensionSources,fun buildExtensionSnapshot

## core/plugin/src/main/kotlin/dev/praxis/core/plugin/InstalledPlugin.kt  (116 行 / 4.4 KB)
> * 一个已安装的插件（工具包）。
  - data class InstalledPlugin,sealed interface PluginOrigin

## core/plugin/src/main/kotlin/dev/praxis/core/plugin/LocalizedText.kt  (156 行 / 5.8 KB)
> * 多语言文本。
  - data class LocalizedText,object LocalizedTextCodec,sealed interface ManifestValue

## core/plugin/src/main/kotlin/dev/praxis/core/plugin/ManifestMapper.kt  (400 行 / 18.2 KB)
> * 把解析后的 [ManifestValue] 树映射成 [PluginManifest]。
  - object ManifestMapper,sealed interface ManifestProblem

## core/plugin/src/main/kotlin/dev/praxis/core/plugin/ManifestParser.kt  (389 行 / 15.0 KB)
> * 清单解析：HJSON + JSON 双格式，产出统一的 [ManifestValue]。
  - object ManifestParser

## core/plugin/src/main/kotlin/dev/praxis/core/plugin/MarketApi.kt  (214 行 / 8.2 KB)
> * 在线市场的条目摘要（列表页用）。
  - data class MarketEntrySummary,data class MarketEntryDetail,data class MarketVersion,data class MarketAsset,enum class MarketSort,interface MarketApi,private object DisabledMarket : MarketApi,data class MarketPage

## core/plugin/src/main/kotlin/dev/praxis/core/plugin/MarketEndpoints.kt  (265 行 / 10.8 KB)
> * 市场 URL 构造。
  - object MarketEndpoints,object MarketOriginCodec

## core/plugin/src/main/kotlin/dev/praxis/core/plugin/MarketJsonParser.kt  (230 行 / 10.0 KB)
> * 把市场返回的 JSON 解析成 [MarketEntrySummary] / [MarketEntryDetail]。
  - object MarketJsonParser

## core/plugin/src/main/kotlin/dev/praxis/core/plugin/McpAndSandbox.kt  (183 行 / 6.6 KB)
> * MCP 服务器配置。
  - data class McpServerConfig,enum class McpTransport,data class McpTool,data class McpToolParameter,fun McpServerConfig.toExtensionEntry,fun Skill.toExtensionEntry,data class SandboxPackageInfo,data class SandboxScreen,fun PluginManifest.providesUi

## core/plugin/src/main/kotlin/dev/praxis/core/plugin/PluginActivation.kt  (148 行 / 5.7 KB)
> * 装载时需要的包来源。
  - interface PluginSource,data class ActivationReport,class PluginActivation

## core/plugin/src/main/kotlin/dev/praxis/core/plugin/PluginArchive.kt  (289 行 / 12.4 KB)
> * 包内容读取端口。
  - interface PluginArchive,class InMemoryPluginArchive,data class PluginArchiveContent,object PluginArchiveReader

## core/plugin/src/main/kotlin/dev/praxis/core/plugin/PluginEntryPoint.kt  (227 行 / 8.6 KB)
> * 插件入口。
  - data class PluginEntryPoint,data class PluginActionHook,object EntrySurfaces,enum class EntryOrigin,data class InstalledEntryPoint,fun List<InstalledPlugin>.collectEntryPoints

## core/plugin/src/main/kotlin/dev/praxis/core/plugin/PluginLoadOrder.kt  (212 行 / 8.9 KB)
> * 插件加载顺序：依赖优先的拓扑排序。
  - object PluginLoadOrder

## core/plugin/src/main/kotlin/dev/praxis/core/plugin/PluginManifest.kt  (223 行 / 8.8 KB)
> * 插件清单（工具包清单）。
  - data class PluginManifest,data class PluginRequirement,data class PluginSubpackage,data class PluginResource,data class PluginWasmModule,data class SemVer,object PluginApiCompatibility

## core/plugin/src/main/kotlin/dev/praxis/core/plugin/PluginRegistrationCodec.kt  (133 行 / 5.8 KB)
> * 插件脚本注册结果的解析（纯函数，可纯 JVM 单测）。
  - object PluginRegistrationCodec

## core/plugin/src/main/kotlin/dev/praxis/core/plugin/PluginRepository.kt  (340 行 / 15.2 KB)
> * 插件安装的持久化端口。
  - interface PluginStorage,class PluginRepository,private inline fun <T : PluginArchive, R> T.use

## core/plugin/src/main/kotlin/dev/praxis/core/plugin/PluginRuntimeBootstrapScript.kt  (333 行 / 11.1 KB)
> * 注入到脚本上下文里的引导脚本（照 Operit 的注册面）。
  - object PluginRuntimeBootstrapScript

## core/plugin/src/main/kotlin/dev/praxis/core/plugin/PluginRuntimeHost.kt  (386 行 / 16.1 KB)
> * 插件声明的工具（由脚本在运行时注册）。
  - data class PluginToolDeclaration,data class PluginScriptSource,interface PluginScriptRuntime,data class PluginLoadOutcome,class PluginRuntimeHost,private class PluginBackedTool

## core/plugin/src/main/kotlin/dev/praxis/core/plugin/ScriptMetadata.kt  (256 行 / 10.1 KB)
> * 子包脚本里的 METADATA 块（工具的唯一来源）。
  - data class ScriptMetadata,data class ScriptTool,data class ScriptToolParameter,object ScriptMetadataParser

## core/plugin/src/main/kotlin/dev/praxis/core/plugin/Skill.kt  (82 行 / 2.9 KB)
> * 一个 Skill（技能包）。
  - data class Skill,data class SkillScanResult,data class SkillSkip

## core/plugin/src/main/kotlin/dev/praxis/core/plugin/SkillParser.kt  (157 行 / 5.6 KB)
> * SKILL.md 解析器。
  - object SkillParser

## core/plugin/src/main/kotlin/dev/praxis/core/plugin/SkillRegistry.kt  (129 行 / 4.7 KB)
> * 技能文件读取端口。
  - interface SkillSource,data class SkillFile,class SkillRegistry

## core/plugin/src/test/kotlin/dev/praxis/core/plugin/ComposeDslParserTest.kt  (171 行 / 5.8 KB)
> * compose_dsl 渲染树解析。
  - class ComposeDslParserTest

## core/plugin/src/test/kotlin/dev/praxis/core/plugin/InMemoryPluginStorage.kt  (130 行 / 5.0 KB)
> * 内存版 [PluginStorage]：单测用。
  - class InMemoryPluginStorage

## core/plugin/src/test/kotlin/dev/praxis/core/plugin/ManifestParserTest.kt  (314 行 / 10.0 KB)
> * HJSON / 清单解析与映射的护栏。
  - class ManifestParserTest

## core/plugin/src/test/kotlin/dev/praxis/core/plugin/MarketAndSnapshotTest.kt  (448 行 / 15.1 KB)
> * 市场 JSON 解析的容错护栏。
  - class MarketJsonParserTest,class ExtensionSnapshotTest,class PluginArchivePathTest

## core/plugin/src/test/kotlin/dev/praxis/core/plugin/PluginActivationTest.kt  (188 行 / 6.6 KB)
> * 启动装载编排。
  - class PluginActivationTest

## core/plugin/src/test/kotlin/dev/praxis/core/plugin/PluginEntryPointTest.kt  (446 行 / 15.8 KB)
> * 插件入口（对齐 Operit 的运行时注册载荷）。
  - class PluginEntryPointTest

## core/plugin/src/test/kotlin/dev/praxis/core/plugin/PluginLoadOrderTest.kt  (277 行 / 9.3 KB)
> * 加载顺序（拓扑排序）与依赖校验。
  - class PluginLoadOrderTest,class MarketEndpointsTest,class MarketOriginCodecTest

## core/plugin/src/test/kotlin/dev/praxis/core/plugin/PluginRegistrationCodecTest.kt  (149 行 / 5.1 KB)
> * 脚本注册结果的解析。
  - class PluginRegistrationCodecTest

## core/plugin/src/test/kotlin/dev/praxis/core/plugin/PluginRepositoryTest.kt  (283 行 / 11.1 KB)
  - class PluginRepositoryTest

## core/plugin/src/test/kotlin/dev/praxis/core/plugin/PluginRuntimeHostTest.kt  (448 行 / 17.0 KB)
  - class PluginRuntimeHostTest

## core/plugin/src/test/kotlin/dev/praxis/core/plugin/ScriptMetadataParserTest.kt  (154 行 / 5.6 KB)
> * 子包 METADATA 的解析与工具声明生成。
  - class ScriptMetadataParserTest

## core/plugin/src/test/kotlin/dev/praxis/core/plugin/SkillTest.kt  (294 行 / 9.6 KB)
  - private const val REAL_SKILL,class SkillParserTest,class SkillRegistryTest

## core/terminal/build.gradle.kts  (13 行 / 0.4 KB)
> // R14：测试里用了 runTest / TestDispatcher。本模块的测试从未在 CI 上编译过


## core/terminal/src/main/kotlin/dev/praxis/core/terminal/AnsiScanner.kt  (256 行 / 8.7 KB)
> * ANSI 转义序列扫出来的一个事件。
  - sealed interface AnsiEvent,class AnsiScanner

## core/terminal/src/main/kotlin/dev/praxis/core/terminal/TerminalBuffer.kt  (450 行 / 18.1 KB)
> * 一个格子的样式。
  - data class CellStyle,data class Cell,class TerminalBuffer

## core/terminal/src/main/kotlin/dev/praxis/core/terminal/TerminalSession.kt  (218 行 / 8.2 KB)
> * 终端会话：把 ANSI 事件逐个应用到 [TerminalBuffer]，并提供「喂字节、取屏幕」两个入口。
  - class TerminalSession

## core/terminal/src/test/kotlin/dev/praxis/core/terminal/TerminalBufferReuseTest.kt  (195 行 / 7.6 KB)
> * 「屏幕被写到多深」与「行版本号」的行为锁。
  - class TerminalBufferReuseTest

## core/terminal/src/test/kotlin/dev/praxis/core/terminal/TerminalTest.kt  (328 行 / 10.1 KB)
> * 终端解析的行为锁。
  - class AnsiScannerTest,class TerminalBufferTest,class TerminalSessionTest

## core/tool/build.gradle.kts  (13 行 / 0.3 KB)


## core/tool/src/main/kotlin/dev/praxis/core/tool/ToolArguments.kt  (77 行 / 2.9 KB)
> * 工具参数解析。
  - class ToolArguments,class ToolArgumentException

## core/tool/src/main/kotlin/dev/praxis/core/tool/ToolContract.kt  (110 行 / 3.4 KB)
> * 工具层契约。
  - enum class PermissionTier,enum class PermissionDecision,enum class ToolRisk,data class ToolSpec,data class ToolResult,interface ToolContext,interface Tool,class ToolException

## core/tool/src/main/kotlin/dev/praxis/core/tool/ToolExecutor.kt  (202 行 / 8.0 KB)
> * 一次工具调用的请求（对应 core:ai 的 ToolCall）。
  - data class ToolInvocation,data class ToolExecution,sealed interface ToolPresentState,class ToolExecutor

## core/tool/src/main/kotlin/dev/praxis/core/tool/ToolFailureStage.kt  (79 行 / 3.3 KB)
> * 工具调用失败的位置。
  - enum class ToolFailureStage

## core/tool/src/main/kotlin/dev/praxis/core/tool/ToolPermissionState.kt  (72 行 / 2.6 KB)
> * 工具权限的**运行时可改**状态。
  - class ToolPermissionState,data class ToolPermissionSnapshot,fun ToolRegistry.descriptorsFor

## core/tool/src/main/kotlin/dev/praxis/core/tool/ToolRegistry.kt  (146 行 / 5.5 KB)
> * 工具注册表。
  - class ToolRegistry,fun PermissionTier.allows,object DefaultPermissionPolicy

## core/tool/src/main/kotlin/dev/praxis/core/tool/builtin/AskUserQuestionTool.kt  (109 行 / 4.6 KB)
> * 用户交互端口（由 :app 实现，接管真正弹给用户的问题）。
  - interface AskUserPort,class AskUserQuestionTool

## core/tool/src/main/kotlin/dev/praxis/core/tool/builtin/FileTools.kt  (490 行 / 19.8 KB)
  - class FileTools,internal fun resolveInRoot,private class ListDirTool,private class ReadFileTool,private class WriteFileTool,private class DeleteFileTool,private class FileExistsTool,private class FileInfoTool,private class MoveFileTool,private class CopyFileTool

## core/tool/src/main/kotlin/dev/praxis/core/tool/custom/CustomTool.kt  (82 行 / 3.2 KB)
> * 把一条 [CustomToolDefinition] 包装成可注册的 [Tool]。
  - class CustomTool

## core/tool/src/main/kotlin/dev/praxis/core/tool/custom/CustomToolDefinition.kt  (204 行 / 7.5 KB)
> * 用户/AI 自定义工具的声明式定义。
  - data class CustomToolDefinition,data class CustomToolParam,data class CustomToolStep,object ToolPrimitives,data class DefinitionIssue,object CustomToolValidator

## core/tool/src/main/kotlin/dev/praxis/core/tool/custom/CustomToolRunner.kt  (172 行 / 6.8 KB)
> * 执行一条自定义工具定义。
  - class CustomToolRunner,data class RunOutcome

## core/tool/src/test/kotlin/dev/praxis/core/tool/ToolFailureStageTest.kt  (165 行 / 6.4 KB)
> * 五类失败位置的归因。
  - class ToolFailureStageTest

## core/tool/src/test/kotlin/dev/praxis/core/tool/ToolLayerTest.kt  (217 行 / 7.5 KB)
> * 权限策略 + 注册表 + 执行器的行为锁。
  - class ToolLayerTest

## core/tool/src/test/kotlin/dev/praxis/core/tool/ToolPermissionStateTest.kt  (113 行 / 3.5 KB)
> * 权限状态的行为锁。
  - class ToolPermissionStateTest

## core/tool/src/test/kotlin/dev/praxis/core/tool/builtin/FileToolsTest.kt  (81 行 / 2.4 KB)
  - class FileToolsTest

## core/tool/src/test/kotlin/dev/praxis/core/tool/custom/CustomToolValidatorTest.kt  (139 行 / 4.4 KB)
> * 自定义工具定义的校验。
  - class CustomToolValidatorTest

## core/workflow/build.gradle.kts  (11 行 / 0.3 KB)
> // 引擎 run() 是 suspend 的，测试要用 runTest（不用 Thread.sleep）。


## core/workflow/src/main/kotlin/dev/praxis/core/workflow/WorkflowContract.kt  (135 行 / 4.5 KB)
> * 工作流节点。
  - sealed interface WorkflowNode,data class WorkflowDefinition,data class StepRecord,data class WorkflowRunResult,interface WorkflowToolPort

## core/workflow/src/main/kotlin/dev/praxis/core/workflow/WorkflowEngine.kt  (237 行 / 10.2 KB)
> * 工作流执行引擎。
  - class WorkflowEngine

## core/workflow/src/main/kotlin/dev/praxis/core/workflow/WorkflowExpression.kt  (148 行 / 5.4 KB)
> * 受限条件表达式。
  - object ConditionExpression,fun renderTemplate

## core/workflow/src/test/kotlin/dev/praxis/core/workflow/WorkflowTest.kt  (378 行 / 13.4 KB)
  - private class FakeToolPort,class ConditionExpressionTest,class RenderTemplateTest,class WorkflowEngineTest

## docs/ACCEPTANCE-CHECKLIST.md  (227 行 / 6.0 KB)
> # 验收清单（24 条，设备恢复后逐条执行）


## docs/DEVICE-FINDINGS.md  (370 行 / 14.7 KB)
> # 真机实测结论（Redmi 25053RT47C / Android 17 / arm64 / 有 root）
  - val result

## docs/EMULATOR-LIMITS.md  (52 行 / 1.5 KB)
> # 模拟器能测什么、不能测什么（2026-11-03 实证）


## docs/GATEWAY-PORTING-STATUS.md  (330 行 / 21.1 KB)
> # 网关移植 · 当前状态与偏离记录


## docs/HANDOFF.md  (183 行 / 5.9 KB)
> # Praxis 改造任务 · 交接说明


## docs/HANDOVER.md  (461 行 / 19.9 KB)
> # Praxis 交接文档（接手方必读）


## docs/P1-FALLBACK-DESIGN.md  (113 行 / 4.3 KB)
> # P1 退路方案设计（仅设计，未实现）
  - val fullText

## docs/P1-PERF-SCRATCH.md  (54 行 / 2.3 KB)
> ## 附：两个看似能优化、实则不能的做法（2026-11-03 分析后否决）


## docs/PLAN-next.md  (502 行 / 22.1 KB)
> # Praxis 本轮改造计划（调查结论 + 实施方案）


## docs/PLAN-terminal-copy.md  (197 行 / 8.4 KB)
> # 终端复制粘贴改造方案（M2）


## docs/PLUGIN-COMPOSE-DSL.md  (147 行 / 7.0 KB)
> # 插件 UI 渲染（compose_dsl）可行性评估


## docs/PORTING-GATEWAY.md  (1711 行 / 94.8 KB)
> # Agent2API 网关 → Praxis 完整移植方案
  - data class CheckinSchedule,data class AccountPlan,data class PlannedAccount,data class CheckinRecordEntity,interface CheckinProvider,fun chat,data class GatewayAccountEntity,data class GatewayProviderEntity,data class GatewayRequestEntity,data class GatewayDailyEntity,data class ModelEntry,class RouteState,data object Gateway : SettingsEntry,data class LoginMethod

## docs/PORTING-IMAGEGEN.md  (1241 行 / 67.9 KB)
> # Local Dream 生图内置到 Praxis —— 完整方案
  - enum class EngineSource,val engine,val command,data class ImageGenParams,data class ImageGenEntity,object GenerateImageTool : Tool,suspend fun execute,@Serializable data object SettingsImageGen : PraxisRoute,@Serializable data object SettingsImageGenModels : PraxisRoute,@Serializable data class SettingsImageGenModelDetail,@Serializable data object SettingsImageGenProvider : PraxisRoute      // 右上角齿轮,sealed interface ImageGenTarget,data class ImageModel,val suffix,val url

## docs/PREVIEW.md  (149 行 / 4.7 KB)
> # 浏览器实时预览 UI
  - fun my_screen

## docs/SETTLEMENT.md  (911 行 / 36.4 KB)
> # Praxis 改造结算报告
  - val releaseKeystore,val storePass,val keyPass

## docs/TERMINAL-PROOT.md  (92 行 / 3.9 KB)
> # 终端与 proot 的三个问题（2026-11-03，用户真机反馈）
  - val args

## docs/THIRD_PARTY.md  (73 行 / 3.5 KB)
> # 第三方组件与协议


## docs/VERIFICATION.md  (284 行 / 12.3 KB)
> # 八项修复的验证状态


## docs/final-audit.md  (407 行 / 25.2 KB)
> # Praxis 最终审计（R37 更新）


## docs/memories/PRAXIS-only.md  (10457 行 / 788.2 KB)
> # Praxis / operit-next 专项记忆（从 Operit 库自动抽取）
  - val beforeTimestamp,val afterTimestamp,val replayText: String?,val active,val origin,fun CircularProgressIndicator,val DefaultCircularProgressIndicatorSize,fun rememberKeyColor,data class ImeLift,@Composable fun rememberImeLift,fun Modifier.imeLiftPadding,val imeHeight,val effectiveScrollLimit,val finalMessageContent,val preservedContent

## docs/memories/README.md  (79 行 / 3.3 KB)
> # Praxis 记忆库


## docs/memories/by-topic/01-小猿口算逆向.md  (7318 行 / 401.5 KB)
> # 01-小猿口算逆向
  - val app,val apkPath,var n,var r,var d,val hostApp,suspend fun logout,private var syncedYfdU: Long,val want,val needPush,fun relinkAsync

## docs/memories/by-topic/02-pk-node.md  (9840 行 / 600.7 KB)
> # 02-pk-node
  - data class ImeLift,@Composable fun rememberImeLift,fun Modifier.imeLiftPadding,var g,var v,var y,val imeHeight,val effectiveScrollLimit,val LocalBottomBarInset,var m

## docs/memories/by-topic/03-pk-h5私服.md  (2005 行 / 112.2 KB)
> # 03-pk-h5私服
  - var d,var j,var T,var W,var u,var l

## docs/memories/by-topic/04-cn.apixiaoyuan.app.md  (1564 行 / 85.9 KB)
> # 04-cn.apixiaoyuan.app
  - @Composable fun NavDisplay,inline fun <reified T : NavKey> NavEntryBuilder.entry,typealias NavBackStack,@Composable inline fun <reified T : NavKey> rememberNavBackStack,data class NavDisplayEffects,enum class NavSwipeDirection,fun navGraphicsTransition,val MiuixDefault: NavTransition,val backStack,val navigator,val pagerState,val maxSupported: FloatingBottomBarMode   // API>,fun clampToDevice,fun rememberKeyColor,val preservedContent,fun rememberMiuixBlurBackdrop,val floatingBackdrop,val miuixBackdrop,val floatingBottomBarMode

## docs/memories/by-topic/05-suchat.md  (2160 行 / 140.2 KB)
> # 05-suchat
  - val tilt,val baseHighlight,val pillHighlight,val dx,val dy,val dz,val len,override fun ContentDrawScope.draw,internal inline fun DrawScope.recordLayer,val offset,val trackBackdrop,fun <T> FloatingBottomBar,enum class FloatingBottomBarMode,val tabsBackdrop,val combinedBackdrop

## docs/memories/by-topic/06-HyperOS内核.md  (1191 行 / 98.6 KB)
> # 06-HyperOS内核
  - val beforeTimestamp,val afterTimestamp,val finalMessageContent

## docs/memories/by-topic/07-Operit与Praxis.md  (7912 行 / 552.9 KB)
> # 07-Operit与Praxis
  - val replayText: String?,val active,val origin,fun CircularProgressIndicator,val DefaultCircularProgressIndicatorSize,var normalized,var screen

## docs/memories/by-topic/08-工具与纪律.md  (1134 行 / 73.0 KB)
> # 08-工具与纪律
  - interface PromptTurn,sealed interface Route : NavKey

## docs/memories/by-topic/09-LocalHub与运维.md  (1500 行 / 84.9 KB)
> # 09-LocalHub与运维
  - interface RevisableTextStream : Stream<String>,data class TextStreamEvent,enum class TextStreamEventType

## docs/memories/by-topic/99-其它.md  (3356 行 / 165.3 KB)
> # 99-其它
  - val scrollProgress by remember,val dismissState,val dismissSheet,@Composable private fun SettingsFloatingBottomBar,@Composable fun SettingsCompactLayout,@Composable fun SettingsWideScreenLayout,@Composable private fun SettingsWideContent,val contentWindowInsets,val startInsets

## docs/phase0-baseline.md  (129 行 / 7.3 KB)
> # Praxis S0 基线硬化记录


## docs/release-workflow-draft.yml  (150 行 / 5.5 KB)
> # ⚠️ 这不是生效的 workflow —— 目录是 docs/，不是 .github/workflows/


## feature/chat/build.gradle.kts  (83 行 / 4.1 KB)


## feature/chat/src/main/AndroidManifest.xml  (7 行 / 0.4 KB)


## feature/chat/src/main/java/dev/praxis/feature/chat/AttachmentPanel.kt  (168 行 / 6.8 KB)
  - private data class AttachmentCell,fun AttachmentPanel

## feature/chat/src/main/java/dev/praxis/feature/chat/ChatContextUi.kt  (177 行 / 7.2 KB)
  - internal fun ContextUsageBar,private fun Modifier.clickableRow

## feature/chat/src/main/java/dev/praxis/feature/chat/ChatCopyText.kt  (110 行 / 4.5 KB)
> * Markdown → 纯文本（对齐 Operit `markdownToPlainTextForCopy`）。
  - object ChatCopyText

## feature/chat/src/main/java/dev/praxis/feature/chat/ChatListScreen.kt  (16 行 / 0.4 KB)
> * 是否置顶。
  - data class SessionItem

## feature/chat/src/main/java/dev/praxis/feature/chat/ChatListUi.kt  (191 行 / 8.6 KB)
  - fun ChatListScreen,private fun formatTime

## feature/chat/src/main/java/dev/praxis/feature/chat/ChatMarkupRegex.kt  (239 行 / 9.9 KB)
> * 聊天消息里的结构化标记与供应商协议元数据（1:1 移植 Operit `ChatMarkupRegex`）。
  - object ChatMarkupRegex

## feature/chat/src/main/java/dev/praxis/feature/chat/ChatPersistence.kt  (330 行 / 13.9 KB)
> * 聊天持久化端口。
  - interface ChatPersistence,data class PersistedMessage

## feature/chat/src/main/java/dev/praxis/feature/chat/ChatScreen.kt  (2523 行 / 133.1 KB)
  - internal data class LocatorEntry,fun ChatScreen,private fun ChatInputBar,private fun hasEarlierAssistantInTurn,private fun replyPreviewText,private fun SendAction,internal enum class SendActionMode,internal fun sendActionMode,private fun AttachmentStrip,private fun AttachmentThumbnail,private fun VariantSwitcher,private fun VariantArrow

## feature/chat/src/main/java/dev/praxis/feature/chat/ChatSettingsBar.kt  (1336 行 / 67.5 KB)
  - internal fun ChatSettingsBar,data class ModelGroup,fun ModelPill,private fun FoldSection,private fun SwitchRow,private fun ActionRow,private fun ChoiceRow,private fun ThinkingEffort.chipLabel,private fun ThinkingEffort.menuLabel,private fun PermissionTier.label,private fun PermissionTier.rowLabel

## feature/chat/src/main/java/dev/praxis/feature/chat/ChatState.kt  (1014 行 / 44.3 KB)
> * 消息在 UI 上的角色（与 core:ai 的 ChatRole 分开：UI 多一个 Error 态）。
  - enum class BubbleRole,data class ChatBubble,data class ToolCallDraft,data class ToolCallInfo,enum class ChatStage,val ChatStage.label: String,data class ChatState,sealed interface ChatEvent,data class ContextUsage,enum class MessageAction,fun cleanMessageContentForCopy,fun cleanMessageContentForXmlCopy,data class ChatCopyContent

## feature/chat/src/main/java/dev/praxis/feature/chat/ChatTitlePort.kt  (33 行 / 1.3 KB)
> * 会话标题生成端口。
  - fun interface ChatTitleGenerator

## feature/chat/src/main/java/dev/praxis/feature/chat/ChatViewModel.kt  (5025 行 / 252.7 KB)
  - class ChatViewModel,private fun AiError.displayText,data class ContextConfig,private const val CONTINUE_HINT: String,private const val MAX_ATTACHMENTS,private val PLACEHOLDER_TITLES: Set<String>

## feature/chat/src/main/java/dev/praxis/feature/chat/ChatWindowPolicy.kt  (112 行 / 4.9 KB)
> * 长对话的渲染预算。
  - internal object ChatRenderBudget,internal object MarkdownParseCachePolicy

## feature/chat/src/main/java/dev/praxis/feature/chat/ImageGalleryPort.kt  (29 行 / 1.1 KB)
> * 把一张图存进系统相册的端口（生图文档 P2 的「长按保存到相册」）。
  - fun interface ImageGalleryPort

## feature/chat/src/main/java/dev/praxis/feature/chat/MemoryPort.kt  (70 行 / 2.7 KB)
> * 长期记忆对聊天层的单向端口。
  - interface MemoryPort

## feature/chat/src/main/java/dev/praxis/feature/chat/PendingQueue.kt  (229 行 / 9.2 KB)
> * 待发送队列。
  - data class PendingQueue,enum class QueueItemKind,data class QueuedMessage,object PendingQueueOps

## feature/chat/src/main/java/dev/praxis/feature/chat/PendingQueueCodec.kt  (137 行 / 5.5 KB)
> * 待发队列的持久化编解码。
  - object PendingQueueCodec

## feature/chat/src/main/java/dev/praxis/feature/chat/PendingQueueStore.kt  (26 行 / 1.0 KB)
> * 待发队列的持久化端口。
  - interface PendingQueueStore

## feature/chat/src/main/java/dev/praxis/feature/chat/SessionTitle.kt  (150 行 / 7.1 KB)
> * AI 总结会话标题（1:1 移植 Operit `ConversationService.generateConversationTitle`
  - object SessionTitle

## feature/chat/src/main/java/dev/praxis/feature/chat/SkillView.kt  (27 行 / 0.9 KB)
> * 技能系统在聊天层的**最小视图**。
  - data class SkillView

## feature/chat/src/main/java/dev/praxis/feature/chat/ThrottledContentWriter.kt  (97 行 / 3.6 KB)
> * 流式正文写入节流器。
  - class ThrottledContentWriter

## feature/chat/src/main/java/dev/praxis/feature/chat/command/BuiltinCommands.kt  (60 行 / 1.9 KB)
> * Praxis 的内置命令集。
  - object BuiltinCommands

## feature/chat/src/main/java/dev/praxis/feature/chat/command/SlashCommand.kt  (131 行 / 5.4 KB)
> * 解析后的 `"/命令 参数"`。
  - data class ParsedCommand,object SlashCommand,data class CommandDefinition

## feature/chat/src/main/java/dev/praxis/feature/chat/command/SlashCommandPanel.kt  (146 行 / 5.4 KB)
  - fun SlashCommandPanel,private fun CommandRow

## feature/chat/src/main/java/dev/praxis/feature/chat/editor/MessageEditor.kt  (1079 行 / 46.0 KB)
  - data class ParsedMessagePart,enum class PartType,private data class XmlTagSuggestion,private val XmlTagSuggestions,fun parseMessageContentForEditor,fun recomposeMessageFromParts,fun MessageEditor,private fun EditorHeader,private fun RawEditPane,private fun VisualEditPane,private fun XmlTagItem,private fun ActionIconButton,private fun EditorActions,private fun TagEditorDialog

## feature/chat/src/main/java/dev/praxis/feature/chat/menu/MessageContextMenu.kt  (564 行 / 22.8 KB)
  - fun MessageContextMenu,private fun MessageMenuItem,private val MessageAction.icon: ImageVector,fun actionsForBubble,private fun roleActions,enum class MessageCopyMode,fun MessageCopyPreviewSheet,fun MessageDeleteConfirmDialog,fun MessageInfoDialog,private fun InfoRow,private fun roleLabel,private fun formatTimestamp

## feature/chat/src/main/java/dev/praxis/feature/chat/navigator/ChatMessageLocator.kt  (277 行 / 10.3 KB)
> * 定位器里的一条（1:1 对齐 Operit `ChatMessageLocatorPreview`）。
  - data class ChatMessageLocatorPreview,fun BubbleRole.locatorSender,fun ChatBubble.toLocatorPreview,fun buildTurnEntries,data class TurnEntry,fun normalizeMessageSearchText,fun messageBarFraction,fun buildMessagePreview,fun resolveCenteredMessageIndex,data class VisibleItem

## feature/chat/src/main/java/dev/praxis/feature/chat/navigator/ChatScrollNavigator.kt  (605 行 / 26.3 KB)
  - private const val NAVIGATOR_HIDE_DELAY_MS,private const val LOCATOR_SEARCH_DEBOUNCE_MS,private data class NavigatorSnapshot,fun ChatScrollNavigator,private fun resolveCenteredFromListState,private fun ChatMessageLocatorDialog,private fun ChatMessageLocatorRow,private fun senderLabel

## feature/chat/src/main/java/dev/praxis/feature/chat/queue/PendingQueuePanel.kt  (619 行 / 27.6 KB)
  - fun PendingQueuePanel,private fun QueueRow,private fun QueueIconAction,internal fun rememberDragReorderState,internal class DragReorderState

## feature/chat/src/main/java/dev/praxis/feature/chat/widget/ImageThumbnail.kt  (71 行 / 2.9 KB)
> * 把磁盘上的原图缩成消息里用的缩略图（docs/PORTING-IMAGEGEN.md §6.3）。
  - object ImageThumbnail

## feature/chat/src/main/java/dev/praxis/feature/chat/widget/MessageBubble.kt  (736 行 / 32.5 KB)
  - enum class BubbleKind,fun ChatMessageItem,fun MessageBubble,private fun ReasoningBlock,private fun formatReasoningSize,private fun CursorMessage,private fun MessageLabel,private fun RetryRow,private fun MessageImages

## feature/chat/src/main/java/dev/praxis/feature/chat/widget/ModelConfigCard.kt  (140 行 / 5.4 KB)
  - fun ModelConfigCard,private fun FactLine

## feature/chat/src/main/java/dev/praxis/feature/chat/widget/SummaryDivider.kt  (303 行 / 13.4 KB)
  - fun SummaryDivider,fun SummaryDetailDialog

## feature/chat/src/main/java/dev/praxis/feature/chat/widget/TokenUsageBadge.kt  (91 行 / 3.1 KB)
  - fun TokenUsageBadge,private fun BadgeText,internal fun formatTokens

## feature/chat/src/main/java/dev/praxis/feature/chat/widget/ToolCallCard.kt  (345 行 / 15.3 KB)
  - fun ToolCallCard,private fun SectionLabel,private fun CodeBlock,private fun formatElapsed,private fun formatChars

## feature/chat/src/test/java/dev/praxis/feature/chat/ChatContinueTest.kt  (143 行 / 5.3 KB)
> * 「继续生成」的状态契约测试。
  - class ChatContinueTest

## feature/chat/src/test/java/dev/praxis/feature/chat/ChatStageTest.kt  (323 行 / 12.7 KB)
  - class ChatStageTest

## feature/chat/src/test/java/dev/praxis/feature/chat/ChatStateCanSendTest.kt  (71 行 / 2.1 KB)
> * 生成中的发送按钮语义（2026-11-07 用户反馈）。
  - class ChatStateCanSendTest

## feature/chat/src/test/java/dev/praxis/feature/chat/ChatStreamingTextTest.kt  (116 行 / 4.4 KB)
> * 流式文本槽（`ChatState.streamingText`）的语义测试。
  - class ChatStreamingTextTest

## feature/chat/src/test/java/dev/praxis/feature/chat/ChatTitleTriggerTest.kt  (321 行 / 12.1 KB)
  - class ChatTitleTriggerTest

## feature/chat/src/test/java/dev/praxis/feature/chat/ChatWiringGuardTest.kt  (834 行 / 40.8 KB)
> * 接线护栏：守住「参数声明了但调用点没传」这类静默失效。
  - class ChatWiringGuardTest

## feature/chat/src/test/java/dev/praxis/feature/chat/MessageContextMenuTest.kt  (292 行 / 12.0 KB)
> * 长按菜单移植的纯 JVM 回归面。
  - class MessageContextMenuTest

## feature/chat/src/test/java/dev/praxis/feature/chat/MessageDeletePersistenceTest.kt  (265 行 / 9.3 KB)
  - class MessageDeletePersistenceTest

## feature/chat/src/test/java/dev/praxis/feature/chat/MessageEditorPartsTest.kt  (112 行 / 4.3 KB)
> * `MessageEditor` 的片段双向转换（纯函数，不需 Android 环境）。
  - class MessageEditorPartsTest

## feature/chat/src/test/java/dev/praxis/feature/chat/PendingQueueCodecTest.kt  (101 行 / 3.9 KB)
> * [PendingQueueCodec] 的往返契约。
  - class PendingQueueCodecTest

## feature/chat/src/test/java/dev/praxis/feature/chat/PendingQueueTest.kt  (174 行 / 6.1 KB)
> * 待发队列的顺序调整。
  - class PendingQueueTest

## feature/chat/src/test/java/dev/praxis/feature/chat/ReasoningPersistenceTest.kt  (253 行 / 8.7 KB)
  - class ReasoningPersistenceTest

## feature/chat/src/test/java/dev/praxis/feature/chat/SendActionModeTest.kt  (82 行 / 2.6 KB)
> * 发送按钮四态判定。
  - class SendActionModeTest

## feature/chat/src/test/java/dev/praxis/feature/chat/SessionTitleTest.kt  (102 行 / 3.8 KB)
> * `SessionTitle` 的清洗与兜底逻辑（纯函数，不需 Android 环境）。
  - class SessionTitleTest

## feature/chat/src/test/java/dev/praxis/feature/chat/SlashQueryTest.kt  (55 行 / 1.4 KB)
> * `slashQuery`（命令候选的过滤词）的取值规则。
  - class SlashQueryTest

## feature/chat/src/test/java/dev/praxis/feature/chat/ThrottledContentWriterTest.kt  (184 行 / 5.6 KB)
> * 节流器的行为锁。
  - class ThrottledContentWriterTest

## feature/chat/src/test/java/dev/praxis/feature/chat/ToolCardExecutingTest.kt  (288 行 / 11.3 KB)
  - class ToolCardExecutingTest

## feature/chat/src/test/java/dev/praxis/feature/chat/command/SlashCommandTest.kt  (123 行 / 3.5 KB)
> * `/` 命令解析的边界。
  - class SlashCommandTest

## feature/chat/src/test/java/dev/praxis/feature/chat/navigator/BuildTurnEntriesTest.kt  (118 行 / 4.2 KB)
> * 定位器「按轮聚合」的回归测试。
  - class BuildTurnEntriesTest

## feature/gateway/build.gradle.kts  (27 行 / 0.8 KB)
> // 与其它 feature 模块同向：只依赖 ui 层与 core 的纯契约。


## feature/gateway/src/main/java/dev/praxis/feature/gateway/GatewayAccountsScreen.kt  (413 行 / 17.8 KB)
  - fun GatewayAccountsScreen,private fun StatusBadge,private fun AddAccountDialog,private fun describe

## feature/gateway/src/main/java/dev/praxis/feature/gateway/GatewayAddAccountScreens.kt  (393 行 / 15.6 KB)
  - data class GatewayProviderChoice,fun GatewayAddProviderScreen,fun GatewayAddLoginScreen,private fun ApiKeyAccountDialog,private fun WebAuthDialog,private fun AddField

## feature/gateway/src/main/java/dev/praxis/feature/gateway/GatewayImportScreen.kt  (293 行 / 11.3 KB)
  - fun GatewayImportScreen,internal fun formatReport

## feature/gateway/src/main/java/dev/praxis/feature/gateway/GatewayModelsScreen.kt  (307 行 / 13.2 KB)
  - data class GatewayModelTestReport,fun GatewayModelsScreen,private fun AddModelDialog

## feature/gateway/src/main/java/dev/praxis/feature/gateway/GatewayProvidersScreen.kt  (421 行 / 18.9 KB)
  - data class GatewayProviderRowUi,data class GatewayProviderModelsFetch,data class GatewayBuiltinProviderRowUi,fun GatewayProvidersScreen,private fun FinalCard,private fun AddProviderDialog,private fun ProviderField

## feature/gateway/src/main/java/dev/praxis/feature/gateway/GatewayProxyScreen.kt  (303 行 / 12.0 KB)
  - fun GatewayProxyScreen,private fun AddProxyNodeDialog,private fun BindProxyDialog,private fun ProxyField

## feature/gateway/src/main/java/dev/praxis/feature/gateway/GatewayReportScreen.kt  (226 行 / 9.2 KB)
  - data class GatewayReportWindowUi,fun GatewayReportScreen,private fun RetentionDialog

## feature/gateway/src/main/java/dev/praxis/feature/gateway/GatewayRoute.kt  (320 行 / 12.1 KB)
  - fun GatewayRoute,private val PLAN_LINES

## feature/gateway/src/main/java/dev/praxis/feature/gateway/GatewayRoutingScreen.kt  (697 行 / 30.6 KB)
  - fun GatewayRoutingScreen,private fun FaultParamsDialog,private fun budgetText,private fun BudgetDialog,private fun replayTimeText,private fun AddRouteGroupDialog

## feature/gateway/src/main/java/dev/praxis/feature/gateway/GatewaySystemScreen.kt  (344 行 / 16.3 KB)
  - fun GatewaySystemScreen

## feature/gateway/src/main/java/dev/praxis/feature/gateway/GatewayWidgets.kt  (30 行 / 0.9 KB)
> * 统一的卡片外壳：水平留白与其它设置页一致，内容统一 16dp 内边距。
  - internal fun GatewayCard

## feature/settings/build.gradle.kts  (80 行 / 4.0 KB)
> // 账号行模型（GatewayAccountRow）在核心层；网关页的行数据要经过这里转发。


## feature/settings/src/main/AndroidManifest.xml  (7 行 / 0.4 KB)


## feature/settings/src/main/java/dev/praxis/feature/settings/ImageGenModelsRoute.kt  (225 行 / 9.2 KB)
  - fun ImageGenModelsRoute,private fun StateCard,private fun progressLine,private fun formatBytes,private fun backendLabel

## feature/settings/src/main/java/dev/praxis/feature/settings/ImageGenRoute.kt  (888 行 / 40.1 KB)
  - private sealed interface ProbeUi,fun ImageGenRoute,private fun targetLine,private fun BareCard,private fun sourceLine,private fun healthLine,private fun reasonLine,private val PLAN_LINES,private fun DownloadSourceDialog,private fun formatCfg,private fun GenerationParamsDialog,private fun DiagnosticRow,private fun imageGenAvailabilityText

## feature/settings/src/main/java/dev/praxis/feature/settings/ImageGenTargetDialog.kt  (352 行 / 16.1 KB)
  - data class ImageGenRemoteModel,fun ImageGenTargetDialog,private fun RemoteTargetDialog,private fun RemoteField

## feature/settings/src/main/java/dev/praxis/feature/settings/SettingsEntry.kt  (220 行 / 7.6 KB)
> * 设置子页清单。
  - enum class SettingsEntry

## feature/settings/src/main/java/dev/praxis/feature/settings/SettingsHost.kt  (1107 行 / 57.5 KB)
  - fun SettingsHost,private fun SettingsPageBody,internal val SPEECH_REASON: String,internal val PACKAGES_REASON: String

## feature/settings/src/main/java/dev/praxis/feature/settings/ai/FunctionModelScreen.kt  (443 行 / 19.7 KB)
  - fun FunctionModelRoute,private fun imageGenTargetText,private fun FunctionSlotRow

## feature/settings/src/main/java/dev/praxis/feature/settings/ai/ModelPickerDialog.kt  (512 行 / 25.7 KB)
  - fun ModelPickerDialog,private fun ModelRow

## feature/settings/src/main/java/dev/praxis/feature/settings/ai/ModelRoute.kt  (303 行 / 13.2 KB)
  - private sealed interface ProfileDialog,fun ModelRoute,private fun ProfileNameDialog

## feature/settings/src/main/java/dev/praxis/feature/settings/ai/ModelScreen.kt  (1437 行 / 73.5 KB)
  - fun ModelScreen,private fun ProfileSpinner,private fun ProviderSpinner,private fun ClearApiKeyButton,fun maskApiKey

## feature/settings/src/main/java/dev/praxis/feature/settings/ai/ModelSelectDialog.kt  (262 行 / 11.0 KB)
  - fun ModelSelectDialog,private fun SingleModelRow

## feature/settings/src/main/java/dev/praxis/feature/settings/ai/SystemPromptRoute.kt  (43 行 / 1.8 KB)
> * 系统提示词路由：读当前值 + 保存。
  - fun SystemPromptRoute

## feature/settings/src/main/java/dev/praxis/feature/settings/ai/SystemPromptScreen.kt  (255 行 / 12.3 KB)
  - fun SystemPromptScreen

## feature/settings/src/main/java/dev/praxis/feature/settings/appearance/AppearanceRoute.kt  (78 行 / 3.5 KB)
  - fun AppearanceRoute

## feature/settings/src/main/java/dev/praxis/feature/settings/appearance/AppearanceScreen.kt  (415 行 / 18.5 KB)
  - fun AppearanceScreen,private val ChatDisplayStyle.label: String,private fun chatDisplayStyleHint,private fun <T> SpinnerRow,private fun Hint,internal val ThemeMode.displayName: String,internal val ColorSource.displayName: String,internal val PaletteStyle.displayName: String,internal val ColorSpecVersion.displayName: String,internal val PredictiveBackAnimation.displayName: String,internal val PredictiveBackExitDirection.displayName: String,private fun predictiveBackHint

## feature/settings/src/main/java/dev/praxis/feature/settings/capability/MemoryCard.kt  (124 行 / 4.2 KB)
  - fun MemoryCard,private fun TagChip,internal fun relativeTime

## feature/settings/src/main/java/dev/praxis/feature/settings/capability/MemoryRoute.kt  (88 行 / 3.3 KB)
  - fun MemoryRoute

## feature/settings/src/main/java/dev/praxis/feature/settings/capability/MemoryScreen.kt  (281 行 / 12.4 KB)
  - fun MemoryScreen,private const val DEFAULT_SPACE_LABEL,private fun spaceLabelOf,private fun CreateSpaceDialog

## feature/settings/src/main/java/dev/praxis/feature/settings/capability/WorkflowNodeCard.kt  (169 行 / 7.3 KB)
  - fun WorkflowNodeCard,fun WorkflowBranchRow,private fun BranchChip

## feature/settings/src/main/java/dev/praxis/feature/settings/capability/WorkflowRoute.kt  (70 行 / 2.6 KB)
  - fun WorkflowRoute

## feature/settings/src/main/java/dev/praxis/feature/settings/capability/WorkflowScreen.kt  (328 行 / 13.8 KB)
  - fun WorkflowScreen,private fun StepRow,internal fun WorkflowNode.kindLabel,private fun WorkflowNode.kindColor,internal fun WorkflowNode.primaryNextId,internal fun WorkflowNode.detailRows,val SampleWorkflows: List<WorkflowDefinition>

## feature/settings/src/main/java/dev/praxis/feature/settings/character/CharacterEditRoute.kt  (78 行 / 3.1 KB)
  - fun CharacterEditRoute

## feature/settings/src/main/java/dev/praxis/feature/settings/character/CharacterEditScreen.kt  (416 行 / 19.2 KB)
  - fun CharacterEditScreen

## feature/settings/src/main/java/dev/praxis/feature/settings/character/CharacterViewModel.kt  (394 行 / 14.6 KB)
  - data class CharactersUiState,data class CharacterEditUiState,class CharacterViewModel

## feature/settings/src/main/java/dev/praxis/feature/settings/character/CharactersRoute.kt  (67 行 / 2.8 KB)
  - fun CharactersRoute

## feature/settings/src/main/java/dev/praxis/feature/settings/character/CharactersScreen.kt  (334 行 / 13.3 KB)
  - fun CharactersScreen,private fun ImportDialog,private fun CharacterRow

## feature/settings/src/main/java/dev/praxis/feature/settings/character/PromptTagsRoute.kt  (68 行 / 2.4 KB)
  - fun PromptTagsRoute

## feature/settings/src/main/java/dev/praxis/feature/settings/character/PromptTagsScreen.kt  (349 行 / 15.1 KB)
  - fun PromptTagsScreen,private fun TagRow,private fun EditTagDialog

## feature/settings/src/main/java/dev/praxis/feature/settings/common/SettingsList.kt  (161 行 / 8.8 KB)
  - fun SettingsList

## feature/settings/src/main/java/dev/praxis/feature/settings/env/InstallLogPanel.kt  (146 行 / 6.2 KB)
  - fun InstallLogPanel

## feature/settings/src/main/java/dev/praxis/feature/settings/env/LinuxEnvRoute.kt  (73 行 / 3.4 KB)
> * Linux 环境路由：把纯逻辑的 [LinuxEnvPort] 接到 ViewModel + 页面。
  - fun LinuxEnvRoute

## feature/settings/src/main/java/dev/praxis/feature/settings/env/LinuxEnvScreen.kt  (800 行 / 39.6 KB)
  - fun LinuxEnvScreen,private fun SharedRootfsNotice,private fun <T> SpinnerRow

## feature/settings/src/main/java/dev/praxis/feature/settings/env/LinuxEnvViewModel.kt  (1376 行 / 59.2 KB)
  - data class LinuxEnvUiState,interface LinuxEnvPort,class LinuxEnvViewModel

## feature/settings/src/main/java/dev/praxis/feature/settings/env/PackageGroupsCard.kt  (232 行 / 9.9 KB)
  - internal fun PackageGroupsCard,private fun PackageGroupRow

## feature/settings/src/main/java/dev/praxis/feature/settings/env/TerminalSettingsScreen.kt  (261 行 / 12.3 KB)
  - fun TerminalSettingsScreen,data class TerminalStorageState

## feature/settings/src/main/java/dev/praxis/feature/settings/env/TerminalStorageRoute.kt  (150 行 / 6.9 KB)
  - internal fun rememberTerminalStorageState,private fun rememberTerminalStorageStateOrThrow

## feature/settings/src/main/java/dev/praxis/feature/settings/env/TerminalStorageSection.kt  (572 行 / 25.0 KB)
  - internal fun StorageManagementCard,private fun UsageRow,private fun ResetConfirmDialog,internal fun SharedTmpCard,internal fun VirtualKeyboardCard,internal fun VirtualKeyboardEditorDialog

## feature/settings/src/main/java/dev/praxis/feature/settings/home/SettingsHomeScreen.kt  (153 行 / 6.4 KB)
  - fun SettingsHomeScreen,private fun SettingsCard

## feature/settings/src/main/java/dev/praxis/feature/settings/home/SettingsUnfinished.kt  (101 行 / 3.9 KB)
  - internal fun SettingsUnfinished,internal fun SettingWrongRoute

## feature/settings/src/main/java/dev/praxis/feature/settings/info/AboutScreen.kt  (632 行 / 29.0 KB)
  - fun AboutScreen,private fun GlassCard,private fun layerProgress,private fun LicenseRow

## feature/settings/src/main/java/dev/praxis/feature/settings/info/LogScreen.kt  (700 行 / 30.4 KB)
  - fun LogScreen,private const val COLLAPSE_LINES,private fun LogEntryCard,private fun MonoText,private fun levelColor,private fun ClearLogsRow,private const val LOG_SCROLL_ANIMATION_THRESHOLD,private fun LogScrollButtons,private fun ScrollFab,private fun labelOf,private fun formatBytes,private fun GatewayRequestCard

## feature/settings/src/main/java/dev/praxis/feature/settings/info/OpenSourceLicenseScreen.kt  (157 行 / 6.9 KB)
  - fun OpenSourceLicenseScreen,private fun InfoCard

## feature/settings/src/main/java/dev/praxis/feature/settings/plugin/CompatPluginEntryRoute.kt  (86 行 / 3.2 KB)
  - fun CompatPluginEntryRoute

## feature/settings/src/main/java/dev/praxis/feature/settings/plugin/CompatPluginEntryScreen.kt  (183 行 / 7.7 KB)
  - fun CompatPluginEntryScreen,private fun EntryRow

## feature/settings/src/main/java/dev/praxis/feature/settings/plugin/ComposeDslView.kt  (170 行 / 5.6 KB)
  - fun ComposeDslView,private const val MAX_DEPTH,private fun unsupportedText,private fun UnsupportedCard

## feature/settings/src/main/java/dev/praxis/feature/settings/plugin/PluginEntryScreen.kt  (144 行 / 5.4 KB)
  - fun PluginEntryScreen,private sealed interface EntryViewState

## feature/settings/src/main/java/dev/praxis/feature/settings/plugin/PluginViewModel.kt  (236 行 / 9.7 KB)
  - data class PluginUiState,class PluginViewModel

## feature/settings/src/main/java/dev/praxis/feature/settings/plugin/PluginsRoute.kt  (48 行 / 1.7 KB)
  - fun PluginsRoute

## feature/settings/src/main/java/dev/praxis/feature/settings/plugin/PluginsScreen.kt  (263 行 / 10.1 KB)
  - fun PluginsScreen,private fun InstalledRow,private fun MarketRow

## feature/settings/src/main/java/dev/praxis/feature/settings/tools/PermissionScreen.kt  (399 行 / 18.9 KB)
  - fun PermissionScreen,private fun CapabilityRow,private fun PermissionTier.summary,private fun PermissionTier.detail

## feature/settings/src/main/java/dev/praxis/feature/settings/tools/PermissionSwitchRow.kt  (179 行 / 7.1 KB)
  - enum class ToolAccessLevel,fun PermissionSwitchRow,private fun AccessBadge

## feature/settings/src/main/java/dev/praxis/feature/settings/tools/PrivilegeStatusCard.kt  (307 行 / 12.6 KB)
> // SPDX-License-Identifier: GPL-3.0-only
  - fun PrivilegeStatusCard,fun PrivilegeStatCard,fun PrivilegeStatRow

## feature/settings/src/main/java/dev/praxis/feature/settings/tools/ToolsScreen.kt  (181 行 / 6.9 KB)
  - fun ToolsScreen,private fun ToolRow,internal val PermissionTier.tierLabel: String,internal val ToolRisk.riskLabel: String,internal fun PermissionDecision.toAccessLevel

## feature/settings/src/test/java/dev/praxis/feature/settings/LinuxEnvViewModelTest.kt  (351 行 / 13.4 KB)
  - class LinuxEnvViewModelTest

## feature/settings/src/test/java/dev/praxis/feature/settings/SettingsMappingTest.kt  (173 行 / 7.2 KB)
  - class SettingsMappingTest

## feature/settings/src/test/java/dev/praxis/feature/settings/character/CharacterUiStateTest.kt  (140 行 / 5.0 KB)
> * 角色卡界面的**派生状态**判定。
  - class CharacterUiStateTest

## feature/settings/src/test/java/dev/praxis/feature/settings/env/LinuxEnvSharedRootfsTest.kt  (79 行 / 3.3 KB)
> * 共用文件系统下「要不要显示『应用 XX 的软件源』」的判定。
  - class LinuxEnvSharedRootfsTest

## feature/shell/build.gradle.kts  (33 行 / 0.9 KB)
> // 根目的地 = 底栏 + Pager + 每页自己的 Scaffold。


## feature/terminal/build.gradle.kts  (35 行 / 1.0 KB)


## feature/terminal/src/main/AndroidManifest.xml  (2 行 / 0.0 KB)


## feature/terminal/src/main/java/dev/praxis/feature/terminal/PtySessionPort.kt  (148 行 / 5.7 KB)
> * 真终端会话端口。
  - interface PtySessionPort,object NoPtySessionPort : PtySessionPort

## feature/terminal/src/main/java/dev/praxis/feature/terminal/TerminalScreen.kt  (1439 行 / 66.2 KB)
  - fun TerminalScreen,private fun historyIndexOf,private fun TerminalTabStrip,private fun TerminalTabItem,private fun TerminalActionBar,private fun TerminalIconChip,private fun TerminalActionChip,internal fun buildAnnotatedScreen,private fun buildAnnotatedLine,private data class StyleKey,private fun TerminalInputBar,data class TerminalTab,data class TerminalUiState,data class TerminalLine,data class VersionedLine,data class RenderCell,object TerminalPalette,object TerminalTypography

## feature/terminal/src/main/java/dev/praxis/feature/terminal/TerminalSnapshotPort.kt  (84 行 / 3.4 KB)
> * 终端会话状态持久化端口（**进程重启后恢复**）。
  - interface TerminalSnapshotPort,data class TerminalSnapshot,data class TabSnapshot

## feature/terminal/src/main/java/dev/praxis/feature/terminal/TerminalViewModel.kt  (1137 行 / 51.1 KB)
  - private val CRLF: String,interface CommandRunner,class TerminalViewModel,internal class Utf8StreamDecoder

## feature/terminal/src/main/java/dev/praxis/feature/terminal/keyboard/TerminalKeyEdit.kt  (277 行 / 11.6 KB)
> * CTRL / ALT 修饰符状态。
  - data class TerminalModifiers,data class TerminalKeyInput,fun applyTerminalKey,private fun historyStep,private fun wordLeft,private fun wordRight,const val HISTORY_PAGE_SIZE: Int

## feature/terminal/src/main/java/dev/praxis/feature/terminal/keyboard/TerminalKeyboardModel.kt  (223 行 / 8.0 KB)
> * 虚拟键盘上的一个**物理按键身份**。
  - enum class TerminalKeyId,data class TerminalKey,data class TerminalKeyLayout,val DefaultTerminalKeyLayout: TerminalKeyLayout,fun terminalKeyLayoutOf

## feature/terminal/src/main/java/dev/praxis/feature/terminal/keyboard/TerminalVirtualKeyboard.kt  (227 行 / 10.0 KB)
  - fun TerminalVirtualKeyboard,private fun KeyCap

## feature/terminal/src/test/java/dev/praxis/feature/terminal/ExternalSessionSyncTest.kt  (217 行 / 7.2 KB)
  - class ExternalSessionSyncTest

## feature/terminal/src/test/java/dev/praxis/feature/terminal/TerminalAnnotatedScreenTest.kt  (98 行 / 3.6 KB)
> * [buildAnnotatedScreen] 的单测。
  - class TerminalAnnotatedScreenTest

## feature/terminal/src/test/java/dev/praxis/feature/terminal/TerminalViewModelTest.kt  (243 行 / 9.1 KB)
  - class TerminalViewModelTest

## feature/terminal/src/test/java/dev/praxis/feature/terminal/Utf8StreamDecoderTest.kt  (135 行 / 5.4 KB)
> // ## 为什么用 JUnit4 而不是 kotlin.test（2026-11-05）
  - class Utf8StreamDecoderTest

## feature/terminal/src/test/java/dev/praxis/feature/terminal/keyboard/TerminalKeyEditTest.kt  (308 行 / 11.7 KB)
> * 虚拟键盘编辑层的单元测试。
  - class TerminalKeyEditTest

## gradle.properties  (63 行 / 3.3 KB)
> # ── Gradle JVM ─────────────────────────────────────────


## gradle/libs.versions.toml  (256 行 / 15.1 KB)
> # ⚠️ 不能低于 1.13.0 —— 这是 miuix 0.9.4 下拉/弹窗的一个硬约束。


## gradle/wrapper/gradle-wrapper.properties  (7 行 / 0.2 KB)


## gradlew.bat  (83 行 / 2.8 KB)


## local.properties  (7 行 / 0.3 KB)
> # 本地调试用（本文件已被 .gitignore 排除）。


## local.properties.example  (20 行 / 1.0 KB)
> # 复制为 local.properties 后按本机情况填写。


## preview/build.gradle.kts  (75 行 / 2.9 KB)
> // ## 为什么必须应用 Paparazzi 的 Gradle 插件（2026-11-01 补）


## preview/src/main/AndroidManifest.xml  (21 行 / 1.1 KB)
> ## 为什么需要 `uses-sdk tools:overrideLibrary`


## preview/src/test/java/dev/praxis/preview/ScreenPreviewTest.kt  (242 行 / 9.7 KB)
  - class ScreenPreviewTest

## scripts/ci-release.sh  (43 行 / 1.4 KB)
> #!/usr/bin/env bash


## scripts/ci.sh  (42 行 / 1.4 KB)
> #!/usr/bin/env bash


## scripts/fetch-rootfs.sh  (158 行 / 5.5 KB)
> #!/usr/bin/env bash


## scripts/smoke.sh  (51 行 / 1.5 KB)
> #!/usr/bin/env bash


## settings.gradle.kts  (108 行 / 5.0 KB)


## third_party/maven/repack.py  (27 行 / 1.0 KB)


## third_party/miuix/build.gradle.kts  (86 行 / 3.9 KB)
> // `fan.miuix:*:1.0.13.0` 的本地 Maven 仓库载体。


## tools/audit_scroll_inset.py  (103 行 / 4.0 KB)
> #!/usr/bin/env python3


## tools/audit_unwired.py  (157 行 / 5.9 KB)
> #!/usr/bin/env python3


## tools/check_apk_alignment.py  (183 行 / 6.9 KB)
> #!/usr/bin/env python3


## tools/check_kt_balance.py  (58 行 / 2.2 KB)
> #!/usr/bin/env python3


## tools/check_kt_files.py  (54 行 / 1.7 KB)
> #!/usr/bin/env python3


## tools/check_kt_literals.py  (258 行 / 7.7 KB)
> #!/usr/bin/env python3


## tools/check_nested_comments.py  (157 行 / 5.5 KB)
> #!/usr/bin/env python3
  - fun foo

## tools/fix_html_entities.py  (180 行 / 5.6 KB)
> #!/usr/bin/env python3


## tools/kt_lexer.py  (197 行 / 5.6 KB)
> #!/usr/bin/env python3


## tools/preview_server.py  (527 行 / 19.3 KB)
> #!/usr/bin/env python3
  - class Renderer:,class Handler,class Server

## tools/publish_apk.py  (106 行 / 3.1 KB)
> #!/usr/bin/env python3


## tools/test_kt_lexer.py  (183 行 / 5.2 KB)
> #!/usr/bin/env python3


## tools/verify_device_terminal.py  (181 行 / 6.0 KB)
> # -*- coding: utf-8 -*-


## ui/design/build.gradle.kts  (59 行 / 2.3 KB)


## ui/design/src/main/AndroidManifest.xml  (7 行 / 0.4 KB)


## ui/design/src/main/java/dev/praxis/ui/design/component/AppScaffold.kt  (407 行 / 20.5 KB)
  - private val BlurOverhang,val LocalBottomBarInset,fun AppScaffold,val LocalTopBarInset,val LocalScrollBottomLimit,private fun BlurredTopBar

## ui/design/src/main/java/dev/praxis/ui/design/component/AutoFollowScroll.kt  (677 行 / 27.8 KB)
  - fun AutoFollowScrollLazy,fun AutoFollowScroll,private const val NOT_SEEN,internal fun shouldFollowContent,internal fun shouldFollowScroll,internal fun isAtBottom,internal fun unknownRemainingPx,internal fun nextAutoFollow,private data class ViewportProbe,private class RunState,private suspend fun followToEnd,private fun remainingDeltaPx

## ui/design/src/main/java/dev/praxis/ui/design/component/FloatingIconButton.kt  (87 行 / 3.4 KB)
  - fun FloatingIconButton

## ui/design/src/main/java/dev/praxis/ui/design/component/ImeLift.kt  (152 行 / 6.4 KB)
  - data class ImeLift,fun rememberImeLift,fun Modifier.imeLiftPadding

## ui/design/src/main/java/dev/praxis/ui/design/component/LowAppScaffold.kt  (88 行 / 3.9 KB)
  - internal fun LowAppScaffold

## ui/design/src/main/java/dev/praxis/ui/design/component/PraxisPage.kt  (115 行 / 5.4 KB)
  - fun PraxisPage,fun PageSurface

## ui/design/src/main/java/dev/praxis/ui/design/component/RevealEngine.kt  (198 行 / 8.2 KB)
> * 揭示节奏引擎：控制「文字如何出现」。
  - object RevealEngine

## ui/design/src/main/java/dev/praxis/ui/design/component/ScrollThumbRail.kt  (240 行 / 11.2 KB)
  - private val BUTTON_SIZE,fun ScrollThumbRail,internal fun isAtBottom,internal fun shouldShowScrollToBottom

## ui/design/src/main/java/dev/praxis/ui/design/component/SpringFollower.kt  (110 行 / 4.2 KB)
> * 二阶阻尼弹簧逐帧积分器（照抄 pk-node 的实现）。
  - internal object SpringFollower

## ui/design/src/main/java/dev/praxis/ui/design/diagnostics/UiDiagnostics.kt  (143 行 / 6.1 KB)
> * UI 缺陷的**证据收集器**（不依赖任何日志库）。
  - object UiDiagnostics,fun TraceRecomposition

## ui/design/src/main/java/dev/praxis/ui/design/effect/AnimatedFluidBackground.kt  (431 行 / 19.6 KB)
> // SPDX-License-Identifier: GPL-3.0-only
  - fun AnimatedFluidBackground,private fun AnimatedFluidBackgroundLayers

## ui/design/src/main/java/dev/praxis/ui/design/effect/BgEffectBackground.kt  (207 行 / 9.8 KB)
  - fun BgEffectBackground,private fun rememberDeviceType

## ui/design/src/main/java/dev/praxis/ui/design/effect/BgEffectConfig.kt  (210 行 / 8.1 KB)
> * 背景渐变预设。**数值逐字照抄 InstallerX `ui/library/effect/BgEffectConfig.kt`**。
  - object BgEffectConfig

## ui/design/src/main/java/dev/praxis/ui/design/effect/BgEffectModifier.kt  (241 行 / 9.2 KB)
> * 把背景着色器画进本节点的绘制阶段。**移植自 InstallerX `BgEffectModifier.kt`**，
  - internal fun Modifier.bgEffectDraw,private class BgEffectElement,private class BgEffectNode

## ui/design/src/main/java/dev/praxis/ui/design/effect/BgEffectPainter.kt  (210 行 / 9.0 KB)
> * 背景着色器的参数装配器。**逐行移植 InstallerX `BgEffectPainter.kt`**。
  - class BgEffectPainter

## ui/design/src/main/java/dev/praxis/ui/design/effect/DeviceType.kt  (24 行 / 0.9 KB)
> * 背景渐变预设对应的设备形态。
  - enum class DeviceType

## ui/design/src/main/java/dev/praxis/ui/design/effect/GlowShader.kt  (370 行 / 12.2 KB)
> * 开屏光晕着色器 —— **逐字照抄 HyperCeiler `library/provision/src/main/res/raw/glow.glsl`**。
  - const val GLOW_AGSL

## ui/design/src/main/java/dev/praxis/ui/design/effect/HyperCeilerStartupGlow.kt  (271 行 / 12.4 KB)
  - fun HyperCeilerStartupGlow,private fun StartupGlowShaderImpl,private fun applyAllUniforms

## ui/design/src/main/java/dev/praxis/ui/design/effect/OS2BgFrag.kt  (101 行 / 4.0 KB)
> * OS2（MIUI 12）背景着色器。**全文逐字照抄 InstallerX `OS2BgFrag.kt`**。
  - const val OS2_BG_FRAG

## ui/design/src/main/java/dev/praxis/ui/design/effect/OS3BgFrag.kt  (104 行 / 4.6 KB)
> * OS3（MIUI 14+）背景着色器。**全文逐字照抄 InstallerX `OS3BgFrag.kt`**。
  - const val OS3_BG_FRAG

## ui/design/src/main/java/dev/praxis/ui/design/glass/DragGestureInspector.kt  (104 行 / 3.8 KB)
> // InstallerX-Revived
  - suspend fun PointerInputScope.inspectDragGestures,private suspend inline fun AwaitPointerEventScope.drag,private suspend inline fun AwaitPointerEventScope.awaitDragOrUp

## ui/design/src/main/java/dev/praxis/ui/design/glass/FloatingBottomBar.kt  (657 行 / 28.9 KB)
> // SPDX-License-Identifier: GPL-3.0-only
  - private val LocalFloatingBottomBarContentColor,private val LocalFloatingBottomBarTabScale,class FloatingBottomBarColors,object FloatingBottomBarDefaults,enum class FloatingBottomBarMode,private val iosIndicatorSpecular: Highlight,private const val LIGHT_REF_X,private const val LIGHT_REF_Y,private const val GRAVITY_DIR_THRESHOLD_SQ,private fun rememberGravityRotatedHighlight,fun <T> FloatingBottomBar

## ui/design/src/main/java/dev/praxis/ui/design/glass/GlassBackend.kt  (62 行 / 2.3 KB)
> * 玻璃渲染后端选择 —— 含**临时调试开关**（用户要求：「测试可以开一个临时用来启用 old 渲染的开关」）。
  - object GlassBackend

## ui/design/src/main/java/dev/praxis/ui/design/glass/LocalPageBackdrop.kt  (25 行 / 1.1 KB)
> * 当前页面内容层的采样源，供**页面之上的浮层**（底栏、悬浮按钮、弹出面板）折射。
  - val LocalPageBackdrop

## ui/design/src/main/java/dev/praxis/ui/design/glass/animation/DampedDragAnimation.kt  (225 行 / 8.6 KB)
> // SPDX-License-Identifier: GPL-3.0-only
  - class DampedDragAnimation

## ui/design/src/main/java/dev/praxis/ui/design/glass/animation/InteractiveHighlight.kt  (120 行 / 5.0 KB)
> // InstallerX-Revived
  - class InteractiveHighlight

## ui/design/src/main/java/dev/praxis/ui/design/glass/kyant/LiquidButton.kt  (206 行 / 8.5 KB)
  - fun LiquidGlassButton,enum class LiquidButtonTone,private fun Color.isSpecifiedCompat

## ui/design/src/main/java/dev/praxis/ui/design/glass/kyant/LiquidToggle.kt  (308 行 / 14.7 KB)
  - fun LiquidToggle

## ui/design/src/main/java/dev/praxis/ui/design/glass/kyant/LocalKyantBackdrop.kt  (43 行 / 2.0 KB)
> * 当前页面的 Kyant 采样源（液态玻璃控件的「背景」）。
  - val LocalKyantBackdrop,fun rememberPageBackdropOrFallback

## ui/design/src/main/java/dev/praxis/ui/design/glass/liquid/CombinedBackdrop.kt  (48 行 / 1.9 KB)
> // Copyright 2026, compose-miuix-ui contributors
  - class CombinedBackdrop,fun rememberCombinedBackdrop

## ui/design/src/main/java/dev/praxis/ui/design/glass/liquid/InnerShadow.kt  (164 行 / 5.2 KB)
> // Copyright 2026, compose-miuix-ui contributors
  - data class InnerShadow,fun Modifier.innerShadow,private class InnerShadowElement,private class InnerShadowNode,private val ShadowMaskPaint: Paint

## ui/design/src/main/java/dev/praxis/ui/design/glass/liquid/Lens.kt  (226 行 / 7.9 KB)
> // Copyright 2026, compose-miuix-ui contributors
  - fun BackdropEffectScope.lens,private fun BackdropEffectScope.roundedRectCornerRadii,private const val ROUNDED_RECT_SDF,private const val ROUNDED_RECT_REFRACTION_SHADER,private const val ROUNDED_RECT_REFRACTION_WITH_DISPERSION_SHADER

## ui/design/src/main/java/dev/praxis/ui/design/glass/liquid/Vibrancy.kt  (20 行 / 0.6 KB)
> // Copyright 2026, compose-miuix-ui contributors
  - fun BackdropEffectScope.vibrancy

## ui/design/src/main/java/dev/praxis/ui/design/glass/low/LowFrostedTopBar.kt  (89 行 / 3.6 KB)
  - private const val TOPBAR_FROSTED_ALPHA,internal fun LowFrostedTopBar

## ui/design/src/main/java/dev/praxis/ui/design/glass/low/LowLiquidToggle.kt  (199 行 / 8.9 KB)
  - internal fun LowLiquidToggle

## ui/design/src/main/java/dev/praxis/ui/design/util/DeviceCornerRadius.kt  (104 行 / 3.8 KB)
  - fun rememberDeviceCornerRadius,private fun deviceCornerRadiusPx,private fun roundedCornerRadiusPx,private fun cornerRadiusFromResources

## ui/design/src/main/res/anim/sine_in_out_interpolator.xml  (7 行 / 0.2 KB)


## ui/design/src/main/res/values-night/colors.xml  (30 行 / 0.9 KB)


## ui/design/src/main/res/values/colors.xml  (37 行 / 1.0 KB)


## ui/design/src/test/java/dev/praxis/ui/design/component/AutoFollowScrollTest.kt  (239 行 / 8.0 KB)
> * 自动跟随滚动的**判据**测试（对应缺陷第 10 项）。
  - class AutoFollowScrollTest

## ui/design/src/test/java/dev/praxis/ui/design/component/RevealEngineTest.kt  (181 行 / 7.2 KB)
> * 揭示节奏引擎的数值契约。
  - class RevealEngineTest

## ui/design/src/test/java/dev/praxis/ui/design/component/ScrollThumbRailVisibilityTest.kt  (107 行 / 4.0 KB)
> * ⑥ 聊天页回底按钮：显示判定的两个纯函数。
  - class ScrollThumbRailVisibilityTest

## ui/design/src/test/java/dev/praxis/ui/design/component/SpringFollowerTest.kt  (110 行 / 4.2 KB)
> * [SpringFollower] 的行为锁定。
  - class SpringFollowerTest

## ui/icons/build.gradle.kts  (23 行 / 0.6 KB)


## ui/icons/src/main/kotlin/dev/praxis/ui/icons/PraxisIcons.kt  (96 行 / 4.5 KB)
> // SPDX-License-Identifier: GPL-3.0-only
  - object PraxisIcons

## ui/navigation/build.gradle.kts  (38 行 / 1.2 KB)


## ui/navigation/src/main/AndroidManifest.xml  (7 行 / 0.4 KB)


## ui/navigation/src/main/java/dev/praxis/ui/navigation/BottomBarGlass.kt  (93 行 / 4.0 KB)
> // SPDX-License-Identifier: GPL-3.0-only
  - internal fun bottomBarSurface,internal fun rememberBottomBarBackdrop,internal fun Modifier.bottomBarGlass

## ui/navigation/src/main/java/dev/praxis/ui/navigation/MainPagerState.kt  (137 行 / 6.2 KB)
  - class MainPagerState,fun rememberMainPagerState

## ui/navigation/src/main/java/dev/praxis/ui/navigation/MainScreenBackHandler.kt  (97 行 / 4.5 KB)
> * 系统返回键的**第二级**处理：Tab 内退回到第一个 Tab。
  - fun MainScreenBackHandler

## ui/navigation/src/main/java/dev/praxis/ui/navigation/Navigator.kt  (121 行 / 4.9 KB)
> * 导航器：栈操作的唯一入口。
  - class Navigator,val LocalNavigator

## ui/navigation/src/main/java/dev/praxis/ui/navigation/PraxisBottomBar.kt  (151 行 / 5.8 KB)
> // SPDX-License-Identifier: GPL-3.0-only
  - fun PraxisBottomBar,private fun PraxisFallbackBottomBar,private fun MainTab.icon,private fun MainTab.title

## ui/navigation/src/main/java/dev/praxis/ui/navigation/PraxisNavDisplay.kt  (240 行 / 12.6 KB)
  - fun PraxisNavDisplay,fun PraxisRoute.title,fun PraxisRoute.isRoot,fun NavBackStack.currentRoute,fun NavBackStack.ensureSeeded

## ui/navigation/src/main/java/dev/praxis/ui/navigation/Routes.kt  (319 行 / 12.4 KB)
> * 全部导航目的地。
  - sealed interface PraxisRoute : NavKey,data object SettingsGateway : PraxisRoute,data object SettingsGatewayAccounts : PraxisRoute,data object SettingsGatewayImport : PraxisRoute,data object SettingsGatewayRouting : PraxisRoute,data object SettingsGatewayAdd : PraxisRoute,data class SettingsGatewayAddLogin,data object SettingsGatewayProxy : PraxisRoute,data object SettingsGatewaySystem : PraxisRoute,data object SettingsGatewayProviders : PraxisRoute,data object SettingsGatewayReport : PraxisRoute,data object SettingsGatewayModels : PraxisRoute,data object SettingsImageGen : PraxisRoute,data object SettingsImageGenModels : PraxisRoute,data object SettingsCompatPluginEntries : PraxisRoute,data class PluginEntry,enum class MainTab

## ui/navigation/src/main/java/dev/praxis/ui/navigation/transition/AospNavTransition.kt  (369 行 / 15.7 KB)
> // SPDX-License-Identifier: GPL-3.0-only
  - private const val BOUNCE_STIFFNESS,private const val BOUNCE_DAMPING,private const val BOUNCE_MAX_KICK,private const val BOUNCE_MIN_KICK,private const val OPEN_FADE_START,private const val OPEN_FADE_SPAN,private const val CLOSE_FADE_START,private const val CLOSE_FADE_SPAN,private const val CLASSIC_FADE_DURATION,private const val OPEN_FADE_OFFSET,private const val CLOSE_FADE_OFFSET,private const val CROSS_ACTIVITY_MIN_SCALE,private val CrossActivityDrift,private val CrossActivityEdgeMargin,private val ClassicActivityMotion,private val ClassicActivityOpen: NavTransition,private val ClassicActivityClose: NavTransition,private val CrossActivityPredictive: NavTransition,val AospNavTransition: NavTransition,private fun bounceScale,private fun shapedTopProgress,private fun crossActivityYShift

## ui/navigation/src/main/java/dev/praxis/ui/navigation/transition/NavTransitionDispatcher.kt  (42 行 / 2.0 KB)
> // SPDX-License-Identifier: GPL-3.0-only
  - fun navTransitionFor

## ui/navigation/src/main/java/dev/praxis/ui/navigation/transition/NavTransitionGeometry.kt  (92 行 / 3.9 KB)
> // SPDX-License-Identifier: GPL-3.0-only
  - internal val FastOutExtraSlowIn: Easing,internal val BackGestureEasing: Easing,internal fun topProgress,internal fun coverProgress,internal fun snapScaleToPixelExtent,internal fun snapTranslationToPixelEdge

## ui/navigation/src/main/java/dev/praxis/ui/navigation/transition/ScaleAndClassicNavTransition.kt  (229 行 / 9.8 KB)
> // SPDX-License-Identifier: GPL-3.0-only
  - private val ScaleExitMotion,fun scaleNavTransition,private fun shapedTopProgress,private fun exitDirectionSign,private fun gesturePivotY,private val ClassicScaleMotion,private val ClassicScalePop: NavTransition,val ClassicNavTransition: NavTransition

## ui/provision/build.gradle.kts  (104 行 / 4.7 KB)
> // HyperCeiler `library/provision` 的 1:1 搬运（50 java / 74 res / 2 aidl /


## ui/provision/lint.xml  (37 行 / 1.7 KB)


## ui/provision/src/main/AndroidManifest.xml  (70 行 / 2.9 KB)


## ui/provision/src/main/aidl/com/sevtinge/hyperceiler/provision/IAnimCallback.aidl  (8 行 / 0.1 KB)
  - interface IAnimCallback

## ui/provision/src/main/aidl/com/sevtinge/hyperceiler/provision/IProvisionAnim.aidl  (15 行 / 0.3 KB)
  - interface IProvisionAnim

## ui/provision/src/main/assets/github_style.css  (28 行 / 0.9 KB)


## ui/provision/src/main/assets/markdown_template.html  (35 行 / 1.3 KB)


## ui/provision/src/main/assets/marked.min.js  (70 行 / 39.0 KB)
> * marked v15.0.12 - a markdown parser


## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/activity/BaseActivity.java  (193 行 / 6.9 KB)
> * This file is part of HyperCeiler.
  - public abstract class BaseActivity extends ProvisionBaseActivity

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/activity/CongratulationActivity.java  (58 行 / 1.9 KB)
> * This file is part of HyperCeiler.
  - public class CongratulationActivity extends ProvisionDetailActivity

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/activity/DefaultActivity.java  (203 行 / 7.2 KB)
> * This file is part of HyperCeiler.
  - public class DefaultActivity extends ProvisionBaseActivity

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/activity/PermissionSettingsActivity.java  (176 行 / 6.9 KB)
> * This file is part of HyperCeiler.
  - public class PermissionSettingsActivity extends BaseActivity

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/activity/ProvisionDetailActivity.java  (69 行 / 2.3 KB)
> * This file is part of HyperCeiler.
  - public abstract class ProvisionDetailActivity extends AppCompatActivity

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/activity/TermsAndStatementActivity.java  (72 行 / 1.9 KB)
> * This file is part of HyperCeiler.
  - public class TermsAndStatementActivity extends BaseActivity

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/fragment/BaseFragment.java  (64 行 / 1.9 KB)
> * This file is part of HyperCeiler.
  - public abstract class BaseFragment extends Fragment

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/fragment/BaseListFragment.java  (56 行 / 1.8 KB)
> * This file is part of HyperCeiler.
  - public abstract class BaseListFragment extends ListFragment

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/fragment/CongratulationFragment.java  (373 行 / 14.3 KB)
> * This file is part of HyperCeiler.
  - public class CongratulationFragment extends BaseFragment implements IOnFocusListener

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/fragment/PermissionSettingsFragment.java  (328 行 / 14.2 KB)
> * This file is part of HyperCeiler.
  - public class PermissionSettingsFragment extends BaseFragment

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/fragment/StartupFragment.java  (472 行 / 16.9 KB)
  - public class StartupFragment extends BaseFragment implements IOnFocusListener

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/fragment/TermsAndStatementFragment.java  (347 行 / 13.0 KB)
> * This file is part of HyperCeiler.
  - public class TermsAndStatementFragment extends BaseFragment

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/fragment/WebFragment.java  (30 行 / 0.8 KB)
  - public class WebFragment extends Fragment

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/renderengine/GlowController.java  (101 行 / 2.9 KB)
  - public class GlowController implements Runnable

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/renderengine/GlowPainter.java  (151 行 / 6.1 KB)
  - public class GlowPainter

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/renderengine/GlowShader.java  (138 行 / 4.7 KB)
> * {@code RuntimeShader} / {@code RenderEffect} 的版本隔离层。
  - interface GlowShader

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/renderengine/RenderCompat.java  (106 行 / 4.1 KB)
> * {@code View.setRenderEffect} 的版本隔离层 + 低版本静态渐变兜底。


## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/renderengine/RenderViewLayout.java  (78 行 / 2.2 KB)
  - public class RenderViewLayout extends ViewGroup

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/service/ProvisionAnimService.java  (103 行 / 3.2 KB)
> * This file is part of HyperCeiler.
  - public class ProvisionAnimService extends Service

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/state/CongratulationState.java  (14 行 / 0.4 KB)
  - public class CongratulationState extends State

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/state/PermissionState.java  (14 行 / 0.4 KB)
  - public class PermissionState extends State

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/state/StartupState.java  (76 行 / 2.9 KB)
  - public class StartupState extends State implements IKeyEvent, IOnFocusListener

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/state/State.java  (131 行 / 3.7 KB)
  - public class State

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/state/StateInfo.java  (24 行 / 0.4 KB)
  - public class StateInfo

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/state/StateMachine.java  (346 行 / 13.6 KB)
  - public class StateMachine

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/state/TermsAndStatementState.java  (10 行 / 0.2 KB)
  - public class TermsAndStatementState extends State

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/text/style/ClickSpan.java  (62 行 / 1.9 KB)
> * This file is part of HyperCeiler.
  - public class ClickSpan extends ClickableSpan

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/text/style/TermsTitleSpan.java  (60 行 / 2.2 KB)
> * This file is part of HyperCeiler.
  - public class TermsTitleSpan extends ClickableSpan

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/utils/AnimHelper.java  (186 行 / 7.5 KB)
> * This file is part of HyperCeiler.
  - public class AnimHelper

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/utils/BlurUtils.java  (49 行 / 1.7 KB)
> * This file is part of HyperCeiler.
  - public class BlurUtils

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/utils/IKeyEvent.java  (26 行 / 0.9 KB)
> * This file is part of HyperCeiler.
  - public interface IKeyEvent

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/utils/IOnFocusListener.java  (24 行 / 0.8 KB)
> * This file is part of HyperCeiler.
  - public interface IOnFocusListener

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/utils/LifecycleHandler.java  (69 行 / 2.2 KB)
> * This file is part of HyperCeiler.
  - public class LifecycleHandler

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/utils/LocalBroadcastHelper.java  (175 行 / 6.3 KB)
> * This file is part of HyperCeiler.
  - public class LocalBroadcastHelper

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/utils/NetworkManager.java  (41 行 / 1.2 KB)
  - public class NetworkManager

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/utils/NoticeProvider.java  (10 行 / 0.2 KB)
  - public interface NoticeProvider

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/utils/PageIntercepHelper.java  (153 行 / 5.5 KB)
> * This file is part of HyperCeiler.
  - public class PageIntercepHelper

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/utils/ProvisionManager.java  (15 行 / 0.3 KB)
  - public class ProvisionManager

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/utils/ProvisionPrefs.java  (114 行 / 5.3 KB)
> * This file is part of HyperCeiler.


## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/utils/ProvisionStateHolder.java  (44 行 / 1.4 KB)
> * This file is part of HyperCeiler.
  - public class ProvisionStateHolder

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/utils/Utils.java  (76 行 / 2.4 KB)
  - public class Utils

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/utils/ViewUtils.java  (96 行 / 3.8 KB)
  - public class ViewUtils

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/widget/MarkdownView.java  (305 行 / 12.2 KB)
  - public class MarkdownView extends AppCompatTextView

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/widget/PermissionItemView.java  (93 行 / 2.7 KB)
> * This file is part of HyperCeiler.
  - public class PermissionItemView extends FrameLayout implements Checkable

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/widget/PermissionWarningSheet.java  (135 行 / 5.3 KB)
> * This file is part of HyperCeiler.
  - public class PermissionWarningSheet

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/widget/SimpleTextWatcher.java  (18 行 / 0.3 KB)
  - public interface SimpleTextWatcher extends TextWatcher

## ui/provision/src/main/java/com/sevtinge/hyperceiler/provision/widget/TermsAndStatementBottomSheet.java  (113 行 / 3.7 KB)
> * This file is part of HyperCeiler.
  - public class TermsAndStatementBottomSheet

## ui/provision/src/main/java/fan/provision/CustomDispatchFrameLayout.java  (64 行 / 2.0 KB)
> * This file is part of HyperCeiler.
  - public class CustomDispatchFrameLayout extends FrameLayout

## ui/provision/src/main/java/fan/provision/OobeUtils.java  (230 行 / 7.7 KB)
> * This file is part of HyperCeiler.
  - public class OobeUtils

## ui/provision/src/main/java/fan/provision/ProvisionAnimHelper.java  (231 行 / 7.8 KB)
> * This file is part of HyperCeiler.
  - public class ProvisionAnimHelper

## ui/provision/src/main/java/fan/provision/ProvisionBaseActivity.java  (615 行 / 19.5 KB)
> * This file is part of HyperCeiler.
  - public abstract class ProvisionBaseActivity extends AppCompatActivity

## ui/provision/src/main/res/anim/enter_home_anim.xml  (20 行 / 0.6 KB)


## ui/provision/src/main/res/anim/enter_home_spring_interpolator.xml  (5 行 / 0.2 KB)


## ui/provision/src/main/res/anim/provision_out_anim.xml  (10 行 / 0.3 KB)


## ui/provision/src/main/res/anim/provision_slide_in_left.xml  (27 行 / 1.0 KB)


## ui/provision/src/main/res/anim/provision_slide_in_right.xml  (27 行 / 1.0 KB)


## ui/provision/src/main/res/anim/provision_slide_out_left.xml  (27 行 / 1.0 KB)


## ui/provision/src/main/res/anim/provision_slide_out_left_animator.xml  (27 行 / 1.0 KB)


## ui/provision/src/main/res/anim/provision_slide_out_right.xml  (27 行 / 1.0 KB)


## ui/provision/src/main/res/anim/sine_in_out_interpolator.xml  (7 行 / 0.2 KB)


## ui/provision/src/main/res/color/provision_item_bg_color.xml  (23 行 / 1.0 KB)


## ui/provision/src/main/res/drawable/provision_confirm_background.xml  (24 行 / 0.9 KB)


## ui/provision/src/main/res/drawable/provision_icon_arrow.xml  (30 行 / 1.5 KB)


## ui/provision/src/main/res/drawable/provision_list_item_background.xml  (23 行 / 1.0 KB)


## ui/provision/src/main/res/drawable/provision_logo_image.xml  (56 行 / 3.1 KB)
> ## 渐变与透明从哪来（与 HyperCeiler 1:1）


## ui/provision/src/main/res/drawable/provision_logo_image_lite.xml  (31 行 / 1.8 KB)


## ui/provision/src/main/res/drawable/provision_logo_terms.xml  (23 行 / 0.9 KB)


## ui/provision/src/main/res/drawable/provision_next.xml  (31 行 / 1.2 KB)


## ui/provision/src/main/res/drawable/provision_next_btn_background.xml  (24 行 / 0.9 KB)


## ui/provision/src/main/res/drawable/provision_next_lite.xml  (35 行 / 1.8 KB)


## ui/provision/src/main/res/drawable/provision_picker_btn_radio.xml  (30 行 / 1.4 KB)


## ui/provision/src/main/res/drawable/provision_service_state.xml  (56 行 / 2.2 KB)


## ui/provision/src/main/res/drawable/provision_skip_background.xml  (24 行 / 0.9 KB)


## ui/provision/src/main/res/drawable/provision_terms.xml  (46 行 / 3.4 KB)


## ui/provision/src/main/res/drawable/provision_text_logo_image.xml  (35 行 / 3.5 KB)
> ## 机制与 HyperCeiler 完全一致


## ui/provision/src/main/res/drawable/provision_text_logo_image_lite.xml  (25 行 / 3.2 KB)


## ui/provision/src/main/res/layout/edit_verification_code_dialog.xml  (44 行 / 1.7 KB)


## ui/provision/src/main/res/layout/fragment_bottom_sheet_web.xml  (51 行 / 1.8 KB)


## ui/provision/src/main/res/layout/provision_actionbar_layout.xml  (37 行 / 1.6 KB)


## ui/provision/src/main/res/layout/provision_anim_display_layout.xml  (39 行 / 1.5 KB)


## ui/provision/src/main/res/layout/provision_congratulation_layout.xml  (124 行 / 4.9 KB)


## ui/provision/src/main/res/layout/provision_detail_layout.xml  (50 行 / 1.9 KB)


## ui/provision/src/main/res/layout/provision_fragment_content.xml  (27 行 / 1.1 KB)


## ui/provision/src/main/res/layout/provision_item_list_statement.xml  (84 行 / 3.2 KB)


## ui/provision/src/main/res/layout/provision_item_list_terms.xml  (54 行 / 2.4 KB)


## ui/provision/src/main/res/layout/provision_list_page_layout.xml  (46 行 / 1.7 KB)


## ui/provision/src/main/res/layout/provision_lyt_title_layout.xml  (52 行 / 2.1 KB)


## ui/provision/src/main/res/layout/provision_page_layout.xml  (117 行 / 4.6 KB)


## ui/provision/src/main/res/layout/provision_permission_layout.xml  (101 行 / 4.6 KB)


## ui/provision/src/main/res/layout/provision_permission_list_item_view.xml  (69 行 / 3.1 KB)


## ui/provision/src/main/res/layout/provision_permission_warning_sheet.xml  (69 行 / 2.6 KB)
> ## 为什么不给 Button 加 style


## ui/provision/src/main/res/layout/provision_startup_layout.xml  (129 行 / 5.1 KB)


## ui/provision/src/main/res/layout/provision_terms_and_statement_layout.xml  (62 行 / 2.6 KB)


## ui/provision/src/main/res/raw/glow.glsl  (382 行 / 12.6 KB)
> //------------------------Basic Params------------------------


## ui/provision/src/main/res/raw/mesh_fragment_shader.glsl  (10 行 / 0.1 KB)
> #version 300 es


## ui/provision/src/main/res/raw/mesh_vertex_shader.glsl  (64 行 / 2.1 KB)
> #version 300 es


## ui/provision/src/main/res/raw/vertex_shader.glsl  (12 行 / 0.2 KB)
> #version 300 es


## ui/provision/src/main/res/values-ar-rSA/strings.xml  (40 行 / 3.3 KB)


## ui/provision/src/main/res/values-de-rDE/strings.xml  (40 行 / 3.0 KB)


## ui/provision/src/main/res/values-es-rES/strings.xml  (23 行 / 0.9 KB)


## ui/provision/src/main/res/values-in-rID/strings.xml  (23 行 / 0.9 KB)


## ui/provision/src/main/res/values-it-rIT/strings.xml  (23 行 / 0.9 KB)


## ui/provision/src/main/res/values-ja-rJP/strings.xml  (69 行 / 5.9 KB)


## ui/provision/src/main/res/values-night/colors.xml  (41 行 / 1.7 KB)


## ui/provision/src/main/res/values-night/themes.xml  (56 行 / 2.6 KB)


## ui/provision/src/main/res/values-pl-rPL/strings.xml  (23 行 / 0.9 KB)


## ui/provision/src/main/res/values-pt-rBR/strings.xml  (40 行 / 2.8 KB)


## ui/provision/src/main/res/values-ru-rRU/strings.xml  (40 行 / 3.7 KB)


## ui/provision/src/main/res/values-tr-rTR/strings.xml  (71 行 / 5.6 KB)


## ui/provision/src/main/res/values-uk-rUA/strings.xml  (40 行 / 3.6 KB)


## ui/provision/src/main/res/values-vi-rVN/strings.xml  (63 行 / 5.4 KB)


## ui/provision/src/main/res/values-zh-rCN/strings.xml  (83 行 / 6.7 KB)


## ui/provision/src/main/res/values-zh-rHK/strings.xml  (71 行 / 5.3 KB)


## ui/provision/src/main/res/values-zh-rME/strings.xml  (27 行 / 1.2 KB)


## ui/provision/src/main/res/values-zh-rTW/strings.xml  (41 行 / 2.7 KB)


## ui/provision/src/main/res/values/arrays.xml  (28 行 / 0.9 KB)


## ui/provision/src/main/res/values/attrs.xml  (7 行 / 0.2 KB)


## ui/provision/src/main/res/values/colors.xml  (50 行 / 2.0 KB)


## ui/provision/src/main/res/values/dimens.xml  (140 行 / 6.5 KB)


## ui/provision/src/main/res/values/drawables.xml  (23 行 / 0.9 KB)


## ui/provision/src/main/res/values/strings.xml  (89 行 / 7.2 KB)


## ui/provision/src/main/res/values/styles.xml  (64 行 / 2.8 KB)


## ui/provision/src/main/res/values/themes.xml  (57 行 / 2.7 KB)


## ui/theme/build.gradle.kts  (35 行 / 1.1 KB)


## ui/theme/src/main/AndroidManifest.xml  (7 行 / 0.4 KB)


## ui/theme/src/main/java/dev/praxis/ui/theme/PraxisCapabilities.kt  (34 行 / 1.4 KB)
> * 设备能力判定。
  - object PraxisCapabilities

## ui/theme/src/main/java/dev/praxis/ui/theme/PraxisMotion.kt  (170 行 / 6.9 KB)
  - object PraxisMotion

## ui/theme/src/main/java/dev/praxis/ui/theme/PraxisRadius.kt  (103 行 / 4.4 KB)
> * 圆角令牌：全项目唯一的圆角数值来源。
  - object PraxisRadius

## ui/theme/src/main/java/dev/praxis/ui/theme/PraxisTheme.kt  (248 行 / 11.1 KB)
  - val LocalThemeSettings,val LocalPraxisIsDark,fun PraxisTheme,private fun rememberM3FromMiuix,internal fun PaletteStyle.toMiuix,internal fun ColorSpecVersion.toMiuix

## ui/theme/src/main/java/dev/praxis/ui/theme/PraxisThemeSettings.kt  (13 行 / 0.6 KB)
> // 类型已下沉到 core:model（core 层不得依赖 ui 层，而设置仓储在 core:data）。
  - val ThemeSettings.keyColorOrNull: Color?

## ui/theme/src/main/java/dev/praxis/ui/theme/SeedSwatch.kt  (208 行 / 7.3 KB)
> * 从 seed 派生一组「色卡预览」用色。
  - data class SeedSwatch,fun seedSwatch,private fun rgbToHsl,private fun hslToColor,object PresetSeedColors

## ui/theme/src/test/java/dev/praxis/ui/theme/PaletteMappingTest.kt  (59 行 / 2.0 KB)
  - class PaletteMappingTest

## ui/widgets/build.gradle.kts  (34 行 / 1.0 KB)


## ui/widgets/src/main/AndroidManifest.xml  (7 行 / 0.4 KB)


## ui/widgets/src/main/java/dev/praxis/ui/widgets/BuildFlags.kt  (19 行 / 0.7 KB)
> * 构建类型标记（library 模块没有 BuildConfig，由 :app 在启动时注入）。
  - object BuildFlags,internal fun isDebugBuild

## ui/widgets/src/main/java/dev/praxis/ui/widgets/ChatDisplayStyle.kt  (78 行 / 3.1 KB)
> * 对话列表的显示风格。
  - enum class ChatDisplayStyle

## ui/widgets/src/main/java/dev/praxis/ui/widgets/FoldableContent.kt  (132 行 / 6.0 KB)
  - fun FoldableContent

## ui/widgets/src/main/java/dev/praxis/ui/widgets/MarkdownText.kt  (517 行 / 21.5 KB)
  - fun MarkdownText,private fun blockSpacing,private fun HeadingBlock,private fun InlineBlock,private fun ListBlock,private fun QuoteBlock,private fun quoteBarHeight,private fun CodeBlock,private fun TableBlock,private fun TableCell,private fun DividerBlock,private fun inlineToAnnotated,private fun StreamingCursor,private const val SHORT_TEXT_LIMIT: Int

## ui/widgets/src/main/java/dev/praxis/ui/widgets/MiuixAnimatedDialog.kt  (309 行 / 14.2 KB)
  - private const val ENTER_DURATION_MS,private const val EXIT_DURATION_MS,private const val ENTER_SCALE,private const val ENTER_SLIDE_DP,private const val EXIT_SCALE,private const val LIFT_RATIO,private const val LIFT_MAX_DP,private const val LIFT_MIN_DP,private val LIFT_RANGE,fun MiuixAnimatedDialog

## ui/widgets/src/main/java/dev/praxis/ui/widgets/MiuixAnimatedPopup.kt  (146 行 / 5.6 KB)
  - private const val POPUP_ENTER_MS,private const val POPUP_EXIT_MS,private const val POPUP_ENTER_SCALE,private const val POPUP_EXIT_SCALE,fun MiuixAnimatedPopup

## ui/widgets/src/main/java/dev/praxis/ui/widgets/MiuixBadge.kt  (54 行 / 1.9 KB)
> // SPDX-License-Identifier: GPL-3.0-only
  - fun MiuixBadge

## ui/widgets/src/main/java/dev/praxis/ui/widgets/MiuixBaseWidget.kt  (206 行 / 6.7 KB)
  - fun MiuixSwitchWidget,fun MiuixCheckboxWidget,fun MiuixMultiApkCheckboxWidget

## ui/widgets/src/main/java/dev/praxis/ui/widgets/MiuixBlockingLoadingDialog.kt  (34 行 / 1.1 KB)
> // SPDX-License-Identifier: GPL-3.0-only
  - fun MiuixBlockingLoadingDialog

## ui/widgets/src/main/java/dev/praxis/ui/widgets/MiuixButtons.kt  (143 行 / 5.3 KB)
  - fun MiuixBackButton,fun MiuixTextActionButton,fun SidebarActionButton

## ui/widgets/src/main/java/dev/praxis/ui/widgets/MiuixCards.kt  (194 行 / 6.5 KB)
  - private fun TipCard,fun WarningCard,fun MiuixSettingsTipCard,fun MiuixInstallerTipCard,fun MiuixErrorTextBlock

## ui/widgets/src/main/java/dev/praxis/ui/widgets/MiuixCharacterSwitcher.kt  (113 行 / 4.6 KB)
  - fun MiuixCharacterSwitcher,private fun String.toChatHeaderName,private const val CHAT_HEADER_NAME_MAX,private val CHAT_HEADER_MAX_WIDTH,private val CHAT_HEADER_NAME_WIDTH

## ui/widgets/src/main/java/dev/praxis/ui/widgets/MiuixDialog.kt  (173 行 / 6.9 KB)
> // 结构与数值移植自 InstallerX-Revived ui/page/miuix/widgets/MiuixDialog.kt（GPL-3.0-only）。
  - fun MiuixConfirmDialog,fun <T> MiuixSelectionDialog

## ui/widgets/src/main/java/dev/praxis/ui/widgets/MiuixDraggableList.kt  (185 行 / 7.3 KB)
> // SPDX-License-Identifier: GPL-3.0-only
  - fun <T> MiuixDraggableList

## ui/widgets/src/main/java/dev/praxis/ui/widgets/MiuixDropDownItem.kt  (50 行 / 1.7 KB)
  - fun DropdownItem

## ui/widgets/src/main/java/dev/praxis/ui/widgets/MiuixNavigationItemWidget.kt  (36 行 / 1.2 KB)
> // SPDX-License-Identifier: GPL-3.0-only
  - fun MiuixNavigationItemWidget

## ui/widgets/src/main/java/dev/praxis/ui/widgets/MiuixNumPickerWidget.kt  (116 行 / 4.1 KB)
> // SPDX-License-Identifier: GPL-3.0-only
  - fun MiuixIntNumberPickerWidget

## ui/widgets/src/main/java/dev/praxis/ui/widgets/MiuixSelectableRow.kt  (61 行 / 2.1 KB)
> // 结构与数值移植自 InstallerX-Revived ui/page/miuix/widgets/MiuixDialog.kt 内的 SelectableRow（GPL-3.0-only）。
  - fun MiuixSelectableRow

## ui/widgets/src/main/java/dev/praxis/ui/widgets/MiuixSidebar.kt  (842 行 / 40.9 KB)
  - fun MiuixSidebar,private fun SidebarPanel,private fun SidebarHeader,fun SidebarSessionRow,fun SidebarSectionLabel,fun SidebarEmptyState,private val SIDEBAR_WIDTH_FRACTION: Float,private val SIDEBAR_MAX_WIDTH: Dp,private const val SIDEBAR_DIM_ALPHA: Float,private const val SIDEBAR_HORIZONTAL_DOMINANCE: Float,fun sidebarEasedProgress,private const val SIDEBAR_SETTLE_THRESHOLD: Float,private const val SIDEBAR_VELOCITY_PROJECTION_MS: Float

## ui/widgets/src/main/java/dev/praxis/ui/widgets/MiuixSmallTitleDropDown.kt  (198 行 / 9.0 KB)
> // Copyright 2025, miuix-kotlin-multiplatform contributors
  - fun MiuixDropdown

## ui/widgets/src/main/java/dev/praxis/ui/widgets/MiuixTextField.kt  (101 行 / 4.4 KB)
  - fun MiuixHintTextField

## ui/widgets/src/main/java/dev/praxis/ui/widgets/PraxisWebViewSheet.kt  (313 行 / 14.5 KB)
  - fun PraxisWebViewSheet,private const val PAGE_COLOR_PROBE,internal fun parsePageColor

## ui/widgets/src/main/java/dev/praxis/ui/widgets/ProgressButton.kt  (149 行 / 6.0 KB)
> // Copyright 2025-2026, compose-miuix-ui contributors
  - fun ProgressButton,object ProgressButtonDefaults,data class ProgressButtonColors

## ui/widgets/src/main/java/dev/praxis/ui/widgets/SeedColorGrid.kt  (220 行 / 8.5 KB)
  - fun SeedColorGrid,private fun SeedColorSwatch

## ui/widgets/src/main/java/dev/praxis/ui/widgets/SettingsSectionHeader.kt  (106 行 / 4.2 KB)
  - fun SettingsSectionHeader

## ui/widgets/src/main/java/dev/praxis/ui/widgets/StreamingText.kt  (84 行 / 3.1 KB)
  - fun StreamingText,private fun BlinkingCursor

## ui/widgets/src/main/java/dev/praxis/ui/widgets/markdown/MarkdownParseCache.kt  (151 行 / 6.7 KB)
> * Markdown 解析结果缓存（跨组合生效）。
  - object MarkdownParseCache

## ui/widgets/src/main/java/dev/praxis/ui/widgets/markdown/MarkdownParser.kt  (351 行 / 14.1 KB)
> * 轻量 Markdown 解析器（块级 + 行内）。
  - object MarkdownParser,sealed interface MdBlock,sealed interface MdInline

## ui/widgets/src/main/java/dev/praxis/ui/widgets/markdown/StreamingMarkdownState.kt  (140 行 / 5.8 KB)
> * 流式 Markdown 的**增量**解析。
  - internal class StreamingMarkdownState

## ui/widgets/src/main/java/dev/praxis/ui/widgets/theme/MiuixSheetColors.kt  (35 行 / 1.2 KB)
> // 移植自 InstallerX-Revived ui/theme/MiuixSheetColors.kt（GPL-3.0-only）。
  - private val SheetCardDark,fun miuixSheetCardColor,fun miuixSheetCardColors

## ui/widgets/src/main/res/drawable/ic_praxis_drag_handle.xml  (11 行 / 0.6 KB)


## ui/widgets/src/test/java/dev/praxis/ui/widgets/markdown/MarkdownParserTest.kt  (202 行 / 7.6 KB)
> * Markdown 解析器的回归护栏。
  - class MarkdownParserTest

## ui/widgets/src/test/java/dev/praxis/ui/widgets/markdown/StreamingMarkdownStateTest.kt  (135 行 / 5.7 KB)
> * 流式增量解析。
  - class StreamingMarkdownStateTest