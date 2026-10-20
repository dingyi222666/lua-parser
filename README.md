# lua-parser

## _work in progress_

A Lua 5.3 lexer, parser, AST, and semantic-analysis toolkit written in pure Kotlin.

The project scope covers Lua 5.3 plus Android-Lua/LuaJava analysis, JVM reflection interop, and a JVM language-server path. Semantic analysis uses the `SemanticPipeline` API.

## Features

- [x] Kotlin Multiplatform support (JVM / JS / Native)
- [x] Parse source to AST
- [x] Transform AST to source code
- [ ] Semantic analysis: provide type information (work in progress)

## Setup

Use JDK 17 for local JVM work.

For Android framework reflection, point the JVM workspace configuration at the platform jar for the API level you want to model, via the `jvm.androidJar` metadata key or `JvmWorkspaceConfiguration.androidJar`. Keep application/library jars in `jvm.classpath`, and reserve `jvm.androidJar` for the Android platform jar. See `docs/android-platform-setup.md` for details.

## Usage

Add the dependency to your Gradle file:

```kotlin
implementation("io.github.dingyi222666:luaparser:1.0.4")
```

**Requires Kotlin 2.1 or newer** — the library is compiled with Kotlin 2.2.0, and older Kotlin toolchains reject its metadata.

### Parser round-trip

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

The no-argument `LuaParser()` constructor defaults to `LuaVersion.ANDROLUA_5_3`, keeping Android-Lua/AndroLua 5.3 syntax available by default. For strict Lua 5.3 grammar:

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

The returned `SemanticAnalysisResult` exposes the `SemanticModel` plus a lightweight summary. The former `SemanticAnalyzer` / `AnalysisResult` / legacy symbol-table API has been retired; see `docs/semantic-compat.md`.

### JVM workspace interop

`JvmWorkspaceEngine` can reflect JVM classes into synthetic Lua modules so `require("String")`, `require("Arrays")`, and similar patterns resolve through the workspace graph.

Metadata-driven integration keys:

- `jvm.classes`: fully-qualified class names to mount directly
- `androlua.imports`: AndroLua-style simple import names resolved through JVM import prefixes
- `jvm.classpath`: newline-separated jar or directory entries used to build a reflective class loader
- `jvm.androidJar`: optional path to an Android platform `android.jar`, appended to the effective reflective classpath
- `jvm.importPrefixes`: newline-separated prefix list used for simple-name import resolution

Or configure the JVM engine directly:

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
        androidJar = "/opt/android/platforms/android-35/android.jar",
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

Source-level Android-Lua patterns recognized by the JVM workspace layer:

- `import "java.lang.String"` exposes `String` as a visible reflected module symbol.
- `import "java.util.*"` resolves top-level classes discoverable from that package on the active JVM classpath/runtime image.
- `local import = require("import")` followed by `import("java.util.Locale")` resolves the reflected class type for the assigned local.
- `luajava.bindClass("java.util.Locale")` resolves the reflected class type for the assigned local.

### Language server

A JVM `lsp4j` server is available under `io.github.dingyi222666.luaparser.lsp` and can be launched over stdio:

```bash
./gradlew runLuaLanguageServer
```

The server maps workspace-backed diagnostics, hover, completion, and goto-definition queries through `LuaWorkspaceQueryFacade`. Workspace symbol search (`workspace/symbol`) matches case-insensitively and ranks name-prefix matches ahead of mid-name substring matches.

Example client settings payload:

```json
{
  "androlua.imports": ["String", "LinearLayout"],
  "jvm.classpath": ["build/test-libs/app.jar"],
  "jvm.androidJar": "/opt/android/platforms/android-34/android.jar",
  "jvm.importPrefixes": ["java.lang", "android.widget"]
}
```

See `docs/language-server-usage.md` for capabilities and request examples.

## Documentation

- [Production readiness guide](docs/production-readiness.md): setup, usage, verification boundaries, known limitations, and extension points.
- [Android-Lua architecture notes](docs/android-lua-architecture.md)
- [Android-Lua import and LuaJava behavior](docs/android-lua-import-luajava.md)
- [Android-Lua compatibility matrix](docs/android-lua-compatibility-matrix.md)
- [Android-Lua library model notes](docs/android-lua-library-models.md)
- [Android-Lua corpus verification](docs/android-lua-verification.md)
- [Android platform setup](docs/android-platform-setup.md)
- [Java interop model guide](docs/java-interop-model.md)
- [JVM reflection and classloader design](docs/jvm-reflection-classloader-design.md)
- [JVM language-server usage](docs/language-server-usage.md)
- [Semantic compatibility policy](docs/semantic-compat.md)
- [Test strategy and inventory](docs/test-strategy.md)

## Validation

- `./gradlew check` is the default local validation path.
- Native host test execution is opt-in with `-PrunNativeHostTests=true`.
- JVM interop coverage lives in `src/jvmTest/kotlin/interop/jvm/JvmWorkspaceEngineTest.kt`; LSP coverage lives in `src/jvmTest/kotlin/lsp/LuaLanguageServiceTest.kt`.

## Publishing

Releases go to Maven Central through the manual `publish` workflow (`.github/workflows/publish.yml`, "Run workflow" only). Bump `publishVersion` in `build.gradle.kts` before every run — Maven Central forbids re-uploading an existing version.

## License

[Apache License 2.0](LICENSE)

## Special thanks

- [GavinHigham/lpil53](https://github.com/GavinHigham/lpil53)
- [fstirlitz/luaparse](https://github.com/fstirlitz/luaparse)
- [Rosemoe/sora-editor](https://github.com/Rosemoe/sora-editor/blob/main/language-java/src/main/java/io/github/rosemoe/sora/langs/java/JavaTextTokenizer.java)
