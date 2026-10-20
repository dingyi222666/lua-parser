# Final Verification Ledger

TASK-037 is the final acceptance audit for the Android-Lua/parser/semantic/workspace/JVM interop/LSP wave. This ledger records **serialized TASK-043 command evidence**. Pre-verification inventory prose below is retained as history; **current pass/fail authority is the Windows tip-coherent full `jvmTest` run**.

**Final criteria-by-criteria acceptance** remains owned by **TASK-037** after this ledger (TASK-105) and the traceability matrix (TASK-106) are updated.

## Current Status (2026-07-13)

| Item | Status | Evidence |
| --- | --- | --- |
| At least 500 new tests exist (source accounting) | **Met (dated source count)** | Acceptance-time source recount recorded campaign **307** `*TddTest.kt` files / **4857** `@Test` methods. This is historical audit evidence, not a continuously maintained inventory assertion; suite pass is separate. |
| Required tests pass | **Pass (Windows full jvmTest)** | TASK-043 full suite Action [29228040252](https://github.com/dingyi222666/lua-parser/actions/runs/29228040252) on `windows-verify` SHA `cd188239b7a52744c9519e8d15169808a9f7e2c2`: **tests=5386, failures=0, errors=0, skipped=177, compileExit=0, exitCode=0, durationSec=161**. Result JSON: `tasks/agent-runs/full-jvmtest/full-jvmtest-result-29228040252.json`. |
| Command outputs recorded | **Yes** | `tasks/agent-runs/REVIEW-FULL-29228040252.last.txt`, full JUnit XML artifact `full-junit-xml` on the Action, HTML report artifact `full-test-report-html`. |
| Environment details recorded | **Yes (Windows self-hosted)** | Runner: self-hosted Windows X64 (`DINGYI-PC`). `JAVA_HOME=C:\Users\dingyi\.jdks\temurin-17.0.11`. `ANDROID_HOME=C:\Users\dingyi\AppData\Local\Android\Sdk`. CPU hard-cap: affinity `0x3F`, `GRADLE_OPTS=-Dorg.gradle.workers.max=5`, BelowNormal. Workflow: `.github/workflows/windows-full-jvmtest.yml` + `scripts/windows-run-full-jvmtest.ps1`. Never invent `G:/`. |
| Windows slice progress bar | **Supporting evidence only** | `tasks/agent-runs/win-slices/`: s001–s018 **success**, filesDone **367/367**, strategy `must-green-to-advance`, chunkSize 45. Slice bar is **not** tip-coherent full suite; full suite above is authority. |
| Final criteria-by-criteria acceptance | **Pending TASK-037** | TASK-043 **done**. TASK-105 refreshes this ledger. TASK-106 updates traceability. TASK-037 performs final audit. |

## Pass/Fail Command Evidence (TASK-043)

| # | Command (Windows self-hosted) | Exit | Result |
| ---: | --- | ---: | --- |
| 1 | `scripts/windows-run-gradle-capped.ps1 compileTestKotlinJvm --parallel --max-workers=5` | **0** | Compile gate green (`compileExit=0` in result JSON). |
| 2 | `scripts/windows-run-gradle-capped.ps1 jvmTest --parallel --max-workers=5` (**no** `--tests` filter) | **0** | Full suite green: 5386 tests, 0 failures, 0 errors, 177 skipped. |

Primary evidence run:

```text
mode: full-jvmtest
runId: 29228040252
sha: cd188239b7a52744c9519e8d15169808a9f7e2c2
status: success
tests: 5386
failures: 0
errors: 0
skipped: 177
durationSec: 161
gate: task-043-full-jvmtest
Action: https://github.com/dingyi222666/lua-parser/actions/runs/29228040252
```

Prior full-suite reds (same tip-coherent path) for audit trail:

| runId | status | failures | notes |
| --- | --- | ---: | --- |
| 29225343255 | failure | 9 | First full suite; product fixes wave FULLJVM-29225343255 |
| 29227224781 | failure | 2 | Regression pair (SignatureHelp generic identity + AST2Lua nested-if assert); fixed TASK-670/676 |
| **29228040252** | **success** | **0** | **Accepted TASK-043 evidence** |

## 500-Test Campaign Criterion

| Slice | Count | Notes |
| --- | ---: | --- |
| Campaign floor (TASK-037) | 500 | New TDD `@Test` methods after baseline rules in `docs/test-strategy.md` |
| Campaign files / methods (fixture + strategy) | **307 / 4857** | `remaining_to_500 = 0` by **source accounting** |
| Full suite executed methods (JUnit) | **5386** | Includes non-campaign + skipped accounting differs from source inventory |
| Suite hard failures | **0** | On accepted full run |

`remaining_to_500 = 0` alone is **not** suite-pass proof. Combined with full `jvmTest` exit 0 / 0 failures, the campaign floor is satisfied for TASK-037 review.

## Supporting: Windows Slice Bar (must-green-to-advance)

| Field | Value |
| --- | --- |
| strategy | must-green-to-advance |
| total slices / files | 18 / 367 |
| success | 18 (filesDone 367/367) |
| ALL_GREEN tip commit | `0de427f` (slice bar bookkeeping) |
| s018 last green | run 29220930531 (240 tests, 0 fails) |

Slice bar proves ordered chunked green history. **Tip-coherent acceptance uses full jvmTest only.**

## Honesty Bounds

1. Soft-skips (`Assume.assumeTrue`, missing android.jar, unimplemented LSP caps) can still skip work; accepted suite reports **177 skipped**, **0 failed**.
2. Open product/docs tasks outside the 043 evidence path may remain; they do not reopen the recorded full-suite exit code unless a later suite fails.
3. Do **not** treat Mac host inventory paths as the Windows CI environment.
4. Do **not** claim production acceptance complete until TASK-037 finishes criteria-by-criteria audit after TASK-106.

## Historical Pre-Verification Inventory (stale snapshots)

Older TASK-503 / WAVE36F figures (e.g. 272/4125 campaign, 331/4640 baseline) are **superseded** by the strategy bars **307/4857** and **366/5378** and by full-suite run **29228040252**. Retain git history for audit; do not use those rows as current pass/fail.
