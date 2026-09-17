// Module build file for the Lightwave Light Phone III tool.
//
// This module is NOT independently buildable — it's the payload that gets dropped
// into a github.com/lightphone/light-sdk checkout (replacing the placeholder
// `tool/` module) so it can resolve `project(":sdk:...")` and the root Gradle
// setup (version catalog, the `light-sdk` plugin, dev signing key). See SETUP.md.
//
// Structure verified directly against a real light-sdk checkout's `tool/build.gradle.kts`
// (not just the summarized SDK reference notes) — matches it closely on purpose:
// Compose, Room's runtime, WorkManager, DataStore, and the lifecycle-viewmodel APIs
// all come transitively through `:sdk:client` (confirmed: upstream `tool/` doesn't
// redeclare any of them either), so this only adds what's genuinely extra —
// the Navidrome/Subsonic network stack.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.light.sdk)
}

android {
    compileSdk = rootProject.ext["compileSdk"] as Int

    signingConfigs {
        create("lightsdkDev") {
            storeFile = file("../sdk/keys/lightsdk-dev.jks")
            storePassword = "android"
            keyAlias = "lightsdk-dev"
            keyPassword = "android"
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    defaultConfig {
        // applicationId / versionCode / versionName are NOT set here — the
        // `light-sdk` plugin injects them from lighttool.toml at build time
        // (confirmed: upstream tool/build.gradle.kts doesn't set them either).
        minSdk = rootProject.ext["minSdk"] as Int
        targetSdk = rootProject.ext["targetSdk"] as Int
        manifestPlaceholders["sdkVersion"] = property("sdkVersion") as String
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
    }

    lint {
        warningsAsErrors = false
        error += "RestrictedApi"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
        targetCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(rootProject.ext["jvmTarget"] as String))
    }
}

dependencies {
    implementation(project(":sdk:client"))
    testImplementation(libs.kotlin.test)
    ksp(libs.androidx.room.compiler)

    // Everything below is genuinely extra beyond what :sdk:client already exposes —
    // there's no Light-provided HTTP client, and Navidrome speaks the open Subsonic
    // REST API, not anything SDK-specific.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.kotlinx.serialization.json)
}
