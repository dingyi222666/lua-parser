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
