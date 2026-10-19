import org.gradle.api.JavaVersion
import org.gradle.api.Project

plugins {
    id("com.android.application")
}

android {
    compileSdk = PraxisBuildConfig.COMPILE_SDK

    defaultConfig {
        minSdk = PraxisBuildConfig.MIN_SDK
        targetSdk = PraxisBuildConfig.TARGET_SDK
    }

    compileOptions {
        val javaVersion = JavaVersion.toVersion(PraxisBuildConfig.JDK_VERSION)
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }

    packaging {
        resources {
            excludes.add("/META-INF/{AL2.0,LGPL2.1}")
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    jvmToolchain(PraxisBuildConfig.JDK_VERSION)
}