# Android-Lua Corpus Manifest

Prepared by TASK-054 on 2026-06-08.
Host dual-path external root policy refreshed by TASK-562 on 2026-07-12.
Joint green-lock (root dual-path + android-35 jar metadata) coordinated by TASK-605 on 2026-07-12.

External source root, read-only (do not vendor Android-Lua sources into this repo):

```text
/Users/dingyi/projects/java_projects/Android-Lua/app/src/main
```

Host dual-path default resolution used by
`AndroidLuaCorpusSemanticVerificationTest` (TASK-562):

1. system property `androidLua.main` (non-blank)
2. environment `ANDROID_LUA_MAIN` (non-blank; empty env is treated as unset)
3. host dual-path candidates, first existing directory wins:
   - preferred macOS clone: `/Users/dingyi/projects/java_projects/Android-Lua/app/src/main`
   - documented Windows last-resort only: `G:/Android-Lua/app/src/main`
     (never a sole hard default that fails macOS solely because `G:` is missing)

Missing root fails with a message that lists tried paths and the override knobs above.
External tree remains read-only.

Host android.jar dual-path (TASK-605 joint green-lock with root dual-path):

1. explicit workspace metadata `jvm.androidJar` when set
2. `ANDROID_HOME` / `ANDROID_SDK_ROOT` platforms/android-*/android.jar (highest API)
3. well-known host SDK roots (macOS `~/Library/Android/sdk` preferred; Windows `G:/Android/Sdk` last-resort only)
4. preferred WAVE path when present: `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar`

Never hard-requires `G:/`. Downloads jars are metadata-only (never auto-selected).

Observed external repository:

```text
Repository: /Users/dingyi/projects/java_projects/Android-Lua
Remote: https://github.com/TheMostBlack/Android-Lua.git
Commit: 686a792dbdd2fe9727a34768ceadffcaa2abc20d
Status at inspection: clean
```

No Android-Lua source text is copied into this manifest. Rows identify the upstream files that TASK-035 should load from the read-only external checkout during integration verification.

## Manifest Rules

- The corpus is bounded. Use the rows below as the integration fixture set unless a later review task expands this manifest.
- `lua` and `aly` rows are parse and semantic inputs. `.aly` files are Lua chunks that return Android-Lua layout tables.
- `java-anchor` rows are not Lua parse inputs. They document Java/LuaJava/runtime classes that semantic and LSP verification should rely on through existing JVM/Android metadata paths.
- `deferred` notes are expected limits for TASK-035/TASK-043. They should not become copied source fixtures unless a later task explicitly changes scope and provenance handling.

## Tags

| Tag | Meaning |
| --- | --- |
| `parser.strict` | Should parse as accepted Lua/Android-Lua syntax without recovery. |
| `parser.recovery` | Contains known nonstandard Android-Lua syntax that should exercise recovery without crashing. |
| `semantic.import` | Uses `require "import"` or Android-Lua `import` side effects. |
| `semantic.require` | Exercises Lua module resolution. |
| `semantic.module` | Exposes or consumes a module-like API. |
| `semantic.layout` | Builds or consumes Android-Lua layout tables. |
| `semantic.activity` | Uses the `activity` Android-Lua global. |
| `semantic.service` | Uses `service` or activity/service shared context behavior. |
| `interop.luajava` | Uses `luajava` directly or Java class values returned by LuaJava. |
| `interop.android` | Uses Android framework classes or bundled Android-like widget classes. |
| `interop.java` | Uses JDK classes such as `java.io`, `java.util`, or `java.util.zip`. |
| `helper.stub` | Covered by TASK-044 Android-Lua helper declaration resources. |
| `lsp.hover` | Good candidate for hover/type queries. |
| `lsp.completion` | Good candidate for completion queries. |
| `lsp.definition` | Good candidate for go-to-definition or declaration queries. |
| `lsp.diagnostics` | Good candidate for diagnostics assertions. |

## Primary Corpus

| ID | Source path under `/Users/dingyi/projects/java_projects/Android-Lua/app/src/main` | Kind | Size bytes | Tags | Verification focus | Known unsupported or deferred patterns |
| --- | --- | --- | ---: | --- | --- | --- |
| `runtime-import` | `resources/lua/import.lua` | lua | 13769 | `parser.strict`, `semantic.import`, `semantic.require`, `interop.luajava`, `helper.stub`, `lsp.completion`, `lsp.definition` | Import side effects, default package prefixes, wildcard imports, dex-prefixed imports, helper globals, lazy `_G`/`luajava` metatables. | Runtime metatable mutation and dex class loading should be modeled as facts/candidates, not executed. |
| `runtime-loadlayout` | `resources/lua/loadlayout.lua` | lua | 17628 | `parser.strict`, `semantic.layout`, `semantic.activity`, `semantic.service`, `interop.luajava`, `interop.android`, `helper.stub`, `lsp.hover` | Recursive layout traversal, class binding, listener proxy shapes, id tables, Android dimension/resource strings. | Do not attempt exact Android resource lookup, reflection overload resolution, or view construction during tests. |
| `runtime-loadbitmap` | `resources/lua/loadbitmap.lua` | lua | 529 | `parser.strict`, `semantic.module`, `semantic.activity`, `helper.stub` | Small helper returning bitmap/drawable-like Android values from URL/local paths. | Network and filesystem effects remain unexecuted. |
| `runtime-loadmenu` | `resources/lua/loadmenu.lua` | lua | 1222 | `parser.strict`, `semantic.layout`, `interop.android`, `helper.stub` | Table-to-menu helper and callback field shape. | Android menu object mutation is not executed. |
| `runtime-autotheme` | `resources/lua/autotheme.lua` | lua | 491 | `parser.strict`, `interop.luajava`, `interop.android`, `helper.stub`, `lsp.hover` | Compact chained Java access: `luajava.bindClass("android.os.Build").VERSION.SDK_INT`. | Exact SDK branch result is not asserted. |
| `runtime-bin` | `resources/lua/bin.lua` | lua | 13689 | `parser.strict`, `semantic.import`, `semantic.activity`, `interop.java`, `interop.android`, `helper.stub`, `lsp.diagnostics` | APK packaging helper imports, nested locals, zip/signing APIs, activity task callbacks. | File mutation, signing, APK install, and zip output are documentation-only. |
| `runtime-http` | `resources/lua/http.lua` | lua | 16303 | `parser.strict`, `semantic.module`, `semantic.require`, `helper.stub`, `lsp.hover` | LuaSocket-style request surfaces and module return behavior. | Network I/O is not executed. |
| `runtime-socket-url` | `resources/lua/socket/url.lua` | lua | 11335 | `parser.strict`, `semantic.module`, `semantic.require`, `helper.stub` | Dotted module resolution for `socket.url` and URL table helpers. | Native socket behavior is outside static verification. |
| `runtime-json` | `resources/lua/json.lua` | lua | 15751 | `parser.strict`, `semantic.module`, `semantic.require`, `helper.stub` | Data helper module surface, encode/decode functions, table recursion. | Runtime JSON compatibility edge cases are not exhaustively tested. |
| `runtime-xml` | `resources/lua/xml.lua` | lua | 3833 | `parser.strict`, `semantic.module`, `helper.stub` | XML helper module and legacy commented `module("xml")` pattern. | XML native parser behavior is not executed. |
| `runtime-permission` | `resources/lua/permission.lua` | lua | 1352 | `parser.strict`, `semantic.import`, `semantic.activity`, `interop.android`, `helper.stub` | Android package manager access and permission table exports. | Real package permissions are not queried. |
| `runtime-logcat` | `resources/lua/logcat.lua` | lua | 1849 | `parser.strict`, `semantic.import`, `semantic.activity`, `interop.android`, `lsp.completion` | Activity UI setup and options menu callback declarations. | Logcat process/runtime output is not executed. |
| `asset-main` | `assets/main.lua` | lua | 66298 | `parser.strict`, `semantic.import`, `semantic.activity`, `semantic.layout`, `interop.android`, `lsp.diagnostics`, `lsp.completion` | Large application shell with imports, project list logic, nested callbacks, `dofile`, and Android object calls. | UI side effects, APK build paths, and filesystem mutation are skipped. |
| `asset-main2-editor` | `assets/main2.lua` | lua | 86805 | `parser.strict`, `semantic.import`, `semantic.activity`, `semantic.layout`, `interop.android`, `lsp.hover`, `lsp.definition` | Editor/file-manager screen, deeply nested callbacks, lifecycle-style functions, shortcut handling. | Runtime editor widgets are not instantiated. |
| `asset-main13-recovery` | `assets/main13.lua` | lua | 50466 | `parser.recovery`, `semantic.import`, `semantic.activity`, `lsp.diagnostics` | Nonstandard `switch`/`case` syntax recovery in otherwise real Android-Lua app code. | Recovery diagnostics are expected; this row should not be a strict parse pass. |
| `asset-bin` | `assets/bin.lua` | lua | 13693 | `parser.strict`, `semantic.import`, `semantic.activity`, `interop.java`, `interop.android`, `helper.stub` | App-level packaging helper duplicate with imports, zip/signing APIs, task callback flow. | Same side-effect exclusions as `runtime-bin`. |
| `asset-andlua` | `assets/AndLua.lua` | lua | 13231 | `parser.strict`, `semantic.import`, `semantic.activity`, `semantic.layout`, `interop.android`, `helper.stub`, `lsp.hover` | Common helper functions, UI helpers, `loadlayout`, dialog/button helpers, wildcard imports. | Non-ASCII helper names and app-specific globals should be permissive, not exhaustive. |
| `asset-thomelua` | `assets/ThomeLua.lua` | lua | 15023 | `parser.recovery`, `semantic.import`, `semantic.activity`, `interop.android`, `helper.stub`, `lsp.diagnostics` | Helper library plus known nonstandard `switch`/`case` snippet around status bar handling. | Recovery syntax is expected; do not require complete semantic facts in recovered blocks. |
| `asset-yidian` | `assets/yidian.lua` | lua | 29220 | `parser.strict`, `semantic.import`, `semantic.activity`, `semantic.layout`, `interop.android`, `helper.stub` | Alternate helper library with dialog/toast/layout patterns and many Android imports. | Non-ASCII helper names and UI side effects are deferred. |
| `asset-dialog` | `assets/Dialog.lua` | lua | 8993 | `parser.strict`, `semantic.import`, `semantic.layout`, `interop.android`, `helper.stub`, `lsp.definition` | Fluent dialog helper table, assigned functions, callback fields, chained Android calls. | Dialog window creation is not executed. |
| `asset-file` | `assets/file.lua` | lua | 18280 | `parser.strict`, `semantic.import`, `semantic.activity`, `semantic.layout`, `interop.java`, `interop.android` | File browser screen, vararg entry parameters, file APIs, table layouts. | Filesystem reads/writes are not executed. |
| `asset-toast` | `assets/toast.lua` | lua | 771 | `parser.strict`, `semantic.layout`, `semantic.activity`, `interop.android`, `helper.stub` | Small toast helper with nested layout table and Android gravity/class access. | Toast display is not executed. |
| `asset-xml2table` | `assets/xml2table.lua` | lua | 3010 | `parser.strict`, `semantic.import`, `semantic.require`, `semantic.layout`, `helper.stub` | XML-to-layout helper, `loadlayout3` import, clipboard context, string transforms. | Clipboard and editor UI effects are not executed. |
| `layouthelper-main` | `assets/layouthelper/main.lua` | lua | 28498 | `parser.strict`, `semantic.import`, `semantic.layout`, `semantic.activity`, `lsp.diagnostics` | Layout-helper app flow, `.aly` loading with `loadstring`, package path mutation. | Dynamic loading should be represented as deferred/unknown, not executed. |
| `layouthelper-loadlayout2` | `assets/layouthelper/loadlayout2.lua` | lua | 17957 | `parser.strict`, `semantic.layout`, `semantic.activity`, `semantic.service`, `interop.luajava`, `helper.stub` | Alternate layout loader and class binding behavior. | Exact Android view creation is deferred. |
| `layouthelper-loadlayout3` | `assets/layouthelper/loadlayout3.lua` | lua | 14748 | `parser.strict`, `semantic.layout`, `semantic.activity`, `semantic.service`, `interop.luajava`, `helper.stub` | Alternate compact layout loader and dimension/id handling. | Exact Android view creation is deferred. |
| `javaapi-fiximport` | `assets/javaapi/fiximport.lua` | lua | 3332 | `parser.strict`, `semantic.import`, `semantic.require`, `interop.android`, `lsp.completion` | Import-normalization helper, class list consumption, source scanning loop. | It is not the runtime importer; do not use it as `import.lua` semantics. |
| `plugin-main` | `assets/plugin/main.lua` | lua | 7815 | `parser.strict`, `semantic.import`, `semantic.activity`, `semantic.layout`, `interop.java`, `interop.android`, `lsp.definition` | Plugin manager screen, `loadfile(..., "bt", env)`, table metadata, callbacks. | Plugin project discovery and filesystem calls are not executed. |

## Layout Corpus

| ID | Source path under `/Users/dingyi/projects/java_projects/Android-Lua/app/src/main` | Kind | Size bytes | Tags | Verification focus | Known unsupported or deferred patterns |
| --- | --- | --- | ---: | --- | --- | --- |
| `layout-root` | `assets/layout.aly` | aly | 5123 | `parser.strict`, `semantic.layout`, `interop.android`, `lsp.hover` | Large root layout table with nested views, ids, dimensions, and resource-like values. | Runtime id binding is asserted as shape only. |
| `layout-my` | `assets/My.aly` | aly | 23968 | `parser.strict`, `semantic.layout`, `interop.android`, `lsp.completion` | Large profile/settings layout table with many nested Android widget entries. | Exact widget class existence depends on metadata indexes. |
| `layout-project` | `assets/project.aly` | aly | 1505 | `parser.strict`, `semantic.layout`, `interop.android` | Compact project screen layout with alignment keys and button id. | No Android rendering. |
| `layout-projectitem` | `assets/projectitem.aly` | aly | 2617 | `parser.strict`, `semantic.layout`, `interop.android` | Repeated list item/card layout, id exports, table children. | No Android rendering. |
| `layout-community` | `assets/Community.aly` | aly | 291 | `parser.strict`, `semantic.layout`, `interop.android` | Small `PageView` layout and table-valued `pages` property. | Referenced globals such as `layout2` may remain external. |
| `layout-javaapi-clayout` | `assets/javaapi/clayout.aly` | aly | 350 | `parser.strict`, `semantic.layout`, `interop.android`, `lsp.completion` | Java API class-list search layout with `EditText` and `ListView` ids. | `classes` global is supplied by companion Lua, not this file. |
| `layout-javaapi-mlayout` | `assets/javaapi/mlayout.aly` | aly | 332 | `parser.strict`, `semantic.layout`, `interop.android` | Java API member-list search layout. | Companion globals are external. |
| `layout-plugin-item` | `assets/plugin/item.aly` | aly | 562 | `parser.strict`, `semantic.layout`, `interop.android` | Plugin list-item layout with `ImageView`/`TextView` ids. | No Android rendering. |
| `layout-plugin-root` | `assets/plugin/layout.aly` | aly | 142 | `parser.strict`, `semantic.layout`, `interop.android` | Minimal plugin list layout. | No Android rendering. |

## Java Interop Anchors

These rows explain which Java files motivated the interop assertions. TASK-035 should use existing JVM/Android metadata configuration instead of parsing Java source as Lua.

| ID | Source path under `/Users/dingyi/projects/java_projects/Android-Lua/app/src/main` | Kind | Tags | Verification focus | Known unsupported or deferred patterns |
| --- | --- | --- | --- | --- | --- |
| `java-luajava-api` | `java/com/luajava/LuaJavaAPI.java` | java-anchor | `interop.luajava`, `interop.java`, `lsp.hover`, `lsp.completion` | Method/property lookup, class binding, constructors, Java field access, listener setter proxy behavior. | Exact overload resolution remains permissive until a robust Java call checker exists. |
| `java-lua-state` | `java/com/luajava/LuaState.java` | java-anchor | `interop.luajava` | `openLibs`, `setGlobal`, `LloadFile`, `LloadBuffer`, LuaJava library loading. | Native calls are not executed. |
| `java-lua-activity` | `java/com/androlua/LuaActivity.java` | java-anchor | `semantic.activity`, `interop.android`, `lsp.hover` | `activity` global shape, `doFile`, `doAsset`, path helpers, lifecycle callback dispatch. | Android lifecycle is not executed. |
| `java-lua-service` | `java/com/androlua/LuaService.java` | java-anchor | `semantic.service`, `interop.android` | `service` global shape and service-side Lua state setup. | Android service lifecycle is not executed. |
| `java-asset-loader` | `java/com/androlua/LuaAssetLoader.java` | java-anchor | `semantic.require`, `helper.stub` | Asset-backed `require` loader behavior for Lua chunks. | Asset extraction and classloader mutation are not executed. |
| `java-dex-loader` | `java/com/androlua/LuaDexLoader.java` | java-anchor | `interop.luajava`, `interop.java` | Dex/jar/native library maps that feed `import.lua` and package searchers. | Dynamic dex/native loading is deferred. |
| `java-lua-adapter` | `java/com/androlua/LuaAdapter.java` | java-anchor | `semantic.layout`, `interop.android` | Adapter values used by `loadlayout` and list/table UI helpers. | Adapter rendering is not executed. |
| `java-card-view` | `java/android/widget/CardView.java` | java-anchor | `interop.android`, `lsp.completion` | Bundled widget class used frequently by layout tables. | Prefer class-index/reflection metadata over source parsing. |

## Minimum TASK-035 Assertions

TASK-035 should use this manifest to create integration coverage at these levels:

- Parse every `parser.strict` `lua` and `aly` row without parser recovery errors.
- Parse every `parser.recovery` row without crashing and assert at least one recovery diagnostic or recovered syntax marker.
- Analyze semantic rows with Android-Lua 5.3 overlays enabled and `jvm.androidJar` configured when available.
- Assert that `semantic.import` rows record import facts, default package prefixes, explicit class imports, and wildcard package prefixes.
- Assert that `semantic.layout` rows produce layout table facts or at least stable table/member diagnostics rather than unknown-source crashes.
- Assert that `interop.luajava`, `interop.java`, and `interop.android` rows resolve representative Java class candidates such as `java.io.File`, `java.util.ArrayList`, `android.widget.TextView`, `android.view.View$OnClickListener`, and bundled `android.widget.CardView`.
- Assert LSP scenarios over a small subset of marked rows: hover on imported class values, completion after imported prefixes, definition/declaration for local functions, and diagnostics for recovery rows.

## Exclusions

- Do not copy upstream Android-Lua files into this repository for TASK-035 unless a later task explicitly expands scope and provenance requirements.
- Do not include images, fonts, signing keys, `.odex`, `.vdex`, `.oat`, native C/JNI implementations, or Android resource binaries in this corpus.
- Do not execute Android UI, file mutation, network, APK packaging, signing, native library loading, dex loading, or `loadstring`/`loadfile` side effects during verification.
