# Android-Lua app/src/main Architecture

Study date: 2026-06-06  
Docs refresh: TASK-504 (WAVE36F), 2026-07-12 — post-TASK-184 / pre-TASK-043 honesty, macOS host paths, Corretto 17, android-35 dual-path jar policy. Docs-only; no Gradle/tests/compile.

**Honesty bound (post-TASK-184, pre-TASK-043):** this page is a read-only architecture inventory of the external Android-Lua tree and how this repository uses it as fixture/source material. TASK-184 library-stub / overlay surfaces are accepted in task metadata. This document does **not** claim final green acceptance, does **not** unlock **TASK-043** (serialized verification, still `blocked`), and does **not** unlock **TASK-037** (final acceptance, still `blocked`). Inventory counts and host path status here are operator notes for analysis setup — **inventory is not final until TASK-043**.

External source studied read-only on this macOS host:

```text
/Users/dingyi/projects/java_projects/Android-Lua/app/src/main
```

Verified repository (2026-07-12 re-check):

- `/Users/dingyi/projects/java_projects/Android-Lua` is **present** on this host.
- `origin` is `https://github.com/TheMostBlack/Android-Lua.git` for fetch and push.
- Observed commit: `686a792dbdd2fe9727a34768ceadffcaa2abc20d`.

No files were copied from the external checkout into this repository for the original architecture study (TASK-002) or for this docs refresh (TASK-504).

## Host toolchain and `android.jar` dual-path policy

Analysis and verification notes on this macOS host must use **only** these candidates. Never hard-code Windows `G:/` (or any other non-host) paths.

| Priority | Path | Role | Host status (2026-07-12 TASK-504 re-check) |
| --- | --- | --- | --- |
| 1 (preferred for reproducible analysis) | Explicit metadata `jvm.androidJar` | Caller-supplied single platform jar | Set to the SDK path below when configuring analysis/tests |
| 2 | `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar` | Android SDK Platform 35 jar (`ANDROID_HOME` / `ANDROID_SDK_ROOT` typically `/Users/dingyi/Library/Android/sdk`) | **Present** (~27,092,450 bytes) |
| 3 (optional copy) | `/Users/dingyi/Downloads/android.jar` | Operator drop-in copy of a platform jar | **Absent** on this host; allowed when present as an **explicit** metadata override only |

Preferred SDK path block:

```text
/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar
```

Optional Downloads path block (use only when the file exists; do not invent classpath entries for a missing file):

```text
/Users/dingyi/Downloads/android.jar
```

JDK for product/test reflection on this host:

```text
Amazon Corretto 17
/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home
```

Note: the shell default `java` on this host may resolve to a newer OpenJDK (for example OpenJDK 26). Prefer Corretto 17 for Gradle/JVM-test alignment with the project toolchain. Historical Windows notes that used Temurin 17 `jar.exe` are not host defaults here.

Companion docs for classpath discovery and skip behavior: `docs/android-platform-setup.md`, `docs/jvm-reflection-classloader-design.md`, `docs/android-lua-verification.md`.

## Source Layout

`app/src/main` is a hybrid Android/Lua runtime tree:

- `AndroidManifest.xml`: declares package `com.androlua`, `versionCode="4402"`, and `versionName="4.4.2"`.
- `java/`: Java runtime, Lua bridge, editor widgets, dex/proxy support, Trime-derived app code, and helper utilities. The snapshot contains 493 `.java` files.
- `assets/`: app-facing Lua scripts, `.aly` layout files, images, fonts, signing keys, precompiled oat/odex/vdex artifacts, plugin files, and helper subapps. The snapshot contains 42 `.lua` and 10 `.aly` files under `assets`.
- `resources/lua/`: 28 Lua support modules that are packaged as Java resources at `lua/...`; `Welcome.java` extracts `lua` entries into `LuaApplication.getLuaMdDir()`.
- `jni/`: native Lua interpreter and C modules, built through `externalNativeBuild.ndkBuild` from `src/main/jni/android.mk`.
- `res/`: normal Android resources: icon and welcome drawables, values, accessibility-service XML, and FileProvider paths.

The overall file-type inventory for `app/src/main` (host re-check 2026-07-12, still architecture-study scale; **not final acceptance inventory**): 493 `.java`, 408 `.c`, 176 `.h`, 128 `.png`, 71 `.lua`, 18 `.mk`, 15 `.XY`, 13 `.odex`, 13 `.vdex`, 10 `.aly`, and 5 `.xml` files. Do not treat these counts as TASK-043 / suite inventory; suite inventory remains frozen/review-owned (see TASK-125 / `docs/test-strategy.md`).

## Java Packages

The largest Java areas are:

- `com.androlua`: core AndroLua runtime classes. Important files include `LuaApplication`, `LuaActivity`, `LuaActivityX`, `LuaService`, `LuaAccessibilityService`, `LuaAssetLoader`, `LuaDexLoader`, `LuaResources`, UI adapters, bitmap helpers, networking helpers, timers, threads, and `Main`.
- `com.luajava`: Java side of LuaJava. `LuaState` exposes native Lua APIs, `LuaStateFactory` creates and tracks states, and `LuaJavaAPI` implements Java reflection, object indexing, method calls, class binding, proxy creation, array creation, and Java library loading.
- `com.osfans.trime`: Trime-derived IME/configuration code. This includes `Welcome`, `Pref`, `Trime`, keyboard/config managers, dialogs, cloud candidates, backup/download/log screens, and related utilities.
- `com.myopicmobile.textwarrior`: editor/text-buffer components used by the Lua editor.
- `com.android.cglib.dx` and `com.android.cglib.proxy`: bundled dex generation and proxy/enhancement support.
- `com.nirenr` and `com.nirenr.screencapture`: color/search utilities and screen-capture helpers.
- Copied support/runtime namespaces such as `android.support.multidex`, `android.support.v4.app`, `android.widget`, `android.app`, and `android.content`.

The manifest launcher names `com.androlua.Welcome`, but the only `Welcome.java` found in this snapshot is `app/src/main/java/com/osfans.trime/Welcome.java` with package `com.osfans.trime`. Treat that as a source-snapshot mismatch to resolve before relying on launcher behavior in runtime tests.

## Lua Assets

There are two different Lua-bearing areas:

- `resources/lua`: runtime support modules. Notable files are `import.lua`, `loadlayout.lua`, `loadbitmap.lua`, `loadmenu.lua`, `autotheme.lua`, `permission.lua`, `console.lua`, `http.lua`, `socket.lua`, `socket/*.lua`, `json.lua`, `xml.lua`, `base64.lua`, `bin.lua`, `bmob.lua`, and LuaSocket-style support modules.
- `assets`: application scripts and layouts. The root includes large app scripts such as `main.lua`, `main2.lua`, `main13.lua`, `android.lua`, `AndLua.lua`, `ThomeLua.lua`, `yidian.lua`, `file.lua`, `Dialog.lua`, `bin.lua`, `bmob.lua`, `color*.lua`, `loadlayout2.lua`, and `loadlayout3.lua`. It also contains `.aly` table-layout files such as `layout.aly`, `My.aly`, `project.aly`, `projectitem.aly`, and `Community.aly`.

Important subdirectories under `assets`:

- `javaapi/`: large Java API reference/import helper scripts and small `.aly` layouts.
- `layouthelper/`: helper app scripts for converting or authoring layouts.
- `plugin/`: plugin UI scripts and layouts.
- `font/`, `image/`, `img/`, `imgs/`, `picture/`, `res*`, and `Verify/`: UI media, fonts, and small data files.
- `keys/`, `libs/`, and `oat/`: bundled signing material and precompiled artifacts.

The app scripts make heavy use of AndroLua-specific `import "..."`, `require(...)`, globals such as `activity`/`service`, table-based Android layouts, event-handler assignment (`view.onClick = function(...) ... end`), callbacks nested in tables, and Java class/member access.

## Native/JNI Pieces

`app/build.gradle` uses:

- `compileSdkVersion 28`
- `minSdkVersion 18`
- `targetSdkVersion 23`
- `ndk.abiFilters 'armeabi-v7a'`
- `externalNativeBuild.ndkBuild.path 'src/main/jni/android.mk'`

`src/main/jni/android.mk` sets `APP_ABI = armeabi-v7a` and includes all subdirectory makefiles. Native directories present include `bson`, `canvas`, `cjson`, `crypt`, `lfs`, `lluv`, `lsqlite3`, `lua`, `luagl`, `luajava`, `luayaml`, `luv`, `md5`, `regex`, `sensor`, `socket`, `tcc`, `xml`, `zip`, and `zlib`.

Representative built modules declared by subdirectory `Android.mk` files include:

- `lua`: Lua interpreter/runtime C sources.
- `luajava`: JNI bridge implementing `luaopen_luajava` and native methods for `com.luajava.LuaState`.
- `socket` and `mime`: LuaSocket-style native modules.
- `cjson`, `bson`, `xml`, `yaml`, `md5`, `regex`, `zlib`, `libzip`, `crypt`, `canvas`, `gl`, `luv`, and `sensor`.

`LuaState` loads the native LuaJava library with `System.loadLibrary(...)`. `LuaState.openLibs()` opens standard Lua libraries and then `_openLuajava`, which registers the `luajava` module and exposes functions such as `bindClass`, `newInstance`, `loadLib`, and `createProxy`.

## Bundled Android Resources

The Android `res` tree is small:

- `res/drawable/icon.png` and `res/drawable/welcome.png`.
- `res/values/strings.xml` with the accessibility-service description.
- `res/values/styles.xml` defining `app_theme` as a Holo light no-action-bar theme with `@drawable/welcome` background.
- `res/xml/accessibility_service_config.xml`.
- `res/xml/androlua_filepaths.xml` for the manifest `FileProvider`.

The broad runtime payload is instead in `assets` and `resources/lua`.

## Runtime Entry Points

Manifest-declared Android entry points:

- `application`: `com.androlua.LuaApplication`.
- Activity `com.androlua.Main`: handles `.alp` file/content intents and extends `LuaActivity`.
- Activity `com.androlua.LuaActivity`: handles `androlua://com.androlua` and `.lua` file/content intents.
- Activity `com.androlua.LuaActivityX`: document-style Lua activity variant.
- Activity `com.androlua.Welcome`: declared as `MAIN`/`LAUNCHER`, but see the package mismatch noted above.
- Activity `com.nirenr.screencapture.ScreenCaptureActivity`.
- Service `com.androlua.LuaService`.
- Accessibility service `com.androlua.LuaAccessibilityService`.
- Provider `android.content.FileProvider` with authority `com.androlua`.

Runtime Lua state setup:

- `LuaApplication.onCreate()` initializes global directories, `luaCpath`, and `luaLpath`. The Lua path includes local project paths and the extracted managed Lua resource directory.
- `Main` extends `LuaActivity`; its `getLuaPath()` calls `initMain()` and returns `<localDir>/main.lua`.
- `LuaActivity.onCreate()` resolves the Lua file/project, calls `doFile(luaPath, arg)`, and dispatches Lua lifecycle hooks such as `onCreate`, `onNewIntent`, and menu/key callbacks through `runFunc`.
- `LuaActivity.initLua()` creates a `LuaState`, opens libraries and LuaJava, sets globals including `activity`, `service`, and `this`, installs `print`, `set`, and `call`, and sets `package.path`/`package.cpath`.
- `LuaActivity.doFile()` loads file-backed Lua with `L.LloadFile(...)`; `doAsset()` loads asset-backed Lua with `L.LloadBuffer(...)`.
- `LuaService` mirrors the Lua-state setup for service scripts, setting `service` and `this`, installing an asset loader, and executing file or asset scripts.
- `LuaAssetLoader` is registered into package searchers so `require` can load Lua chunks from Android assets.

The observed `com.osfans.trime.Welcome` implementation, if used, requests permissions, extracts `lua` resources to `LuaApplication.getLuaMdDir()`, extracts `assets/rime` to a shared data directory, then starts `Pref`.

## Fixture Candidates

Future parser and semantic tasks should use small, attributed snippets or generated fixtures derived from these files; TASK-002 does not vendor any of them. Post-TASK-184 product work models many of these surfaces via handwritten stubs/overlays rather than vendored upstream sources — still **awaiting TASK-043** suite confirmation; do not claim final green from fixture lists alone.

Must-cover parse fixtures:

- `resources/lua/import.lua`: AndroLua import syntax, environment mutation, nested local functions, metatables, `pcall`, `xpcall`, `luajava.bindClass`, and lazy package/class lookup.
- `resources/lua/loadlayout.lua`: table-driven layout parsing, `require` by string, Java class binding, listener generation, nested table traversal, and callback functions embedded in tables.
- `assets/layout.aly`, `assets/My.aly`, `assets/project.aly`, `assets/projectitem.aly`, and `assets/Community.aly`: `.aly` files are Lua table layout literals with Android class names, attribute strings, nested children, and callback fields.
- `assets/main.lua`: large app shell with many `import "..."` statements, project-list logic, APK build helpers, `dofile`, event handlers, nested functions, table constructors, and Android object calls.
- `assets/main2.lua`: editor/file-manager script with long callbacks, timers, lifecycle hooks, shortcut handling, file operations, and heavily nested functions.
- `assets/AndLua.lua` and `assets/yidian.lua`: helper libraries with non-ASCII identifiers, UI helpers, Android imports, and global function definitions.
- `assets/loadlayout2.lua`, `assets/loadlayout3.lua`, and `assets/layouthelper/main.lua`: alternate layout-helper implementations and app-like helper flow.

Must-cover semantic fixtures:

- `resources/lua/import.lua`: global `import`, local `import = require("import")`, wildcard package behavior, default helper modules, and lazy `luajava` metatable resolution.
- `resources/lua/autotheme.lua`: compact `luajava.bindClass("android.os.Build").VERSION.SDK_INT` access.
- `resources/lua/bin.lua` and `assets/bin.lua`: many Java imports, zip/sign/build flows, Android intent/file APIs, nested local functions, and task callbacks.
- `resources/lua/http.lua`, `resources/lua/socket.lua`, `resources/lua/json.lua`, and `resources/lua/xml.lua`: reusable Lua modules for `require`/module resolution and pure Lua library semantics.
- `assets/javaapi/android.lua` and `assets/javaapi/fiximport.lua`: large Java API tables and import-normalization behavior.
- `assets/plugin/main.lua` and `assets/plugin/*.aly`: plugin-style entry script plus layout table fixtures.

## Relation to post-TASK-184 product surfaces

This architecture page remains the external-tree map. Product modeling lives elsewhere:

| Concern | Companion doc / status note |
| --- | --- |
| Library stub / helper type inventory | `docs/android-lua-library-models.md`, `docs/android-lua-library-stub-matrix.md` — TASK-184 accepted focused stub surfaces; not final green |
| Compatibility claim policy | `docs/android-lua-compatibility-matrix.md` — post-184 modeled / awaiting TASK-043 |
| Require / path matrix | `docs/android-lua-require-path-matrix.md` |
| Corpus verification procedure | `docs/android-lua-verification.md` — uses external root + dual-path jar policy |
| Host SDK / metadata keys | `docs/android-platform-setup.md` |
| JVM reflection classloader | `docs/jvm-reflection-classloader-design.md` |

Claim policy for readers of this page:

- External layout, package list, and fixture candidates: architecture facts from the checkout at the observed commit.
- Static analysis support for `import`, `loadlayout`, `activity`/`service`, LuaJava helpers, and Android framework classes: modeled in-product to varying degrees after TASK-184 and related work — **do not claim final green**.
- Serialized Gradle/test verification remains review-owned under **TASK-043**. Workers must not run Gradle, tests, or compile for docs-only tasks.

## Reproducible Read-Only Commands

Run these from `/Users/dingyi/projects/java_projects/lua-parser` in bash/zsh. The Android-Lua checkout is **present** on this host at the path below; re-clone before corpus acceptance if missing.

```bash
ROOT=/Users/dingyi/projects/java_projects/Android-Lua
test -d "$ROOT" && echo present || echo missing
git -C "$ROOT" remote -v
git -C "$ROOT" rev-parse HEAD
git -C "$ROOT" status --short
ls -la "$ROOT/app/src/main"
cat "$ROOT/app/src/main/AndroidManifest.xml"
cat "$ROOT/app/build.gradle"
find "$ROOT/app/src/main" -type f | head
find "$ROOT/app/src/main/assets" -type f | head
find "$ROOT/app/src/main/resources/lua" -type f | head
find "$ROOT/app/src/main/java" -type f | head
ls -d "$ROOT/app/src/main/jni"/*/ 2>/dev/null
find "$ROOT/app/src/main/jni" -type f -name 'Android.mk'
rg -n 'class Main|extends LuaActivity|onCreate|main.lua|newLuaState|openLibs|setGlobal|LloadFile|LloadBuffer' \
  "$ROOT/app/src/main/java/com/androlua"
rg -n 'luaopen_luajava|bindClass|newInstance|createProxy|javaLoadLib|Java_com_luajava' \
  "$ROOT/app/src/main/jni/luajava" "$ROOT/app/src/main/java/com/luajava"
rg -n -g '*.lua' -g '*.aly' 'import|require|luajava|loadlayout|activity|service|onClick|onCreate' \
  "$ROOT/app/src/main/assets" "$ROOT/app/src/main/resources/lua"

# Host android.jar dual-path check (macOS only; never G:/)
SDK_JAR=/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar
DL_JAR=/Users/dingyi/Downloads/android.jar
test -f "$SDK_JAR" && ls -la "$SDK_JAR" || echo "SDK android-35 jar missing"
test -f "$DL_JAR" && ls -la "$DL_JAR" || echo "Downloads android.jar absent (ok; explicit-only)"

# Prefer Corretto 17 for jar inspection aligned with project JDK
CORRETTO17=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home
"$CORRETTO17/bin/jar" tf "$SDK_JAR" 2>/dev/null | rg 'android/widget/TextView|android/content/Context|android/view/View' | head
```

## Refresh footer

- Docs-only worker: TASK-504-WORKER-WAVE36F-20260712
- Post-TASK-184 library stub acceptance: REVIEW38-WAVE-WAVE36C (TASK-184 `done` in metadata)
- TASK-043 / TASK-037 remain blocked; no final-green claim
- Host re-check: Android-Lua present @ `686a792dbdd2fe9727a34768ceadffcaa2abc20d`; SDK android-35 jar present; Downloads jar absent; Corretto 17 available
- No product code; no Gradle/tests/compile
