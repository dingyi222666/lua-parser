# Android-Lua Corpus Verification

Prepared by TASK-054 on 2026-06-08. Host android.jar dual-path policy refreshed by TASK-453 (WAVE36E) on 2026-07-11.

This page explains how the integration corpus manifest should be used by TASK-035 and by the later serialized TASK-043 verification run. It is documentation only; TASK-054 / TASK-453 did not run Gradle, tests, compile commands, or build-output-writing commands.

## Inputs

Manifest:

```text
src/jvmTest/resources/integration/androidlua/corpus-manifest.md
```

Default external source root (present on this host as of 2026-07-11; re-clone before corpus acceptance if missing):

```text
/Users/dingyi/projects/java_projects/Android-Lua/app/src/main
```

Observed external source commit:

```text
686a792dbdd2fe9727a34768ceadffcaa2abc20d
```

### Host `android.jar` dual-path policy (TASK-453)

Verification and JVM metadata must use **only** these macOS host candidates. Never hard-code Windows `G:/` (or any other non-host) paths.

| Priority | Path | Role | Host status (2026-07-11 WAVE36E re-check) |
| --- | --- | --- | --- |
| 1 (preferred for reproducible analysis) | Explicit metadata `jvm.androidJar` | Caller-supplied single platform jar | Set to the SDK path below when configuring tests |
| 2 | `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar` | Android SDK Platform 35 jar | **Present** (~27,092,450 bytes) |
| 3 (optional copy) | `/Users/dingyi/Downloads/android.jar` | Operator drop-in copy of a platform jar | **Absent** on this host; allowed when present |

Preferred SDK path block:

```text
/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar
```

Optional Downloads path block (use only when the file exists; do not invent classpath entries for a missing file):

```text
/Users/dingyi/Downloads/android.jar
```

Dual-path resolution guidance for corpus / integration rows:

1. If the test or harness already receives `jvm.androidJar`, keep that value (must still be one of the two host paths above, or another operator-chosen real jar on the machine under test — never `G:/`).
2. Else prefer the SDK android-35 path when `File.isFile` is true.
3. Else use `/Users/dingyi/Downloads/android.jar` only when that file exists.
4. If neither host path exists, follow `docs/android-platform-setup.md` skip/unavailable behavior for Android framework assertions; JDK-only rows may still run.

The manifest records upstream paths, feature tags, verification focus, and deferred runtime patterns. It deliberately avoids vendoring the upstream Lua, `.aly`, Java, binary, media, and native sources.

## TASK-035 Usage

TASK-035 implements `src/jvmTest/kotlin/integration/AndroidLuaCorpusSemanticVerificationTest.kt` against the manifest. The test loads source files from the external Android-Lua checkout at runtime and keeps the upstream tree read-only.

Recommended root resolution:

1. Use a test metadata/env override if provided: system property `androidLua.main` or environment variable `ANDROID_LUA_MAIN`.
2. Otherwise default to `/Users/dingyi/projects/java_projects/Android-Lua/app/src/main`.
3. If the root is missing, fail with a clear message that names the expected root and this manifest.

Implemented manifest handling:

- Treat the Markdown tables as the human-maintained fixture manifest and parse rows whose first column is a backticked corpus ID.
- For `lua` rows, read the upstream `.lua` file and run parser plus Android-Lua semantic/workspace analysis.
- For `aly` rows, read the upstream `.aly` file as Lua source. These files should be treated as layout-table chunks that return a table.
- For `java-anchor` rows, do not parse Java as Lua. Use them to choose representative JVM/Android metadata assertions and LSP probes.
- Keep the external tree read-only. The integration test must not write derived fixtures back into `/Users/dingyi/projects/java_projects/Android-Lua`.

Implemented TASK-035 checks:

- Manifest integrity: unique IDs, Lua rows, `.aly` rows, Java anchor rows, recovery tags, Android interop tags, and source-file existence.
- Parser gate: strict rows parse with Android-Lua 5.3 recovery disabled; recovery rows strict-fail, then parse with recovery enabled and must report recovery output or bad AST markers.
- Workspace/semantic gate: representative external runtime, asset, helper, module, and layout sources build through `WorkspaceSemanticHarness` and `JvmWorkspaceEngine` with Android/JVM metadata.
- Java/Android interop gate: representative provider surfaces exist for `java.io.File`, `java.util.ArrayList`, `android.widget.TextView`, and `android.view.View$OnClickListener`.
- LSP gate: real `assets/main.lua` and `assets/layout.aly` open in `LuaLanguageService`; hover, completion, definition, workspace symbol, and document symbol queries exercise imported Android symbols.

Minimum test matrix:

| Manifest tag | TASK-035 expectation |
| --- | --- |
| `parser.strict` | Parse without recovery diagnostics. |
| `parser.recovery` | Parse without crashing and assert stable recovery diagnostics or recovered syntax markers. |
| `semantic.import` | Record `require "import"` activation, explicit imports, wildcard prefixes, and default Android-Lua import prefixes. |
| `semantic.require` | Resolve known Android-Lua helper modules and dotted modules such as `socket.url`. |
| `semantic.layout` | Recognize layout table shapes, nested children, id fields, listener fields, and `.aly` return tables. |
| `semantic.activity` | Expose `activity` as an Android-Lua context value with activity/helper methods. |
| `semantic.service` | Expose service-compatible context behavior where files use `activity or service`. |
| `interop.luajava` | Resolve `luajava.bindClass`, Java class values, class calls, and listener proxy candidates permissively. |
| `interop.android` | Resolve Android framework and bundled Android-Lua widget class names when `jvm.androidJar` and class indexes are configured. |
| `interop.java` | Resolve JDK classes from the running Java 17 runtime or configured JVM metadata. |
| `helper.stub` | Confirm TASK-044 Android-Lua helper declarations participate in analysis. |
| `lsp.*` | Use a small subset for hover, completion, definition/declaration, and diagnostics probes. |

Suggested metadata for Android/JVM-aware rows (host dual-path: prefer SDK android-35; optional Downloads when present; never `G:/`):

```kotlin
val metadata = mapOf(
    "jvm.androidJar" to "/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar",
    "jvm.importPrefixes" to listOf(
        "java.lang",
        "java.util",
        "java.io",
        "android.app",
        "android.content",
        "android.view",
        "android.widget",
        "com.androlua"
    ).joinToString("\n"),
)
```

Optional Downloads form when that file exists on the operator machine:

```kotlin
val metadata = mapOf(
    "jvm.androidJar" to "/Users/dingyi/Downloads/android.jar",
    // ... same import prefixes as above ...
)
```

The exact API for enabling Android-Lua overlays may change as TASK-033/TASK-034 land. The integration test should prefer the current workspace/LSP configuration path already used by the repository rather than adding a second fixture loader.

## Parser Recovery Fixture Boundaries

TASK-145 reconciles the historical all-red recovery backlog with the current parser fixture contract. After TASK-180 and the green focused recovery filters, the fixture inventory has no remaining production-blocked required-recovery cases. The focused recovery tests now classify malformed parser inputs into two groups:

- Required recovery cases live in `LuaParserRecoveryTddTest` and must keep later statements reachable, keep recovered AST shape deterministic, preserve expected bad-node markers, preserve structured `LuaParser.parseWithDiagnostics` warning fragments where the parser currently emits them, and either strict-fail deterministically or be called out as a current strict-mode gap.
- Intentionally rejected boundaries are also explicit in `LuaParserRecoveryTddTest`. They cover inputs outside normal source recovery, such as unmatched top-level block terminators, unattached Android-Lua `case` clauses, and Android-Lua array syntax in plain Lua 5.3 mode. These are not production acceptance gaps unless review creates a separate parser policy task.
- The Android-Lua incomplete-call fixture (`view:setText(` followed by `activity.setContentView(view)`) remains a required recovery case, not an intentionally rejected boundary. It is directly asserted in supported recovery coverage with required recovered AST shape `CallStmt(Call(Member(Id(activity).setContentView):Id(view)))`, bad call marker `Call(Member(Id(view):setText):)`, and the expected `')' expected` recovery warning fragment. TASK-145 does not weaken that acceptance requirement.
- TASK-180 moved `function body missing closing paren should keep return` out of the production-blocked inventory and into supported recovery coverage. Review verification has confirmed `LuaParser.parseWithDiagnostics` keeps `Return(Id(a))` reachable and emits the expected `) expected` structured warning for `function broken(a\nreturn a\nend`.

Current strict-mode gap note: two assignment RHS recovery fixtures (`a =` followed by a later statement, and `a, b = 1,` followed by a later statement) still parse under strict mode while recovery mode marks the missing expression and keeps the following statement reachable. This does not weaken production acceptance; it is recorded so TASK-043 can report whether the behavior is still current when it runs focused recovery verification.

Current recovery shape note: the `a, b` missing-equals fixture expects the current resynchronization markers `Assign(Id(a),ExpressionNodeSupport=)` and `Call(Id(b):)`. TASK-043 should report drift if parser recovery later keeps `Id(b)` inside the assignment instead, but that is an expectation change rather than an intentionally rejected malformed input.

Current structured-diagnostic note: the resource-backed mixed Android-Lua recovery fixture keeps required bad-node markers for the broken member, malformed `when` branch call, and lambda placeholder, but no longer asserts missing-token warning text because the structured diagnostics stream is currently empty for those malformed constructs. TASK-043 should report diagnostic drift separately from recovered AST reachability.

## TASK-043 Usage

TASK-043 is the serialized verification phase. It should run the integration test only after the parallel implementation/docs wave has finished and all wave locks are released or formally reclaimed.

Required deferred command from TASK-054 and TASK-035 (macOS host preferred form with Corretto 17):

```bash
JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home ./gradlew jvmTest --tests integration.AndroidLuaCorpusSemanticVerificationTest
```

TASK-043 must follow `docs/serialized-verification.md`:

- Hold the serialized verification task lock plus build, `.gradle`, daemon, and task-progress locks.
- Run one Gradle command at a time.
- Record whether this integration command passes, fails because production behavior is incomplete, fails because test expectations need adjustment, or fails because of infrastructure.
- Do not edit TASK-035 test code, source code, or this manifest during verification unless a review agent creates or expands a separate implementation task.

TASK-145 focused recovery verification for TASK-043:

```bash
JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home ./gradlew jvmTest --tests parser.recovery.LuaParserRecoveryTddTest
JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home ./gradlew jvmTest --tests parser.ParserRecoveryRegressionTest
```

When recording those results, call out any mismatch in the required-recovery inventory, the intentionally rejected boundaries, the empty production-blocked inventory, the structured recovery diagnostics, the assignment missing-equals bad-shape markers, the now-supported mixed Android-Lua incomplete-call recovery case, the now-supported function-body missing-closing-paren recovery case, or the two strict-mode assignment RHS gaps separately from broader Android-Lua corpus failures. If the mixed Android-Lua incomplete-call fixture or the function-body missing-closing-paren fixture regresses and throws before recovery, route that as parser implementation scope rather than weakening the required recovery inventory.

## Deferred Runtime Patterns

The corpus intentionally includes patterns that static verification should identify without executing:

- Android UI creation, `loadlayout` view construction, menu mutation, adapter rendering, toast/dialog display, and lifecycle callbacks.
- Filesystem mutation, APK packaging, zip/signing output, install intents, and project/plugin discovery writes.
- Network, HTTP/socket, logcat process access, clipboard reads/writes, and package manager calls.
- Dynamic dex/jar/native library loading and LuaJava native calls.
- Dynamic `loadstring`, `loadfile`, and `dofile` side effects.
- Exact Java overload resolution and Android resource lookup.
- Nonstandard Android-Lua `switch`/`case` syntax in rows tagged `parser.recovery`.

These are acceptance boundaries, not missing fixture work. TASK-035 should assert graceful parse/analysis/LSP behavior around them, while deeper runtime execution belongs outside this repository's static parser/semantic verification.

## Troubleshooting

- If `/Users/dingyi/projects/java_projects/Android-Lua/app/src/main` is missing, restore or reclone the Android-Lua checkout before TASK-043 runs.
- If `android.jar` is missing on a given machine, use the fallback guidance in `docs/android-platform-setup.md`; JDK-only rows can still resolve, but Android framework assertions should be marked unavailable or skipped with a clear reason. On this macOS host (2026-07-11 WAVE36E / TASK-453 re-check): SDK path `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar` is **present** (~27,092,450 bytes); optional dual-path `/Users/dingyi/Downloads/android.jar` is **absent** and is not required when the SDK path exists. Never hard-code `G:/`.
- If the external Android-Lua checkout is at a different commit, record the observed commit in TASK-035/TASK-043 progress and treat unexpected parse or semantic differences as corpus drift.
- If a strict row requires parser recovery, first check whether the external source changed. Do not silently reclassify rows without a review-created task updating this manifest.
