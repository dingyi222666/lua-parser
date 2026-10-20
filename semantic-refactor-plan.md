# Semantic Refactor Plan

Base package: `io.github.dingyi222666.luaparser.semantic`

## 1. Target Package Layout

### `semantic/comments`
- `semantic/comments/CommentAttachPass.kt` - walks the AST, groups adjacent comments, attaches structured comment data to target nodes
- `semantic/comments/CommentAttachmentIndex.kt` - immutable lookup API from AST node to attached comment payload
- `semantic/comments/CommentBlockCollector.kt` - collects raw comment blocks from statements and nested blocks
- `semantic/comments/DocCommentParser.kt` - parses `---@param`, `---@return`, `---@class`, `---@type`, etc.
- `semantic/comments/CommentModel.kt` - comment-domain model only (`DocComment`, `DocTag`, `TypeTag`, `ClassTag`, ...)

### `semantic/binder`
- `semantic/binder/BinderPass.kt` - entry point for symbol declaration and scope construction
- `semantic/binder/ScopeGraph.kt` - immutable scope tree for the semantic model
- `semantic/binder/Scope.kt` - single lexical scope with parent/children links and owner node
- `semantic/binder/Symbol.kt` - semantic symbol model independent from checker internals
- `semantic/binder/SymbolTableBuilder.kt` - mutable builder used only during binding
- `semantic/binder/BuiltinSymbolSeeder.kt` - registers builtins before AST binding starts
- `semantic/binder/DeclarationBinder.kt` - binds locals, params, globals, classes, aliases, fields, methods

### `semantic/types/syntax`
- `semantic/types/syntax/TypeSyntax.kt` - sealed syntax tree for declared type syntax
- `semantic/types/syntax/TypeAnnotationParser.kt` - parser from comment text into `TypeSyntax`
- `semantic/types/syntax/TypeSyntaxRenderer.kt` - debug/test rendering for parsed type syntax
- `semantic/types/syntax/TypeParameterSyntax.kt` - generic parameter declarations and bounds

### `semantic/types/model`
- `semantic/types/model/Type.kt` - sealed semantic type model
- `semantic/types/model/PrimitiveType.kt` - primitive singleton types
- `semantic/types/model/FunctionType.kt` - callable and overload-capable function types
- `semantic/types/model/TableType.kt` - fields, methods, index signatures
- `semantic/types/model/UnionType.kt` - union composition helpers
- `semantic/types/model/LiteralType.kt` - literal string/number/boolean types

### `semantic/types/resolve`
- `semantic/types/resolve/TypeSyntaxPass.kt` - pass-3 entry point that resolves attached declared type syntax after binding
- `semantic/types/resolve/TypeSyntaxPassResult.kt` - pass-3 result payload with resolved declared types and declaration-resolution diagnostics
- `semantic/types/resolve/TypeSyntaxResolver.kt` - resolves `TypeSyntax` to semantic `Type`
- `semantic/types/resolve/TypeResolutionContext.kt` - visible type names, generics, aliases, class names
- `semantic/types/resolve/DeclaredTypeRegistry.kt` - stores named declared types gathered during passes
- `semantic/types/resolve/TypeSubstitutor.kt` - generic substitution for resolved member/call types
- `semantic/types/resolve/TypeNormalizer.kt` - alias unwrap, union flattening, canonicalization

### `semantic/checker`
- `semantic/checker/CheckerPass.kt` - entry point for semantic validation over a bound tree
- `semantic/checker/ExpressionTypeEvaluator.kt` - expression-level type inference/evaluation
- `semantic/checker/AssignmentChecker.kt` - assignability and declaration-vs-value checks
- `semantic/checker/CallChecker.kt` - call resolution and argument validation
- `semantic/checker/MemberChecker.kt` - member/index access checks
- `semantic/checker/ReturnChecker.kt` - function return validation
- `semantic/checker/DiagnosticSink.kt` - checker-local diagnostic writer abstraction

### `semantic/model`
- `semantic/model/SemanticModel.kt` - public immutable query API returned by analysis
- `semantic/model/SemanticModelBuilder.kt` - internal assembly of final indexes from pass outputs
- `semantic/model/NodeSymbolIndex.kt` - node/position to symbol lookups
- `semantic/model/NodeTypeIndex.kt` - node to inferred/declared type lookups
- `semantic/model/CompletionProvider.kt` - symbol/member completion assembly from model indexes

### `semantic/api`
- `semantic/api/Symbol.kt` - public symbol shape returned by `SemanticModel`
- `semantic/api/Scope.kt` - public scope shape returned by `SemanticModel`
- `semantic/api/CompletionItem.kt` - public completion item returned by `SemanticModel`
- `semantic/api/Diagnostic.kt` - public diagnostic type returned by analysis/model APIs

### Orchestration Root
- `semantic/SemanticPipeline.kt` - runs the four passes in order and builds `SemanticModel`
- `semantic/SemanticAnalysisResult.kt` - final diagnostics + semantic model payload

## 2. Pass Pipeline

1. `Comment Attach Pass`
   - Input: raw AST
   - Output: `CommentAttachmentIndex`
   - Responsibility: collect adjacent comments, parse doc tags, attach comment/type syntax payload to nodes only
   - Must not: declare symbols, resolve types, emit checker diagnostics

2. `Binder Pass`
   - Input: AST + `CommentAttachmentIndex`
   - Output: `ScopeGraph`, symbol index, declared symbol/type stubs
   - Responsibility: create scopes, declare names, predeclare class/alias/type names, record declaration ownership
   - Must not: perform full expression checking

3. `Type Syntax Pass`
    - Input: binder output + attached type syntax
    - Entrypoint: `TypeSyntaxPass.kt`
    - Output: `TypeSyntaxPassResult` containing resolved declared type registry, per-symbol declared types, and declaration-resolution diagnostics
    - Responsibility: resolve comment-declared types, generics, class inheritance, aliases, function signatures
    - Must not: walk all executable expressions for diagnostics beyond declaration resolution failures

4. `Checker Pass`
   - Input: AST + binder output + resolved declared types
   - Output: inferred expression/node types, diagnostics, completion/member metadata
   - Responsibility: evaluate expressions, validate assignments/calls/returns/member access, build final query indexes

## 3. Migration Mapping From Current Files

| Current file | New structure |
| --- | --- |
| `semantic/SemanticAnalyzer.kt` | split into `semantic/SemanticPipeline.kt`, `semantic/binder/BinderPass.kt`, `semantic/types/resolve/TypeSyntaxPass.kt`, `semantic/types/resolve/TypeSyntaxResolver.kt`, `semantic/checker/CheckerPass.kt`, `semantic/model/SemanticModelBuilder.kt` |
| `semantic/SemanticAnalyzer.kt:512` (`AnalysisResult`) | migrate to `semantic/SemanticAnalysisResult.kt`, then narrow public surface toward `semantic/model/SemanticModel.kt` |
| `semantic/SemanticAnalyzer.kt:518` (`Diagnostic`) | move to `semantic/api/Diagnostic.kt` as the API-safe diagnostic type used by passes and `SemanticModel` |
| `semantic/comment/CommentProcessor.kt` | split into `semantic/comments/CommentAttachPass.kt`, `semantic/comments/CommentBlockCollector.kt`, `semantic/comments/DocCommentParser.kt` |
| `semantic/comment/CommentModel.kt` | move to `semantic/comments/CommentModel.kt` |
| `semantic/comment/CommentModel.kt:16` (`CommentIndex`) | replace with `semantic/comments/CommentAttachmentIndex.kt`; keep a temporary typealias/bridge during migration |
| `semantic/types/TypeAnnotationParser.kt` | move to `semantic/types/syntax/TypeAnnotationParser.kt` |
| `semantic/types/TypeSyntax.kt` | move to `semantic/types/syntax/TypeSyntax.kt`; extract generic declarations to `semantic/types/syntax/TypeParameterSyntax.kt` if needed |
| `semantic/types/Type.kt` | move to `semantic/types/model/Type.kt`; split large nested types into dedicated files under `semantic/types/model` |
| `semantic/types/TypeContext.kt` | split into `semantic/types/resolve/TypeResolutionContext.kt` and checker-local state object under `semantic/checker` |
| `semantic/types/TypeInferer.kt` | move and split into `semantic/checker/ExpressionTypeEvaluator.kt`, `semantic/checker/CallChecker.kt`, `semantic/checker/MemberChecker.kt`, `semantic/checker/AssignmentChecker.kt` |
| `semantic/symbol/SymbolTable.kt` | replace internal binding storage with `semantic/binder/Scope.kt`, `semantic/binder/ScopeGraph.kt`, `semantic/binder/SymbolTableBuilder.kt`; expose API-safe `semantic/api/Scope.kt` and `semantic/api/Symbol.kt` through `SemanticModel` |
| `semantic/symbol/GlobalSymbolTable.kt` | fold into binder/global scope support in `semantic/binder/BuiltinSymbolSeeder.kt` and `semantic/binder/ScopeGraph.kt` |

### Package Rename Hazards
- `semantic.comment` -> `semantic.comments`: do not hard-move imports first; add bridge/typealias layer and update call sites incrementally.
- `semantic.types.Type` -> `semantic.types.model.Type`: this touches a wide import surface and should be protected by compile-safe aliases before file moves.
- current symbol types -> new binder/model locations: separate internal binder storage from public API types so `SemanticModel` does not leak mutable builder classes.

## 4. `SemanticModel` API Goals

Public API should be immutable, query-oriented, and independent from pass internals.

API ownership plan:
- `Diagnostic`, `CompletionItem`, `Scope`, and `Symbol` are public API types because `SemanticModel` returns them directly.
- Public query-facing types live under `semantic/api` to keep them stable while binder/checker internals evolve.
- `semantic/binder` keeps internal construction/state objects; `semantic/model` adapts those to `semantic/api` objects when building the final model.

```kotlin
interface SemanticModel {
    fun getSymbolAt(position: Position): Symbol?
    fun getTypeAt(node: BaseASTNode): Type?
    fun getDeclaredType(symbol: Symbol): Type?
    fun getMembers(type: Type): List<Symbol>
    fun getCompletionsAt(position: Position): List<CompletionItem>
    fun getDiagnostics(): List<Diagnostic>
    fun getScopeAt(position: Position): Scope?
    fun getSymbolDeclarations(name: String): List<Symbol>
}
```

Implementation goals:
- Position queries use prebuilt indexes, not AST rescans
- `getTypeAt(node)` returns inferred type for expressions and declared type for declarations where applicable
- `getDeclaredType(symbol)` distinguishes declaration syntax from inferred runtime type
- `getMembers(type)` works for tables, classes, aliases, unions, and resolved metatable-like member surfaces if introduced later
- `getCompletionsAt(position)` composes lexical scope symbols plus member completions when the cursor is on a member access base
- `CompletionItem` carries enough data for label, kind, source symbol, optional detail text, and replacement range without exposing checker internals

## 5. Testing Strategy

### Parser
- type annotation parsing unit tests
- doc tag parsing unit tests
- malformed annotation recovery tests

### Binder
- scope creation tests
- shadowing and visibility tests
- class/alias/function/local declaration tests

### Checker
- assignment compatibility tests
- call/overload validation tests
- member/index access tests
- return type validation tests

### Integration
- end-to-end semantic pipeline tests returning `SemanticModel`
- symbol/type/completion query tests at concrete positions
- mixed comment + binding + checker scenarios

### Regression
- one test per fixed semantic bug
- snapshot-style diagnostics for representative Lua samples
- migration parity tests comparing old analyzer behavior during transition where practical

## 6. 25-Step Migration Checklist

1. Add `semantic-refactor-plan.md` with target packages, pass boundaries, and migration order.
2. Add compile-safe API shells: `semantic/api/Diagnostic.kt`, `semantic/api/Symbol.kt`, `semantic/api/Scope.kt`, and `semantic/api/CompletionItem.kt`.
3. Introduce `semantic/SemanticPipeline.kt` as an empty orchestration shell beside the current analyzer.
4. Add `semantic/SemanticAnalysisResult.kt` and bridge it from the current `AnalysisResult` shape.
5. Add `semantic/comments` package with bridge types/typealiases for `semantic.comment` so imports keep compiling.
6. Extract raw comment block collection from `CommentProcessor` into `CommentBlockCollector` without moving packages yet.
7. Extract doc-tag parsing into `DocCommentParser` without changing public call sites.
8. Introduce `CommentAttachPass` returning `CommentAttachmentIndex`, plus a compatibility bridge from current `CommentIndex`.
9. Migrate comment call sites from `semantic.comment` to `semantic.comments`, then remove the bridge.
10. Create `semantic/binder` package with internal `ScopeGraph` and `SymbolTableBuilder`, plus adapters to public `semantic/api` types.
11. Extract builtin registration into `BuiltinSymbolSeeder`.
12. Extract declaration binding logic from `SemanticAnalyzer` into `DeclarationBinder` while preserving current result types.
13. Implement `BinderPass` to build scopes and declarations from AST plus attached comments.
14. Create `semantic/types/syntax` package with compatibility shims for current `semantic.types` syntax imports.
15. Split syntax-only generic/type-parameter declarations out of mixed type files.
16. Create `semantic/types/model` package and add compile-safe aliases/bridges for current `semantic.types.Type` references.
17. Create `semantic/types/resolve/DeclaredTypeRegistry.kt` and `TypeSyntaxPassResult.kt` for named classes and aliases.
18. Implement `TypeResolutionContext` to replace binder-like state inside `TypeContext`.
19. Implement `TypeSyntaxResolver` for `@type`, `@param`, `@return`, `@class`, `@alias`, and `@field` resolution.
20. Implement `TypeSyntaxPass.kt` and run it before executable checking, keeping a compatibility path for legacy analyzer entrypoints.
21. Create `semantic/checker` package and move expression inference to `ExpressionTypeEvaluator`.
22. Split assignment, call, member, and return validation into focused checker components.
23. Implement `CheckerPass` to produce diagnostics plus inferred node types.
24. Create `semantic/model/SemanticModel.kt` and `SemanticModelBuilder.kt`, then build indexes for symbol lookup, declared type lookup, inferred node types, and completions.
25. Switch public analysis entry point from legacy `SemanticAnalyzer`/`AnalysisResult` to `SemanticPipeline` + `SemanticModel`, then remove obsolete analyzer, package bridges, and type-context glue after tests pass.

## Step 2 Risks

- `SemanticAnalyzer.kt` currently mixes pass orchestration with concrete binding/checking logic, so the first extraction can accidentally change execution order.
- `TypeContext.kt` appears to carry both resolution state and checker state; step 2 should avoid moving it wholesale before responsibilities are split.
- Existing global-vs-local declaration behavior is embedded in current symbol table usage, so binder boundaries need snapshot tests early.
- Comment attachment adjacency rules are already behavior-defining; preserve them before changing package names or APIs.
