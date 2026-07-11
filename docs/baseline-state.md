<!-- Host path migration 2026-07-11/12: current host is macOS; Windows paths below in historical command transcripts were from the prior host. Current review JAVA_HOME is Corretto 17 under ~/Library/Java/JavaVirtualMachines/corretto-17.0.19. -->

# Baseline Repository State

This page has two layers:

1. **Current post-TASK-184 / pre-TASK-043 baseline note** (TASK-509 WAVE36F docs-only refresh) — host paths, inventory honesty, and gate status for the present macOS host.
2. **Historical TASK-001 capture** (2026-06-06) — original inventory transcript retained for audit trail. Treat numbers and dirty-file lists in that section as **historical**, not live-tree truth.

**Do not claim final green acceptance** from this document. Inventory is **not final until TASK-043**. TASK-043 remains **blocked**. No Gradle, tests, or compile were run for this refresh.

## Current Post-TASK-184 / Pre-TASK-043 Baseline Note (TASK-509, 2026-07-12)

### Honesty bound

| Item | Status |
| --- | --- |
| TASK-184 (Android-Lua library stub surfaces) | **done** (review-accepted). Unblocks product surface work only. |
| TASK-125 (pre-verification inventory bars) | **review** (not accepted green). Live recount bars: baseline **331** `*Test.kt` / **4624** `@Test`; campaign **272** `*TddTest.kt` / **4109** `@Test`. |
| TASK-038 | **ready** (depends on TASK-125). |
| TASK-043 (serialized Gradle verification) | **blocked**. Depends_on product wave through TASK-184 is done at task level, but inventory/trace path (TASK-125 → TASK-038) is still open. |
| TASK-105 / TASK-106 / TASK-037 | Final ledger / traceability / acceptance audit chain remains **blocked** on TASK-043. |
| Global green / release | **Not green.** Source-count campaign methods ≥ 500 is necessary for TASK-037's floor but is **not** command evidence. |

**Inventory is not final until TASK-043.** Raised fixture bars, docs-only recounts, and worker `review` statuses are not pass/fail suite evidence. Do not treat this baseline note as a release statement.

### Host paths (macOS; read-only checks)

Never hard-code Windows-only roots such as `G:/` or `C:\Users\dingyi\...` in new docs or product defaults. Historical Windows paths in the TASK-001 section below are transcripts only.

| Path | Purpose | Host check (2026-07-12 WAVE36F / TASK-509) |
| --- | --- | --- |
| `/Users/dingyi/projects/java_projects/lua-parser` | Repository root | Present |
| `/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home` | Primary coordinated-wave JDK 17 (`JAVA_HOME`) | **Present** (Amazon Corretto 17.0.19) |
| `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar` | Preferred host `android.jar` (SDK platform 35) | **Present** (~27,092,450 bytes) |
| `/Users/dingyi/Downloads/android.jar` | Alternate host `android.jar` (explicit metadata only) | **Absent** |
| `/Users/dingyi/Library/Android/sdk` | Typical `ANDROID_HOME` / `ANDROID_SDK_ROOT` when set | Present (platforms include `android-35`) |

Dual-path policy for `android.jar`:

1. Prefer SDK discovery / explicit `jvm.androidJar` pointing at `.../platforms/android-35/android.jar` when present.
2. `/Users/dingyi/Downloads/android.jar` is an optional alternate only when that file exists; it is **not** present on this host at inventory time.
3. Never invent a reflective classpath entry for a missing jar; skip reflective Android framework mounting when absent.
4. Never use `G:/` or other non-macOS roots as defaults on this host.

Verified Corretto 17 identity (no Gradle):

```text
/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home/bin/java -version
```

```text
openjdk version "17.0.19" 2026-04-21 LTS
OpenJDK Runtime Environment Corretto-17.0.19.10.1 (build 17.0.19+10-LTS)
OpenJDK 64-Bit Server VM Corretto-17.0.19.10.1 (build 17.0.19+10-LTS, mixed mode, sharing)
```

Default shell `java` on this host may be a newer OpenJDK (e.g. 26); verification docs still force Corretto 17 via `JAVA_HOME` as above. Project `jvmToolchain` / compile target remain JVM 11 in build files; that does not change the coordinated verification JDK.

### Live source-tree inventory (docs-only; not final)

Figures match the WAVE36F / TASK-125 worker recount and sibling docs (`docs/acceptance-traceability.md`, `docs/production-readiness.md`). They are filesystem/source accounting only.

| Inventory slice | Count |
| --- | ---: |
| Baseline `*Test.kt` files (excl. inventory fixture) | **331** |
| Baseline `@Test` methods | **4624** |
| Campaign `*TddTest.kt` files (excl. `testinventory`) | **272** |
| Campaign `@Test` methods | **4109** |
| Remaining to 500 by **source** campaign method count | **0** (4109 ≥ 500) |

**Not final until TASK-043.** Mid-wave live disk may drift further (e.g. TASK-503 ledger noted campaign methods 4125 / baseline methods 4640 at a later recount). Prefer TASK-125 fixture + `docs/test-strategy.md` once review-accepted, then TASK-043 command evidence.

### What this baseline page is for now

- Host and JDK prerequisites for coordinated waves (macOS + Corretto 17).
- Dual-path `android.jar` honesty (SDK android-35 present; Downloads absent).
- Pointer that pre-final inventory/trace is open and final green is closed.
- Historical TASK-001 layout transcript for archaeology only.

Companion docs (do not edit under this task's scope):

- `docs/android-platform-setup.md` — SDK discovery and skip-when-absent policy
- `docs/final-verification.md` — pre-verification ledger (not green)
- `docs/acceptance-traceability.md` — AC matrix + inventory open note
- `docs/production-readiness.md` — gap checklist and blocked chains
- `docs/test-strategy.md` / inventory fixture — owned by TASK-125 path

### Explicit non-claims

- No claim that `./gradlew check`, `compileTestKotlinJvm`, or any full/focused suite currently passes.
- No claim that inventory bars are review-accepted or finally reconciled.
- No claim that Android-Lua, LuaJava, workspace, or LSP surfaces are release-complete.
- TASK-184 **done** is not a substitute for TASK-043/TASK-037.

---

## Historical Capture (TASK-001 / RERUN2-W001, 2026-06-06)

Captured by `RERUN2-W001` for `TASK-001` on 2026-06-06 at approximately 19:40-19:45 +08:00.

The following status, source counts, dirty-file lists, and lock lists are a **point-in-time historical transcript**. They are superseded for live discussion by the Current Post-TASK-184 / Pre-TASK-043 section above and by current task files under `tasks/`.

## Git Status (historical)

Command required by the task:

```text
git status --short
```

Output captured after `TASK-001` locks were acquired:

```text
D  .claude/scheduled_tasks.lock
 M src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/LuaWorkspaceInput.kt
 M src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/std/BuiltinOverlayLoader.kt
 M src/commonTest/kotlin/semantic/support/WorkspaceSemanticHarness.kt
 M src/commonTest/kotlin/semantic/workspace/BuiltinOverlayLoaderTest.kt
D  src/commonTest/kotlin/semantic/workspace/LuaWorkspaceQueryFacadeTest.kt
 M src/jvmTest/kotlin/lsp/LuaLanguageServiceTest.kt
AM src/jvmTest/kotlin/semantic/workspace/LuaWorkspaceQueryFacadeTest.kt
?? docs/android-lua-architecture.md
?? docs/android-lua-import-luajava.md
?? docs/android-platform-setup.md
?? docs/test-strategy.md
?? locks/
?? sh.exe.stackdump
?? src/jvmTest/kotlin/testinventory/
?? tasks/
warning: unable to access 'C:\Users\dingyi/.config/git/ignore (historical Windows path) (historical Windows path)': Permission denied
warning: unable to access 'C:\Users\dingyi/.config/git/ignore (historical Windows path) (historical Windows path)': Permission denied
```

Additional read-only git checks:

```text
git diff --name-status
```

```text
M	src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/LuaWorkspaceInput.kt
M	src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/std/BuiltinOverlayLoader.kt
M	src/commonTest/kotlin/semantic/support/WorkspaceSemanticHarness.kt
M	src/commonTest/kotlin/semantic/workspace/BuiltinOverlayLoaderTest.kt
M	src/jvmTest/kotlin/lsp/LuaLanguageServiceTest.kt
M	src/jvmTest/kotlin/semantic/workspace/LuaWorkspaceQueryFacadeTest.kt
```

```text
git diff --cached --name-status
```

```text
D	.claude/scheduled_tasks.lock
D	src/commonTest/kotlin/semantic/workspace/LuaWorkspaceQueryFacadeTest.kt
A	src/jvmTest/kotlin/semantic/workspace/LuaWorkspaceQueryFacadeTest.kt
warning: unable to access 'C:\Users\dingyi/.config/git/ignore (historical Windows path) (historical Windows path)': Permission denied
```

The status command succeeded, but git could not read the user-level ignore file at the historical Windows path `C:\Users\dingyi/.config/git/ignore`. Current host is macOS; that warning is historical.

## Source Layout (historical TASK-001 counts)

Tracked source-set file counts from `git ls-files src` **as of 2026-06-06**:

```text
commonMain: 149 tracked
commonTest: 17 tracked
jsMain: 1 tracked
jsTest: 1 tracked
jvmMain: 9 tracked
jvmTest: 54 tracked
nativeMain: 1 tracked
nativeTest: 1 tracked
```

Current filesystem source-set file counts from `Get-ChildItem -Path .\src -Recurse -File` **as of 2026-06-06** (PowerShell on the prior host):

```text
commonMain: 149
commonTest: 17
jsMain: 1
jsTest: 1
jvmMain: 9
jvmTest: 55
nativeMain: 1
nativeTest: 1
```

These counts are **not** the 2026-07-12 live tree (campaign inventory alone is now hundreds of `*TddTest.kt` files). Re-inventory belongs to TASK-125 / final-verification docs, not this historical section.

Top-level `src` source sets:

```text
commonMain
commonTest
jsMain
jsTest
jvmMain
jvmTest
nativeMain
nativeTest
```

Primary `commonMain` packages under `io.github.dingyi222666.luaparser`:

```text
lexer
parser
semantic
source
util
```

Tracked `commonMain` package counts **(historical)**:

```text
lexer: 2 tracked
parser: 7 tracked
semantic: 114 tracked
source: 1 tracked
util: 3 tracked
```

`semantic` is subdivided into:

```text
api
binder
checker
comment
comments
model
symbol
types
workspace
```

`jvmMain` contains JVM-only interop, LSP, and JVM resource access code:

```text
interop
lsp
semantic
```

`commonMain/resources` contains Lua standard-library overlays for Lua 5.3 and Lua 5.4 under:

```text
src/commonMain/resources/io/github/dingyi222666/luaparser/semantic/workspace/std/lua53
src/commonMain/resources/io/github/dingyi222666/luaparser/semantic/workspace/std/lua54
```

(Android-Lua / android-framework resource trees and library stubs restored under later tasks including TASK-184 are outside this 2026-06-06 list.)

## Test Layout (historical TASK-001 list)

Tracked common/JVM Kotlin test file count **as of 2026-06-06**:

```text
tracked test kt files: 62
```

`commonTest` Kotlin files **(historical enumeration)**:

```text
src/commonTest/kotlin/parser.common.kt
src/commonTest/kotlin/semantic/api/TypeInfoShapeTest.kt
src/commonTest/kotlin/semantic/checker/ExpressionTypeEvaluatorTest.kt
src/commonTest/kotlin/semantic/model/CompletionProviderTest.kt
src/commonTest/kotlin/semantic/model/SemanticModelFacadeTest.kt
src/commonTest/kotlin/semantic/SemanticPipelineIntegrationTest.kt
src/commonTest/kotlin/semantic/SemanticPipelineTest.kt
src/commonTest/kotlin/semantic/support/WorkspaceSemanticHarness.kt
src/commonTest/kotlin/semantic/types/ModuleTypeBridgeTest.kt
src/commonTest/kotlin/semantic/workspace/BuiltinOverlayLoaderTest.kt
src/commonTest/kotlin/semantic/workspace/DocumentFactsCollectorTest.kt
src/commonTest/kotlin/semantic/workspace/LegacyModuleEnvironmentPassTest.kt
src/commonTest/kotlin/semantic/workspace/LuaWorkspaceEngineTest.kt
src/commonTest/kotlin/semantic/workspace/LuaWorkspaceEngineUpdateTest.kt
src/commonTest/kotlin/semantic/workspace/ModuleExportCollectorTest.kt
src/commonTest/kotlin/semantic/workspace/WorkspaceFoundationTest.kt
src/commonTest/kotlin/semantic/workspace/WorkspaceModuleGraphTest.kt
```

`jvmTest` Kotlin files **(historical enumeration; pre-campaign growth)**:

```text
src/jvmTest/kotlin/interop/jvm/JvmWorkspaceEngineTest.kt
src/jvmTest/kotlin/lsp/LuaLanguageServiceTest.kt
src/jvmTest/kotlin/parser.jvm.kt
src/jvmTest/kotlin/parser/AndroLuaSyntaxRegressionTest.kt
src/jvmTest/kotlin/parser/Lua53SyntaxRegressionTest.kt
src/jvmTest/kotlin/parser/Lua54SyntaxRegressionTest.kt
src/jvmTest/kotlin/parser/ParserCommentSyntaxRegressionTest.kt
src/jvmTest/kotlin/parser/ParserRecoveryRegressionTest.kt
src/jvmTest/kotlin/parser/ParserRegressionHarness.kt
src/jvmTest/kotlin/parser/ParserRegressionHarnessTest.kt
src/jvmTest/kotlin/semantic/binder/BinderPassDeclarationTest.kt
src/jvmTest/kotlin/semantic/binder/BinderPassDocDeclarationTest.kt
src/jvmTest/kotlin/semantic/binder/BinderPassScopeTest.kt
src/jvmTest/kotlin/semantic/binder/BinderPositionQueriesTest.kt
src/jvmTest/kotlin/semantic/binder/DeclarationCommentInteropTest.kt
src/jvmTest/kotlin/semantic/binder/DeclarationIndexTest.kt
src/jvmTest/kotlin/semantic/binder/DeclarationModelTest.kt
src/jvmTest/kotlin/semantic/binder/PositionRangeIndexTest.kt
src/jvmTest/kotlin/semantic/binder/ScopeGraphTest.kt
src/jvmTest/kotlin/semantic/checker/CallCheckerTest.kt
src/jvmTest/kotlin/semantic/checker/FunctionSignatureCheckerTest.kt
src/jvmTest/kotlin/semantic/checker/MemberResolverTest.kt
src/jvmTest/kotlin/semantic/checker/ReturnCheckerTest.kt
src/jvmTest/kotlin/semantic/comments/CommentAttachPassTest.kt
src/jvmTest/kotlin/semantic/comments/CommentCollectorTest.kt
src/jvmTest/kotlin/semantic/comments/CommentSyntaxModelTest.kt
src/jvmTest/kotlin/semantic/comments/DocCommentSyntaxParserRobustnessTest.kt
src/jvmTest/kotlin/semantic/comments/EmmyLuaDocRegressionTest.kt
src/jvmTest/kotlin/semantic/LegacySemanticAnalyzerCompatibilityTest.kt
src/jvmTest/kotlin/semantic/model/SemanticModelDiagnosticsTest.kt
src/jvmTest/kotlin/semantic/SemanticPublicApiTest.kt
src/jvmTest/kotlin/semantic/types/model/TypeModelTest.kt
src/jvmTest/kotlin/semantic/types/resolve/DocFunctionTypeSyntaxParserTest.kt
src/jvmTest/kotlin/semantic/types/resolve/DocTypeResolutionTest.kt
src/jvmTest/kotlin/semantic/types/resolve/TypeNormalizerTest.kt
src/jvmTest/kotlin/semantic/types/resolve/TypeRelationsTest.kt
src/jvmTest/kotlin/semantic/types/resolve/TypeResolutionContextTest.kt
src/jvmTest/kotlin/semantic/types/resolve/TypeResolverTest.kt
src/jvmTest/kotlin/semantic/types/resolve/TypeScopeGraphTest.kt
src/jvmTest/kotlin/semantic/types/resolve/TypeSubstitutorTest.kt
src/jvmTest/kotlin/semantic/types/syntax/TypeSyntaxParserTest.kt
src/jvmTest/kotlin/semantic/types/syntax/TypeSyntaxRendererTest.kt
src/jvmTest/kotlin/semantic/workspace/LuaWorkspaceEngineUpdateJvmTest.kt
src/jvmTest/kotlin/semantic/workspace/LuaWorkspaceQueryFacadeTest.kt
src/jvmTest/kotlin/source/AST2LuaRoundTripTest.kt
src/jvmTest/kotlin/testinventory/NewTestInventoryTddTest.kt
```

The `src/jvmTest/kotlin/testinventory/NewTestInventoryTddTest.kt` path was untracked and locked by `TASK-006` at TASK-001 time. Current inventory-fixture ownership is the TASK-125 path; do not treat the 2026-06-06 lock note as current.

`jvmTest` parser regression resources **(historical)**:

```text
src/jvmTest/resources/parser/regressions/androlua/control_flow_lambda_array.lua
src/jvmTest/resources/parser/regressions/androlua/control_flow_lambda_array.shape.txt
src/jvmTest/resources/parser/regressions/lua53/labels_loops_tables.lua
src/jvmTest/resources/parser/regressions/lua53/labels_loops_tables.shape.txt
src/jvmTest/resources/parser/regressions/lua54/attributes_in_blocks.lua
src/jvmTest/resources/parser/regressions/lua54/attributes_in_blocks.shape.txt
src/jvmTest/resources/parser/regressions/recovery/doc_member_if_repeat.lua
src/jvmTest/resources/parser/regressions/recovery/doc_member_if_repeat.shape.txt
```

## Gradle and JDK Prerequisites (still directionally true; host paths updated)

Build files (as documented at TASK-001; re-check build files if you need exact plugin versions):

- `settings.gradle.kts` declares the root project name `luaparser` and uses `google`, `gradlePluginPortal`, and `mavenCentral`.
- `build.gradle.kts` uses Kotlin Multiplatform, `com.vanniktech.maven.publish`, `maven-publish`, and signing.
- Project coordinates are `io.github.dingyi222666:luaparser` (version may drift; see build file).
- Kotlin targets include JVM, JS browser, and native hosts as configured in the build file.
- JVM compilation target is `JvmTarget.JVM_11`.
- `kotlin { jvmToolchain(11) }` is configured, so Gradle may need a JDK 11 toolchain or a resolver/cache that can provide one.
- JVM main dependencies include LSP4J artifacts for the language server.
- JVM tests depend on `kotlin("test-junit")`.
- Native host test linking/execution is gated behind project properties as documented in the build file.
- `gradle.properties` sets Kotlin official style, JS IR, daemon heap, and related flags.

Gradle wrapper distribution URL is defined in `gradle/wrapper/gradle-wrapper.properties` (do not treat a single hard-coded version string in this historical section as authoritative if the wrapper file has since changed).

**Current verification JDK (macOS host):**

```text
JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home
```

```bash
export JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
# Example only — TASK-043 owns serialized verification; do not run from parallel doc workers:
# ./gradlew jvmTest --tests testinventory.NewTestInventoryTddTest
```

Historical Windows verification path transcripts (stale; do not use on this host):

```text
JAVA_HOME=C:/Users/dingyi/.jdks/temurin-17.0.11
./gradlew.bat ...
```

No Gradle test command was required or run for the original TASK-001 inventory, and **no Gradle/tests/compile were run for TASK-509**.

## Existing Dirty Files to Avoid (historical TASK-001 list)

At TASK-001 capture time, workers were told not to edit the following without locks:

```text
.claude/scheduled_tasks.lock
src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/LuaWorkspaceInput.kt
src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/std/BuiltinOverlayLoader.kt
src/commonTest/kotlin/semantic/support/WorkspaceSemanticHarness.kt
src/commonTest/kotlin/semantic/workspace/BuiltinOverlayLoaderTest.kt
src/commonTest/kotlin/semantic/workspace/LuaWorkspaceQueryFacadeTest.kt
src/jvmTest/kotlin/lsp/LuaLanguageServiceTest.kt
src/jvmTest/kotlin/semantic/workspace/LuaWorkspaceQueryFacadeTest.kt
```

That dirty set is **historical**. Current dirty/owned paths are governed by live `locks/` and task files, not this list.

Workers were also told to avoid these active task outputs/paths unless assigned:

```text
docs/test-strategy.md
src/jvmTest/kotlin/testinventory/
docs/android-lua-library-models.md
```

`docs/test-strategy.md` and the inventory fixture remain sensitive under the TASK-125 path. Do not edit them from this baseline-state task.

Active locks observed **during TASK-001 inventory** (stale):

```text
locks/tasks/TASK-001.lock: owner RERUN2-W001, task TASK-001
locks/files/docs__baseline-state.md.lock: owner RERUN2-W001, task TASK-001
locks/files/tasks__TASK-001.md.lock: owner RERUN2-W001, task TASK-001
locks/tasks/TASK-004.lock: owner RERUN2-W004, task TASK-004
locks/files/docs__android-lua-library-models.md.lock: owner RERUN2-W004, task TASK-004
locks/files/tasks__TASK-004.md.lock: owner RERUN2-W004, task TASK-004
locks/tasks/TASK-006.lock: owner RERUN-W006, task TASK-006
locks/files/docs__test-strategy.md.lock: owner RERUN-W006, task TASK-006
locks/files/src__jvmTest__kotlin__testinventory__NewTestInventoryTddTest.kt.lock: owner RERUN-W006, task TASK-006
```

Completed documentation already present but untracked at the time of TASK-001 status capture:

```text
docs/android-lua-architecture.md
docs/android-lua-import-luajava.md
docs/android-platform-setup.md
```

Other untracked item at that time:

```text
sh.exe.stackdump
```

## Commands Used

### TASK-509 (2026-07-12) — docs-only host checks

```text
# Read-only; no Gradle
/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home/bin/java -version
/usr/libexec/java_home -V
test -f /Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar
ls -la /Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar
test -f /Users/dingyi/Downloads/android.jar   # absent on this host
```

### TASK-001 (historical 2026-06-06)

```text
git status --short
git diff --name-status
git diff --cached --name-status
rg --files
Get-Content .\settings.gradle.kts
Get-Content .\build.gradle.kts
Get-Content .\gradle.properties
Get-Content .\gradle\wrapper\gradle-wrapper.properties
Get-ChildItem .\src ...
git ls-files src ...
```

Historical Java version probe on the prior host mixed path labels; current macOS probe uses Corretto 17 as shown in the Current section.

---

**TASK-509 footer:** Documentation-only refresh of this baseline page for post-TASK-184 / pre-TASK-043 accuracy: macOS paths, Amazon Corretto 17, dual-path android-35 `android.jar` (SDK present / Downloads absent), inventory-not-final-until-043, TASK-043 blocked, no final green claim. Historical TASK-001 transcript retained and labeled stale. No product code. No Gradle/tests/compile.
