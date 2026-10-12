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
