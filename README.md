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

More usage coming soon.

## Validation

- `./gradlew check` is the supported default local validation path.
- Semantic validation now lives in shared `commonTest` coverage, including workspace query flows.
- Native host test execution is opt-in with `-PrunNativeHostTests=true`.
- Run Windows Kotlin/Native host tests explicitly with `./gradlew -PrunNativeHostTests=true mingwX64Test`.

## Special thanks

[GavinHigham/lpil53](https://github.com/GavinHigham/lpil53)

[fstirlitz/luaparse](https://github.com/fstirlitz/luaparse)

[Rosemose/sora-editor](https://github.com/Rosemoe/sora-editor/blob/main/language-java/src/main/java/io/github/rosemoe/sora/langs/java/JavaTextTokenizer.java)
