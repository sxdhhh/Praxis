package dev.praxis.ui.theme

import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * 引导页（SetupWizard）的**字体族映射** —— 1:1 对齐 HyperCeiler provision 模块。
 *
 * ## 原版到底用了哪几种字体（取证）
 *
 * | 用途 | 原版写法 | 出处 |
 * |---|---|---|
 * | 页面标题 | `android:fontFamily="sans-serif"` | `styles.xml` → `ProvisionPageTitleTextStyle` |
 * | 副标题 | `android:fontFamily="sans-serif"` | `ProvisionPageSubTitleTextStyle` |
 * | 服务名 / 清单行标题 | `android:fontFamily="sans-serif-medium"` | `ProvisionServiceTitleTextStyle` |
 * | 「我已阅读并同意」 | `android:fontFamily="sans-serif-medium"` | `provision_page_layout.xml` 的 `text_agree` |
 * | 完成页状态文字 | `android:fontFamily="mipro-regular"` | `provision_congratulation_layout.xml` 的 `system_state_text` |
 *
 * 原版**没有**打包任何 `.ttf` / `.otf`：
 * `library/provision/src/main/res/` 下没有 `font/` 目录，`raw/` 里只有
 * 四个 `.glsl`。全部靠系统字体名解析。这一点决定了我们的实现方式（见下）。
 *
 * ## 为什么 `sans-serif` 与 `sans-serif-medium` 必须分开
 *
 * 它们在 Android 上是**两个不同的 Typeface 家族名**，不是同一个家族的两种
 * 字重。`sans-serif-medium` 在 AOSP 上映射到 `Roboto-Medium.ttf`，
 * 而 `sans-serif` + `FontWeight.Medium` 在 MIUI/HyperOS 上会被
 * 字体覆盖机制重定向到 `MiSans-Medium` —— 两者字宽不同。
 *
 * 差异具体表现在哪：清单行标题较长时的省略号位置、以及
 * `provision_service_name_text_size`（16.7sp）标题的断行点。
 * 用 weight 去近似，在小米机器上会与原版错开。
 *
 * ## 为什么用 `Font(DeviceFontFamilyName(...))` 而不是 `FontFamily(Typeface)`
 *
 * Compose 确实提供了 `FontFamily(Typeface)`（见 `FontFamilyKt`），
 * 但它把「一个已经解析好的 Typeface」整个塞进家族，**丢失了字重与字形信息**：
 * 同一个 `FontFamily` 下再想表达 Medium 就得再建一个 Typeface，
 * 而 `TextStyle` 的 `fontWeight` 参数对它不再生效（家族里只有一个 face）。
 *
 * `Font(DeviceFontFamilyName(name), weight, style)` 是**官方推荐的系统字体入口**
 * （`androidx.compose.ui.text.font` 的 Android 源集工厂）。它保留
 * weight/style，家族内部可以按需再补其他 face，语义与 XML 的
 * `android:fontFamily` 完全对应。
 *
 * ## AOSP 上会不会崩
 *
 * 不会。`DeviceFontFamilyName` 只在**创建家族时**记录名字字符串，
 * 真正解析发生在加载期；系统找不到该家族时回落到默认字体
 * （与 `Typeface.create(name, …)` 的回落行为一致）。
 * 原版在同样设备上也是回落 —— 我们保持行为一致，而不是
 * 「为了保险把 MiSans 打进 APK」（那会多 5~10MB，且字体授权不明）。
 */
object PraxisProvisionFonts {

    /**
     * 页面标题 / 副标题 —— 原版 `sans-serif`。
     *
     * 对应 `ProvisionPageTitleTextStyle`（32sp）与
     * `ProvisionPageSubTitleTextStyle`（14sp）。
     */
    val SansSerif: FontFamily = systemFontFamily("sans-serif")

    /**
     * 清单行标题 / 服务名 / 「我已阅读并同意」—— 原版 `sans-serif-medium`。
     *
     * 对应 `ProvisionServiceTitleTextStyle`（16.7sp）与
     * `provision_page_layout.xml` 的 `text_agree`。
     */
    val SansSerifMedium: FontFamily = systemFontFamily("sans-serif-medium")

    /**
     * 完成页状态文字 —— 原版 `mipro-regular`（小米系统字体）。
     *
     * 对应 `system_state_text`（24sp、`#BF000000`）。
     */
    val MiProRegular: FontFamily = systemFontFamily("mipro-regular")

    /**
     * 按系统字体族名建一个单 face 家族。
     *
     * `weight = Normal` 是有意的：家族名本身已经编码了粗细
     * （`sans-serif-medium` 就是 Medium），这里再写 Medium 会在部分
     * ROM 上触发一次合成加粗（fake bold），笔画比原版粗一档。
     */
    private fun systemFontFamily(name: String): FontFamily =
        FontFamily(
            Font(
                familyName = DeviceFontFamilyName(name),
                weight = FontWeight.Normal,
            ),
        )
}