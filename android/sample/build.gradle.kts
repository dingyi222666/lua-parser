// Minimal Android sample app embedding the lua-parser LSP exactly like the
// sora-editor Lua LSP sample (sora-editor 0.23.6 app/LspTestActivity.kt +
// app/LspLanguageServerService.kt):
//
//   CodeEditor (layout) -> CustomLanguageServerDefinition over an abstract
//   LocalSocket ("lua-lsp") -> LspProject -> LspEditor (wrapperLanguage =
//   TextMateLanguage) -> connectWithTimeout -> didChangeWorkspaceFolders /
//   didChangeConfiguration -> dispose in onDestroy.
//
// The SERVER side lives in LspServerService.kt: instead of sora's TCP
// java.net.ServerSocket demo, it opens an android.net.LocalServerSocket and
// launches ONE io.github.dingyi222666.luaparser.lsp.LuaLanguageServer per
// accepted connection via LuaLanguageServerLauncher.launch (the per-connection
// contract documented on LuaLspServerHost in :android).
//
// NOTE ON SORA APIs: all APIs used here were verified against the sora-editor
// tag 0.23.6 sources. The `languageServerDefinition { ... }` /
// `connection { local(...) }` Kotlin DSL seen on sora master does NOT exist at
// 0.23.6 (LanguageServerDefinitionDsl.kt / ConnectionDsl.kt /
// LocalSocketStreamConnectionProvider.kt are master-only), so this sample uses
// the 0.23.6 constructors CustomLanguageServerDefinition(ext, provider) and a
// small local LocalSocketStreamProvider built on CustomConnectProvider.
//
// CI: android-verify currently builds :android only; this subproject is built
// on demand (`:android:sample:assembleDebug`). Adding it to the workflow is a
// documented follow-up (see android/sample/README.md).

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Versions pinned in settings.gradle.kts pluginManagement (AGP 8.10.0,
    // Kotlin Android 2.2.0) so this file stays declarative like android/build.gradle.kts.
    // The `com.android.* apply false` entries in the ROOT build.gradle.kts are
    // required by the same KT-57162 classloader workaround that fixes :android.
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.dingyi222666.luaparser.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.dingyi222666.luaparser.sample"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            // Sample-only: keep R8 out of the loop so no keep rules are needed
            // here (the :android AAR ships its own consumer rules for release apps).
            isMinifyEnabled = false
        }
    }

    // Java 17 + jvmTarget 17: a transitive sora dep ships Java 16+ records,
    // and D8's Record desugaring fails with "Invalid build configuration ...
    // without a global synthetic" unless the app's dex pipeline runs with a
    // matching (>= 17) class file target.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += "META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    // The embedded language server itself (commonMain + jvmMain union AAR).
    implementation(project(":android"))

    // sora-editor stack, single version pin (mission: 0.23.6).
    val soraVersion = "0.23.6"
    implementation("io.github.Rosemoe.sora-editor:editor:$soraVersion")
    implementation("io.github.Rosemoe.sora-editor:editor-lsp:$soraVersion")
    implementation("io.github.Rosemoe.sora-editor:language-textmate:$soraVersion")

    // lsp4j aligned with :android (android/build.gradle.kts pins 0.23.1).
    // CHECK-API(sora): sora-editor 0.23.6 itself declares lsp4j 0.24.0; Gradle
    // resolves the conflict in favor of the direct 0.23.1 pins (nearest wins),
    // which is exactly what :android was built against. If a missing-method
    // error ever shows up inside sora's client, bump these two lines to 0.24.0
    // (the Lua server side is lsp4j-version agnostic).
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.23.1")
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:0.23.1")

    // Coroutines for the service accept loop and the connect lifecycle
    // (LspEditor.connectWithTimeout() is suspend at 0.23.6).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // AppCompatActivity + lifecycleScope (same hosts the sora sample uses).
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Material 3, View-based (NOT Compose): the app hosts sora's View-based
    // CodeEditor, so the M3 surface comes from MaterialComponents —
    // Theme.Material3.DayNight.NoActionBar, MaterialToolbar and
    // BottomSheetDialogFragment for the workspace file browser.
    implementation("com.google.android.material:material:1.12.0")

    // RecyclerView for the file browser (material ships it transitively; the
    // explicit pin keeps the compile classpath independent of material's
    // internal dependency resolution).
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
