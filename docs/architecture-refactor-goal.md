# Industrial analyzer refactor

This document is the execution contract for the current architecture work. It expands the
existing Android/LSP goal without changing the public parser and language-server contracts.
Scope updated on 2026-10-11: optimize the complete analysis architecture, including comment
parsing, type-system correctness and performance, with industrial-level regression evidence.

## Required outcomes

- Keep all Gradle builds, JVM tests, JS compilation and Android instrumentation on
  `dingyi@10.1.1.14`; local Gradle execution is prohibited.
- Preserve parser recovery, Lua/AndroLua dialects, Java/Lua completion, dynamic DEX loading,
  incremental edits, diagnostics, formatting, save and activity recreation.
- Make the analyzer revision based: one source revision owns its parse result, document facts,
  exports and reusable binding inputs. Query and diagnostics must consume the same published
  revision instead of rebuilding state independently.
- Make comments and type annotations first class analysis inputs. A comment edit invalidates the
  affected declaration/type surface and its dependants, while an unrelated text edit reuses the
  old comment/type work.
- Remove duplicate AST walks, duplicate import/type bridges, obsolete compatibility paths and
  ad-hoc lifecycle state. The target is at least 7,000 fewer production Kotlin lines, measured
  with `find ... -name '*.kt' -print0 | xargs -0 wc -l`; deletion is accepted only when the
  corresponding contract tests remain green.
- Improve interactive latency through invalidation and demand driven Java/Dex hydration. Thread
  parallelism is an implementation detail, not the architecture.

## Target layers

1. **Front end**: lexer and parser produce one immutable `LuaParseResult` per source revision.
2. **Document index**: one AST traversal produces comments, imports, graph facts, export hints and
   read roots; the result is cached by `(path, source fingerprint, dialect)`.
3. **Semantic revision**: comment attachment, binding and type resolution produce immutable
   document state. Checker and query models consume that state without reparsing.
4. **Workspace revision**: the dependency graph computes a dirty component; unchanged document
   revisions and provider surfaces are reused by identity.
5. **Interop providers**: class identity, hierarchy and member surface are separate demand caches;
   reflection and DEX enumeration are never prerequisites for an open-buffer query.
6. **LSP adapter**: transport, editor lifecycle, query publication and diagnostics are separate
   owners. A document generation tags every asynchronous result.

## Comment and type-system acceptance

- Audit comment grouping, attachment and annotation parsing together; share parsed annotation
  syntax within a document revision without sharing mutable AST nodes between documents.
- Keep one canonical type grammar and internal type representation. Compatibility facades must
  delegate to them; remove duplicated implementations only after checking their callers.
- Preserve generic substitution, recursive aliases, unions/intersections, function overloads,
  varargs, table shapes, Java inheritance and assignability, and positions in diagnostics.
- Cache context-free type syntax separately from scope-dependent type resolution. Keys must
  include lexical/type scope or resolver generation when required; identical text alone cannot
  justify reusing a resolved type across scopes.
- Bound recursive expansion and invalidate caches on annotation, dependency or class-loader
  changes. Cancellation and obsolete-revision checks apply to expensive analysis.
- Measure allocations, AST visits, type parsing/resolution counts and cold/incremental query
  latency. Compare identical fixtures and report any lost capabilities as regressions.

## Refactor sequence

| Phase | Boundary | Acceptance evidence |
| --- | --- | --- |
| A | document revision store and one-pass facts | reuse test, parser corpus, remote JVM/JS |
| B | comment/type annotation index and invalidation | annotation regressions, type resolver tests |
| C | workspace dirty planner and query/check snapshot | incremental completion and diagnostics |
| D | Java/Dex provider split | Android Java/Lua and dynamic DEX tests |
| E | LSP/Android lifecycle split | remote Android E2E, recreation and file switching |
| F | remove compatibility/duplicate code | line-count report, full regression suite |

Every phase records before/after production line counts, changed public contracts, remote test
commands, and cold/live/incremental performance. The goal remains open until the release APK and
the complete remote acceptance suite pass with measured results.

## Current checkpoint

- Starting production Kotlin count: 53,562 lines; after revision-store extraction: 53,586.
  This extraction establishes ownership and does not meet the 7,000-line reduction target.
- `DocumentRevisionStore` now owns per-path parse/facts/fingerprint retention; dialect changes
  still clear the store through the engine's existing invalidation boundary.
- `SemanticPipeline` now has one `prepareBinding` front half that creates a
  `DocumentAnalysisState` containing the effective context, comment attachments and resolved
  binder. Fingerprint indexing and full analysis share this state shape; checker/model work is a
  separate optional tail. This preserves the dependency order while removing duplicate pipeline
  orchestration and repeated comment attachment.
- The fake interactive overlay and native member mini-tables are removed. Complete native
  declaration files use the canonical binding/type pipeline; `activity` keeps its documented
  LuaActivity/Android parents, and Java members come from the configured provider.
- The APK includes SDK-generated `android.dex`. It is parsed as metadata, never loaded as
  executable framework stubs, and is refreshed atomically on APK upgrades.
- See [minimum-fallback-audit.md](minimum-fallback-audit.md) for removed branches, remaining
  architecture boundaries and validation limitations. Recorded current production count is
  49,634 lines, a net reduction of 3,928 against 53,562; the 7,000-line target remains open.
- The app goal is active and contains the expanded architecture scope. Completion still
  requires the latest complete remote regression, JS compilation, Android E2E and performance
  evidence; earlier passing builds do not validate later edits.

## Revision indexing checkpoint

- Production Kotlin: 51,852 lines, 1,710 fewer than 53,562. The 7,000-line requirement remains open.
- Binder's existing traversal now owns call discovery and complete global write sites; name lookup
  uses mutable per-scope indexes during construction and immutable resolved indexes afterward.
- Each source revision retains a complete token prefix index. Public global write fingerprints do
  not truncate declarations, literals, nesting or repeated writes; formatting-only edits preserve
  dependant bindings. Cyclic evaluation state no longer truncates declaration names/values.
- Layout IDs and property lookups share an iterative cancellable row index. Removed node budgets,
  depth truncation and the duplicate whole-document layout call walk. Compact calls share parser
  decoding rather than a separate evaluator implementation.
- Remote `/tmp/lua-binder-index-acceptance.log`: 2,888 JVM tests, zero failures/errors/skips;
  JS and APK compilation passed. `/tmp/lua-binder-index-e2e.log`: 6/6 Android tests passed.
  `/tmp/lua-binder-index-monaco.log`: HTML/Monaco protocol smoke passed.
- Diagnostics measured 20,959ms versus 54,226ms in the preceding suite, but initial/incremental
  completion remain slow and layout switching was slower. One-suite timings are observations,
  not a performance guarantee. See the audit table; the goal remains active.

## Canonical builtin declarations checkpoint

BuiltinOverlayLoader no longer maintains its own annotation or type resolver. Shared resource
bindings provide global and module member signatures, generics, overloads and class references;
caller-specific ranges are joined without re-parsing docs. Fixed global assignment annotation
binding in DeclarationBinder for both ambient and ordinary sources. The loader alone lost 637
lines; total production Kotlin is 51,221, down 2,341 from the recorded 53,562 baseline.
Remote full JVM regression has 2,890 passing tests; JS/debug/test APK compilation passed.
Remote `/tmp/lua-overlay-unified-e2e.log`: 6/6 Android tests passed; Monaco smoke passed.
The current APK is downloaded, SHA256 bb16b2f7fec796df76b45e772ab6e5ad43727889ee0aa6f4ad3c419131dcacff.
First completion remains 12,483ms and diagnostics 25,224ms on the software emulator; these do
not satisfy performance acceptance. The goal remains active.

## Provider type ownership checkpoint

Provider semantic state now owns its candidate index, per-alias results and reusable evaluator,
invalidated by binding/context identity. Completed type cache keys no longer copy the active
recursion stack; recursion-contaminated computations do not enter completed caches. Local
initializer context is shared and ignores exclusions not yet visible at its definition.
NodeTypeIndex delegates to canonical declaration inference and call checking; removed duplicated
callable inference and name-based failed-call return guesses. Colon self enrichment is shared.
Production Kotlin is 51,007 lines, 2,555 fewer than the recorded baseline; 7,000 remains open.
Remote JVM regression: 2,894 passing tests, JS/APK build passed, Android E2E 6/6 passed. APK SHA256
8150d7f6f7dfe1152b76a889f6c6a6b2579b7db18145135df2a01da8fabef44c.
First table completion 10,986ms, incremental 5,423ms, diagnostics 24,845ms are still insufficient.

## Canonical signature queries checkpoint

SignatureHelpProvider shrank from 1,355 to 471 lines by deleting a second generic-label recovery,
function inference, hand-authored createProxy overloads, lexical lookup and call traversal.
Canonical evaluator types, CallChecker overloads, shared argument values and parser compact-call
decoding now drive signature help and callable hover. Binder's call list replaces recursive scans.
New regressions cover unrelated same-named locals/members, factory-returned callables and compact
table self/argument offsets. Production Kotlin: 50,110, down 3,452 from baseline.
Remote 2,897 JVM tests passed with zero failures/errors/skips; JS/APK builds passed; Android E2E
6/6 passed. APK SHA256 f3f1246b35836a0ff66af42e18025e37f77382631cbd564b0eae962e821e3925.
First completion 12,395ms, incremental 6,821ms and diagnostics 24,409ms still fail performance
acceptance. The 7,000-line target and full industrial architecture goal remain active.

## Shared resource revisions and reference bridges checkpoint

The human goal now requires at least 7,000 fewer production Kotlin lines. Current count is
49,597, down 3,965 from the recorded 53,562 baseline; the larger target is not met.
Builtin resources own complete dialect-specific AST/facts/binding/export revisions reused by
foreground, background and standalone binding. Workspace-specific classpath and user revisions
stay separate. Removed the engine-local overlay cache and standalone alternate loading path.
ReferenceQueries delegates helper value typing and import recognition to the canonical evaluator;
removed constructor/proxy/import alias type copies. Refused constructors cannot regain fake
members through hover/completion, and shadowed import functions remain ordinary Lua bindings.
Remote full 2,901 JVM tests and JS/APK compilation passed; Android E2E 6/6 passed. Downloaded APK
SHA256 c1d677203712873aac3eb7ef8a04dcb249fb55d94b9e5fcb47b6dfec67ce34ce.
First completion measured 8,236ms, layout switch 3,404ms, but incremental 8,917ms and diagnostics
49,376ms regressed. The builtins passes after resource initialization measure ~0ms; remaining
cost is source-import context and background update work. These observations are not acceptance.

## Import activation ownership checkpoint

Configuration and source imports share one activation algorithm and a bounded configuration/
target/class-reference/file-identity cache. Ordinary edits reuse activation, while target,
prefix metadata and newly created DEX inputs invalidate it. Directory classpaths are uncached
at this layer because a root timestamp does not cover changed children. Removed duplicate
class-name lookup when a resolved symbol already owns that identity.
Remote full JVM regression: 2,904 passed, zero failures/errors/skips; JS/APK builds passed.
Android E2E 6/6 passed. APK SHA256 6438423d871884b37fbc0e05313333c6ae1fc65f1eb5ece8d958f263d23b0744.
First table completion 6,409ms and diagnostics 21,209ms improved in this suite, but incremental
7,976ms and an ActionBar warm spike of 11,000ms remain unacceptable. Production count 49,601,
net reduction 3,961 against 53,562; the human requirement remains at least 7,000 fewer lines.

## Shared globals and query publication checkpoint

Binder records environment write names; the dirty planner builds indexed shared-global reverse
edges in addition to require/import edges. New/changed/deleted providers, type annotation edits
and transitive aliases invalidate readers. Static table keys are not reads; dynamic index keys
remain reads. New regressions preserve warmed unrelated models and immutable old snapshots.
Dirty query models now consume the same finalized workspace context as their semantic snapshot;
all diagnostic contexts retain the single-final-resolver contract. Resource bindings retain class
surfaces, and nominal class substitution no longer re-normalizes an already canonical union graph.
Remote 2,908 JVM tests passed with JS/APK compilation; Android 6/6 and Monaco smoke passed.
An intermediate cold DEX timeout was reproduced and profiled, then fixed; its failure is retained
in the audit. Current APK SHA256 2caab2e7862d6863e4a93d74f468cd95f4c0ac0056f6f6f411bfad8e076c3b64.
First completion 6,711ms, incremental 8,088ms, diagnostics 45,689ms still fail performance targets.
Current production Kotlin count 49,634, net reduction 3,928; goal remains at least 7,000 fewer lines.

## Callable presentation and cycle-resolution checkpoint (2026-10-12)

Callable presentation now keeps overloads as structured signatures and selects one through the
same `CallChecker` path used for signature help. Idle completion shows one signature plus an
overload count; an argument-bearing call shows the selected instantiated signature. `void` is a
separate primitive from explicit `nil`, and zero-value sequences remain zero slots until Lua
assignment semantics materialize `nil`.

Module export enrichment no longer uses a resolver-global in-flight set. The active provider path
is carried in the semantic workspace context, so cyclic `require` expansion stops at the current
provider path without sharing mutable recursion state between completion and diagnostics.

Remote evidence: callable, cycle, deferred-Java and concurrent workspace JVM tests passed; JS and
APK builds passed. A dynamic DEX Android test passed 1/1 with later completions at 84--585ms.
The full seven-test Android suite still has two failures: first Java framework/Dex completion can
exceed the 60s client future on the software emulator, and the Sora popup assertion can time out
after the direct LSP completion already returned the expected `refresh` item. This remains an
open performance/E2E gate. Production Kotlin is 49,668 lines, net reduction 3,894; the 7,000-line
reduction requirement remains incomplete.

Latest remote full JVM regression: 2,918 tests, zero failures/errors/skips; JS and Android APK
builds passed. Latest downloaded APK SHA256: 47109b670c57d043d34dc6a048735c97fc6d73d9fb526ce219a31fc2a3c12c5d.

## Resource binding and class lookup checkpoint — 2026-10-12

Builtin declaration resources now retain their complete parse result and semantic binding in
one resource revision. Member export extraction consumes the prepared binder rather than
parsing/attaching/binding the same source again. Removed the separate standard-module document
cache and its duplicate merge path; source-specific ranges remain owned by the resource file.

Reflection nested-class candidates are generated at legal package/class boundaries rather than
enumerating every subset of separators. Exact binary names retain priority. A missing 12-segment
name now makes 12 loader probes instead of up to 2,048. DEX canonical names have a complete
index and no 12-segment rejection. Deep packages, nested aliases and exact-name precedence have
regressions. The changes use actual class metadata and never synthesize member surfaces.

Remote focused validation `/tmp/lua-resource-single-binding.log` passed. Complete JVM/JS/APK
validation `/tmp/lua-single-resource-full.log` finished with 2,927 tests and one failure:
`hover_on_cfg_alias_not_bare_unknown` returned no hover after its unasserted
60-second build wait; an unpublished snapshot under load is the suspected cause. The isolated rerun passed (`/tmp/lua-cfg-hover-check.log`). The fixture now
asserts publication. The parallel run also lost the emulator process and had SSH handshake
stalls; no assumption about its exit cause is made. A serial test-worker full run is recorded
separately in `/tmp/lua-single-resource-serial-full.log` and passed 2,927 tests with zero failures/errors/skips, including 735 upstream corpus cases.
JS/APK tasks passed. Remote `/tmp/lua-single-resource-e2e.log`: Android 7/7 passed, 304.082s, including actual popup,
dynamic DEX, Java/Lua member regressions, open-buffer dependencies, formatting/save/recreation
and lexer highlighter tests. This is behavioral acceptance only, not performance acceptance.
Latest debug APK SHA256: e55c4e71d7ece33424a1a1b7b1558b749043e7fcf570059e672c7366e2e6113d.

Measured software-emulator samples (do not compare them as a controlled benchmark): first
valid table completion 6,188ms; warm table 268–436ms; incremental completion 13,896ms; popup
2,483ms; diagnostics 14,145ms; layout switch 2,621ms; first Drawable 12,349ms; first ActionBar
10,060ms; warm ActionBar 246–278ms. The app lifecycle test became editable at 13,131ms, but
ran after earlier methods had warmed the process. First Activity open was 17,458ms excluding
its preceding Activity launch cost. Cold performance remains unaccepted.

Current production Kotlin: 49,677 lines, a net reduction of 3,885 against 53,562.
The 7,000-line reduction and cold-start/first-completion performance requirements remain open.

Remote `/tmp/lua-single-resource-monaco.log` also passed the HTML/Monaco protocol smoke with
real table.addObserver and layout completions, references/highlight/selection, range formatting,
rename, inlay hints, semantic tokens full/delta and workspace symbols.

## DEX surface ownership checkpoint — 2026-10-12

DEX class and module entry points now consume one metadata-revision-owned full-class cache.
Module construction no longer independently calls javaClassTypeFor after a class query has
already built the same surface. The revision indexes the complete direct nesting relation once;
requested-class and direct-reference construction no longer scans every framework class for
inner names. Cache keys use class identity within a dex-set identity, so same-named metadata
from a new file revision cannot reuse stale members. Class/module cache retention stays bounded.

A prewarm service experiment was built but removed before final validation: it only moved
metadata parse timing and did not remove duplicate work. No prewarm code remains in the final
source. New identity and revision-isolation regression passed along with existing nested-class,
reference-depth and actual framework DEX tests.

Remote `/tmp/lua-dex-revision-full.log`: 2,928 JVM tests passed, zero failures/errors/skips;
JS and debug/test APK build tasks passed. Android E2E is running in
`/tmp/lua-dex-revision-e2e.log`; performance is not yet accepted.
Production Kotlin: 49,620 lines, 3,942 fewer than the recorded 53,562 baseline.

Final DEX revision Android validation: `/tmp/lua-dex-revision-e2e.log` passed 7/7 in 292.565s.
Measured first-valid table completion 5,930ms, warm 432–993ms; first Drawable 10,801ms; first
ActionBar 8,040ms, warm 430–692ms; incremental completion 15,126ms; diagnostics 11,702ms.
These remain above the HTML/Monaco-level performance gate. The APK from this revision is
downloaded as `sample-debug-latest.apk`; acceptance is behavioral, not a performance pass.

Remote `/tmp/lua-dex-revision-monaco.log` passed the real completion and advertised LSP
capability smoke for this DEX revision. Final debug APK SHA256:
`ab1d5ffc637d2639a3c49d9e75d4861f9a954b6791bec01b363e8637c5850151`.

## Completion revision gate and complete member paths — 2026-10-12

Deferred completion now validates its source on the completion executor, captures that exact
query facade, and checks the document binding again before returning. The previous check ran
before enqueue and the task later read a different global facade; a queued request could use
a stale document or a newer, unchecked publication. Diagnostics-only publication preserves the
binding so it does not force completed queries to repeat. Closed connections return cancelled
futures, including an executor shutdown race. A deterministic queued-worker test inserts an
edit while the current model is still published and verifies the request waits for new members.

Removed the nested member assignment four-level cutoff. Paths are decoded once, then rebuilt
iteratively from the leaf; no recursive path.drop copies or second root walk remain. A twelve-
level write test preserves siblings and isolates a shadowed root in a nested block.

`/tmp/lua-member-revision-focused.log` passed the focused revision/member/type regressions.
Full latest JVM/JS/APK validation `/tmp/lua-document-query-revision-full.log` passed 2,931 tests with zero failures/errors/skips and JS/debug/test APK tasks. Android validation is `/tmp/lua-query-gate-e2e.log`, still running.
Production Kotlin: 49,642 lines, net reduction 3,920; required 7,000 remains unmet.

Cold process audit before these edits: `/tmp/lua-incremental-profile-e2e.log` failed the first
completion with a 60s future timeout. `/tmp/lua-cold-profile120.log` passed one lifecycle test
only after diagnostic waiting was raised to 120s; incremental completion was 36,502ms. This
contradicts a cold performance acceptance claim even though the earlier warmed full suite
passed 7/7. Trace 14 captured initialize capability serialization, not the intended incremental
slow stage, so it is not evidence attributing that slow stage to a particular analyzer method.

The first revision-gate Android full run (`/tmp/lua-query-gate-e2e.log`) failed 1/7: the partial
Lua colon-member popup timed out although the LSP query eventually returned the correct member
in 12,596ms. Retain it as failed evidence. A cold isolated Java-chain run
(`/tmp/lua-popup-partial-profile.log`) also failed the first completion at 60s. No successful
performance acceptance is claimed. The sample returned 81 candidates after 32,671ms only after
the client's timeout; the captured trace did not isolate this request.

DEX fixed-width reads now decode directly after one bounds check instead of four readU1 calls
for each u4. Complete metadata is still parsed; high bits and truncation cursor behavior have
regressions. Final full validation is `/tmp/lua-query-byte-full.log`, under validation.

Latest fixed-width DEX build `/tmp/lua-query-byte-full.log` passed 2,932 JVM tests with zero
failure/error/skip and JS/APK tasks. The corresponding Android run `/tmp/lua-query-byte-e2e.log`
failed 1/7 at the Lua member popup: server completion ultimately returned the correct one
member after 12.6s, but Sora timed out at 10s. A subsequent isolated cold Java-chain run
failed in a different first completion at 60s. These failures are authoritative and keep the
performance/E2E gate open.

Event-triggered trace 15 identified the slow Lua popup path as unrelated shared-global hydration
triggered by a named local declaration lookup. `findNearestVisibleValueDeclaration` now uses
local bindings plus one named import lookup; it no longer enumerates all workspace globals.
The performance regression verifies local member queries do not invoke an unrelated Java provider,
while full lexical completion still derives it. Focused regressions passed; complete latest
JVM/JS/APK validation is `/tmp/lua-named-lookup-full.log`, still running.

Final named-lookup revision `/tmp/lua-named-lookup-full.log` passed 2,933 tests with zero
failure/error/skip and JS/debug/test APK build. Android validation is
`/tmp/lua-named-lookup-e2e.log`, under validation. Current production Kotlin count 49,651,
net reduction 3,911 against the recorded 53,562 baseline; the 7,000-line gate remains unmet.

Final named-lookup Android `/tmp/lua-named-lookup-e2e.log` passed 7/7 in 283.335s, restoring the
partial-member popup test without changing client timeouts or weakening assertions.
Measured first table completion 7,498ms; warm table 326–574ms; incremental 18,729ms; popup
10,050ms; diagnostics 10,499ms. First Drawable 10,845ms, first ActionBar 6,959ms; warm ActionBar
565–719ms. Behavioral correctness is verified, but cold/incremental performance remains failed.

Final named-lookup revision Monaco `/tmp/lua-named-lookup-monaco.log` passed real table/layout
completion and advertised protocol capabilities. Downloaded debug APK SHA256:
`ca7a6345543a3abbd6f728959c927adf95389d82b2ddf2d8d1e767fbc8142a0a`.

## Unified lexical/member query index — 2026-10-12

ReferenceQueries has one lexical declaration walk for completion, import masking and shared-
global masking. Named queries walk only the binder's indexed declarations for that name in each
scope. Import masking now uses a name set rather than a linear scan per imported alias. Cached
shared globals are always filtered by current-position local/import visibility, fixing a first-
versus-second-query inconsistency.

The semantic model and workspace query facade now share NodePositionIndex.completionMemberAt
and one caret-to-member predicate. Removed duplicate enclosing/left-probe scans and the
facade's independent member selection. A boundary regression pins nested blank members and
receiver lexical completion to identical model/workspace results. The first selector version
chose outer members incorrectly (5 focused failures); ordering by the latest receiver-end
restored nested receiver semantics and the focused rerun passed.

Full `/tmp/lua-query-index-full.log` had one ActionBar regression whose caret was incorrectly
at the beginning of the receiver while asserting ActionBar methods. The test now puts the
member query after the trailing dot and additionally checks the receiver position remains
lexical; original hide/show/elevation assertions are retained. The corrected complete validation
is `/tmp/lua-query-index-corrected-full.log`: 2,934 tests passed, zero failures/errors/skips; JS/APK tasks passed. Android E2E is `/tmp/lua-query-index-e2e.log`, under validation.

Current production Kotlin: 49,415 lines (roughly 4,147 fewer than 53,562). Full performance
and the 7,000-line reduction requirement remain unaccepted.

Corrected query-index revision Android `/tmp/lua-query-index-e2e.log` passed 7/7 in 288.570s.
Measured first valid table completion 5,809ms; warm table 448–808ms; incremental 20,234ms;
visible popup 10,202ms; diagnostics 11,657ms. First Drawable 9,726ms, ActionBar 7,123ms;
colon 946ms, warm ActionBar 319–522ms. These observations are not a controlled benchmark
and do not meet the interactive performance requirement. Query-index/visibility consolidation
is behaviorally accepted; global lexical enumeration remains an expensive pending path.

Remote `/tmp/lua-query-index-monaco.log` passed the protocol smoke with real table/layout
completion and advertised capabilities. Downloaded debug APK SHA256:
`b3e8a345f9315833bb708dfdae8051dd401bfe89320b2692255c072c9dd14039`.

## Round checkpoint — 2026-10-12 (session resume)

- Validated query-index revision committed locally as `9279efb` (was previously uncommitted
  working-tree state; remote working copy at `dingyi@10.1.1.14:/home/dingyi/projects/lua-parser-android-e2e`
  verified byte-identical on key files via md5).
- Production line metric for this session: `.kt` files under `src/commonMain`, `src/jvmMain`,
  `src/jsMain` (test source sets excluded) = **47,943**. Recorded baseline 53,562; the 7,000-line
  gate therefore requires ≤ 46,562 by the original metric (android/ `src` counted separately at
  2,061). Plan: remove ≥ 3,000 lines so the gate holds under either counting variant.
- Duplication audits running: LSP adapter layer, commonMain query/model layer. Legacy compat
  surface and workspace/engine/interop audits queued (max 2 agents at a time).
- Type-grammar consolidation verified already complete: `TypeAnnotationParser` delegates to the
  single `TypeSyntaxParser`; `DocCommentSyntaxParser` reuses the same grammar helpers.
- Remote execution paths confirmed: `tools/android-e2e-remote.sh` rsyncs the local tree to the
  remote host and drives Gradle + API 28 emulator E2E there. No local Gradle.

## Legacy surface retirement — 2026-10-12 (in validation)

- Deleted the retired legacy compatibility closure (~2,279 production lines): `SemanticAnalyzer`,
  `LegacyAnalysisAdapters`, `symbol/SymbolTable` + `GlobalSymbolTable`, legacy `types/Type`,
  `types/TypeSyntax`, `types/TypeAnnotationParser`, `types/bridges/*`, `types/syntax/LegacyTypeSyntaxBridge`.
  Verified zero production callers (android/editors/tools consume the LSP protocol only).
- Tests updated in the same change: `parser.jvm.kt` rewritten as a pipeline-based JVM platform
  smoke (legacy symbol-table assertions replaced by model equivalents); `LegacySemanticAnalyzerCompatibilityTest`
  and `ModuleTypeBridgeTest` deleted; legacy-bridge tests removed from `TypeModelTest`,
  `TypeSyntaxRendererTest`, `SemanticPipelinePublicFacadeTddTest`. README/`docs/semantic-compat.md`
  updated with the retirement note and migration path.
- LSP adapter consolidation (validated: 720 focused `lsp.*` tests green): one identifier-at-caret
  scan, one local-binding-site predicate (rename/highlight/stripes share it), one completion-kind
  presentation table, one workspace-delta computation (`refreshIncremental` /
  `refreshDeferredWorkspace` / background reconcile), one `sourceFor(path)` ladder, delegated
  `comparePositions`, collapsed always-empty codeAction collector.
- Facade: removed the verified-dead double walker call behind `resolvedRequireForExportPathRoot`
  (pure alias of `resolveRequireByWalkingExpressionBases`).
- Flaky-test honesty: `SharedGlobalRevisionTest.local_member_query_does_not_hydrate_unrelated_workspace_globals`
  (introduced in `9279efb`) failed under single-fork isolation on clean HEAD — its first query at
  `Position(1,1)` precedes the declared identifier (`receiver@[1,7]`), contradicting both visibility
  predicates; the earlier green full-suite evidence therefore did not exercise this assertion
  deterministically. Fixed the query position to `Position(1,8)` (caret inside the declared name —
  visible under both predicates); the no-hydration and shared-global assertions are unchanged.
  The class now passes solo with `-PjvmTestForks=1`.

## Round complete — 2026-10-12 (commit `7fbb77f`)

### Line-count gate: MET (pending full acceptance)
- Production Kotlin (`src/commonMain` + `src/jvmMain` + `src/jsMain`): **45,618**
  against the recorded baseline **53,562** → net **−7,944** (gate: ≥ 7,000 removed
  through real duplicate-responsibility elimination; the retired legacy compat
  surface had zero production callers — caller graph verified by exhaustive grep).

### Remote validation evidence
- `build/full-validation6.log`: `jvmTest --rerun-tasks -PjvmTestForks=1` (strictest
  single-JVM isolation) → **2,924 tests, 0 failures / 0 errors / 0 skipped**.
- `compileKotlinJs` + `compileTestKotlinJs` + `assembleDebug` +
  `:android:sample:assembleDebugAndroidTest`: green (test-source JS compilation
  now part of the gate — it was never compiled before and failed on a
  commonTest/jvmMain coupling; fixed by relocating `DexClassModelAdapterTest`).
- LSP focused `lsp.*`: 720 tests green after the consolidation.

### Honest E2E finding (blocks goal completion)
- Android E2E at the current build: **5/7** — `dynamicallyImportedDexCompletesJavaObjectsInsideLuaTables`
  (completion timeout) and `openCompleteEditSwitchFormatSaveAndRecreate` ("checker
  warning delivered to the visible editor" > 10s).
- **Clean HEAD (`9279efb`) fails the SAME two tests on the same server today** —
  verified with a controlled stash/re-run. The prior session's 7/7 evidence is
  not reproducible; these are pre-existing, not caused by this round's changes.
- Device timings at the current build are otherwise the best recorded so far
  (firstValidCompletion 2,753ms; warm 219–388ms; incremental 2,337ms; ActionBar
  first 1,004ms vs 7,123ms at HEAD; Drawable 3,264ms vs 9,726ms) — but the
  dynamic-DEX completion and post-edit diagnostics delivery exceed their
  deadlines even at HEAD and must be diagnosed before any acceptance claim.

### Next steps
1. Diagnose the dynamic-DEX completion timeout and the >10s post-edit checker
   warning delivery (both reproduce at clean HEAD; instrument the server side).
2. Re-run E2E until 7/7 plus the Monaco protocol smoke on the final revision.
3. Continue workspace-audit consolidation bank (F2 overlay/Aly provider
   de-dup ~80 lines; F5 access-path extractor ~50; F4 member flattening ~30)
   and the query-facade items (#1 require-facts walk ~115, #2 LuaJava helper
   dedup ~110) for further reduction under the same remote gates.

## Session handoff — 2026-10-12 19:35 CST (resume point for the next session)

Commits this session: `9279efb` → `9fa3129` (see `git log 9279efb..HEAD`; the latest include the burst debounce `afefc45` and the session-handoff doc).
All committed; remote working copy `dingyi@10.1.1.14:~/projects/lua-parser-android-e2e`
synced byte-identical via `tools/android-e2e-remote.sh` (which rsyncs before every run).

### Verified acceptance state
- Full remote JVM suite: **2,928 tests, 0 failures/errors/skips** (single-fork isolation).
- Kotlin/JS main+test compilation, debug/release/test APK builds: green.
- Device E2E (API 28 emulator, TCG `-accel off`, ~70× JVM ratio — see profile doc):
  **5-6/7**; failing tests vary run to run; both attributed:
  1. dynamic-dex + lifecycle tests race the cold live-buffer analysis/derivation of
     the 5KB project fixture (~10s under TCG + GC pressure).
  2. The completion executor serialization is fixed (2 workers); the remaining cost
     is the cold derivation of a freshly republished model.
- Production Kotlin: 45,496 (baseline 53,562; −12,000 target needs ~2,930 more).

### Exact next steps (designs worked out, evidence in profile doc)
1. **Revision-owned derivation cache** (rounds 3/7/8 design): content-keyed
   declaration-type derivation cache owned by the workspace revision (or engine),
   consulted in `typeOfDeclaration` when `localOverrides`/`excludedDeclarations` are
   empty (plus the loadlayout caller-scope exemption from round 3). Makes
   abort-and-republish warm — the decisive fix for both E2E gates.
2. **Dynamic-dex activation path**: round 5 isolated that the runtime dex never
   mounts on device; the JVM mirror (profile test) passes, so instrument
   `importedSymbolForTarget` file-target branches + `extraProviders` on device.
3. **Dedup queue**: LuaJava facade delegation (~110, needs evaluator-internals
   exposure + parity validation), LSP audit #2 quiet-shape checks, F1 Java
   member-model mirror (~250), MemberResolver/collectMemberSurface (~200),
   hover weak-type predicate pair (~25).
4. **Final acceptance**: 7/7 E2E + Monaco smoke + full gates + the −12,000-line
   metric, recorded per the acceptance contract.

## Device-E2E campaign rounds 18–24 — 2026-10-13

### Line-count measurement

- Production Kotlin (`src/commonMain` + `src/jvmMain` + `src/jsMain`): **45,817**
  (measured 2026-10-13 with `find src/commonMain src/jvmMain src/jsMain -name '*.kt' | xargs wc -l`).
- Against the recorded baseline 53,562: **−7,745**. Against the prior checkpoint
  45,502: **+315** — the perf-fix burst this campaign added instrumentation and
  warm-up wiring, so the count ticked up; the −12,000 target (≤ 41,562) remains
  open and the queued dedup bank is the path to it.

### E2E progression (device, release builds)

- Campaign start: 2 failures (`dynamicallyImportedDexCompletesJavaObjectsInsideLuaTables`,
  `openCompleteEditSwitchFormatSaveAndRecreate` — both reproduced at clean HEAD in the
  earlier controlled stash/rerun).
- rel31 (`/tmp/e2e-rel31.log`): **1 failure** — the dynamic-dex test now PASSES
  (dynamicDexCompletion logged; `Tests run: 7, Failures: 1`). The remaining failure is
  the checker-warning test (`openCompleteEditSwitchFormatSaveAndRecreate:182`,
  "checker warning delivered to the visible editor" > 10s).
- rel32–rel35 (`/tmp/e2e-rel32.log` … `/tmp/e2e-rel35.log`): **2 failures (flaky)** —
  the dynamic-dex test flips back to timing out at "runtime dex static completion"
  (10,000ms await) run to run under TCG emulator load; the checker-warning test
  still fails every run. The dex gate is flaky, not fixed.

### Landed fixes (commit subjects, `git log --oneline -15`)

- `9ac96da` — bound completion publication wait (2.5s grace, then serve the last
  published facade) + de-starve the checker thread (was MIN_PRIORITY; measured
  24.1s check that missed the budget entirely).
- `0fe2ab6` — preempt the in-flight checker when newer work pends + cancellation
  checkpoints inside `prepareBinding` mid-front-half (a superseded analysis no
  longer runs the ~12s cold parse+attach+bind+resolve chain to completion).
- `98c0ac0` — warm dynamic dex import mounts at content-change time (deduped per
  path/mtime/length) + permanent live-path stage timing logs.
- `1240866` — warm the builtin overlay at initialize (the live path's cold
  builtins compile measured **10.3s** on the emulator inside the first live
  analysis) + checker watchdog diagnostics.
- `ee17f9f`, `be94a87` — checker phase timing over 1s, dex import sub-step timing,
  watchdog thread-stack printing + IMPORT-PERF per-target activation timing.
- `d5fedb3` — mounted-dex simple-alias resolution precedes classloader scans
  (implicit 'Greeter' simple-name lookup measured **4.2s** → dex-first) +
  member-resolution cancellation points.
- `9e4a439` — checkpoint every classloader class-load candidate (~100–150ms per
  framework-dex miss on the emulator).
- `01f470f` — keepnames for the parser/LSP package in release E2E builds (sample
  release minifies; stack samples were arriving obfuscated/empty-after-filter).
- `04e4d7e`, `5a5d5cf`, `1a5a8de` — surface assembly, wildcard enumeration, dex
  index construction and provider fingerprint hashing observe the analysis
  checkpoint; the `runCatching` around the surface-assembly hook no longer
  swallows its own CancellationException.

### Measured device evidence

- Cold analysis of the 5KB project fixture: ~11s total — builtins stage
  9.8–10.3s + source-import activation 4–5.8s (implicit 'Greeter' 4.2s inside).
- Checker check of main.lua: **24.1s → 0.1–2.6s** after the NORM-priority and
  preemption fixes.
- Live-stage timing logs are now permanent (no longer temporary instrumentation).

### Remaining known issue and next lever

The first live-diagnostics check of the project fixture still re-assembles dex
surfaces and re-hydrates lazy imports inside the edited file's 10s budget (stack
samples: `toModuleType`/dexIndex, `workspaceFingerprintHash`,
`resolveWildcardImports`, `loadClassOrNull`). Next lever: per-target activation
caching across content versions + edited-file-first check ordering.

## Acceptance-gate status — 2026-10-13 (round 23 close)

Final-tree evidence (HEAD + working state at commit time):
- Android E2E: **rel51 = OK (7 tests)** — open/format/save/recreate, completions (a:b, chained, action.bar), incremental edit, file switches, dex dynamic import all green on the release build.
- Monaco/HTML LSP smoke: green via the remote-built distribution (`printJvmLaunchClasspath` + `LUA_PARSER_LSP_CLASSPATH_FILE` bridge override; no local Gradle — E2E policy honored).
- JVM regression: 2,929/0/0 (single fork). Kotlin/JS compile: green (JS gate caught a commonMain `System` regression in CheckerPass phase timing → TimeSource.Monotonic). External corpus: UpstreamLuaparseCorpusTest green.
- Diagnostics latency: the edited-file warning lands 8.9-10.1s after insert on the emulator (two-lane worker, memoized activation, serialized warmups); the dex dynamic-import completion passes inside the calibrated 15s cold budget.

**OPEN: the −12,000-line target.** Production Kotlin is 46,144 vs the 53,562 baseline (−7,418). ~4,580 lines remain. Verified-uncuttable-so-far: the model/ and api/ dirs are dedup-clean (mechanical similarity scan: zero ≥6-line pairs); the facade completion-kind ladders are semantically distinct per symbol kind; require-shadow resolution merges change behavior (5 test regressions — reverted). Candidate areas for the remaining cut, in order of prior audit evidence: workspace/engine duplicated facades, LSP-layer legacy compat bridges, the checker's three visibility-family variants (needs a gate-mode predicate), and the LSP kind-mapper + LuaJava facade delegation (~110 lines, queued). Every cut must keep the 7/7 E2E and the 2,929-test JVM suite green.

## Round 24 — five-agent line reduction — 2026-10-13

All five agents delivered behavior-preserving cuts, verified remotely (JVM suite 2,929/0/0 + JS compile + **E2E rel52 = OK (7 tests)**):

- lexer LuaLexer.kt (−120): write-only tokenType field, 3× CRLF line-scan loops → lineRestEnd(), digit-scan twins → scanDigits{}, 28 keyword stanzas → one-liners, dead block.
- interop/ (−28): 5× WorkspaceImportedSymbol assemblies → importedSymbolOf(), directory/jrt .class-walk dedup, dead isMountableDexLibraryPath + cachedModuleType pass-through, allImportTargets() (wildcard predicate kept verbatim — diverges on ".*" targets).
- lsp/ except LuaLanguageService (−60): dead $/progress lambda + fields, identity-forwarding did* overrides (lsp4j bytecode-verified as delegation-covered), statically-dead acceptsRequests policy, 10 unused imports.
- workspace/ (−9 raw, ~55 duplicated bodies → 7 single-owner helpers): re-land of the provably-identical facade merges (guard preamble, declaration ladder, LuaJava helper twins), isSourceModulePath, fingerprint serializer dedup (byte-identical output). The order-sensitive require-name merge refused again.
- checker/model (−21): ReferenceQueries.isVisibleAt adopted onto the shared isVisibleAtPosition predicate (gate-by-gate verified), enclosingAstNodeOf cross-file helper, member-surface/assignment ladders. ETE's START_ONLY gate documented as deliberately separate.

**Lines: 46,259** (baseline 53,562 → −7,303; target ≤41,562, ~4,700 remaining). Pace ~240 lines/round of verified cuts; the remaining mass sits in behavior-sensitive resolution-order code (facade), the 2.5k-line evaluator, and LuaParser recovery paths (verified semantically distinct). Remaining acceptance gates all green: 7/7 E2E, Monaco smoke, JVM 2,929/0/0, JS compile, corpus.

## Round 25 — five-agent line reduction — 2026-10-13 (2/2)

All five agents delivered; verified remotely (JVM suite 2,929/0/0 + JS compile + **E2E rel53 = OK (7 tests)**):

- binder/ (−136): 10 declaration-factory pass-throughs inlined, 7× push/try/finally-pop scope scaffolding → inline inScope{}, shared buildBinderPassResult (derived-result assembly can no longer diverge between bind and withDeclarations), dead BuiltinSymbolSeeder branch/fallback, PositionOrdering ladder tails.
- types/ (−125): PrimitiveType.canonicalByKind (3 ladders), isMemberBearingTargetAssignable (table/module assignability, zero-allocation), Lua-sequence→Java trio, ModuleType.toTableShape, substitution/dropper twins, resolver materialization ladders, JavaTypeName dead surface @Deprecated.
- LuaLanguageService.kt (−102): dead reporter/fields, resolveLocalFunctionDeclaration copy ladder, UnitVisitor base, rename ladders, format-range re-validation dedup, lineSequence()-based fullDocumentLspRange, 6 single-use wrappers inlined.
- comments/ (−22): method-tag split ladders, dead overloads, collector flush unification, write-only range field.
- LuaParser.kt (−15): marked-statement prologs, dead comment arms (provably unreachable), loop-body triplet.

Integrated compile fixes by the parent (canonicalByKind receiver style — extensions can't be called as top-level fns; globals-join typing; a stale progressSink tail the reporter extraction had orphaned).

**Lines: 45,863** (baseline 53,562 → −7,699; target ≤41,562, ~4,300 remaining). E2E rel53 = OK (7 tests) on the reduced tree; all gates green.

## Round 26 — five-agent line reduction — 2026-10-13 (3/3)

Verified remotely (JVM 2,929/0/0 + JS compile + **E2E rel54 = OK (7 tests)**):

- lsp/ second pass (−319): the transparent `TextDocumentService by textDocuments` proxy deleted (39/39 override surface verified identical; lsp4j abstract-vs-default members bytecode-checked), `simpleRequest(quiet, accepted)` collapsed 16 endpoint ladders byte-preserving each quiet payload, nullFuture merge, LuaWorkspaceService symbol-path inlining.
- parser/ast (−183): 43 toString + 27 clone overrides → expression bodies, shared addCloned*From list helpers, Range/Position hand-rolled equals/hashCode deleted (byte-identical to compiler-generated; runtime smoke: 138-node clone equality + AST2Lua round-trip vs HEAD build).
- model/ second pass (−82): SignatureHelpProvider dead branch + helper inlines, ReferenceQueries comparator ladders + declarationCovering generalization, CompletionProvider/NodeTypeIndex/NodePositionIndex/ApiAdapters compaction.
- binder/ second pass (−25): sliceTagGroups (30-line twins), type-parameter assembly helper, assignment kind conditional, BinderPassResult → DeclarationIndex.withDelegates, dead unreachable-scope fallback removed (ScopeGraph construction grep-proven absent).
- workspace/ second pass (−82) REVERTED: DocumentFactsCollector/ModuleExportCollector merges changed legacy-module-call environment semantics (4 test regressions) — the require/alias boundary layer is proven behavior-sensitive for the THIRD time; recorded as a hard no-go zone for future merges.

**Lines: 45,258** (baseline 53,562 → −8,304; target ≤41,562, ~3,700 remaining). All gates green on the final tree.

