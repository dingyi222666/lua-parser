# Test Inventory Recount Procedure

This document explains how to update the accounting constants in
`src/jvmTest/kotlin/testinventory/NewTestInventoryTddTest.kt` and keep them
aligned with `docs/test-strategy.md`. It is the procedure for inventory recount
tasks (for example TASK-231 / TASK-224 style refreshes, and the pre-verification
TASK-125 recount path). It does **not** authorize workers to run Gradle, tests,
compile, or build-output cleanup; those remain review-owned and serial under
`docs/serialized-verification.md` (**TASK-043**).

**Honesty bound (post-TASK-184 / pre-043):** TASK-184 Android-Lua library stub
restoration is **done** (review-accepted). That unblocks product surface work
and the pre-final inventory/trace path. It does **not** unlock TASK-043, clear
the compile gate, prove inventory bars, or authorize any final green /
global-pass claim. Inventory accounting on this page is procedure and
source-tree snapshot guidance only. **Inventory is not final until TASK-043.**

## Why recount exists

`NewTestInventoryTddTest` is an accounting fixture, not product coverage. It walks
`src/commonTest/kotlin` and `src/jvmTest/kotlin`, counts Kotlin `*Test.kt` files
and line-leading `@Test` methods, and asserts that:

1. Live source-tree totals match fixture constants (baseline inventory).
2. Campaign (`*TddTest.kt`, excluding `testinventory`) totals match fixture constants.
3. Documented numbers in `docs/test-strategy.md` match those same constants.
4. Named non-`*Test.kt` annotated smoke fixtures stay on the exclusion map.

When workers add TDD suites, the live tree drifts above the frozen constants and
the fixture fails until a recount task raises bars and refreshes the strategy
document together.

## Constants that must stay in lockstep

Update these companion values in
`src/jvmTest/kotlin/testinventory/NewTestInventoryTddTest.kt` only when the
matching strategy text is updated in the same change set (or a paired serial task
that owns the other file):

| Constant | Meaning |
| --- | --- |
| `expectedBaselineFiles` | Count of `*Test.kt` files under commonTest + jvmTest, **excluding** the inventory fixture itself. |
| `expectedBaselineTestMethods` | Sum of line-leading `@Test` methods on those baseline files. |
| `expectedCampaignFiles` | Count of `*TddTest.kt` files that are **not** under `testinventory`. |
| `expectedCampaignTestMethods` | Sum of `@Test` methods on those campaign suites. |
| `expectedExcludedAnnotatedKotlinFiles` | Map of non-`*Test.kt` Kotlin paths that still contain `@Test`, with their method counts. |

Also update the hard-coded strategy assertions in
`strategyDocumentTracksBaselineInventoryAndFixtureScope` (table total row and
`current_campaign_files` / `current_campaign_total` / `remaining_to_500` lines)
so they quote the same numbers as the constants and as `docs/test-strategy.md`.

## Counting rules (must match the fixture)

Use the same rules as `NewTestInventoryTddTest` / `docs/test-strategy.md`:

1. Roots: `src/commonTest/kotlin` and `src/jvmTest/kotlin` only.
2. Inventory files: regular `.kt` files whose names end with `Test.kt`.
3. Method count: regex `(?m)^\s*@Test\b` (annotation at the start of a line,
   optional leading whitespace). Do not count `@Test` inside comments or mid-line
   attributes unless the fixture regex would match them.
4. Exclude the fixture path
   `src/jvmTest/kotlin/testinventory/NewTestInventoryTddTest.kt` from baseline and
   campaign totals.
5. Campaign subset: file name ends with `TddTest.kt` and path does not contain
   `testinventory`.
6. Excluded annotated Kotlin: `.kt` files that do **not** end with `Test.kt` but
   still contain at least one counted `@Test` (currently
   `parser.common.kt` / `parser.jvm.kt`).
7. Generated/build directories and production sources are never walked.
8. Area labels in the strategy table (parser, semantic, workspace, interop, LSP,
   integration) follow the path heuristics in the fixture's `TestEntry.area`;
   when refreshing the area table, re-derive from the same heuristics rather than
   inventing new buckets.

## How to obtain live counts (workers)

Workers in parallel waves must **not** run Gradle or the inventory test class.
Prefer a pure filesystem recount that mirrors the fixture (find + count). Example
shell sketch for this **macOS** host (illustrative; adjust quoting for the host shell):

```text
# Baseline *Test.kt files (exclude inventory fixture)
find src/commonTest/kotlin src/jvmTest/kotlin -name '*Test.kt' -type f \
  ! -path '*/testinventory/NewTestInventoryTddTest.kt' | wc -l

# Campaign *TddTest.kt files (exclude testinventory path)
find src/commonTest/kotlin src/jvmTest/kotlin -name '*TddTest.kt' -type f \
  ! -path '*/testinventory/*' | wc -l

# @Test methods: apply the same line-leading regex per file as the fixture
```

A review-owned serialized run may instead execute (never by parallel workers),
using the primary macOS Corretto 17 JDK and `./gradlew` (not `./gradlew.bat`):

```text
export JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home \
  ./gradlew jvmTest --tests testinventory.NewTestInventoryTddTest
```

and use the printed inventory report plus assertion diffs as the source of truth
for the next raise. That command is a deferred acceptance reference for
**TASK-043** only. Documenting it here does **not** authorize parallel workers
to run it, and a green inventory fixture alone is **not** final suite green.

## Host environment for recount / deferred verification (macOS primary; never `G:/`)

| Role | Path | Host check (2026-07-12 WAVE36F re-check) |
| --- | --- | --- |
| Primary verification JDK | `/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home` | **Present** (Amazon Corretto 17.0.19) |
| Preferred `android.jar` | `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar` | **Present** (~27,092,450 bytes) |
| Optional `android.jar` | `/Users/dingyi/Downloads/android.jar` | **Absent** on this host; allowed when present |
| Wrapper | `./gradlew` (not `./gradlew.bat`) | macOS primary |

Rules:

1. Prefer SDK android-35 when `File.isFile` is true.
2. Use Downloads only when that file exists; do not invent classpath entries for a missing file.
3. Never hard-code Windows `G:/` (or any other non-host) paths as the primary location on this page.
4. Inventory recount itself does not require `android.jar`. Host jar paths are recorded so deferred
   TASK-043 verification of Android/JVM filters and related strategy docs stay consistent with
   `docs/android-platform-setup.md` and `docs/serialized-verification.md`.

## Raise-only bar policy (mandatory)

**Do not lower acceptance bars without an explicit review note.**

- If live counts are **higher** than the current constants, raise the constants
  and the matching `docs/test-strategy.md` rows/strings to the live totals.
- If live counts are **equal**, leave constants unchanged (or re-sync docs text
  only if wording drifted without numbers changing).
- If live counts are **lower** than the current constants, **stop**. Do not edit
  the constants downward in a worker wave. Record the drift in the task
  `progress` / notes, keep status out of silent green, and require a review agent
  note that explicitly authorizes a bar decrease (for example after intentional
  suite retirement or rename). Lowering without that note is a reject condition.
- Never “split the difference,” average, or pick intermediate values. Bars move
  only to the agreed live snapshot (raise) or to a review-authorized lower
  snapshot (decrease with note).
- Campaign and baseline must move together with the strategy document. Fixture
  constants and `docs/test-strategy.md` must not disagree after the change set.

## Document sync checklist (`docs/test-strategy.md`)

When constants change, update the strategy document in the same serial ownership
window:

1. Area table rows and the **Total current inventory** bold file/method counts.
2. Campaign block: `current_campaign_files`, `current_campaign_total`,
   `remaining_to_500` (`max(0, 500 - current_campaign_total)`).
3. Inventory notes / exclusion table if excluded annotated files changed.
4. Point-in-time note (task id + wave) stating this is a source-tree snapshot,
   **not** a global green or production-readiness claim.
5. Keep the 500-test accounting rules and `testinventory` exclusion intact.
6. State explicitly that **inventory is not final until TASK-043** records
   command evidence for the inventory fixture (and any review-selected filters).

## Pairing ownership

- Recounts that touch both the fixture and the strategy doc need non-overlapping
  locks on both files (see TASK-231 / TASK-125 related locks). Do not race
  TASK-224-style docs-only refreshes against fixture-only updates without
  serializing.
- This procedure doc (`docs/test-inventory-recount-procedure.md`) is docs-only and
  does not itself change inventory numbers.
- Product and test corpus tasks must not silently edit inventory constants as a
  side effect; open or extend a dedicated recount task instead.
- Pre-verification path after TASK-184: TASK-125 (fixture + strategy raise-only
  recount under review) → TASK-038 (fixture/compile alignment after TASK-125
  accept) → **TASK-043** (serialized proof). This procedure does not advance
  those statuses.

## Review acceptance for recount work

Review accepts a recount when:

1. Fixture constants equal live counts under the rules above (or review noted a
   deliberate lower bar).
2. `docs/test-strategy.md` quotes the same baseline and campaign numbers.
3. Bars were not lowered without a review note in the task file.
4. `testinventory` remains excluded from campaign totals.
5. No unauthorized Gradle/test execution by the worker; verification stays
   deferred to TASK-043 when a test run is required.
6. No final-green, production-ready, or “suite passed” claim is made from source
   counts alone. **Inventory is not final until TASK-043.**

## Related references

- Fixture: `src/jvmTest/kotlin/testinventory/NewTestInventoryTddTest.kt`
- Strategy snapshot: `docs/test-strategy.md`
- Serialized verification: `docs/serialized-verification.md`
- Android host setup: `docs/android-platform-setup.md`
- Final verification ledger: `docs/final-verification.md`
- Acceptance traceability: `docs/acceptance-traceability.md`
- Recent recount example: TASK-231 (fixture + strategy raise-only sync)
- Docs inventory snapshot example: TASK-224 (strategy-only refresh)
- Pre-verification recount path: TASK-125 (under review; not accepted green)
- Product-surface unlock (not verification unlock): TASK-184 (done)
- Serialized verification owner: TASK-043 (blocked / pending; not released by this doc)

## TASK-510 docs-only refresh note

TASK-510 is documentation-only. This refresh records the post-TASK-184 /
pre-TASK-043 honesty bound, macOS host paths (Amazon Corretto 17 primary JDK;
SDK android-35 `android.jar` present ~27,092,450 bytes; Downloads `android.jar`
absent; never `G:/`), inventory-not-final-until-043 wording, and the deferred
Corretto 17 + `./gradlew` inventory command form for TASK-043. It does **not**
edit fixture constants, does **not** run Gradle/tests/compile, does **not**
unlock TASK-043, and does **not** claim final green acceptance.
