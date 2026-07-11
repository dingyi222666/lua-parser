# JVM Language Server Usage

This document describes the JVM LSP entry point, workspace configuration, supported request surface, Android-Lua/JVM metadata behavior, and the serialized verification harness expectations for TASK-053.

The behavior here is based on the current JVM implementation under `src/jvmMain/kotlin/io/github/dingyi222666/luaparser/lsp` and related JVM interop code. Items that still need command-level confirmation are labeled as pending TASK-043 or TASK-037 because this documentation wave does not run Gradle, compile, or test commands.

## Scope

The language server is a JVM-only LSP4J server for Lua 5.3-oriented workspace analysis with Android-Lua and LuaJava-aware JVM metadata providers. It exposes analysis results from the semantic workspace engine through standard LSP text-document and workspace requests.

The server is not an Android runtime, a Lua runtime, or a full Java execution environment. Android framework and JVM class support is metadata-oriented: configured classes are reflected from the running JDK, configured jars, class directories, or `android.jar`, then exposed as synthetic workspace providers.

## Launch

The stdio entry point is:

```text
io.github.dingyi222666.luaparser.lsp.LuaLanguageServerLauncherKt
```

`LuaLanguageServerLauncher.main()` calls `LuaLanguageServerLauncher.startAndWait()`, which creates a `LuaLanguageServer`, wires it to an LSP4J JSON-RPC launcher over standard input and output, connects the remote `LanguageClient`, and blocks while the launcher listens.

The repository declares a Gradle `JavaExec` helper named `runLuaLanguageServer` that starts the same main class with the JVM jar and `jvmRuntimeClasspath`.

```bash
export JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home
./gradlew runLuaLanguageServer
```

Do not run that command during parallel implementation or documentation waves. It is shown as the intended launch shape only; command verification is deferred to TASK-043.

Editors or harnesses that launch from a built distribution should start the same main class on a classpath containing the project JVM artifact and runtime dependencies, then speak LSP JSON-RPC over stdio.

## Lifecycle

The server accepts exactly one normal lifecycle:

1. `initialize`
2. `initialized`
3. text-document and workspace notifications/requests
4. `shutdown`
5. `exit`

`initialize` indexes `.lua` and `.aly` files under configured workspace folders, builds the first workspace snapshot, and returns `ServerCapabilities`. A second `initialize`, `initialize` after `shutdown`, `initialize` after `exit`, and `shutdown` before `initialize` complete exceptionally. `shutdown` after successful initialization returns `0`; repeated `shutdown` after that also returns `0`.

After `shutdown` or `exit`, text-document and workspace activity is ignored or rejected depending on whether the LSP method is a notification or request. After `exit`, the connected client reference is cleared, so diagnostics are no longer published.

Workspace-folder selection during `initialize` follows the policy in [Workspace Folders, rootUri, And Watched Files](#workspace-folders-rooturi-and-watched-files). Existing folder-backed `.lua` and `.aly` files are available to diagnostics, require resolution, references, document symbols, and workspace symbols before they are opened in the editor.

## Capabilities

`initialize` currently advertises:

| Capability | LSP behavior |
| --- | --- |
| Text document sync | `TextDocumentSyncKind.Full` |
| Hover | Markdown hover content through `textDocument/hover` |
| Completion | `CompletionList` through `textDocument/completion`; trigger characters `.` and `:` |
| Signature help | Trigger characters `(` and `,`; retrigger `)` |
| Declaration | `textDocument/declaration` returns location lists |
| Definition | `textDocument/definition` returns location lists |
| References | `textDocument/references` returns location lists |
| Document highlight | `textDocument/documentHighlight` |
| Document symbols | `textDocument/documentSymbol`, returned as `SymbolInformation` |
| Workspace symbols | `workspace/symbol`, returned as `SymbolInformation` |

The implementation returns LSP4J `Either` wrappers where required by the protocol, but currently uses location lists and `SymbolInformation` rather than `LocationLink`, hierarchical `DocumentSymbol`, or `WorkspaceSymbol` objects.

Capability advertisement and request behavior are pending serialized confirmation in TASK-043. Final production-readiness claims remain pending TASK-037.

## Workspace Folders, rootUri, And Watched Files

After TASK-156, initialization indexes disk-backed Lua sources under the client's workspace roots instead of analyzing only open documents. The policy is implemented in `LuaLanguageService` and exercised by `LspWorkspaceFoldersTddTest` and `LspWatchedFilesTddTest`.

### Folder selection and `rootUri` fallback

1. If `InitializeParams.workspaceFolders` is present and non-empty, those folders are retained as the workspace roots.
2. If `workspaceFolders` is omitted or empty, a non-blank `rootUri` is promoted to a single synthetic `WorkspaceFolder` whose name is derived from the URI path segment (default name `workspace` when the path cannot be derived).
3. If both `workspaceFolders` and `rootUri` are absent/blank, the server starts with no indexed disk roots. Only open documents and synthetic providers participate in the snapshot until the client supplies folders or opens files.

Only resolvable real directories are scanned. Folder URIs that do not map to an existing directory are skipped for indexing. Non-`file:` schemes (and other unresolvable roots) do not contribute disk files; open documents with custom schemes still work through the open-document overlay path described below.

### What is indexed

For each retained real directory root, `initialize` walks the tree and indexes regular files whose names end in `.lua` or `.aly` (case-insensitive). Indexed paths are stored relative to their workspace folder so module resolution treats folder-root modules as siblings (`require("dep")` → `dep.lua` at the folder root). Diagnostics, definitions, references, and workspace symbols still report the real `file:` URI for those indexed files.

Open documents overlay the indexed source map: unsaved editor text wins until `textDocument/didClose`, which removes only the overlay, restores the indexed disk source for the next snapshot, and clears client diagnostics for that URI. The indexed disk file remains available after close.

### Watched-file expectations

Clients that want the disk index to track external create/change/delete events should send `workspace/didChangeWatchedFiles`. `LuaWorkspaceService.didChangeWatchedFiles` records the events and forwards them to `LuaLanguageService.applyWatchedFileChanges`, which:

- accepts `Created` and `Changed` events by reading the current on-disk source for `.lua` / `.aly` URIs and upserting the indexed map;
- accepts `Deleted` events by removing the indexed source (URI mapping may be retained so diagnostics for that path can still clear against the original URI);
- ignores non-Lua/ALY files;
- never overwrites an open-document overlay with disk content for the same virtual path;
- rebuilds the workspace snapshot incrementally when the indexed map mutates, then republishes open-document diagnostics so require/module resolution updates are visible.

The server does not register file watchers itself. Editors or harnesses must subscribe to the relevant globs (at least `**/*.{lua,aly}` under each workspace folder) and forward events. Without watched-file notifications, only the initialization index plus open/close/change overlays stay current; external disk edits remain invisible until the next full re-index opportunity (currently a new `initialize` lifecycle).

Focused coverage for create/change/delete, `.aly` indexing, overlay preservation, and diagnostic republish lives in `LspWatchedFilesTddTest`. Command-level confirmation remains deferred to TASK-043.

### URI normalization caveats (TASK-161)

Workspace-folder roots, `rootUri`, open-document URIs, watched-file URIs, and client path-string APIs currently share several ad hoc conversion helpers (`rawWorkspacePathFromUri`, `pathFromFileUri`, `lspVirtualPathFromUri`, `lspFileUri`). Known caveats pending TASK-161:

- Unix absolute `file:` URIs and Windows drive-letter paths are not yet guaranteed to share one hardened normalization path; leading-slash and percent-encoding roundtrips can still differ by platform.
- `file:` path decoding currently strips a leading `/` in some helpers, which is Windows-oriented and can confuse POSIX absolute paths until TASK-161 hardens the conversion.
- Non-file or custom schemes (`untitled:`, `vscode-notebook-cell:`, opaque URIs) are encoded to stable internal virtual paths for analysis, while diagnostics and same-document navigation preserve the original client URI when possible. Synthetic JVM/Android providers continue to use virtual `file:///__jvm__/...` URIs.
- `rootUri` and workspace-folder URI conversion are intended to share one path (acceptance criterion for TASK-161) but are not yet fully unified across all entry points.

Clients should prefer consistent `file:` URIs from the same host/path style for folders, documents, and watched events. Cross-platform URI hardening is owned by TASK-161 (`LspUriHandlingTddTest`); this page only documents the current caveats.

## Document Sync And Diagnostics

Opened documents are kept in memory by their exact LSP document URI. The implementation converts normal `file:` URIs into virtual workspace paths, maps non-file, opaque, or custom-scheme URIs such as `untitled:` and `vscode-notebook-cell:` to stable encoded internal paths, rebuilds the workspace snapshot after document changes, and publishes diagnostics for the changed document.

On initialization, real `file:` workspace folders are scanned recursively for `.lua` and `.aly` files. If `workspaceFolders` is absent, a real `file:` `rootUri` is scanned the same way. Indexed file paths are normalized relative to their workspace folder so `require("dep")` resolves `dep.lua` at the folder root, while LSP locations and diagnostics still report the real file URI. Open documents overlay the indexed source map, so unsaved editor text wins over the on-disk file until the document is closed. Closing the document removes only the open overlay, restores the indexed disk source to the snapshot, and clears diagnostics for that client URI; the indexed disk file remains available in the next rebuilt snapshot.

Supported notifications:

- `textDocument/didOpen` stores the full text, rebuilds analysis, and publishes diagnostics.
- `textDocument/didChange` applies the received changes to the in-memory document, rebuilds analysis, and publishes diagnostics.
- `textDocument/didClose` removes the document from the snapshot and publishes an empty diagnostic list for that URI.
- `textDocument/didSave` is accepted but currently has no additional behavior.
- `workspace/didChangeWatchedFiles` updates the indexed disk source map for `.lua` / `.aly` create/change/delete events (see [Workspace Folders, rootUri, And Watched Files](#workspace-folders-rooturi-and-watched-files)).

Although the advertised sync mode is full sync, `LuaTextDocumentService` can apply ranged `TextDocumentContentChangeEvent` values to its own open-document cache before forwarding a full replacement to `LuaLanguageService`. Stale versioned changes are ignored when both the cached and incoming versions are known and the incoming version is not newer. Changes for unknown documents are ignored.

Diagnostics combine parser recovery diagnostics for the current source text with workspace query diagnostics. Invalid Lua produces LSP `Error` diagnostics for parse recovery failures, including unopened indexed workspace files; semantic diagnostics use `Error`, `Warning`, or `Information` severities mapped from the semantic diagnostic severity. Closing a document clears client diagnostics for that URI.

Diagnostics and locations for open client documents preserve the original document URI when possible. For example, an `untitled:` document publishes diagnostics back to that exact `untitled:` URI, and same-document navigation or workspace symbols for a custom-scheme document report the original custom URI. Synthetic provider locations, including JVM and Android-Lua metadata providers, continue to use virtual `file:///__jvm__/...` URIs.

When `workspace/didChangeConfiguration` changes JVM or Android-Lua metadata, open-document diagnostics are republished after the workspace is rebuilt. When watched-file events mutate the indexed disk map, open-document diagnostics are likewise republished after the incremental snapshot update.

## Request Behavior

The request surface is backed by `LuaWorkspaceQueryFacade` over the current workspace snapshot:

- Hover returns Markdown containing the symbol name, detail, and type display name when available.
- Completion returns visible locals, functions, module members, reflected JVM members, configured imports, and keyword/snippet entries as provided by the semantic query layer. The server advertises `.` and `:` as Lua member-completion trigger characters; this advertisement does not change the completion item list shape.
- Signature help returns callable labels, parameter labels, optional Markdown documentation, active signature, and active parameter. Method receiver offsets and Lua short-string call syntax are implemented in the query layer and covered by pending LSP tests.
- Definition and declaration resolve locals, module aliases, module fields, reflected JVM class/member providers, and Android-Lua/LuaJava class-load facts when the current snapshot can identify them.
- References and document highlights collect known occurrences from the current workspace snapshot and provider surfaces.
- Document symbols list navigable non-builtin declarations in the requested open document, excluding parameters and type parameters.
- Workspace symbols search indexed workspace documents, open document overlays, and synthetic provider entries. Blank queries return all indexed symbols; nonblank queries use case-insensitive substring matching.

The current implementation uses a full engine rebuild on cold `initialize` and on metadata configuration changes. Open/change/close and watched-file updates use incremental snapshot deltas when a prior snapshot is ready. Folder indexing runs during initialization; later disk create/change/delete visibility depends on `workspace/didChangeWatchedFiles` as described above.

## Workspace Configuration

JVM and Android-Lua behavior is configured through `workspace/didChangeConfiguration`. The server accepts both flat keys and nested sections. List settings may be JSON arrays or newline-separated strings.

Flat settings:

```json
{
  "jvm.classes": ["java.util.Arrays", "java.util.Locale"],
  "androlua.imports": ["Context", "TextView"],
  "jvm.classpath": ["/Users/dingyi/example/app.jar", "/Users/dingyi/example/classes"],
  "jvm.androidJar": "/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar",
  "jvm.importPrefixes": ["java.lang", "java.util", "android.content", "android.widget"]
}
```

Nested settings:

```json
{
  "jvm": {
    "classes": "java.util.Arrays\njava.util.Locale",
    "classpath": ["/Users/dingyi/example/app.jar"],
    "androidJar": "/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar",
    "importPrefixes": "java.lang\nandroid.content\nandroid.widget"
  },
  "androlua": {
    "imports": "Context\nTextView"
  }
}
```

Configuration keys:

| Key | Purpose |
| --- | --- |
| `jvm.classes` | Fully qualified or resolvable class names to mount as reflected providers. |
| `androlua.imports` | Android-Lua import targets to pre-resolve into reflected providers. |
| `jvm.classpath` | Additional jar files or class-directory roots for reflection. |
| `jvm.androidJar` | Android platform jar used to resolve Android framework classes. |
| `jvm.importPrefixes` | Prefixes used to resolve simple import names such as `TextView`. |

Default import prefixes are:

```text
java.lang
java.util
java.io
android.app
android.content
android.view
android.widget
com.androlua
```

Configuration changes update workspace metadata. If metadata is unchanged after normalization, the workspace service avoids redundant rebuild work.

## Android Jar And Classpath

`jvm.classpath` entries are jar files or directories containing package-rooted `.class` files. `jvm.androidJar` is appended to the effective reflective classpath after `jvm.classpath`.

The implementation also has a local fallback path:

```text
/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar
```

That fallback is used for reflection classpath entries only when no `jvm.androidJar` is configured and the file exists. Production clients should still send `jvm.androidJar` explicitly so the target Android platform is reproducible.

The reflection provider creates a child `URLClassLoader` when classpath entries are configured. Class loading uses `Class.forName(name, false, classLoader)`, so classes are inspected without class initialization. Missing classpath entries generally leave affected classes unresolved; they do not by themselves make LSP startup fail.

Synthetic provider URIs use this shape:

```text
file:///__jvm__/classes/java/util/Arrays.lua
file:///__jvm__/classes/android/widget/TextView.lua
file:///__jvm__/packages/android/widget.lua
```

These are virtual workspace provider paths, not files on disk.

## Android-Lua And LuaJava Metadata

The JVM workspace engine collects Android-Lua and LuaJava facts from source documents, then asks the reflective provider to mount the classes and packages required by those facts.

Supported source patterns include:

- `import "android.widget.TextView"`
- `import "android.widget.*"`
- `import { "android.content.Context", "android.view.View" }`
- `import "plugin.dex:android.content.Context"`
- `luajava.bindClass("android.content.Context")`
- `luajava.newInstance("android.widget.TextView", ...)`
- `luajava.createProxy("android.view.View.OnClickListener", table)`
- `luajava.loadLib("java.lang.System", "currentTimeMillis")`
- short-string call forms such as `import "android.content.Context"` and aliased helper calls when the source facts can trace them

Explicit imports create simple-name aliases such as `TextView`. Wildcard imports add package prefixes and can mount package providers when the package can be enumerated from the runtime image, jars, or classpath directories. Dex-prefixed strings are normalized by ignoring the prefix before `:` for metadata resolution; this models the class target but does not load an Android dex runtime.

Inner classes can be requested with JVM binary names, Java-style dotted nested names, or Android-Lua underscore aliases:

```lua
import "android.view.View$OnClickListener"
import "android.view.View.OnClickListener"
import "android.view.View_OnClickListener"
```

Reflection resolves these to provider paths such as:

```text
file:///__jvm__/classes/android/view/View$OnClickListener.lua
```

Android-Lua overlay globals such as `import`, `loadlayout`, `loadbitmap`, `loadmenu`, and `luajava` are modeled by the semantic workspace overlay. Definitions for overlay globals may intentionally remain empty because the overlay is builtin metadata rather than an opened source file.

Android-Lua fixture-level behavior, including `.aly` layout files, layout id tracking, Android listener methods, and Android provider-backed references, is represented in pending LSP tests and must be reconciled in TASK-043 before this page is treated as verified production guidance.

## Client Message Examples

A minimal initialization request:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "processId": null,
    "rootUri": "file:///Users/dingyi/example-workspace",
    "workspaceFolders": [
      {
        "uri": "file:///Users/dingyi/example-workspace",
        "name": "example-workspace"
      }
    ],
    "capabilities": {}
  }
}
```

`rootUri`-only initialization (no `workspaceFolders`) is also accepted and indexes the same way when `rootUri` is a real `file:` directory:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "processId": null,
    "rootUri": "file:///Users/dingyi/example-workspace",
    "capabilities": {}
  }
}
```

Example watched-file batch after external disk edits under that folder:

```json
{
  "jsonrpc": "2.0",
  "method": "workspace/didChangeWatchedFiles",
  "params": {
    "changes": [
      {
        "uri": "file:///Users/dingyi/example-workspace/dep.lua",
        "type": 1
      },
      {
        "uri": "file:///Users/dingyi/example-workspace/old.lua",
        "type": 3
      }
    ]
  }
}
```

LSP `FileChangeType` values are `1` Created, `2` Changed, and `3` Deleted.

Android-Lua configuration for an Android 35 workspace:

```json
{
  "jsonrpc": "2.0",
  "method": "workspace/didChangeConfiguration",
  "params": {
    "settings": {
      "jvm": {
        "androidJar": "/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar",
        "importPrefixes": [
          "java.lang",
          "java.util",
          "android.app",
          "android.content",
          "android.view",
          "android.view.View",
          "android.widget"
        ]
      },
      "androlua": {
        "imports": [
          "Activity",
          "Context",
          "View",
          "OnClickListener",
          "TextView",
          "Button",
          "LinearLayout"
        ]
      }
    }
  }
}
```

Open a Lua document:

```json
{
  "jsonrpc": "2.0",
  "method": "textDocument/didOpen",
  "params": {
    "textDocument": {
      "uri": "file:///Users/dingyi/example-workspace/main.lua",
      "languageId": "lua",
      "version": 1,
      "text": "import \"android.widget.TextView\"\nlocal view = TextView(activity)\nreturn view.setText"
    }
  }
}
```

Request hover or definition over a reflected member:

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "textDocument/hover",
  "params": {
    "textDocument": {
      "uri": "file:///Users/dingyi/example-workspace/main.lua"
    },
    "position": {
      "line": 2,
      "character": 12
    }
  }
}
```

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "textDocument/definition",
  "params": {
    "textDocument": {
      "uri": "file:///Users/dingyi/example-workspace/main.lua"
    },
    "position": {
      "line": 2,
      "character": 12
    }
  }
}
```

The expected definition target for the `TextView` provider is pending TASK-043 confirmation, but current implementation intent is a synthetic URI such as:

```text
file:///__jvm__/classes/android/widget/TextView.lua
```

## Serialized Verification Harness

This repository uses a serialized verification phase for commands that invoke Gradle, compile, tests, or build-output-writing work. Documentation and implementation workers must not run those commands during parallel waves.

TASK-043 is the current serialized verification task. It owns the Gradle/test execution slot and must hold the relevant locks before running verification commands. The LSP-related filters that should reconcile this page include the lifecycle/diagnostics, navigation/symbols, workspace-folder, watched-files, URI-handling, and Android-Lua end-to-end LSP tests under `src/jvmTest/kotlin/lsp`.

Representative verification commands are documented as deferred examples only:

```bash
export JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home
./gradlew compileTestKotlinJvm
./gradlew jvmTest --tests lsp.LspLifecycleDiagnosticsTddTest
./gradlew jvmTest --tests lsp.LspNavigationSymbolsTddTest
./gradlew jvmTest --tests lsp.LspWorkspaceFoldersTddTest
./gradlew jvmTest --tests lsp.LspWatchedFilesTddTest
./gradlew jvmTest --tests lsp.LspUriHandlingTddTest
./gradlew jvmTest --tests lsp.LspAndroidLuaE2eTddTest
```

Do not run those commands outside the serialized verification phase. The final green audit and production-readiness statement are pending TASK-037 after TASK-043 and the focused documentation tasks have completed.

## Current Limitations

- Folder indexing runs during initialization for real directory workspace folders (or a `rootUri` fallback) and is limited to `.lua` and `.aly` files. External disk create/change/delete is refreshed only when the client sends `workspace/didChangeWatchedFiles`; the server does not install its own watchers.
- URI path normalization for Unix absolute paths, Windows drive letters, and percent-encoded segments is incomplete and pending TASK-161 hardening.
- Reflected JVM providers expose public reflection surfaces only. Generic signatures, annotations, JavaDoc, Android API-level metadata, hidden APIs, and runtime side effects are not modeled.
- Android support reads `android.jar` metadata and does not emulate Android runtime behavior, resources, devices, dex loading, or app class loader semantics.
- Wildcard package enumeration is shallow and classpath-dependent. It exposes directly loadable top-level classes, not a complete Android or JVM package index.
- Synthetic provider URIs are virtual and may appear in definitions, declarations, references, and workspace symbols.
- Configuration is supplied through `workspace/didChangeConfiguration`; current code does not read `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or editor-specific setting names directly.
- Launch, LSP transport behavior, and pending TDD fixture coverage must be confirmed in TASK-043 before TASK-036 uses this page for final production-readiness documentation.
