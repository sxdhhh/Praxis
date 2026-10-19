import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// 纯 JVM 模块共用配置（core:model / core:common / core:ai 等）。
// 只配置工具链与编译器，不注入任何依赖 —— 版本目录是唯一真源。
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(PraxisBuildConfig.JDK_VERSION)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(PraxisBuildConfig.JDK_VERSION))
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(PraxisBuildConfig.JDK_VERSION.toString()))
    }
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
