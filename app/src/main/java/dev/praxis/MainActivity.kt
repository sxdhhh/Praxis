package dev.praxis

import android.os.Bundle

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.praxis.core.data.settings.SettingsRepository
import dev.praxis.core.model.settings.ThemeSettings
import dev.praxis.ui.PraxisApp
import dev.praxis.ui.theme.PraxisTheme
import org.koin.compose.koinInject

/**
 * 单 Activity 宿主。
 *
 * 主题的**唯一来源**是 [SettingsRepository]：设置页改完立刻落盘，
 * 这里用 `collectAsState` 订阅，整棵树按新设置重组。
 * 不在 Activity 里另存一份主题状态 —— 两份状态一旦分叉，
 * 就会出现「设置页显示深色、界面还是浅色」这种自相矛盾。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val repository: SettingsRepository = koinInject()

            // 把存量明文密钥升级为密文（幂等，加密失败则保持原样）。
            // 放在 MainActivity 而不是 SettingsRepository 构造里：
            // 构造里有 suspend 调用会让 DI 变成异步初始化，
            // 而这里天然就在协程作用域内。
            androidx.compose.runtime.LaunchedEffect(Unit) {
                runCatching { repository.migrateApiKeyIfNeeded() }
            }

            val settings by repository.themeSettings.collectAsState(initial = ThemeSettings())

            PraxisTheme(settings = settings) {
                PraxisApp(
                    settingsRepository = repository,
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                )
            }
        }
    }
}
