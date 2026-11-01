package dev.praxis.preview

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import dev.praxis.core.model.settings.ApiProvider
import dev.praxis.core.model.settings.ModelSettings
import dev.praxis.core.model.settings.ThinkingEffort
import dev.praxis.feature.chat.ChatScreen
import dev.praxis.feature.chat.ChatState
import dev.praxis.feature.chat.ChatBubble
import dev.praxis.feature.chat.BubbleRole
import dev.praxis.ui.theme.PraxisTheme
// ThemeSettings / ThemeMode 定义在 core:model，不在 ui:theme。
// 曾误写成 `dev.praxis.ui.theme.ThemeSettings` 导致 preview 模块编译失败
// （Unresolved reference），而 build workflow 不编译 preview，所以肉眼难发现。
import dev.praxis.core.model.settings.ThemeMode
import dev.praxis.core.model.settings.ThemeSettings
import org.junit.Rule
import org.junit.Test

/**
 * 浏览器预览的渲染入口。
 *
 * ## 这一层在做什么
 *
 * Paparazzi 用 layoutlib（Android 的渲染引擎，也就是 Android Studio
 * Preview 背后的那个）在 **JVM 上**把 Composable 画成 PNG。没有模拟器、
 * 没有 adb、没有真机 —— 所以能被任意脚本批量调用。
 *
 * 产出落在 `preview/build/reports/paparazzi/images/`，
 * 由 `tools/preview_server.py` 喂给浏览器。
 *
 * ## 为什么渲染参数写在这里而不是外部配置
 *
 * 预览的意义是「看到的东西和真机一致」。设备参数（尺寸 / 密度 /
 * 深色模式）一旦变成命令行开关，就会出现「你本地看是 420dp 宽、
 * CI 上是 360dp 宽」的分歧。写死成一组确定的设备，所有人看到同一张图。
 *
 * 目前固定 411×891dp / xxhdpi —— 这是最主流的 Android 尺寸，
 * 也是本项目 UI 按它调出来的那个宽度。
 *
 * ## ⚠️ 它只能在 x86_64 上跑
 *
 * layoutlib 的 native 渲染库只发布 x86_64。本机是 aarch64，所以
 * 在本地直接跑这个测试会失败在 `UnsatisfiedLinkError`。
 * 正确用法是让它跑在 CI 的 ubuntu-latest 上（见 `.github/workflows`）。
 */
class ScreenPreviewTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenWidth = 411,
            screenHeight = 891,
            density = Density.XXHIGH,
            orientation = ScreenOrientation.PORTRAIT,
            // 圆角给 0：窗口边界不参与 UI 布局，留着只会在图片四角
            // 留下一圈透明 —— 浏览器里看会以为渲染失败。
            roundedCorners = false,
        ),
        // ## 为什么 showSystemUi = false
        //
        // true 会把状态栏/导航栏一起画进来。它们不是本项目的 UI，
        // 而且高度随 Android 版本变化 —— 会让同一个界面的预览图
        // 在不同 layoutlib 版本之间产生无意义的 diff。
        showSystemUi = false,
        theme = "android:Theme.Material.Light.NoActionBar",
    )

    /** 浅色主题的默认设置（与真机首启一致）。 */
    private val lightTheme = ThemeSettings(mode = ThemeMode.Light)

    /** 深色主题 —— 用于检查深色下的对比度问题（那种问题只在深色下暴露）。 */
    private val darkTheme = ThemeSettings(mode = ThemeMode.Dark)

    @Test
    fun chat_empty() {
        paparazzi.snapshot(name = "chat-empty") {
            PraxisTheme(lightTheme) {
                PreviewChatScreen(state = ChatState())
            }
        }
    }

    @Test
    fun chat_conversation() {
        paparazzi.snapshot(name = "chat-conversation") {
            PraxisTheme(lightTheme) {
                PreviewChatScreen(state = sampleConversation())
            }
        }
    }

    @Test
    fun chat_conversation_dark() {
        paparazzi.snapshot(name = "chat-conversation-dark") {
            PraxisTheme(darkTheme) {
                PreviewChatScreen(state = sampleConversation())
            }
        }
    }

    /**
     * 滚动条的形态验收。
     *
     * ## 为什么单独出一张
     *
     * 2026-10-31 重构过那根 rail（原来圆点 4dp 嵌在 6dp 轨道里、按钮压在
     * 导航胶囊上）。这类问题**静态图里能看出来**，而真机上要滚到中间
     * 才看得到 —— 每次改都要手动滑一遍太慢。固定一张「未到底」的图，
     * 改完直接对比。
     *
     * 内容足够长是必要条件：只有能滚动时 rail 与回底按钮才会出现。
     */
    @Test
    fun chat_scroll_affordance() {
        paparazzi.snapshot(name = "chat-scroll-affordance") {
            PraxisTheme(lightTheme) {
                PreviewChatScreen(state = longConversation())
            }
        }
    }

    /**
     * 模型设置页。
     *
     * 它的分区最多（主模型 / 上下文 / 采样 / 进阶），也最容易在
     * 「折叠/展开动画」上出问题。
     */
    @Test
    fun model_settings() {
        paparazzi.snapshot(name = "model-settings") {
            PraxisTheme(lightTheme) {
                dev.praxis.feature.settings.ai.ModelScreen(
                    settings = sampleModelSettings(),
                    apiKeyMasked = "sk-••••••••••••••••3f2a",
                    onSave = { _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
                )
            }
        }
    }

    // ── 假数据 ────────────────────────────────────────────────
    //
    // 全部是纯内存对象，不碰网络、不碰数据库、不碰 Android Context。
    // 这也是预览能跑在纯 JVM 上的前提。

    private fun PreviewChatScreen(state: ChatState) {
        ChatScreen(
            state = state,
            onInputChange = {},
            onSend = {},
            onRetry = {},
            // 不传 onCancel / onSendOrEnqueue / header：
            // 那些会引入 ViewModel 或模型配置，与「只测 UI 形态」无关。
        )
    }

    private fun sampleConversation(): ChatState = ChatState(
        bubbles = listOf(
            ChatBubble(
                id = 1L,
                role = BubbleRole.User,
                text = "帮我看看这个崩溃日志，为什么会空指针？",
                timestamp = 1_700_000_000_000L,
            ),
            ChatBubble(
                id = 2L,
                role = BubbleRole.Assistant,
                text = """
                    崩溃点在 `SettingsBackedProvider.resolve` 里：`settings.first()`
                    返回的 `model` 是空串，后面直接 `substringBefore('/')` 不会崩，
                    但 `baseUrl` 为空时 OkHttp 组装请求会抛 `IllegalArgumentException`。

                    修法是两个：

                    1. 解析后立即校验，空值就抛业务层的 `AiError.NotConfigured`；
                    2. 别让空串进到 provider —— 在设置页保存时就拦掉。

                    第二种更根本，但需要同时处理「用户清空模型池」的场景。
                """.trimIndent(),
                timestamp = 1_700_000_001_000L,
            ),
            ChatBubble(
                id = 3L,
                role = BubbleRole.User,
                text = "那第二种怎么处理清空的情况？",
                timestamp = 1_700_000_002_000L,
            ),
            ChatBubble(
                id = 4L,
                role = BubbleRole.Assistant,
                text = "清空不该被拦 —— 清空是合法状态，只是 `isConfigured` 变 false，" +
                    "聊天页给一个「先去设置里选模型」的空态，而不是让请求发出去。",
                timestamp = 1_700_000_003_000L,
                streaming = true,
            ),
        ),
        input = "那如果用户就是想先清空再重配呢？",
    )

    /** 足够长 —— 让 rail 与回底按钮真的出现。 */
    private fun longConversation(): ChatState = ChatState(
        bubbles = (1..14).map { i ->
            ChatBubble(
                id = i.toLong(),
                role = if (i % 2 == 1) BubbleRole.User else BubbleRole.Assistant,
                text = "第 $i 条消息。这里是一段用来把列表撑长的占位正文，" +
                    "目的是让滚动条与回底按钮进入可见状态。",
                timestamp = 1_700_000_000_000L + i * 1000L,
            )
        },
    )

    private fun sampleModelSettings(): ModelSettings = ModelSettings(
        provider = ApiProvider.OpenAI,
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-4o,gpt-4o-mini",
        modelIndex = 0,
        temperature = 0.7,
        contextLimit = 128_000,
        compactThreshold = 0.75f,
        thinkingEffort = ThinkingEffort.Medium,
    )
}