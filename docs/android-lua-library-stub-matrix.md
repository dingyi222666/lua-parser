# Android-Lua Library Stub Matrix

Study date: 2026-06-06 (original inventory)
Docs refresh: TASK-507 (WAVE36F), 2026-07-12 — post-TASK-184 / pre-TASK-043 honesty, macOS host paths, Corretto 17, android-35 dual-path jar policy. Docs-only; no Gradle/tests/compile.

This matrix locks the **intended product surface** for Android-Lua 5.3 library stubs and reflective `android.jar` usage in this repository. It is a focused companion to:

- `docs/android-lua-library-models.md` — inventory and stub package layout (TASK-004/033/044)
- `docs/android-lua-compatibility-matrix.md` — broader compatibility claims and open chains
- `docs/luajava-helper-surface-matrix.md` — LuaJava helper support status
- `docs/android-platform-setup.md` — host SDK / `jvm.androidJar` configuration
- `docs/android-lua-architecture.md` — external Android-Lua runtime study notes

**Honesty bound (post-TASK-184, pre-TASK-043):** rows describe handwritten declaration overlays, builtin provider catalog entries, and reflective classpath policy already present in product resources/code. They do **not** invent APIs, claim runtime-complete behavior, or claim final green verification. TASK-184 library-stub / overlay surfaces are **accepted** in task metadata (REVIEW38-WAVE-WAVE36C). That acceptance does **not** unlock **TASK-043** (serialized Gradle/test verification, still `blocked`) and does **not** unlock **TASK-037** (final acceptance, still `blocked`). Inventory counts and suite status are **not final until TASK-043**. No Gradle, compile, or test command was run for this documentation task.

## Status labels

| Status | Meaning |
| --- | --- |
| Cataloged (declaration / modeled) | Resource + builtin overlay catalog expose a require/global surface with documented fields/methods or callable `__call`. Exact runtime behavior is not simulated. Final suite green still pending TASK-043. |
| Global + require | Symbol is both a global (via `_G` / import activation) and a requireable root module. |
| Managed precedence | Asset helper resource exists but managed `modules/` catalog wins for that require name. |
| Fixture only | Present in external Android-Lua assets or study notes; not claimed as a product stub surface. |
| Reflective (host jar) | Class/member surface comes from JVM reflection of a configured `android.jar` / classpath, not from Lua stubs. |
| Deferred | Known Android-Lua runtime behavior; static analysis only preserves parse/facts or intentionally returns unknown. |

## Resource roots (source of truth)

| Layer | Path under `src/commonMain/resources/io/github/dingyi222666/luaparser/semantic/workspace/` | Loader role |
| --- | --- | --- |
| Android-Lua declaration tree | `androidlua/androlua5.3/` | Root helpers, managed modules, asset helpers, compact class indexes |
| Active LuaJava overlay | `std/androlua53-luajava/luajava.lua` (+ sibling `_G.lua`) | Active `luajava` / AndroLua globals provider for `LuaVersion.ANDROLUA_5_3` |
| Static Android framework | `android-framework/` (`manifest.index`, packages, models) | Curated non-reflective class/package providers under `__jvm__/…` |
| Builtin wiring | `BuiltinOverlayLoader` (`…/std/BuiltinOverlayLoader.kt`) | Catalogs require names, synthetic export surfaces, managed-over-asset precedence |

Virtual require paths for Android-Lua modules stay synthetic under `__lua_std__/androlua5.3/…`. Static framework providers use `__jvm__/classes/…`, `__jvm__/packages/…`, and `__jvm__/class-aliases/…`.

## Host toolchain and `android.jar` dual-path policy (never hardcode `G:/`)

Reflective Android framework resolution on **this** macOS host must prefer real files, not Windows-only paths.

### JDK (project verification)

```text
/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home
```

Amazon Corretto 17.0.19 is the expected verification JDK. The shell default `java` on this host may resolve to a newer OpenJDK (for example OpenJDK 26); prefer Corretto 17 for Gradle/JVM-test alignment. Historical Windows notes that used Temurin 17 are not host defaults here.

### `android.jar` candidates

| Priority | Path / source | Role | Host status (2026-07-12 TASK-507 re-check) |
| --- | --- | --- | --- |
| 1 (preferred for reproducible analysis) | Explicit metadata `jvm.androidJar` | Caller-supplied single platform jar | Set to the SDK path below when configuring analysis/tests |
| 2 | `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar` | Android SDK Platform 35 jar (`ANDROID_HOME` / `ANDROID_SDK_ROOT` typically `/Users/dingyi/Library/Android/sdk`) | **Present** (~27,092,450 bytes) |
| 3 (optional copy) | `/Users/dingyi/Downloads/android.jar` | Operator drop-in copy of a platform jar | **Absent** on this host; allowed when present as an **explicit** metadata override only |
| Product default discovery | `JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH` / `resolveDefaultAndroidJarPath()` | Resolves via `ANDROID_HOME` → `ANDROID_SDK_ROOT` → well-known SDK roots; highest `platforms/android-*/android.jar` | Prefer explicit `jvm.androidJar`; discovery is convenience only |

Preferred SDK path block:

```text
/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar
```

Optional Downloads path block (use only when the file exists; do not invent classpath entries for a missing file):

```text
/Users/dingyi/Downloads/android.jar
```

Rules:

1. **Never hardcode `G:/Android/Sdk/...`** (or any other non-host Windows path) in new product or docs paths. Historical Windows paths may appear only as negative test fixtures.
2. Missing jar ⇒ skip reflective Android mounts; do **not** invent class members. Static `android-framework` resources may still supply curated symbols when the Android-Lua overlay is active.
3. JDK `java.lang.*` / `java.util.*` come from the running JVM reflection path; `android.jar` is required for `android.*` framework packages beyond the curated static models.
4. Tests that need reflection should resolve candidates in a multi-OS order such as: explicit path → `DEFAULT_ANDROID_JAR_PATH` → `ANDROID_HOME`/`ANDROID_SDK_ROOT` `platforms/android-35|34/android.jar` → optional Downloads copy when present — and assert skip when none exist.
5. **Inventory / suite green is not final until TASK-043.** Host path presence alone does not mean final acceptance.

## `_G` / context globals matrix

Globals cataloged for `LuaVersion.ANDROLUA_5_3` (in addition to normal Lua 5.3 globals). Declaration resource: `androidlua/androlua5.3/_G.lua` (and active `std/androlua53-luajava/_G.lua` for the LuaJava-facing overlay path).

| Symbol | Kind | Modeled surface (declared) | Status | Notes / non-claims |
| --- | --- | --- | --- | --- |
| `activity` | Global | `LuaActivity` extends `AndroidLuaContext` | Cataloged | Context methods are declaration shapes (`getLuaDir`, `newTask`, `loadDex`, …), not a full Activity runtime. TASK-184 accepted focused type surfaces; still pending TASK-043 suite confirmation. |
| `service` | Global | `LuaService` extends `AndroidLuaContext` | Cataloged | Service-context twin of `activity`. |
| `this` | Global | `LuaActivity \| LuaService \| AndroidLuaContext` | Cataloged | Mirrors runtime host object. |
| `context` | Global | `AndroidLuaContext` | Cataloged | Generic context alias. |
| `luajava` | Global + module | Helper table (see LuaJava matrix) | Cataloged | Active resource: `std/androlua53-luajava/luajava.lua`. |
| `import` | Global + require | `import(package, env?)` | Cataloged | Side effects are document facts, not eager global population. Path-scoped workspace work landed under the TASK-176 chain (done in metadata); still pending TASK-043 confirmation. |
| `env_import` | Global / import return | Callable importer | Cataloged | Matches `import.lua` return shape. |
| `compile` | Global | `(name)` | Cataloged | Declaration only. |
| `enum` / `each` | Global | Java enumeration/iterable adapters | Cataloged | Declaration only. |
| `dump` / `printstack` / `getids` | Global | Debug / layout-id helpers | Cataloged | `getids` → `LuaLayoutIds`. |
| `thread` / `task` / `timer` | Global | Async helpers returning `JavaObject` | Cataloged | Not a concurrency simulator. |
| `loadlayout` | **Global + require** | `(layout, root?, group?) → AndroidView` | Cataloged | See loadlayout row. |
| `loadbitmap` | **Global + require** | `(path) → JavaObject` | Cataloged | See loadbitmap row. |
| `loadmenu` | **Global + require** | `(menu, spec, root?, actionCount?)` | Cataloged | See loadmenu row. |

Opaque type aliases declared for analysis: `JavaObject`, `JavaClass<T>`, `JavaArray<T>`, `JavaProxy`, `AndroidView`, `AndroidMenu`, `AndroidMenuItem`, `LuaLayoutSpec`, `LuaLayoutIds`.

Semantic harness goldens after TASK-184 treat method/function type text as `fun(...)` display forms (cluster A), not the bare word `function`.

## Root helpers: `loadlayout` / `loadbitmap` / `loadmenu`

| Helper | Resource | Require name | Catalog surface | Runtime role (study) | Status | Unknown / degrade |
| --- | --- | --- | --- | --- | --- | --- |
| `loadlayout` | `androidlua/androlua5.3/loadlayout.lua` | `loadlayout` | Callable module (`__call`); args: layout table/string, optional root ids table, optional parent group class; returns `AndroidView` | Table / `.aly` layout inflation, id binding, listeners | Cataloged (TASK-184 accepted focused surfaces) | Does **not** claim Android resource lookup, unit conversion, or exact layout-param reflection. Dynamic layouts stay view-like / unknown for deep members. Dual-path / CURRENTLY_ACCEPTS fixtures may still exist for partial product paths until TASK-043. |
| `loadbitmap` | `…/loadbitmap.lua` | `loadbitmap` | Callable; `(path: string) → JavaObject` | Image load (HTTP/local) via runtime `LuaBitmap` | Cataloged | Path contents not validated; result is opaque bitmap/drawable-like object. |
| `loadmenu` | `…/loadmenu.lua` | `loadmenu` | Callable; menu object + `LuaMenuSpec[]` (+ optional root/actionCount) | Table-to-menu wiring | Cataloged | Spec keys (`id`, `title`, `icon`, …) are declaration shapes only. |

Also requireable via `require "import"` activation surfaces: import module export fields include `loadlayout`, `loadbitmap`, `loadmenu`, and `luajava` (catalog field set on `import`).

`.aly` files are **fixtures** (Lua table layout literals), not separate library modules.

## `import` module

| Item | Detail |
| --- | --- |
| Resource | `androidlua/androlua5.3/import.lua` |
| Require | `require "import"` |
| Catalog methods | `import`, `compile`, `enum`, `each`, `dump`, `printstack`, `getids`, `thread`, `task`, `timer` (+ LuaJava helper names merged onto import surface) |
| Catalog fields | `__call`, `luajava`, callable `loadlayout`/`loadbitmap`/`loadmenu`, plus LuaJava fields |
| Status | Cataloged declaration; TASK-176 path-scoped workspace work is **done** in task metadata; still **awaiting TASK-043** suite confirmation (do not claim final green). |
| Non-claims | No static execution of dex loaders, native searchers, or full metatable package chaining |

## Managed runtime modules (`modules/`)

All paths relative to `androidlua/androlua5.3/`. Require names match the catalog keys below. Status is **Cataloged** unless noted.

| Require name | Resource | Catalog fields | Catalog methods / call shape | Notes |
| --- | --- | --- | --- | --- |
| `autotheme` | `modules/autotheme.lua` | `__call` | callable → number-ish theme id | SDK-sensitive theme chooser shape |
| `base64` | `modules/base64.lua` | — | `encode`, `decode` | Data helper |
| `bin` | `modules/bin.lua` | `__call` | callable | **Managed precedence** over `helpers/bin.lua` |
| `bmob` | `modules/bmob.lua` | — | `sign`, `login` | **Managed precedence** over `helpers/bmob.lua` |
| `check` | `modules/check.lua` | — | `check`, `uncheck` | |
| `console` | `modules/console.lua` | — | `build`, `build_aly` | Project build helpers |
| `ftp` | `modules/ftp.lua` | — | `put`, `get`, `command` | LuaSocket-style |
| `hex` | `modules/hex.lua` | — | `encode`, `decode`, `dump`, `smart_dump`, `pack`, `smart_pack` | |
| `http` | `modules/http.lua` | `cookie`, `header`, `ua` | `request`, `get`, `post`, `download`, `upload`, `open` | Declaration networking shape |
| `json` | `modules/json.lua` | — | `encode`, `decode`, `null`, `encodeString`, `isArray`, `isEncodable` | |
| `logcat` | `modules/logcat.lua` | — | `readlog`, `clearlog`, `show` | |
| `ltn12` | `modules/ltn12.lua` | `filter`, `source`, `sink`, `pump` | — | Transfer helpers |
| `mbox` | `modules/mbox.lua` | — | `parse`, `parse_message` | |
| `mime` | `modules/mime.lua` | — | `normalize`, `stuff`, `wrap`, `encode`, `decode` | Native-backed at runtime |
| `options` | `modules/options.lua` | — | (module table only) | Thin catalog |
| `permission` | `modules/permission.lua` | `permission`, `permission_info` | — | Name/description tables |
| `smtp` | `modules/smtp.lua` | — | `message`, `send` | |
| `socket` | `modules/socket.lua` | `BLOCKSIZE`, `sourcet`, `sinkt` | `connect`, `connect4`, `connect6`, `bind`, `choose`, `newtry`, `try`, `protect`, `skip`, `sink`, `source` | Native-backed at runtime |
| `socket.headers` | `modules/socket/headers.lua` | `canonic` | — | Dotted require |
| `socket.tp` | `modules/socket/tp.lua` | `TIMEOUT` | `connect` | Dotted require |
| `socket.url` | `modules/socket/url.lua` | `_VERSION` | `parse`, `build`, `escape`, `unescape`, `absolute`, `parse_path`, `build_path` | Dotted require |
| `su` | `modules/su.lua` | `__call` | callable → string | Shell helper shape |
| `test` | `modules/test.lua` | — | (module table only) | Thin catalog / debug |
| `xml` | `modules/xml.lua` | — | `new`, `tag`, `append`, `str`, `save`, `find` | |

Native JNI implementations (`socket`, `mime`, `cjson`, …) are **Deferred** beyond these Lua-facing declarations.

## Asset helpers (`helpers/`)

Cataloged only where the builtin loader exports them. Duplicate names `bin` / `bmob` keep **managed precedence** (`ANDROLUA_MANAGED_MODULE_PRECEDENCE_NAMES`); helper resources remain on disk but are **not** separate require targets.

| Require name | Resource | Catalog surface | Status |
| --- | --- | --- | --- |
| `AndLua` | `helpers/AndLua.lua` | Methods include English-facing helpers (`setTitle`, `setContentView`, `toast`, `fileExists`, …) plus selected non-ASCII runtime names present in the catalog | Cataloged |
| `Dialog` | `helpers/Dialog.lua` | Callable + `MyBottomSheetDialog` | Cataloged (TASK-184 accepted focused Dialog surface) |
| `ThomeLua` | `helpers/ThomeLua.lua` | `byteEquals` | Cataloged |
| `file` | `helpers/file.lua` | `exists`, `isDirectory`, `isFile`, `createFile`, `createDirectory`, `deleteFile`, `getFileList`, `loadbitmap`, `getExtension`, `attrdir` | Cataloged |
| `loadlayout2` | `helpers/loadlayout2.lua` | Callable alternate layout loader | Cataloged (compatibility; does not replace `loadlayout`) |
| `loadlayout3` | `helpers/loadlayout3.lua` | Callable alternate layout loader | Cataloged (compatibility) |
| `toast` | `helpers/toast.lua` | `print`, `show` | Cataloged |
| `xml2table` | `helpers/xml2table.lua` | `xml2table`, `show`, `editlayout` | Cataloged |
| `helpers/bin.lua` | present | — | **Managed precedence** → use `require "bin"` (modules) |
| `helpers/bmob.lua` | present | — | **Managed precedence** → use `require "bmob"` (modules) |

App/editor screens (`main*.lua`, plugin UI, javaapi UI) remain **Fixture only**.

## LuaJava stub surface (pointer)

Full helper analysis status lives in `docs/luajava-helper-surface-matrix.md`. Product catalog fields/methods for the `luajava` module:

| Fields | Methods |
| --- | --- |
| `loaded`, `imported`, `ids`, `luadir` | `bindClass`, `new`, `newInstance`, `loadLib`, `createProxy`, `newArray`, `createArray`, `astable`, `tostring`, `instanceof`, `getContext`, `override` |

String-literal gates, colon-call guards, and transitive alias rules from the TASK-140 chain apply; this matrix does not restate them as Supported end-to-end. TASK-140 is **done** in task metadata; still **awaiting TASK-043** suite confirmation — do not claim final green.

## Reflective `android.jar` vs static framework stubs

| Concern | Static `android-framework` resources | Reflective `android.jar` |
| --- | --- | --- |
| Activation | Builtin overlay for `ANDROLUA_5_3` | `jvm.androidJar` and/or SDK env discovery + classpath |
| Coverage | Curated packages/classes in `manifest.index` + compact models | Authoritative public members for classes present in the jar |
| Members | Documented high-value fields/methods only | Constructors, fields, methods, overloads via reflection |
| Wildcard packages | Limited to indexed classes | Package enumeration from jar scan |
| Missing host jar | Still available | Must skip; no invented members |
| Host paths (macOS) | N/A | Prefer `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar` (**present**, ~27,092,450 bytes); optional `/Users/dingyi/Downloads/android.jar` (**absent** on this host); never `G:/` |
| Status | Cataloged curated model | Reflective (host jar); host presence ≠ final green |

Compact Android-Lua class name catalogs under `androidlua/androlua5.3/classes/` (`android-framework.index`, `androlua-runtime.index`, `bundled-widgets.index`) remain provenance/completion seeds; production static providers come from the `android-framework/` resource set.

## Compact surface map (product lock)

Intended requireable Android-Lua stub names (product catalog):

```text
import
loadlayout
loadbitmap
loadmenu
autotheme base64 bin bmob check console ftp hex http json
logcat ltn12 mbox mime options permission smtp socket
socket.headers socket.tp socket.url su test xml
AndLua Dialog ThomeLua file loadlayout2 loadlayout3 toast xml2table
luajava   # via std/androlua53-luajava overlay
```

Intended globals beyond Lua 5.3 (product catalog):

```text
activity service this context luajava import env_import
compile enum each dump printstack getids thread task timer
loadbitmap loadlayout loadmenu
```

Anything outside this map (plugin apps, `main*.lua`, media, dex internals, JNI C APIs, invented widget helpers) is **not** claimed by this matrix.

## Unknown / degrade policy (library stubs)

1. **Declaration ≠ runtime.** Stubs expose shapes for completion/hover/checking; they do not execute Android-Lua.
2. **No invented APIs.** Do not document methods/fields that are not in the resource declarations or builtin catalog surfaces above.
3. **Managed over asset** for `bin` and `bmob` only; other asset helpers fill gaps without overriding managed names.
4. **No provider / no jar ⇒ no Android members.** Prefer honest unknown over fabricated framework APIs.
5. **Native modules** stay Lua-facing stubs; `.so` loading is deferred.
6. **Layout/menu/bitmap** helpers model argument/return shapes only; id side effects and resource lookup remain partial (see compatibility matrix).
7. **Import activation** side effects are facts + workspace resolution work; path-scoped leakage fixes landed under TASK-176 (done in metadata) but remain **awaiting TASK-043** confirmation.
8. **Post-TASK-184 ≠ final green.** Focused library-stub acceptance does not substitute for TASK-043 serialized verification or TASK-037 final acceptance. **Inventory is not final until TASK-043.**

## Related docs

- `docs/android-lua-library-models.md`
- `docs/android-lua-compatibility-matrix.md`
- `docs/luajava-helper-surface-matrix.md`
- `docs/android-platform-setup.md`
- `docs/android-lua-architecture.md`
- `docs/android-lua-import-luajava.md`
- `docs/java-interop-model.md`
- `docs/jvm-reflection-classloader-design.md`
- Overlay tree: `src/commonMain/resources/io/github/dingyi222666/luaparser/semantic/workspace/androidlua/androlua5.3/`
- Loader: `src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/std/BuiltinOverlayLoader.kt`

## Host re-check (TASK-507)

- Date: 2026-07-12 (WAVE36F docs refresh)
- macOS host paths only; never `G:/`
- Corretto 17: `/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home` (present)
- SDK android-35 jar: `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar` (**present**, ~27,092,450 bytes)
- Downloads jar: `/Users/dingyi/Downloads/android.jar` (**absent**)
- TASK-184: done (accepted); TASK-043: blocked; no final green claim
- No Gradle / tests / compile run by this task
