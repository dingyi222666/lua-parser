# Android-Lua require path matrix

Date: 2026-07-11  
Task: TASK-477 (docs-only)

This matrix documents **how static analysis maps `require "..."` module names to provider paths** for Android-Lua 5.3 workspaces in this repository. It is a focused companion to:

- `docs/android-lua-import-resolution.md` — import / `require "import"` / LuaJava load order
- `docs/android-lua-library-stub-matrix.md` — library stub product surface
- `docs/android-lua-library-models.md` — inventory + post-TASK-184 product surfaces
- `docs/android-lua-compatibility-matrix.md` — broader compatibility honesty
- `docs/android-platform-setup.md` — host `android.jar` / metadata setup
- `docs/android-lua-import-luajava.md` — runtime `package.path` / `import.lua` study notes

**Honesty bound:** rows describe the post-TASK-184 product/static model (library stubs accepted by REVIEW38 → TASK-184 `done`) and the dual-path `.aly` require corpus (TASK-381 accepted). They do **not** claim TASK-043 serialized suite green, TASK-037 final acceptance, or that every free-form workspace require edge is complete. Workers must not run Gradle, tests, or compile for this task; verification is review-owned under TASK-043.

---

## Status labels

| Status | Meaning |
| --- | --- |
| Modeled | Static graph / overlay / path-derived provider is wired and should resolve when the consumer require fact matches the module name. Still pending TASK-043 green. |
| Modeled + precedence | Same, with an explicit conflict rule (managed-over-asset, reserved simple names). |
| Dual-path / partial | Ideal path is documented; product may still leave `resolveRequire.provider` null or incomplete export surface. Corpora may use CURRENTLY_ACCEPTS. |
| Runtime-only | Android-Lua runtime `package.path` / asset / dex / native searcher behavior. Static analysis does **not** execute those searchers. |
| Deferred | Known behavior; not claimed as a complete static path. |

---

## Host `android.jar` dual-path (never `G:/`)

Reflective Android framework extras that *appear* after a require/import (for example members on a class resolved via `import` after `require "import"`) use only host-local jars. This matrix does not invent classpath entries.

| Priority | Path / source | Host check (2026-07-11 WAVE36F re-check) |
| --- | --- | --- |
| 1 | Explicit metadata `jvm.androidJar` | Preferred for reproducible analysis |
| 2 | `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar` | **Present** (~27,092,450 bytes; SDK Platform 35) |
| 3 (optional) | `/Users/dingyi/Downloads/android.jar` | **Absent** on this host; allowed when present |

Rules:

1. **Never hard-code `G:/Android/Sdk/...`** (or any Windows-only path) in new docs, product, or tests. Historical Windows paths may appear only as negative fixtures.
2. Prefer SDK android-35 when the file exists; use Downloads only when that file exists.
3. Missing jar ⇒ skip reflective Android mounts; keep overlay + static `android-framework` providers. Do not invent class members.
4. Require **module** resolution itself does not need `android.jar`; jar policy matters for post-require import/class surfaces.

Preferred SDK path:

```text
/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar
```

Optional Downloads path (only when the file exists):

```text
/Users/dingyi/Downloads/android.jar
```

---

## Layers (static require resolution)

Static resolution is **not** a live walk of runtime `package.path` templates. It is layered:

1. **Document facts** — `DocumentFactsCollector` records literal `require "M"` as `requires` (and dynamic requires separately). Path-derived module-name candidates come from the file’s virtual path (including `.aly`).
2. **Provider claims** — `WorkspaceModuleGraphBuilder` collects providers:
   - Workspace files → `VIRTUAL_PATH` (and top-level `module()` → `LEGACY_TOP_LEVEL`)
   - Builtin overlay catalog → `STANDARD_LIBRARY_OVERLAY`
   - Engine extras (JVM class/package mounts, etc.) → `EXTRA_WORKSPACE_PROVIDER`
3. **Active provider per module name** — for each module name, providers are sorted by rank then path; the first wins. Conflicts are retained in `providerConflicts`.
4. **Graph edges** — each static require fact becomes a `ResolvedDependency` if `activeProviders[moduleName]` exists; otherwise `UnresolvedRequire`.
5. **Query `resolveRequire`** — `WorkspaceModuleResolver.resolveRequire(consumer, moduleName)`:
   1. Prefer an already-recorded graph edge **with a non-null export surface**.
   2. Special-case `moduleName == "import"` (overlay / synthetic import surface).
   3. Else return `null` (unresolved or surface missing).

Runtime Android-Lua still uses `package.path` / `package.cpath`, asset loaders, and native searchers (`docs/android-lua-import-luajava.md`). Static analysis approximates that with virtual providers and overlays.

---

## Provider source rank (conflict matrix)

Lower rank wins when multiple providers claim the same module name (`WorkspaceModuleGraphBuilder.providerSourceRank`). Tie-break: lexicographic `path.value`.

| Rank | `ProviderSource` | Typical paths | Example claim |
| --- | --- | --- | --- |
| 0 | `LEGACY_TOP_LEVEL` | User file with top-level `module("name")` | Legacy module env |
| 1 | `VIRTUAL_PATH` | Workspace `.lua` / `.aly` path-derived name | `lib/dep.lua` → `lib.dep` |
| 2 | `EXTRA_WORKSPACE_PROVIDER` | `__jvm__/classes/...`, `__jvm__/packages/...`, other engine extras | Reflective / static class mounts |
| 3 | `STANDARD_LIBRARY_OVERLAY` | `__lua_std__/androlua5.3/...`, Lua 5.3 std, curated framework aliases | `require "json"`, `require "import"` |

Implications:

- A workspace file that provides `"import"` (for example project-local `import.lua`) wins over the Android-Lua overlay.
- Managed overlay modules (rank 3) lose to same-named workspace files (rank 1).
- Reserved Android-Lua simple names (`Dialog`, `file`, `toast`, `json`, …) are kept off simple framework aliases so `require "Dialog"` stays `helpers/Dialog.lua`, not `android.app.Dialog` (see `ANDROLUA_RESERVED_SIMPLE_MODULE_NAMES` in `BuiltinOverlayLoader`).

---

## Path → module name derivation

`DocumentFactsCollector.deriveModuleNameFromPath` turns a workspace `VirtualPath` into a requireable module name (source `VIRTUAL_PATH`):

| Virtual path shape | Derived module name | Notes |
| --- | --- | --- |
| `foo/bar.lua` | `foo.bar` | Strip `.lua`, `/` → `.` |
| `foo/bar/init.lua` | `foo.bar` | Strip `/init.lua` |
| `init.lua` (root only) | *(none)* | Explicitly no candidate |
| `foo/bar.aly` | `foo.bar` | Android-Lua layout modules are requireable **without** a `.lua` suffix in the require string |
| other (no known suffix) | path with `/` → `.` | Rare / synthetic |

LSP indexing stores folder-relative paths so folder-root modules behave as siblings (`require("dep")` → `dep.lua` at the folder root). See `docs/language-server-usage.md`.

---

## `resolveRequire` decision matrix

For consumer path `P` and module name `M`:

| Step | Condition | Result | Status |
| --- | --- | --- | --- |
| 1 | `graph.resolvedDependencies[P]` has edge for `M` **and** export surface exists | That provider + surface | Modeled |
| 1b | Edge exists but export surface is null | Treat as unresolved (`null`) | Dual-path / partial (notably free-form `.aly`) |
| 2 | `M == "import"` and STANDARD_LIBRARY_OVERLAY provider has surface | Overlay `__lua_std__/androlua5.3/import.lua` (preferred among import providers) | Modeled |
| 3 | `M == "import"` and any active provider has surface | That provider | Modeled |
| 4 | `M == "import"` and overlay globals list includes `import` | Synthetic import surface on globals path | Modeled (fallback) |
| 5 | Else | `null` | Unresolved static require |

Negative / non-activating paths:

- Dynamic `require(name)` / non-literal targets → `dynamicRequireSites` only; no static edge; does **not** activate Android-Lua import mode.
- Missing provider for `M` → unresolved static require diagnostic surface (when diagnostics are enabled).
- Graph edge without export surface → query-level `null` even if a path-derived provider was claimed (`.aly` partial wiring).

---

## Runtime vs static path templates

### Runtime (Android-Lua study; not executed by static analysis)

| Mechanism | Role | Status |
| --- | --- | --- |
| `package.path` / `package.cpath` set in `LuaActivity` / `LuaThread` | File-system and luaDir template search | Runtime-only |
| Managed lua dir (`resources/lua` extracted) | `import`, `loadlayout`, socket helpers, … | Runtime-only as searcher; **modeled** as overlay stubs |
| Asset loaders / `LuaAssetLoader` | `require` from APK assets | Runtime-only |
| `import.lua` native `package.searchers` entry | Prefix → `.so` / `package.loadlib` | Runtime-only / deferred |
| Dex class loaders via `import "dex:…"` | Runtime class load, not file require | Deferred beyond fact preservation |

### Static analysis path templates (synthetic)

| Template | Maps to | Status |
| --- | --- | --- |
| Workspace relative `*.lua` | `VIRTUAL_PATH` provider; module name from path derivation | Modeled |
| Workspace relative `*.aly` | Same derivation; ideal require arg omits `.lua` and `.aly` | Dual-path / partial (TASK-381 / TASK-184 corpus) |
| `__lua_std__/androlua5.3/<module>.lua` | Android-Lua overlay providers (`BuiltinOverlayLoader.overlayModulePath`) | Modeled |
| `__lua_std__/5.3/<module>.lua` (and version peers) | Normal Lua stdlib overlay | Modeled |
| `__jvm__/classes/<binary/with/slashes>.lua` | Class module mounts (static framework and/or reflection) | Modeled (class providers; not typical `require` string for scripts) |
| `__jvm__/packages/<pkg/with/slashes>.lua` | Package / wildcard mounts | Modeled for import/package surfaces |
| `__jvm__/class-aliases/...` | Alternate FQCN / simple aliases | Modeled with reserved-name exclusions |

There is **no** static expansion of `?.lua;?/init.lua` path templates against the real filesystem during graph build. Workspace files must already be in the snapshot (indexed folder files + open overlays + extras).

---

## Android-Lua overlay require matrix (post-TASK-184)

Synthetic provider root: `__lua_std__/androlua5.3/…`  
Resource root: `src/commonMain/resources/.../androidlua/androlua5.3/`  
Loader: `BuiltinOverlayLoader` for `LuaVersion.ANDROLUA_5_3` (public parser default).

### Root helpers

| Require name | Resource (relative) | Synthetic path | Status | Notes |
| --- | --- | --- | --- | --- |
| `import` | `import.lua` | `__lua_std__/androlua5.3/import.lua` | Modeled | Activation signal for Android-Lua import mode; path-scoped workspace preservation still **pending TASK-176** for full query honesty |
| `loadlayout` | `loadlayout.lua` | `__lua_std__/androlua5.3/loadlayout.lua` | Modeled | Also global; callable `__call` → `AndroidView` shell |
| `loadbitmap` | `loadbitmap.lua` | `__lua_std__/androlua5.3/loadbitmap.lua` | Modeled | Callable → Bitmap-like shell |
| `loadmenu` | `loadmenu.lua` | `__lua_std__/androlua5.3/loadmenu.lua` | Modeled | Callable → menu-like shell |
| `luajava` | active `std/androlua53-luajava/luajava.lua` | `__lua_std__/androlua5.3/luajava.lua` | Modeled | Active LuaJava overlay is the `std/androlua53-luajava` resource, not a vendored runtime file |

### Managed modules (`modules/`)

All **Modeled** unless noted. Dotted names map to nested resource paths (`socket.url` → `modules/socket/url.lua`).

| Require name | Resource | Precedence / notes |
| --- | --- | --- |
| `autotheme` | `modules/autotheme.lua` | Callable theme helper shape |
| `base64` | `modules/base64.lua` | `encode` / `decode` |
| `bin` | `modules/bin.lua` | **Managed precedence** over `helpers/bin.lua` |
| `bmob` | `modules/bmob.lua` | **Managed precedence** over `helpers/bmob.lua` |
| `check` | `modules/check.lua` | |
| `console` | `modules/console.lua` | `build` / `build_aly` |
| `ftp` | `modules/ftp.lua` | |
| `hex` | `modules/hex.lua` | |
| `http` | `modules/http.lua` | |
| `json` | `modules/json.lua` | |
| `logcat` | `modules/logcat.lua` | |
| `ltn12` | `modules/ltn12.lua` | |
| `mbox` | `modules/mbox.lua` | |
| `mime` | `modules/mime.lua` | |
| `options` | `modules/options.lua` | Lightweight |
| `permission` | `modules/permission.lua` | |
| `smtp` | `modules/smtp.lua` | |
| `socket` | `modules/socket.lua` | |
| `socket.headers` | `modules/socket/headers.lua` | Dotted module |
| `socket.tp` | `modules/socket/tp.lua` | Dotted module |
| `socket.url` | `modules/socket/url.lua` | Dotted module |
| `su` | `modules/su.lua` | Callable-ish shell |
| `test` | `modules/test.lua` | Lightweight |
| `xml` | `modules/xml.lua` | |

### Asset helpers (`helpers/`) — require names

| Require name | Resource | Status | Notes |
| --- | --- | --- | --- |
| `AndLua` | `helpers/AndLua.lua` | Modeled | Reserved simple name vs framework |
| `Dialog` | `helpers/Dialog.lua` | Modeled + precedence | Reserved; not `android.app.Dialog` |
| `ThomeLua` | `helpers/ThomeLua.lua` | Modeled | |
| `file` | `helpers/file.lua` | Modeled + precedence | Reserved simple name |
| `loadlayout2` | `helpers/loadlayout2.lua` | Modeled | Alternate layout loader |
| `loadlayout3` | `helpers/loadlayout3.lua` | Modeled | Alternate layout loader |
| `toast` | `helpers/toast.lua` | Modeled + precedence | Reserved simple name |
| `xml2table` | `helpers/xml2table.lua` | Modeled | |
| `bin` (asset) | `helpers/bin.lua` | **Not exported** when managed exists | Managed wins |
| `bmob` (asset) | `helpers/bmob.lua` | **Not exported** when managed exists | Managed wins |

Loader rule: `withAndroidLuaAssetHelperFallbacks` merges asset helpers only when the module name is not already in the managed map; duplicates must be in `ANDROLUA_MANAGED_MODULE_PRECEDENCE_NAMES` (`bin`, `bmob`).

### Workspace / fixture path families

| Require form | Ideal provider | Post-184 / dual-path honesty | Status |
| --- | --- | --- | --- |
| `require "dep"` with `dep.lua` in same workspace folder | `VIRTUAL_PATH` → folder-relative `dep.lua` | Modeled when file is indexed | Modeled |
| `require "pkg.mod"` with `pkg/mod.lua` | `VIRTUAL_PATH` → `pkg/mod.lua` | Path derivation uses `/` → `.` | Modeled |
| `require "semantic.androidlua.library-fixtures.representative_layout"` with `…/representative_layout.aly` | Provider path ends with `.aly` | Ideal locked by TASK-381 / TASK-184; product may still return null provider → CURRENTLY_ACCEPTS | Dual-path / partial |
| `require "import"` without workspace override | Overlay import | Modeled; TASK-176 still open for full path-scoped import **query** surfaces | Modeled / pending chain for activation queries |
| `require "json"` / `require "socket.url"` | Overlay managed | Modeled declaration surface | Modeled |
| `require "Dialog"` | Overlay helper | Must not lose to `android.app.Dialog` simple alias | Modeled + precedence |
| `require(name)` dynamic | No static edge | Facts only | Deferred / dynamic site |

---

## `.aly` require dual-path (TASK-381 / TASK-184)

| Aspect | Ideal | CURRENTLY_ACCEPTS (product partial) |
| --- | --- | --- |
| Require string | Module name **without** `.lua` or `.aly` suffix | Same string shape |
| Path derivation | `….aly` → dotted module name | Already implemented in `deriveModuleNameFromPath` |
| `resolveRequire` / `lookupModule` provider | Non-null path ending in `.aly` (workspace fixture), not `__jvm__/` and not fabricated `.lua` | Provider may be **null** when export surface / graph edge is incomplete |
| Layout typing | `LuaLayoutSpec` / layout-table shape; `loadlayout` consumer paths | Soft typing may degrade without inventing members |
| Corpus | `AlyLayoutRequireResolutionTddTest`, `AndroidLuaLibraryStubsTddTest` aly fixtures | Accepted with dual-path goldens; not a claim of final green |

Dual-path acceptance is **not** license to invent provider paths or claim TASK-043 suite green.

---

## Interaction with import activation (cross-matrix)

| Sequence | Require path role | Import path role |
| --- | --- | --- |
| `require "import"` then `import "java.io.File"` | Resolves import overlay / workspace import module | Source import facts + JVM class providers under `__jvm__/…` |
| `require "import"` then simple globals | Import module surface / globals | Default prefixes + wildcards (see import-resolution doc) |
| Sibling file A requires import; file B does not | Per-file require edges | Source imports must not leak into B (TASK-155 / TASK-176) |
| `luajava.bindClass("…")` without require import | No import module edge required for fact collection | Class loads still collected as JVM facts when literal |

Full import order remains in `docs/android-lua-import-resolution.md`. This matrix only records how **require names** land on providers before import side effects run.

---

## Configuration that affects require-adjacent resolution

| Metadata / setting | Affects require module path? | Affects post-require class/import path? |
| --- | --- | --- |
| Workspace folders / indexed `.lua`/`.aly` | Yes — available `VIRTUAL_PATH` providers | Indirect (source facts) |
| `LuaVersion.ANDROLUA_5_3` (default) | Yes — Android-Lua overlay catalog | Yes — globals + import helpers |
| `jvm.androidJar` (SDK or Downloads dual-path) | No (module names) | Yes — reflective Android classes |
| `jvm.classpath` / `jvm.classes` | No | Yes — extra class providers |
| `androlua.imports` | No | Yes — workspace-wide configured imports |
| `jvm.importPrefixes` | No | Yes — simple-name class resolution |

---

## Known gaps (do not claim final green)

1. **TASK-043** owns serialized `jvmTest` verification of require/overlay/`.aly` corpora. This doc does not claim green.
2. **TASK-176** (blocked on TASK-140 chain) still owns path-scoped import **query** preservation after `require "import"`.
3. **Free-form `.aly`** provider/export wiring may still be partial (dual-path CURRENTLY_ACCEPTS).
4. **Runtime** `package.path` templates, asset-only modules, dex-native searchers, and dynamic require targets are not fully simulated.
5. **Provider conflicts** beyond rank + path sort (for example multi-root relative collisions) are covered by dedicated workspace tasks/docs; this matrix only records the rank table.
6. **Host Downloads `android.jar`** is currently absent; analysis should use SDK android-35 when reflection is needed.

---

## Related product / test anchors (review-owned verification)

Workers must **not** run these; listed for TASK-043 / review only:

```text
JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home \
  ./gradlew.lf jvmTest --tests semantic.androidlua.AndroidLuaLibraryStubsTddTest

JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home \
  ./gradlew.lf jvmTest --tests semantic.androidlua.AlyLayoutRequireResolutionTddTest

JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home \
  ./gradlew.lf jvmTest --tests semantic.workspace.BuiltinOverlayLoaderTest
```

Primary implementation files (read-only references for this docs task):

- `src/commonMain/kotlin/.../workspace/DocumentFactsCollector.kt` — path → module name (incl. `.aly`)
- `src/commonMain/kotlin/.../workspace/WorkspaceModuleGraphBuilder.kt` — provider ranks + require edges
- `src/commonMain/kotlin/.../workspace/WorkspaceModuleResolver.kt` — `resolveRequire`
- `src/commonMain/kotlin/.../workspace/std/BuiltinOverlayLoader.kt` — Android-Lua catalog, managed precedence, reserved names

---

## Acceptance mapping (TASK-477)

| Acceptance criterion | Covered by |
| --- | --- |
| Scoped deliverable: docs refresh of Android-Lua require path matrix | This file: `docs/android-lua-require-path-matrix.md` |
| Non-overlapping file scope | Docs artifact only (+ task metadata / locks) |
| Host android.jar paths only (Downloads + SDK android-35); never `G:/` | Dual-path host table (SDK present, Downloads absent on re-check) |
| Accurate post-184 pre-043 state; no final green claim | Honesty bound, dual-path `.aly`, TASK-043 deferral |
| Docs-only; no Gradle | Explicit worker prohibition + deferred filters |

---

## Provenance

- Docs-only worker: TASK-477-WORKER-WAVE36F-20260712
- Post-TASK-184 library stub acceptance: REVIEW38-WAVE-WAVE36C (TASK-184 `done`)
- `.aly` require corpus acceptance: TASK-381 `done`
- Host jar re-check aligned with WAVE36E/F macOS paths (2026-07-11)
- No product code edits; no Gradle/tests/compile
