pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }

    // Versions for the android/ embedding subproject (android/build.gradle.kts
    // applies these plugins WITHOUT versions). AGP 8.13.2: carries the variant
    // API (BaseVariant) that KGP 2.2.0's KotlinAndroidTarget requires — 8.5.2
    // failed in CI with NoClassDefFoundError: BaseVariant; needs Gradle >= 8.7
    // (wrapper is 8.14.4) and JDK 17 — all satisfied here; Kotlin Android
    // plugin is pinned to the same 2.2.0 the root multiplatform build uses so
    // compiler behavior matches the desktop jvm() artifact exactly.
    plugins {
        id("com.android.library") version "8.13.2"
        // Same AGP line as the library plugin above; used only by the sample app.
        id("com.android.application") version "8.13.2"
        id("org.jetbrains.kotlin.android") version "2.2.0"
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "luaparser"

// Android embedding subproject: packages the jvmMain LSP stack as an
// Android-consumable AAR without touching the root KMP contract
// (see android/build.gradle.kts for the full rationale).
include(":android")
project(":android").projectDir = file("android")

// Minimal sora-editor style sample app embedding the LSP over a LocalSocket
// (see android/sample/README.md). NOT built by android-verify CI yet; the app
// is compiled on demand via `:android:sample:assembleDebug`.
include(":android:sample")
project(":android:sample").projectDir = file("android/sample")

