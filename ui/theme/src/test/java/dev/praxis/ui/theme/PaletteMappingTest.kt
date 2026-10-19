package dev.praxis.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import androidx.compose.ui.graphics.toArgb
import dev.praxis.core.model.settings.ColorSource
import dev.praxis.core.model.settings.PaletteStyle
import dev.praxis.core.model.settings.ThemeSettings
import org.junit.Test
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * 调色板映射的回归锁。
 *
 * 用手写映射而不是 `valueOf` 反射：库升级新增枚举时，这里会编译失败，
 * 强迫我们去看一眼新枚举该不该暴露给用户，而不是静默漂移。
 */
class PaletteMappingTest {

    @Test
    fun `every Praxis palette maps to a real miuix palette`() {
        PaletteStyle.entries.forEach { style ->
            val mapped: ThemePaletteStyle = style.toMiuix()
            assertNotNull("$style must map to a miuix palette", mapped)
        }
    }

    @Test
    fun `Praxis exposes exactly 9 palettes matching miuix 0_9_4`() {
        assertEquals(9, PaletteStyle.entries.size)
        assertEquals(9, ThemePaletteStyle.entries.size)
    }

    @Test
    fun `mapping is injective - no two Praxis palettes share a miuix target`() {
        val targets = PaletteStyle.entries.map { it.toMiuix() }
        assertEquals(targets.size, targets.toSet().size)
    }

    @Test
    fun `wallpaper source yields null keyColor so miuix reads the real wallpaper`() {
        val wallpaper = ThemeSettings(colorSource = ColorSource.Wallpaper)
        assertNull(
            "miuix 会优先用 keyColor 生成调色板；跟随壁纸时必须为 null 才会调用 platformDynamicColors",
            wallpaper.keyColorOrNull,
        )
    }

    @Test
    fun `custom source yields the seed color`() {
        val custom = ThemeSettings(
            colorSource = ColorSource.Custom,
            seedColorArgb = 0xFF112233,
        )
        assertEquals(0xFF112233.toInt(), custom.keyColorOrNull?.toArgb())
    }
}
