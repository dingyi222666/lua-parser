# Final Verification Ledger

TASK-037 is the final acceptance audit for the Android-Lua/parser/semantic/workspace/JVM interop/LSP wave. This ledger is **not** a green report. Pre-verification workers may refresh source-tree inventory and documentation only. They must not run Gradle, build, compile, test, `jvmTest`, or any command that writes Gradle outputs. Serialized execution remains owned by **TASK-043**. Final criteria-by-criteria acceptance remains owned by **TASK-037**.

**Do not declare final green acceptance** until TASK-043 records pass/fail command evidence and TASK-037 completes its acceptance audit against that evidence.

## Current Status

| Item | Status | Evidence |
| --- | --- | --- |
| At least 500 new tests exist (source accounting) | Pre-verification inventory only | Read-only source scan on **2026-07-11** (TASK-503 WAVE36F live recount) found **272** campaign `*TddTest.kt` files excluding `testinventory` with **4125** `@Test` methods. That **exceeds** the 500-new-test campaign floor by source count alone. Source count is **not** proof that tests compile or pass. |
| Required tests pass | Pending TASK-043 | No verification command was run by TASK-503. Workers in this wave forbid Gradle/build/test/compile execution outside TASK-043. |
| Command outputs recorded | Pending TASK-043 | No new Gradle stdout/stderr summaries from TASK-043 yet. TASK-043 must record exit status and pass/fail per command. |
| Environment details recorded | Partial (host paths checked read-only) | Repository root: `/Users/dingyi/projects/java_projects/lua-parser`. Expected JVM verification JDK: `/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home` (present, Amazon Corretto 17.0.19). Android jar host path: `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar` (present, **27,092,450** bytes). Alternate host path `/Users/dingyi/Downloads/android.jar` was **not** present at inventory time. Never hard-code `G:/`. |
| Final criteria-by-criteria acceptance | Blocked | Success **cannot** be declared until TASK-043 runs the serialized suite and TASK-037 accepts the recorded evidence. A campaign source count ≥ 500 is necessary but not sufficient. TASK-184 Android-Lua library stub restoration is **done** (review-accepted); that unblocks product surface work but does **not** unlock or satisfy TASK-043 verification. TASK-043 remains **blocked** (inventory/trace chain; TASK-125 still in the pre-043 path). |

## Historical Snapshot (Stale — Do Not Treat As Current)

The following numbers are **historical only**. They come from the WPOOL-211 read-only scan on **2026-06-08** and are retained for audit trail. They are **not** the current pre-verification inventory.

| Historical inventory slice (2026-06-08) | Count |
| --- | ---: |
| Common/JVM Kotlin test files with at least one `@Test` | 82 |
| Common/JVM `@Test` methods | 963 |
| Campaign `*TddTest.kt` files excluding `testinventory` | **20** |
| Campaign `*TddTest.kt` `@Test` methods excluding `testinventory` | **454** |

Historical campaign files from that 2026-06-08 snapshot (stale list):

| File | `@Test` methods (then) |
| --- | ---: |
| `src/commonTest/kotlin/semantic/workspace/DocumentFactsAndroidImportTddTest.kt` | 41 |
| `src/jvmTest/kotlin/interop/jvm/AndroidJarReflectionTddTest.kt` | 32 |
| `src/jvmTest/kotlin/interop/jvm/JvmClassloaderConfigurationTddTest.kt` | 10 |
| `src/jvmTest/kotlin/interop/jvm/JvmReflectionModelTddTest.kt` | 53 |
| `src/jvmTest/kotlin/lsp/LspAndroidLuaE2eTddTest.kt` | 31 |
| `src/jvmTest/kotlin/lsp/LspJavaAndroidFeatureTddTest.kt` | 5 |
| `src/jvmTest/kotlin/lsp/LspLifecycleDiagnosticsTddTest.kt` | 32 |
| `src/jvmTest/kotlin/lsp/LspNavigationSymbolsTddTest.kt` | 41 |
| `src/jvmTest/kotlin/parser/androidlua/AndroidLuaAssetCorpusParseTddTest.kt` | 2 |
| `src/jvmTest/kotlin/parser/androidlua/AndroLuaSyntaxTddTest.kt` | 10 |
| `src/jvmTest/kotlin/parser/ast/AstShapeTddTest.kt` | 3 |
| `src/jvmTest/kotlin/parser/lexer/LuaLexerLiteralCommentTddTest.kt` | 7 |
| `src/jvmTest/kotlin/parser/lua53/Lua53ExpressionPrecedenceTddTest.kt` | 6 |
| `src/jvmTest/kotlin/parser/lua53/Lua53StatementGrammarTddTest.kt` | 8 |
| `src/jvmTest/kotlin/parser/lua53/Lua53TableFunctionModuleTddTest.kt` | 5 |
| `src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryTddTest.kt` | 4 |
| `src/jvmTest/kotlin/semantic/androidlua/AndroidLuaLibraryStubsTddTest.kt` | 37 |
| `src/jvmTest/kotlin/semantic/interop/JavaChainedCallTddTest.kt` | 38 |
| `src/jvmTest/kotlin/semantic/interop/LuaJavaBindClassTddTest.kt` | 36 |
| `src/jvmTest/kotlin/semantic/workspace/AndroidLuaImportWorkspaceTddTest.kt` | 53 |

Any prose that still cites **20 campaign files / 454 campaign methods** as “current” is outdated; use the **Current Pre-Verification Inventory** section below.

## Superseded Pre-Verification Snapshots (still not green)

| Snapshot | Campaign files / methods | Baseline `*Test.kt` files / methods | Notes |
| --- | ---: | ---: | --- |
| TASK-164 (2026-07-11 draft) | 241 / 3434 | 303 / 3957 (all-with-`@Test`) | Superseded; not pass/fail |
| TASK-405 WAVE36E live recount | 255 / 3675 | 314 / 4190 | Superseded by WAVE36F corpus growth |
| TASK-125 WAVE36F fixture bars (at worker stamp) | 272 / 4109 | 331 / 4624 | Fixture + `docs/test-strategy.md` bars; may lag live disk while other WAVE36F test workers land methods |
| **TASK-503 WAVE36F live disk recount (this ledger)** | **272 / 4125** | **331 / 4640** | Pre-verification **source** inventory only; not pass/fail |

All of the above remain **non-green**. Inventory-fixture constants are owned by **TASK-125**; this ledger records live disk at TASK-503 time and does **not** edit the fixture.

## Current Pre-Verification Inventory (2026-07-11, TASK-503 WAVE36F live recount)

Read-only scan over `src/commonTest/kotlin` and `src/jvmTest/kotlin`, counting matches of `(?m)^\s*@Test\b`. Campaign set = files named `*TddTest.kt` excluding any path under `testinventory`. Baseline set = files named `*Test.kt` excluding `testinventory`. No Gradle, compile, or test execution was performed.

| Inventory slice | Count |
| --- | ---: |
| Common/JVM Kotlin test files with at least one `@Test` | **333** |
| Common/JVM `@Test` methods (all annotated files) | **4644** |
| Baseline `*Test.kt` files excluding inventory fixture | **331** |
| Baseline `*Test.kt` `@Test` methods excluding inventory fixture | **4640** |
| Campaign `*TddTest.kt` files excluding `testinventory` | **272** |
| Campaign `*TddTest.kt` `@Test` methods excluding `testinventory` | **4125** |
| Campaign floor for TASK-037 (new-test campaign ≥ 500) | 500 |
| Remaining to 500 by **source** campaign count | **0** (4125 ≥ 500) |

### Campaign rollup by area (current)

| Area root | Campaign files | Campaign `@Test` methods |
| --- | ---: | ---: |
| `src/commonTest/kotlin/semantic` | 1 | 41 |
| `src/jvmTest/kotlin/integration` | 3 | 37 |
| `src/jvmTest/kotlin/interop` | 16 | 327 |
| `src/jvmTest/kotlin/lexer` | 1 | 7 |
| `src/jvmTest/kotlin/lsp` | 57 | 904 |
| `src/jvmTest/kotlin/parser` | 57 | 528 |
| `src/jvmTest/kotlin/semantic` | 127 | 2187 |
| `src/jvmTest/kotlin/source` | 10 | 94 |
| **Total campaign** | **272** | **4125** |

Full per-file enumeration is intentionally omitted from this ledger (272 files). Recompute with:

```bash
# Read-only; do not run Gradle here
# Prefer Python or another line scanner if `rg` is unavailable on the host.
find src/commonTest/kotlin src/jvmTest/kotlin -name '*TddTest.kt' ! -path '*testinventory*' | sort | while read f; do
  printf '%4d  %s\n' "$(python3 -c "import re,sys; print(len(re.findall(r'(?m)^\\s*@Test\\b', open(sys.argv[1],encoding='utf-8',errors='replace').read())))" "$f")" "$f"
done
```

### Inventory vs pass/fail evidence

| Layer | What it means | Owner |
| --- | --- | --- |
| Historical 2026-06-08 snapshot (20 / 454) | Stale source inventory; audit trail only | WPOOL-211 (past) |
| Superseded TASK-164 snapshot (241 / 3434) | Earlier pre-verification draft; not pass/fail | TASK-164 (superseded) |
| Superseded TASK-405 WAVE36E recount (255 / 3675 campaign; 314 / 4190 baseline) | Prior pre-verification source inventory; not pass/fail | TASK-405 (superseded) |
| TASK-125 fixture bars (272 / 4109 campaign; 331 / 4624 baseline at worker stamp) | Inventory guardrail constants + `docs/test-strategy.md`; may lag live disk mid-wave | TASK-125 |
| Current TASK-503 WAVE36F live disk recount (272 / 4125 campaign; 331 / 4640 baseline) | Pre-verification **source** inventory only; not pass/fail | TASK-503 |
| TASK-184 library stub restoration | Product surface restored and review-accepted; **not** suite green | TASK-184 (done) |
| TASK-043 command results | Actual compile/test exit codes and logs | TASK-043 (**blocked** / pending; still gated by inventory/trace chain until review unlocks) |
| TASK-037 acceptance | Final criteria-by-criteria green/red decision | TASK-037 (blocked on TASK-043) |

Related accounting docs (`docs/test-strategy.md`, `testinventory.NewTestInventoryTddTest`) may lag or lead this ledger while parallel WAVE36F workers land additional `@Test` methods; TASK-125 owns inventory fixture refresh. Reconcile strategy docs and fixture constants under their own tasks before TASK-037 acceptance. **This ledger must not claim green solely because the source campaign count is ≥ 500.** **This worker does not unlock TASK-043.**

## Host Environment (macOS primary; never `G:/`)

| Resource | Path | Present at TASK-503 inventory time |
| --- | --- | --- |
| Repository root | `/Users/dingyi/projects/java_projects/lua-parser` | yes |
| Primary verification JDK | `/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home` | yes (Amazon Corretto 17.0.19) |
| Preferred `android.jar` | `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar` | yes (**27,092,450** bytes) |
| Optional `android.jar` | `/Users/dingyi/Downloads/android.jar` | **no** (absent) |
| Wrapper | `./gradlew` (not `./gradlew.bat`) | host is macOS |

Never hard-code Windows `G:/` (or any other non-host) paths in verification notes or product configuration.

## Serialized Verification Commands (TASK-043 Only)

TASK-043 must hold the serialized verification locks before running any command: `locks/tasks/TASK-043.lock`, `locks/files/build.lock`, `locks/files/.gradle.lock`, `locks/files/gradle-daemon.lock`, and `locks/files/tasks__TASK-043.md.lock`.

Recommended command order for the verifier (macOS host):

```bash
export JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew compileTestKotlinJvm
./gradlew jvmTest
```

If the broad `jvmTest` run fails or the review agent requests focused traceability, TASK-043 should rerun one command at a time with filters for the affected lanes, including parser Lua 5.3 grammar, Android-Lua parser/corpus, AST shape, recovery, semantic checker/model/workspace suites, JVM interop and android.jar reflection, Android-Lua semantic integration, LSP lifecycle/navigation/Android features, and the test inventory fixture.

## TASK-043 Pass/Fail Evidence (Empty Until Run)

| Command | Exit | Pass/Fail | Notes / log path |
| --- | --- | --- | --- |
| `./gradlew compileTestKotlinJvm` | _pending_ | _pending_ | Recorded by TASK-043 only |
| `./gradlew jvmTest` | _pending_ | _pending_ | Recorded by TASK-043 only |
| Focused lane re-runs (if any) | _pending_ | _pending_ | Recorded by TASK-043 only |

Until the table above is filled by TASK-043, this ledger remains **non-green**.

## Acceptance Notes For TASK-043 / TASK-037

- Record each command, exit status, and whether a failure is production behavior, test expectation drift, or infrastructure.
- Do **not** declare TASK-037 green without TASK-043 pass/fail evidence, even though the current campaign **source** count (4125) is already above 500.
- Do **not** revive the historical **20 / 454** snapshot, the superseded TASK-164 **241 / 3434** draft, or the superseded TASK-405 **255 / 3675** draft as current inventory.
- Reconcile `docs/test-strategy.md` and inventory-fixture constants (TASK-125 / related) with the current live source count (baseline **331 / 4640**, campaign **272 / 4125**) before final acceptance if those documents still lag.
- TASK-184 is done for Android-Lua library stubs; that does **not** substitute for TASK-043 serialized verification.
- If additional common/js/native tests are enabled and relevant, record them in the TASK-043 result before returning to TASK-037 acceptance.
- Leave skipped tests explicit, including the reason and whether the skip affects acceptance.
- Host android.jar for verification: prefer `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar` (present at TASK-503 inventory time, 27,092,450 bytes). Optional `/Users/dingyi/Downloads/android.jar` when present; never hard-code `G:/`.
- **Inventory is not final until TASK-043** records command evidence; source recount alone is pre-verification only.
