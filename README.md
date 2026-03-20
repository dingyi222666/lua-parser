# lua-parser

## _work in progress_

A Lua 5.3 Lexer & Parser written in pure Kotlin.

Semantic analysis now uses the `SemanticPipeline` API. Legacy analyzer entry points remain available as compatibility wrappers over the pipeline.

## Features

- [X] Kotlin Multiplatform support (JVM / JS / Native)
- [x] Parse source to AST
- [x] Transform AST to source code
- [ ] Semantic analysis. Provide type information (Work in progress)

## Usage

- Add the dependency to your gradle file

```kotlin
implementation("io.github.dingyi222666:luaparser:1.0.3")
```

Ok. Use it like this:

```kotlin
val lexer = LuaLexer("print('hello world')")
val parser = LuaParser()

val root = parser.parse(lexer)

println(AST2Lua().asCode(root))
```

### Semantic analysis

Use `SemanticPipeline` as the primary file-local semantic entry point. For workspace-level module resolution and path-based queries, use `LuaWorkspaceEngine` plus `LuaWorkspaceQueryFacade`.

```kotlin
val chunk = LuaParser().parse(
    """
    ---@type string
    local name = "lua"
    """.trimIndent()
)

val result = SemanticPipeline().analyze(chunk)
val symbol = result.model.getSymbolAt(Position(2, 11))

println(symbol?.name)
println(result.summary.diagnosticCount)
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

A minimal end-to-end local verification flow is:

1. Start the language server with `./gradlew runLuaLanguageServer`.
2. Open a Lua document containing `local String = require("String")` and set client configuration with `androlua.imports = ["String"]`.
3. Request hover on `String.__class.length` and verify the server reports `java.lang.String`.
4. Request goto definition on `String.__class` and verify the target URI resolves to `file:///__jvm__/classes/java/lang/String.lua`.
5. Request completion on `Arrays.` after configuring `jvm.classes = ["java.util.Arrays"]` and verify `asList` is offered.

More usage coming soon.

## Validation

- `./gradlew check` is the supported default local validation path.
- Semantic validation now lives in shared `commonTest` coverage, including workspace query flows.
- Native host test execution is opt-in with `-PrunNativeHostTests=true`.
- Run Windows Kotlin/Native host tests explicitly with `./gradlew -PrunNativeHostTests=true mingwX64Test`.
- JVM interop coverage currently lives in `src/jvmTest/kotlin/interop/jvm/JvmWorkspaceEngineTest.kt`.
- LSP coverage currently lives in `src/jvmTest/kotlin/lsp/LuaLanguageServiceTest.kt`.
- Runnable JVM language server entry point: `./gradlew runLuaLanguageServer`.

## Special thanks

[GavinHigham/lpil53](https://github.com/GavinHigham/lpil53)

[fstirlitz/luaparse](https://github.com/fstirlitz/luaparse)

[Rosemose/sora-editor](https://github.com/Rosemoe/sora-editor/blob/main/language-java/src/main/java/io/github/rosemoe/sora/langs/java/JavaTextTokenizer.java)
