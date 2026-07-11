# lua-parser

## _work in progress_

A Lua 5.3 lexer, parser, AST, and semantic-analysis toolkit written in pure Kotlin.

The current project scope is Lua 5.3 support plus ongoing Android-Lua/LuaJava analysis, JVM reflection interop, and a JVM language-server path. These areas are active work; README examples describe the intended integration surface without claiming final serialized verification has completed.

Semantic analysis now uses the `SemanticPipeline` API. Legacy analyzer entry points remain available as compatibility wrappers over the pipeline.

## Features

- [X] Kotlin Multiplatform support (JVM / JS / Native)
- [x] Parse source to AST
- [x] Transform AST to source code
- [ ] Semantic analysis. Provide type information (Work in progress)

## Setup

Use JDK 17 for local JVM work. On macOS, a typical session sets `JAVA_HOME` (Corretto 17) before invoking Gradle or IDE import:

```bash
export JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
```

For Android-Lua and LuaJava analysis, keep the Android-Lua source checkout outside this repository and record its path in your worker notes or IDE run configuration. The historical analysis docs use that checkout as read-only reference material for import, LuaJava, JNI, asset, and helper-library behavior.

For Android framework reflection, point the JVM workspace configuration at the platform jar for the API level you want to model. On this macOS host (2026-07-11 re-check) the Android SDK platform jar is **present** at:

```text
/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar
```

Optional alternate host path when the SDK tree is not used: `/Users/dingyi/Downloads/android.jar` (currently absent on this machine). Prefer the SDK path above; never hard-code Windows `G:/` paths. See `docs/android-platform-setup.md` for discovery, skip policy, and metadata keys.

Use the `jvm.androidJar` metadata key or `JvmWorkspaceConfiguration.androidJar` for that path. Keep application/library jars in `jvm.classpath`, and reserve `jvm.androidJar` for the Android platform jar.


### Documentation map

- [Production readiness guide](docs/production-readiness.md): setup, usage, Android-Lua inputs, verification boundaries, known limitations, and extension points.
- [Android-Lua architecture notes](docs/android-lua-architecture.md): read-only source layout and fixture candidates from `/Users/dingyi/projects/java_projects/Android-Lua/app/src/main`.
- [Android-Lua import and LuaJava behavior](docs/android-lua-import-luajava.md): runtime patterns modeled by parser, semantic, interop, and LSP work.
- [Android-Lua compatibility matrix](docs/android-lua-compatibility-matrix.md): supported, partial, deferred, and out-of-scope behavior.
- [Android-Lua library model notes](docs/android-lua-library-models.md): helper modules, overlays, stubs, and type-model priorities.
- [Android-Lua corpus verification](docs/android-lua-verification.md): external corpus manifest usage and TASK-043 verification command.
- [Android platform setup](docs/android-platform-setup.md): `android.jar` acquisition, metadata keys, and classloader expectations.
- [Java interop model guide](docs/java-interop-model.md): Java type shapes, LuaJava helper mapping, and extension points.
- [JVM reflection and classloader design](docs/jvm-reflection-classloader-design.md): provider loading, package scanning, Android jar behavior, and limitations.
- [JVM language-server usage](docs/language-server-usage.md): launch shape, LSP capabilities, settings, and request examples.
- [Semantic compatibility APIs](docs/semantic-compat.md): pipeline-backed legacy API behavior.
- [Serialized verification workflow](docs/serialized-verification.md): one-at-a-time Gradle/test execution and lock rules.
- [Test strategy and inventory](docs/test-strategy.md): parser, semantic, workspace, interop, LSP, and TDD inventory accounting.

## Usage

- Add the dependency to your gradle file

```kotlin
implementation("io.github.dingyi222666:luaparser:1.0.3")
```

Minimal parser round-trip example:

```kotlin
import io.github.dingyi222666.luaparser.lexer.LuaLexer
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.source.AST2Lua

fun main() {
    val lexer = LuaLexer("print('hello world')")
    val parser = LuaParser()

    val root = parser.parse(lexer)

    println(AST2Lua().asCode(root))
}
```

### Parser version policy

The public no-argument `LuaParser()` constructor intentionally defaults to `LuaVersion.ANDROLUA_5_3`. That keeps Android-Lua/AndroLua 5.3 syntax available by default for current parser, semantic, interop, and language-server work.

For strict Lua 5.3 grammar, construct the parser explicitly:

```kotlin
val parser = LuaParser(luaVersion = LuaVersion.LUA_5_3)
```

In strict Lua 5.3 mode, Android-Lua keywords such as `continue`, `when`, `switch`, `case`, `default`, and `lambda` are not enabled as parser keywords.

### Semantic analysis

Use `SemanticPipeline` as the primary file-local semantic entry point. For workspace-level module resolution and path-based queries, use `LuaWorkspaceEngine` plus `LuaWorkspaceQueryFacade`.

```kotlin
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline

fun main() {
    val chunk = LuaParser().parse(
        """
        ---@type string
        local name = "lua"
        return name
        """.trimIndent()
    )

    val result = SemanticPipeline().analyze(chunk)
    val symbol = result.model.getSymbolAt(Position(2, 11))

    println(symbol?.name)
    println(result.summary.diagnosticCount)
}
```

The returned `SemanticAnalysisResult` exposes the `SemanticModel` plus a lightweight summary.

If you still depend on `SemanticAnalyzer`, `AnalysisResult`, or legacy symbol tables, they are kept as pipeline-backed compatibility APIs. See `docs/semantic-compat.md`.

### JVM workspace interop

`JvmWorkspaceEngine` can reflect JVM classes into synthetic Lua modules so `require("String")`, `require("Arrays")`, and similar patterns resolve through the workspace graph.

For metadata-driven integration, use these keys:

- `jvm.classes`: fully-qualified class names to mount directly
- `androlua.imports`: AndroLua-style simple import names that should be resolved through JVM import prefixes
- `jvm.classpath`: newline-separated jar or directory entries used to build a reflective class loader
- `jvm.androidJar`: optional path to an Android platform `android.jar`; appended to the effective reflective classpath
- `jvm.importPrefixes`: newline-separated prefix list used for simple-name import resolution

You can also configure the JVM engine directly with `JvmWorkspaceConfiguration`:

```kotlin
val engine = JvmWorkspaceEngine(
    configuration = JvmWorkspaceConfiguration(
        classes = linkedSetOf("java.util.Locale"),
        androluaImports = listOf("String"),
        classpathEntries = listOf("libs/example.jar"),
        androidJar = "/opt/android/platforms/android-34/android.jar",
        importPrefixes = listOf("java.lang", "android.widget")
    )
)
```

The structured configuration is merged with per-workspace metadata, letting embedding applications provide a default class loader or classpath while still honoring workspace-specific AndroLua imports.

Minimal workspace query example:

```kotlin
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceQueryFacade
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath

fun main() {
    val path = VirtualPath.of("main.lua")
    val configuration = JvmWorkspaceConfiguration(
        androidJar = "/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar",
        androluaImports = listOf("TextView"),
        importPrefixes = listOf("java.lang", "android.widget")
    )
    val input = LuaWorkspaceInput(
        files = mapOf(
            path to """
            require "import"
            import "android.widget.TextView"
            local view = TextView(activity)
            return view
            """.trimIndent()
        ),
        metadata = configuration.applyToMetadata(emptyMap())
    )

    val snapshot = JvmWorkspaceEngine(configuration = configuration).build(input).snapshot
    val queries = LuaWorkspaceQueryFacade(snapshot)

    println(queries.diagnostics(path))
    println(queries.hover(path, Position(3, 15))?.typeInfo?.displayName)
}
```

Source-level Android-Lua patterns are also recognized by the JVM workspace layer:

- `import "java.lang.String"` exposes `String` as a visible reflected module symbol.
- `import "java.util.*"` resolves all top-level classes discoverable from that package on the active JVM classpath/runtime image, making them available as reflected module symbols.
- `local import = require("import")` followed by `import("java.util.Locale")` resolves the reflected class type for the assigned local.
- `luajava.bindClass("java.util.Locale")` resolves the reflected class type for the assigned local.

### Android and AndroLua setup notes

To reproduce Android class resolution on the JVM side, point `jvm.androidJar` at the platform jar for the SDK level you want to model. If you already have a combined classpath, place your dependency jars in `jvm.classpath` and the platform jar in `jvm.androidJar` so the reflective provider can resolve Android framework classes alongside your own libraries.

Example metadata payload:

```kotlin
val metadata = mapOf(
    "androlua.imports" to "String\nLinearLayout",
    "jvm.classpath" to "build/test-libs/app.jar",
    "jvm.androidJar" to "/opt/android/platforms/android-34/android.jar",
    "jvm.importPrefixes" to "java.lang\nandroid.widget"
)
```

### Language server

A JVM `lsp4j` server is now available under `io.github.dingyi222666.luaparser.lsp` and can be launched over stdio with:

```bash
./gradlew runLuaLanguageServer
```

The current server implementation maps workspace-backed diagnostics, hover, completion, and goto-definition queries through `LuaWorkspaceQueryFacade`.

Example client settings payload:

```json
{
  "androlua.imports": ["String", "LinearLayout"],
  "jvm.classpath": ["build/test-libs/app.jar"],
  "jvm.androidJar": "/opt/android/platforms/android-34/android.jar",
  "jvm.importPrefixes": ["java.lang", "android.widget"]
}
```

This configuration is consumed by the workspace service and serialized into the same metadata keys used by the reflective JVM workspace engine.

### Representative verification path

A minimal end-to-end local verification flow, to be run only in the serialized verification phase during coordinated waves, is:

1. Start the language server with `./gradlew runLuaLanguageServer`.
2. Open a Lua document containing `local String = require("String")` and set client configuration with `androlua.imports = ["String"]`.
3. Request hover on `String.__class.length` and verify the server reports `java.lang.String`.
4. Request goto definition on `String.__class` and verify the target URI resolves to `file:///__jvm__/classes/java/lang/String.lua`.
5. Request completion on `Arrays.` after configuring `jvm.classes = ["java.util.Arrays"]` and verify `asList` is offered.

For a fuller setup and acceptance checklist, see `docs/production-readiness.md`.

## Validation

- During parallel code and documentation waves, do not run Gradle, tests, or compile tasks. Required checks are deferred to the dedicated serialized verification phase, currently tracked by `TASK-043`.
- Outside coordinated worker waves, `./gradlew check` remains the supported default local validation path.
- Semantic validation now lives in shared `commonTest` coverage, including workspace query flows.
- Native host test execution is opt-in with `-PrunNativeHostTests=true`.
- Run Windows Kotlin/Native host tests explicitly with `./gradlew -PrunNativeHostTests=true mingwX64Test`.
- JVM interop coverage currently lives in `src/jvmTest/kotlin/interop/jvm/JvmWorkspaceEngineTest.kt`.
- LSP coverage currently lives in `src/jvmTest/kotlin/lsp/LuaLanguageServiceTest.kt`.
- Runnable JVM language server entry point: `./gradlew runLuaLanguageServer`.
- Final production acceptance is not claimed until `TASK-043` serialized verification and the `TASK-037` acceptance audit complete.

## Special thanks

[GavinHigham/lpil53](https://github.com/GavinHigham/lpil53)

[fstirlitz/luaparse](https://github.com/fstirlitz/luaparse)

[Rosemose/sora-editor](https://github.com/Rosemoe/sora-editor/blob/main/language-java/src/main/java/io/github/rosemoe/sora/langs/java/JavaTextTokenizer.java)
