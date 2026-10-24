@file:OptIn(ExperimentalScrollBarApi::class)

package dev.praxis.feature.settings.info

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.praxis.ui.design.component.LocalScrollBottomLimit
import dev.praxis.ui.design.component.LocalTopBarInset
import dev.praxis.ui.design.effect.BgEffectBackground
import dev.praxis.ui.navigation.LocalNavigator
import dev.praxis.ui.navigation.PraxisRoute
import dev.praxis.ui.icons.PraxisIcons
import dev.praxis.ui.theme.LocalPraxisIsDark
import dev.praxis.ui.widgets.MiuixNavigationItemWidget
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 关于页 —— **1:1 照 InstallerX `MiuixAboutPage.kt`（892 行）**。
 *
 * ## 本轮从「半个上游」补成完整对齐（2026-10-23）
 *
 * 上一版只抄了 hero 的形状，漏掉了**让它「像上游」的全部机制**。逐项对照：
 *
 * | 上游有 | 上一版 Praxis | 后果 |
 * |---|---|---|
 * | `Scaffold` + `SmallTopAppBar`（自己的一层） | 无，复用 `PraxisPage` 顶栏 | 顶栏标题常驻、`titleColor` 不随滚动淡入，滚动时标题与 hero 文字叠影 |
 * | 顶栏 `textureBlur(backdrop)` + `color` 随进度切透明 | 无 | 列表滚到顶栏下时是一条硬边，没有磨砂 |
 * | hero 图标 `textureBlur` + `contentBlendMode = DstIn`（玻璃 logo） | 实色 `primaryContainer` 色块 + 「思」字 | 少了「图标是玻璃、透出背景流动渐变」这个最显眼的观感 |
 * | spacer 高度 `logoHeightDp + 52 + logoPadding.top - listPadding.top + 126.dp` | `heroDp + 52.dp` | **少 126dp**：hero 与第一张卡片挤在一起 |
 * | `VerticalScrollBar`（`rememberScrollBarAdapter`） | 无 | 长列表没有滚动条，不知道自己在哪 |
 * | `scrollEndHaptic()` + `overScrollVertical()` | 无 | 滚到底没有触感反馈、没有回弹 |
 * | `Card` 的 `textureBlur` + `colors` 用 `Color.Transparent` | 默认色卡 | 卡片是实色块，玻璃质感全无 |
 * | `hero` 图标 88dp + 内层 `requiredSize(160.dp)` blur 图 | 88dp 实色 | 玻璃层需要**比容器大**才有渐变边 |
 *
 * ## 为什么 hero 必须「画在 LazyColumn 之外」
 *
 * 上游把 hero 画在 `BgEffectBackground` 的 Box 里、列表之上，列表第一条只放
 * 一个**透明 spacer** 撑出 hero 的高度。所以：
 *
 *  - 滚动时 hero **原地不动**（不跟列表走），靠 `graphicsLayer` 的
 *    alpha/scale 分层消失 —— 观感是「被顶栏吸走」；
 *  - 若把 hero 当普通 item，一滑就跟着走，立刻变成「一张普通卡片」。
 *
 * 三层的阈值是 `0.35 / 0.20 / 0.05`（图标 / 应用名 / 版本号），
 * 窗口都是 0.15。**顺序不能反**：版本号最先消失（最次要），图标最后。
 *
 * ## 为什么 spacer 要 `- listPadding.top + 126.dp`
 *
 * 上游那行：
 *
 * ```kotlin
 * height(logoHeightDp + 52.dp + logoPadding.top - listContentPadding.top + 126.dp)
 * ```
 *
 * - `logoHeightDp`：hero 实测内容高（`onSizeChanged` 挂在 padding 之后）；
 * - `+ 52.dp`：hero 顶部那层额外留白；
 * - `+ logoPadding.top - listContentPadding.top`：两侧顶栏高度差（Praxis 两者同源，此处为 0）；
 * - `+ 126.dp`：**hero 与第一张卡片之间的呼吸**。上一版漏掉这一项，
 *   卡片紧贴版本号，看起来像「挤在一起」。
 *
 * ## 与上游的业务差异（不是偷工，是 Praxis 没有对应能力）
 *
 * | 上游功能 | 为什么不给 |
 * |---|---|
 * | 检查更新 / 直接更新 | Praxis 没有更新通道，给了就是死按钮 |
 * | 调试组（日志开关 / 导出日志） | 日志页已在设置首页独立入口（`SettingsEntry.Log`），重复入口是「入口乱」的来源 |
 * | 开发者的 Texture Set 面板（长按 logo 开） | 那是上游调参用的 debug 面板，Praxis 的玻璃参数已固定在 `BgEffectBackground` / `AppScaffold` 里 |
 * | `uiState.appIcon`（运行时读本 App 图标） | Praxis 有 `ic_launcher_foreground` 单色矢量，直接用它做 monochrome 玻璃图标，比读 Bitmap 更贴合上游观感 |
 *
 * 结论：**版式、动效、玻璃层次、滚动条、触感全部按上游**；
 * 只砍掉 Praxis 无法落地的功能入口。
 *
 * @param versionName 版本名（来自 `BuildConfig`）。
 * @param versionCode 版本号。
 */
@Composable
fun AboutScreen(
    versionName: String,
    versionCode: Int,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalNavigator.current
    val uriHandler = LocalUriHandler.current
    val density = LocalDensity.current

    // ## 玻璃采样源：**必须先过 `GlassBackend` 分流**（2026-10-23 修，低版本会崩）
    //
    // 旧写法是 `val blurSupported = isRuntimeShaderSupported()`。
    // 两个问题：
    //
    //  1. **低版本直接 `NoClassDefFoundError`**。`miuix-blur` 的
    //     `AndroidManifest.xml` 声明 `minSdkVersion="33"`；在 API 28~32 的机器上
    //     类加载器一碰到 `top.yukonga.miuix.kmp.blur.*` 就抛错。
    //     本项目 minSdk 是 28，所以这台分支**真的会走到**。
    //     `AppScaffold` 早就为此分了 `GlassBackend.useLowPipeline` 两套管线，
    //     本页是唯一没接口径的地方（`grep isRuntimeShaderSupported` 里
    //     只有这里没有前置分流）。
    //  2. `GlassBackend` 还有 `FORCE_LOW` / `FORCE_NONE` 两个**调试档**
    //     （用户要求「测试可以开一个临时开关」）。直接读 SDK 会绕开它们 ——
    //     切到 FORCE_LOW 后底栏变了、关于页还是玻璃，对照就失效。
    //
    // 所以判据统一走 `GlassBackend`：
    //   `useLowPipeline` = AUTO 且 SDK < 33、或强制低版本、或强制无玻璃。
    // 三种情况都意味着「不要碰 miuix-blur 的符号」。
    val blurSupported = !dev.praxis.ui.design.glass.GlassBackend.useLowPipeline

    // 顶栏与 hero 共用的采样源。**只有模糊可用时才建**：
    // 低版本建了也用不上，白占一层 GraphicsLayer。
    val backdrop = if (blurSupported) {
        rememberLayerBackdrop {
            drawContent()
        }
    } else {
        null
    }

    val listState = rememberLazyListState()

    // ## 为什么必须是**两个**高度变量（踩过的坑，别合并）
    //
    // 上游也是两个：`logoHeightDp`（hero 内容高）与 `logoHeightPx`（spacer 高）。
    //
    // 我第一版只用一个 `heroHeightPx`，既当 spacer 高度的输入、又当它的输出：
    //
    // ```kotlin
    // spacer.height = heroHeightPx + 178.dp
    // spacer.onSizeChanged { heroHeightPx = it.height }   // ← 写回同一个变量
    // // 下一帧：spacer.height = (旧值 + 178) + 178 → 无限长高
    // ```
    //
    // 结果进关于页后列表头越撑越大，最后把内容全顶出屏幕。
    // 分开之后职责清晰：hero 的高只来自 hero 自己，spacer 的高只用于算进度。

    /** hero **内容**的实测高度（`onSizeChanged` 挂在 padding 之后，不含外部留白）。 */
    var heroContentHeightPx by remember { mutableIntStateOf(0) }

    /** spacer 的实测高度 = 「滚过 hero 需要多少像素」，作为进度分母。 */
    var spacerHeightPx by remember { mutableIntStateOf(0) }

    val scrollProgress by remember {
        derivedStateOf {
            if (spacerHeightPx <= 0) {
                0f
            } else {
                val index = listState.firstVisibleItemIndex
                val offset = listState.firstVisibleItemScrollOffset
                // 滚过第一条之后一律 1f：此时 hero 必须**完全**消失，
                // 否则会在内容上方留一层半透明残影。
                if (index > 0) 1f else (offset.toFloat() / spacerHeightPx).coerceIn(0f, 1f)
            }
        }
    }

    val topInset = LocalTopBarInset.current
    val scrollLimit = LocalScrollBottomLimit.current
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // 列表内容 padding。
    //
    // 上游用 `innerPadding`（自己的 Scaffold）+ safeDrawing 的左右 inset。
    // Praxis 的顶栏在 `PraxisPage` 里，高度经 `LocalTopBarInset` 下发 ——
    // 语义等价。
    val listContentPadding = PaddingValues(
        top = topInset,
        // 底部：手势条 + 浮动底栏限位 + 上游那 12dp 呼吸。
        bottom = navBarBottom + scrollLimit + 12.dp,
    )

    // 图标玻璃配色（照上游 `logoBlend`）。
    //
    // ## 这三条 blend 各自负责什么
    //
    // 上游用三层不同 blendMode 叠出「金属玻璃」观感：
    //  - `ColorDodge`（浅灰）：压出高光边缘；
    //  - `LinearLight`（更浅）：中间调过渡；
    //  - `Lab`（黄绿）：**这才是那个特有的青绿反光**。
    //
    // 删掉第三条会变成一块灰玻璃 —— 观感差很多。
    // 深色/浅色各一套，因为同一组 blend 在两种底色上结果完全不同。
    //
    // 暗色判定用 Praxis 自己的 CompositionLocal，而不是 `MiuixTheme.colorScheme.isDark`
    // —— 后者在 miuix 0.9.4 的 `Colors` 上**不存在**（javap 实测只有颜色字段）。
    // 这也是 `MiuixCards.kt` / `MiuixSheetColors.kt` 的既有口径。
    //
    // ## 为什么整段包在 `if (blurSupported)` 里面（2026-10-23 修，低版本会崩）
    //
    // `BlendColorEntry(...)` 与 `BlurBlendMode.ColorDodge` 都是 miuix-blur 的符号，
    // 而 `miuix-blur` 的 `AndroidManifest.xml` 写的是 `minSdkVersion="33"`。
    // 本项目 minSdk 28 —— 在 API 28~32 上**只要执行到这一行**就
    // `NoClassDefFoundError`（不是「效果不生效」，是崩溃）。
    //
    // 旧写法把 `logoBlend` 无条件 `remember` 出来，于是「低版本降级路径」
    // 从头就没机会跑：崩在构造参数这一步。
    // 包进 `if` 之后，低版本构造都不会发生，两个消费点都改成 `?.…orEmpty()`。
    val isDark = LocalPraxisIsDark.current
    val logoBlend: List<BlendColorEntry>? = if (blurSupported) {
        remember(isDark) {
            if (isDark) {
                listOf(
                    BlendColorEntry(Color(0xe6a1a1a1), BlurBlendMode.ColorDodge),
                    BlendColorEntry(Color(0x4de6e6e6), BlurBlendMode.LinearLight),
                    BlendColorEntry(Color(0xff1af500), BlurBlendMode.Lab),
                )
            } else {
                listOf(
                    BlendColorEntry(Color(0xcc4a4a4a), BlurBlendMode.ColorBurn),
                    BlendColorEntry(Color(0xff4f4f4f), BlurBlendMode.LinearLight),
                    BlendColorEntry(Color(0xff1af200), BlurBlendMode.Lab),
                )
            }
        }
    } else {
        null
    }

    // 卡片玻璃配色：用 surface 以 60% 叠在采样源上（上游 `surface` 常量）。
    // 同样只在模糊可用时构造（理由见上）。
    val cardBlend: List<BlendColorEntry>? = if (blurSupported) {
        val cardSurface = MiuixTheme.colorScheme.surface.copy(alpha = 0.6f)
        remember(cardSurface) {
            listOf(BlendColorEntry(cardSurface, BlurBlendMode.SrcOver))
        }
    } else {
        null
    }

    // 背景：AGSL 流动渐变，随滚动淡出（与 hero 联动）。
    //
    // `alpha = { 1f - scrollProgress }` 是关键：顶部时不透明，滚到底完全消失。
    // 否则滚到列表区会有彩色在卡片之间流动，观感很脏。
    //
    // ## `bgModifier` 挂采样源 —— 这是玻璃能看见东西的唯一办法（2026-10-23 对齐上游）
    //
    // 上游 `MiuixAboutPage.kt:393`：
    //
    // ```kotlin
    // bgModifier = backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier,
    // ```
    //
    // 采样源挂在**背景层**（本组件内部的 `Spacer`，它带着 `bgEffectDraw`）。
    // Modifier 链是「外到内」画：`layerBackdrop` 在 `bgEffectDraw` 之前，
    // 所以它的 `drawContent()` 会把**渐变背景**录下来 —— 卡片与 hero 图标的
    // 玻璃采样到的就是那片流动彩光，透出来才是真玻璃。
    //
    // ## 旧写法为什么是错的（真会出观感缺陷）
    //
    // 之前把 `layerBackdrop` 挂在**列表那个 Box** 上（相当于录「列表自己」）。
    // 于是卡片采样到的是「自己和邻居卡片」：
    //  - 玻璃里看不到背景渐变，只有一层灰；
    //  - 卡片边缘出现「玻璃折射玻璃」的重影（与底栏自采样是同一个毛病）。
    //
    // 这与 `AppScaffold` 里「底栏必须画在采样源之外」是同一条纪律：
    // **采样源只装你要透出来的东西，不装消费者自己**。
    BgEffectBackground(
        dynamicBackground = true,
        modifier = modifier.fillMaxSize(),
        bgModifier = backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier,
        alpha = { 1f - scrollProgress },
    ) {
        // ── 层 1：hero（**不参与滚动**，画在列表之前）────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = topInset + 52.dp)
                .onSizeChanged { heroContentHeightPx = it.height },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 图标：0.35 起淡出。
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(88.dp)
                    .graphicsLayer {
                        val t = layerProgress(scrollProgress, start = 0.35f)
                        alpha = 1f - t
                        scaleX = 1f - t * 0.05f
                        scaleY = 1f - t * 0.05f
                    },
            ) {
                if (backdrop != null && logoBlend != null) {
                    // ## 为什么 blur 图要 `requiredSize(160.dp)`
                    //
                    // 玻璃效果靠 `textureBlur` 采样背景后按 blend 重绘。
                    // 若图片尺寸 == 容器尺寸（88dp），采样与重绘的边界重合，
                    // 边缘会被裁成直角硬边 —— 玻璃的「渐变消散」只在
                    // **比容器更大**的画布上才表现得出。这也是为什么
                    // 上游这里是 160 而不是 88。
                    Image(
                        imageVector = PraxisIcons.Logo,
                        contentDescription = null,
                        modifier = Modifier
                            .requiredSize(160.dp)
                            .textureBlur(
                                backdrop = backdrop,
                                shape = RoundedCornerShape(16.dp),
                                blurRadius = 200f,
                                noiseCoefficient = BlurDefaults.NoiseCoefficient,
                                colors = BlurColors(blendColors = logoBlend),
                                // DstIn：只保留图标轮廓内的玻璃，轮廓外全透明。
                                // 换成 SrcOver 会得到一个 160dp 的方块。
                                contentBlendMode = BlendMode.DstIn,
                                enabled = true,
                            ),
                    )
                } else {
                    // 低版本（无 RuntimeShader）降级：普通着色图标。
                    // 不用色块垫底 —— 那个「思」字占位是上一版的做法，
                    // 与上游「单色图标」的观感差得更远。
                    Image(
                        imageVector = PraxisIcons.Logo,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                    )
                }
            }

            // 应用名：0.20 起淡出。
            val nameModifier = Modifier
                .padding(top = 12.dp, bottom = 5.dp)
                .graphicsLayer {
                    val t = layerProgress(scrollProgress, start = 0.20f)
                    alpha = 1f - t
                    scaleX = 1f - t * 0.05f
                    scaleY = 1f - t * 0.05f
                }
                .then(
                    if (backdrop != null && logoBlend != null) {
                        Modifier.textureBlur(
                            backdrop = backdrop,
                            shape = RoundedCornerShape(16.dp),
                            blurRadius = 150f,
                            noiseCoefficient = BlurDefaults.NoiseCoefficient,
                            colors = BlurColors(blendColors = logoBlend),
                            contentBlendMode = BlendMode.DstIn,
                            enabled = true,
                        )
                    } else {
                        Modifier
                    },
                )
            Text(
                text = "Praxis",
                modifier = nameModifier,
                fontWeight = FontWeight.Bold,
                fontSize = 35.sp,
                color = MiuixTheme.colorScheme.onSurface,
            )

            // 版本：0.05 起淡出（**最早消失**）。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        val t = layerProgress(scrollProgress, start = 0.05f)
                        alpha = 1f - t
                        scaleX = 1f - t * 0.05f
                        scaleY = 1f - t * 0.05f
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "思行 · $versionName ($versionCode)",
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // ── 层 2：内容列表 ────────────────────────────────────────────
        //
        // ## 这里**不再挂 `layerBackdrop`**（2026-10-23 对齐上游）
        //
        // 采样源已经在上面的 `BgEffectBackground(bgModifier = ...)` 里挂了。
        // 本层若再挂一次，等于把「列表自己（连卡片带滚动条）」也录进同一个
        // GraphicsLayer —— 即自采样：卡片玻璃会折射到卡片自身，
        // 边缘出现「玻璃套玻璃」的重影，而背景渐变反而被挡住看不见。
        //
        // 上游 `MiuixAboutPage.kt` 也是这样：`layerBackdrop` 只出现在
        // 背景层（第 393 行）与顶栏层（第 268 行），**列表从不挂**。
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    // 滚到底的触感反馈（上游同款）。
                    .scrollEndHaptic()
                    // 越界回弹（上游同款）。没有它，边界处是硬停。
                    .overScrollVertical(),
                contentPadding = listContentPadding,
            ) {
                // 透明 spacer：撑出 hero 的高度，让第一张卡片落在 hero 下方。
                //
                // 高度见 KDoc 里对 `+126.dp` 的说明 —— 那是上一版漏掉的呼吸。
                item(key = "heroSpacer") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(
                                (with(density) { heroContentHeightPx.toDp() } + 52.dp + 126.dp)
                                    .coerceAtLeast(0.dp),
                            )
                            .onSizeChanged { spacerHeightPx = it.height },
                    )
                }

                item(key = "about_content") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // ── 关于 ──
                        SmallTitle("关于")
                        GlassCard(
                            backdrop = backdrop,
                            blend = cardBlend,
                        ) {
                            MiuixNavigationItemWidget(
                                title = "开源许可",
                                description = "本应用依赖的开源项目与许可协议",
                                onClick = {
                                    navigator.push(PraxisRoute.SettingsOpenSourceLicense)
                                },
                            )
                            MiuixNavigationItemWidget(
                                title = "设计参照",
                                description = "壳与动效来自 apixiaoyuan，控件与底栏来自 InstallerX",
                                onClick = {
                                    navigator.push(PraxisRoute.SettingsOpenSourceLicense)
                                },
                            )
                            MiuixNavigationItemWidget(
                                title = "项目主页",
                                description = "源码、问题反馈与更新日志",
                                onClick = {
                                    uriHandler.openUri("https://github.com/sxd91/Praxis")
                                },
                            )
                        }

                        // ── 第三方组件 ──
                        SmallTitle("第三方组件")
                        GlassCard(
                            backdrop = backdrop,
                            blend = cardBlend,
                        ) {
                            LicenseRow("miuix", "Apache-2.0")
                            LicenseRow("Kyant0/AndroidLiquidGlass", "Apache-2.0")
                            LicenseRow("InstallerX-Revived", "GPL-3.0-only")
                            LicenseRow("apixiaoyuan", "—")
                            LicenseRow("AndroidX / Compose", "Apache-2.0")
                            LicenseRow("Koin", "Apache-2.0")
                            LicenseRow("Room / DataStore", "Apache-2.0")
                        }

                        Spacer(Modifier.height(12.dp))
                    }
                }
            }

            // 滚动条：长列表必须能看出自己在哪（上游同款）。
            VerticalScrollBar(
                adapter = rememberScrollBarAdapter(listState),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight(),
                trackPadding = listContentPadding,
            )
        }
    }
}

/**
 * 玻璃卡片：有采样源时走 `textureBlur`，否则退回普通实色 Card。
 *
 * ## 为什么 `colors` 两个参数都要传 Transparent
 *
 * 上游：
 * ```kotlin
 * colors = CardDefaults.defaultColors(
 *     if (backdrop != null && blurEnable) Color.Transparent else surfaceContainer,
 *     Color.Transparent,
 * )
 * ```
 *
 * 第一个是 container（卡片底），第二个是 contentColor。
 * 玻璃模式必须把 container 设成 **Transparent** ——
 * 否则卡片的实色底会盖在 `textureBlur` 画出来的玻璃之上，
 * 结果「看起来有模糊但完全看不到背景」。
 *
 * contentColor 传 Transparent 是为了让内部控件的颜色由 `MiuixTheme` 自己决定，
 * 不被 Card 的局部 CompositionLocal 覆盖。
 */
@Composable
private fun GlassCard(
    backdrop: LayerBackdrop?,
    blend: List<BlendColorEntry>?,
    content: @Composable () -> Unit,
) {
    // ## 为什么 `blend` 可空且必须与 `backdrop` 同时判（2026-10-23）
    //
    // 低版本（API 28~32）两个都是 null —— 那时 `BlurColors(...)` 这一类
    // miuix-blur 符号根本不能碰（`NoClassDefFoundError`）。
    // 只判 `backdrop != null` 会在 `blend` 为 null 时依旧进到
    // `BlurColors(blendColors = blend)`，编译期就过不去（类型不符）。
    //
    // 反过来只判 `blend != null` 也不够：`textureBlur` 的 `backdrop` 是非空参数。
    // 两者必须**同时**有值才走玻璃。
    val glass = backdrop != null && blend != null
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .then(
                if (glass) {
                    Modifier.textureBlur(
                        backdrop = backdrop!!,
                        shape = RoundedCornerShape(16.dp),
                        // 卡片的模糊半径比图标小：大半径在卡片这种大面积上
                        // 会把背景糊成一片纯色，看不出「玻璃」只看得出「脏」。
                        blurRadius = 60f,
                        noiseCoefficient = BlurDefaults.NoiseCoefficient,
                        colors = BlurColors(blendColors = blend!!),
                        enabled = true,
                    )
                } else {
                    Modifier
                },
            ),
        colors = CardDefaults.defaultColors(
            if (glass) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer,
            Color.Transparent,
        ),
    ) {
        content()
    }
}

/**
 * 单层淡出进度。
 *
 * `(p - start) / 0.15` 截到 0..1：滚到 `start + 0.15` 时该层完全消失，
 * 之后不再变化（否则会算出负数 alpha 直接闪黑）。
 *
 * 窗口固定 0.15 与上游一致 —— 相邻两层因此有 0.15 的重叠，
 * 那段重叠是「连续淡出」而非「三下闪烁」的来源。
 */
private fun layerProgress(p: Float, start: Float): Float =
    ((p - start) / 0.15f).coerceIn(0f, 1f)

/**
 * 一行「名称 — 许可」。
 *
 * 这一组是**纯展示**，点了不该去哪。用带箭头的 `MiuixNavigationItemWidget`
 * 就是在骗用户（上游也只在这里用纯 `Row`）。
 */
@Composable
private fun LicenseRow(name: String, license: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = name,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = license,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
