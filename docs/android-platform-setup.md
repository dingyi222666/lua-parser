# Android Platform Classpath Setup

Docs refresh: **TASK-508** (WORKER-WAVE36F-20260712), 2026-07-11/12 — post-**TASK-184** / pre-**TASK-043** documentation accuracy on this macOS host. Docs-only; no product code; **no Gradle/tests/compile**.

**Honesty bound (post-TASK-184, pre-TASK-043):** this page records expected local Android SDK platform jar paths, dual-path host policy (SDK + optional Downloads), JVM interop/LSP metadata keys, Corretto 17 toolchain notes, and skip behavior when `android.jar` is absent. TASK-184 library-stub / overlay surfaces are accepted in task metadata. This document does **not** claim final green acceptance, does **not** unlock **TASK-043** (serialized verification, still `blocked`), and does **not** unlock **TASK-037** (final acceptance, still `blocked`). Host path status and operator notes here are analysis-setup inventory only — **inventory is not final until TASK-043**.

This document is documentation-only: no production code, tests, Gradle build files, or host environment variables are changed by following it. Workers must not run Gradle/tests/compile for this task.

## Host toolchain (macOS)

Force Amazon Corretto 17 for product/test alignment on this host (do not rely on the shell default `java`, which may be a newer OpenJDK such as 26):

```text
Amazon Corretto 17
JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home
```

Historical Windows notes that used Temurin 17 `jar.exe` or `G:/Android/Sdk/...` are **not** host defaults here. **Never hard-code `G:/` (or any non-host Windows path)** in new docs, tests, or product path assumptions for this macOS host.

## Host `android.jar` dual-path policy (never `G:/`)

Docs, tests, and operator notes on this macOS host may reference **only** these host jar locations:

| Priority | Path | Role | Host status (2026-07-11 TASK-508 re-check) |
| --- | --- | --- | --- |
| 1 (preferred for reproducible analysis) | Explicit metadata `jvm.androidJar` | Caller-supplied single platform jar | Set to the SDK path below when configuring analysis/tests |
| 2 | `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar` | Android SDK Platform 35 jar (`ANDROID_HOME` / `ANDROID_SDK_ROOT` typically `/Users/dingyi/Library/Android/sdk`) | **Present** (~27,092,450 bytes) |
| 3 (optional copy) | `/Users/dingyi/Downloads/android.jar` | Operator drop-in copy of a platform jar | **Absent** on this host; allowed when present as an **explicit** metadata override only |

Preferred SDK path block:

```text
Path: /Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar
ANDROID_HOME / ANDROID_SDK_ROOT (when set): /Users/dingyi/Library/Android/sdk
```

Optional Downloads path block (use only when the file exists; do not invent classpath entries for a missing file; product discovery does **not** auto-select Downloads):

```text
/Users/dingyi/Downloads/android.jar
```

Rules:

1. Prefer SDK `platforms/android-35/android.jar` when the file exists (current host: present).
2. Use `/Users/dingyi/Downloads/android.jar` only when that file exists **and** is supplied via explicit `jvm.androidJar` (or equivalent operator config).
3. If neither host jar is available, **skip** reflective Android framework mounting; do not fail Gradle/LSP startup solely because `android.jar` is missing. Curated Android-Lua static framework overlays may still provide a subset of symbols.
4. Never hard-code `G:/Android/Sdk/...` or other Windows-only paths for this host.

**Host status (2026-07-11 TASK-508 re-check):** SDK `android.jar` is **present** at the path above (~27,092,450 bytes; Android SDK Platform 35 under `/Users/dingyi/Library/Android/sdk`). Downloads `android.jar` is **absent**. Earlier notes that said the SDK jar was "not installed yet" are obsolete for this host; earlier notes that omitted the Downloads dual-path should be treated as incomplete for operator drop-ins.

Historical inspection also verified the same SDK path/size on a Windows host (2026-06-06). Representative class entries present when inspected with JDK 17 `jar` (macOS host: Corretto 17 `jar`; historical Windows host: Temurin 17 `jar.exe`):

```text
android/widget/TextView.class
android/content/Context.class
android/view/View.class
java/lang/String.class
```

Use `java.lang.*` classes from the running JDK runtime for normal JVM reflection. The Android jar is needed for Android framework packages such as `android.content`, `android.view`, and `android.widget`.

### Default path resolution (product)

`JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH` / `resolveDefaultAndroidJarPath()` resolve a host-local default for tests and convenience consumers. Behavior (product work landed in **TASK-245**):

1. Prefer `ANDROID_HOME`, then `ANDROID_SDK_ROOT`, selecting the highest `platforms/android-*/android.jar`.
2. Else scan well-known SDK roots (macOS `~/Library/Android/sdk`, Linux `~/Android/Sdk`, Windows `%LOCALAPPDATA%/Android/Sdk`, etc.).
3. If no real jar is found, return a preferred candidate path for messaging only — callers must check `File.isFile` and **skip when absent**. Never invent a reflective classpath entry for a missing jar.
4. Downloads (`/Users/dingyi/Downloads/android.jar`) is **not** part of automatic discovery; it is explicit-metadata only when present.

Prefer explicit `jvm.androidJar` metadata for reproducible analysis. Do not hard-code Windows-only paths in new product code (see TASK-245).

## Metadata Keys

The JVM workspace path is configured through `JvmWorkspaceConfiguration` and `JvmClassModuleProvider`. These are the exact metadata keys:

| Key | Value shape | Purpose |
| --- | --- | --- |
| `jvm.classes` | Fully qualified class names. Direct metadata accepts comma, semicolon, or newline separators; LSP settings accept a string or string array. | Mounts explicit reflected class providers such as `java.util.Arrays` or `android.content.Context`. |
| `androlua.imports` | AndroLua import targets. Direct metadata is newline-separated; LSP settings accept a string or string array. | Resolves simple or fully qualified AndroLua-style imports through the import prefix list. |
| `jvm.classpath` | Newline-separated jar or class-directory entries; LSP settings accept a string or string array. | Adds external jars/directories to the reflective class loader. |
| `jvm.androidJar` | Single file path string. | Appends one Android platform `android.jar` to the effective reflective classpath and takes precedence over SDK environment discovery. |
| `jvm.importPrefixes` | Newline-separated package/class prefixes; LSP settings accept a string or string array. | Resolves simple import names such as `TextView` or nested Android aliases. |

Default import prefixes are:

```text
java.lang
java.util
java.io
android.app
android.content
android.view
android.widget
com.androlua
```

`JvmWorkspaceConfiguration.effectiveClasspathEntries()` preserves `jvm.classpath` entries first and appends explicit `jvm.androidJar` last. Empty strings are trimmed out.

## Static Framework Resources vs Reflection

The Android-Lua built-in overlay also embeds static Android framework resource models under:

```text
src/commonMain/resources/io/github/dingyi222666/luaparser/semantic/workspace/android-framework/
```

Those resources are loaded by `BuiltinOverlayLoader` for `LuaVersion.ANDROLUA_5_3`. They are independent of the local Android SDK installation and can provide a curated baseline for common Android-Lua symbols. The manifest-backed static providers expose selected `android.app`, `android.content`, `android.view`, `android.widget`, `android.graphics`, `android.graphics.drawable`, and supporting classes through synthetic `__jvm__/classes/...`, `__jvm__/packages/...`, and `__jvm__/class-aliases/...` provider paths.

Use the static resources for documented, high-value Android-Lua analysis where a local platform jar is not available: common imports such as `TextView`, `View`, `Context`, selected nested listener/enum classes, package completions, and compact member metadata declared in the model files.

Use `android.jar` reflection when the analysis needs authoritative SDK coverage:

- classes or packages outside the curated static manifest
- API-level-specific availability
- broad public field, method, constructor, overload, inheritance, and generic metadata
- wildcard package enumeration from the installed SDK jar
- parity with a specific Android platform selected by `jvm.androidJar`, `ANDROID_HOME`, or `ANDROID_SDK_ROOT`

The static resources are model inputs, not a runtime or SDK substitute. They do not perform Android resource lookup, do not prove that an API exists on every Android level, and do not replace external jars or class directories required for application/plugin classes. Post-TASK-184 library-stub / overlay acceptance does **not** by itself mean reflective SDK coverage or full suite green — that remains pre-TASK-043.

## Android SDK Environment Discovery

For reproducible analysis, prefer explicit `jvm.androidJar`. When it is configured, the workspace provider does not inspect Android SDK environment variables.

When `jvm.androidJar` is absent, JVM reflection discovers a platform jar from SDK environment variables in this deterministic order:

1. `ANDROID_HOME`
2. `ANDROID_SDK_ROOT`

Within the selected SDK root, discovery scans `platforms/android-*/android.jar` and uses the highest numeric API level. For example, if `ANDROID_HOME` contains both `platforms/android-34/android.jar` and `platforms/android-35/android.jar`, API 35 is selected.

If both `ANDROID_HOME` and `ANDROID_SDK_ROOT` contain platform jars, `ANDROID_HOME` wins by precedence. `JvmWorkspaceConfiguration.androidJarConfigurationNote()` reports that multiple SDK variables were usable and recommends setting `jvm.androidJar` explicitly to avoid ambiguity.

If neither environment variable is set, or neither SDK root contains `platforms/android-*/android.jar`, no machine-local hardcoded fallback is forced onto the reflective classpath. Reflective Android framework providers remain unavailable, and `androidJarConfigurationNote()` reports the missing SDK state with the exact variables or roots inspected. Android-Lua static framework resources may still provide their curated symbols when the Android-Lua overlay is active. Convenience helpers may still surface a preferred candidate path for skip guards (see Default path resolution above). Downloads is never auto-discovered.

## Skip When Absent

**Policy:** never mutate the host environment, never fail Gradle/LSP startup solely because `android.jar` is missing, and never pretends reflective Android SDK coverage exists when the jar is absent.

When the configured or discovered jar path is not a regular file:

1. Skip reflective Android framework class mounting for that run.
2. Keep JDK classes and any valid `jvm.classpath` entries available.
3. Keep curated Android-Lua static framework resources available when that overlay is active.
4. Tests that require authoritative Android reflection should detect absence (`!File(path).isFile`) and skip with a clear reason rather than fail the whole suite.
5. Do not rewrite `build.gradle.kts`, install SDKs, or set `ANDROID_HOME` / `ANDROID_SDK_ROOT` as part of worker docs tasks.

## JVM Test Setup

Use the repository Java prerequisite when running JVM tests on macOS (force Corretto 17). **Operators/review only:** workers for docs tasks must not run Gradle/tests/compile; verification is review-owned and deferred to **TASK-043**.

```bash
export JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home
./gradlew jvmTest --tests interop.jvm.JvmWorkspaceEngineTest
```

Tests load Android framework classes by passing metadata to the workspace engine, not by editing the Gradle test runtime classpath. A minimal metadata payload for Android 35 is:

```kotlin
val metadata = mapOf(
    "androlua.imports" to "Context\nTextView",
    "jvm.androidJar" to "/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar",
    "jvm.importPrefixes" to "android.content\nandroid.widget"
)
```

For explicit class mounting instead of AndroLua simple imports:

```kotlin
val metadata = mapOf(
    "jvm.classes" to "android.content.Context\nandroid.widget.TextView",
    "jvm.androidJar" to "/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar"
)
```

When an operator has placed a platform jar under Downloads (currently **absent** on this host), that path may be used only as explicit metadata:

```kotlin
val metadata = mapOf(
    "jvm.androidJar" to "/Users/dingyi/Downloads/android.jar"
)
```

External test jars should be added through `jvm.classpath`, with one entry per line:

```kotlin
val metadata = mapOf(
    "jvm.classpath" to "build/test-libs/app.jar\nbuild/test-libs/plugin.jar",
    "jvm.androidJar" to "/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar"
)
```

No `build.gradle.kts` change is needed for these paths. If the path is missing on another machine, omit `jvm.androidJar` or skip Android-reflection assertions (see Skip When Absent).

## LSP Setup

Launch the JVM language server with Gradle (**operators/review only**; docs workers do not run this):

```bash
export JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home
./gradlew runLuaLanguageServer
```

The `runLuaLanguageServer` Gradle task uses the project JVM jar plus `jvmRuntimeClasspath` to start `io.github.dingyi222666.luaparser.lsp.LuaLanguageServerLauncherKt`. Android and external application jars are then supplied by LSP workspace configuration, not by changing the Gradle task.

`LuaWorkspaceService.didChangeConfiguration` accepts either flat settings:

```json
{
  "androlua.imports": ["Context", "TextView"],
  "jvm.classpath": ["build/test-libs/app.jar"],
  "jvm.androidJar": "/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar",
  "jvm.importPrefixes": ["java.lang", "android.content", "android.widget"]
}
```

Or nested settings:

```json
{
  "androlua": {
    "imports": ["Context", "TextView"]
  },
  "jvm": {
    "classpath": ["build/test-libs/app.jar"],
    "androidJar": "/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar",
    "importPrefixes": ["java.lang", "android.content", "android.widget"]
  }
}
```

String values for list settings are interpreted as newline-separated lists. Array values are also accepted.

## Classloader Expectations

`JvmClassModuleProvider` uses the provider's JVM class loader as the base class loader. When `jvm.classpath`, explicit `jvm.androidJar`, or a discovered SDK platform jar is present, it builds a child `URLClassLoader` from the reflection classpath entries and uses that loader for `Class.forName(..., initialize = false, classLoader)`.

Expected behavior:

- JDK classes such as `java.lang.String`, `java.util.Locale`, `java.io.File`, and JDK wildcard packages come from the running Java 17 runtime image (prefer Corretto 17 on this host).
- Reflective Android framework providers for classes such as `android.content.Context`, `android.view.View`, `android.view.View$OnClickListener`, and `android.widget.TextView` require either explicit `jvm.androidJar` or a discovered platform jar from `ANDROID_HOME` or `ANDROID_SDK_ROOT`. The Android-Lua static overlay may still provide a curated subset of those symbols when reflection is unavailable.
- External application or plugin classes require their jar or class-directory root in `jvm.classpath`.
- Directories in `jvm.classpath` must be package roots containing `.class` files under paths such as `com/example/Foo.class`.
- Jar entries are scanned for wildcard imports. Top-level class files in the requested package are exposed; nested classes are resolved by exact or alias candidate names when requested.
- Missing or invalid classpath entries do not automatically fail Gradle startup. They simply leave the affected classes unresolved by the reflective provider.

Because the URL class loader delegates to its parent, normal JDK and project runtime classes remain available even when no Android jar is configured.

## Missing Android Jar Fallback

If explicit `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar` is missing on a given machine:

1. Do not edit `build.gradle.kts` for this path.
2. Do not mutate environment variables from worker/docs tasks. Operators may install Android SDK Platform 35 with Android Studio SDK Manager or `sdkmanager "platforms;android-35"` if full reflective coverage is required.
3. If another platform is already installed, point `jvm.androidJar` to that platform jar in metadata or LSP settings, for example `/Users/dingyi/Library/Android/sdk/platforms/android-34/android.jar`.
4. If a copy exists at `/Users/dingyi/Downloads/android.jar`, set `jvm.androidJar` to that path explicitly (never auto-discovered).
5. If `jvm.androidJar` is omitted, set `ANDROID_HOME` or `ANDROID_SDK_ROOT` (operator choice) to an Android SDK root that contains at least one `platforms/android-*/android.jar`, or rely on well-known root discovery from TASK-245.
6. If no Android platform jar is available, omit `jvm.androidJar`. JDK classes and external jars in `jvm.classpath` can still resolve. Curated Android-Lua static framework resources may satisfy documented framework symbols, but reflective Android SDK coverage should be treated as unavailable for that run — **skip**, do not fail host setup.
7. Keep Android-specific tests or LSP scenarios scoped to environments where the configured or discovered `android.jar` exists; otherwise skip with a clear reason.

## Pre-TASK-043 note

- **TASK-184** product library-stub work is accepted in task metadata; this page does not re-verify those tests.
- **TASK-043** serialized Gradle verification remains the only place for suite-level pass/fail claims; it is still `blocked` / open from the worker perspective.
- Host path re-checks on this page are not suite evidence. Test scope is documented in `docs/test-strategy.md`; pass/fail authority remains with TASK-043.
- Do **not** claim final green acceptance from this docs task.

Companion docs: `docs/jvm-reflection-classloader-design.md`, `docs/android-lua-architecture.md`, `docs/android-lua-verification.md`, `docs/java-interop-model.md`, `docs/serialized-verification.md`, `docs/final-verification.md`.

## Read-only Inspection Commands

Commands for current-host verification (macOS/zsh). These are optional operator checks; workers must not run Gradle/tests/compiles for this task:

```bash
ls -la /Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar
ls -la /Users/dingyi/Downloads/android.jar  # expected absent unless operator drop-in exists

export JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
java -version
jar tf /Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar | rg '^(android/content/Context|android/widget/TextView|android/view/View|java/lang/String)\.class$'

rg -n "jvm\.androidJar|jvm\.classpath|androlua\.imports|URLClassLoader|runLuaLanguageServer" -S src build.gradle.kts README.md
```

## Verification (TASK-508)

No Gradle, compile, or test commands should be run for **TASK-508**. This task is docs-only: review reads the markdown artifact.

Verification of reflected JVM provider behavior, classpath discovery, and documentation consistency is deferred to **TASK-043** serialized verification. Do **not** claim final green, do not unlock TASK-043, and do not run `jvmTest` filters from this worker.
