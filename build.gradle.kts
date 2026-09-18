// Module build file for the Music + Light Phone III tool.
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
        // Real per-app release identity, never the shared SDK dev key every
        // example module uses. Keystore lives at ~/.android/keystores/ (outside
        // any repo) and its password comes from an env var set by
        // scripts/release.sh (which reads it from macOS Keychain) — never
        // hardcoded here, since this file is committed. Falls back to the dev
        // key below when the env vars aren't set, so a plain `assembleRelease`
        // still works for anyone without the real release credentials (CI,
        // another contributor, etc.) instead of failing the build outright.
        val releaseKeystorePath = System.getenv("MUSICPLUS_RELEASE_KEYSTORE_PATH")
        val releaseKeystorePassword = System.getenv("MUSICPLUS_RELEASE_KEYSTORE_PASSWORD")
        if (releaseKeystorePath != null && releaseKeystorePassword != null) {
            create("musicplusRelease") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseKeystorePassword
                keyAlias = "musicplus-release"
                keyPassword = releaseKeystorePassword
                enableV3Signing = true
                enableV4Signing = true
            }
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
            signingConfig = signingConfigs.findByName("musicplusRelease") ?: signingConfigs.getByName("lightsdkDev")
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

    // Needed by MaskedTextInputEditor.kt's `viewModel<EnQwertyLp3KeyboardViewModel<*>>(...)`
    // call (see that file for why it exists — Forgejo issue #2). `:sdk:client` and
    // `:sdk:ui` both declare lifecycle-viewmodel-compose as `implementation`, not `api`,
    // so it isn't on this module's classpath transitively even though the SDK's own
    // LightTextInputEditor uses the same function internally. `androidx.lifecycle` is
    // allowlisted as a whole group by LightSdkPlugin.ALLOWED_DEPENDENCIES, so adding it
    // directly here is within the tool module's allowed dependencies.
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Everything below is genuinely extra beyond what :sdk:client already exposes —
    // there's no Light-provided HTTP client, and Navidrome speaks the open Subsonic
    // REST API, not anything SDK-specific.
    implementation(libs.ktor.client.core)
    // CIO, not OkHttp: OkHttp's Android platform integration enforces Android's
    // default cleartext (plain http://) block, which would make any self-hosted
    // Subsonic/Navidrome server running without TLS on a LAN/tailnet unreachable —
    // and this SDK's manifest generator forbids a custom AndroidManifest.xml, so
    // there's no android:usesCleartextTraffic escape hatch available to set. CIO is
    // Ktor's own pure-Kotlin engine (kotlinx-io based sockets) and isn't subject to
    // that Android-specific policy check at all. "io.ktor" is allowlisted as a
    // whole group by the SDK's dependency policy (LightSdkPlugin.ALLOWED_DEPENDENCIES),
    // so this artifact is fine even though it isn't one of Light's own catalog aliases.
    // Confirmed via on-device testing 2026-09-17 (see project memory note).
    implementation("io.ktor:ktor-client-cio:3.4.2")
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.kotlinx.serialization.json)
}
