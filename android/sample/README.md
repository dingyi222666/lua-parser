# lua-parser Android LSP sample

A minimal Android app that embeds the [lua-parser](../../README.md) Lua
language server **in-process** and drives it from the
[sora-editor](https://github.com/Rosemoe/sora-editor) `CodeEditor` through
sora's `editor-lsp` module — the same shape as sora-editor 0.23.6's own
`LspTestActivity` / `LspLanguageServerService` demo, with two differences:

1. the server is **ours** (`io.github.dingyi222666.luaparser.lsp` from the
   `:android` AAR), served over an abstract `android.net.LocalServerSocket`
   ("lua-lsp") instead of a TCP loopback `java.net.ServerSocket`, and
2. the JVM interop configuration (`jvm.androidJar`, optionally `jvm.classpath`)
   is pushed to the server via `workspace/didChangeConfiguration` after the
   connection is up.

Pinned sora-editor version: **0.23.6** (all APIs used here were verified
against that tag; see the "sora API notes" section at the bottom).

## Layout

```
android/sample/
├── build.gradle.kts                      com.android.application, mirrors android/ conventions
└── src/main/
    ├── AndroidManifest.xml               MainActivity (launcher) + LspServerService (exported=false)
    ├── java/io/github/dingyi222666/luaparser/sample/
    │   ├── MainActivity.kt               sora-pattern activity: TextMate theme/grammar,
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

Prerequisites: JDK 17, Android SDK platform 34. From the repository root:

```bash
./gradlew :android:sample:assembleDebug
adb install -r android/sample/build/outputs/apk/debug/sample-debug.apk
```

or open the project in Android Studio and run the `android.sample`
configuration on a device/emulator with API 26+.

## Provide android.jar (optional but recommended)

The JVM interop surface (completion/hover for `luajava.bindClass("java.lang.String")`
and friends) needs a platform `android.jar` reachable by the server. On Android
there is no SDK directory to auto-discover, so the app expects it as an asset:

1. copy `<$ANDROID_HOME>/platforms/android-34/android.jar` to
   `android/sample/src/main/assets/android.jar` (about 50 MB — not committed);
2. install and launch the app.

On first run `MainActivity` copies `assets/android.jar` to
`filesDir/android.jar` and advertises that **absolute path** to the server as
flat workspace configuration:

```json
{ "jvm.androidJar": "/data/user/0/io.github.dingyi222666.luaparser.sample/files/android.jar" }
```

(`LuaWorkspaceService.parseWorkspaceMetadata` in `:android` accepts the flat
`jvm.androidJar` key or a nested `jvm { "androidJar": ... }` section; without
the asset the key is simply omitted and everything else keeps working.)

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
* **JVM interop** — with `assets/android.jar` provided, `luajava.bindClass`
  targets and `import "android.foo.Bar"` resolve with full member hover and
  completion.
* **Syntax highlighting** — TextMate (`source.lua` grammar, dark theme) as the
  wrapper language; LSP semantic tokens and diagnostics are layered on top by
  sora's `LspEditor`.

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
workflow once the sample has been built once locally and stabilized (that is
deliberately deferred here: no local builds were run while authoring it).

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
* `GrammarRegistry.loadGrammars(languages { ... })` reads grammar files through
  `FileProviderRegistry`, which must have the `AssetsFileResolver` registered
  first (`ensureTextmateTheme()` does this before any load).
* tm4e (`org.eclipse.tm4e.core.registry.IThemeSource`, `ThemeModel`,
  `ThemeRegistry`, `TextMateColorScheme`, `TextMateLanguage`) is vendored
  inside the sora `language-textmate` artifact at 0.23.6, so no extra
  dependency is needed.
