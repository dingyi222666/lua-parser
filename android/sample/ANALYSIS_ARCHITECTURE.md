# Android analysis architecture

The production path should be a small pipeline with one immutable revision at
each boundary:

```text
text revision
  -> lexer/token stream
  -> recovered AST
  -> document facts + export facts
  -> bindings/scopes
  -> optional type resolution
  -> query model or diagnostics
```

The parser and analyzer are separate products. The parser answers “what syntax
is present, including an incomplete editor suffix?” and returns one AST plus
recovery diagnostics. The analyzer answers “what does this name/member mean in
revision R?” and must never mutate the parser's AST or reuse a binder from a
different revision. Queries and diagnostics consume the same immutable semantic
snapshot; diagnostics add a checker result instead of rebuilding parse and
binding work.

## Measured baseline

The `deferred-export` APK passed six Android workflows, but did not meet the
startup requirement. A fresh launcher run observed text at 15.648 s, connection
at 42.123 s, and the first live semantic snapshot at 69.995 s. Its live build
took 28.855 s after didOpen. Warm completion timings are not a substitute for
this cold path. The remote emulator uses software emulation without KVM.

Method sampling in `current-editor-profile.txt` identified Java import
resolution, provider reflection, builtin construction, workspace context
composition and parsing as major costs. Inclusive percentages overlap and
must not be added together.

## Dependency boundaries

The editor has three distinct outputs:

1. Text and syntax highlighting, which do not require an LSP connection.
2. Completion and navigation, which need current bindings, dependency exports
   and the selected receiver's members.
3. Diagnostics, which may evaluate every expression and may use additional
   Java types while checking calls and assignability.

Full project indexing must not be a prerequisite for an open buffer's query
snapshot. The first live snapshot releases the background index, instead of a
fixed delay that can expire while foreground analysis is still running.
Diagnostics run separately and are versioned per document. An unrelated edit
must neither discard an unchanged document's checked report nor replace the
edited document's query state with an older snapshot.

## Parser and analyzer simplification

`LuaLexer` owns cursor state, token positions and BOM/newline handling.
`WrapperLuaLexer` only supplies bounded look-back for parser recovery. The
parser owns recovery and produces `LuaParseResult`; consumers should not call
the lexer again to discover syntax errors. A future cleanup should keep this
contract explicit and remove caller-specific recovery probes from semantic
passes.

`DocumentFactsCollector` now records imports, graph facts, export hints and read
roots in its existing traversal. Bare writes, local declarations, parameters and
loop variables are excluded from read roots; member access contributes its base.
The JVM-only AST scanner, duplicate import extraction and separate document
scan cache have been removed. Providers consume the common facts for the exact
source revision. Read roots are candidates, not binding decisions: the binder
still determines shadowing and lexical visibility.

This does not mean the entire analyzer has one traversal. These passes currently
have distinct contracts:

| Pass | Reads | Produces | Execution/Reuse |
| --- | --- | --- | --- |
| Lexer/parser | source and dialect | AST and recovery errors | once for each changed source |
| Facts | AST | imports, graph facts, read roots, export hints | once per source; shared by consumers |
| Exports | AST, facts, legacy environments | structural table/export surface | changed sources |
| Comment attachment | AST comments/statements | documented declaration attachments | reused with prepared binder |
| Binder | AST, comments, builtins | scopes and declarations | once for prepared changed binding |
| TypeResolver | declaration index | resolved documented types | prepared binding reused by main pass |
| Graph/dirty planner | facts and fingerprints | dependency closure and affected SCCs | graph reused on edge-neutral edits |
| Query model | AST, binder, import context | completion/navigation providers | position indexes remain lazy |
| Checker | same AST/binder and context | diagnostics | background; unaffected reports reused |

Facts and exports are candidates for further fusion, but their alias and legacy
environment rules currently differ. Removing the second walk without preserving
those rules can change returned module types. Comment attachment and declaration
binding also have different ordering requirements. They require contract-level
tests before fusion. The current parser rebuilds the changed file's AST; this is
document-level incremental analysis, not an incremental syntax tree.

The analyzer should have three intentionally narrow modes:

- **index**: parse, facts, exports and bindings needed to build dependency
  fingerprints; no Java member reflection and no expression diagnostics;
- **query**: current buffer binding plus on-demand receiver/type hydration;
- **check**: query snapshot plus checker traversal and diagnostics.

The current code has these modes (`bindForGlobalFingerprint`, deferred live
analysis, and `finishDiagnostics`) but their ownership is spread across the
workspace engine and language service. Consolidating them behind a revision
object is the next refactor. It will remove duplicated freshness checks and
make cancellation a property of the revision rather than a collection of
thread-local callbacks.

## Measurements That Choose The Next Change

`analysisStage` is one common-engine observation hook. The JVM/Android embedder
logs to stderr when `lua.parser.perf=true` or `LUA_PARSER_PERF` is set:
`parse`, `parse-file`, `builtins`, `providers`, `facts`, `exports`, `binding`,
`graph`, `invalidation`, `context` and `semantic`. Existing `PIPE` timings divide
the semantic pass into comment/bind/type/check/model work. Nested timings overlap;
do not sum parent and child measurements.

`AnalysisPassReuseTest` drives several facts consumers in one revision and checks
that the parse and facts passes occur once. Its dependency edit checks that the
unchanged consumer reuses its AST/facts while obtaining a fresh binding. This
guards removal of repeated analysis independently of wall-clock variability.

The unified-facts APK completed 2867 JVM tests and six Android E2E tests.
Its cold live analysis took 27.985 s; first live publication was observed at
71.095 s from launcher start. First meaningful project completion was 9.718 s,
incremental completion 8.142 s, and diagnostic publication 57.360 s. Those
measurements do not establish HTML-level performance. The next change must be
selected from whole-pipeline stage measurements, with parsing, provider/context
setup and checker work reported separately.

Each cache should state its key and owner: source text to AST/facts, snapshot
identity to resolver state, binding identity to diagnostics, and class-loader
identity plus binary name to Java reflection. No cache may be keyed only by a
short class name or by a mutable editor object.

## Revision and invalidation algorithm

Every open edit creates a new source revision. The cheap path is:

1. reuse the cached parse result when the source text is unchanged;
2. collect facts and export shape once for the changed document;
3. rebuild only affected dependency components and reuse unchanged binders;
4. publish a query snapshot tagged with the current source and binder identity;
5. run checker diagnostics against that same snapshot, publishing each valid
   document independently.

The dependency graph is the invalidation index: a file edit invalidates its own
facts, exported surface and direct/dependent module consumers. Java reflection
is a separate demand cache keyed by class-loader identity and binary name.
Wildcard package enumeration and the full Java provider graph are excluded from
the interactive snapshot and are built by the background index. A query can
hydrate the selected exact class without waiting for that catalog.

This is the useful algorithmic speedup: work is proportional to the edited
component and the queried receiver, rather than the number of workspace files,
package members, or all Java methods. Thread parallelism is only a bounded
implementation detail after this invalidation set is known.

## Java analysis

A Java type has separate identity, hierarchy and member surfaces:

- Import and wildcard catalogs need a real class identity and provider path.
- Assignability needs superclass and interface identities, including transitive
  relationships and generic parameters.
- A member query needs the selected class's public fields, methods,
  constructors and nested class names.

The deferred Android provider expands members for the selected class only.
Public reflection already contains inherited public methods and fields, so
ancestor nodes retain hierarchy information without repeating member and
constructor expansion. Nested class entries remain identity modules and are
hydrated when queried. Loading a previously indexed ancestor as a query root
still expands that ancestor's own surface; the hierarchy cache distinguishes
root and ancestor views.

Public subclasses of package-private JDK classes can expose synthetic access
bridges. The provider recovers missing names from original ancestor signatures
(for example StringBuilder.setLength), while retaining the existing exclusion
of synthetic overloads. This must preserve inherited generic varargs,
covariant returns and JavaBean aliases.

## Remaining architectural work

The live build now skips the wildcard package directory and JVM provider graph;
path-scoped exact imports and free class roots still hydrate on demand. The
authoritative background index retains the complete catalog for workspace-wide
queries. Callable type hydration can still expand parameter and return types
before a particular overload is selected, and the full engine still analyzes
builtin implementation resources. Those are the next demand-driven targets.

Changes at those boundaries must preserve path-scoped imports, local shadowing,
dependency globals, unsaved dependency edits, explicit Java classes and dynamically
mounted DEX libraries. A faster empty or incomplete completion list fails
acceptance. Each change requires JVM semantic regressions and remote Android
measurements of cold startup, meaningful first completion and incremental edits.
