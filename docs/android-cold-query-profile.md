# Android cold query profile — 2026-10-12

All Gradle commands and Android instrumentation ran on `dingyi@10.1.1.14`.
The API 28 software emulator has six cores. Its boot time is excluded from app timing.

## Confirmed expensive stages

1. First resource revision construction runs on `lsp-document-refresh`. The captured
   `/data/anr/trace_11` has roughly 30 seconds of thread CPU in resource declaration evaluation
   and `BinderPositionQueries.valuesByScopeAndName`. A newer buffer revision waits for this
   process-wide immutable resource initialization to finish. This is not evidence of a deadlock.
2. After the current buffer is published, `/data/anr/trace_13` captures `lsp-completion` in
   `DexByteReader.readMutf8 -> parseStrings -> parseDex -> DexLibraryMounter.libraryFor`.
   This is the first mount of the real packaged framework DEX, not a fabricated Java surface.
3. The UI may exceed Sora's completion timeout even though a later direct request succeeds.
   The popup test remains required; direct LSP completion is not sufficient acceptance.

## Changes under validation

- API type handles use object identity, avoiding recursive hashing of Java member graphs while
  preserving distinct same-named surfaces.
- Each binder scope owns a lazy name index, so querying one scope does not initialize all scopes.
- ASCII DEX metadata strings use direct decoding. Non-ASCII, modified NUL, surrogate and malformed
  sequences retain the validated MUTF-8 path.
- Concurrent mounts of one file revision share a future. The file revision cache still includes
  absolute path, modification time and length; different libraries may load concurrently.
- Background diagnostics own a separate resolver over the same immutable workspace revision.

The identity/Dex parser/mounting regressions and focused dependency regressions passed remotely.
The first full regression exposed a concurrent cold dependency-global completion returning no
members (2,921 tests, one failure); retain `/tmp/lua-dex-fast-full.log` as failed evidence.
Full regression after diagnostic-resolver isolation is recorded in
`/tmp/lua-dex-isolated-full.log`. Android timing for these final changes is still pending.

## Rejected experiments

Caching members by display name could conflate distinct same-named types and was removed.
Interrupting the document-refresh worker caused later revisions to retain an interrupt flag;
the experiment was reverted. Moving resource initialization to Application prewarming would
shift rather than remove the full analysis cost, so that experiment was also removed.

The startup/first-completion performance gate and net reduction of 7,000 production Kotlin
lines remain open. No result in this profile proves overall completion.

## Next architecture revision

The static declaration catalog used one binding for exported members and another for the
resource file snapshot. The latest source reuses a complete declaration parse/binding revision
and passes prepared binders into member extraction. The separate standard-member cache and
range merge loop were removed. Focused resource/provider tests passed remotely in
`/tmp/lua-resource-single-binding.log`. The full two-worker run in
`/tmp/lua-single-resource-full.log` failed 1/2,927 tests; the fixture ignored the publication
result after the background build wait. An unpublished snapshot is suspected, not proven. Its isolated rerun passed. Publication is now
asserted, and `/tmp/lua-single-resource-serial-full.log` records the full one-worker rerun.
The one-worker full rerun passed 2,927 tests with zero failures/errors/skips and JS/APK tasks.
No Android timing claim is made yet.

Java nested names previously generated every subset of dot-to-dollar rewrites. Candidate
search now follows legal enclosing-class boundaries; DEX uses a canonical-name index directly.
The former 12-segment DEX cutoff was removed and deep-package aliases are covered by regression.

## Verified final observations for this revision

`/tmp/lua-single-resource-serial-full.log`: 2,927 JVM tests passed, zero failures/errors/skips;
735 upstream luaparse cases included; JS and debug/test APK tasks passed.
`/tmp/lua-single-resource-e2e.log`: Android 7/7 passed in 304.082s.
`/tmp/lua-single-resource-logcat.log` contains actual client/query/popup and lifecycle timing:

| Observation | Milliseconds |
| --- | ---: |
| First valid table completion | 6,188 |
| Warm table completion | 268–436 |
| Incremental completion | 13,896 |
| Visible completion popup | 2,483 |
| Diagnostics after edit | 14,145 |
| Layout file switch | 2,621 |
| First Drawable completion | 12,349 |
| First ActionBar completion | 10,060 |
| Warm ActionBar completion | 246–278 |

The lifecycle test ran after other methods had warmed the process; its editorReady=13,131ms
is not a fresh process startup result. Emulator reboot=145,470ms is excluded from app timing.
Background publication changed ready=false to ready=true during incremental completion; the
first full-revision query spent 8,968ms and the next 840ms. This is a concrete next profiling
target: publication replaces query/evaluator caches and recomputes the receiver surface.
No claim of HTML-level performance or completed 7,000-line reduction is supported.

Remote `/tmp/lua-single-resource-monaco.log` also passed the HTML/Monaco protocol smoke with
real table.addObserver and layout completions, references/highlight/selection, range formatting,
rename, inlay hints, semantic tokens full/delta and workspace symbols.

The final DEX surface revision kept Android E2E 7/7 green. It reduced the measured first valid
table completion to 5,930ms in this run, but incremental completion was 15,126ms and first Java
member completions remained 8–11 seconds. Warm completions stayed sub-second. The next bottleneck
is the full workspace snapshot publication/query facade rebuild, not class-name enumeration alone.

The fixed-width DEX revision preserved JVM/JS/APK green but did not pass Android behavioral
acceptance: `/tmp/lua-query-byte-e2e.log` has 1/7 popup timeout. The server returned the
expected member after the client timeout, so content correctness and interactive latency remain
separate gates.

## Event-triggered trace 15: named lookup forces unrelated global hydration

`/tmp/lua-query-byte-event-trace.txt` (remote `/data/anr/trace_15`) finally captures the actual
partial-member slow request. `lsp-completion` has about 10.4s accumulated thread CPU and is in
DEX class/reference surface construction. Its caller is not the desired receiver: the stack
continues through WorkspaceModuleResolver.allSharedGlobalSymbols -> importedVisibleDeclarations
-> visibleValueDeclarations -> findNearestVisibleValueDeclaration -> localJavaMemberInitializerSymbolAt
-> getSymbolAt -> requireBackedExportPath -> workspaceMemberCompletions.

A lookup for one ordinary local was enumerating and deriving every project global before
determining that the local was not require-backed. The named lookup now checks local declarations
and then resolves just the requested import name. A regression verifies no unrelated provider
resolution for a local member query, while full lexical completion still derives shared globals.
Focused `/tmp/lua-named-lookup-focused.log` passed. Full latest `/tmp/lua-named-lookup-full.log` passed 2,933 tests and JS/APK builds.
Android validation is `/tmp/lua-named-lookup-e2e.log`; no speed claim is made before that run finishes.

Final named-lookup revision Android E2E 7/7 passed (283.335s). The previous failed run and trace
remain necessary evidence: eliminating the named-global enumeration restores the Lua popup,
but incremental table completion still measured 18,729ms and popup 10,050ms. No overall speed
acceptance claim is made. Query revision publication and lexical requests still need profiling.

Unified lexical/member query index revision passed 2,934 JVM tests and Android E2E 7/7.
`/tmp/lua-query-index-logcat.log` measured incremental completion at 20,234ms and popup at
10,202ms despite first table completion 5,809ms and warm table 448–808ms. Position-rule
consolidation removed duplicate scans but cannot support a claim of overall performance success.

## Live-diagnostics cold-path profile — 2026-10-12 (round 2)

Method: release APK on the remote API 28 emulator with temporary server-side timing
logs (`liveDiagnostics begin/checked`, checker-stage split, slow-expression log ≥200ms).
Reproduces the two open E2E failures that also fail at clean HEAD
(`dynamicallyImportedDexCompletesJavaObjectsInsideLuaTables`,
`openCompleteEditSwitchFormatSaveAndRecreate`).

### Measured chain for a diagnostics-after-edit publication

1. Live analysis after didChange: 1.2–1.5s (release) — healthy.
2. `scheduleLiveDiagnostics` worker: check of main.lua takes up to **9.0s**
   (`checked main.lua in 9015ms`); the stage split attributes nearly all of it to
   `ExpressionUsageChecker.check` (expr=**6,275ms**, sigReturn ≤81ms, decls ≤62).
3. Each didChange during a 9s check produces a new binder; the obsolete check is
   dropped (begin-without-checked) and the work restarts, so the 10s client budget
   expires before any checker warning is published.
4. Debug builds multiply stage 1 to ~10.2s and fail 5/7 — the timing gates must be
   judged on release builds only.

### Slow-expression profile (same checker run)

- `D @[141,21]` (Java-instance hydration path): **3,980ms / 2,625ms / 1,913ms /
  1,177ms / 1,093ms** — the same fixture expressions re-evaluated per distinct
  lexical `Context`.
- `F @[84,1]` 3,402ms / 2,889ms; `F @[28,1]` 3,849ms; repeated `j/M @[185,12]`
  evaluations at 200–1,400ms each.
- The evaluator caches are per-instance (`expressionTypeCache`,
  `contextualExpressionTypeCache` keyed by `(node, Context)`, and the AndroLua
  hydrated-surface caches), and `Context` is a clean data class — so the repeats
  are per-scope typings, each paying a fresh Java/DEX member-surface hydration
  that costs seconds on this CPU. The live-diagnostics worker builds a NEW
  `WorkspaceModuleResolver` + evaluator per work item, so none of that work is
  reused across checker runs (deliberate cache separation vs. the query resolver).

### Required architecture fix (next round)

Move the expensive Java/DEX member-surface hydration behind a cache owned by the
**workspace revision** (e.g. on `WorkspaceModuleGraph` / the snapshot's provider
objects, keyed by class identity and invalidated with the snapshot), so the
checker's evaluator, the query resolver and completion share one hydration result
per (class, revision) without sharing mutable caches. Target: checker cold path
for the fixture workspace ≤2s so a warning publishes within the 10s budget even
after one invalidation. Same mechanism should cut the dynamic-DEX first
completion (currently ~5.7s server-side in the failing test).

## Round 3 — declaration-scope type derivation + E2E churn finding

Landed (commit `2a91805`): `typeOfDeclaration` now derives and caches in the
declaration's OWN scope (loadlayout ids sinks exempted — they bind the layout call
visible from the caller). JVM profile under the device dex configuration (new
`lsp.LspLiveDiagnosticsLatencyProfileTest`): warm expression stage 65ms → 50ms;
hot repeats (68ms/47ms/43ms) collapsed. Full remote JVM suite green (2,925/0/0).

Measurement infrastructure note: the same workload costs 70–102ms on the JVM vs
6,275–9,015ms on the device — the E2E emulator runs with `-accel off` (TCG
software emulation, ~70×). Device budget conclusions must account for that ratio;
a 50ms JVM checker ≈ 3.5s on device.

### Remaining device-side failure mechanism (next round)

Post-optimization release E2E is still 5/7 with the same two tests. Timeline from
the instrumented earlier run shows the live-diagnostics worker fighting the
document-refresh worker:

- The awaited edit's check is repeatedly superseded: each live-analysis republication
  replaces the workspace snapshot with a new binder for the edited file, so the
  in-flight checker (even when aborted promptly through `analysisCheckpoint`)
  restarts against churn instead of finishing.
- The worker also begins stale work items (`begin mods/dingyi.lua` ×3 without
  `checked`) between the awaited schedule and its final 9s run — cancellation works,
  but the restart cost is paid in full each time because all evaluator-level caches
  die with the dropped attempt.

Next step: coalesce — when a superseding snapshot exists, restart the check ONCE
against the newest revision with warm revision-owned caches, and let the refresh
worker hand its result directly to the diagnostics worker (single publication
instead of analysis → schedule → re-check). Then re-measure on device.

## Round 4 — content-based diagnostics currency: 6/7

Landed (commit `de7740d` + follow-up): the live-diagnostics worker now keys
checker-result reuse and currency by document CONTENT (path, source) instead of
binder identity, storing diagnostic lists only (no stale binder graphs pinned).
Refresh republications no longer abort valid in-flight checks; rebinds reuse the
cached report.

Device E2E on the now-idle server: **6/7** (first run after the fix on a loaded
server: 5/7 with a different failing test — the TCG emulator's ~70× ratio makes
these gates load-sensitive). The diagnostics checker-warning await now passes;
visible popup 2,400ms (was ~10,000ms in the last accepted run); first valid
completion 2,731ms; ActionBar warm 329-576ms.

Also landed this round: DexClassModelAdapter LRU bounds 128 → 512 (a completion
sweep evicted the checker's hot framework closure; measured 2.4-8.5s re-assembly
per check) and `largeHeap` for the sample harness.

### Remaining failure (next round)

`dynamicallyImportedDexCompletesJavaObjectsInsideLuaTables` — completion times out
while the dynamically imported project dex (mounted via `jvm.classpath`) is
hydrated for the first time on device (same class of cold first-touch as the
framework dex was). Next: profile the first dynamic-dex completion
(server-side `CompletionThread` timeout observed), then apply the same
demand-hydration/cheap-first pattern that fixed the framework surface.

## Round 5 — configuration-time dex warmup; dynamic-dex mount bug isolated

Landed: `warmUpConfiguredDexLibraries` — the LSP service now pre-mounts the
configured framework/classpath dex files on a background thread at
`setWorkspaceMetadata` (configuration push) instead of lazily on the first
framework member query. Previously the 5,981-class framework parse (9.2-10.3s on
the TCG emulator) started ~11s into a test's life, mid-await, and blocked every
framework-touching phase through the shared mount future. It now starts at
service construction and completes before the interactive phases.

Device E2E held at **6/7** (identical failing test across runs — deterministic now).

### Isolated: the dynamic-dex test never mounts its dex

Instrumented run evidence for
`dynamicallyImportedDexCompletesJavaObjectsInsideLuaTables`:

- `DexLibraryMounter.readClasses` (the single parseDex path) logged ZERO attempts
  for `runtime-e2e.dex` — the engine never even tries to mount the dynamically
  imported library during the awaited phase.
- The awaited phase's first completion took 9,587ms (blocked on the refresh
  worker's engine update) and returned 68 lexical items; subsequent polls returned
  the 9-item lexical surface only — the imported library never enters the snapshot.
- The await therefore expires regardless of the framework warmup.

Next round: instrument `JvmClassModuleProvider.importedSymbolForTarget` /
`importedClassNames` for file targets (`.dex`/`.apk`) and the `extraProviders`
mount path, and verify the source-import fact target for the quoted
`import '<path>'` form reaches activation unquoted. The regression suspect is the
import-activation file-list construction in `activateImports`
(`text.endsWith(".dex")` on a target that may still carry quotes) and/or the
provider's file-target branch being skipped under
`deferDependencyGlobals`/`deferWildcardPackageIndex` in the live-buffer engine.

## Round 6 — baseResolve* fallback fix + JVM dynamic-dex mirror

Landed (commit `0ec6a9f`): `JvmWorkspaceEngine.workspaceContext` now publishes its
resolver-free import lambdas as `baseResolveImportedSymbol` / `baseResolveImportTarget`.
The live-diagnostics checker rebuilds its context with the composed resolve* lambdas
nulled and `withWorkspaceImportEffects()` recomposes from the base fallbacks — which
nothing ever set, so checked models could only resolve deferred shells: a dynamically
imported dex class could never hydrate its member surface in any checked model.

New JVM mirror test (`lsp.LspLiveDiagnosticsLatencyProfileTest.dynamic_dex_import_...`):
deferred service mode + `import '<runtime-greeter.dex>'` + `bindClass` + member
completion — passes; the module surface hydrates 3f/2m (create, libraryMarker).
Provider-level probe (`DynamicDexProviderProbeTest`): bare library import then class
resolution returns the full surface — the provider chain was never broken; only the
context fallback wiring was.

Remote validation: full jvmTest 2,927 tests 0 failures/errors/skips.

### Device E2E after the fix: still 5/7, failing tests vary run to run

rel14 failures: the dynamic-dex test now times out at its FIRST completion
(`complete(scenario, 1, 8)` after setText — before the dex import phase), and the
lifecycle test at its own timing gate. Combined with earlier rounds: whichever
phase first requests a cold completion/check after content changes absorbs a
multi-second server-side enumeration (measured 8-9.6s completions with 68-item
lexical surfaces), and the 10s per-await budget trips on whichever phase lands
there. The TCG ratio (~70×) makes the first-completion cold path the remaining
gate: JVM-equivalent work is ~100ms.

### Next round

Attack the cold first-completion enumeration itself: the 68-item lexical surface
that takes 8-9.6s on device (~120ms JVM) — profile its stages (shared-global
hydration vs candidate assembly vs adapter memo warmth) with the same
JVM-mirror methodology, then apply per-revision caching for the enumeration so
the first completion after any edit is warm-path cheap. Also re-check the
completion executor serialization (single lsp-completion thread queues a fresh
request behind an in-flight 8s one).

## Round 7 — remaining gates quantified; lifecycle test green on device

Instrumented release E2E (queue-wait + per-request completion logging):

- Device E2E **6/7** — `openCompleteEditSwitchFormatSaveAndRecreate` (the
  lifecycle/format/save/recreate gate) now passes; the only remaining failure is
  `dynamicallyImportedDexCompletesJavaObjectsInsideLuaTables`.
- The two stacked costs behind the remaining timeouts are now measured:
  1. **Completion serialization**: the single `lsp-completion` executor queues a
     fresh request behind an in-flight one — measured **6,515ms queue wait**, then
     the request's own execution took another **7,433ms** (items=68, live-buffer
     state) — the client's 10s await covers both.
  2. **Cold live-buffer model derivations**: each live-analysis republication
     rebuilds models with fresh per-evaluator caches, so the first completion
     against a refreshed snapshot re-derives every declaration (JVM equivalent
     ~100ms; device ~7s at the TCG ratio).

Next round candidates (in order):
1. Supersede-aware completion execution: a newer request for the same buffer
   cancels the in-flight/queued one instead of queueing behind it (the client
   polls; only the newest request's result matters).
2. Content-keyed declaration-derivation cache at the liveBufferEngine level so
   restart-after-abort and republication-after-refresh reuse derived types for
   unchanged declarations (the revision-owned cache identified in round 3).
3. The dynamic-dex first-touch mount (round 5's isolated activation-path issue).

## Round 8 — cancellable per-document analyses; device variance persists

Landed (commits `3e04f5f`, `36cfaee`, `ef2bfbc`): the analysis checkpoint now fires at
every per-file analysis boundary (build loop, update loop, per-document model-build
loop) and between the pipeline's per-document phases (bind → checker → model) —
previously it fired only inside type evaluation, so a superseded document analysis
ran 10s+ to completion on the emulator while the newer content waited.

Full remote JVM suite green throughout. Device E2E remains timing-limited: rel18/rel19
landed 5/7 with the same two tests (dynamic-dex, lifecycle) failing on timing gates
that flip run to run. 16MB logcat buffer added to the harness (`adb logcat -G 16M`) —
the default ring buffer was evicting early-test evidence.

### Open items for the next rounds (evidence in this file)

1. The dynamic-dex test's first await races the cold live-buffer analysis of the
   project fixture (didOpen 5,017 chars at T+0; test setText at T+1s; the fresh
   analysis must complete/abort and republish within the 10s budget under the 70×
   TCG ratio + heavy allocation rate (4-5MB freed per ~2.5s GC cycle)).
2. Completion serialization: single `lsp-completion` executor (6.5s queue waits).
3. The revision-owned derivation cache (rounds 3/7 design) to make republication
   after abort warm.
4. The −12,000-line target: ~2,800 lines remain (audit-backed items queued).

## Round 8 addendum — bounded-parallel completion executor

Landed (commit `2aa22f1`): the completion executor now runs 2 workers (read-only
facade queries; the bound keeps device CPU for analysis workers). The measured
6.5s queue wait behind a stale in-flight enumeration can no longer delay the
newest request. Device E2E unchanged at 5/7 this run — the dynamic-dex test's
first await still races the cold live-buffer analysis of the 5KB project fixture
(didOpen at T+0, test setText at T+1s, the fresh analysis must abort-and-republish
within the 10s budget).

Full remote JVM suite green (lsp suites re-validated after the executor change).

### Remaining round-9+ plan (unchanged, evidence above)

1. The revision-owned, content-keyed derivation cache — makes abort-and-republish
   warm (the decisive fix for both remaining tests).
2. The dynamic-dex activation path instrumentation on device.
3. The −12,000-line dedup items.

## Round 10 — dedup: shared access-path descent + facts-driven require verification

Landed (commits `b40a3d8`, `0e96658`):
- `hasBuiltinRequireCallSite` verifies the query's module name against the collected
  `RequireFact` ranges through the same `builtinRequireModuleName` un-shadowed test
  the former ~135-line full-chunk statement/expression walk applied (the walk was a
  per-query AST traversal duplicating what DocumentFacts already records per edit).
- One shared `extractAccessPathSegments` descent now feeds both
  `DocumentFactsCollector` (require alias/module-environment access paths) and
  `ModuleExportCollector` (export write anchors + alias bindings); `WriteTargetBase`
  and both bespoke base walkers are deleted.

Remote validation: semantic+lsp 1,851 focused tests green; full jvmTest 2,928/0/0;
device E2E 5/7 (the two timing-gated tests, unchanged by these refactors — their
gates are the cold-analysis/derivation costs documented above).

Production Kotlin: **45,582** (baseline 53,562; −7,980; −12,000 target continues).

## Round 11 — single-owner .aly provider recovery

Landed (commit `9a47fff`): `findAlyLayoutProviderIn` / `alyModuleNameFromPath` are now
one shared internal pair serving the graph builder's build-time claim and the
resolver's fallback lookup (the resolver only differed in reading snapshot.files vs
the builder's graph files). Full remote JVM suite green (2,928/0/0).

Deferred with rationale: the facade's LuaJava helper-detection block (query audit #2,
~110 lines) — delegation to the evaluator's stronger recursive implementation requires
exposing evaluator internals through the model and re-validating behavior parity for
navigation targets pinned by the interop suites (the facade copy is deliberately
weaker in its declaration-chain depth); queued as a dedicated change with its own
validation cycle.

Production Kotlin: 45,563 (baseline 53,562; −7,999).
