import java.util.Properties

plugins {
    id("praxis.android.application")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * 从 local.properties 取配置，取不到再退到同名环境变量，最后才用 fallback。
 *
 * 顺序是有意的：本地开发改 local.properties 最顺手；CI 上则用环境变量注入，
 * 两处都不用把密钥写进仓库。local.properties 已在 .gitignore 里。
 */
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

/**
 * 取值顺序：local.properties → 环境变量 [envKey] → fallback。
 *
 * 环境变量名单独传，是因为键名里带点（praxis.baseUrl）不能被 shell 直接 export，
 * CI 上必须用 PRAXIS_BASE_URL 这种名字。
 */
fun localProp(key: String, envKey: String, fallback: String): String =
    localProps.getProperty(key) ?: System.getenv(envKey) ?: fallback

/** release 签名材料：路径允许写进 local.properties 或环境变量，口令只走环境变量。 */
val releaseKeystore: String? = localProp("PRAXIS_KEYSTORE", "PRAXIS_KEYSTORE", "")
    .takeIf { it.isNotBlank() }

android {
    namespace = "dev.praxis"

    defaultConfig {
        applicationId = "dev.praxis"
        versionCode = project.findProperty("praxisVersionCode")?.toString()?.toIntOrNull() ?: 1
        versionName = project.findProperty("praxisVersionName")?.toString() ?: "0.1.0"

        // S3：把「服务商地址」和「调试用 Key」变成编译期常量。
        // apiKey 默认为空字符串 —— 真实密钥走设置页写入加密存储，不预置在仓库里。
        buildConfigField(
            "String",
            "DEFAULT_BASE_URL",
            "\"${localProp("praxis.baseUrl", "PRAXIS_BASE_URL", "https://api.example.com")}\"",
        )
        buildConfigField(
            "String",
            "API_KEY",
            "\"${localProp("praxis.apiKey", "PRAXIS_API_KEY", "")}\"",
        )
    }

    // 签名配置在 buildTypes 之前先建好：AGP 9 的 signingConfigs 容器类型在版本间变过，
    // 用 create(...) 的返回值直接持有，比事后 findByName 稳。
    val releaseSigning = run {
        val keystorePath = releaseKeystore
        val storePass = System.getenv("PRAXIS_STORE_PASS")
        val keyPass = System.getenv("PRAXIS_KEY_PASS")
        if (keystorePath != null && !storePass.isNullOrBlank() && !keyPass.isNullOrBlank()) {
            signingConfigs.create("release") {
                storeFile = file(keystorePath)
                storePassword = storePass
                keyAlias = System.getenv("PRAXIS_KEY_ALIAS") ?: "praxis"
                keyPassword = keyPass
            }
        } else {
            // 缺料就不建签名配置；release 产物保持未签名，
            // 让「忘了配签名」在安装阶段暴露，而不是产出一个看起来正常的包。
            null
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            releaseSigning?.let { signingConfig = it }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        // 不开基线、不设 warningsAsErrors（默认 false，且字段在 AGP 版本间有变动）：
        // 现有告警都是 warning，abortOnError 只拦 error 即可。
        // 等真出现存量 error 时再用 IDE 或 updateLintBaseline 生成基线，单独提交。
        //
        // 不检查依赖包内部：miuix / Compose AAR 里的问题不归本项目修，
        // 打开只会让 CI 长期红着，掩盖自己代码里的真问题。
        checkDependencies = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// S5：Room 导出 schema 到 app/schemas，供后续版本写迁移与做迁移测试。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// 注：暂不把 app/schemas 挂到 androidTest 的 assets。
// 目前没有迁移测试，挂一个尚不存在的目录只有风险没有收益；
// 等第一个 MigrationTest 落地时再按 Room 官方模板挂上。

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:ai"))
    implementation(project(":core:tool"))
    implementation(project(":core:memory"))
    implementation(project(":core:workflow"))
    implementation(project(":ui:theme"))
    implementation(project(":ui:design"))
    implementation(project(":ui:widgets"))
    implementation(project(":ui:navigation"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:terminal"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.miuix.core)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.shader)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.navigation)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.compose.viewmodel)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)
    implementation(libs.material.kolor)

    // S3：网络栈。Phase 3 的流式解析直接接 OkHttp 的 ResponseBody.source()，
    // 所以现在就把依赖摆上，避免后续换库牵动 AI 契约。
    // logging-interceptor 用 implementation 而非 debugImplementation：
    // 拦截器的挂载点在 main 源集里（按 BuildConfig.DEBUG 决定是否 add），
    // 放 debugImplementation 会让 release 变体编译失败。
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}