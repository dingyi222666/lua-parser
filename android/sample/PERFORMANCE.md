# Android Performance Verification

Measured on 2026-10-11 (Asia/Shanghai; remote logs use UTC). Performance acceptance remains open.

## Environment

- SSH host: `dingyi@10.1.1.14`.
- Workspace: `/home/dingyi/projects/lua-parser-android-e2e`.
- Android 28 x86_64 AVD `lua-parser-e2e28`, software emulation without KVM.
- Historical timings used two guest cores. After the remote host reboot, the
  emulator was started with eight requested cores and 3072 MiB RAM; Android
  reports six present/online cores (`0-5`): the emulator explicitly caps at six.
  Persisted AVD and runner settings now request six. This is a separate baseline.
- Gradle tasks use `--parallel --max-workers=8` remotely. Application full builds
  reserve two guest CPUs for the editor, so six guest CPUs select four front-end
  workers; worker count is capped at eight on larger devices.
- Remote host now reports 26 CPUs and 11 GiB RAM.
- The original TCG configuration executes guest CPUs on one host thread despite
  exposing six guest cores. An explicit `-qemu -accel tcg,thread=multi` experiment
  confirmed six active CPU execution threads, but took 581124ms to boot, hit ADB
  protocol faults, and timed out installing the test APK after 90 seconds even
  after reconnecting ADB. No application E2E completed under that mode. The runner
  retains the working six-core configuration without the experimental TCG option;
  more guest cores alone do not prove parallel host CPU execution.
- Debug APK; all Gradle builds and tests executed remotely.
- App data cleared before cold measurements. Emulator boot time excluded.
- No Gradle build ran during Android timing measurements.

Downloaded artifact: `sample-debug-latest.apk` at the repository root.
Local and remote SHA-256 match:

```text
100bc16665d7fbffc90fd6e787e2c567d2c74a752ec678ea39eba07e8d70b0d9
```

The later architecture/cache consolidation APK was also built remotely and
passed all six Android E2E tests. Its SHA-256 is
`90f624c5d39c7342e0d00ed794b194f2c1892ad7dba263199b44577531fb37be`.
Measured in the final run: dynamic DEX completion 4716 ms, first meaningful
completion 8999 ms, incremental completion 8702 ms, `GradientDrawable`
completion 9921 ms, colon completion 1276 ms, `activity.actionBar` completion
8233 ms, and semantic diagnostics 57609 ms. The six-test run took 295.92 s.
The cold first live snapshot requirement is still open; this run does not
establish HTML-level startup performance.

The current live-overlay APK was built remotely after replacing the interactive
overlay with a synthetic module index plus the single `_G.lua` documented type
surface. It passed all six Android E2E tests in 286.14 s. Its SHA-256 is
`8384bbabbbd9d92497c82aa6bc74f4bd9242051e82aa450f8f76cf3bb9cebc31`.
The cold launcher measurement observed the first live snapshot at 61396 ms and
the live analysis itself at 22820 ms. The same run measured dynamic DEX at
4945 ms, first meaningful completion at 9342 ms, incremental completion at
7105 ms, and semantic diagnostics at 41488 ms. Java `actionBar` completion
remained valid. HTML-level startup acceptance is still open.

The subsequent class-root candidate refinement APK was built remotely and
passed all six Android E2E tests in 289.996 s. Its SHA-256 is
`40dff0fa37bdeefc7b9359ba2e87d1b2d0d2dee948723f72223229e89133355c`.
That run measured dynamic DEX 4337 ms, first meaningful completion 8862 ms,
incremental completion 7506 ms, `actionBar` completion 7889 ms, and semantic
diagnostics 53282 ms. Cold first live snapshot was 66090 ms on that run;
software-emulator variance remains significant, so the 61396 ms measurement
above remains the cleaner comparison for the overlay-only change.

The lazy interactive import context APK was then built remotely and passed all
six Android E2E tests in 306.15 s. Its SHA-256 is
`da1d6536c65d3f7057e53410766c9a19d8d33a40c80c013c18fa99b5091f9e90`.
Cold first live snapshot measured 62756 ms and live analysis 19891 ms. Dynamic
DEX remained functional at 5396 ms; first meaningful completion was 7525 ms,
incremental completion 7789 ms, and `actionBar` completion 9749 ms. Ordinary
Java source imports are lazy, while DEX/path imports remain eager to preserve
library mounting semantics.

The production release APK (R8 plus baseline profile) was built remotely with a
matched release AndroidTest APK and passed all six Android E2E tests in
232.995 s. The release APK SHA-256 is
`9b7f29c1de8f5b6154025abf6b877f6cb549369e4e1a098b7eff35eb00994cc0`.
Cold release measurements: first live snapshot 36863 ms, Activity open complete
12724 ms, live analysis 13495 ms, dynamic DEX 2453 ms, first meaningful
completion 5173 ms, incremental completion 6131 ms, and semantic diagnostics
53146 ms. Release R8 keep rules were expanded for instrumentation-visible sample,
AndroidX lifecycle/activity/test, and Sora classes; this fixes release-only
NoClassDefFoundError/NoSuchMethodError failures.

## Current Measurements

The current APK is `100bc16665d7...`. The complete JVM run passed 2860 tests,
including the parallel cancellation/retry regression and the disk dependency Java
return-value regression. All six Android E2E tests passed in 315.551 seconds
under the stable six-core software emulator. First project completion was 10927ms,
warm completion 431-791ms, incremental completion 8823ms, popup 2303ms, and
semantic diagnostics 58017ms. The added export inference preserves the behavior
of existing declarations and did not demonstrate a material latency improvement
in this run.

The table below retains the preceding APK `d60d6253b18a...` measurement set;
its six tests passed in 284.210 seconds. It also uses single-thread TCG and must
not be combined with the new multithread TCG configuration.

| Measurement | Milliseconds |
| --- | ---: |
| Current APK cold launcher: first text observed | 15648 |
| Current APK cold launcher: LSP connection ready | 42123 |
| Current APK cold launcher: first live semantic snapshot | 69995 |
| Current APK warm Activity open complete | 5548 |
| Current APK first project completion | 10927 |
| Current APK warm completion, five requests | 431-791 |
| Current APK completion after editing | 8823 |
| Current APK visible completion popup | 2303 |
| Current APK semantic diagnostics after editing | 58017 |
| Current APK cached Lua document switches | 502-925 |
| Current APK first layout document switch | 3373 |
| Current APK cross-document dependency switches | 1093-2328 |

Normal launcher observations use the remote host's monotonic clock and log
polling. The launcher returned `Status: timeout` before the open-complete event;
Activity's internal open duration was 25637ms and excludes process/class loading
before its first timing mark. Connection readiness is **not** semantic readiness:
the first live snapshot arrived roughly 66 seconds after launch. The later E2E
workflow runs in a process warmed by other fixtures and cannot substitute for a
cold-process measurement.

The current APK cold launcher took 15648ms for first text, 42123ms for connection
readiness, and 69995ms for the first live semantic snapshot. The previous correct APK
`acf0a2219db8...` on two guest cores took 17355ms for
first text, 41484ms for connection readiness, 11892ms for first project completion,
7048ms for incremental completion, and 45593ms for diagnostics. These single runs
changed hardware configuration as well as code. Warm/incremental/popup timings
regressed, and neither increased cores nor parallel front-end analysis establishes
an overall speedup or HTML-level acceptance. The even earlier single-document
bootstrap omitted open files and cannot serve as a correct performance baseline.

Named-scope lookup now uses a lazily shared index per resolved binding, keeping
the original reverse declaration order and value namespace. Declaration-to-scope
lookup also uses an index, preserving first-scope precedence. This removes repeated
whole-scope list construction and repeated scope scans during evaluation and
type resolution. Raw and resolved binding tests check that their indexed
declarations remain separate; a nested-shadowing test checks self-initializers,
later declarations, outer-scope reads, and excluded declarations. The combined
preceding selected regression run passed 1754 tests. The latest full JVM suite
passed 2860 tests, including all 735 upstream grammar cases. Current Android
timings are recorded above; performance acceptance remains open.

## Code Changes

- Java constructor detection checks for a module before hydrating it. Ordinary
  Lua function types cannot become constructor modules, so their Java signature
  expansion now happens only on the normal function-call path. The preceding
  sampled editor workflow showed constructor detection repeatedly entering that
  signature hydration path; sampling timings do not establish an end-user gain.
- The real editor E2E accepts an optional `methodTrace` argument, sampling at
  5000us into a 32 MiB buffer and stopping through a JUnit watcher even on failure.
  Normal E2E leaves tracing disabled. Its initial trace-directory setup was fixed
  after clearing application data; the sampled editor workflow then passed.
- Full Android workspace analysis now uses bounded parallel front-end tasks for
  independent file parsing and binding/type declaration resolution. Each worker
  owns its parser and SemanticPipeline/TypeResolver; cache writes and snapshot
  publication happen on the engine owner, in deterministic path order. Import,
  dependency and cyclic-module analysis retain their ordered behavior. Single
  document live analysis retains its own serial cancellation context.
- Background full indexing waits for actual first-live-snapshot publication,
  replacing a fixed eight-second sleep that expired while the initial live parse
  was still running. No-document clients retain an eight-second fallback, and a
  blocked/changing live buffer cannot delay indexing beyond an additional thirty
  seconds. Connection close interrupts the wait.

- Removed the single-document cold-bootstrap shortcut. It analyzed only the most
  recently opened file but recorded every open file as synchronized; missing
  dependencies could therefore remain missing indefinitely. A remote regression
  reproduced the missing completion before the fix. Live builds now analyze all
  open buffers, and incremental deltas compare their text against the sources
  actually stored in the previous semantic snapshot.


- Exact Java binary names are tried before generating nested aliases. Package
  prefixes try their direct class name first; enclosing-class prefixes retain
  nested-name precedence. Candidate generation is lazy, with a bound on separator
  combinations for pathological missing imports.
- Live diagnostics check changed documents first and publish valid per-document
  results as they finish. Query snapshots still publish atomically. Repeated
  batch notifications for already-published documents are suppressed.
- Android method sampling identified initial provider loading, DEX parsing, and
  class lookup as substantial costs. The DEX parse appeared once in the sampled
  window, so it did not justify a speculative duplicate-parse cache change.
  Sampling timings are excluded from the measurement table.
- Sampling during incremental editing also identified repeated reflection signature
  conversion when inherited methods occur in multiple class surfaces. Signatures
  and generic metadata are now cached by their reflected executable, retaining
  declaring-class and class-loader identity. Parameter arrays are read once per
  conversion, and cached class references and names avoid cache-mapper allocations.
  The unprofiled measurements above do not establish an overall latency improvement.
- Reflected inheritance types now reuse results keyed by defining Class and
  expansion depth. Full and hierarchy-only expansion share one implementation,
  retaining existing depth bounds and transitive assignability information.
- DEX class-data parsing now resets field and method index accumulators for each
  separate list. Previously, instance fields and virtual methods continued the
  static/direct index chain, misreading or dropping real library members. The
  manually assembled fixture had repeated that assumption and was corrected.
  A real D8-generated fixture independently covers both list boundaries.
- Live-buffer DEX mounting now honors deferred member analysis, matching reflected
  class imports. Explicit classes and bare library catalogs retain class identity,
  aliases, provider paths, and guarded module names without converting every
  member. Querying a selected class loads its complete provider. Full background
  workspace analysis retains eager providers. Shallow and complete provider caches
  remain separate, and complete class providers supersede identity-only entries.
- Handshake-window sampling identified concurrent live/full builtin catalog
  construction and repeated documented-signature parsing as substantial work.
  Fixed standard-library resources now share their parsed signature templates;
  each analysis merges its own ranges and extra exports. The resource-only LuaJava
  surface is shared as well. ASTs, diagnostics, and caller-specific exports are
  not cached in these templates. Profile timings are excluded from the table.
- Incremental-edit sampling showed position indexes built during both raw binding
  and resolved binding, although type resolution does not use them. Binder position
  range indexes, scope depths, and declaration-order maps now initialize on first
  query. Each binding retains its own declaration index. The profiled workflow ran
  from a cold process and is excluded from the performance table.
- The default deferred/Android engine now publishes full resolved bindings before
  checker work. Its diagnostic worker handles open documents against both live
  and full workspace snapshots, prioritizes edited documents, and retains revision
  and binding guards. Opening/reopening unchanged indexed files schedules checks
  even when there is no source delta. Preliminary syntax reports are emitted before
  scheduling the checker, avoiding overwrite of a fast fresh checker report.
  Synchronous JVM analysis retains its existing eager-check behavior. Caller-supplied
  engines retain their own checking policy. Unopened-file/project-wide diagnostic
  coverage remains an explicit production verification item; this change is not
  evidence that such a workflow is complete.
- Full-workspace diagnostic checks now compose the worker's document/binding
  cancellation guard with the engine checkpoint. Previously the full engine only
  checked connection closure while the checker walked expressions; an obsolete
  document could continue consuming CPU until its final publication was rejected.
  Checked snapshots restore the engine context afterward so the temporary worker
  closure is not retained in query snapshots.

## Verification

- Current HTML/Monaco protocol smoke passed on the remote host: initialization,
  meaningful custom+builtin completion, Lua/ALY completion, references, highlights,
  selection ranges, formatting, rename, inlay hints, semantic tokens full/delta
  and workspace symbols. Initialize round trip including Gradle/JVM launch was
  13446.4ms (service internal 21ms); project completion round trip was 199.9ms
  (service internal 32ms), and ALY completion was 98.5ms (internal 2ms). This uses
  native host JVM execution, while Android uses software-emulated guest execution;
  those are different environments, and Android has not reached that response level.

- All 2860 JVM tests passed, including LSP, DEX/Java interop, workspace, semantic
  pipeline, binder, type resolution, lexer/parser, source printing and all 735
  upstream grammar cases. All 21 deferred-analysis tests passed.
- Parallel-analysis regressions force actual parser overlap with a latch, compare
  serial/parallel diagnostics, fingerprints and completion through cross-file
  edits/removals, verify equal source files own distinct ASTs, and cancel a worker
  before retrying a complete build. Dependency cycles and Java imports are covered.
- A startup scheduling regression blocks the first foreground parser for longer
  than the old fixed delay, verifies full indexing has not started, then releases
  it and verifies both current completion and the eventual full index.
- Six Android E2E tests passed: colon/partial-colon completion, newline-dot fluent
  completion, `activity.actionBar` typing, real completion popup, incremental member
  replacement, current diagnostics, Lua/layout switching, format/save, Activity
  recreation, highlighting, and unsaved dependency edits across file switches.
- Runtime DEX E2E verifies members are absent before import, copies an independent
  library into the connected project's libs directory, imports its absolute path,
  and checks static methods, a Java factory result inside a Lua table, partial
  colon completion, and actual popups. The class is absent from both APK classpaths.
  Relative imports, removal/replacement, and all dynamic-loading cases remain
  broader verification work; this test does not prove those scenarios.
- Diagnostic reuse tests preserve unchanged warnings and refresh consumers when
  a required module's return type changes, including clearing the resulting error.
- New concurrency tests block an unfinished checker, verify earlier diagnostic
  delivery, and edit during that block to verify replacement with current results.
  Class lookup tests verify direct imports avoid failed probes and preserve
  dotted, underscore, and enclosing-class aliases.
- Inherited-method tests verify generic constraints, varargs, overload metadata,
  both subclass load orders, and covariant overrides without parent-return leakage.
- Hierarchy load-order tests compare shared-provider results against fresh-provider
  results when an ancestor is first loaded at a deeper expansion depth.
- Deferred DEX tests verify constructor/static/instance members hydrate from class
  identities and library catalog entries. A live LSP test follows a library catalog
  class factory result through a Lua table to colon completion.
- Template reuse tests supply different analysis ranges and duplicate extra exports,
  verifying caller isolation, documented signature precedence, first-export
  precedence, fallback metadata, and absence of caller exports in the live template.
- Position-query tests query raw binding before type resolution, then verify the
  resolved binding returns typed declarations while the original binding remains
  intact. Scope and symbol results remain consistent across those bindings.
- Deferred full-index tests block a cold diagnostic worker, verify complete
  cross-file queries still publish, then verify a dependent type error appears and
  clears after editing. Unchanged indexed-file tests verify first-open warnings,
  close clears, and reopening restores the warning without a source change.
- A cancellation regression blocks an indexed document's first member evaluation,
  edits its buffer, and verifies that the obsolete checker stops before walking
  250 old member accesses while the replacement document's warnings are delivered.
- The complete JVM suite passed: 2860 tests, zero failures/errors/skips, including
  all 735 pinned upstream luaparse cases. Strict parsing now enforces loop and
  function boundaries for break/varargs, rejects parenthesized assignment targets
  and incomplete colon expressions, and validates goto label visibility and local
  lifetimes. AndroLua switch control flow and editor recovery remain covered.
  The original extendedIdentifiers option on local:55 was restored without
  changing any acceptance expectation. Remaining strict failures were product
  gaps, not evidence of intentional AndroLua dialect differences.

- The cold-bootstrap regression opens a dependency while the first analysis is
  blocked, then verifies completion in both files and replacement after editing.
  Android E2E also switches through a helper and consumer, edits the helper without
  saving, and verifies the consumer reads that unsaved buffer instead of disk.

Remote evidence (the host reboot removed older `/tmp` logs; their results were
read before that reboot, while new runs are retained under workspace `build/`):

```text
build/constructor-gate-full.log
build/constructor-gate-e2e.log
build/constructor-gate-logcat.log
build/current-sampling-build.log
build/current-sampling-e2e.log
build/current-sampling-logcat.log
build/current-editor.trace
build/current-editor-profile.txt
build/emulator-mttcg.log
build/emulator-six-stable.log
build/deferred-export-apk.log
build/deferred-export-e2e.log
build/deferred-export-logcat.log
build/final-full-jvm.log
build/java-export-inference-focused.log
build/java-export-inference-rerun.log
build/deferred-export-cold-launch.log
build/final-html-smoke.log
build/mttcg-test-build.log
build/parallel-focused.log
build/parallel-full-jvm.log
build/parallel-cancellation.log
build/parallel-android-e2e.log
build/parallel-android-logcat.log
build/parallel-cold-launch.py
build/parallel-cold-launch.log
build/parallel-html-smoke.log
/tmp/lua-global-index-full.log
/tmp/lua-global-index-e2e.log
/tmp/lua-global-index-logcat.log
/tmp/lua-global-index-cold-launch.log
/tmp/lua-bootstrap-repro.log
/tmp/lua-bootstrap-fixed-tests.log
/tmp/lua-bootstrap-correct-full.log
/tmp/lua-bootstrap-final-apk.log
/tmp/lua-bootstrap-correct-e2e.log
/tmp/lua-bootstrap-correct-logcat.log
/tmp/lua-bootstrap-cold-launch.log
/tmp/lua-strict-complete-jvm.log
/tmp/lua-strict-zero-build.log
/tmp/lua-strict-zero-e2e.log
/tmp/lua-strict-zero-logcat.log
/tmp/lua-current-checker-final-build.log
/tmp/lua-current-checker-focused.log
/tmp/lua-current-checker-e2e.log
/tmp/lua-current-checker-logcat.log
/tmp/lua-current-checker-launch.log
/tmp/lua-shared-overlay-launch.log
/tmp/lua-edit-window.trace
/tmp/lua-edit-window-profile.txt
/tmp/lua-edit-window-logcat.log
/tmp/lua-edit-window-e2e.log
/tmp/lua-handshake-sampling.trace
/tmp/lua-handshake-sampling-profile.txt
/tmp/lua-handshake-sampling-logcat.log
/tmp/lua-hierarchy-cache-build.log
/tmp/lua-hierarchy-cache-e2e.log
/tmp/lua-hierarchy-cache-logcat.log
/tmp/lua-first-analysis.trace
/tmp/lua-first-analysis-profile.txt
/tmp/lua-incremental-analysis.trace
/tmp/lua-incremental-analysis-profile.txt
/tmp/lua-incremental-profile-e2e.log
```
