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
- Config: `jvm.androidJar` auto-detected from `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or standard host SDK locations

## Try

1. Click **Start LSP**; `main.lua` (or the first project file) opens after initialization
2. Hover identifiers, Ctrl/Cmd-click definitions, type `.` / `:` for completion
3. Open other project files from the sidebar as needed

Env overrides: `PORT`, `JAVA_HOME`, `ANDROID_JAR`, `LUA_PARSER_ROOT`.

Run `npm run smoke` to build/start the real Gradle LSP path, initialize the full
demo workspace, open its entry file, receive diagnostics, and shut down cleanly.
