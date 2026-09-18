# AndroLua Lua Support (VSCode)

A **thin launcher** extension: all language intelligence lives in the
[luaparser](https://github.com/dingyi222666/luaparser) JVM language server
(`io.github.dingyi222666.luaparser.lsp.LuaLanguageServerLauncherKt`), which this
extension spawns and drives over LSP stdio.

Features come straight from the server: diagnostics, completion, hover,
signature help, definition/declaration, references, document highlight,
document & workspace symbols, rename, call hierarchy, folding ranges,
selection ranges, semantic tokens, inlay hints, and formatting — for `.lua`
and `.aly` (AndroLua layout) files.

## Prerequisites

- VSCode ^1.85.0
- A JDK 11+ (`java` on PATH, or point `luaparser.javaPath` at one)
- Node/npm (only for installing the extension's npm dependency)

## 1. Build the language server jar

From the repository root:

```sh
./gradlew jvmJar
```

This produces `build/libs/luaparser-jvm-<version>.jar`.

**Important — the published jar is a *thin* jar** (no `Main-Class` manifest
attribute, dependencies not bundled). `java -jar` on it fails. The extension
therefore launches via classpath when it finds a `lib` folder of dependency
jars next to the configured jar. Stage one with this init script (works without
modifying any repo build file):

```sh
cat > /tmp/stage-lsp-deps.gradle <<'EOF'
rootProject {
    tasks.register("stageLspServerDeps", Copy) {
        dependsOn tasks.named("jvmJar")
        from configurations.getByName("jvmRuntimeClasspath")
        into layout.buildDirectory.dir("libs/lib")
    }
}
EOF
./gradlew -I /tmp/stage-lsp-deps.gradle stageLspServerDeps
```

Resulting layout (matches the default `luaparser.jarPath`):

```
build/libs/luaparser-jvm-1.0.4.jar
build/libs/lib/*.jar          <- kotlin-stdlib, lsp4j, gson, ...
```

If no `lib` folder exists, the extension falls back to `java -jar <jarPath>`
(works with fat jars only).

## 2. Install the extension

```sh
cd editors/vscode
npm install                      # vscode-languageclient
npx @vscode/vsce package         # or: npm run package (requires vsce)
```

Then in VSCode: *Extensions* view → `...` → *Install from VSIX...* and pick the
generated `.vsix`.

For development, open the `editors/vscode` folder in VSCode and press F5
("Run Extension") to launch an Extension Development Host.

## 3. Configuration

| Setting | Default | Description |
| --- | --- | --- |
| `luaparser.jarPath` | `build/libs/luaparser-jvm-1.0.4.jar` | Server jar. Relative paths resolve against the first workspace folder, then the extension directory. |
| `luaparser.javaPath` | `java` | Java launcher (JDK 11+). |
| `luaparser.jvmArgs` | `[]` | Extra JVM args, e.g. `["-Xmx2g"]`. |
| `luaparser.serverSettings` | `{}` | Forwarded verbatim to the server via `workspace/didChangeConfiguration` (see below). |

The exact launch command constructed (stdio transport, no CLI args needed —
the workspace reaches the server inside the `initialize` request):

```
# with build/libs/lib present (current artifact):
<javaPath> <jvmArgs...> -cp <jarPath><:|;><libDir>/* io.github.dingyi222666.luaparser.lsp.LuaLanguageServerLauncherKt

# without it:
<javaPath> <jvmArgs...> -jar <jarPath>
```

## 4. Android / AndroLua (dex and android.jar mounting)

Java interop features need the Android surface mounted. Set
`luaparser.serverSettings` in your workspace/user settings — keys are read by
the server's `workspace/didChangeConfiguration` handler:

```jsonc
{
  "luaparser.serverSettings": {
    "jvm.androidJar": "/path/to/android.jar",     // or a dex/classpath mount
    "jvm.classpath": ["/path/to/more/classes.jar"],
    "jvm.importPrefixes": ["android.widget", "android.view"],
    "androlua.imports": ["android.app.*"],
    "lua.layout.properties": "android.widget.Button: text|onClick"
  }
}
```

Changes apply live (the extension re-pushes settings; the server rebuilds).

## Known limits

- **Dex mounting is manual**: the server only sees Android APIs if the
  dex/android.jar path is provided via `luaparser.serverSettings` (above);
  there is no automatic SDK discovery.
- The default jar is a thin jar — the `lib` staging step in section 1 is
  required until a fat jar artifact is published.
- Relative `luaparser.jarPath` uses the *first* workspace folder; multi-root
  setups should configure an absolute path.
- Semantic token colors follow VSCode's standard TextMate/semantic theming;
  no custom token legend configuration is exposed.
- No formatter configuration (the server's built-in formatting is used as-is).
