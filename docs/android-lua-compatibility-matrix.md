# Android-Lua Compatibility Matrix

Date: 2026-07-12  
Task: TASK-499 (docs-only refresh)

This matrix documents the current Android-Lua and LuaJava compatibility target for the Lua parser, semantic workspace, JVM interop, and LSP analysis work in this repository.

**Honesty bound (post-TASK-184, pre-TASK-043):** these entries describe intended and currently implemented/static-model coverage after the Android-Lua documentation, resource-model, workspace import-resolution, LuaJava helper-alias, library-stub, and related task chain. They do **not** claim final green verification. Serialized Gradle/test verification remains review-owned under **TASK-043** (`blocked`). Final acceptance remains **TASK-037** (`blocked`). No Gradle, compile, or test command was run for this documentation task.

## Open verification gate (post-chain honesty)

The historical implementation chain **TASK-169 → TASK-140 → TASK-176 → TASK-184** is **done** in task metadata (accepted by serial review waves). That chain is **no longer** an open product-implementation honesty gate for “pending chain” claims. What remains open is the **serialized verification / acceptance** gate:

| Task | Title | Task status (metadata) | What it gates for this matrix |
| --- | --- | --- | --- |
| TASK-169 | Collect transitive LuaJava helper alias facts | `done` | Fact collection / provider-mount for source-discovered `bindClass` / `newInstance` loads and transitive local helper-alias document facts. |
| TASK-140 | Complete transitive LuaJava helper alias resolution | `done` | Evaluator/reference resolution for chained local aliases such as `local bindClass = luajava.bindClass; local bind = bindClass; local again = bind`. Focused serial `LuaJavaBindClassTddTest` was accepted green under review. |
| TASK-176 | Preserve Android-Lua import surfaces under scoped activation | `done` | Path-scoped `require "import"` / import-module surfaces, imported class/package completion kinds, dynamic import aliases, and current-file import definitions after sibling-file leakage filters. |
| TASK-184 | Restore Android-Lua library stub fixture and type surfaces | `done` | Android-Lua library stub/global/type surfaces (`activity`/`service`/`loadlayout`/`loadbitmap`/`loadmenu`, helper modules, `.aly`/layout fixtures) restored under review acceptance. |
| TASK-043 | Serialized Gradle verification phase | **`blocked`** | Full/serial Gradle and focused suite verification across the wave backlog. **Do not claim final green.** Inventory/path follow-ups (for example TASK-125 `review`, TASK-038 `ready`) still sit in front of a clean unlock narrative. |
| TASK-037 | Final green verification and acceptance audit | **`blocked`** | End-to-end acceptance after TASK-043 evidence is honest and green enough. |

Claim policy after the chain:

- Direct `luajava.bindClass("…")` / `luajava.newInstance("…")` with string literals: **modeled**; still pending TASK-043 suite-level confirmation.
- Transitive local helper-alias chains (`again("java.util.Locale")`, chained `newInstance` member hover): **modeled and review-accepted for the focused LuaJava bindClass suite**; still pending TASK-043 as the serialized verification owner. Do **not** treat “TASK-140 done” as a substitute for full TASK-043 green.
- Path-scoped Android-Lua import activation that keeps current-file import/module/query surfaces while blocking sibling leakage: **modeled and TASK-176 done** in metadata; still pending TASK-043 confirmation.
- Android-Lua library stub / overlay surfaces from TASK-184: **modeled and accepted** for the focused library-stub suite; still pending TASK-043 confirmation.
- None of TASK-169 / TASK-140 / TASK-176 / TASK-184 alone unlocks TASK-043 or TASK-037.

## Status labels

| Status | Meaning |
| --- | --- |
| Supported | The behavior has an implementation or declaration model in the current task graph and should be available when required metadata, stubs, or classpath entries are configured. Final green verification is still pending TASK-043 / TASK-037. |
| Partially supported | The common static-analysis shape is modeled, but runtime-complete behavior, broad module loading, exact Java reflection semantics, or corpus verification is incomplete. |
| Modeled (awaiting TASK-043) | Implementation and focused review acceptance exist for the named surface, but this matrix still refuses a “final green” claim until serialized TASK-043 evidence is recorded. |
| Deferred | The behavior is known Android-Lua/LuaJava behavior, but this repository should not currently claim support beyond parse/fact preservation or fixture use. |

> Note: the older **Pending chain (TASK-169 / TASK-140 / TASK-176)** label is retired for this matrix refresh because those tasks are `done`. Residual risk is expressed as **Modeled (awaiting TASK-043)** or **Partially supported**, never as final green.

## Compatibility matrix

| Area | Status | Current analysis behavior | Example | Known gaps |
| --- | --- | --- | --- | --- |
| `require "import"` activation | Modeled (awaiting TASK-043) | Treated as the Android-Lua import setup signal for the file/workspace when import mode is active. Import facts can flow into workspace context, definitions, references, completions, and diagnostics. The `import` module surface is modeled as an Android-Lua compatibility provider. Path-scoped activation that preserves current-file surfaces without sibling leakage is the TASK-176 deliverable (done in metadata). | `require "import"` | Dynamic `require(name)` calls are not assumed to activate Android-Lua import mode. Final suite confirmation remains TASK-043-owned. |
| Explicit class import | Modeled (awaiting TASK-043) | `import "pkg.Class"` introduces the simple class name when the class can be resolved by configured JDK, Android, external jar, or catalog providers. Source-import definition/query surfaces under path scoping are part of the TASK-176-accepted import-workspace work. | `require "import"`<br>`import "java.io.File"`<br>`local f = File("/sdcard/a.txt")` | Runtime dex class loaders can find classes that static analysis cannot see unless the jar/classes are provided through metadata. Missing `android.jar` / classpath leaves Android/framework classes unresolved. |
| Table/array imports | Supported | Sequential string entries in `import { ... }` are modeled as import targets. | `import { "java.io.File", "java.util.ArrayList" }` | Android-Lua runtime uses `ipairs`; named fields, sparse numeric fields, non-string entries, and dynamic expressions are intentionally not treated as concrete imports. |
| Wildcard package imports | Supported with configured class providers | `import "pkg.*"` records a lazy package prefix. Later simple-name global lookup can resolve classes from that prefix when a class provider or index knows the class. | `import "java.io.*"`<br>`local f = File("a.txt")` | Wildcards do not eagerly enumerate every class. Completion quality depends on Android/JDK/external class indexes. Prefix order can affect ambiguous simple names. |
| Direct default class globals | Partially supported | After Android-Lua import mode is active, unresolved simple globals may be resolved through Android-Lua default prefixes such as empty prefix, `java.lang`, `java.util`, and `com.androlua`, plus imported wildcard prefixes. | `require "import"`<br>`local text = String("ok")`<br>`local list = ArrayList()` | This is static class exposure only. Existing locals/globals shadow class lookup. Availability depends on configured providers and final verification. |
| Android framework classes | Supported with `jvm.androidJar` | Android packages are available to JVM reflection and workspace import resolution when metadata points at an Android platform jar. On this macOS host (2026-07-12 re-check): `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar` is **present** (27,092,450 bytes). Prefer that SDK path. Optional `/Users/dingyi/Downloads/android.jar` is allowed when present; it is **absent** on this host as of this refresh. **Never hard-code Windows `G:/` paths.** | Metadata: `jvm.androidJar = /Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar`<br>`import "android.widget.TextView"`<br>`local v = TextView(activity)` | Product code may discover jars via `ANDROID_HOME` / well-known roots (see `docs/android-platform-setup.md`), but reproducible analysis should still set `jvm.androidJar` explicitly. Missing or wrong `android.jar` leaves framework classes unresolved. |
| Android wildcard packages | Supported with `jvm.androidJar` | Android wildcard prefixes can resolve simple Android view/context/widget class names through the configured Android jar. | `import "android.widget.*"`<br>`local v = TextView(activity)` | Member details still depend on JVM reflection/model support. Wildcard completion is only as complete as the scanned jar/index. |
| Inner-class naming | Partially supported | Explicit Android-Lua-style imports can normalize underscore inner-class spelling to Java `$` spelling, and exact `$` names are valid provider targets. | `import "android.view.View_OnClickListener"` maps to `android.view.View$OnClickListener` | Android-Lua's broad underscore rewrite can conflict with real class names containing underscores. Arbitrary global names are not rewritten unless modeled as import/class candidates. |
| LuaJava `bindClass` / `newInstance` (direct) | Modeled (awaiting TASK-043) | Static facts and model resources cover common helper calls such as `luajava.bindClass`, `newInstance`, `createProxy`, `loadLib`, `newArray`, and `createArray`. Bound class results are intended to behave as Java class values when the call is a recognized helper with a string-literal target. | `local TextView = luajava.bindClass("android.widget.TextView")`<br>`local tv = TextView(activity)` | Exact Java constructor overload resolution, primitive conversion, table-to-array conversion, and proxy validation remain permissive and require TASK-043 / TASK-037 verification. |
| LuaJava transitive helper alias chains | Modeled (awaiting TASK-043) | TASK-169 accepted fact collection for transitive local helper aliases and source-discovered class loads into workspace provider mounts. TASK-140 accepted evaluator/reference resolution for multi-hop local aliases under focused serial `LuaJavaBindClassTddTest` review. | `local bindClass = luajava.bindClass`<br>`local bind = bindClass`<br>`local again = bind`<br>`local Locale = again("java.util.Locale")`<br>`local root = Locale.ROOT` | **Do not claim final green.** Colon-call and shadowing guards from earlier tasks must remain. Serial broad-suite green remains TASK-043-owned. Companion detail: `docs/luajava-helper-surface-matrix.md` may still carry pre-acceptance wording in places; this matrix is the post-184 refresh authority for chain status. |
| LuaJava object members | Partially supported | Java objects/classes are modeled for useful method, field, property, constructor, and nested-class analysis where provider metadata is available. Chained helper-alias member resolution is part of the TASK-140-accepted focused surface. | `File("a.txt").exists()`<br>`Build.VERSION.SDK_INT` | Runtime reflection lookup order, overloaded methods, JavaBean setter/getter behavior, map/list/array special cases, and listener assignment are not fully equivalent to LuaJava runtime behavior. |
| Listener/proxy shorthand | Partially supported | The analysis target recognizes common Android-Lua callback shapes as valid Lua functions/tables passed to Java listener positions. | `{ Button, text = "Save", onClick = function(v) print(v) end }` | Exact `setOnClickListener` discovery and one-method interface proxy construction are runtime reflection behaviors and are only approximated statically. |
| `loadlayout`, `.aly`, layout tables | Modeled (awaiting TASK-043) | Android-Lua layout-table shape and root helper declarations are modeled. `.aly` files are treated as Lua table layout fixture material rather than a separate language. TASK-184 restored focused library-stub / layout fixture type surfaces under review acceptance. | `local ids = {}`<br>`local view = loadlayout({ LinearLayout, { TextView, id = "title" } }, ids)` | Exact Android resource lookup, layout param reflection, id binding side effects, style/unit conversion, and listener wiring are not runtime-complete. Dual-path / CURRENTLY_ACCEPTS fixtures may still exist for partial product paths. |
| Root helper modules | Modeled (awaiting TASK-043) | Android-Lua root helper declarations exist for `import`, `loadlayout`, `loadbitmap`, and `loadmenu` (TASK-184-accepted overlay/stub surfaces). | `local loadlayout = require "loadlayout"`<br>`local bitmap = loadbitmap("icon.png")` | Broad overlay-loader integration for all helper resources remains subject to later integration and TASK-043 verification. |
| Managed runtime helper modules | Partially supported | Handwritten declaration resources exist for common managed modules such as `json`, `xml`, `base64`, `http`, `socket`, `socket.url`, `permission`, `console`, `bin`, `bmob`, and related helpers. Managed-over-asset precedence (including `bmob` / `bin`) is preserved by earlier tasks. | `local json = require "json"`<br>`local data = json.decode(text)`<br>`local url = require "socket.url"` | Declarations model useful API shapes, not copied implementations. Native-backed modules and exact networking/encoding behavior are not executed or fully typed. |
| Selected app helper modules | Partially supported | Compatibility declarations exist for selected reusable assets such as `AndLua`, `ThomeLua`, `Dialog`, `file`, `toast`, `xml2table`, `loadlayout2`, and `loadlayout3`. | `local dialog = require "Dialog"` | App/editor screens, plugin UI scripts, and non-ASCII/project-specific helper globals remain fixture material unless later corpus evidence promotes them. |
| Bundled Android-Lua Java classes | Partially supported | Compact class catalogs and classpath metadata can expose high-value packages such as `com.androlua`, `com.luajava`, bundled widgets, and `com.nirenr`. | `import "com.androlua.LuaActivity"`<br>`import "android.widget.PageView"` | Public member signatures should come from JVM/Android reflection or generated metadata. The catalogs are not a replacement for full Java source/runtime modeling. |
| Parser handling for Android-Lua syntax | Supported for Lua-shaped constructs | Android-Lua `import "..."`, `require "..."`, table layouts, function callbacks, member access, and `.aly` layout literals are Lua-shaped constructs and do not require a separate parser language. | `import "android.widget.*"`<br>`view.onClick = function(v) end` | Real-corpus parser acceptance still belongs to TASK-043 / TASK-037. Android XML, Java source, resource files, and native code are outside the Lua parser. Public parser default remains Android-Lua 5.3 (`LuaVersion.ANDROLUA_5_3`). |
| Parser recovery for real snippets | Partially supported | Android-Lua fixture work identifies large scripts, nested callbacks, long strings/comments, layout tables, and malformed snippets as required parser/recovery coverage. | A partially broken layout table should still allow later declarations to be recovered. | Full Android-Lua corpus parse/recovery verification is deferred to TASK-043. |
| Diagnostics | Partially supported | Known imports and resolved class globals are intended to avoid false unknown-global diagnostics; unresolved dynamic imports/classes may still produce diagnostics. | `import "java.io.File"` should make `File` a known class symbol. | Diagnostic exactness depends on final workspace, classpath, and model verification. Missing `android.jar` should produce unresolved Android class behavior rather than pretending support exists. |
| Completion/navigation/references | Modeled (awaiting TASK-043) | Workspace import facts are intended to feed completion, hover/definition, references, and highlights for imported classes and wildcard-backed simple names. Chained LuaJava helper navigation is part of the TASK-140-accepted focused surface. | Completion after `import "android.widget.*"; Te` can offer `TextView` when the Android jar/index is configured. | LSP end-to-end behavior is not final until serialized verification covers workspace and LSP tasks under TASK-043. |
| Dex-prefixed imports | Deferred beyond fact preservation | Android-Lua syntax such as `import "dexname:pkg.Class"` is recognized as Android-Lua behavior. Static class resolution requires the corresponding dex/jar/classes to be supplied as normal classpath metadata. | `import "plugin.dex:com.example.PluginActivity"` | Runtime `luacontext.loadDex(...)`, dynamic class loaders, and dex-native library discovery are not statically executed. |
| Native library loading | Deferred | LuaJava and `import.lua` support `loadLib` and native module searchers at runtime, but static analysis should only model the Lua-facing helper signatures. | `luajava.loadLib("com.example.Loader", "open")` | `.so` lookup, `package.loadlib`, JNI entry points, and Android private library paths are runtime behaviors outside static verification. |
| Exact Android runtime behavior | Deferred | Activity/service lifecycle globals, Android resource resolution, UI inflation, task/thread/timer execution, and accessibility/service behavior are documented as runtime context. | `activity.newTask(...)`<br>`service.sendMsg("done")` | Static analysis should expose useful APIs, not emulate the Android runtime. |

## Host `android.jar` dual-path policy (macOS only)

Verification, docs, and JVM metadata examples on this host must use **only** these candidates. Never hard-code Windows `G:/` (or any other non-host) paths.

| Priority | Path | Role | Host status (2026-07-12 TASK-499 re-check) |
| --- | --- | --- | --- |
| 1 (preferred for reproducible analysis) | Explicit metadata `jvm.androidJar` | Caller-supplied single platform jar | Set to the SDK path below when configuring analysis/tests |
| 2 | `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar` | Android SDK Platform 35 jar (`ANDROID_HOME=/Users/dingyi/Library/Android/sdk`) | **Present** (27,092,450 bytes) |
| 3 (optional copy) | `/Users/dingyi/Downloads/android.jar` | Operator drop-in copy of a platform jar | **Absent** on this host; allowed when present |

Preferred SDK path block:

```text
/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar
```

Optional Downloads candidate (only when the file exists):

```text
/Users/dingyi/Downloads/android.jar
```

Product discovery may still walk `ANDROID_HOME` / well-known SDK roots (see `docs/android-platform-setup.md` and `JvmWorkspaceConfiguration` defaults). Callers must check `File.isFile` and **skip when absent** — never invent a reflective classpath entry for a missing jar.

## Parser version policy

The public parser default is Android-Lua 5.3: `LuaParser()` uses `LuaVersion.ANDROLUA_5_3`. This is the compatibility default for current Android-Lua/LuaJava-aware parser and language-server work.

Strict Lua 5.3 remains available by constructing `LuaParser(luaVersion = LuaVersion.LUA_5_3)`. In that mode, Android-Lua keyword forms such as `continue`, `when`, `switch`, `case`, `default`, `lambda`, array constructors, and dollar-prefixed locals are not enabled as Android-Lua syntax.

## Import behavior rules

The compatibility target for `import.lua` follows the observed Android-Lua runtime behavior:

- `require "import"` installs Android-Lua import behavior for the environment and exposes helper globals.
- `import "fully.qualified.Class"` binds the final segment as a simple global when the target is resolved.
- `import "package.*"` records a package prefix for later lazy simple-name lookup.
- `import { "a.Class", "b.*" }` processes sequential array entries in order.
- Existing locals or globals shadow direct class-global lookup.
- Wildcard imports are prefixes, not complete symbol declarations.
- Explicit imports using Android-Lua inner-class spelling may map `_` to Java `$`.
- Dynamic arguments such as `import(name)` are preserved as dynamic behavior but are not resolved as concrete classes.
- Path-scoped workspace activation must keep those surfaces for the **current file** while preventing sibling-file source-import leakage; that preservation work is **TASK-176 done** in metadata and still awaits TASK-043 confirmation.

## LuaJava helper alias rules (current honesty bound)

- Direct `luajava.bindClass` / `newInstance` / `createProxy` / `loadLib` / array helpers with string-literal targets are the supported analysis shape.
- Local aliases of those helpers are collected as document facts when TASK-169-accepted collector/provider-mount behavior is present.
- Multi-hop transitive alias resolution for hover, definition, and instance members is **TASK-140 done** in metadata (focused serial acceptance recorded under review). Still **not** a final-green claim.
- Colon-form helper calls and shadowed locals remain intentionally non-resolving for the helper surfaces covered by earlier guard tasks.
- Dynamic class-name expressions are not resolved as concrete JVM classes.
- Detailed per-helper degrade policy remains in `docs/luajava-helper-surface-matrix.md` (companion doc; chain-status authority for this refresh is this matrix).

## Configuration requirements

Useful Android-Lua analysis depends on workspace metadata:

| Metadata | Purpose |
| --- | --- |
| `jvm.androidJar` | Adds Android framework classes such as `android.content.Context`, `android.view.View`, and `android.widget.TextView`. On this host prefer `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar`. |
| `jvm.classpath` | Adds external application, plugin, or generated classes. |
| `jvm.classes` | Mounts explicit reflected classes when a small class set is enough. |
| `jvm.importPrefixes` | Adds package prefixes for simple-name import resolution. |
| `androlua.imports` | Supplies additional Android-Lua import targets through workspace/LSP configuration. |

Without the right metadata, the parser can still read Lua source, but semantic class resolution and Android completions should remain limited.

## Known limitations

- This repository should not claim final Android-Lua compatibility until TASK-043 serialized verification and TASK-037 final acceptance audit complete.
- **TASK-169 / TASK-140 / TASK-176 / TASK-184 are done in task metadata.** That closes the old “pending chain” honesty gate for product implementation, but **none of those replace TASK-043 green evidence.**
- Android-Lua can load dex files, jars, native libraries, assets, and app-specific modules at runtime. Static analysis only sees configured project files, resource models, classpath entries, and indexes.
- Wildcard imports are intentionally lazy. They should not invent concrete globals without a class provider or class index.
- LuaJava overload resolution and Lua-to-Java conversion are runtime reflection behaviors. Static checking should be useful but permissive.
- Android layout loading is reflection-heavy and resource-dependent. The model covers layout-table shape and callback/id patterns, not exact Android inflation.
- Helper-module declarations are handwritten compatibility models, not vendored Android-Lua source.
- App/editor assets, plugin screens, Java API helper UI, images, signing material, odex/vdex files, and JNI/C implementations remain fixture/provenance material unless a later task expands support.
- If TASK-043 finds mismatches, this matrix must be reconciled with the final test results before TASK-037 can declare acceptance.
- Host path policy is macOS-only for this refresh: Downloads + SDK `android-35` only; never `G:/`.

## Related docs

- `docs/android-lua-import-luajava.md` — runtime `import.lua` / LuaJava study notes
- `docs/android-lua-library-models.md` — helper and class-model inventory
- `docs/android-lua-verification.md` — corpus verification plan (TASK-043-owned execution)
- `docs/android-platform-setup.md` — host SDK / `android.jar` setup
- `docs/android-lua-require-path-matrix.md` — require path / overlay resolution matrix
- `docs/luajava-helper-surface-matrix.md` — focused LuaJava helper surface matrix
- `docs/java-interop-model.md` — JVM interop type and helper mapping guide
- `docs/jvm-reflection-classloader-design.md` — classloader / provider design
- `docs/language-server-usage.md` — LSP surface (pending TASK-043 confirmation)
- `docs/serialized-verification.md` — serialized verification inventory / gate notes
