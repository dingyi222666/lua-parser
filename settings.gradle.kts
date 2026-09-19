pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }

    // Versions for the android/ embedding subprojects (android/build.gradle.kts
    // and android/sample/build.gradle.kts apply these plugins WITHOUT versions).
    // AGP 8.10.0: the TOP of the officially supported range for KGP 2.2.0
    // (7.3.1-8.10.0 per the Kotlin/AGP compatibility map); needs Gradle
    // >= 8.11.1 (wrapper is 8.14.4) and JDK 17 — both satisfied here.
    // NOTE: the earlier "AGP 8.12+ removed the BaseVariant API" theory for the
    // CI NoClassDefFoundError was wrong — the error was IDENTICAL with AGP
    // 8.5.2, 8.10.0 and 8.13.2, and com/android/build/gradle/api/BaseVariant
    // is still present in every AGP 8.x (and 9.x) main jar. The real cause is
    // the KGP/AGP plugin classloader split (KT-57162 / gradle/gradle#25616):
    // KGP is defined by the ROOT project's plugin classloader, AGP by the
    // subprojects', so KGP's KotlinAndroidTarget cannot resolve BaseVariant.
    // Worked around in the ROOT build.gradle.kts plugins block
    // (com.android.* apply false); these pins stay version-only.
    plugins {
        id("com.android.library") version "8.10.0"
        // Same AGP line as the library plugin above; used only by the sample app.
        id("com.android.application") version "8.10.0"
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

