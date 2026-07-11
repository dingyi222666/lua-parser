# AndroidLuaContext Inherited Method Matrix

Date: 2026-07-11  
Task: TASK-426 (docs-only)

This matrix documents the **declaration + hover/completion expectations** for the AndroLua host context surface modeled as:

- `AndroidLuaContext` — shared base declaration
- `LuaActivity` — `activity` global (`---@class LuaActivity: AndroidLuaContext`)
- `LuaService` — `service` global (`---@class LuaService: AndroidLuaContext`)
- `this` — `LuaActivity | LuaService | AndroidLuaContext`
- `context` — typed as `android.content.Context` on the active overlay (not a pure `AndroidLuaContext` alias)

It is a focused companion to:

- `docs/android-lua-library-stub-matrix.md` — broader library/stub inventory (TASK-376)
- `docs/android-lua-library-models.md` — type-model requirements for `AndroidLuaContext`
- `docs/android-lua-compatibility-matrix.md` — support claims and open chains
- `docs/luajava-helper-surface-matrix.md` — `luajava.*` helpers (including `getContext`)

**Honesty bound:** rows describe handwritten Emmy-style fields on the Android-Lua `_G` overlays and the **intended** hover/completion surface for static analysis / LSP. They do **not** claim full Android Activity/Service runtime fidelity, full JVM reflection of `com.androlua.LuaActivity` / `LuaService`, or final green verification. Serialized Gradle/test verification remains review-owned under TASK-043; final Android-Lua acceptance remains TASK-037. No Gradle, compile, or test command was run for this documentation task. No product code was changed.

## Status labels

| Status | Meaning |
| --- | --- |
| Declared (overlay field) | Present as `---@field` on `AndroidLuaContext` in product resources / overlay sources. |
| Inherited (empty subclass) | `LuaActivity` / `LuaService` declare no extra fields; members are expected only via inheritance from `AndroidLuaContext`. |
| Semantic expected | Semantic / binder / member-resolution harnesses (e.g. `AndroidLuaLibraryStubsTddTest`) expect the member type or completion label when the overlay is active. |
| LSP gap (product-current) | Global `activity` / `service` / `this` may hover as the type **name**, but bare receiver member completion/hover on the CustomType path can still be empty/unknown without local Emmy re-annotation (see TASK-229 notes). |
| Reflective optional | Extra Android `Activity` / `Service` / `Context` members may appear only when `jvm.androidJar` / classpath reflection hydrates a Java class surface; not part of the handwritten context stub. |
| Deferred | Runtime-only lifecycle or overload fidelity; static analysis should not invent behavior. |

## Source of truth (declaration resources)

| Layer | Path | Role for this matrix |
| --- | --- | --- |
| Android-Lua `_G` overlay | `src/commonMain/resources/io/github/dingyi222666/luaparser/semantic/workspace/androidlua/androlua5.3/_G.lua` | Primary `AndroidLuaContext` / `LuaActivity` / `LuaService` fields + globals |
| Active LuaJava-facing `_G` | `src/commonMain/resources/.../std/androlua53-luajava/_G.lua` | Same context field set for the AndroLua 5.3 LuaJava overlay path |
| Builtin overlay source twin | `src/commonMain/kotlin/.../std/AndroidLua53LuaJavaBuiltinOverlaySources.kt` | Embedded twin of the same declarations |
| Compact class index only | `androidlua/androlua5.3/classes/androlua-runtime.index` | Lists `com.androlua.LuaActivity` / `LuaService` names; **not** a full method catalog |

Inheritance shape in all three declaration sources:

```text
---@class AndroidLuaContext
-- fields...
local AndroidLuaContext = {}

---@class LuaActivity: AndroidLuaContext
local LuaActivity = {}

---@class LuaService: AndroidLuaContext
local LuaService = {}
```

There are **no** activity-only or service-only extra fields in the current handwritten stubs. Differentiation is by **global binding** (`activity` vs `service` vs `this`), not by distinct method catalogs.

## Receiver globals vs types

| Global | Declared type | Expected shared members | Notes |
| --- | --- | --- | --- |
| `activity` | `LuaActivity` | Full `AndroidLuaContext` field set via inheritance | Hover should mention `LuaActivity` / `AndroidLuaContext` / activity-like name. |
| `service` | `LuaService` | Same inherited set | Service scripts use the same path/async/messaging helpers. |
| `this` | `LuaActivity \| LuaService \| AndroidLuaContext` | Same inherited set | Host object mirror; prefer members that exist on the base. |
| `context` | `android.content.Context` | Android `Context` surface (`getSystemService`, …), **not** the full AndroLua path helper set unless reflection/models also expose them | Overlay intentionally types `context` as platform `Context`, not `AndroidLuaContext`. |

## Fields (properties)

| Member | Declared type | On base | On `LuaActivity` | On `LuaService` | Hover expectation | Completion expectation | Status / notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `luaDir` | `string` | Yes | Inherited | Inherited | Property/type mention `string` when member surface expands | Label `luaDir` among context members | Declared; often less exercised than getters |
| `luaPath` | `string` | Yes | Inherited | Inherited | Same as `luaDir` | Label `luaPath` | Declared |
| `Width` | `integer` | Yes | Inherited | Inherited | Dimension-like number | Label `Width` | Declared (AndroLua host size helpers) |
| `Height` | `integer` | Yes | Inherited | Inherited | Dimension-like number | Label `Height` | Declared |

## Methods (declared on `AndroidLuaContext`)

All methods below are **Declared (overlay field)** on the base and **Inherited (empty subclass)** on both `LuaActivity` and `LuaService`.

| Member | Declared signature (overlay) | Runtime role (study / models) | Semantic hover / type expectation | Semantic completion expectation | LSP product-current note | Status |
| --- | --- | --- | --- | --- | --- | --- |
| `getContext` | `fun(): any` | Return Android context userdata | Function/method; return loosely typed | Label `getContext` | May need Emmy local stub for member surface | Declared |
| `getLuaDir` | `fun(): string` | Project/script directory | Member type fragment `fun`; hover may show `string` return | Labels include `getLuaDir` (golden in `AndroidLuaLibraryStubsTddTest`) | Bare `activity.getLuaDir` often empty on LSP CustomType path; annotated local stubs expected | Declared + semantic expected; **LSP gap** for bare global |
| `getLuaPath` | `fun(): string` | Current script path | Function; `string` | Label `getLuaPath` (asserted on `service` / `this` harnesses) | Same CustomType gap as other members | Declared + semantic expected |
| `getLuaExtDir` | `fun(): string` | External/app-ext dir | Function; `string` | Label `getLuaExtDir` | Same | Declared |
| `getLuaExtPath` | `fun(...: any): string` | Path under ext dir (vararg) | Function; `string` | Label `getLuaExtPath` | Vararg fidelity not overload-checked | Declared |
| `getClassLoaders` | `fun(): table` | Dex/class loader table | Function; table-like | Label `getClassLoaders` | Not a live loader inventory | Declared |
| `getLibrarys` | `fun(): table<string, string>` | Loaded library name map (AndroLua spelling) | Function; string map | Label `getLibrarys` (note spelling) | Declaration only | Declared |
| `loadDex` | `fun(name: string): JavaObject` | Load dex/plugin by name | Function; `JavaObject`-ish | Label `loadDex` (activity completion golden) | Does not execute loaders; unresolved name stays opaque | Declared + semantic expected |
| `sendMsg` | `fun(message: any)` | Host message / toast-ish path | Function | Label `sendMsg` (service completion golden) | No runtime delivery | Declared + semantic expected |
| `sendError` | `fun(title: string, error: any)` | Error reporting to host | Function | Label `sendError` | No UI emulation | Declared + semantic expected |
| `newActivity` | `fun(path: string, arg?: table)` | Start another Lua activity | Function | Label `newActivity` | Lifecycle not simulated | Declared + semantic expected |
| `newTask` | `fun(src: string\|function, callback?: function): JavaObject` | Async task helper | Function; `JavaObject` | Label `newTask` | Not a concurrency simulator | Declared + semantic expected |
| `newThread` | `fun(src: string\|function): JavaObject` | Thread helper | Function; `JavaObject` | Label `newThread` | Not a concurrency simulator | Declared |
| `setContentView` | `fun(view: AndroidView\|LuaLayoutSpec\|any)` | Set activity content / layout | Function | Label `setContentView` (activity completion golden; colon-call fixtures in LSP stub tests) | Common activity path; still base-declared, not activity-only | Declared + semantic expected |
| `getMenu` | `fun(): AndroidMenu` | Options menu surface | Function; `AndroidMenu` | Label `getMenu` | Menu methods are the separate `AndroidMenu` stub | Declared |
| `getSystemService` | `fun(name: string): any` | Android system service lookup | Function; `any` | Label `getSystemService` | Also expected on platform `context` | Declared + semantic expected (via `context` / base) |

## Inheritance matrix (who exposes what)

| Member | `AndroidLuaContext` | `LuaActivity` (`activity`) | `LuaService` (`service`) | `this` union | `context` (`android.content.Context`) |
| --- | --- | --- | --- | --- | --- |
| `luaDir` / `luaPath` / `Width` / `Height` | Declared | Inherited | Inherited | Expected if typed as context/activity/service | **Not** claimed by AndroLua stub; reflective `Context` only if present |
| `getLuaDir` / `getLuaPath` / `getLuaExtDir` / `getLuaExtPath` | Declared | Inherited | Inherited | Expected | Not AndroLua-stub claimed |
| `getContext` | Declared | Inherited | Inherited | Expected | N/A (receiver *is* context-like) |
| `getClassLoaders` / `getLibrarys` / `loadDex` | Declared | Inherited | Inherited | Expected | Not claimed |
| `sendMsg` / `sendError` | Declared | Inherited | Inherited | Expected | Not claimed |
| `newActivity` / `newTask` / `newThread` | Declared | Inherited | Inherited | Expected | Not claimed |
| `setContentView` / `getMenu` | Declared | Inherited | Inherited | Expected | Not claimed (Activity-specific at runtime; stub still on base) |
| `getSystemService` | Declared | Inherited | Inherited | Expected | **Expected** as platform `Context` member when models/reflection available |

**Design note:** Runtime `setContentView` / menu APIs are activity-centric, but the **product stub** places them on the shared base so `activity` / `this` / permissive `service` receivers share one declaration catalog. Do not invent separate empty subclass method lists until corpus evidence forces a split.

## Hover / completion expectations by analysis path

| Path | Receiver example | Hover expectation | Completion expectation | Documented product reality |
| --- | --- | --- | --- | --- |
| Semantic harness (binder + member resolver + completion provider) | `activity.getLuaDir` after `require "import"` | Global type contains `LuaActivity`; member `getLuaDir` is METHOD / `fun…` | Member list includes at least `getLuaDir`, `newActivity`, `newTask`, `loadDex`, `setContentView` | Covered by `AndroidLuaLibraryStubsTddTest` (TASK-184 accepted) |
| Semantic harness | `service.getLuaDir` | Type contains `LuaService`; `getLuaDir` method | Includes `sendMsg`, `sendError`, `getLuaPath` | Same test class |
| Semantic harness | `this.getLuaPath`, `context.getSystemService` | `this` → `AndroidLuaContext`; `context` → `android.content.Context` | Member probes on those names | Same test class |
| LSP E2E (bare global) | `activity` identifier | Mentions `activity` / `LuaActivity` / `AndroidLuaContext` / `Activity` | Global name may appear; **member** list on bare CustomType often empty | `LspAndroidLuaE2eActivityStubTddTest` documents gap |
| LSP E2E (Emmy local stub) | `---@class ActivityStub` + fields/methods; `host.getLuaDir` | Member hover can show function/`string` | Labels: `getLuaDir`, `newActivity`, `newTask`, `loadDex`, `setContentView` | Fixture models the **expected** stub surface when CustomType expansion is insufficient |
| Reflective `com.androlua.LuaActivity` | `import "com.androlua.LuaActivity"` + jar/index | Class name / Java members if provider mounts | Public Java methods from jar/index only | Index lists FQCN; full signatures need reflection — **Reflective optional** |
| Missing overlay / wrong Lua version | Plain Lua 5.3 without AndroLua mode | No AndroLua context globals | No `getLuaDir` from this matrix | Degrade to unknown / absent |

## Unknown / degrade policy

1. **No invented Activity/Service lifecycle.** Declaring `onCreate` / `onDestroy` Lua callbacks as host methods is out of scope; those are script-side hooks invoked by the runtime, not fields on the overlay class.
2. **No full `android.app.Activity` dump on the stub.** Handwritten context members are the high-value AndroLua helpers. Extra Android members require reflection/`android.jar` and remain **Reflective optional**.
3. **Empty subclass honesty.** Because `LuaActivity` / `LuaService` declare no extra methods, docs must not claim activity-only vs service-only catalogs beyond global typing and corpus goldens that simply sample different member names.
4. **CustomType member expansion gap.** If hover shows `LuaActivity` but completion on `activity.` is empty, that is a known **LSP gap**, not a missing row in this matrix. Local Emmy `---@class` / `---@field` / `---@method` fixtures remain the supported expansion shape until product CustomType hydration improves.
5. **`context` is not a second `AndroidLuaContext`.** Prefer `getSystemService` and other `android.content.Context` members on `context`; do not expect `newTask` / `loadDex` on `context` from the AndroLua stub alone.
6. **Spelling `getLibrarys`.** Overlay keeps the AndroLua runtime spelling; do not “fix” to `getLibraries` in the stub matrix.
7. **Colon vs dot.** Lua may call `activity:setContentView(view)` or `activity.setContentView(view)`. Completion labels are bare member names; call-style does not add new matrix rows.
8. **Dex / task / thread execution is not simulated.** Successful static typing of `loadDex` / `newTask` / `newThread` does not mean loaders ran or threads scheduled.
9. **Host `android.jar` paths** (never hardcode `G:/`): prefer `jvm.androidJar`, then `/Users/dingyi/Downloads/android.jar`, then `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar` / SDK discovery. Missing jar does not remove handwritten context fields; it only limits reflective extras.
10. **Final suite green is still TASK-043-owned.** Matrix rows are documentation of intended surfaces, not a substitute for serial verification.

## Fixture / TDD cross-links (verification still review-owned)

| Surface | Representative tests |
| --- | --- |
| Overlay globals + context members | `src/jvmTest/kotlin/semantic/androidlua/AndroidLuaLibraryStubsTddTest.kt` (`activity_global_has_lua_activity_type_and_context_methods`, `service_global_…`, `this_and_context_…`) |
| ClassType inheritance member resolution | `src/jvmTest/kotlin/semantic/checker/MemberResolverClassTypeDotMethodTddTest.kt` |
| Builtin seeder global types | `src/jvmTest/kotlin/semantic/binder/BuiltinSymbolSeederClassTypeGlobalTddTest.kt` |
| LSP activity stub hover/completion (incl. CustomType gap) | `src/jvmTest/kotlin/lsp/LspAndroidLuaE2eActivityStubTddTest.kt` |
| Corpus anchors | `src/jvmTest/resources/integration/androidlua/corpus-manifest.md` (`java-lua-activity`, `java-lua-service`) |

## Related docs

- `docs/android-lua-library-stub-matrix.md`
- `docs/android-lua-library-models.md`
- `docs/android-lua-architecture.md`
- `docs/android-lua-compatibility-matrix.md`
- `docs/android-platform-setup.md`
- `docs/member-resolver-chain-evaluation.md`
- Overlay resources: `androidlua/androlua5.3/_G.lua`, `std/androlua53-luajava/_G.lua`
