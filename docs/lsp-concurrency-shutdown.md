# LSP Concurrency And Shutdown Policy

Date: 2026-07-12  
Task: TASK-512 (docs-only refresh)

This document records the JVM language-server concurrency and lifecycle
expectations exercised by the TASK-221 open/close stress corpus and the
TASK-222 shutdown/exit idempotency corpus. It is a policy note for clients,
harness authors, and reviewers — not a claim of full multi-threaded safety for
every LSP path.

Related usage surface: [language-server-usage.md](language-server-usage.md).
Implementation lives under `src/jvmMain/kotlin/io/github/dingyi222666/luaparser/lsp`.

**Honesty bound (post-TASK-184 / pre-TASK-043):** product-surface work through
the chain ending in **TASK-184** (Android-Lua library stubs / overlay surfaces)
is review-accepted at task level. That does **not** unlock **TASK-043**
(serialized Gradle verification, still `blocked`), does **not** unlock
**TASK-037** (final acceptance, still `blocked`), and does **not** authorize any
final-green, suite-green, or production-readiness claim. **Inventory is not
final until TASK-043.** This page documents intended policy and the tested
shapes only; it does not re-run Gradle, expand production code, or re-certify
historical review acceptance of TASK-221 / TASK-222. Never hard-code Windows
`G:/` (or other non-host) paths.

Command-level confirmation of the corpora remains deferred to TASK-043
serialized verification. Docs-only workers must not run the filters below.

## Host analysis environment (macOS only)

Verification notes and deferred filter examples on this host use **macOS** paths
only. Host path status is analysis-setup inventory only — not final inventory
and not a green claim.

| Item | Host value | Status (2026-07-12 TASK-512 re-check) |
| --- | --- | --- |
| JDK for deferred JVM filters | Corretto 17: `/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home` | Present; use as `JAVA_HOME` when review later runs verification under TASK-043 |
| Preferred Android platform jar | `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar` | **Present** (27,092,450 bytes; Android SDK Platform 35) |
| Optional drop-in jar | `/Users/dingyi/Downloads/android.jar` | **Absent** on this host; allowed only as explicit metadata when present |
| Android SDK root | `/Users/dingyi/Library/Android/sdk` (`ANDROID_HOME` / `ANDROID_SDK_ROOT` when set) | Present |

**Dual-path `android.jar` policy:** only Downloads + SDK `android-35` candidates
above. Prefer explicit metadata `jvm.androidJar` pointing at the SDK path for
reproducible analysis/LSP Android reflection. Product discovery may also scan
`ANDROID_HOME` / well-known roots (see [android-platform-setup.md](android-platform-setup.md)
and [language-server-usage.md](language-server-usage.md)); missing jars must
**skip** reflective Android mounting — never invent classpath entries. **Never
hard-code Windows `G:/` paths.** Concurrency/shutdown policy itself does not
require `android.jar`, but Android-Lua LSP stress that publishes diagnostics or
symbols under concurrent open/change may depend on host classpath configuration
when fixtures import Android types.

## Scope Boundaries

| In scope for this page | Out of scope / not claimed |
| --- | --- |
| Concurrent `didOpen` / `didChange` / `didClose` stress on `LuaLanguageService` + `LuaTextDocumentService` (TASK-221) | Arbitrary concurrent mutators across every semantic/workspace engine internal |
| Shutdown/exit idempotency and post-lifecycle request policy on `LuaLanguageServer` (TASK-222) | Full LSP4J transport thread-model guarantees |
| Coarse service locks observed in the current JVM code (`stateLock`, `lifecycleLock`) | “Thread-safe under any client scheduling” product marketing |
| Final diagnostics matching the last published snapshot after stress converges | Ordering of intermediate diagnostic publishes during races |
| Reviewed corpora that REVIEW accepted green historically | Standing guarantee that every future refactor remains green without TASK-043 |
| Host path / toolchain inventory for deferred filters | Final inventory bars (not final until TASK-043) |

**Do not treat this document as a full thread-safety certificate.** Safety claims
stop at the corpora and locks described below. Paths not covered by those tests
may still race, block longer than expected, or publish intermediate diagnostics
that are later overwritten.

## Lifecycle States (TASK-222)

`LuaLanguageServer` tracks a small lifecycle enum under `lifecycleLock`:

1. `CREATED` — constructed, not yet initialized.
2. `INITIALIZED` — successful `initialize`; text-document and workspace traffic accepted.
3. `SHUTDOWN` — successful `shutdown` after initialize.
4. `EXITED` — `exit` has run (with or without a prior shutdown).

Normal client sequence remains:

```text
initialize → initialized → (document/workspace traffic) → shutdown → exit
```

### Shutdown and exit idempotency

Policy under test in `LspShutdownExitIdempotencyTddTest`:

| Call | From state | Result |
| --- | --- | --- |
| `shutdown` | `INITIALIZED` | Completes with result `0`, transitions to `SHUTDOWN` |
| `shutdown` | `SHUTDOWN` | Completes with result `0` again (idempotent) |
| `shutdown` | `CREATED` | Completes exceptionally (cannot shut down before initialize) |
| `shutdown` | `EXITED` | Completes exceptionally |
| `exit` | any | Sets `EXITED`, clears the connected `LanguageClient` (no-op if already `EXITED`) |
| `initialize` | `INITIALIZED` / `SHUTDOWN` / `EXITED` | Completes exceptionally |
| Repeated concurrent `shutdown` | after initialize | Each call that still observes pre-exit state returns `0`; post-condition is shutdown policy |
| Repeated concurrent `exit` | after initialize | Converges to `EXITED`; further exit calls stay quiet |
| Interleaved concurrent `shutdown` + `exit` | after initialize | Converges to `EXITED` policy (quiet text-document requests, rejected workspace/lifecycle) |

`exit` without a prior `shutdown` is allowed and still reaches `EXITED`. After
exit, the client reference is null, so diagnostics are no longer published even
if internal services were still mutated (they should not be, because
notifications are gated).

### Post-shutdown vs post-exit request policy

Text-document and workspace **notifications** (`didOpen`, `didChange`,
`didClose`, `didSave`, `didChangeConfiguration`, `didChangeWatchedFiles`) are
ignored once the server is no longer `INITIALIZED`. No new diagnostics should be
published to the client after shutdown or exit.

Text-document **requests** (hover, completion, signature help, definition,
declaration, document highlight, references, document symbol):

| Lifecycle | Policy |
| --- | --- |
| `CREATED` | Quiet empty / null-style responses (not yet ready) |
| `INITIALIZED` | Accepted and delegated to the text-document service |
| `SHUTDOWN` | Rejected: futures complete exceptionally (`IllegalStateException`, already shut down) |
| `EXITED` | Quiet empty / null-style responses (not exceptional rejection) |

Workspace **requests** (`workspace/symbol`) are rejected once the server is not
accepting workspace traffic (after shutdown or exit). Lifecycle requests that
would resurrect the server (`initialize` after shutdown/exit, `shutdown` after
exit) complete exceptionally.

This asymmetric post-exit quieting of text-document requests is intentional and
covered by the TASK-222 corpus. Clients should still prefer the protocol order
`shutdown` then `exit` and stop issuing requests afterward.

## Concurrent Open / Change / Close Stress (TASK-221)

`LspConcurrentOpenCloseStressTddTest` stresses `LuaTextDocumentService` and
`LuaLanguageService` after a successful initialize. It does **not** exercise the
full `LuaLanguageServer` lifecycle gates; it focuses on service state under
rapid document churn.

### Expectations

1. **No service corruption under concurrent open/change/close.** Multiple
   threads may open, change, close, and query document symbols against the same
   URI (or a small multi-document set) without leaving the service unusable.
2. **Final diagnostics match the last published snapshot.** After stress stops
   and the harness applies a deterministic final `didOpen` (or final close), the
   diagnostics returned by `LuaLanguageService.diagnostics(...)` match the last
   `publishDiagnostics` payload for that URI (severity, message, range).
3. **Final content wins.** A last sequential open with known-valid source yields
   empty diagnostics and queryable symbols for that content, even if concurrent
   invalid edits raced earlier.
4. **Close clears overlays.** A final `didClose` publishes empty diagnostics for
   the URI and leaves no open-document symbols for that path (indexed disk
   sources, if any, remain a separate workspace concern — see language-server
   usage for folder indexing).
5. **Sequential rapid loops behave the same as concurrent ones** once a final
   open is applied: last published diagnostics and queried diagnostics agree.

### What the stress corpus actually does

Representative shapes (not an exhaustive protocol matrix):

- Single-document concurrent open / change / close / documentSymbol, then final open.
- Multi-document concurrent change / close-open / documentSymbol, then final open per URI.
- Concurrent close-all churn, then reopen with known content.
- Rapid sequential open/change/close loops with a final open.
- Concurrent invalid and valid full-text changes with a final valid open dominating.
- Concurrent churn ending in close, asserting empty publish and empty symbols.

Thread pools use a barrier so workers start together; versions are advanced with
atomics. The corpus treats intermediate publish order as unordered noise and only
asserts the **post-convergence** snapshot.

### Related but separate concurrency coverage

`LspServiceStateConcurrencyTddTest` overlaps document change, configuration
change, diagnostic republish, hover, document symbols, and workspace symbols on
the service layer. It reinforces that coarse locks keep overlapping requests from
corrupting state for that fixture set. It is **not** a substitute for TASK-221
open/close stress or TASK-222 lifecycle idempotency.

## Locking Model (Implementation Notes)

Current JVM code uses coarse object monitors rather than a free-threaded design:

| Component | Lock | Protects (summary) |
| --- | --- | --- |
| `LuaLanguageServer` | `lifecycleLock` | Lifecycle transitions, client connect/clear, initialize/shutdown/exit |
| `LuaLanguageService` | `stateLock` | Initialize, open/change/close, watched files, metadata, queries, diagnostics |
| `LuaTextDocumentService` | `stateLock` | Open-document cache, version gating for changes, publish path coordination |
| `LuaWorkspaceService` | `stateLock` | Configuration map, watched-file event list, workspace symbol entry |

Notifications on the server facade check `acceptsMessages()` (initialized only)
before delegating. Request wrappers consult `textDocumentRequestPolicy()` so
shutdown and exit produce the reject-vs-quiet behavior above.

These locks serialize mutating and querying paths that hold them. They do **not**
prove:

- lock-free or fully reentrant multi-client scaling;
- that LSP4J message dispatch threads never block on long analysis;
- that every future capability (rename, folding, code actions, etc.) is covered
  by concurrent tests;
- that intermediate diagnostics during a race equal any particular intermediate
  source version;
- that Android-Lua reflection / library-stub surfaces under concurrent edits are
  independently proven beyond what `stateLock` serializes (TASK-184 is product
  surface acceptance only).

## Client And Harness Guidance

1. Prefer one logical editor session per server process following the normal
   lifecycle. Do not re-`initialize` an existing process after shutdown/exit.
2. After `shutdown`, stop sending text-document and workspace requests; expect
   exceptional completion if any still land. After `exit`, treat the process as
   dead even though some quiet empty responses may still complete.
3. For open/change/close, send full-document sync consistent with advertised
   `TextDocumentSyncKind.Full` when possible. Ranged changes are applied in the
   text-document cache when versions are newer, but stress coverage converges on
   full replacements.
4. Treat published diagnostics as eventually consistent under concurrent edits:
   only the last publish after the client stops mutating is the contract asserted
   by TASK-221.
5. Do not run concurrent Gradle/test verification of these corpora outside the
   serialized TASK-043 slot.
6. When Android-Lua fixtures participate in concurrent stress, configure
   `jvm.androidJar` with the macOS SDK android-35 path (or an explicit Downloads
   override if that file is present). Do not rely on missing jars or non-host
   paths.

## Test Anchors And Deferred Verification

| Corpus | File | Policy focus |
| --- | --- | --- |
| TASK-221 | `src/jvmTest/kotlin/lsp/LspConcurrentOpenCloseStressTddTest.kt` | Concurrent open/change/close; final diagnostics match last publish |
| TASK-222 | `src/jvmTest/kotlin/lsp/LspShutdownExitIdempotencyTddTest.kt` | Repeated shutdown/exit; post-lifecycle refuse/quiet policy; concurrent lifecycle |
| Related | `src/jvmTest/kotlin/lsp/LspServiceStateConcurrencyTddTest.kt` | Overlapping document/workspace service operations |
| Related | `src/jvmTest/kotlin/lsp/LspShutdownBehaviorTddTest.kt` | Additional shutdown behavior fixtures (see that suite for details) |

Representative deferred verification filters (do not run in parallel worker waves;
TASK-043 serial only; macOS Corretto 17):

```bash
export JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home
./gradlew jvmTest --tests lsp.LspConcurrentOpenCloseStressTddTest
./gradlew jvmTest --tests lsp.LspShutdownExitIdempotencyTddTest
```

Optional host classpath context when a review run also exercises Android-aware
LSP paths under concurrency (explicit metadata preferred):

```text
jvm.androidJar=/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar
# optional only if present: /Users/dingyi/Downloads/android.jar
```

Review notes for TASK-221/TASK-222 record those filters as accepted under serial
review execution at historical review time. That acceptance is historical
evidence for the corpora that existed then; it is **not** a standing guarantee
that every future refactor remains green until TASK-043 re-runs the suite. This
docs refresh did **not** re-run those filters.

## Explicit Non-Claims

- **Not claimed:** the entire LSP stack is free of data races under arbitrary
  multi-threaded client abuse.
- **Not claimed:** intermediate diagnostics during concurrent edits are ordered
  or version-stable.
- **Not claimed:** workspace engine internals, reflection classloaders, or
  Android-Lua metadata providers are independently proven thread-safe beyond
  what holding `LuaLanguageService.stateLock` serializes.
- **Not claimed:** production readiness or full green suite status — those remain
  TASK-037 / TASK-043 concerns.
- **Not claimed:** inventory bars / recount fixtures are final — **inventory is
  not final until TASK-043**.
- **Not claimed:** TASK-184 product-surface acceptance unlocks concurrency or
  lifecycle certification; it does not.
- **Not claimed:** this document itself was command-verified; it is documentation
  only (TASK-512 docs refresh; earlier TASK-267 lineage). No final green
  acceptance is claimed by this page.

## Cross-Links

- [language-server-usage.md](language-server-usage.md) — launch, lifecycle
  overview, capabilities, document sync, workspace folders, configuration,
  macOS android-35 / Downloads dual-path notes.
- [android-platform-setup.md](android-platform-setup.md) — Corretto 17, SDK
  android-35 jar, skip-when-absent policy.
- [test-strategy.md](test-strategy.md) — inventory context for LSP corpora
  (inventory not final until TASK-043).
- [serialized-verification.md](serialized-verification.md) — why workers must not
  run the Gradle filters above during parallel implementation waves.
- [acceptance-traceability.md](acceptance-traceability.md) — AC-10 LSP lifecycle,
  shutdown, and concurrency traceability notes.
- [test-inventory-recount-procedure.md](test-inventory-recount-procedure.md) —
  recount procedure; inventory not final until TASK-043.

## TASK-512 refresh checklist

| Acceptance item | Where addressed |
| --- | --- |
| Post-TASK-184 / pre-TASK-043 accuracy | Honesty bound + Explicit Non-Claims |
| macOS host paths, Corretto 17, android-35 jar | Host analysis environment table |
| Dual-path Downloads + SDK; never `G:/` | Dual-path policy paragraph |
| Inventory not final until TASK-043 | Honesty bound, scope table, non-claims |
| Do not claim final green | Honesty bound, deferred verification, non-claims |
| Docs-only; no Gradle | Header + deferred filters labeled TASK-043 only |

When later review runs verification, use Corretto 17 `JAVA_HOME` and (for
Android-aware paths) explicit `jvm.androidJar` to the SDK android-35 path. That
work belongs to **TASK-043**, not this docs refresh.
