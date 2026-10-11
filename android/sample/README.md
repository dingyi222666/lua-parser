# lua-parser Android LSP sample

A minimal Android app that embeds the [lua-parser](../../README.md) Lua
language server **in-process** and drives it from the
[sora-editor](https://github.com/Rosemoe/sora-editor) `CodeEditor` through
sora's `editor-lsp` module — the same shape as sora-editor 0.23.6's own
`LspTestActivity` / `LspLanguageServerService` demo, with two differences:

1. the server is **ours** (`io.github.dingyi222666.luaparser.lsp` from the
   `:android` AAR), served over an abstract `android.net.LocalServerSocket`
   ("lua-lsp") instead of a TCP loopback `java.net.ServerSocket`, and
2. the JVM/Dex interop configuration (`jvm.androidDex`, optionally `jvm.classpath`)
   is supplied before initialize, so the background workspace index is built
   once with the correct dex settings.

Pinned sora-editor version: **0.23.6** (all APIs used here were verified
against that tag; see the "sora API notes" section at the bottom).

## Layout

```
android/sample/
├── build.gradle.kts                      com.android.application, mirrors android/ conventions
└── src/main/
    ├── AndroidManifest.xml               MainActivity (launcher) + LspServerService (exported=false)
    ├── java/io/github/dingyi222666/luaparser/sample/
    │   ├── MainActivity.kt               Lua highlighting, cached document buffers,
    │   │                                 LspProject/LspEditor attach, connect, jvm config, dispose
    │   ├── ProjectBootstrapper.kt        assets/project -> filesDir/project copy driven by
    │   │                                 manifest.txt + a SHA-256 version marker in filesDir
    │   ├── LspServerService.kt           LocalServerSocket("lua-lsp") accept loop;
    │   │                                 LuaLanguageServerLauncher.launch(in, out) PER connection
    │   └── LocalSocketStreamProvider.kt  client half of the LocalSocket (CustomConnectProvider)
    ├── res/                              layout (CodeEditor), theme, colors, strings
    └── assets/
        ├── project/                      the REAL demo corpus (tools/monaco-lsp-demo/workspace):
        │   │                             main.lua + adapter/ + model/ + mods/ + views/ +
        │   │                             layout/ + image/ + libs/classes.dex
        │   └── manifest.txt              one project-relative path per line (AssetManager
        │                                 cannot list directories — the bootstrapper reads this)
        └── textmate/                     source.lua grammar + language-configuration + lua-dark theme
```

## Build and run

Builds and tests run only on `dingyi@10.1.1.14`. The configured remote workspace
is `/home/dingyi/projects/lua-parser-android-e2e`, with JDK 17 and Android SDK 35.
Run the orchestration script from the repository root:

```bash
bash tools/android-e2e-remote.sh
```

The script syncs source, executes Gradle over SSH, boots the remote
`lua-parser-e2e28` AVD if needed, installs the release APK and instrumentation
APK, and runs Android E2E. No local Gradle process is started. Logs are saved
under `build/android-e2e` **on the remote host**. A failed instrumentation test
returns a nonzero script status, even when `am instrument` returns zero.

`ANDROID_E2E_BUILD_TYPE=debug` selects a diagnostic debug APK.
`ANDROID_READY_BUDGET_MS` controls the E2E wait budget; increasing it is for
investigation and does not demonstrate the startup performance target.
Release uses R8 and includes a Baseline Profile. This sample is signed with the
debug key for installation on the test emulator; it is not a store signing setup.

The remote host has no KVM/nested virtualization, so this AVD uses software
emulation. Emulator boot time is excluded from app startup measurements.

## Package the Android framework DEX

The device-side JVM/Dex interop surface needs framework class metadata. The APK carries
`android.dex`, generated on the remote build host from its installed Android platform with D8:

1. use `<$ANDROID_HOME>/platforms/android-35/android.jar` as D8 input;
2. write the output as `android/sample/src/main/assets/android.dex`;
3. build and install the APK.

On first run `MainActivity` copies `assets/android.dex` to
`filesDir/android.dex` and advertises that **absolute path** to the server as
flat workspace configuration:

```json
{ "jvm.androidDex": "/data/user/0/io.github.dingyi222666.luaparser.sample/files/android.dex" }
```

(`LuaWorkspaceService.parseWorkspaceMetadata` in `:android` accepts the flat
`jvm.androidDex` key or a nested `jvm { "androidDex": ... }` section.)

**dex note:** the bundled demo corpus ships `project/libs/classes.dex`, which
the bootstrapper copies to `filesDir/project/libs/classes.dex` and the app
advertises via `jvm.classpath`; dex libraries are mounted through the parser's
dex reader (see `src/jvmTest/kotlin/interop/jvm/DexMountingTddTest.kt` for the
accepted entries, including `path:Class` prefixed imports).

## What works

* **Completion / hover / diagnostics / document symbols / definition /
  formatting / semantic tokens** — served by the embedded
  `LuaLanguageServer` (one instance per connection), including stdlib
  completion for `math.`, `string.`, ... from the builtin overlay mirror.
* **Workspace features** — `filesDir/project` (the real demo corpus, opened on
  `main.lua`) is registered as a workspace folder, so cross-file references —
  `require("mods.util")`, `adapter.MyLuaAdapter`, `views.*`, `layout/*.aly`,
  `model.AppListStream` — resolve across files.
* **JVM/Dex interop** — with `assets/android.dex` provided, `luajava.bindClass`
  targets and `import "android.foo.Bar"` resolve with full member hover and
  completion.
* **Runtime DEX imports** — a library copied into the project's `libs` directory
  after connection can be imported from Lua. Android E2E covers an independent
  D8-generated library absent from both APK classpaths: static methods, Java
  factory results stored in Lua tables, and partial colon completion through the
  visible editor popup. This does not yet prove every library replacement or
  relative-path import scenario.
* **Syntax highlighting** — the parser's `LuaLexer` drives the editor analyzer.
  It resumes before the edited token, handles long strings/comments and CRLF,
  and retains keyword/literal/comment colors, bracket pairs and indentation.
* **Document switching** — bridges and unsaved buffers are retained for the
  Activity. Only the active document subscribes to editor changes; diagnostics
  from inactive documents do not overwrite the visible editor. E2E verifies that
  editing an open dependency without saving replaces the consumer's completion
  members after switching back, while the disk file retains its original text.
* **Startup analysis** — a small typed builtin overlay serves live-buffer
  queries while full workspace/framework/dex analysis proceeds in the background.
  Full builds parallelize independent parsing and binding with isolated worker
  state, while dependency-sensitive resolution and publication stay ordered.
  The first document is editable before the bundled workspace copy completes.
  Live-buffer queries are published before checker diagnostics, which are sent
  separately for the same document revision. Closed connections cancel indexing.
  Wildcard packages, explicit imports and implicit class names use a name index
  until a class is queried. Completed diagnostics are reused for unchanged
  bindings and invalidated for affected documents. An unrelated edit does not
  interrupt a checker already running on an unchanged binding. Superseded
  analyses stop at cooperative checkpoints; publication still checks the revision.
  The full index also publishes resolved bindings before checker work; open
  documents are checked afterward. Opening an unchanged indexed document or
  reopening it still produces current diagnostics.
* **Incremental edits** — changes from one UI turn are batched in order, using
  correct zero-width insertion ranges. File switches flush pending edits before
  detaching the old document. Formatting and saving wait for those notifications.

Functional E2E success does not imply performance acceptance. On the remote
software emulator, first custom completion and completion after editing are
still slower than the HTML baseline. Timing logs distinguish text visibility,
connection readiness, first/warm completion, the actual completion popup,
semantic diagnostics, and document switching.
Current measurements and verification limits are recorded in [PERFORMANCE.md](PERFORMANCE.md).

## Troubleshooting

* *"Unable to connect the Lua language server"* toast — the service failed to
  bind or exited early; check logcat for `LspServerService` /
  `MainActivity` tags.
* A previous run left a server bound to the socket name: the abstract socket
  dies with the app process, so force-stopping the app clears it.
* lsp4j version: the sample pins lsp4j **0.23.1** to match the `:android`
  artifact; sora-editor 0.23.6 was built against 0.24.0 (Gradle resolves to
  0.23.1). If sora's client ever trips over a missing lsp4j method, bump the
  two lsp4j lines in `android/sample/build.gradle.kts` to 0.24.0.

## CI / follow-ups

`android-verify` currently builds **`:android` only** — this sample is not
compiled by CI yet. Follow-up: add `:android:sample:assembleDebug` to the
workflow after the remote sample/E2E performance checks are settled.

## sora API notes (version-sensitive bits)

* `CustomLanguageServerDefinition(ext, serverConnectProvider)` — 0.23.6 has no
  `name` parameter and no `languageServerDefinition { ... }` /
  `connection { local(...) }` Kotlin DSL (those are sora master only); the
  sample uses the verified 0.23.6 constructors.
* There is no `LocalSocketStreamConnectionProvider` at 0.23.6 —
  `LocalSocketStreamProvider` (abstract namespace, matching sora master's
  implementation) fills the gap on top of `CustomConnectProvider.StreamProvider`.
* `LspEditor.connectWithTimeout()` is `suspend` at 0.23.6 (called from
  `lifecycleScope.launch` like the sora sample); `requestManager` is nullable.
* `WorkspaceCodeEditor` keeps UI analyzer replacement separate from transport
  disposal. It unsubscribes Sora document/selection receivers before switching
  the buffer. Activity destruction disposes bridges sequentially before the
  project, avoiding Sora 0.23.6's map mutation during `closeAllEditors`.
* The LocalSocket output adapter uses unbuffered writes without the platform's
  queue-draining `flush`, avoiding a sender/handler wait during document events.
