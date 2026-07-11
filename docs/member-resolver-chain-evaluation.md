# Member Resolver Chain Evaluation

This page documents how multi-level member chains such as `a.b.c` and
`a.b:c()` are typed in the current semantic pipeline. It is a design note for
reviewers and IDE/LSP consumers — not a product change and not a substitute for
serialized verification of the related corpora.

Primary implementation:

- `src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/checker/MemberResolver.kt`
- `src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/checker/ExpressionTypeEvaluator.kt`

Corpus that locks the multi-level behavior (TASK-259, accepted):

- `src/jvmTest/kotlin/semantic/checker/MemberResolverMultiLevelChainTddTest.kt`

Related Android-Lua corpus after TASK-184 (accepted, review-owned serial
verification still TASK-043):

- `src/jvmTest/kotlin/semantic/androidlua/AndroidLuaLibraryStubsTddTest.kt`
- ClassType inheritance hop companion: `src/jvmTest/kotlin/semantic/checker/MemberResolverClassTypeDotMethodTddTest.kt`

Related notes:

- [semantic-compat.md](semantic-compat.md) — pipeline surface and checker packages
- [serialized-verification.md](serialized-verification.md) — review-owned Gradle gate
- [java-interop-model.md](java-interop-model.md) — Java class/instance member surfaces used as chain bases
- [android-lua-context-method-matrix.md](android-lua-context-method-matrix.md) — `activity` / `service` / `this` / `context` member catalog
- [android-lua-library-models.md](android-lua-library-models.md) — loadlayout / loadbitmap / loadmenu and layout id surfaces

Command-level confirmation of the multi-level and Android-Lua corpora remains
deferred to the serialized verification task (TASK-043 or its successor). This
page documents intended evaluation rules only; workers must not re-run Gradle
here.

## Goals

1. Describe **multi-level chain evaluation**: each hop is resolved from the type
   of the previous hop, not from a flat name lookup.
2. Describe **leaf typing**: the type of the full expression is the type of the
   last successful hop (or `unknown` after failure).
3. Note the difference between a **string-literal leaf** (inferred
   `LiteralType("…", STRING)` / string-ish surface) and **unknown** degrade
   when a hop cannot be resolved.
4. After TASK-184, describe how Android-Lua globals, load* call returns, and
   layout-id tables feed the same single-hop resolver without inventing hops.

## Pipeline Roles

| Component | Role in a chain |
| --- | --- |
| `ExpressionTypeEvaluator.evaluateMemberExpression` | Recursively types the base expression, then asks `MemberResolver` for one hop. Missing hop → `UnknownType`. |
| `ExpressionTypeEvaluator.evaluateIndexExpression` | Same recursive base evaluation, then `MemberResolver.resolveIndex` for `base[key]`. |
| `MemberResolver.resolveMember` | Single-hop resolution on an already-known base type (table/module/class/Java/union/intersection). |
| `MemberResolver.resolveIndex` | Single-hop bracket index on arrays, tuples, tables, modules, Java types. |
| Table constructor evaluation | Builds nested `TableType` fields so inferred `a = { b = { c = "hi" } }` can feed multi-hop resolution. |
| Constant evaluation | String constants become `LiteralType(value, PrimitiveType.STRING)`, not bare `unknown`. |
| `deriveGlobalDeclarationValueType` | Root base for builtin non-callable globals (`activity`, `service`, `this`, `context`): preserve `declaredType` (`ClassType` / `UnionType` / …) so the first hop is not forced to unknown. |
| `resolveLoadlayoutFamilyCall` | Call leaf for `loadlayout` / `loadbitmap` / `loadmenu` (and loadlayout2/3): shell `JavaInstanceType` surfaces used as chain bases for further hops. |
| `loadlayoutIdsTableType` | Local ids table after `loadlayout(layout, ids)`: `ModuleType(LuaLayoutIds)` fields that feed chains such as `ids.title.setText`. |

`MemberResolver` is intentionally **single-hop**. Multi-level chains emerge
because the evaluator types nested `MemberExpression` / `IndexExpression` ASTs
from the inside out: for `a.b.c`, the AST is effectively
`(a.b).c`.

## Multi-Level Evaluation Model

### Dot chains (`a.b.c`)

For a chain of `n` hops:

1. Type the root base (`a`) via declaration / local / global resolution
   (`evaluateReferenceBaseType`).
2. For each hop `nameᵢ` with indexer `.`:
   - Expand the current base through `TypeExpansion.expandForMemberSurface`.
   - Call `resolveMember(base, nameᵢ, preferMethod = false, scope)`.
   - On success, the hop’s result type becomes the base for hop `i+1`.
   - On failure (`type == null`), the expression type is `UnknownType`.
3. The **leaf type** is the type returned by the last hop.

Annotated example (object-shaped EmmyLua surface):

```lua
---@type { b: { c: string } }
local a = {}
return a.b.c   -- leaf PrimitiveType.STRING when the annotation is honored
```

Inferred nested table constructor:

```lua
local a = { b = { c = "hi" } }
return a.b.c
-- intermediate a.b → TableType(fields = { c = <string-ish> })
-- leaf a.b.c → string-ish (see “String-literal leaf vs unknown”)
```

Four-level chains (`a.b.c.d.e`) follow the same rule: each intermediate table
field must resolve to a surface that still supports member lookup.

### Colon method chains (`a.b:c()`)

Colon hops set `preferMethod = true` (`node.indexer == ":"`):

1. Resolve intermediate field hops as ordinary dots (`a.b`).
2. Resolve the colon member on the intermediate type with method preference.
3. Successful method resolution binds a `self` parameter when the callable does
   not already accept the receiver (`bindMethodReceiver` /
   `bindMethodSignature`).
4. A call expression then types through `CallChecker` using that callable; the
   **leaf of the call** is the method return type (or `unknown` if call typing
   degrades).

```lua
---@type { b: { c: fun(self: table): number } }
local a = {}
return a.b:c()   -- prefer number return; unknown is the safe degrade
```

Inferred:

```lua
local a = {
  b = {
    c = function(self) return 1 end
  }
}
return a.b:c()   -- must not throw; intermediate a.b remains evaluable
```

### Bracket index hops

`base[key]` uses `resolveIndex`. Literal string/integer keys can hit named
table/module/class/Java members; non-literal keys only succeed when an index
signature (or array/tuple rule) accepts the key type. Chains may mix dots and
indexes (`a.b["c"].d`); each hop is still independent and recursive through the
evaluator.

## Single-Hop Surfaces (What Intermediate Bases May Be)

After expansion, `resolveMember` dispatches on the normalized base:

| Base surface | Member lookup |
| --- | --- |
| `TableType` | Fields first (or methods first when `preferMethod`); then the other map. |
| `ModuleType` | Same field/method preference; optional Java static surface fallback. |
| `ClassType` | Inherited fields/methods; Java-provider classes rewrite callable surfaces. |
| `JavaClassType` | Static members, bean properties, nested classes. |
| `JavaInstanceType` | Instance members / bean properties; method signatures may inject receiver. |
| `JavaArrayType` | Field `length` only (`number`). |
| `TypeParameterType` | Resolve against constraint when present. |
| `UnionType` | Resolve each branch; any failure fails the hop; success unions result types. |
| `IntersectionType` | Keep successful branches; merge types; empty success set → missing member. |
| Other / unsupported | `MemberFailureReason.UNSUPPORTED_BASE_TYPE`. |

Failure reasons used by hop results:

| Reason | Typical cause |
| --- | --- |
| `MISSING_MEMBER` | Named field/method not present on the base surface. |
| `INVALID_INDEX_TYPE` | Index key type does not match array/tuple/index-signature rules. |
| `UNSUPPORTED_BASE_TYPE` | Base cannot host members after expansion (e.g. bare primitive). |

## Missing Intermediate Degrade (No Invented Hops)

When an intermediate hop fails:

1. `MemberResolver` returns `type = null` with `MISSING_MEMBER` (or another
   failure reason).
2. `ExpressionTypeEvaluator` maps that to **`UnknownType`** for the expression
   (`?: UnknownType`).
3. Further hops are **not invented**. A pure multi-hop harness that threads
   `MemberResolution.type` must short-circuit when `type` is null (see corpus
   `resolveChain`).
4. Evaluation must **not throw** (no NPE) on missing intermediate or missing
   leaf — unknown is the only safe residual type for incomplete surfaces.

Examples:

```lua
---@type { other: number }
local a = {}
return a.b.c   -- a.b missing → unknown (not a fabricated b table)

local a = { other = 1 }
return a.b.c   -- inferred missing intermediate → unknown without throw
```

Present intermediate, missing leaf:

```text
a.b succeeds (TableType), a.b.c fails → leaf UnknownType / MISSING_MEMBER
```

## Leaf Typing

| Situation | Expected leaf type |
| --- | --- |
| Annotated multi-level field chain fully present | Concrete annotated type at the last hop (e.g. `PrimitiveType.STRING`). |
| Inferred nested table with a string constant leaf | **String-literal leaf** (see below). |
| Colon method hop succeeds and call is typed | Method return type, or `unknown` if call binding is incomplete. |
| Any hop fails | `UnknownType` for the whole remaining expression. |
| Unsupported base at any hop | `UnknownType` (resolver reports unsupported/missing). |
| Android-Lua ClassType global + present method | Callable leaf (`fun…` / method surface), not unknown solely because the base is a builtin global. |
| `loadlayout(...)` call as chain base | Shell `JavaInstanceType` (`android.view.View`-ish); further hops resolve on demand. |
| `ids.<id>` after loadlayout ids binding | View-like field type from `ModuleType(LuaLayoutIds)`; further hops use that surface. |

The leaf of a pure member expression is **not** re-normalized to `any`. Unknown
is reserved for failure/degrade; successful hops preserve the concrete member
type (including nested `TableType` intermediates used only as further bases).

## String-Literal Leaf vs Unknown

These two outcomes are **not** interchangeable.

### String-literal leaf

When a nested table constructor (or equivalent inference path) stores a string
constant, constant evaluation produces:

```text
LiteralType(value = "hi", baseType = PrimitiveType.STRING)
```

That value is stored on the intermediate `TableType.fields["c"]`. Resolving
`a.b.c` therefore yields a **string-ish** leaf:

| Acceptable string-ish leaf | Notes |
| --- | --- |
| `LiteralType("hi", PrimitiveType.STRING)` | Exact inferred constant; `displayName` is typically the quoted form `"hi"`, **not** the word `string`. |
| `PrimitiveType.STRING` | Widened string primitive if inference/annotation collapsed the literal. |
| Other types whose display name is clearly string-related | Only as a compatibility fallback in tests; prefer the two rows above. |

Corpus helper policy (`isStringishOrUnknown` in TASK-259): treat quoted
display names (`"hi"`, `'hi'`) as string-ish so review goldens do not reject a
correct `LiteralType` leaf that prints as `"hi"`.

### Unknown

`UnknownType` means a hop could not be typed — missing member, unsupported base,
incomplete inference that never produced a table field, or call/method binding
that returned no type. It is **not** a stand-in for “we saw a string but widened
it.”

| Expression shape | Prefer | Avoid |
| --- | --- | --- |
| `local a = { b = { c = "hi" } }; return a.b.c` with full inference | String-ish leaf (`LiteralType` / `STRING`) | Claiming the leaf must be unknown solely because the display name is `"hi"` |
| `return a.b.c` when `b` is missing | `UnknownType` | Inventing a string leaf |
| Annotated `{ c: string }` leaf | `PrimitiveType.STRING` (or equivalent named string) | Treating annotation success as unknown |

Safe dual acceptance appears only where product behavior may still degrade
(e.g. incomplete method binding on colon calls): tests may allow
`number | unknown` for annotated `a.b:c()` returns until binding is complete.
That dual is **call-return degrade**, not the string-literal leaf rule.

## Worked Chain Summary

```text
a.b.c
  type(a)  → root surface
  type(a.b) = resolveMember(type(a), "b", preferMethod=false)
  type(a.b.c) = resolveMember(type(a.b), "c", preferMethod=false)   -- leaf

a.b:c()
  type(a.b) as above
  type(a.b:c) = resolveMember(type(a.b), "c", preferMethod=true)  -- binds self
  type(a.b:c()) = CallChecker return of that callable             -- call leaf
```

Harness multi-hop (tests / tooling that call `MemberResolver` directly) must
mirror the same short-circuit:

```text
current = root
for hop in hops:
  step = resolveMember(current, hop.name, hop.preferMethod, scope)
  record(step)
  if step.type == null: break
  current = step.type
leaf = last successful step.type or failure
```

## Post-TASK-184 Android-Lua Chain Surfaces

TASK-184 restored Android-Lua library stub fixtures and type surfaces
(`activity` / `service` / `this` / `context`, `loadlayout` / `loadbitmap` /
`loadmenu`, layout / `.aly` fixtures, helper modules). Review accepted the task
as **done**. This section records how those surfaces plug into the **same**
multi-level chain model — it does **not** claim TASK-043 suite green and does
not authorize workers to run Gradle.

Honesty bounds (shared with the context method matrix):

- Handwritten overlay fields are the high-value AndroLua helpers; full
  `android.app.Activity` dumps remain **reflective optional** via host
  `android.jar` only under Downloads / SDK android-35 paths (never `G:/`).
- Missing intermediate hops stay `UnknownType`; no fabricated activity tables.
- LSP bare-global CustomType member expansion may still lag semantic harness
  goldens; local Emmy stubs remain a supported expansion shape.

### Root bases that feed chains

| Root | How the base is typed | First hop example | Expected hop role |
| --- | --- | --- | --- |
| `activity` | Builtin GLOBAL `ClassType(LuaActivity)` via `deriveGlobalDeclarationValueType` (non-callable `declaredType` preserved) | `activity.getLuaDir` | Method surface on inherited `AndroidLuaContext` fields; callable leaf is `fun…` (not the word `function` in current goldens). |
| `service` | `ClassType(LuaService)` same path | `service.getLuaPath` / `service.sendMsg` | Same inherited catalog; sample different method names in corpus. |
| `this` | Union / context-like declared type | `this.getLuaPath` | Prefer members present on the shared base. |
| `context` | Platform `android.content.Context` typing on the active overlay | `context.getSystemService` | Platform Context surface, **not** a second full `AndroidLuaContext` catalog. |
| `loadlayout(...)` | `resolveLoadlayoutFamilyCall` → shell `JavaInstanceType` (`android.view.View`) | `loadlayout(...).getId` (when reflection/models expose it) | Call leaf is the chain base; members resolve on demand from the shell (no deep hydrate during the call). |
| `loadbitmap(...)` | Shell `Bitmap` instance | Further bitmap members when present | Same cheap-surface rule. |
| `loadmenu(...)` | Shell `AndroidMenu` / Menu instance | Menu members when present | Same cheap-surface rule. |
| `ids` after `loadlayout(layout, ids)` | `loadlayoutIdsTableType` → `ModuleType(moduleName=LuaLayoutIds, fields=…)` | `ids.title` | Field type is view-like (`AndroidView` / widget surface); then `ids.title.setText` is a normal second hop. |
| `.aly` require | `CustomType("LuaLayoutSpec")` when provider path ends with `.aly` | Layout-spec consumption by `loadlayout` / `setContentView` | Spec is a layout table shape, not a live view; do not invent view members on the bare require leaf alone. |

Worked Android-Lua chains (intended evaluation, not suite proof):

```text
activity.getLuaDir
  type(activity) = ClassType(LuaActivity)   -- declaredType preserved
  type(activity.getLuaDir) = resolveMember(..., "getLuaDir", preferMethod=false|true)
  -- leaf: callable / fun… surface when inheritance expands AndroidLuaContext

activity:setContentView(view)
  type(activity) as above
  type(activity:setContentView) = resolveMember(..., preferMethod=true)  -- colon self bind
  type(activity:setContentView(view)) = CallChecker return (often unknown/any-ish)

loadlayout(layout, ids)
  call leaf → JavaInstanceType(android.view.View shell)
  type(ids) → ModuleType(LuaLayoutIds) with fields from layout id= strings
  type(ids.title) = fields["title"] or index signature → view-like
  type(ids.title.setText) = resolveMember(view-like, "setText", ...)

require "main"  -- when provider ends with .aly
  leaf CustomType(LuaLayoutSpec)  -- not a view chain base by itself
```

### ClassType inheritance hops

`MemberResolver.resolveMember` on `ClassType` walks inherited fields/methods.
`LuaActivity` / `LuaService` are empty subclasses of `AndroidLuaContext` in the
handwritten stubs, so:

1. Root global type names stay activity/service-specific for hover.
2. First method hop still resolves through the **base** field catalog
   (`getLuaDir`, `newActivity`, `loadDex`, `setContentView`, …).
3. Empty subclass honesty: docs and corpora must not invent activity-only vs
   service-only method catalogs beyond global typing and sampling different
   member names.

Companion corpus: `MemberResolverClassTypeDotMethodTddTest` (ClassType dot /
method hop behavior independent of Android-Lua fixtures).

### Load* cheap surfaces (OOM-safe bases)

Call typing for the loadlayout family intentionally returns **shell**
`JavaInstanceType` values (display name = FQCN) and must **not** deep-hydrate
the full android.view.View / Bitmap / Menu member graphs during the call:

- Hydration rewrite of every method signature under `android.jar` was an OOM
  source (TASK-379 path).
- Further hops still use `MemberResolver` on the shell; instance members are
  read on demand when the hop runs.
- Host jar discovery for reflective extras: prefer `jvm.androidJar`, then
  `/Users/dingyi/Downloads/android.jar`, then
  `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar`. Missing
  jar does not remove handwritten overlay fields; it only limits reflective
  extras.

Ids-table collection is similarly bounded (identity visited set, node budgets,
no parent re-entry into layout tables / listener bodies) so multi-hop
`ids.<id>.…` typing does not re-walk the entire layout AST.

### Callable leaf display (`fun` vs `function`)

Semantic harness goldens after TASK-184 treat method / function type text as
the product `FunctionType.displayName` shape (`fun…`), not the English word
`function`. Chain leaves that are callables should be described as `fun…`
surfaces in documentation and dual-path tests; claiming a hard requirement for
the word `function` is outdated relative to the accepted TASK-184 cluster A
cleanup.

### Dual-path honesty for incomplete product paths

Where product behavior may still degrade (incomplete CustomType expansion on
LSP bare globals, partial reflective members without android.jar, incomplete
colon binding), documentation and dual-path corpora may accept:

| Path | Prefer | Soft dual |
| --- | --- | --- |
| Semantic harness with overlay active | Concrete ClassType + method `fun…` | — |
| Missing intermediate / missing member | `UnknownType` | — |
| LSP bare `activity.` completion | Non-empty when expansion works | Empty / gap still documented; local Emmy stubs expected |
| Load* without jar | Shell FQCN leaf still present | Member hop may be unknown without reflection |
| `.aly` / layoutSpec partial wiring | `LuaLayoutSpec` / view-like where product fills in | Dual-path CURRENTLY_ACCEPTS only where product remains partial |

Dual acceptance is **not** license to invent string-ish leaves for missing hops
or to claim suite green without TASK-043.

## Explicit Non-Goals

- This page does not authorize parallel workers to run Gradle or change product
  code.
- It does not redefine Java overload picking, require-chain module visibility, or
  LSP completion ranking (see those dedicated docs/corpora).
- It does not claim that every incomplete inference path already produces
  string-literal leaves; only that when inference **does** materialize a string
  constant field, the leaf is string-ish rather than unknown, and that missing
  hops stay unknown without throwing.
- TASK-184 acceptance unblocks product surface documentation; it does **not**
  substitute for serialized verification (TASK-043) or final acceptance
  (TASK-037).

## Acceptance Mapping

### TASK-311 (original docs-only)

| Acceptance criterion | Covered by |
| --- | --- |
| Docs describe multi-level chain evaluation and leaf typing | “Multi-Level Evaluation Model”, “Leaf Typing”, “Worked Chain Summary” |
| Notes string-literal leaf vs unknown | “String-Literal Leaf vs Unknown” |
| Docs-only | This file under `docs/`; no product/test edits in TASK-311 |

### TASK-455 (post-184 docs refresh)

| Acceptance criterion | Covered by |
| --- | --- |
| Docs refresh for goal completeness after TASK-184 | “Post-TASK-184 Android-Lua Chain Surfaces” + related corpus/doc links |
| Non-overlapping file scope | This file only under worker scope |
| Host android.jar paths only (Downloads + SDK android-35); never G:/ | Load* cheap surfaces / reflective optional note |
| Docs-only; no Gradle | Explicit non-goals + serialized-verification deferral |
