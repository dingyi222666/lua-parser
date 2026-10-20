# Embedding the Lua LSP server in an Android host app

This guide is for AndroLua-style host apps that want full Lua language support
(completion, hover, diagnostics, document symbols, workspace symbols, definition)
inside an editor such as sora-editor, by running the parser's JVM LSP server
**in-process** on the device.

Everything below is plain `java.net` / `java.nio.file` JVM code — no Android-specific
APIs are required from this library, so it runs unmodified on Android's ART JVM.

- Artifact: the `luaparser-android` AAR built from `android/` (group
  `io.github.dingyi222666`, see `android/build.gradle.kts`). A fat classes jar is
  also published for consumers that run their own dex pipeline.
- Server entry points (all in `src/jvmMain/.../lsp/`):
  - `LuaLanguageServerLauncher.launch(...)` — one LSP connection over arbitrary streams.
  - `serveSocket(...)` — loopback TCP accept loop, one fresh server per connection.
  - `LuaLanguageServer` / `LuaLanguageService` — protocol + engine.

---

## 1. Topology: service + `LocalServerSocket` + `launch()` per connection

The recommended Android topology is:

```
app process
├── LocalServerSocket("lua.lsp")            ← accept loop (your code, ~10 lines)
│     └── per accepted LocalSocket:
│          LuaLanguageServerLauncher.launch(in, out)   ← ONE server per connection
└── sora-editor lsp client                  ← connects to the local end
```

`LuaLanguageServerLauncher.launch` never blocks and never touches the process
lifecycle: its `processExit` hook defaults to a **no-op**, so a client `exit`
notification cannot kill your app (the desktop `start()`/`startAndWait()`/`main()`
entry points pin `exitProcess` instead — do not use those in an app).

### `android.net.LocalServerSocket` accept loop

```kotlin
class LuaLspEngine(context: Context) : Closeable {
    private val listener = android.net.LocalServerSocket("lua.lsp")
    private val connections = mutableListOf<LuaLanguageServerConnection>()
    private var closed = false

    private val acceptThread = Thread {
        while (!closed) {
            val socket = try { listener.accept() } catch (e: Exception) { break }
            Thread {
                // Fresh streams + fresh server PER connection (see §2).
                val connection = LuaLanguageServerLauncher.launch(
                    socket.inputStream, socket.outputStream
                )
                synchronized(connections) { connections += connection }
                connection.listening.get()   // returns when the peer disconnects
                synchronized(connections) { connections -= connection }
                socket.close()
            }.start()
        }
    }.apply { isDaemon = true; start() }

    override fun close() {
        closed = true
        runCatching { listener.close() }
        synchronized(connections) { connections.forEach { it.listening.cancel(true) } }
    }
}
```

Alternatively use the built-in loopback TCP host — it binds `127.0.0.1` only
(no `INTERNET` permission needed, traffic never leaves the app sandbox) and
creates one server per accepted connection for you:

```kotlin
val host = serveSocket(
    socketName = "lua-lsp",
    serverFactory = { LuaLanguageServer() }, // invoked once per accepted connection
    onError = { it.printStackTrace() },      // keep cheap and non-throwing
    port = 0                                 // ephemeral; read host.port afterwards
)
// later, on app shutdown:
host.close()
```

Both transports work unchanged on Android. Prefer `LocalServerSocket` when you
want an abstract UNIX-domain name (no port collisions, kernel-level same-uid
isolation); prefer `serveSocket` for zero boilerplate.

## 2. One server per connection — mandatory

`LuaLanguageService` (the engine behind every `LuaLanguageServer`) holds
per-connection mutable state: the didOpen/didChange text overlays, the workspace
index, the semantic-token cache, and client capabilities captured at `initialize`.
`LuaLanguageServer` additionally holds exactly one client proxy (diagnostics are
published to it) and a single-shot lifecycle (`initialize` twice fails).

Sharing one instance across two connections would publish editor A's diagnostics
to editor B, mix A's buffers into B's queries, and hard-fail B's `initialize`.
Create a **fresh `LuaLanguageServer` per accepted connection** — exactly what
`serveSocket` does and what the `launch(...)` snippet above does (each call builds
a new server unless you pass one explicitly).

## 3. Connection lifecycle

Standard LSP over Content-Length framed JSON-RPC:

1. `initialize` — pass the workspace root in `rootUri` (or `workspace/folders`).
   On device this is an app-private `filesDir` subdirectory (see §6):

   ```json
   {
     "processId": null,
     "rootUri": "file:///data/user/0/com.example.app/files/lua-project",
     "capabilities": {
       "textDocument": { "documentSymbol": { "hierarchicalDocumentSymbolSupport": true } }
     }
   }
   ```

2. `initialized` — handshake complete.

3. `workspace/didChangeConfiguration` — this is how all parser configuration is
   delivered (`initializationOptions` at `initialize` are **not** read; the
   settings object of `didChangeConfiguration` is the single configuration
   channel). See §4/§5 for the exact JSON.

4. `textDocument/didOpen` / `didChange` / `didClose` — open buffers overlay the
   workspace index and are authoritative for unsaved content.

5. `shutdown` / `exit` — the server transitions to EXITED and rejects further
   requests; `processExit` stays a no-op. When
   `LuaLanguageServerConnection.listening` completes, drop the connection
   (close the socket). Nothing else is required — never call `exitProcess`.

Note the deliberate sequencing: workspace indexing (`Files.walk` over the root)
happens synchronously inside `initialize`, and the JVM-interop engine rebuild
happens on each `didChangeConfiguration` that carries new metadata. Send the
`didChangeConfiguration` right after `initialized` so the first completion
requests already see your android.jar/dex surface.

## 4. `jvm.androidJar` on device — explicit config wins, zero discovery

On a phone there is no `ANDROID_HOME`, no SDK install, and no well-known SDK
root. The discovery ladder in
`src/jvmMain/.../interop/jvm/JvmWorkspaceConfiguration.kt` (env vars
`ANDROID_HOME`/`ANDROID_SDK_ROOT`, then macOS/Linux/Windows well-known SDK
roots) is therefore useless on device — but it is also **never consulted when
you configure the jar explicitly**:

- `reflectionClasspathEntries()` adds your `jvm.androidJar` value to the
  reflective classpath directly and runs discovery only when the value is
  unset/blank (`if (androidJar.isNullOrBlank())`).
- `shouldSoftFallbackToHostAndroidJar()` (in `JvmClassModuleProvider`) returns
  `false` when the configured path exists (`File(configuredJar).isFile`), so the
  host-SDK soft fallback cannot fire either. A device path under
  `/data/user/0/...` matches none of the well-known *host* fixture shapes, so a
  missing explicit path degrades to "no framework classes" — it never silently
  mounts a host jar.

**Rule for Android hosts: always ship and configure android.jar explicitly.**
Bundle a platform `android.jar` in your APK assets (or download it into
`filesDir` on first run), copy it to an app-private file, and point
`jvm.androidJar` at the absolute path.

### Exact metadata JSON (workspace/didChangeConfiguration)

Settings are read by `LuaWorkspaceService` and accept flat keys, nested
sections, or a mix; list values may be JSON arrays of strings or one
multi-line string:

```json
{
  "settings": {
    "jvm": {
      "androidJar": "/data/user/0/com.example.app/files/lua/android.jar",
      "classpath": [
        "/data/user/0/com.example.app/files/lua/libs/classes.dex",
        "/data/user/0/com.example.app/files/lua/libs/mylib.apk"
      ],
      "importPrefixes": ["android.view", "android.widget"]
    },
    "androlua": {
      "imports": ["com.androlua.*"]
    }
  }
}
```

| Key | Type | Meaning |
|---|---|---|
| `jvm.androidJar` | string | Absolute path to a platform `android.jar`. Explicit config; discovery is skipped entirely when set. |
| `jvm.classpath` | string[] | Jars, class directories, and `.dex`/`.apk` libraries (see §5). |
| `jvm.classes` | string[] | Fully-qualified classes to expose by simple name. |
| `androlua.imports` | string[] | AndroLua-style import targets: `com.androlua.*` wildcards, class names, or dex/apk paths. |
| `jvm.importPrefixes` | string[] | Packages resolvable by simple name. Defaults: `java.lang`, `java.util`, `java.io`, `android.app`, `android.content`, `android.view`, `android.widget`, `com.androlua`. `com.androlua` is always kept active even when you override the list. |
| `lua.layout.properties` | string | Optional AndroLua layout-completion spec (`ClassName: prop|detail, ...`). |

Flat form of the same payload is equally valid:
`{"settings": {"jvm.androidJar": "...", "jvm.classpath": ["..."], "androlua.imports": ["com.androlua.*"]}}`.

## 5. Dex libraries (`DexLibraryMounter`) with app-private paths

`.dex` files and `.apk` files (their bundled `classes.dex`, `classes2.dex`, ...)
listed in `jvm.classpath` or `androlua.imports` are mounted by
`DexLibraryMounter` (`src/jvmMain/.../interop/jvm/DexLibraryMounter.kt`):

- Mountability check is exactly `File.isFile && extension in {"dex","apk"}` —
  no host-specific paths, no `ANDROID_HOME`, no prefix assumptions. App-private
  `filesDir`/`cacheDir` paths (`/data/user/0/<pkg>/files/...`) work as-is; the
  parse cache keys on `(absolutePath, lastModified, length)`.
- Re-mounts after the file changes are automatic (mtime/size identity), and the
  parsed-library cache is LRU-bounded.
- Optimized device artifacts (`.odex`/`.vdex`) are **not** mountable — they are
  device build artifacts; ship the original `classes.dex` or a jar instead.
- The file must already exist on disk when `didChangeConfiguration` arrives;
  a not-yet-copied path is treated as "not a classpath entry" (no crash, no
  invented classes).

AndroLua aliasing is built in: `com.foo.Outer_Inner` / `com.foo.Outer.Inner`
resolve to nested `Outer$Inner` classes, and bare simple-name imports resolve
against the configured prefixes (std module names like `string`/`table` are
guarded and never resolve to a dex class bare).

## 6. Workspace root via `filesDir`

The service indexes `.lua`/`.aly` files under the initialize root with
`java.nio.file.Files.walk` (`LuaLanguageService.indexWorkspaceFolder`). The
whole initialize/index path is host-agnostic: no hard-coded path prefixes, no
`user.home` or environment lookups anywhere in the `lsp` package — only
`Files.isDirectory` / `Files.isRegularFile` / `Files.readString` guards, all of
which behave normally on device.

Point `rootUri` at an app-private directory:

```kotlin
val workspaceDir = File(context.filesDir, "lua-project").apply { mkdirs() }
val rootUri = Uri.fromFile(workspaceDir).toString()   // file:///data/user/0/<pkg>/files/lua-project
```

- `file:` URIs and plain absolute path strings are both accepted
  (`pathFromFileUri` handles `file:///...`, `file:/...`, and scheme-less
  paths); non-file schemes (`content://`) are not filesystem roots — copy or
  stream such content under `filesDir` first.
- Relative path computation uses `Path.relativize` on the *passed* root, so
  virtual paths shown in symbols/diagnostics are relative to your `filesDir`
  subdir, not to any host layout.
- Live file additions/deletions under the root are picked up via
  `workspace/didChangeWatchedFiles` (send them from a
  `FileObserver` if you mutate the workspace outside the editor).

## 7. sora-editor (editor-lsp module) client snippet

With sora-editor's `editor-lsp` artifact, the client end of the loopback socket:

```kotlin
// TCP route (matches serveSocket above); names track your editor-lsp version.
val project = LspProject()
project.addServerDefinition(
    CustomLanguageServerDefinition("lua") {
        Socket("127.0.0.1", host.port)   // fresh socket per editor instance
    }
)
val lspEditor = project.createEditor(
    "file:///data/user/0/com.example.app/files/lua-project/main.lua"
)
val codeEditor: CodeEditor = lspEditor.editor   // embed in your view hierarchy
```

For the `LocalServerSocket` route, use a custom connect provider that opens a
`LocalSocket()` and connects it to `"lua.lsp"`, returning its streams' socket
adapter — or bridge `LocalSocket` to a pair of `PipedInputStream`/`PipedOutputStream`
if your client API demands a `java.net.Socket`.

Published diagnostics arrive on the lsp4j reader thread; if you surface them in
UI (squiggles, a problems panel), hop to the main thread yourself.

## 8. minSdk 26 and R8/ProGuard

- **minSdk 26**: the jvmMain sources use `java.nio.file.{Files,Path,Paths}`,
  `Files.readString`, and other APIs that only exist from Android API 26 on.
  `android/build.gradle.kts` sets `minSdk = 26`; your app must be
  `minSdk >= 26` too.
- **Consumer ProGuard/R8 rules ship inside the AAR**
  (`consumerProguardFiles("proguard-consumer.pro")`): they keep
  `org.eclipse.lsp4j.**`, `org.eclipse.lsp4j.jsonrpc.**` (Gson-reflective
  models and remote proxies), the Gson `TypeToken`/`@SerializedName` surface,
  and Kotlin annotation attributes. Consuming the AAR requires no extra config.
- **Fat classes jar consumers** (custom dex pipeline, e.g. feeding AndroLua):
  apply `android/proguard-consumer.pro` to your own `proguardFiles` by hand.
- The bundled AndroLua runtime classes (`com.androlua.*`, `com.luajava.*`) are
  extracted from library resources at runtime; do not strip resource files
  matching the runtime jar from the artifact.

## 9. Shutdown checklist

1. Close each editor / drop each sora `LspEditor`.
2. Send `shutdown` + `exit` per connection (or just cancel
   `LuaLanguageServerConnection.listening` / close the socket).
3. `host.close()` (built-in TCP host) or close your `LocalServerSocket` and
   cancel accept/per-connection threads.
4. Never call `exitProcess` — the default `processExit` no-op keeps the app
   process alive by design.
