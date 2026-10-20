# Monaco + lua-parser LSP demo

Local webpage (Monaco Editor) talking to the JVM language server via a small Node WebSocket bridge.

## Prerequisites

- JDK 17 (`JAVA_HOME`)
- Node.js 18+

## Run

```bash
# from repo root
cd tools/monaco-lsp-demo
npm ci
npm start
```

The bridge starts the language server through the repository Gradle wrapper, so
the JVM jar and runtime classpath are built/resolved automatically. No generated
`.lsp-classpath` file is required. It uses `JAVA_HOME` when that points to JDK 17,
otherwise it searches standard JDK locations; on Windows it uses `gradlew.bat`.

Open **http://127.0.0.1:3099/**

## Architecture

```
Browser (Monaco)  --WS JSON-RPC-->  Node bridge  --stdio Content-Length-->  JVM LSP
```

- Server: `io.github.dingyi222666.luaparser.lsp.LuaLanguageServerLauncherKt`
- Workspace: recursive `.lua` / `.aly` files under `tools/monaco-lsp-demo/workspace`
- Config: `jvm.androidJar` auto-detected from `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or standard host SDK locations; `jvm.classpath` (dex) auto-injected by the bridge from `workspace/libs/classes.dex` — see "Dex support" below

## Try

1. Click **Start LSP**; `main.lua` (or the first project file) opens after initialization
2. Hover identifiers, Ctrl/Cmd-click definitions, type `.` / `:` for completion
3. Document symbols: press Ctrl/Cmd+Shift+O (quick outline) to browse functions/
   variables of the active file — served by
   `textDocument/documentSymbol` (hierarchical or flat, both mapped)
4. Folding: collapse multi-line functions and table constructors via the gutter arrows —
   served by `textDocument/foldingRange`
5. Semantic tokens: beyond grammar highlighting, the server's
   `textDocument/semanticTokens/full` recolors variables/parameters/strings/numbers/
   comments using the legend advertised at initialize
6. Open other project files from the sidebar as needed; **↻ Refresh** re-reads the
   workspace and closes tabs whose files no longer exist on disk

> Semantic tokens require the demo's `semanticHighlighting: true` editor option
> (already set) plus the provider registration, which happens right after the
> initialize result delivers the legend.

### Diagnostics you will see

`mods/dingyi.lua` carries a real catch: an unresolved global `h` (a typo for
the `w` parameter). `main.lua` used to ship a second intentional INFO marker:
its `android.support.v7.widget.*` wildcard mounted zero classes because the
support library lives in the workspace's own `libs/classes.dex`, which the
analyzer did not load. With dex support wired (see below), those members now
resolve from the dex, so that marker no longer appears.

### AndroLua layout (.aly) completions

`.aly` files and `loadlayout({...}, ids)` tables are layout-aware:

- Property keys come from the enclosing view class (`adapter` on `ListView`,
  `textSize`/`textColor` on `TextView`, `radius`/`cardElevation` on `CardView`) plus the
  loadlayout special keys (`id`, `style`, `onClick`, `src`, `items`) and LayoutParams keys.
- String values complete from loadlayout's value domains: `orientation = ""` offers
  `vertical`/`horizontal`; `layout_width = ""` offers `wrap`, `fill`, `match`, `-1`, `-2`,
  dp and `%w`/`%h` sizes; `gravity`/`inputType`/`scaleType`/... have their token lists.
- `require("...")` / `import "..."` strings complete workspace module names.
- `id = "name"` registers a typed view field on the ids table (`tab.poplist`).

String completion while typing: completion triggers are only `.` and `:` — typing
`"` does not pop the suggestion list (so closing a string never triggers spam).
Inside string literals, value completion appears as you type via
`quickSuggestions.strings`: after `orientation = "` the list offers
`vertical`/`horizontal`, after `require("` it offers workspace modules, etc.

### Custom view properties (embedders)

Workspace/LSP configuration key `lua.layout.properties` extends completions for
project-defined views, one class per line:

```
LuaRecyclerView: refresh|pull-to-refresh callback, loadMore|load-more callback
com.project.MyBanner: autoScroll|boolean
```

FQCN keys are also matched by simple name.

## Dex support

The demo workspace ships a real Android dex library at
`workspace/libs/classes.dex` (Dalvik 035, 929 classes — the same fixture the
`interop.jvm.DexRealFixtureTddTest` suite runs against). The Node bridge wires
it into the language server automatically: when forwarding
`workspace/didChangeConfiguration` it merges a `jvm.classpath` entry pointing
at that file into the settings, and the LSP mounts it as a dex library
(`JvmWorkspaceConfiguration` → `DexLibraryMounter`).

What works with the demo dex:

- `luajava.bindClass("com.example.SomeClass")` resolves classes from the dex,
  with the full member surface (`__class` instance shell, constructor `__call`,
  public static fields, methods)
- `import "libs/classes.dex:com.example.SomeClass"` — path-prefixed imports
  resolve the class inside that library
- bare `import "libs/classes.dex"` — mounts the library module plus per-class
  providers, so short class names resolve afterwards
- `import "android.support.v7.widget.*"`-style wildcards pick up the dex's
  support-library classes (previously diagnosed as mounting zero classes)

What to configure (the demo bridge does this for you; embedders send the same
keys themselves):

- LSP workspace-configuration key **`jvm.classpath`** in
  `workspace/didChangeConfiguration` settings: a newline-separated string or an
  array of paths; `.dex` and `.apk` entries mount as dex libraries (`.jar` and
  class directories stay on the reflective classpath)
- The bridge logs the resolved entry at startup
  (`dex classpath: ... present=true`), exposes it as `demoDex`/`demoDexPresent`
  on `GET /api/info`, and honors the `DEMO_DEX` env override; without the
  fixture it forwards configuration untouched

## Single editor session

Only one browser tab can hold the language server at a time. A second tab that
connects while another session is active receives a busy message from the bridge
("Another editor holds the language server") and its socket is closed — the
status bar shows the reason. Stop the session in the active tab (or close it)
before connecting from a new one.

Env overrides: `PORT`, `JAVA_HOME`, `ANDROID_JAR`, `DEMO_DEX`, `LUA_PARSER_ROOT`.

Run `npm run smoke` to build/start the real Gradle LSP path, initialize the full
demo workspace, open its entry file, receive diagnostics, and shut down cleanly.
The run also probes live wire coverage on the opened files:
`textDocument/completion` (dynamic table members on `table.` in `mods/dingyi.lua`
plus the `.aly` loadlayout string-value domain), `textDocument/documentSymbol`
(array result + name/kind shape), `textDocument/foldingRange`,
`textDocument/semanticTokens/full` (validated against the initialize legend),
`textDocument/references`, `textDocument/documentHighlight`,
`textDocument/selectionRange` (parent-chain shape), `textDocument/rangeFormatting`,
`textDocument/prepareRename` + `textDocument/rename` (WorkspaceEdit shape),
`textDocument/inlayHint`, and `workspace/symbol`.
