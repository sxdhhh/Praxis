package dev.praxis.ui.design.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.praxis.ui.design.glass.GlassBackend
import dev.praxis.ui.icons.PraxisIcons
import dev.praxis.ui.theme.LocalPraxisIsDark
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur

/**
 * 引导页的玻璃品牌层 —— **与关于页完全同一套实现**。
 *
 * ## 为什么不能用手写渐变 drawable
 *
 * 上一版把「彩色」做成了 `vector` 里的 `aapt:attr` 三色渐变。那是**静态渐变**：
 * 颜色固定、不随壁纸/取色变化、也不会透出背后流光，看起来像贴上去的
 * 一张彩图。
 *
 * 关于页（`AboutScreen.kt` 第 250–268 / 333–370 行）实际用的是另一套：
 *
 * ```kotlin
 * Modifier
 *     .requiredSize(160.dp)            // 画布比容器大，玻璃边缘才有渐变消散
 *     .textureBlur(
 *         backdrop = backdrop,          // 背后流光的采样源
 *         blurRadius = 200f,
 *         colors = BlurColors(blendColors = logoBlend),
 *         contentBlendMode = BlendMode.DstIn,   // 只保留图标轮廓内
 *     )
 * ```
 *
 * 而 `logoBlend` 是**三层混合**（这才是「那个渐变」的来源）：
 *
 * | 层 | 混合模式 | 作用 |
 * |---|---|---|
 * | 1 | `ColorDodge` / `ColorBurn` | 压出高光边缘 |
 * | 2 | `LinearLight` | 中间调过渡 |
 * | 3 | `Lab` | 那一抹青绿反光 |
 *
 * 删掉第 3 层会变成一块灰玻璃 —— 观感差别很大。
 *
 * 所以这里直接搬这套参数，不再自己发明渐变。
 *
 * ## 低版本（API 28~32）为什么整段跳过
 *
 * `miuix-blur` 的 `AndroidManifest.xml` 声明 `minSdkVersion=33`。
 * 本项目 minSdk 28 —— 在 28~32 上只要**构造**到 `BlurColors(...)` /
 * `textureBlur(...)` 就 `NoClassDefFoundError`（是崩溃，不是降级）。
 * 判据统一走 [GlassBackend.useLowPipeline]，与底栏、关于页同口径。
 */
@Composable
fun ProvisionGlassBrand(
    backdrop: LayerBackdrop?,
    /** 图标容器边长（关于页是 88dp）。 */
    iconSize: Dp = 88.dp,
    modifier: Modifier = Modifier,
) {
    val blurSupported = !GlassBackend.useLowPipeline
    val isDark = LocalPraxisIsDark.current

    // 与 `AboutScreen` 的 `logoBlend` 逐字一致 —— 深浅各一套。
    //
    // 同一组 blend 在两种底色上结果完全不同，所以必须分开；
    // 共用一套会导致深色模式下那个青绿反光被压成脏绿。
    val blend: List<BlendColorEntry>? = if (blurSupported) {
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

    val glass = backdrop != null && blend != null

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(iconSize),
    ) {
        if (glass) {
            Image(
                imageVector = PraxisIcons.Logo,
                contentDescription = null,
                // `requiredSize` 而不是 `size`：画布要比容器**大**，
                // 玻璃的「渐变消散」只在超出容器边界的画布上才表现得出。
                // 关于页是 88dp 容器 / 160dp 画布（约 1.82 倍）。
                modifier = Modifier
                    .requiredSize(iconSize * (160f / 88f))
                    .textureBlur(
                        backdrop = backdrop!!,
                        shape = RoundedCornerShape(16.dp),
                        blurRadius = 200f,
                        noiseCoefficient = BlurDefaults.NoiseCoefficient,
                        colors = BlurColors(blendColors = blend!!),
                        // DstIn：只保留图标轮廓内的玻璃，轮廓外全透明。
                        // 换成 SrcOver 会得到一个巨大的方块。
                        contentBlendMode = BlendMode.DstIn,
                        enabled = true,
                    ),
                contentScale = ContentScale.Fit,
            )
        } else {
            // 低版本降级：普通着色图标。
            // 不用色块垫底 —— 那与「单色图标」的观感差得更远。
            Image(
                imageVector = PraxisIcons.Logo,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}