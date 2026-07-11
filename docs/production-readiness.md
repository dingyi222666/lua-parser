# Production Readiness Guide

This guide is the top-level operating map for the current lua-parser repository. It summarizes setup, supported workflows, Android-Lua inputs, JVM interop configuration, language-server usage, extension points, and validation boundaries without expanding compatibility claims beyond the current implementation wave.

Final Gradle/test confirmation is deferred to TASK-043, and final acceptance audit is deferred to TASK-037. During coordinated worker waves, treat every Gradle or test command in this document as a reproducible command for the serialized verification worker, not as permission to run it from a parallel implementation or documentation task.

## Readiness Status

The repository currently contains:

- A Kotlin Multiplatform Lua parser and source round-trip utility centered on Lua 5.3 syntax with Android-Lua recovery support in focused parser lanes.
- A `SemanticPipeline` API that backs both the current semantic model and legacy compatibility adapters.
- Workspace analysis through `LuaWorkspaceEngine`, `LuaWorkspaceQueryFacade`, builtin overlays, module graph resolution, and Android-Lua helper metadata.
- JVM-only reflective interop through `JvmWorkspaceEngine`, `JvmWorkspaceConfiguration`, `JvmClassModuleProvider`, configured jars/directories, optional `android.jar`, and Android-Lua/LuaJava import facts.
- A JVM LSP4J server under `io.github.dingyi222666.luaparser.lsp` for diagnostics, hover, completion, signature help, navigation, highlights, and symbol queries over opened documents and, where implemented, indexed workspace folders.
- Documentation and fixtures for Android-Lua corpus verification, compatibility scope, helper-library modeling, parser/test strategy, serialized verification, and platform setup.

**This repository is not finally green.** Do not treat partial focused-filter accepts, worker `done` statuses, or this guide as a release statement. TASK-043 must run the serialized Gradle/test suite and TASK-037 must record the final acceptance audit before any global green claim is valid.

### Production readiness gap checklist (TASK-501 refresh, post-TASK-184 / pre-TASK-043)

Snapshot date: 2026-07-12 (docs-only inventory criterion note from live task metadata and host path checks; no Gradle/tests run by this refresh). Supersedes the TASK-451 gap checklist.

Inventory criterion (not final until TASK-043): source-count drafts and TASK-125 recounts may reconcile file/`@Test` bars, but inventory is **not final** until serialized verification accepts those bars under TASK-043. Do not treat raised inventory constants or docs-only recount notes as green suite evidence.

Host paths (read-only checks for this macOS machine; never hard-code `G:/`):

| Path | Purpose | Status |
| --- | --- | --- |
| `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar` | Preferred host `android.jar` (SDK android-35) | **Present** (~27,092,450 bytes) |
| `/Users/dingyi/Downloads/android.jar` | Alternate host `android.jar` | **Absent** |
| `/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home` | Coordinated-wave JDK 17 for verification docs | **Present** |

| Area | Current state | Notes |
| --- | --- | --- |
| Global green / release | **Not green** | Final gates TASK-043 (serialized verification) and TASK-037 (acceptance audit) remain **blocked**. TASK-184 acceptance does **not** unlock or satisfy TASK-043. |
| Final verification ledger | **Blocked chain** | TASK-105 blocked on TASK-043; TASK-106 blocked on TASK-105. Pre-verification inventory drafts may refresh source counts only. |
| Pre-verification inventory | **Open pre-final path** | TASK-125 is **review** after WAVE36F live recount (baseline 331 `*Test.kt` / 4624 `@Test`; campaign 272 `*TddTest.kt` / 4109 `@Test`). Inventory is **not final until TASK-043**. TASK-038 is **ready** and still depends on TASK-125. |
| Pre-verification traceability | **Done draft (not final)** | TASK-115 is **done** for the broader pre-verification AC matrix refresh after the TASK-085–TASK-184 wave. Final AC pass/fail remains reserved for TASK-105/TASK-106 after TASK-043. |
| Acceptance traceability draft | May lag live task files | Prefer this checklist and individual task files for current blocked/open state until TASK-106 finalizes the matrix after serialized verification. |

#### Closed product gates (post-TASK-184; still not global green)

These formerly open dependency rows are **done** at task level. They reduce product gaps and unblocked the pre-final inventory/trace path, but they are **not** a substitute for TASK-043 command evidence:

| Task | Title | Task status |
| --- | --- | --- |
| TASK-160 | Hierarchical document symbols / modern workspace symbols | done |
| TASK-161 | File URI normalization cross-platform | done |
| TASK-164 | Final verification ledger inventory snapshot | done |
| TASK-170 | Android-Lua LSP E2E fixture expectations | done |
| TASK-176 | Android-Lua import surfaces under scoped activation | done |
| TASK-177 | JavaBean aliases in member surfaces | done |
| TASK-178 | Mixed Android-Lua incomplete-call parser recovery | done |
| TASK-184 | Android-Lua library stub fixture/type surfaces | done |
| TASK-115 | Pre-verification traceability draft | done |

#### Review-accepted increments (not global green)

These tasks were **accepted by REVIEW19-WAVE-20260711-031147** with focused serial filters only. They reduce specific product gaps but **do not** clear TASK-043/TASK-037:

| Task | Title | Focused evidence (review-owned) |
| --- | --- | --- |
| TASK-144 | Normalize compact short-call AST and roundtrip coverage | `compileTestKotlinJvm`; `parser.ast.CompactCallAstShapeTddTest`; `source.AST2LuaRoundTripTest` |
| TASK-152 | Model listener setter interface assignment | `semantic.interop.JavaChainedCallTddTest` |
| TASK-156 | Index workspace folders for LSP snapshots | `lsp.LspWorkspaceFoldersTddTest`; `lsp.LspNavigationSymbolsTddTest` |

Related follow-ons that progressed after those accepts include TASK-171 (AndroLua switch case AST; later accepted) and workspace/LSP follow-ups released from the TASK-156 gate. Treat each as local evidence only.

#### Open blocked chains (authoritative gates remain closed)

| Blocked / open task | Role | Still-open direct deps or gate notes (non-done) |
| --- | --- | --- |
| TASK-043 | Serialized Gradle verification | Remains **blocked**. Historical product deps through TASK-184 are done at task level; pre-final inventory path still open: **TASK-125** (**review**, bars raised to 331/4624 and 272/4109; not accepted final until review + TASK-043) then **TASK-038** (**ready**, depends on TASK-125). Gate stays closed until that path completes and review releases verification. |
| TASK-037 | Final green acceptance audit | TASK-043, TASK-105, TASK-106 |
| TASK-105 | Final verification ledger refresh | TASK-043 |
| TASK-106 | Finalize acceptance traceability | TASK-105 |
| TASK-125 | Final pre-verification inventory counts | **review**; WAVE36F recount raised bars (331/4624 baseline, 272/4109 campaign). **Inventory not final until TASK-043.** |
| TASK-038 | Inventory compile / assertion alignment | **ready**; depends on TASK-125 |

#### Explicit non-claims

- No claim that `./gradlew check` or any full suite currently passes.
- No claim that the 500-test campaign inventory is finally reconciled or verified at command level (source-count drafts may already exceed 500 methods; that is not green).
- No claim that Android-Lua corpus, LuaJava interop, workspace, or LSP surfaces are release-complete.
- TASK-184 library stub restoration is **done** for product surface work only; it must not be rolled up into a global green statement.
- Focused PASS notes for TASK-144/152/156 are **local** accepts only; they must not be rolled up into a global green statement.
- TASK-125 inventory recount in **review** is a pre-final source-count refresh only; it is **not** final inventory acceptance and does **not** unlock TASK-043 by itself.

## Setup

Use JDK 17 for local JVM verification and language-server work. The Gradle build configures the Kotlin JVM target at 11, but the current coordinated verification docs use a JDK 17 installation:

```bash
export JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
```

The published dependency coordinates in this repository are:

```kotlin
implementation("io.github.dingyi222666:luaparser:1.0.3")
```

For repository-local work, import the Gradle project from the repository root. During parallel worker waves, do not run Gradle, compile, test, build-output cleanup, or daemon-control commands. Use `docs/serialized-verification.md` for the lock and execution rules that TASK-043 must follow.

## Basic Parser Usage

The parser can parse from a source string or an explicit lexer. `AST2Lua` can serialize the parsed chunk back to Lua-like source:

```kotlin
import io.github.dingyi222666.luaparser.lexer.LuaLexer
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.source.AST2Lua

fun main() {
    val lexer = LuaLexer("print('hello world')")
    val chunk = LuaParser().parse(lexer)

    println(AST2Lua().asCode(chunk))
```

Parser coverage and fixture policy are documented in `docs/test-strategy.md`. Android-Lua corpus rows that intentionally require recovery are tracked through `docs/android-lua-verification.md` and the integration manifest under `src/jvmTest/resources/integration/androidlua/`.

## Parser Test Strategy And TDD Workflow

Parser, AST, recovery, semantic, workspace, interop, and LSP coverage is tracked in `docs/test-strategy.md`. New parser or Android-Lua grammar work should start with a focused `*TddTest.kt` fixture under `src/commonTest/kotlin` or `src/jvmTest/kotlin`, then implement the narrow production behavior needed by that test.

During coordinated worker waves, record the required Gradle command in the task file but do not execute it. TASK-043 owns serialized execution and must record pass/fail/infrastructure status. Outside coordinated waves, use the normal Gradle filters documented by the focused test or the broad `./gradlew check` path.

Keep fixture policy explicit:

- Small synthetic Lua snippets belong in focused parser or semantic tests.
- External Android-Lua source remains read-only and is selected through the corpus manifest.
- Test inventory accounting excludes infrastructure fixtures such as `testinventory.NewTestInventoryTddTest.kt`.

## Semantic Usage

Use `SemanticPipeline` for file-local semantic analysis:

```kotlin
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline

fun main() {
    val chunk = LuaParser().parse(
        """
        ---@type string
        local name = "lua"
        return name
        """.trimIndent()
    )

    val result = SemanticPipeline().analyze(chunk)
    val symbol = result.model.getSymbolAt(Position(2, 11))

    println(symbol?.name)
    println(result.summary.diagnosticCount)
```

Legacy semantic entry points are compatibility wrappers over the pipeline. See `docs/semantic-compat.md` before extending or removing compatibility behavior.

## Workspace And Android-Lua Source Usage

Use `LuaWorkspaceEngine` for cross-file Lua/module analysis. Use `JvmWorkspaceEngine` when Android-Lua or LuaJava source needs JVM class metadata:

```kotlin
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceQueryFacade
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath

fun main() {
    val path = VirtualPath.of("main.lua")
    val configuration = JvmWorkspaceConfiguration(
        androidJar = "/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar",
        androluaImports = listOf("TextView"),
        importPrefixes = listOf("java.lang", "android.widget")
    )
    val input = LuaWorkspaceInput(
        files = mapOf(
            path to """
            require "import"
            import "android.widget.TextView"
            local view = TextView(activity)
            return view
            """.trimIndent()
        ),
        metadata = configuration.applyToMetadata(emptyMap())
    )

    val snapshot = JvmWorkspaceEngine(configuration = configuration).build(input).snapshot
    val queries = LuaWorkspaceQueryFacade(snapshot)

    println(queries.diagnostics(path))
    println(queries.hover(path, Position(3, 15))?.typeInfo?.displayName)
```

Keep external Android-Lua source checkouts read-only. The current corpus docs use this default source root:

```text
/Users/dingyi/projects/java_projects/Android-Lua/app/src/main
```

The integration manifest identifies representative Lua, `.aly`, and Java-anchor rows. Do not vendor the upstream Android-Lua app into this repository unless a later task explicitly changes the fixture policy.

## Android Jar And JVM Classpath

Use `jvm.androidJar` for the Android platform jar and `jvm.classpath` for application or library jars/directories. Keep those inputs separate so the reflective provider can describe the target Android API level reproducibly.

Common metadata keys:

| Key | Purpose |
| --- | --- |
| `jvm.classes` | Explicit JVM classes to mount as synthetic providers. |
| `androlua.imports` | Android-Lua simple imports that should resolve through configured prefixes. |
| `jvm.classpath` | Newline-separated jars or class directories for reflection. |
| `jvm.androidJar` | Android platform jar path. |
| `jvm.importPrefixes` | Newline-separated prefixes used for simple-name import resolution. |

The local fallback `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar` is a convenience for this machine only. Production clients and verification runs should pass `jvm.androidJar` explicitly. See `docs/android-platform-setup.md`, `docs/java-interop-model.md`, and `docs/jvm-reflection-classloader-design.md` for the complete classpath and classloader behavior.

If the platform jar is missing, install the target SDK platform with Android Studio SDK Manager or `sdkmanager "platforms;android-35"`, then pass the exact platform jar path through `jvm.androidJar`. The current provider does not discover `ANDROID_HOME` or `ANDROID_SDK_ROOT` by itself.

## Language Server

The JVM language server entry point is:

```text
io.github.dingyi222666.luaparser.lsp.LuaLanguageServerLauncherKt
```

The repository also declares a Gradle helper:

```bash
./gradlew runLuaLanguageServer
```

That command is intentionally shown as a launch shape only during this documentation task. TASK-043 owns serialized command execution.

Clients configure Android-Lua and JVM metadata through `workspace/didChangeConfiguration` using the same keys listed above. Current implementation scope and message examples are documented in `docs/language-server-usage.md`.

Workspace-folder indexing for LSP snapshots is covered by accepted TASK-156 focused evidence only; broader lifecycle, Android-Lua E2E, and unopened-file policy follow-ups remain open and must not be described as release-complete.

## Compatibility Scope

Current compatibility should be read as static analysis support, not runtime emulation:

- Lua parser and AST utilities target Lua 5.3-oriented syntax, with focused Android-Lua grammar/recovery support where tests and docs identify it.
- Android-Lua support models import facts, helper overlays, `.aly` layout source shape, and representative corpus behavior for analysis.
- JVM interop reflects public class/member surfaces from the running JDK, configured classpath entries, and `android.jar`; it does not execute Java or Android code.
- LSP behavior is workspace-snapshot based. Opened documents plus configured metadata remain the primary surface; folder indexing from TASK-156 is present but still subject to open follow-up tasks and final TASK-043 verification.

The compatibility matrix and known unsupported patterns live in `docs/android-lua-compatibility-matrix.md` and `docs/android-lua-verification.md`.

## Known Limitations

- Final green verification has not been recorded in this guide; TASK-043 and TASK-037 are still the authoritative verification gates and remain blocked (see gap checklist above).
- Android resource lookup, Android runtime lifecycle execution, dex/native loading, filesystem mutation, network calls, package-manager behavior, and UI side effects are outside static analysis scope.
- Java overload resolution, generics, annotations, JavaDoc, hidden APIs, and Android API-level availability are modeled conservatively.
- Wildcard import package enumeration depends on the configured classloader, classpath entries, jars, directories, and JRT modules available to the JVM process.
- Dynamic class names, dynamic `loadfile`/`dofile`/`loadstring` side effects, and non-literal helper targets are not treated as fully resolvable static facts.
- LSP synthetic provider URIs such as `file:///__jvm__/classes/java/lang/String.lua` are virtual locations, not files on disk.
- Compact short-call AST/round-trip (TASK-144), listener setter assignment modeling (TASK-152), and workspace-folder indexing (TASK-156) have focused review accepts only; they are not substitutes for full-suite green.
- Android-Lua library stub fixture/type surfaces (TASK-184) are restored at task level only; command-level suite proof remains pending TASK-043.
- Pre-verification inventory recount (TASK-125) may raise source-count bars while remaining **not final until TASK-043**; do not treat inventory docs as suite green.

## Extension Guide

Prefer the existing extension points when adding behavior:

- Parser grammar and recovery: extend `LuaParser` with focused parser or recovery tests and document Android-Lua-only syntax separately from standard Lua.
- Semantic checks: add binder/checker/type-resolution behavior through `SemanticPipeline` passes rather than adding a second semantic path.
- Workspace facts: update `DocumentFactsCollector` and `LuaWorkspaceEngine` together so collected facts affect module/provider resolution when intended.
- Android-Lua overlays: update the builtin overlay resources and loader tests so helper globals, modules, and stubs remain reproducible.
- JVM interop: extend `JvmWorkspaceConfiguration`, `JvmClassModuleProvider`, and `JavaInteropTypes` together when richer Java identity is required.
- LSP: route new request behavior through `LuaWorkspaceQueryFacade` first, then adapt it in the JVM LSP service layer.
- Documentation: update the focused page for the subsystem and keep this guide as the concise cross-linking map.

Every extension that changes behavior should add or update focused tests and record its required Gradle command in the owning task. In coordinated waves, execution of those commands remains deferred to the serialized verification task.

## Verification Map

Use these pages when preparing or auditing verification:

- This guide's **Production readiness gap checklist** for the current blocked-chain snapshot, closed post-TASK-184 product gates, inventory-not-final-until-043 criterion, and the TASK-144/152/156 local accepts.
- `docs/serialized-verification.md` for one-at-a-time Gradle/test execution and locks.
- `docs/test-strategy.md` for inventory and TDD accounting rules.
- `docs/android-lua-verification.md` for corpus inputs and the Android-Lua integration gate.
- `docs/language-server-usage.md` for LSP filters and expected request surface.
- `docs/android-platform-setup.md` for Android SDK and `android.jar` setup.
- `docs/final-verification.md` for the final verification ledger draft (inventory snapshots only until TASK-043).
- `docs/acceptance-traceability.md` for the broader AC matrix draft (TASK-115 done for pre-verification refresh; final pass/fail reserved for TASK-105/TASK-106 after TASK-043).

Outside coordinated waves, `./gradlew check` remains the broad local validation shape documented by the README. Inside this worker wave, all Gradle/build/test/compile verification is deferred to TASK-043. Until TASK-043 and TASK-037 complete, do not interpret any documentation page as a global green release certificate.
