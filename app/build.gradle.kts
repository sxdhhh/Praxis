import java.util.Properties
plugins {
    id("praxis.android.application")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * 版本号唯一定义点。
 *
 * 与 `build-plugins/.../PraxisBuildConfig.kt` 的 VERSION_CODE / VERSION_NAME 同步改。
 * 为什么不在构建脚本里直接引用那个 object：它是 precompiled script 插件里的
 * 默认包 object，`app/build.gradle.kts` 里拿不到（不在可见 classpath 上）。
 */
val PRAXIS_VERSION_CODE = 10
val PRAXIS_VERSION_NAME = "1.0.0"


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
        // 版本号来源：本文件顶部的 PRAXIS_VERSION_* 常量（与 build-plugins 的
        // PraxisBuildConfig 保持一致；那边是 precompiled script 的默认包 object，
        // 在这个构建脚本里无法直接引用）。
        // 允许 -PpraxisVersionCode=... 覆盖（CI 发版用）。
        versionCode = project.findProperty("praxisVersionCode")?.toString()?.toIntOrNull()
            ?: PRAXIS_VERSION_CODE
        versionName = project.findProperty("praxisVersionName")?.toString()
            ?: PRAXIS_VERSION_NAME

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

        /**
         * PTY（伪终端）只编 arm64-v8a。
         *
         * ## 为什么只留一个 ABI
         *
         * `pty.c` 里用的是 `forkpty()`，它来自 bionic 的 `libutil`；
         * 32 位 ABI 上符号名与结构体布局都和 64 位不同，一份源码编两份产物
         * 需要额外验证。而本项目已有的 `libproot.so` / `libtalloc.so` 也只有
         * arm64-v8a（见 `app/src/main/jniLibs/`）—— proot 本身不跑 32 位，
         * PTY 单独为 32 位编译没有意义。
         */
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    /**
     * 原生构建：目前只有 PTY 一个库。
     *
     * ## 为什么现在才引入 NDK
     *
     * 之前全项目的 proot / busybox 都是**预编译好的 .so 直接放 jniLibs**
     * （`libproot.so` 等），不需要 NDK 参与构建。
     * 但 PTY 是**自己写的 JNI**（`pty.c`），必须编出
     * `libpty.so` —— 没有它，Kotlin 侧的 `System.loadLibrary("pty")`
     * 会抛 `UnsatisfiedLinkError`，终端就退化成管道模式
     * （`vim`/`top` 会直接报错，`Ctrl+C` 也不生效）。
     *
     * ## 为什么版本写 3.22.1
     *
     * AGP 内置的 CMake 版本可能与 `cmake_minimum_required(3.18.1)` 不匹配。
     * 显式指定让「同一份代码在不同 AGP 上编出同样的东西」，
     * 也避免 CI 机器上找不到 CMake 时静默跳过。
     */
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
            /**
             * 调试包关闭 PNG 压缩（AAPT2 crunch）。
             *
             * ## 为什么这条能省时间
             *
             * `crunchPngs` 默认对 debug 变体是**开启**的，会对每张 png 做
             * 有损最优压缩（遍历调色板 + 滤波），在 8 核容器上是纯串行的一小段。
             * 它产出的是「体积略小」的调试包 —— 调试阶段没有任何价值，
             * 因为调试包不发布、不进商店、不受体积限制。
             *
             * release 不设这一项（保持默认 true），发布包该省的空间还是要省。
             */
            isCrunchPngs = false
        }
        getByName("release") {
            isMinifyEnabled = true
            // 开了 minify 就必须一起开 shrinkResources：
            // 否则代码里没人引用的资源（图标、字符串）全部原样打进包，
            // lint 的 NotShrinkingResources 警告说的就是这件事。
            isShrinkResources = true
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

    /**
     * native 库必须落地为**真实文件**，否则 proot 无法执行。
     *
     * ## 为什么这一句是 Linux 容器能不能跑的硬前提
     *
     * Android 10+ 起禁止从应用私有目录执行可执行文件（W^X）。唯一稳定可执行的
     * 位置是 `nativeLibraryDir`，而系统只会把 `lib*.so` 解压到那里并给 `-rwxr-xr-x`。
     *
     * 但 AGP 默认会把 native 库**压缩**在 APK 里、安装时由系统按需 mmap ——
     * 那种情况下 `nativeLibraryDir` 里可能没有真实文件（或没有执行位），
     * proot 会以「cannot execute: required file not found」失败。
     *
     * `useLegacyPackaging = true` 强制 extractNativeLibs=true，
     * 让 proot / libtalloc / libandroid-shmem 三个文件真实落地且可执行。
     *
     * ## 三个文件的名字为什么要这样取
     *
     * - `libproot.so`：proot 二进制改名（打包器只认 `lib*.so`）；
     * - `libtalloc.so`：原包是 `libtalloc.so.2`，但 `lib*.so.2` **不匹配**
     *   `lib*.so` 规则会被打包器丢弃。已用 patchelf 把 proot 的 NEEDED
     *   从 `libtalloc.so.2` 改成 `libtalloc.so`，两边一致；
     * - `libandroid-shmem.so`：proot 依赖的共享内存实现。
     *
     * 三者的 RPATH 都已设为 `$ORIGIN`，所以它们在同一个 `nativeLibraryDir`
     * 里能互相找到，不依赖任何绝对路径（Termux 的
     * `/data/data/com.termux/files/usr/lib` 在普通 App 里不存在）。
     */
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    /**
     * 内置 rootfs 必须**不压缩**打包。
     *
     * ## 为什么
     *
     * `assets/rootfs/` 里的 tar.gz 与 tar.xz 本身已是压缩流。再让 AAPT2
     * 做一次 deflate 的后果：
     *
     *  1. **几乎不省空间**：压缩过的数据再压通常只减 0~1%，甚至变大；
     *  2. **拖慢构建**：131 MB 的 Kali 镜像每次打包都要过一遍 deflate；
     *  3. **运行时多一层解压**：`AssetManager.open()` 会流式解 deflate，
     *     拷 131 MB 变成「边解压边写」，耗时翻倍且更耗 CPU。
     *
     * 用 `noCompress` 后 assets 里的字节与源文件完全一致，拷贝就是纯 I/O。
     *
     * ## 为什么用后缀匹配而不是路径前缀
     *
     * AGP 的 `noCompress` 只接受**后缀名**，不接受路径通配。
     * 因此两个内置资产的文件名后缀必须分别是 `.tar.gz` 与 `.tar.xz`
     * （见 `LinuxDistro.builtInAsset`），这也是那两个常量这样命名的原因。
     */
    androidResources {
        noCompress += listOf("tar.gz", "tar.xz")
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

        // 唯一豁免：ObsoleteSdkInt 对 `mipmap-anydpi-v26` 的报告。
        // 实测把目录改名成 `mipmap-anydpi`（去掉 -v26）会让 AAPT 直接报
        // `resource mipmap/ic_launcher not found` —— AGP 不把无 SDK 限定符的
        // anydpi 目录当作 mipmap 资源。二者只能取一，取「能打包」。
        // 目录级 tools:ignore 对 folder 配置类告警无效，只能在这里按路径压制。
        lintConfig = file("lint.xml")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // ## 把 `app/schemas` 挂进 androidTest 的 assets（2026-10-29，G2）
    //
    // `MigrationTestHelper` 要靠**上一版本导出的 schema JSON** 建出 vN 的库，
    // 再跑迁移并校验结果与当前实体是否一致。不挂 assets 时它在运行时报
    // 「找不到 schema 文件」，而那个报错完全指不到「你忘了挂 assets」。
    //
    // 这也是本项目第一次能做迁移测试：之前 7 个 schema
    //（1/2/3/4/5/7.json）一直在导出，但没有任何代码用过它们。
    // 缺 6.json 不影响 —— 测试从 7 升到 8。
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}

// S5：Room 导出 schema 到 app/schemas，供后续版本写迁移与做迁移测试。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}


dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:ai"))
    implementation(project(":core:tool"))
    implementation(project(":core:memory"))
    implementation(project(":core:workflow"))
    implementation(project(":core:plugin"))
    implementation(project(":ui:theme"))
    implementation(project(":ui:design"))
    implementation(project(":ui:widgets"))
    implementation(project(":ui:navigation"))
    implementation(project(":ui:icons"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:settings"))
    // Linux 环境页的纯逻辑（proot 参数 / 镜像表 / SSH·FRP 脚本）。
    // :app 不直接用这些类型，但 ProotEnvPort 需要它们；feature:settings 那边
    // 用的是 implementation 依赖，所以 :app 也要显式声明。
    implementation(project(":core:linux"))
    implementation(project(":feature:terminal"))
    // HyperCeiler 引导链。`:app` 只在 `ProvisionGate` 里按类名字符串跳转，
    // 不 import 它的任何类型 —— 用 `implementation` 就是为了让这个边界
    // 在编译期也成立：如果哪天有人写了 `import com.sevtinge...`，
    // 这里会立刻编不过，从而强迫他保持「按类名跳转」的解耦。
    implementation(project(":ui:provision"))

    implementation(libs.androidx.core.ktx)
    // Kali rootfs 是 .tar.xz；Android 的 toybox `-J` 只是转发外部 xz 命令
    // （实测 `tar -cJf` 报 "exec xz: No such file or directory"，exit=1），
    // 所以解压必须在 Java 侧完成。
    implementation(libs.xz)
    implementation(libs.androidx.activity.compose)
    // 浮层返回键链路：`MainActivity` 直接 provide
    // `LocalNavigationEventDispatcherOwner`（miuix 下拉/弹窗的硬依赖）。
    // 不靠 miuix 传递 —— 那是隐式依赖，见 libs.versions.toml 的注释。
    implementation(libs.androidx.navigationevent.compose)
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
    // `AnimatedContent` / `fadeIn` / `togetherWith` 都在
    // `androidx.compose.animation:animation`，foundation / material3 只传递
    // `animation-core`（`animateFloat` / `tween` 那批）。
    //
    // `:ui:design` 用 `api` 声明了它，所以本模块**能**编过；但那是别人的
    // 传递依赖 —— 上游哪天把 `api` 改成 `implementation`，这里立刻编不过，
    // 而报错落在本文件（使用方），排查方向完全错。项目纪律：直接使用必显式声明。
    implementation(libs.compose.animation)
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

    // 迁移测试（G2）。
    //
    // 为什么必须是 androidTest 而不是 test：`MigrationTestHelper` 要用
    // 真实 SQLite（androidx.sqlite 的实现），JVM 单测里的 SQLite 是假的，
    // 校验不到 Room 生成的建表语句与实际 schema 是否一致 ——
    // 而「不一致」正是启动即崩的成因。
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.room.testing)
}
