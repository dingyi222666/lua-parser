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

## Lua 5.3 builtin overlay inventory (TASK-205)

Authoritative EmmyLua-style resources live under:

`src/commonMain/resources/io/github/dingyi222666/luaparser/semantic/workspace/std/lua53/`

`BuiltinOverlayLoader` mounts them as synthetic providers under `__lua_std__/5.3/...` for `LuaVersion.LUA_5_3` (and as the stdlib base for `ANDROLUA_5_3`).

### Present provider modules and resource members

| Module | Resource members (export surface) |
| --- | --- |
| `coroutine` | `create`, `isyieldable`, `resume`, `running`, `status`, `wrap`, `yield` |
| `debug` | `debug`, `gethook`, `getinfo`, `getlocal`, `getmetatable`, `getregistry`, `getupvalue`, `getuservalue`, `sethook`, `setlocal`, `setmetatable`, `setupvalue`, `setuservalue`, `traceback`, `upvalueid`, `upvaluejoin` |
| `io` | `close`, `flush`, `input`, `lines`, `open`, `output`, `popen`, `read`, `stderr`, `stdin`, `stdout`, `tmpfile`, `type`, `write` |
| `math` | `abs`, `acos`, `asin`, `atan`, `ceil`, `cos`, `deg`, `exp`, `floor`, `fmod`, `huge`, `log`, `max`, `maxinteger`, `min`, `mininteger`, `modf`, `pi`, `rad`, `random`, `randomseed`, `sin`, `sqrt`, `tan`, `tointeger`, `type`, `ult` |
| `os` | `clock`, `date`, `difftime`, `execute`, `exit`, `getenv`, `remove`, `rename`, `setlocale`, `time`, `tmpname` |
| `package` | `config`, `cpath`, `loaded`, `loadlib`, `path`, `preload`, `searchers`, `searchpath` |
| `string` | `byte`, `char`, `dump`, `find`, `format`, `gmatch`, `gsub`, `len`, `lower`, `match`, `pack`, `packsize`, `rep`, `reverse`, `sub`, `unpack`, `upper` |
| `table` | `concat`, `insert`, `move`, `pack`, `remove`, `sort`, `unpack` |
| `utf8` | `char`, `charpattern`, `codepoint`, `codes`, `len`, `offset` |

### Present basic-library globals (resource `global.lua`)

`_G`, `_VERSION`, `assert`, `collectgarbage`, `dofile`, `error`, `getmetatable`, `ipairs`, `load`, `loadfile`, `next`, `pairs`, `pcall`, `print`, `rawequal`, `rawget`, `rawlen`, `rawset`, `require`, `select`, `setmetatable`, `tonumber`, `tostring`, `type`, `xpcall`

Module tables themselves are also catalogued as globals: `coroutine`, `debug`, `io`, `math`, `os`, `package`, `string`, `table`, `utf8`.

### Documented gaps / compatibility-only surfaces

These are intentional incomplete or compatibility-only surfaces. They are locked by `semantic.workspace.BuiltinOverlayLua53CompletenessTddTest` (TASK-205) and must not be treated as full Lua 5.3 stdlib coverage until a follow-up product task expands them.

| Surface | Status | Notes |
| --- | --- | --- |
| `bit32` | **Catalog-only gap** | `globalNames` and `moduleFieldNames["bit32"] = {band}` list bit32, but there is **no** `__lua_std__/5.3/bit32.lua` provider resource/export surface. Full bit32 API (`arshift`, `band`, `bnot`, `bor`, `btest`, `bxor`, `extract`, `lrotate`, `lshift`, `replace`, `rrotate`, `rshift`) is not mounted. |
| `module` | **Catalog-only gap** | Legacy `module` appears in `globalNames` for compatibility, but is **not** part of the Lua 5.3 basic-library resource inventory in `global.lua`. |
| `package.seeall` | **Compatibility shim** | Injected via `compatibilityGlobalsSource` and `moduleFieldNames["package"]`; **not** present on the `package.lua` resource export surface. |
| `moduleFieldNames` | **Seed subset** | Compact seed map used for metadata fingerprinting / early completion seeds (e.g. `math.abs`, `string.format`). Full member inventory lives on provider export surfaces, not this map. |
| `file` userdata (`io` class) | **Out of io inventory** | `io.lua` documents `file` class methods (`seek`, `setvbuf`, and colon-style close/flush/lines/read/write). Provider inventory only models `io.*` members; file-only methods (`seek`, `setvbuf`) are not `io` module members. Shared names (`close`, `flush`, `lines`, `read`, `write`) exist both as `io.*` helpers and as `file:` methods. |
| `builtin.lua` | **Type aliases only** | Primitive class stubs (`nil`, `boolean`, `number`, …); not a requireable stdlib module. |

### Explicit non-goals for this inventory

Pre-5.3 / 5.1–5.2 symbols intentionally absent from provider surfaces (locked by the completeness test):

- `math`: `atan2`, `cosh`, `sinh`, `tanh`, `pow`, `frexp`, `ldexp`, `log10`
- `table`: `maxn`, `foreach`, `foreachi`, `getn`, `setn`
- `package`: `loaders` (replaced by `searchers`), `seeall` (compat shim only)
- `string`: `gfind`
- `debug` / globals: `getfenv`, `setfenv`, `loadstring`, Lua 5.4 `warn`

### Related tests

- Completeness audit: `src/jvmTest/kotlin/semantic/workspace/BuiltinOverlayLua53CompletenessTddTest.kt` (TASK-205)
- Existing loader behavior: `src/commonTest/kotlin/semantic/workspace/BuiltinOverlayLoaderTest.kt`, `src/jvmTest/kotlin/semantic/workspace/BuiltinOverlayLoaderTddTest.kt`

## Validation note

- `./gradlew check` remains the supported default local validation path and runs the shared semantic `commonTest` coverage.
- Windows Kotlin/Native host test execution is opt-in with `-PrunNativeHostTests=true`.
- Use `./gradlew -PrunNativeHostTests=true mingwX64Test` for explicit `mingwX64` host validation when needed.
- TASK-205 verification is review-owned and serialized: `JAVA_HOME=C:/Users/dingyi/.jdks/temurin-17.0.11 ./gradlew.bat jvmTest --tests semantic.workspace.BuiltinOverlayLua53CompletenessTddTest`
