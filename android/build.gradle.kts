// Android embedding subproject.
//
// Packages the JVM language-server stack (the src/jvmMain/kotlin sources plus
// the commonMain sources they are built on) as an Android-consumable library
// artifact, WITHOUT restructuring the root Kotlin/Multiplatform contract:
//
//  * WHY a standalone com.android.library subproject and NOT androidTarget()
//    on the root project? A root androidTarget() compiles COMMON code only —
//    the LSP lives in jvmMain (src/jvmMain/.../lsp), so it would be invisible
//    to the Android target unless every published target was restructured
//    around a jvmAndroid intermediate source set (high risk, breaks the KMP
//    publishing contract). This subproject instead compiles the commonMain +
//    jvmMain union directly under AGP — exactly the source union the root
//    jvm() target compiles today. Verified: no expect/actual declarations
//    anywhere, and commonMain has zero java.* imports.
//
//  * srcDirs: the WHOLE src/commonMain/kotlin + src/jvmMain/kotlin trees are
//    pulled in on purpose. The LSP entry points (lsp/LuaLanguageServer,
//    lsp/LuaLanguageService) directly import interop.jvm.JvmWorkspaceEngine /
//    JvmWorkspaceConfiguration, so an all-in-one artifact is the only coherent
//    packaging; splitting sources would duplicate the engine.
//
//  * minSdk 26: the jvmMain sources use java.nio.file.{Files,Path,Paths},
//    java.lang.reflect.Executable and java.util.Base64 — all three API groups
//    first shipped in Android API 26. Nothing above API 26 is referenced.
//
//  * Overlay resources: Android apps cannot rely on classpath resources,
//    which is why the root project mirrors src/commonMain/resources into a
//    generated Kotlin object (BuiltinOverlayResourceMirror) and
//    BuiltinOverlayResourceAccess serves the overlay ONLY from that mirror.
//    Task generateAndroidBuiltinOverlayMirror below replicates the root
//    generator 1:1 so the AAR bakes in the identical overlay. We deliberately
//    do NOT copy src/commonMain/resources or src/jvmMain/resources into the
//    AAR: the overlay files are dead weight next to the mirror, and
//    androlua-runtime.jar is JVM bytecode that ART cannot load without a
//    prior dex conversion (AndroLua *indexing* still works on Android via the
//    mirror; only class *mounting* needs the jar).
//
//  * CONSUMPTION (both documented, AAR via composite build recommended):
//      1. Composite include (best): in the app's settings.gradle.kts add
//           includeBuild("<path-to-lua-parser>")
//         and depend on the module:
//           implementation("io.github.dingyi222666:android:1.0.4")
//         Gradle substitutes the included build, so transitive deps (lsp4j,
//         gson, stdlib) resolve automatically and Lane A1's in-process host
//         entry points are usable without any copying.
//      2. Plain AAR copy: download the android-release-aar artifact from the
//         android-verify workflow, drop it into app/libs/, and re-declare the
//         compile deps by hand (lsp4j 0.24.0 x2, gson 2.11.0, kotlin-stdlib
//         2.2.0) — a bare AAR carries no POM. R8 rules ship inside the AAR
//         via consumerProguardFiles(proguard-consumer.pro).
//     The sora-editor integration (editor-lsp over LocalSocket) lives in the
//     APP, not here: this artifact only needs lsp4j + gson + stdlib.
//
//  * androidReleaseJar (below): a fat CLASSES jar (our classes + lsp4j +
//    gson + stdlib, no AAR wrapper) for consumers that prefer to run their
//    own dex step (the monaco-lsp-demo classes.dex pipeline). The AAR remains
//    the recommended artifact; the fat jar trades kotlin-stdlib metadata
//    fidelity (multiple .kotlin_module files merged under DuplicatesStrategy
//    EXCLUDE) for a single-file drop.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Versions pinned in settings.gradle.kts pluginManagement (AGP 8.5.2,
    // Kotlin Android 2.2.0) so this file stays declarative.
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Derived from the root publish coordinates (root build.gradle.kts is the
// single source of truth — bump together with publishVersion there).
group = "io.github.dingyi222666"
version = "1.0.4"

// ---------------------------------------------------------------------------
// Builtin overlay mirror — replicates the root project's
// generateBuiltinOverlayMirror task byte for byte (same walk, same ordering,
// same escaping) so the Android artifact serves the identical overlay.
// ---------------------------------------------------------------------------
val overlayResourcesDir = rootProject.layout.projectDirectory.dir("src/commonMain/resources")
val androidOverlayMirrorDir = layout.buildDirectory.dir("generated/androidBuiltinOverlayMirror/kotlin")

val generateAndroidBuiltinOverlayMirror = tasks.register("generateAndroidBuiltinOverlayMirror") {
    group = "build"
    description = "Embeds src/commonMain/resources overlay files into a generated Kotlin source (Android mirror of the root task)"

    inputs.dir(overlayResourcesDir).withPropertyName("overlayResources")
    outputs.dir(androidOverlayMirrorDir).withPropertyName("generatedSources")

    doLast {
        val root = overlayResourcesDir.asFile
        val entries = root.walkTopDown()
            .filter { it.isFile && it.name != ".DS_Store" }
            .map { file ->
                val key = "/" + file.relativeTo(root).invariantSeparatorsPath
                key to file.readText()
            }
            .sortedBy { it.first }
            .toList()

        fun quote(value: String): String = buildString(value.length + 16) {
            append('"')
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '$' -> append("\\$")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
            append('"')
        }

        val target = androidOverlayMirrorDir.get().asFile
            .resolve("io/github/dingyi222666/luaparser/semantic/workspace/std")
        target.mkdirs()
        target.resolve("BuiltinOverlayResourceMirror.kt").writeText(
            buildString {
                appendLine("// Generated by the generateAndroidBuiltinOverlayMirror Gradle task. Do not edit.")
                appendLine("// Source of truth: src/commonMain/resources")
                appendLine("package io.github.dingyi222666.luaparser.semantic.workspace.std")
                appendLine()
                appendLine("internal object BuiltinOverlayResourceMirror {")
                appendLine("    val resources: Map<String, String> = linkedMapOf(")
                entries.forEachIndexed { index, (key, content) ->
                    append("        ")
                    append(quote(key))
                    append(" to ")
                    append(quote(content))
                    appendLine(if (index == entries.lastIndex) "" else ",")
                }
                appendLine("    )")
                appendLine("}")
            }
        )
    }
}

// preBuild is the stable fan-in task of every AGP variant; wiring here keeps
// the generated srcDir valid before any compile task runs.
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(generateAndroidBuiltinOverlayMirror)
}

// ---------------------------------------------------------------------------
// Android library configuration
// ---------------------------------------------------------------------------
android {
    namespace = "io.github.dingyi222666.luaparser.android"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        // R8 keep rules (lsp4j Gson models + gson reflection) travel inside
        // the AAR so every consumer gets them without extra config.
        consumerProguardFiles("proguard-consumer.pro")
    }

    buildTypes {
        release {
            // Library AARs are not signed (signing applies to APP artifacts),
            // so assembleRelease needs no signing config.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        // 11 to match the root jvm() target — no desugaring needed because
        // minSdk 26 already ships every API the sources use.
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    sourceSets {
        getByName("main") {
            // common+jvm union — see header comment for why the whole trees
            // are included. (.DS_Store and non-.kt files are ignored by the
            // compile tasks; resources are intentionally NOT included.)
            java.srcDir(rootProject.layout.projectDirectory.dir("src/commonMain/kotlin"))
            java.srcDir(rootProject.layout.projectDirectory.dir("src/jvmMain/kotlin"))
            // Generated overlay mirror, produced by the task above.
            java.srcDir(androidOverlayMirrorDir)
        }
    }
}

kotlin {
    compilerOptions {
        // Parity with the root jvm target compiler (JvmTarget.JVM_11 and the
        // same -Xjvm-default mode) so Android-compiled classes behave exactly
        // like the desktop artifact at interface-default boundaries.
        jvmTarget.set(JvmTarget.JVM_11)
        freeCompilerArgs.add("-Xjvm-default=all-compatibility")
    }
}

dependencies {
    // api(): mirrors root jvmMain — lsp4j types are part of the public
    // LuaLanguageService surface (Hover, CompletionList, ...), so embedders
    // need them at compile time.
    //
    // 0.24.0 (mirrors the root jvmMain pin): sora-editor editor-lsp 0.23.6 —
    // the sample app's LSP connection layer — declares lsp4j 0.24.0 as a
    // RUNTIME-only dependency (Gradle module metadata variant
    // releaseVariantReleaseRuntimePublication; POM <scope>runtime</scope>), so
    // a consumer of both artifacts resolves 0.24.0 at runtime regardless.
    // Compiling against the same version avoids a compile(0.23.1)/run(0.24.0)
    // skew. Verified binary-compatible with 0.23.1 for every class this repo
    // imports (javap member-signature diff: only the internal
    // adapters.InlineValueResponseAdapter changed). Do NOT jump to 1.0.0:
    // Diagnostic.message -> Either<String,MarkupContent>,
    // TextDocumentEdit.edits -> Either<...,SnippetTextEdit> and removals of
    // deprecated Either/FormattingOptions APIs require a source migration.
    api("org.eclipse.lsp4j:org.eclipse.lsp4j:0.24.0")
    api("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:0.24.0")

    // The root project never pins gson (it resolves transitively through
    // lsp4j.jsonrpc's [2.9.1,3.0) range). A bare AAR loses that POM metadata,
    // so pin it explicitly here with a stable in-range version.
    api("com.google.code.gson:gson:2.11.0")

    // Matches the root commonMain stdlib pin (Kotlin 2.2.0 = consumer floor).
    api("org.jetbrains.kotlin:kotlin-stdlib:2.2.0")
}

// ---------------------------------------------------------------------------
// androidReleaseJar — fat classes jar (see header comment for the trade-offs;
// the AAR from assembleRelease remains the recommended artifact).
// ---------------------------------------------------------------------------
val releaseAarTask = tasks.named("bundleReleaseAar")
val releaseAarExtractDir = layout.buildDirectory.dir("androidReleaseJar/aar")
val releaseClassesDir = layout.buildDirectory.dir("androidReleaseJar/classes")

// AAR is a zip whose classes.jar is itself a zip: two extraction steps.
val extractAndroidReleaseAar = tasks.register<Copy>("extractAndroidReleaseAar") {
    dependsOn(releaseAarTask)
    from({ zipTree(releaseAarTask.get().outputs.files.singleFile) })
    into(releaseAarExtractDir)
}

val extractAndroidReleaseClasses = tasks.register<Copy>("extractAndroidReleaseClasses") {
    dependsOn(extractAndroidReleaseAar)
    from({ zipTree(releaseAarExtractDir.get().asFile.resolve("classes.jar")) })
    into(releaseClassesDir)
}

val androidReleaseJar = tasks.register<Jar>("androidReleaseJar") {
    group = "build"
    description = "Fat classes jar of the android release variant (our classes + lsp4j + gson + stdlib) for custom dex pipelines"
    dependsOn(extractAndroidReleaseClasses, configurations.named("releaseRuntimeClasspath"))

    archiveBaseName.set("luaparser-android")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // Never merge signature files or module-info across jars.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")

    from(releaseClassesDir)
    from(provider {
        configurations.getByName("releaseRuntimeClasspath")
            .filter { it.extension == "jar" }
            .map { zipTree(it) }
    })
}
