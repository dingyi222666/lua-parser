# Android-Lua helper library and type-model inventory

Study date: 2026-06-06

Task: TASK-004

Implementation updates: TASK-044, 2026-06-07; TASK-033, 2026-06-08

Post-TASK-184 docs refresh: TASK-452 (WAVE36E), 2026-07-11 — inventory kept; product-surface honesty, dual-path host `android.jar` policy, and accepted library-stub wiring recorded below. Docs-only; no Gradle/tests/compile.

External source studied read-only:

- `/Users/dingyi/projects/java_projects/Android-Lua/app/src/main/assets`
- `/Users/dingyi/projects/java_projects/Android-Lua/app/src/main/java`
- `/Users/dingyi/projects/java_projects/Android-Lua/app/src/main/resources/lua`

Reference docs used from this repository:

- `docs/android-lua-architecture.md`
- `docs/android-lua-import-luajava.md`
- `docs/android-lua-library-stub-matrix.md` — intended product surface matrix (companion)
- `docs/member-resolver-chain-evaluation.md` — multi-level chain + post-184 Android-Lua hops
- `docs/android-lua-verification.md` — corpus verification + host jar dual-path policy
- `docs/android-lua-context-method-matrix.md` — `activity` / `service` / `this` / `context` catalog

External repository provenance:

- Repository: `/Users/dingyi/projects/java_projects/Android-Lua`
- Remote: `https://github.com/TheMostBlack/Android-Lua.git`
- Observed commit: `686a792dbdd2fe9727a34768ceadffcaa2abc20d` (host re-check 2026-07-11 WAVE36E)
- No external source was copied into this repository for TASK-004.
- No external source was copied into this repository for TASK-044. The TASK-044 resource files are handwritten declaration models and compact class-name catalogs derived from read-only inspection.
- No external source was copied into this repository for TASK-033. TASK-033 wires the existing handwritten declaration resources into the built-in Android-Lua overlay catalog and adds small declaration-only helper aliases where TDD coverage expects reusable module exports.
- TASK-184 restored library-stub fixture/type surfaces against those overlays without vendoring external runtime source.

## Summary

Android-Lua analysis needs two different kinds of library knowledge:

- Runtime Lua module stubs for the modules scripts load with `require`, especially `import`, `loadlayout`, `loadbitmap`, `loadmenu`, networking/encoding helpers, and app helper modules.
- Java class and object models for the values exposed by LuaJava and AndroLua globals, especially `activity`, `service`, `this`, `luajava`, Android framework classes, and bundled helper classes under `com.androlua`, `com.luajava`, custom `android.widget`, and `com.nirenr`.

The preferred implementation direction is to model Android-Lua with typed stub overlays, not by copying the runtime source wholesale. The runtime code is highly dynamic, mutates environments, loads Java/dex/native modules, and includes app/editor scripts that are useful as fixtures but noisy as standard libraries.

## Inventory

### Managed runtime Lua modules

The most important source area is `/Users/dingyi/projects/java_projects/Android-Lua/app/src/main/resources/lua`. It contains 28 Lua files that `Welcome.java` extracts into the managed Lua directory and that `package.path` can resolve at runtime.

Modules and high-level roles:

- `import.lua`: central Android-Lua environment mutator. It installs/imports `import`, lazy Java class lookup, helper globals, LuaJava package chaining, native library searchers, and default helpers.
- `loadlayout.lua`: table and `.aly` layout loader. It binds Android widget classes, resolves style/resource values, creates views, assigns ids, creates layout params, and wires listener tables/functions.
- `loadbitmap.lua`: image loader for HTTP, local Lua-directory paths, and absolute/local paths. It returns Android bitmap/drawable-like values through `LuaBitmap`.
- `loadmenu.lua`: table-to-menu loader for Android menu objects.
- `autotheme.lua`: small SDK-sensitive Android theme chooser.
- `permission.lua`: permission name and permission-description tables.
- `console.lua`: build helpers for Lua and `.aly` projects.
- `bin.lua`: APK/package build helper, strongly tied to Android file, zip, signing, and `activity.newTask` APIs.
- `bmob.lua`: Bmob HTTP API wrapper.
- `http.lua`, `ftp.lua`, `smtp.lua`, `socket.lua`, `socket/tp.lua`, `socket/url.lua`, `socket/headers.lua`, `ltn12.lua`, `mime.lua`, `mbox.lua`: LuaSocket-style networking and transfer helpers, with native `socket`/`mime` dependencies.
- `json.lua`, `xml.lua`, `base64.lua`, `hex.lua`: pure or mostly pure data helpers.
- `check.lua`, `logcat.lua`, `options.lua`, `su.lua`, `test.lua`: environment, log, shell, options, and debug helpers.

These modules should become the base Android-Lua stub set. The first pass should be handwritten typed declarations that expose module surfaces and important side effects; exact implementation copying is unnecessary for useful semantic coverage.

### App/editor assets

`/Users/dingyi/projects/java_projects/Android-Lua/app/src/main/assets` is broader than a library package. It contains 42 `.lua` files, 10 `.aly` layout files, images/fonts, signing material, and precompiled odex/vdex artifacts.

Root Lua assets:

- App/editor entry scripts and screens: `main.lua`, `main2.lua`, `main3.lua`, `main4.lua`, `main5.lua`, `main6.lua`, `main7.lua`, `main9.lua`, `main10.lua`, `main11.lua`, `main12.lua`, `main13.lua`, `main14.lua`, `init.lua`, `other.lua`.
- Helper modules with reusable API value: `AndLua.lua`, `ThomeLua.lua`, `yidian.lua`, `Dialog.lua`, `file.lua`, `toast.lua`, `xml2table.lua`, `color.lua`, `color1.lua`, `dj.lua`, `bmob.lua`, `bin.lua`, `loadlayout2.lua`, `loadlayout3.lua`.
- Large class-reference files: root `android.lua` and `javaapi/android.lua`.

These root assets are not all standard libraries. Treat them as follows:

- Use app/editor scripts as parser and semantic fixtures.
- Type only helpers that are plausibly imported by user projects, especially `AndLua`, `ThomeLua`, `Dialog`, `file`, `toast`, `xml2table`, `loadlayout2`, `loadlayout3`, `bmob`, and `bin`.
- Do not type the `main*.lua` app screens as libraries. They are application code, not reusable standard modules.
- Do not vendor binary/media/signing assets. They have no useful type-model value.

### `.aly` layout files

`.aly` files under `assets` are Lua table layout literals. The observed files are:

- `layout.aly`
- `My.aly`
- `project.aly`
- `projectitem.aly`
- `Community.aly`
- `javaapi/layout.aly`
- `javaapi/clayout.aly`
- `javaapi/mlayout.aly`
- `plugin/layout.aly`
- `plugin/item.aly`

These should be fixtures and grammar/type-shape inputs, not vendored library modules. The semantic model should understand that an `.aly` file produces an Android-Lua layout table and that `loadlayout(tableOrAlyResult, root?, group?)` returns a view plus optional id bindings through the supplied root/id table.

Useful model concepts:

- First array entry can be a Java class object or class name representing a view class.
- String keys represent Android/AndroLua attributes, layout params, ids, or listener names.
- Numeric children are nested view/layout tables.
- Listener fields such as `onClick` are functions or tables that LuaJava can proxy to Java listener interfaces.
- `id` fields introduce globals or entries into a supplied ids/root table at runtime.

### `assets/javaapi`

`assets/javaapi` is an editor/import-helper app:

- `android.lua`: very large Android class reference/import table.
- `fiximport.lua`: scans Lua source and inserts likely import statements. It is not the runtime importer.
- `main.lua`, `init.lua`, and `.aly` files: UI for the helper.

Priority: mirror selected class-name metadata only if it adds completion value beyond `android.jar`. Prefer Android SDK/JVM reflection class indexes for authoritative Android framework symbols. Keep `fiximport.lua` as a fixture for import-normalization behavior.

### `assets/layouthelper`

`assets/layouthelper` contains a helper app for layout conversion/authoring:

- `loadlayout2.lua`, `loadlayout3.lua`: alternate layout loaders.
- `xml2table.lua`, `layout.lua`, `main.lua`, `init.lua`: layout-helper app.

Priority: fixture plus optional lightweight stubs for `loadlayout2`, `loadlayout3`, and `xml2table`. The alternate loaders help test compatibility with nonstandard layout syntax but should not displace the runtime `resources/lua/loadlayout.lua` model.

### `assets/plugin`

`assets/plugin` contains a small plugin UI app:

- `init.lua`
- `main.lua`
- `layout.aly`
- `item.aly`

Priority: fixture. Model plugin-specific globals only if later corpus tests show user scripts import these modules.

### Java helper classes

The Java tree has 493 `.java` files. The packages that matter most to Lua analysis are:

- `com.androlua`: runtime context, activity/service integration, async/thread/timer helpers, resource helpers, adapters, editor widgets, file/network helpers, bitmap/drawable helpers, dex loading, and utility APIs.
- `com.luajava`: Java side of LuaJava, including `LuaState`, `LuaJavaAPI`, `LuaObject`, `LuaFunction`, `LuaTable`, `LuaList`, Java proxies, and native bridge entry points.
- Custom `android.widget`: bundled widget/helper classes such as adapters, `CardView`, `DrawerLayout`, `PageView`, `PageLayout`, `PullingLayout`, `RippleLayout`, `FloatButton`, `CircleImageView`, `ToolBar`, and list/page helpers.
- `com.nirenr` and `com.nirenr.screencapture`: split editor, color finder, point/color utilities, and screen-capture helpers.
- `com.osfans.trime`: app/IME implementation and settings utilities. This is important for app behavior but lower priority for general Android-Lua stubs unless imported by scripts.
- `com.android.cglib.dx` and `com.android.cglib.proxy`: dex/proxy internals. Model only as opaque classes unless direct Lua usage appears.

The Java model should not copy Java source into common resources. It should derive signatures from reflection/source inspection where possible and expose them as class symbols, method/property metadata, and constructor call surfaces.

## Priority and action plan

### P0: type/model first

These are required before TASK-021 can assert meaningful types:

- `import` module and `import(...)` global side effects.
- `luajava` module table: `bindClass`, `new`, `newInstance`, `loadLib`, `createProxy`, `newArray`, `createArray`, `astable`, `tostring`, `instanceof`, `getContext`, and `override`.
- Android-Lua globals in activity/service contexts: `activity`, `service`, `this`, `context`, `luajava`, `print`, `set`, `call`, `loadlayout`, `loadbitmap`, `loadmenu`, and `import`.
- `loadlayout`, `loadbitmap`, `loadmenu` return types and common argument shapes.
- Java class values and Java object values, including constructor calls, static fields, instance method calls, bean getter/setter property access, nested class access, and listener table/function proxies.
- Default import package prefixes from `import.lua`: empty prefix, `java.lang`, `java.util`, and `com.androlua`; wildcard import prefixes should be tracked as lazy prefixes, not expanded eagerly.
- Explicit imported classes from `import "pkg.Class"` and table imports from sequential array entries.
- Inner-class compatibility for explicit import strings that use `_` in place of Java `$`.

### P1: mirror or model selectively

These improve real project coverage but can be added after the first P0 overlay:

- LuaSocket-style modules: `socket`, `socket.url`, `socket.tp`, `socket.headers`, `ltn12`, `mime`, `http`, `ftp`, `smtp`, `mbox`.
- Data helpers: `json`, `xml`, `base64`, `hex`.
- Android-Lua utilities: `permission`, `autotheme`, `console`, `bin`, `bmob`, `su`, `logcat`, `check`.
- App helper modules from `assets`: `AndLua`, `ThomeLua`, `Dialog`, `file`, `toast`, `xml2table`, `loadlayout2`, and `loadlayout3`. Asset helpers named `bmob` and `bin` duplicate managed runtime modules, so the managed `resources/lua` declarations take precedence.
- Bundled Java helper classes under `com.androlua`, `com.luajava`, custom `android.widget`, and `com.nirenr`, generated or modeled as public class/member metadata.

### P2: fixtures only unless later evidence changes priority

- `assets/main*.lua`, root app screens, plugin UI, javaapi UI, layouthelper UI.
- `.aly` files as parse/type fixtures.
- `assets/javaapi/fiximport.lua` as import-helper behavior fixture.
- `assets/android.lua` and `assets/javaapi/android.lua` as optional class-name catalogs. Prefer `android.jar` reflection for authoritative platform classes.
- `com.osfans.trime` app/IME classes unless project scripts directly import them.

### Defer or exclude

- Images, fonts, `.XY` data files, signing keys, `.odex`, `.vdex`, and native build artifacts.
- JNI/C library implementations. Represent their Lua-facing modules with stubs only.
- Dex/proxy generator internals unless a user-facing Lua API directly exposes them.

## Proposed standard-type and stub package layout

The repository already uses EmmyLua-style Lua resource overlays under:

`src/commonMain/resources/io/github/dingyi222666/luaparser/semantic/workspace/std/`

Android-Lua should use a sibling resource tree so Lua standard libraries and Android-Lua compatibility remain separate:

```text
src/commonMain/resources/io/github/dingyi222666/luaparser/semantic/workspace/androidlua/
  androlua5.3/
    _G.lua
    luajava.lua
    import.lua
    loadlayout.lua
    loadbitmap.lua
    loadmenu.lua
    modules/
      autotheme.lua
      base64.lua
      bin.lua
      bmob.lua
      check.lua
      console.lua
      ftp.lua
      hex.lua
      http.lua
      json.lua
      ltn12.lua
      mbox.lua
      mime.lua
      permission.lua
      smtp.lua
      socket.lua
      su.lua
      xml.lua
      socket/
        headers.lua
        tp.lua
        url.lua
    helpers/
      AndLua.lua
      Dialog.lua
      ThomeLua.lua
      file.lua
      loadlayout2.lua
      loadlayout3.lua
      toast.lua
      xml2table.lua
    classes/
      android-framework.index
      androlua-runtime.index
      bundled-widgets.index
```

TASK-044 created this sibling resource tree as a first handwritten Android-Lua model overlay. TASK-033 then integrated that tree into the `LuaVersion.ANDROLUA_5_3` built-in overlay provider catalog so `require` can resolve Android-Lua root helpers, managed runtime modules, dotted LuaSocket modules, and selected asset helper modules from synthetic `__lua_std__/androlua5.3/...` provider paths.

Implemented resource surface:

- Root Android-Lua declarations: `_G.lua`, `luajava.lua`, `import.lua`, `loadlayout.lua`, `loadbitmap.lua`, and `loadmenu.lua`.
- Managed runtime modules from `/Users/dingyi/projects/java_projects/Android-Lua/app/src/main/resources/lua`: `autotheme`, `base64`, `bin`, `bmob`, `check`, `console`, `ftp`, `hex`, `http`, `json`, `ltn12`, `logcat`, `mbox`, `mime`, `options`, `permission`, `smtp`, `socket`, `socket.headers`, `socket.tp`, `socket.url`, `su`, `test`, and `xml`.
- Selected reusable app helper models from `/Users/dingyi/projects/java_projects/Android-Lua/app/src/main/assets`: `AndLua`, `ThomeLua`, `Dialog`, `file`, `toast`, `xml2table`, `loadlayout2`, and `loadlayout3`. The tree also keeps declaration resources for asset `bmob` and `bin`, but those duplicate managed runtime modules and are not exported as separate overlay providers.
- Compact class indexes: `android-framework.index`, `androlua-runtime.index`, and `bundled-widgets.index`.

The declarations model useful static-analysis shapes rather than exact runtime behavior. They expose Android-Lua globals, LuaJava functions, layout table aliases, menu/layout/bitmap helpers, LuaSocket-style source/sink/request tables, JSON/XML/base64/hex helpers, selected Android-Lua utility modules, and class-name catalogs for Java completion/resolution work.

TASK-033 provider catalog surface:

- Root helpers: `require "import"`, `require "loadlayout"`, `require "loadbitmap"`, and `require "loadmenu"` resolve as Android-Lua overlay providers.
- Managed runtime modules: `autotheme`, `base64`, `bin`, `bmob`, `check`, `console`, `ftp`, `hex`, `http`, `json`, `logcat`, `ltn12`, `mbox`, `mime`, `options`, `permission`, `smtp`, `socket`, `socket.headers`, `socket.tp`, `socket.url`, `su`, `test`, and `xml` are cataloged with stable field/method surfaces.
- Asset helper modules: `AndLua`, `Dialog`, `ThomeLua`, `file`, `loadlayout2`, `loadlayout3`, `toast`, and `xml2table` are cataloged as declaration providers.
- Duplicate helper names have explicit precedence: `require "bmob"` and `require "bin"` resolve to the managed runtime declarations under `modules/`, not to asset helper declarations under `helpers/`. The loader intentionally does not export `helpers.bmob`, `helpers.bin`, `assets.bmob`, or `assets.bin` aliases.
- Callable helper modules expose a synthetic `__call` field on their module type so semantic call checking and query surfaces can distinguish callable module values.
- The catalog keeps the resource files as the source text analyzed for document facts while attaching explicit module export surfaces for stable field, method, and callable metadata.

Android framework static resource integration:

- A separate Android framework resource root now exists at `src/commonMain/resources/io/github/dingyi222666/luaparser/semantic/workspace/android-framework/`. It contains `manifest.index`, curated `packages/*.index` class-name lists, compact `models/*.lua` member declarations, and `PROVENANCE.md`.
- TASK-056 seeded those resources from read-only Android SDK Platform 35 class enumeration and Android-Lua compatibility catalogs. They are original metadata and declaration files, not copied Android SDK source.
- TASK-146 wires the `android-framework` manifest into the `LuaVersion.ANDROLUA_5_3` built-in overlay loader. The loader validates manifest rows and package indexes, then creates synthetic class providers under `__jvm__/classes/...`, package providers under `__jvm__/packages/...`, and full/binary/dotted alias providers under `__jvm__/class-aliases/...`.
- These resources are active static model inputs for the curated Android packages and classes named by the manifest. They can satisfy common Android-Lua imports, package completions, member hovers, and definitions for documented classes such as `android.widget.TextView`, `android.view.View`, nested listener/enum types, and selected layout/styling APIs when no local `android.jar` reflection provider is configured.
- Static framework resources are still intentionally incomplete. They do not replace reflective SDK class loading, do not claim exhaustive Android API-level coverage, and do not model runtime resource lookup. Use `android.jar` reflection for authoritative platform class availability, broad member/constructor metadata, wildcard package enumeration beyond the curated indexes, and SDK/API-specific behavior.
- The older Android-Lua catalog file at `androidlua/androlua5.3/classes/android-framework.index` remains a compact compatibility/provenance class-name catalog. It is useful as a seed for completion/resolution work, but the TASK-146 production static framework providers come from the `android-framework/manifest.index` resource set.

LuaJava overlay source of truth:

- Active loading uses `src/commonMain/resources/io/github/dingyi222666/luaparser/semantic/workspace/std/androlua53-luajava/luajava.lua` for the `luajava` provider under `LuaVersion.ANDROLUA_5_3`.
- The broader Android-Lua declaration tree also contains `src/commonMain/resources/io/github/dingyi222666/luaparser/semantic/workspace/androidlua/androlua5.3/luajava.lua`, but the current built-in loader does not use that file as the active `luajava` provider target.
- The read-only Android-Lua project has native LuaJava registration and `resources/lua/import.lua` behavior, not a standalone raw `resources/lua/luajava.lua` source file. Use those raw files for runtime behavior provenance, not as the static overlay source of truth.
- `AndroidLua53LuaJavaBuiltinOverlaySources.luajavaSource` is an embedded fallback for missing classpath resources. It should mirror the active resource closely enough for degraded loading, but edits should start from the `std/androlua53-luajava/luajava.lua` resource unless a later task explicitly changes the loader contract.

TASK-044/TASK-033 provenance notes:

- Each resource file is a declaration or name catalog, not a copied implementation file.
- The class catalogs contain fully qualified class names and provenance comments only.
- App helper stubs use English-facing representative APIs where the source primarily installs global helper functions with non-ASCII names or app-specific UI behavior.
- TASK-033 integrates the resource tree into built-in overlay module loading. Remaining exact runtime behavior, such as Java overload selection, layout id propagation, listener proxy parameter inference, and Android resource lookup, remains semantic-engine work rather than declaration copying.

Recommended semantics for those files:

- `_G.lua`: declares Android-Lua global symbols and context variables. Keep it small and explicit.
- `luajava.lua`: declares LuaJava functions and opaque value classes such as `JavaClass<T>`, `JavaObject`, `JavaArray<T>`, and `JavaProxy`.
- `import.lua`: declares the returned `env_import` function and the global `import` function. The semantic engine should handle import side effects in Kotlin, because the side effects depend on per-file imports and wildcard prefixes.
- `loadlayout.lua`: declares layout table aliases and the `loadlayout` return shape. Kotlin can add special facts for `.aly` files and id tables.
- `modules/`: module stubs named by normal `require` names. For example `require "socket.url"` maps to `modules/socket/url.lua`.
- `helpers/`: lower-priority app helper stubs from `assets`; these should be added only when TASK-021 or corpus tests need them.
- `classes/`: compact metadata catalogs rather than copied source. These can list fully qualified class names, package aliases, and source provenance. Public member signatures should come from JVM/Android reflection where available.

Kotlin integration remains separate from the declaration resources. Current built-in overlay integration lives in the existing semantic workspace package:

```text
src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/std/
  BuiltinOverlayLoader.kt
```

Current loader behavior:

- `LuaVersion.ANDROLUA_5_3` loads the normal Lua 5.3 overlay plus Android-Lua overlay globals/modules.
- Android-Lua module overlay virtual paths stay synthetic under `__lua_std__/androlua5.3/...`.
- Static Android framework class/package overlay virtual paths stay synthetic under `__jvm__/classes/...`, `__jvm__/packages/...`, and `__jvm__/class-aliases/...`.
- Module resolution maps both root modules (`import`, `loadlayout`) and dotted modules (`socket.url`) to overlay files.
- Import side effects should be represented as document facts: explicit class imports, wildcard prefixes, `require "import"` activation, imported helper globals, and layout id exports.
- Java class completion should combine Android SDK class indexes, JDK classes, bundled helper class indexes, explicit imports, wildcard prefixes, and default prefixes.

Compatibility limits for the TASK-044 resource models:

- They are permissive declarations. They do not attempt Java overload resolution, Android resource lookup, LuaJava metatable behavior, dex loading, native library loading, or exact `loadlayout` reflection semantics.
- Wildcard imports and class-name indexes are intentionally lazy metadata. They should assist candidate resolution and completion, not eagerly populate globals.
- Static Android framework resource models cover only curated packages/classes and documented high-value members. They are useful when `android.jar` is unavailable, but broad or API-level-exact Android framework coverage still depends on the reflective `android.jar` path.
- `options.lua`, `test.lua`, app/editor screens, `.aly` files, media assets, signing material, odex/vdex artifacts, JNI/C implementations, and app-specific plugin UI remain fixture material unless later corpus evidence justifies more modeling.
- Helper module models from `assets` are compatibility aids, not a promise that every source-level global helper name is declared. Asset declarations with names that duplicate managed runtime modules stay subordinate unless the loader adds an explicit alias.

Future extension steps:

- Expand the Android-Lua overlay catalog as corpus evidence identifies additional reusable modules or app helper surfaces.
- Merge richer typed global declarations into binder-visible built-in symbols when `LuaVersion.ANDROLUA_5_3` or an Android-Lua workspace mode is active.
- Consume class indexes alongside Android SDK/JDK reflection indexes for class completion and import resolution.
- Add generated member metadata only where reflection/source inspection produces stable public signatures.

## Type-model requirements

The type model should include these shapes:

- `AndroidLuaContext`: common methods available on `LuaActivity` and `LuaService` through `LuaContext`, including file/path helpers, `newActivity`, `newTask`, `newThread`, `loadDex`, `getClassLoaders`, `getLibrarys`, `sendMsg`, `sendError`, and `getLua*` directory/path helpers.
- `LuaActivity` and `LuaService`: context-specific globals. `activity` should include Android `Activity` behavior plus AndroLua helpers; `service` should include Android `Service` behavior plus AndroLua helpers.
- `JavaClass<T>`: callable class value with constructor behavior, static member lookup, nested class lookup, and primitive/array compatibility.
- `JavaObject`: method/property access through Java methods, fields, bean getters/setters, map/list/array special cases, and listener assignment.
- `JavaProxy`: object returned by listener/function proxy creation.
- `LuaLayoutSpec`: recursive table type for layout literals, with view class in array slot 1, nested child specs, string attributes, listener fields, and `id`.
- `LuaLayoutIds`: table populated by layout `id` declarations.
- `LuaSocket` surfaces: client/server socket objects, source/sink functions, URL tables, and request/response tables.
- Data helper modules: JSON encode/decode, XML node table helpers, base64/hex encode/decode.

## TASK-021 test guidance

The first TDD package should assert at least these behaviors:

- `require "import"` makes `import`, `loadlayout`, `loadbitmap`, `loadmenu`, and LuaJava helpers known.
- `import "java.io.File"` introduces a `File` class value.
- `import "java.io.*"` records a lazy prefix and allows `File` to resolve as a candidate.
- `import { "java.io.File", "java.util.ArrayList" }` handles sequential table entries.
- Explicit `View_OnClickListener`-style imports normalize to nested Java class candidates.
- `luajava.bindClass("android.widget.TextView")` returns a class value.
- Calling a Java class value returns an object value.
- Assigning `onClick = function(...) end` in a layout table is accepted as a listener proxy shape.
- `loadlayout(layout)` returns an Android view-like value.
- `.aly` fixture files parse as layout specs, not arbitrary unknown modules.
- `activity` and `service` expose AndroLua context methods.
- `json`, `xml`, `base64`, `http`, `socket.url`, and `permission` resolve as known modules with named exports.

## Post-TASK-184 product surfaces (library models)

TASK-184 restored Android-Lua library stub fixtures and type surfaces after the
TASK-168 `bmob`/`bin` precedence work. Review accepted TASK-184 as **done**
(2026-07-11). This section records how those product surfaces relate to the
inventory above. It is documentation only:

- It does **not** claim TASK-043 suite green or final Android-Lua acceptance
  (TASK-037).
- It does **not** authorize workers to run Gradle, tests, or compile commands.
- Companion detail for multi-hop typing lives in
  `docs/member-resolver-chain-evaluation.md` (Post-TASK-184 section).
- Companion surface matrix lives in `docs/android-lua-library-stub-matrix.md`.

Primary corpus / fixtures after TASK-184:

| Artifact | Role |
| --- | --- |
| `src/jvmTest/kotlin/semantic/androidlua/AndroidLuaLibraryStubsTddTest.kt` | Library-stub goldens (globals, load*, layout/`.aly`, helpers, free-id completions) |
| `src/jvmTest/resources/semantic/androidlua/library-fixtures/` | `helper_modules.lua`, `representative_layout.lua`, `representative_layout.aly` |
| `src/jvmTest/kotlin/semantic/workspace/BuiltinOverlayLoaderTest.kt` | Overlay catalog / precedence regression (must stay green with TASK-184) |
| `androidlua/androlua5.3/` resource tree + `BuiltinOverlayLoader.kt` | Declaration + catalog source of truth |
| `ExpressionTypeEvaluator.kt` load* / global paths | Call shells, ids tables, `declaredType` for ClassType globals |

### Host `android.jar` dual-path policy (never `G:/`)

Reflective Android framework extras for library-model analysis on **this** host
must use only real host files. Never hard-code Windows `G:/` (or any other
non-host) paths in product code, docs examples, or new tests.

| Priority | Path / source | Role | Host status (2026-07-11 WAVE36E re-check) |
| --- | --- | --- | --- |
| 1 | Explicit metadata `jvm.androidJar` | Caller-supplied single platform jar | Set to the SDK path below when configuring analysis |
| 2 | `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar` | Android SDK Platform 35 jar | **Present** (~27,092,450 bytes) |
| 3 (optional copy) | `/Users/dingyi/Downloads/android.jar` | Operator drop-in copy of a platform jar | **Absent** on this host; allowed when present |

Preferred SDK path block:

```text
/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar
```

Optional Downloads path block (use only when the file exists; do not invent
classpath entries for a missing file):

```text
/Users/dingyi/Downloads/android.jar
```

Resolution guidance for library-model / interop rows:

1. If the harness already receives `jvm.androidJar`, keep that value (must still
   be one of the two host paths above, or another operator-chosen real jar on
   the machine under test — never `G:/`).
2. Else prefer the SDK android-35 path when the file exists.
3. Else use `/Users/dingyi/Downloads/android.jar` only when that file exists.
4. If neither host path exists, keep handwritten overlay + static
   `android-framework` models; skip or soft-path reflective-only Android
   framework assertions. Do **not** invent class members.

Suggested metadata snippet (host dual-path; prefer SDK android-35):

```kotlin
val metadata = mapOf(
    "jvm.androidJar" to "/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar",
    "jvm.importPrefixes" to listOf(
        "java.lang",
        "java.util",
        "java.io",
        "android.app",
        "android.content",
        "android.view",
        "android.widget",
        "com.androlua"
    ).joinToString("\n"),
)
```

### Wiring that made library stubs usable (TASK-184 clusters)

These product rules are part of the library-model contract after TASK-184.
They sit beside the declaration resources; they are not a rewrite of the
resource tree.

| Surface | Product rule | Why it matters for this inventory |
| --- | --- | --- |
| Builtin GLOBAL visibility | `BuiltinOverlayLoader.documentedGlobalMembers` emits `range = null` for overlay globals | Overlay `_G` / catalog globals (`activity`, `service`, `this`, `context`, `loadlayout`, …) stay visible at user-file positions; analyzed overlay ranges must not hide them via `isVisibleAt`. |
| ClassType global bases | `ExpressionTypeEvaluator.deriveGlobalDeclarationValueType` preserves non-callable `declaredType` (`ClassType` / `UnionType` / …) | First hop on `activity.getLuaDir` / `service.sendMsg` is not forced to `unknown` when the declaration is a ClassType without a function body. |
| load* call leaves | `resolveLoadlayoutFamilyCall` returns **shell** `JavaInstanceType` surfaces (`View` / Bitmap / Menu-like) without deep-hydrating full member graphs | Avoids OOM from rewriting every reflective method during the call; further hops still use `MemberResolver` on demand. |
| Layout ids table | `loadlayoutIdsTableType` → `ModuleType(moduleName=LuaLayoutIds, fields=…)` with identity visited-set / node budgets | `ids.title` / `ids.title.setText` multi-hop stays OOM-safe; hover displayName can match `LuaLayoutIds`. |
| Callable leaf text | Product `FunctionType.displayName` is `fun…` | Goldens and docs describe method/function leaves as `fun…`, not the English word `function` (TASK-184 cluster A). |
| Dialog helper | `helpers/Dialog.lua` + catalog: colon method `MyBottomSheetDialog` as METHOD; no competing FIELD overwrite | Reserved simple-name helper surface used by library-stub corpus. |
| `bmob` / `bin` | Managed `modules/` catalog **wins** over asset `helpers/` duplicates | TASK-168 / TASK-184 non-regression: `require "bmob"` / `require "bin"` stay managed runtime declarations. |
| Free-id completions | After `require "import"`, completions surface binder builtin globals (`activity`, `service`, `loadlayout`, …) | Overlay catalog is not enough if query surfaces ignore visible GLOBAL decls. |

### Modeled roots vs fixtures (post-184 honesty)

| Kind | Examples | Expected analysis shape | Dual-path note |
| --- | --- | --- | --- |
| Context globals | `activity`, `service`, `this`, `context` | `ClassType(LuaActivity)` / `LuaService` / context-like declared types; inherited `AndroidLuaContext` methods | Prefer concrete ClassType + `fun…` method leaves in semantic harness |
| load* globals/modules | `loadlayout`, `loadbitmap`, `loadmenu` | Callable globals + require providers; call leaves are shell Java instances | Member hops may soft-degrade without reflective jar |
| Ids after `loadlayout(layout, ids)` | `ids.<id>` | `ModuleType(LuaLayoutIds)` fields → view-like, then normal hops | Collection is bounded; missing id → unknown, not invented |
| `.aly` / layout specs | library-fixtures `representative_layout.aly` | Prefer `CustomType("LuaLayoutSpec")` / layout-table shape when provider ends with `.aly` | Partial provider/export wiring may still use dual-path CURRENTLY_ACCEPTS in corpus |
| Widget imports | `import "android.widget.TextView"` | Class value + optional `__jvm__/classes/...` goto when static/reflective providers exist | Empty goto may still be dual-path soft when graph export is partial |
| Helper modules | `json`, `base64`, `socket.url`, `http`, `files` (fixtures), `Dialog` | Named encode/decode/parse/get/exists / Dialog methods | Fixture needles must hit modeled symbols, not string-literal substrings |
| Reflective Android FQCN | `android.view.View`, listeners, enums | Prefer `android.jar` when configured; else curated static models | Missing jar ⇒ no invented members |

### Dual-path honesty for incomplete product paths

Where product behavior may still degrade (incomplete CustomType expansion,
partial reflective members without `android.jar`, incomplete `.aly` provider
edges, widget goto gaps), documentation and dual-path corpora may accept soft
CURRENTLY_ACCEPTS / gap paths **without** inventing types:

| Path | Prefer | Soft dual |
| --- | --- | --- |
| Semantic harness + Android-Lua overlay | Concrete ClassType / ModuleType / shell FQCN leaves | — |
| Missing intermediate / missing member | `UnknownType` | — |
| Load* without host jar | Shell FQCN call leaf still present | Further reflective member hop may be unknown |
| `.aly` require / layoutSpec partial wiring | `LuaLayoutSpec` when product fills in | Dual-path CURRENTLY_ACCEPTS only while edges remain partial |
| Widget import definition goto | `__jvm__/classes/android/widget/{TextView,ImageView}.lua` | Empty goto dual-path while graph export is partial |
| LSP bare-global CustomType expansion | Non-empty when expansion works | Empty / gap documented; local Emmy stubs remain a supported shape |

Dual acceptance is **not** license to claim suite green without TASK-043, and
is not license to invent string-ish leaves for missing hops.

### Related deferred verification

Serialized review-owned commands (workers must not run them):

```text
JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home \
  ./gradlew.lf jvmTest --tests semantic.androidlua.AndroidLuaLibraryStubsTddTest

JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home \
  ./gradlew.lf jvmTest --tests semantic.workspace.BuiltinOverlayLoaderTest
```

(Historical Windows JAVA_HOME forms in older task notes are not host paths for
this macOS worker wave.)

## Limitations

- Android-Lua class resolution is intentionally dynamic. Dex files, jar files, and native libraries can be loaded at runtime, so static analysis can only model known project, SDK, JDK, and bundled classes.
- Wildcard imports are lazy prefixes. They should not create a complete symbol list unless a class index is available.
- `assets/android.lua` and `assets/javaapi/android.lua` may be stale relative to the SDK used by this repository. Prefer `android.jar` and classpath reflection for platform APIs, using host dual-path paths only (Downloads + SDK android-35; never `G:/`).
- `loadlayout` performs runtime reflection, resource lookup, unit conversion, listener proxy creation, id binding, and table traversal. Stubs should model the useful shape, not exact runtime behavior. Call typing uses OOM-safe shell returns plus bounded ids-table collection after TASK-184/TASK-379.
- LuaJava overload resolution depends on runtime Java reflection and Lua stack values. Static call checking should be permissive for Java calls until a robust overload model exists.
- Native modules such as `socket`, `mime`, `cjson`, `xml`, `yaml`, `md5`, `regex`, `zlib`, and related JNI/C modules are not fully represented by Lua source. Model the Lua-facing module APIs only.
- App helper modules in `assets` include project-specific UI text, non-ASCII function names, and editor-specific globals. They should be typed only where they improve real Android-Lua project analysis.
- The manifest/source mismatch noted in `docs/android-lua-architecture.md` around `Welcome` remains unresolved and should not affect helper-library modeling.
- TASK-184 acceptance unblocks product-surface documentation; it does **not** substitute for serialized verification (TASK-043) or final acceptance (TASK-037).

## Licensing and provenance notes

- `/Users/dingyi/projects/java_projects/Android-Lua/LICENSE.txt` is MIT-style and identifies Androlua 1.0 copyright by Michal Kottman and Androlua+ 3.0 copyright by Nirenr.
- `/Users/dingyi/projects/java_projects/Android-Lua/luajava-license.txt` is MIT-style for the Kepler Project LuaJava code.
- `/Users/dingyi/projects/java_projects/Android-Lua/lua-license.txt` is MIT-style for Lua.
- The existing Lua standard stubs in this repository include their own Apache-2.0 notice from their source. Android-Lua stubs should not mix copied text from external source unless the copied scope and license notices are explicit.
- If TASK-033 copies any Android-Lua Lua source instead of handwritten stubs, each copied file should retain a provenance header containing the external repository URL, commit `686a792dbdd2fe9727a34768ceadffcaa2abc20d`, original path, observed license file, and copy date.
- Preferred TASK-033 approach: handwritten declarations and generated metadata catalogs with provenance comments, not copied implementation files.

## Acceptance mapping (TASK-452)

| Acceptance criterion | Covered by |
| --- | --- |
| Scoped deliverable for goal completeness after TASK-184 (docs refresh) | “Post-TASK-184 product surfaces (library models)” + updated reference links / limitations |
| Non-overlapping file scope | This file only under worker scope (`docs/android-lua-library-models.md`) plus task metadata |
| Host android.jar paths only (Downloads + SDK android-35); never G:/ | Dual-path policy table + resolution guidance in this page |
| Docs-only; no Gradle | Explicit deferral of AndroidLuaLibraryStubsTddTest / BuiltinOverlayLoaderTest to TASK-043 |
