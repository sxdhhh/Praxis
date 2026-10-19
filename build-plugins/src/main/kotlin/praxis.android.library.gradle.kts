import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Android library 模块共用配置。
// 只设置 SDK / 工具链 / 打包口径，不注入依赖 —— 各模块自己声明依赖。
plugins {
    id("com.android.library")
}

android {
    compileSdk = PraxisBuildConfig.COMPILE_SDK

    defaultConfig {
        minSdk = PraxisBuildConfig.MIN_SDK
    }

    compileOptions {
        sourceCompatibility = org.gradle.api.JavaVersion.toVersion(PraxisBuildConfig.JDK_VERSION)
        targetCompatibility = org.gradle.api.JavaVersion.toVersion(PraxisBuildConfig.JDK_VERSION)
    }

    packaging {
        resources {
            excludes.add("/META-INF/{AL2.0,LGPL2.1}")
        }
    }

    lint {
        abortOnError = true
        checkDependencies = false
    }
}

kotlin {
    jvmToolchain(PraxisBuildConfig.JDK_VERSION)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(PraxisBuildConfig.JDK_VERSION.toString()))
    }
}
