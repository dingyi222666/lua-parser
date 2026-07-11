# Android-Lua Compatibility Matrix

Date: 2026-07-11

This matrix documents the current Android-Lua and LuaJava compatibility target for the Lua parser, semantic workspace, JVM interop, and LSP analysis work in this repository.

Important verification caveat: these entries describe the intended and currently implemented/static-model coverage after the Android-Lua documentation, resource-model, and workspace import-resolution tasks. They do not claim final green verification. Serialized Gradle/test verification is deferred to TASK-043, and final acceptance is deferred to TASK-037.

## Open verification chain (TASK-169 / TASK-140 / TASK-176)

Do not treat the following as fully supported until the chain clears review-owned serial verification. Status below is task-metadata state as of this matrix refresh (2026-07-11), not a green test result.

| Task | Title | Task status (metadata) | What it gates for this matrix |
| --- | --- | --- | --- |
| TASK-169 | Collect transitive LuaJava helper alias facts | `done` (accepted by REVIEW21C; fact collection / provider-mount lane) | Source-discovered `bindClass` / `newInstance` loads and transitive local helper-alias document facts. Unlocks TASK-140. Still not a substitute for TASK-043 suite green. |
| TASK-140 | Complete transitive LuaJava helper alias resolution | `review` (worker-submitted; **not accepted**) | Evaluator/reference resolution for chained local aliases such as `local bindClass = luajava.bindClass; local bind = bindClass; local again = bind`. Until accepted + serial `LuaJavaBindClassTddTest` green, chained-alias member/provider claims remain pending. |
| TASK-176 | Preserve Android-Lua import surfaces under scoped activation | `blocked` on TASK-140 (and prior TASK-155) | Path-scoped `require "import"` / import-module surfaces, imported class/package completion kinds, dynamic import aliases, and current-file import definitions after sibling-file leakage filters. Last recorded focused `AndroidLuaImportWorkspaceTddTest` evidence still had many failures. |

Claim policy for the chain:

- Direct `luajava.bindClass("…")` / `luajava.newInstance("…")` with string literals: modeled; still pending TASK-043.
- Transitive local helper-alias chains (`again("java.util.Locale")`, chained `newInstance` member hover): **pending TASK-140** acceptance and serial verification. Do not claim Supported.
- Path-scoped Android-Lua import activation that keeps current-file import/module/query surfaces while blocking sibling leakage: **pending TASK-176**. Do not claim Supported end-to-end.
- TASK-169 acceptance only covers fact-collection / provider-mount work; it does not alone make chained resolution or import-workspace suites green.

## Status labels

| Status | Meaning |
| --- | --- |
| Supported | The behavior has an implementation or declaration model in the current task graph and should be available when required metadata, stubs, or classpath entries are configured. Final green verification is still pending TASK-043/TASK-037. |
| Partially supported | The common static-analysis shape is modeled, but runtime-complete behavior, broad module loading, exact Java reflection semantics, or corpus verification is incomplete. |
| Pending chain | Implementation work exists or is in flight, but a known open task chain (TASK-169/140/176 or follow-on) still blocks an honest support claim. |
| Deferred | The behavior is known Android-Lua/LuaJava behavior, but this repository should not currently claim support beyond parse/fact preservation or fixture use. |

## Compatibility matrix

| Area | Status | Current analysis behavior | Example | Known gaps |
| --- | --- | --- | --- | --- |
| `require "import"` activation | Partially supported / **pending TASK-176** | Treated as the Android-Lua import setup signal for the file/workspace when import mode is active. Import facts can flow into workspace context, definitions, references, completions, and diagnostics. The `import` module surface is modeled as an Android-Lua compatibility provider. Path-scoped activation that preserves current-file surfaces without sibling leakage is still incomplete. | `require "import"` | **Pending TASK-176** (blocked on TASK-140): current-file `require "import"` definitions, import symbols, dynamic aliases, package-provider returns, and completion module kinds after scoped activation. Final behavior must also reconcile with TASK-043. Dynamic `require(name)` calls are not assumed to activate Android-Lua import mode. |
| Explicit class import | Partially supported / **pending TASK-176** | `import "pkg.Class"` introduces the simple class name when the class can be resolved by configured JDK, Android, external jar, or catalog providers. Source-import definition/query surfaces under path scoping remain part of the open import-workspace suite. | `require "import"`<br>`import "java.io.File"`<br>`local f = File("/sdcard/a.txt")` | Runtime dex class loaders can find classes that static analysis cannot see unless the jar/classes are provided through metadata. **Pending TASK-176** for path-scoped source-import definition and completion-kind preservation. |
| Table/array imports | Supported | Sequential string entries in `import { ... }` are modeled as import targets. | `import { "java.io.File", "java.util.ArrayList" }` | Android-Lua runtime uses `ipairs`; named fields, sparse numeric fields, non-string entries, and dynamic expressions are intentionally not treated as concrete imports. |
| Wildcard package imports | Supported with configured class providers | `import "pkg.*"` records a lazy package prefix. Later simple-name global lookup can resolve classes from that prefix when a class provider or index knows the class. | `import "java.io.*"`<br>`local f = File("a.txt")` | Wildcards do not eagerly enumerate every class. Completion quality depends on Android/JDK/external class indexes. Prefix order can affect ambiguous simple names. Package-provider return typing under scoped import activation is **pending TASK-176**. |
| Direct default class globals | Partially supported | After Android-Lua import mode is active, unresolved simple globals may be resolved through Android-Lua default prefixes such as empty prefix, `java.lang`, `java.util`, and `com.androlua`, plus imported wildcard prefixes. | `require "import"`<br>`local text = String("ok")`<br>`local list = ArrayList()` | This is static class exposure only. Existing locals/globals shadow class lookup. Availability depends on configured providers and final verification. |
| Android framework classes | Supported with `jvm.androidJar` | Android packages are available to JVM reflection and workspace import resolution when metadata points at an Android platform jar. | Metadata: `jvm.androidJar = /Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar`<br>`import "android.widget.TextView"`<br>`local v = TextView(activity)` | No automatic Android SDK discovery is claimed. Missing or wrong `android.jar` leaves framework classes unresolved. Host installs may still lack the jar until platform setup is complete. |
| Android wildcard packages | Supported with `jvm.androidJar` | Android wildcard prefixes can resolve simple Android view/context/widget class names through the configured Android jar. | `import "android.widget.*"`<br>`local v = TextView(activity)` | Member details still depend on JVM reflection/model support. Wildcard completion is only as complete as the scanned jar/index. |
| Inner-class naming | Partially supported | Explicit Android-Lua-style imports can normalize underscore inner-class spelling to Java `$` spelling, and exact `$` names are valid provider targets. | `import "android.view.View_OnClickListener"` maps to `android.view.View$OnClickListener` | Android-Lua's broad underscore rewrite can conflict with real class names containing underscores. Arbitrary global names are not rewritten unless modeled as import/class candidates. Inner-class import definitions under path-scoped activation are **pending TASK-176**. |
| LuaJava `bindClass` / `newInstance` (direct) | Partially supported | Static facts and model resources cover common helper calls such as `luajava.bindClass`, `newInstance`, `createProxy`, `loadLib`, `newArray`, and `createArray`. Bound class results are intended to behave as Java class values when the call is a recognized helper with a string-literal target. | `local TextView = luajava.bindClass("android.widget.TextView")`<br>`local tv = TextView(activity)` | Exact Java constructor overload resolution, primitive conversion, table-to-array conversion, and proxy validation remain permissive and require TASK-043/TASK-037 verification. |
| LuaJava transitive helper alias chains | **Pending chain (TASK-169 done → TASK-140 review → TASK-176 blocked)** | TASK-169 accepted fact collection for transitive local helper aliases and source-discovered class loads into workspace provider mounts. TASK-140 still owns evaluator/reference resolution for multi-hop local aliases; it is in review and **not** serially accepted. | `local bindClass = luajava.bindClass`<br>`local bind = bindClass`<br>`local again = bind`<br>`local Locale = again("java.util.Locale")`<br>`local root = Locale.ROOT` | **Do not claim Supported.** Focused failures historically included `chained_bind_class_alias_resolves_bound_class` and `chained_new_instance_alias_resolves_instance_member` in `semantic.interop.LuaJavaBindClassTddTest`. Colon-call and shadowing guards from earlier tasks must remain. Serial green remains TASK-043-owned after TASK-140 acceptance. |
| LuaJava object members | Partially supported | Java objects/classes are modeled for useful method, field, property, constructor, and nested-class analysis where provider metadata is available. | `File("a.txt").exists()`<br>`Build.VERSION.SDK_INT` | Runtime reflection lookup order, overloaded methods, JavaBean setter/getter behavior, map/list/array special cases, and listener assignment are not fully equivalent to LuaJava runtime behavior. Member resolution through chained helper aliases is **pending TASK-140**. |
| Listener/proxy shorthand | Partially supported | The analysis target recognizes common Android-Lua callback shapes as valid Lua functions/tables passed to Java listener positions. | `{ Button, text = "Save", onClick = function(v) print(v) end }` | Exact `setOnClickListener` discovery and one-method interface proxy construction are runtime reflection behaviors and are only approximated statically. |
| `loadlayout`, `.aly`, layout tables | Partially supported | Android-Lua layout-table shape and root helper declarations are modeled. `.aly` files are treated as Lua table layout fixture material rather than a separate language. | `local ids = {}`<br>`local view = loadlayout({ LinearLayout, { TextView, id = "title" } }, ids)` | Exact Android resource lookup, layout param reflection, id binding side effects, style/unit conversion, and listener wiring are not runtime-complete. |
| Root helper modules | Partially supported | Android-Lua root helper declarations exist for `import`, `loadlayout`, `loadbitmap`, and `loadmenu`. | `local loadlayout = require "loadlayout"`<br>`local bitmap = loadbitmap("icon.png")` | Broad overlay-loader integration for all helper resources remains subject to later integration and TASK-043 verification. |
| Managed runtime helper modules | Partially supported | Handwritten declaration resources exist for common managed modules such as `json`, `xml`, `base64`, `http`, `socket`, `socket.url`, `permission`, `console`, `bin`, `bmob`, and related helpers. | `local json = require "json"`<br>`local data = json.decode(text)`<br>`local url = require "socket.url"` | Declarations model useful API shapes, not copied implementations. Native-backed modules and exact networking/encoding behavior are not executed or fully typed. |
| Selected app helper modules | Partially supported | Compatibility declarations exist for selected reusable assets such as `AndLua`, `ThomeLua`, `Dialog`, `file`, `toast`, `xml2table`, `loadlayout2`, and `loadlayout3`. | `local dialog = require "Dialog"` | App/editor screens, plugin UI scripts, and non-ASCII/project-specific helper globals remain fixture material unless later corpus evidence promotes them. |
| Bundled Android-Lua Java classes | Partially supported | Compact class catalogs and classpath metadata can expose high-value packages such as `com.androlua`, `com.luajava`, bundled widgets, and `com.nirenr`. | `import "com.androlua.LuaActivity"`<br>`import "android.widget.PageView"` | Public member signatures should come from JVM/Android reflection or generated metadata. The catalogs are not a replacement for full Java source/runtime modeling. |
| Parser handling for Android-Lua syntax | Supported for Lua-shaped constructs | Android-Lua `import "..."`, `require "..."`, table layouts, function callbacks, member access, and `.aly` layout literals are Lua-shaped constructs and do not require a separate parser language. | `import "android.widget.*"`<br>`view.onClick = function(v) end` | Real-corpus parser acceptance still belongs to TASK-043/TASK-037. Android XML, Java source, resource files, and native code are outside the Lua parser. Public parser default remains Android-Lua 5.3 (`LuaVersion.ANDROLUA_5_3`). |
| Parser recovery for real snippets | Partially supported | Android-Lua fixture work identifies large scripts, nested callbacks, long strings/comments, layout tables, and malformed snippets as required parser/recovery coverage. | A partially broken layout table should still allow later declarations to be recovered. | Full Android-Lua corpus parse/recovery verification is deferred. |
| Diagnostics | Partially supported | Known imports and resolved class globals are intended to avoid false unknown-global diagnostics; unresolved dynamic imports/classes may still produce diagnostics. | `import "java.io.File"` should make `File` a known class symbol. | Diagnostic exactness depends on final workspace, classpath, and model verification. Missing `android.jar` should produce unresolved Android class behavior rather than pretending support exists. Scoped-import false positives/negatives remain **pending TASK-176**. |
| Completion/navigation/references | Partially supported / **pending TASK-176** (and TASK-140 for chained LuaJava) | Workspace import facts are intended to feed completion, hover/definition, references, and highlights for imported classes and wildcard-backed simple names. | Completion after `import "android.widget.*"; Te` can offer `TextView` when the Android jar/index is configured. | LSP end-to-end behavior is not final until serialized verification covers workspace and LSP tasks. Imported class/package completion kinds and current-file import definitions are **pending TASK-176**. Chained LuaJava helper navigation is **pending TASK-140**. |
| Dex-prefixed imports | Deferred beyond fact preservation | Android-Lua syntax such as `import "dexname:pkg.Class"` is recognized as Android-Lua behavior. Static class resolution requires the corresponding dex/jar/classes to be supplied as normal classpath metadata. | `import "plugin.dex:com.example.PluginActivity"` | Runtime `luacontext.loadDex(...)`, dynamic class loaders, and dex-native library discovery are not statically executed. |
| Native library loading | Deferred | LuaJava and `import.lua` support `loadLib` and native module searchers at runtime, but static analysis should only model the Lua-facing helper signatures. | `luajava.loadLib("com.example.Loader", "open")` | `.so` lookup, `package.loadlib`, JNI entry points, and Android private library paths are runtime behaviors outside static verification. |
| Exact Android runtime behavior | Deferred | Activity/service lifecycle globals, Android resource resolution, UI inflation, task/thread/timer execution, and accessibility/service behavior are documented as runtime context. | `activity.newTask(...)`<br>`service.sendMsg("done")` | Static analysis should expose useful APIs, not emulate the Android runtime. |

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
- Path-scoped workspace activation must keep those surfaces for the **current file** while preventing sibling-file source-import leakage; that preservation work is **pending TASK-176** and must not be overstated as complete.

## LuaJava helper alias rules (current honesty bound)

- Direct `luajava.bindClass` / `newInstance` / `createProxy` / `loadLib` / array helpers with string-literal targets are the supported analysis shape.
- Local aliases of those helpers are collected as document facts when TASK-169-accepted collector/provider-mount behavior is present.
- Multi-hop transitive alias resolution for hover, definition, and instance members is **pending TASK-140** until serial acceptance.
- Colon-form helper calls and shadowed locals remain intentionally non-resolving for the helper surfaces covered by earlier guard tasks.
- Dynamic class-name expressions are not resolved as concrete JVM classes.

## Configuration requirements

Useful Android-Lua analysis depends on workspace metadata:

| Metadata | Purpose |
| --- | --- |
| `jvm.androidJar` | Adds Android framework classes such as `android.content.Context`, `android.view.View`, and `android.widget.TextView`. |
| `jvm.classpath` | Adds external application, plugin, or generated classes. |
| `jvm.classes` | Mounts explicit reflected classes when a small class set is enough. |
| `jvm.importPrefixes` | Adds package prefixes for simple-name import resolution. |
| `androlua.imports` | Supplies additional Android-Lua import targets through workspace/LSP configuration. |

Without the right metadata, the parser can still read Lua source, but semantic class resolution and Android completions should remain limited.

## Known limitations

- This repository should not claim final Android-Lua compatibility until TASK-043 serialized verification and TASK-037 final acceptance audit complete.
- **TASK-169 / TASK-140 / TASK-176 remain an explicit honesty gate for chained LuaJava helper aliases and path-scoped Android-Lua import surfaces.** TASK-169 is accepted for facts; TASK-140 is only in review; TASK-176 is still blocked. None of those replace TASK-043 green evidence.
- Android-Lua can load dex files, jars, native libraries, assets, and app-specific modules at runtime. Static analysis only sees configured project files, resource models, classpath entries, and indexes.
- Wildcard imports are intentionally lazy. They should not invent concrete globals without a class provider or class index.
- LuaJava overload resolution and Lua-to-Java conversion are runtime reflection behaviors. Static checking should be useful but permissive.
- Android layout loading is reflection-heavy and resource-dependent. The model covers layout-table shape and callback/id patterns, not exact Android inflation.
- Helper-module declarations are handwritten compatibility models, not vendored Android-Lua source.
- App/editor assets, plugin screens, Java API helper UI, images, signing material, odex/vdex files, and JNI/C implementations remain fixture/provenance material unless a later task expands support.
- If TASK-043 finds mismatches, this matrix must be reconciled with the final test results before TASK-037 can declare acceptance.

## Related docs

- `docs/android-lua-import-luajava.md` — runtime `import.lua` / LuaJava study notes
- `docs/android-lua-library-models.md` — helper and class-model inventory
- `docs/android-lua-verification.md` — corpus verification plan (TASK-043-owned execution)
- `docs/java-interop-model.md` — JVM interop type and helper mapping guide
- `docs/jvm-reflection-classloader-design.md` — classloader / provider design
- `docs/language-server-usage.md` — LSP surface (pending TASK-043 confirmation)
