# Android-Lua import resolution order

Date: 2026-07-12  
Task: TASK-506 (docs-only refresh)

Documentation of static-analysis resolution order for `require "import"`, Android-Lua `import` / `import "..."`, and `luajava.bindClass` (and related LuaJava class-load helpers), after TASK-241 negative-path corpus work and the TASK-176 path-scoped import-surface preservation lane. Refreshed for **post-TASK-184 / pre-TASK-043** documentation accuracy on this macOS host.

**Honesty bound (post-TASK-184, pre-TASK-043):** this file is docs-only. It describes intended static resolution order and host analysis classpath assumptions. It does **not** claim final green acceptance, suite green, or inventory finality. Product-surface library stubs / overlay work ending in **TASK-184** are modeled and review-accepted for focused surfaces only. Path-scoped import preservation (**TASK-176**) and negative-path corpus (**TASK-241**) remain documentation anchors for behavior expectations, not a claim that every dual-path query surface is green. Serialized Gradle/test verification remains review-owned under **TASK-043** (`blocked`); final acceptance remains **TASK-037** (`blocked`). Inventory recount / freeze follow-ups (for example TASK-125) are **inventory not final until TASK-043**. No Gradle, compile, or test command was run for this documentation task. Never hard-code Windows `G:/` paths.

## Host analysis environment (macOS only)

Reflective class resolution for Android framework packages (`android.view.*`, `android.widget.*`, etc.) depends on a host `android.jar` when operators enable JVM reflection. On this host, docs and operator notes use **macOS** paths only:

| Item | Host value | Status (2026-07-12 TASK-506 re-check) |
| --- | --- | --- |
| JDK for JVM tests / docs examples | Amazon Corretto 17: `/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home` | Present; force as `JAVA_HOME` (do not rely on shell default OpenJDK 26) |
| Preferred Android platform jar | `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar` | **Present** (27,092,450 bytes; Android SDK Platform 35) |
| Optional drop-in jar | `/Users/dingyi/Downloads/android.jar` | **Absent** on this host; allowed only when present via explicit `jvm.androidJar` |
| Android SDK root | `/Users/dingyi/Library/Android/sdk` (`ANDROID_HOME` / `ANDROID_SDK_ROOT` when set) | Present |

**Dual-path `android.jar` policy (Downloads + SDK only):** prefer explicit metadata `jvm.androidJar` pointing at the SDK `android-35` path for reproducible analysis. Use `/Users/dingyi/Downloads/android.jar` only when that file exists and is supplied explicitly — product discovery does **not** auto-select Downloads. If neither host jar is available, **skip** reflective Android framework mounting; do not invent classpath entries. **Never hard-code `G:/Android/Sdk/...` or other Windows-only paths** for this host. Full setup notes: [android-platform-setup.md](./android-platform-setup.md).

Without a present `android.jar` (or other configured classpath), concrete Android class mounts and package enumeration are incomplete even when static resolution order below is correct. That incompleteness is expected skip behavior, not final acceptance.

## Related documents and tasks

- Runtime and overlay study: [android-lua-import-luajava.md](./android-lua-import-luajava.md)
- Host classpath / Corretto 17 / dual-path jar policy: [android-platform-setup.md](./android-platform-setup.md)
- Compatibility honesty matrix: [android-lua-compatibility-matrix.md](./android-lua-compatibility-matrix.md)
- Java interop type model: [java-interop-model.md](./java-interop-model.md)
- Classloader policy: [jvm-reflection-classloader-design.md](./jvm-reflection-classloader-design.md)
- TASK-176 — preserve Android-Lua import surfaces under scoped activation (`tasks/TASK-176.md`)
- TASK-184 — Android-Lua library stub fixture / type surfaces restored (focused; not TASK-043 green)
- TASK-241 — Android-Lua import fact negative-path corpus (`tasks/TASK-241.md`)
- TASK-043 — serialized verification (still `blocked`; owns final suite verification)
- TASK-037 — final acceptance (still `blocked`)

## Layers

Resolution is layered. Later layers only run when earlier layers do not already answer the query.

1. **Document facts** — `DocumentFactsCollector` records static string targets from source (`sourceImports`, `jvmClassLoads`) without evaluating dynamic expressions.
2. **Workspace graph / require** — `WorkspaceModuleGraphBuilder` + `WorkspaceModuleResolver.resolveRequire` choose a module provider for `require "..."`.
3. **JVM import target resolution** — `JvmClassModuleProvider` parses import strings, applies prefixes/classloaders, and mounts class/package providers under `__jvm__/...`.
4. **Path-scoped activation** — `JvmWorkspaceEngine` / `WorkspaceModuleResolver` expose configured imports workspace-wide and source imports only for the current file (TASK-176 surface).
5. **Query surfaces** — definitions, completions, and imported symbols read active providers and imported-symbol maps; they do not re-parse runtime `import.lua`.

Runtime Android-Lua `import.lua` order (require → bindClass → dex loaders) is described in [android-lua-import-luajava.md](./android-lua-import-luajava.md). Static analysis approximates that order with reflection/classpath providers instead of live dex loaders. Reflective Android hits on this host assume Corretto 17 + present `android-35` `android.jar` as above when metadata enables them.

---

## 1. `require "import"` resolution order

### Graph provider selection

When multiple providers claim the same module name, `WorkspaceModuleGraphBuilder` keeps the first provider after sorting by source priority. Lower rank wins:

1. Workspace / virtual path providers (`VIRTUAL_PATH`, user files)
2. Extra providers (JVM class/package mounts, other engine extras)
3. Standard-library overlay (`STANDARD_LIBRARY_OVERLAY`, including AndroLua 5.3 `import` / `luajava`)

So a workspace file that itself provides module `"import"` (for example `import.lua` in the project) wins over the Android-Lua builtin overlay.

### `WorkspaceModuleResolver.resolveRequire`

For consumer path `P` and module name `M`:

1. If `snapshot.graph.resolvedDependencies[P]` already records a dependency for `M`, use that provider and its export surface.
2. Else if `M == "import"`, use `activeProvider("import")` when that provider has an export surface (typically the AndroLua overlay at `__lua_std__/androlua5.3/import.lua` when no workspace file claimed it).
3. Else if `M == "import"` and the builtin overlay still exposes global name `import`, synthesize a minimal import surface from the overlay globals path.
4. Else resolve fails (`null`).

### Negative / non-activating paths

- Dynamic `require(name)` with a non-literal name does not create a resolved require edge and does not activate Android-Lua import mode.
- Missing overlay / missing workspace provider for `"import"` yields no import module surface.
- Sibling-file `require "import"` activation must not leak source-import symbols into other files; current-file preservation is owned by TASK-176 and sibling leakage guards by TASK-155.

---

## 2. Android-Lua `import` / `import "..."` resolution order

### Fact collection (static)

`DocumentFactsCollector` only records **literal string** import targets:

| Source shape | Recorded facts |
| --- | --- |
| `import "pkg.Class"` / `import("pkg.Class")` | `sourceImports` + `jvmClassLoads` with `IMPORT_CALL` |
| `import "pkg.*"` | `sourceImports` only (wildcard is not a concrete class load) |
| `import { "A", "B" }` | One fact per sequential string entry (`ipairs`-style) |
| `import "dex:pkg.Class"` / `import "prefix:pkg.Class"` | Target string kept as written for later parse |

Not recorded as import targets (negative paths, TASK-241):

- Missing args: `import()`
- Dynamic / concatenated: `import(className)`, `import("a" .. "b")`
- Empty tables, nested tables, named-only table keys, non-string array entries
- Alias used before registration: `local androidImport = import; androidImport("...")` before a real `import` binding is established

Malformed strings that **are** still recorded without throw:

- Empty / whitespace targets: `import("")`, `import("   ")` → facts with empty/blank target
- Bare `".*"` / `"*"` wildcards → source-import only; no package provider mount

### Target normalization

Shared parse shape (`JvmClassModuleProvider.parseImportTarget` / resolver `normalizeImportTarget`):

1. Strip optional leading `import ` prefix and trim.
2. Empty after trim → no resolvable target.
3. If a `:` separates a non-empty path prefix and a trailing class/package fragment (`prefix:className`), keep:
   - `pathPrefix` = left of last `:`
   - `className` = right of last `:`
4. Otherwise the whole string is the class/package target (no path prefix).

Inner-class spelling candidates for dotted/binary names (`candidateClassNames`):

1. Exact text
2. Underscore → `$` rewrite when `_` is present (Android-Lua compatibility)
3. Combinations that replace selected `.` separators with `$` for nested classes

### Concrete class resolution (`resolveClassLoads`)

Given a normalized target and configuration:

1. Choose classloader:
   - No path prefix → workspace reflection classloader (configured classpath / android jar / base).
   - Path prefix with a mapped classpath entry → child `URLClassLoader` over that entry, parent = fallback.
   - Path prefix **without** a mapped entry → **fallback classloader** (still attempts resolution; also emits unsupported-prefix diagnostic — see below).
2. Empty `className` → empty result.
3. Wildcard `pkg.*` → package enumeration (see package order).
4. Target contains `.` → try `candidateClassNames(target)` with `Class.forName(..., false, loader)` until first hit.
5. Simple name (no `.`) → for each configured `importPrefixes` in order, try `candidateClassNames("$prefix.$simple")` until first hit.

Default import prefixes (when configuration does not override) include:

`java.lang`, `java.util`, `java.io`, `android.app`, `android.content`, `android.view`, `android.widget`, `com.androlua`.

Wildcard imports also **append** their package prefix to `importPrefixes` for later simple-name resolution (`JvmWorkspaceEngine.configurationWithWildcardImportPrefixes`).

On this macOS host, reflective hits for Android framework types require the present SDK jar (or an explicit present Downloads override) on the effective classpath / `jvm.androidJar`. Without it, analysis falls back to curated static Android-framework overlays where available and otherwise leaves Android targets unresolved — skip, not throw.

### Package / wildcard resolution

For `import "pkg.*"` (and prefixed forms whose className side is `pkg.*`):

1. `wildcardPackageName` requires a non-blank package before `.*` — bare `".*"` / `"*"` / empty → no package provider.
2. Enumerate top-level classes under that package from:
   - Classloader resources (`file` / `jar` / `jrt`)
   - Additional `jrt` scan
   - Configured reflection classpath entries (directories and jars), including host `android-35` `android.jar` when configured and present
3. Mount provider at `__jvm__/packages/<pkg/with/slashes>.lua` when at least one class is found.
4. Imported package symbol alias is the package name (module name = package name); members are simple class names.

### Activation scope (TASK-176 lane)

In `JvmWorkspaceEngine.workspaceContext`:

1. **Configured** `androlua.imports` / metadata imports → workspace-wide `importedSymbols`.
2. **Source** imports from the **current file's** document facts → merged into that file's active map only.
3. `resolveImportTarget` may still resolve dynamic string targets via `importedSymbolForTarget` even when not yet on the activation set (engine-level JVM resolution).
4. `WorkspaceModuleResolver.activeImportTargets` unions `sourceImports` with `jvmClassLoads` of kind `IMPORT_CALL` for path-scoped symbol queries.

Sibling-file source imports must not appear in another file's imported-symbol map (TASK-155 / TASK-176).

### Provider mount order for a workspace snapshot

`JvmWorkspaceEngine.extraProviders`:

1. Collect document facts for all input files.
2. Expand wildcard prefixes into configuration.
3. Discover concrete classes from source imports + LuaJava load facts.
4. `packageProvidersFor(wildcard targets)` → package mounts.
5. `providersFor(classes + imports)` → class mounts under `__jvm__/classes/<binary/name>.lua`.
6. Return class providers **plus** package providers (package map overwrites same keys if any collide; paths differ by design).

---

## 3. `luajava.bindClass` (and related helpers) resolution order

### Fact kinds

`DocumentFacts.JvmClassLoadKind` records literal string targets for:

| Kind | Typical call |
| --- | --- |
| `BIND_CLASS_CALL` | `luajava.bindClass("pkg.Class")` |
| `NEW_INSTANCE_CALL` | `luajava.newInstance("pkg.Class", ...)` |
| `CREATE_PROXY_CALL` | `luajava.createProxy("iface1,iface2", table)` (segments) |
| `LOAD_LIB_CALL` | `luajava.loadLib("pkg.Class", "method")` |
| `IMPORT_CALL` | `import(...)` (also `sourceImports`) |
| `CREATE_ARRAY_CALL` | array helpers (not used for class discovery in engine) |

### Positive resolution

1. Collect fact target string (static literal only).
2. Resolve with the same `resolveClassLoads` / `importedClassName` path as explicit imports (prefixes, inner-class candidates, classloaders).
3. Successful classes are added to workspace `classes` for provider mounting (`collectSourceDiscoveredClasses`).
4. Bound class providers expose `ModuleType` with `__class` instance surface and static members/methods.

Chained local aliases (`local bind = luajava.bindClass; local again = bind; again("...")`) are fact/evaluator concerns owned by TASK-169 / TASK-140; this document does not claim multi-hop alias resolution is fully accepted end-to-end.

### Negative paths for LuaJava helpers (TASK-241)

Must not throw; typically produce **no** class-load facts:

- Missing arguments: `luajava.bindClass()`
- Dynamic / concatenated targets
- Empty createProxy segment lists after filtering blanks
- Unresolved targets after resolution → `UnresolvedLuaJavaTarget` diagnostics for bind/newInstance/createProxy/loadLib when `importedClassName` is null (not for successful imports)

Empty string targets may still record an empty load fact (for example `luajava.bindClass("")`) without throw; resolution then returns null.

---

## 4. Negative paths and diagnostics summary

Aligned with TASK-241 corpus expectations:

| Input family | Expected static behavior |
| --- | --- |
| Malformed `import` / `luajava.*` call shapes | No throw; empty or partial facts |
| Blank configuration imports (`""`, `"import "`) | Dropped from requested classes; no blank provider paths |
| Unsupported path prefixes (`dexPath:java.io.File`, missing jar prefixes) | Diagnostic code `jvm.import.prefixed.unsupported`; class name preserved; resolution may still try fallback classloader |
| Pure malformed package targets (`""`, `".*"`, `"*"`, `"not-a-package"`) | `packageProvidersFor` → empty map |
| Unsupported prefix **with** resolvable wildcard (`dexPath:java.io.*`) | Diagnostic for prefix; package may still mount via fallback classloader as `__jvm__/packages/java/io.lua` |
| Missing class name | `resolveImport` → `null` without throw |
| Ordinary valid imports | No prefixed diagnostics; classes requested/mounted when present on classpath |
| Missing host `android.jar` / incomplete classpath | Skip reflective Android mounts; curated static overlays may still apply; not a startup hard-fail |

Runtime Android-Lua raises `cannot find <package>` for failed explicit imports; static analysis prefers empty resolution + diagnostics over throw.

---

## 5. End-to-end order cheat sheet

For a file that does:

```lua
require "import"
import "java.io.*"
import "android.view.View_OnClickListener"
local File = luajava.bindClass("java.io.File")
```

Static pipeline order:

1. Parse → document facts: require `"import"`; source imports `java.io.*` and `android.view.View_OnClickListener`; bindClass load `java.io.File`.
2. Module graph: resolve `require "import"` → workspace provider if present, else AndroLua overlay `__lua_std__/androlua5.3/import.lua`.
3. JVM engine: add wildcard prefix `java.io` to import prefixes; enumerate `java.io` package → `__jvm__/packages/java/io.lua`; resolve inner-class candidates for `View_OnClickListener` / `View$OnClickListener` when android jar/classpath allows (this host: SDK `platforms/android-35/android.jar` when configured); mount `java.io.File` class provider from bindClass/import discovery.
4. Path-scoped context for this file only: merge configured + source imported symbols (`File`, package members, inner class alias).
5. Queries: require definition → import overlay; simple name `File` → class/package provider; completions prefer module kinds for imported classes/packages (TASK-176 acceptance surface — documentation of intent, not final green).

---

## 6. Honesty / non-claims

- Does **not** claim final green acceptance, suite green, or that inventory counts are frozen (**inventory is not final until TASK-043**).
- Does **not** claim TASK-176 accepted end-to-end or that Android-Lua import query surfaces are fully green; see TASK-176 and the compatibility matrix.
- Does **not** claim TASK-184 library-stub / overlay restoration substitutes for TASK-043 serialized verification (TASK-184 is focused product-surface modeling only).
- Does **not** claim live dex/`loadDex` parity; unsupported prefixes are diagnostic + optional fallback reflection only.
- Does **not** claim complete package inventories without classpath/android jar configuration; on this host Downloads `android.jar` is currently **absent** and must not be invented.
- Does **not** unlock TASK-043 or TASK-037.
- Final behavioral verification remains TASK-043 serial Gradle ownership; this file is documentation-only (original TASK-266 lane; refreshed under TASK-506). No Gradle/tests/compile were run for this refresh.

## Source anchors (implementation)

- `src/commonMain/kotlin/.../workspace/WorkspaceModuleResolver.kt` — `resolveRequire`, active import targets, import target symbols
- `src/commonMain/kotlin/.../workspace/WorkspaceModuleGraphBuilder.kt` — provider priority / active provider selection
- `src/jvmMain/kotlin/.../interop/jvm/JvmWorkspaceEngine.kt` — extra providers, scoped imports, `resolveImportTarget`
- `src/jvmMain/kotlin/.../interop/jvm/JvmClassModuleProvider.kt` — parse, resolveClassLoads, packageProvidersFor, diagnostics
- `src/jvmTest/kotlin/semantic/workspace/AndroidLuaImportNegativePathTddTest.kt` — TASK-241 negative corpus
- `src/jvmTest/kotlin/semantic/workspace/AndroidLuaImportWorkspaceTddTest.kt` — TASK-176 workspace surfaces
- `docs/android-platform-setup.md` — Corretto 17, dual-path macOS `android.jar`, skip-when-absent
