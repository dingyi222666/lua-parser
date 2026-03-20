# Semantic compatibility

Step 25 makes the semantic pipeline the single implementation path for in-repo semantic analysis.

## Primary API

- `io.github.dingyi222666.luaparser.semantic.SemanticPipeline`
- `io.github.dingyi222666.luaparser.semantic.model.SemanticAnalysisResult`
- `io.github.dingyi222666.luaparser.semantic.model.SemanticModel`
- `io.github.dingyi222666.luaparser.semantic.api.*`
- `io.github.dingyi222666.luaparser.semantic.binder.*`
- `io.github.dingyi222666.luaparser.semantic.comments.*`
- `io.github.dingyi222666.luaparser.semantic.checker.*`
- `io.github.dingyi222666.luaparser.semantic.types.model.*`
- `io.github.dingyi222666.luaparser.semantic.types.resolve.*`
- `io.github.dingyi222666.luaparser.semantic.types.syntax.*`

`SemanticPipeline` runs comment attachment, binding, type resolution, checking, and semantic model construction exactly once. All public semantic behavior is expected to flow through that pipeline.

Workspace-level semantic queries now sit one layer above file-local models through `LuaWorkspaceEngine`, `WorkspaceSnapshot`, and `LuaWorkspaceQueryFacade`.

## Compatibility API

The following APIs remain for callers that still import the pre-pipeline surface:

- `SemanticAnalyzer`
- `AnalysisResult`
- legacy `Diagnostic`
- `semantic.symbol.SymbolTable`
- `semantic.symbol.GlobalSymbolTable`
- legacy `semantic.types.*` facades such as `TypeAnnotationParser`
- bridge types under `semantic.types.bridges`

These compatibility APIs do not run a separate analyzer. They translate pipeline output into legacy shapes.

## Compatibility guarantees

- diagnostics exposed by `SemanticAnalyzer().analyze(...)` come from pipeline diagnostics, with only documented legacy translation layered on top
- legacy symbol tables preserve compatibility-oriented scope visibility for existing callers
- legacy type parsing helpers remain available for callers importing `semantic.types.*`

## Removed migration leftovers

The following pre-refactor scaffolding has been removed because it is no longer referenced by the pipeline:

- `semantic.comment.*`
- `semantic.types.TypeContext`
- `semantic.types.TypeInferer`

If you previously consumed those internals directly, migrate to the pipeline, semantic model, and the newer `semantic.comments.*` / `semantic.types.syntax.*` / `semantic.types.resolve.*` packages.

## Validation note

- `./gradlew check` remains the supported default local validation path and runs the shared semantic `commonTest` coverage.
- Windows Kotlin/Native host test execution is opt-in with `-PrunNativeHostTests=true`.
- Use `./gradlew -PrunNativeHostTests=true mingwX64Test` for explicit `mingwX64` host validation when needed.
