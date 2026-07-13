# Monaco + lua-parser LSP demo

Local webpage (Monaco Editor) talking to the JVM language server via a small Node WebSocket bridge.

## Prerequisites

- JDK 17 (`JAVA_HOME`)
- Node.js 18+
- Built JVM jar: `bash ./gradlew.unix jvmJar` from repo root

## Run

```bash
# from repo root
bash ./gradlew.unix jvmJar
cd tools/monaco-lsp-demo
npm install
node scripts/write-classpath.mjs   # optional but recommended
npm start
```

Open **http://127.0.0.1:3099/**

## Architecture

```
Browser (Monaco)  --WS JSON-RPC-->  Node bridge  --stdio Content-Length-->  JVM LSP
```

- Server: `io.github.dingyi222666.luaparser.lsp.LuaLanguageServerLauncherKt`
- Workspace samples: `tools/monaco-lsp-demo/workspace/*.lua`
- Config: `jvm.androidJar` auto-detected from host SDK android-35 when present

## Try

1. Click a file in the sidebar (or wait for auto-open of `main.lua`)
2. Hover identifiers, Ctrl/Cmd-click definitions, type `.` / `:` for completion
3. Open `broken.lua` for diagnostics
4. Open `android_sample.lua` when android.jar is present for interop surface

Env overrides: `PORT`, `JAVA_HOME`, `ANDROID_JAR`, `LUA_PARSER_ROOT`.
