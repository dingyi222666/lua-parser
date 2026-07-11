# Parser Recovery Strategy Matrix

Date: 2026-07-11  
Tasks: TASK-308 (initial matrix), TASK-390 (docs refresh — LocalAssign / WhileDo / GotoLabel suite map + incomplete-RHS CURRENTLY_ACCEPTS notes)

This matrix maps **malformed-input recovery scenarios** for the Lua 5.3 / Android-Lua parser to the **residual AST shapes** and **structured recovery diagnostics** that focused recovery TDD currently requires. It is a companion to:

- `docs/android-lua-verification.md` — parser recovery fixture boundaries and TASK-145 inventory policy  
- `docs/serialized-verification.md` — review-owned one-command-at-a-time Gradle policy  
- `docs/test-strategy.md` — parser/source lane inventory  
- `docs/final-verification.md` — final filter pointers  

**Honesty bound:** rows describe the **current fixture contract** encoded in `src/jvmTest/kotlin/parser/recovery/*`. They do **not** claim a fresh green Gradle run. Serialized verification remains review-owned under **TASK-043**. No Gradle, compile, or test command was run for this documentation task.

## Recovery contract (shared)

| Concern | Current policy |
| --- | --- |
| Entry API | `LuaParser(luaVersion, errorRecovery = true).parseWithDiagnostics(source)` → `LuaParseResult(chunk, recoveryDiagnostics)`. |
| Diagnostics | Structured `LuaParserRecoveryDiagnostic` (message + range). Recovery must not write recovery warnings to process stdout (TASK-142). |
| Residual AST | Recovered chunk stays usable: later statements remain reachable; recovered constructs keep deterministic compact shapes (`parser.renderShape`). |
| Bad markers | Missing expressions/names/members often surface as `bad` nodes, commonly rendered as `ExpressionNodeSupport`, empty `Id()`, truncated `Member(...)`, or empty-arg `Call(...)`. |
| Strict mode | Default `errorRecovery = false` should reject most of these inputs. Two assignment RHS gaps still currently parse under strict mode (recorded below); they are not production-blocked recovery cases. |
| Production-blocked inventory | Empty after TASK-180. Required cases live in supported recovery lists; intentionally rejected boundaries are explicit and separate. |
| Determinism | Same source under recovery must produce the same shape fragments and the same diagnostic message list across repeated parses. |

### Status labels used below

| Label | Meaning |
| --- | --- |
| Required recovery | Must not throw; must keep required shape / bad-node / warning fragments. |
| Intentionally rejected | Recovery enabled still fails or is out of scope; not a production gap unless review opens a policy task. |
| Strict-mode gap | Recovery path is required; strict path currently accepts the input (documented drift only). |
| Shape note | Residual AST is intentional current behavior; changing it is an expectation change, not silent inventory weakening. |

## Primary scenario matrix (if / for / table / function)

Acceptance for TASK-308 centers on these four families. Representative cases are drawn from `LuaParserRecoveryTddTest` plus focused family suites.

### 1. `if` / `elseif` / `else` chains

| Scenario (representative source) | Family | Expected residual AST (shape fragments) | Expected diagnostics / bad markers | Notes / fixtures |
| --- | --- | --- | --- | --- |
| Missing `then`: `if ready work() end` (+ later `print`) | Required recovery | `If(Clause(Id(ready):Block[…CallStmt(Call(Id(work):))…])`; later `CallStmt(Call(Id(print):…))` when present | Warning fragment: `The <then> expected` (full message may include `near …`) | `LuaParserRecoveryTddTest` missing-delimiter; `LuaParserRecoveryIfChainTddTest`; diagnostics sample: `if ready print('x') end` |
| Missing `then` on `elseif`: `if first then one() elseif second two() else three() end` | Required recovery | `ElseIf(Id(second):Block[CallStmt(Call(Id(two):))])`; `Else(Block[CallStmt(Call(Id(three):))])` | `The <then> expected` | Same suites |
| Multiple `elseif` missing `then` | Required recovery | All branch bodies retained (`ElseIf(Id(b):…`, `ElseIf(Id(c):…`, `Else(…)`) | `The <then> expected` | `LuaParserRecoveryIfChainTddTest` |
| Missing `end` at EOF: `if ready then work() print("after")` | Required recovery | `If(Clause(Id(ready):Block[CallStmt(Call(Id(work):));CallStmt(Call(Id(print):Const("after")))])` | `<end> expected` | Core + if-chain suites |
| Missing both `then` and `end`: `if ready work() print("tail")` | Required recovery | Body calls kept inside recovered `If` | `The <then> expected` **and** `<end> expected` | If-chain suite |
| Missing condition: `if then work() end` | Required recovery | Condition placeholder; body kept; trailing statements reachable | Bad: `ExpressionNodeSupport` | If-chain suite |
| Incomplete condition: `if value + then work() end` | Required recovery | `Binary(+,Id(value),ExpressionNodeSupport)` in condition | Bad: `ExpressionNodeSupport` | If-chain suite |
| Incomplete then/else body expressions | Required recovery | Branch structure + later statements kept | Bad placeholders and/or `')' expected` on incomplete calls | If-chain incomplete-body cases |
| Nested missing inner `then` / `end` | Required recovery | Outer structure + trailing top-level statements remain | Matching missing-token warnings | If-chain nested cases |

### 2. `for` (numeric and generic)

| Scenario (representative source) | Family | Expected residual AST (shape fragments) | Expected diagnostics / bad markers | Notes / fixtures |
| --- | --- | --- | --- | --- |
| Numeric missing `do`: `for i = 1, 3 print(i) end` | Required recovery | `ForNumeric(Id(i)=Const(1),Const(3),null:Block[CallStmt(Call(Id(print):Id(i)))])` | `The <do> expected` | `LuaParserRecoveryTddTest` |
| Generic missing `do`: `for key, value in pairs(items) use(key, value) end` | Required recovery | `ForGeneric(Id(key),Id(value) in Call(Id(pairs):Id(items)):Block[…])` | `The <do> expected` | Core + `LuaParserRecoveryForInDoTddTest` |
| Generic missing `in`: `for item items do consume(item) end` | Required recovery | `ForGeneric(Id(item) in Id(items):Block[CallStmt(Call(Id(consume):Id(item)))])` | `The <in> expected` | Core recovery suite |
| Numeric missing `end` at EOF: `for i = 1, 2 do total = total + i` | Required recovery | `ForNumeric(…:Block[Assign(Id(total)=Binary(+,Id(total),Id(i)))])` | `<end> expected` | Core + `LuaParserRecoveryNumericForEndTddTest` |
| Numeric missing `end` with step / float / negative step / empty body | Required recovery | Loop header preserved; body statements or empty `Block[]` retained | `<end> expected` | Numeric-for-end suite |
| Nested numeric missing outer/inner `end` | Required recovery | Nested `ForNumeric` shapes; end binds innermost first when one `end` is present | `<end> expected` (count may be 1+ depending on nesting) | Numeric-for-end nested family |
| Missing `end` with incomplete body assignment/call/local/return | Required recovery | Partial body kept; placeholders for missing RHS/args | `<end> expected` plus `')' expected` / bad `ExpressionNodeSupport` as applicable | Numeric-for-end incomplete-body family |
| Generic missing `do` keeps following top-level statements | Required recovery | Loop body + later `print` / `local` / `return` / assignment | `The <do> expected` | For-in-do suite “following statement” family |
| Nested for-in missing one or both `do` | Required recovery | Outer/inner for-in residual shapes; following `print` reachable | `The <do> expected` | For-in-do nested family |
| Missing `do` with incomplete body expressions | Required recovery | Incomplete body placeholders; following statements reachable | `The <do> expected` + bad placeholders / `')' expected` | For-in-do incomplete-body family |

### 3. Table constructors

| Scenario (representative source) | Family | Expected residual AST (shape fragments) | Expected diagnostics / bad markers | Notes / fixtures |
| --- | --- | --- | --- | --- |
| Named field missing value: `local config = { name = }\nprint(config)` | Required recovery | Following `CallStmt(Call(Id(print):Id(config)))` | Bad: `ExpressionNodeSupport` | Core + `LuaParserRecoveryTableConstructorTddTest` |
| Named field missing `=`: `local config = { name value }\nprint(config)` | Required recovery | Following print kept | Bad: `TableKeyString(Id(name)=Id(value))`; warning `'=' expected` | Same |
| Bracket field missing value / empty key / missing `=` | Required recovery | Following print kept | Bad: `ExpressionNodeSupport` or `TableKey(Id(key)=Id(value))`; optional `'=' expected` | Same |
| Bracket field missing `]`: `local config = { [key = value }\nprint(config)` | Required recovery | Following print kept | Bad: `TableKey(Id(key)=Id(value))`; warning `']' expected` | Same |
| Second named field missing value: `{ one = 1, two = }` | Required recovery | First field + following print retained | Bad: `ExpressionNodeSupport` | Same |
| Unclosed `{` at EOF / unclosed fields | Required recovery | Table node retained when possible; following statement policy is shape-specific | `'}' expected` (and field-level warnings when applicable) | Table suite unclosed family; **shape note:** some unclosed tables absorb a following call as an array field instead of leaving it as a sibling statement |
| Nested table missing one or both `}` | Required recovery | Outer local/table residual + following print when asserted | `'}' expected` | Table nested family |
| Table used as call argument incomplete | Required recovery | Call residual + following statement | `'}' expected` and/or bad field placeholders | Table call-argument family |
| Incomplete expressions inside fields (`value +`, `not`, unclosed paren/call, function missing `end`) | Required recovery | Field placeholders / partial function expression; following print kept | Bad `ExpressionNodeSupport` and/or matching token warnings | Table expression-in-field family |
| Trailing comma / trailing semicolon after fields | Valid Lua (not recovery) | Clean table + following print; no recovery diagnostics required | None expected | Explicitly present so inventory does not treat legal tables as recovery cases |

### 4. Functions (declarations, bodies, parameters, mixed Android-Lua call)

| Scenario (representative source) | Family | Expected residual AST (shape fragments) | Expected diagnostics / bad markers | Notes / fixtures |
| --- | --- | --- | --- | --- |
| Function missing `end`: `function broken(a) return a` | Required recovery | `Function(Id(broken),Block[Return(Id(a))])` | `<end> expected` | `LuaParserRecoveryTddTest` |
| Anonymous function missing `end`: `return function(a) return a` | Required recovery | `Function(null,Block[Return(Id(a))])` | `<end> expected` | Same |
| Function body missing closing paren: `function broken(a\nreturn a\nend` | Required recovery | `Return(Id(a))` remains reachable inside recovered function | `) expected` | Promoted from production-blocked by TASK-180; still required |
| Malformed parameter list: `function module.run( return 1 end` | Required recovery | Following `print("after")` reachable | Bad empty `Id()`; `) expected` | Same |
| Local function missing name: `local function (a) return a end` | Required recovery | Following call kept | Bad empty `Id()` | Same |
| Function name missing after `.` / `:` | Required recovery | `Function(Member(Id(module).),…)` / `Function(Member(Id(module):),…)` + body | Bad truncated `Member(Id(module).)` / `Member(Id(module):)` | Malformed-statement family |
| Nested `return` / function expression missing pieces | Required recovery | e.g. `Function(null,Block[Return()])` | `<end> expected` as applicable | Return/loop-tail family |
| Mixed Android-Lua incomplete call: `view:setText(` then `activity.setContentView(view)` | Required recovery | Require/import/local retained; later `CallStmt(Call(Member(Id(activity).setContentView):Id(view)))` | Bad: `Call(Member(Id(view):setText):)`; warning `')' expected` | **Must not** be reclassified as intentionally rejected (TASK-145/179) |

## Supporting scenario rows (still part of residual AST / diagnostic contract)

These are not the TASK-308 title families, but the same recovery strategy applies and is asserted in the same core suite or focused suites.

| Family | Representative residual strategy | Typical diagnostics / bad markers | Fixture home |
| --- | --- | --- | --- |
| `while` / `do` missing `do` or `end` | Loop/body statements retained; later siblings when `end` present | `The <do> expected` / `<end> expected` | Core missing-delimiter + **`LuaParserRecoveryWhileDoTddTest`** (TASK-319; 25 inventory cases) |
| `goto` / `::label::` missing name / closing colons | Residual `Goto(Id())` / `Label(Id(…))`; later statements when not absorbed as NAME | `<name> expected` / `'::' expected` | **`LuaParserRecoveryGotoLabelTddTest`** (TASK-321; 14 inventory cases) |
| `repeat` / `until` missing `until` or incomplete condition | Body kept; condition may be `ExpressionNodeSupport` | `'until' expected`; nested recovery stays bounded | Core + `LuaParserRecoveryRepeatUntilTddTest` |
| Bare / non-call expression statements | Wrapped as bad `CallStmt(Call(…):)` so later statements parse | Bad call shapes | Core malformed-statement |
| Broken `.` / `:` members | Truncated `Member` + later statement | Bad member/call shapes | Core |
| Locals / assignments missing names, `=`, RHS, index key | Later statements reachable; placeholders for missing pieces | `'=' expected`; bad `ExpressionNodeSupport` / empty names | Core local/assignment + **`LuaParserRecoveryLocalAssignTddTest`** (TASK-320; 37 inventory cases) + multi-RHS line-break matrix **`LuaParserRecoveryMultiRhsLineBreakTddTest`** (TASK-386; 18 cases) |
| Incomplete return / unary / binary / paren expressions | Return node kept with placeholders | `')' expected` / bad `ExpressionNodeSupport` | Core return/loop-tail |
| Unclosed strings / long strings / block comments | Following statements reachable when asserted | Malformed-token warnings or bad `Const(...)` shapes | Core |
| Android-Lua `switch`/`case`/`when`/`lambda` | Version-gated required recovery under `ANDROLUA_5_3` | `The <do> expected` / `<end> expected` / `'->' expected` / `'=>' expected` / bad call wrappers | Core (AndroLua rows) + focused AndroLua suites |
| Lua 5.4 attribute missing `>` | Local with attribute residual | `'>' expected` | Core (`LUA_5_4`) |

## Intentionally rejected boundaries

Recovery is **not** a universal panic-mode that accepts every token soup. These remain **out of scope** for required residual AST success:

| Boundary | Rationale | Fixture |
| --- | --- | --- |
| Top-level `end` then later statements | Unmatched block terminator at chunk scope is not discarded as “noise” | `intentionallyRejectedRecoveryBoundaryCases` |
| Top-level `else …` | `else` must attach to an `if` | Same |
| Top-level AndroLua `case …` | `case` must attach to `switch` | Same |
| AndroLua array literal under plain Lua 5.3 (`return [1]`) | Version gating, not recovery | Same |

Do not convert these into “required recovery” without a review-created policy task.

## Strict-mode gaps (current)

Core inventory (`LuaParserRecoveryTddTest`) still flags the baseline assignment RHS gaps. Focused suites expand the honest `StrictParseExpectation` matrix without reclassifying recovery requirements.

| Source pattern | Recovery expectation | Strict expectation today | Tracking / suite |
| --- | --- | --- | --- |
| `a =\nprint(a)` | Keep later print; bad missing RHS (`ExpressionNodeSupport`) | Currently **accepts** (no strict failure) | `CURRENTLY_ACCEPTS_MISSING_RHS` — core + `LuaParserRecoveryLocalAssignTddTest` |
| `a, b = 1,\nprint(a)` | Keep later print; bad trailing RHS placeholder | Currently **accepts** | Same |
| `a, b =\nprint(a)` | Multi-target missing first RHS; print sibling under recovery | Currently **accepts** (absorbs print as only RHS) | `CURRENTLY_ACCEPTS_MISSING_RHS` — LocalAssign + MultiRhs |
| `obj.field =\nprint(obj)` / `items[key] =\nprint(items)` | Member/index missing RHS; later print sibling | Currently **accepts** | `CURRENTLY_ACCEPTS_MISSING_RHS` — LocalAssign |
| `a, items[key] = 1,\nprint(a)` | Mixed multi-target trailing RHS gap | Currently **accepts** | Same |
| `a = value +\nprint(a)` | Binary incomplete RHS → `ExpressionNodeSupport` + sibling print | Currently **accepts** (absorbs call as binary right) | `CURRENTLY_ACCEPTS` — LocalAssign incomplete-RHS family |
| `a = not\nprint(a)` / `local flag = not\nprint(flag)` | Unary **absorbs** following print as operand (product has no unary statement-start-after-line-break path) | Currently **accepts** | `CURRENTLY_ACCEPTS` — LocalAssign |
| `local value\nprint(value)` | Valid bare local namelist (no `=`); not a recovery gap | Accepts (valid Lua) | `CURRENTLY_ACCEPTS` — LocalAssign |
| Multi-line multi-RHS later-term footgun: `a, b = x,\n y` | Recovery inserts 2nd RHS placeholder + sibling `Call(Id(y):)` | Currently **accepts** well-formed multi-line explist | `CURRENTLY_ACCEPTS` — `LuaParserRecoveryMultiRhsLineBreakTddTest` |
| Assignment multi-line first RHS: `a, b =\n x,\n y` | Recovery first-RHS placeholder + residual bad `Assign(Id(x),Id(y)=)` | Currently **accepts** | `CURRENTLY_ACCEPTS` — MultiRhs |
| Local multi-line multi-RHS: `local a, b =\n x,\n y` | Local keeps first NAME initializer; later term placeholder + sibling | Currently **accepts** | `CURRENTLY_ACCEPTS` — MultiRhs local family |
| Multi-RHS incomplete unary/binary (selected) | Binary sibling vs unary absorb matrix inside multi-RHS lists | Currently **accepts** absorb/well-formed paths | `CURRENTLY_ACCEPTS` — MultiRhs unary/binary family |

### Incomplete-RHS product notes (LocalAssign / MultiRhs)

These notes document **current product** absorb-vs-sibling behaviour so review does not treat honest `CURRENTLY_ACCEPTS*` flags as inventory weakening:

| Concern | Product behaviour under recovery | Strict mode today |
| --- | --- | --- |
| Assignment explist first RHS after `=` + line-break + statement-start | `recoverFirstStatementLineBreak = true` → insert `ExpressionNodeSupport`; later statement remains sibling | Often absorbs next expression as RHS (`CURRENTLY_ACCEPTS_MISSING_RHS` / `CURRENTLY_ACCEPTS`) |
| Local explist first RHS | `recoverFirstStatementLineBreak = false` → expression-start NAME/`print` may be absorbed as initializer; non-expression statement-starts (`local` / `return` / `end`) force placeholder + sibling | Case-specific |
| Binary right operand incomplete | Expression terminator **or** (line-break + statement-start) → `ExpressionNodeSupport` + sibling | May absorb following NAME call as right operand (`CURRENTLY_ACCEPTS`) |
| Unary operand incomplete | Expression terminator only → placeholder; following NAME/`print(...)` is **absorbed** as the unary operand | Accepts absorbed form (`CURRENTLY_ACCEPTS`) |
| Multi-RHS later term after comma + line-break | Later RHS always uses statement-start-after-line-break recovery → placeholder + sibling leftover NAME/call | Accepts well-formed multi-line multi-RHS (`CURRENTLY_ACCEPTS`) |
| Incomplete RHS with both loop delimiters present | Placeholders only; do/end diagnostics are **not** emitted solely for incomplete expressions | Rejects true gaps when tokens missing |

**Focused-suite honesty tallies (fixture counts, not green-run claims):**

| Suite | Inventory size | `CURRENTLY_ACCEPTS` | `CURRENTLY_ACCEPTS_MISSING_RHS` | Default `REJECTS` remainder |
| --- | --- | --- | --- | --- |
| `LuaParserRecoveryLocalAssignTddTest` | 37 | 4 | 6 | 27 |
| `LuaParserRecoveryMultiRhsLineBreakTddTest` | 18 | 12 | 1 | 5 |
| `LuaParserRecoveryWhileDoTddTest` | 25 | 0 | 0 | 25 (strict rejects malformed; incomplete-body forms still required recovery) |
| `LuaParserRecoveryGotoLabelTddTest` | 14 (required inventory) | n/a (no `StrictParseExpectation` enum; malformed edges assert strict rejection; absorbed-NAME `goto\nprint` documented separately) | n/a | n/a |

These gaps do **not** weaken recovery acceptance. TASK-043 should report whether they remain current when focused recovery filters run.

## Shape notes (current resynchronization markers)

| Pattern | Current residual markers | Drift policy |
| --- | --- | --- |
| `a, b` missing `=` then later statement | `Assign(Id(a),ExpressionNodeSupport=)` and `Call(Id(b):)` | If recovery later keeps `Id(b)` inside the assignment, update fixtures deliberately; do not silently weaken reachability of the later statement |
| Unary incomplete RHS followed by `print(...)` | Absorbs print as unary operand (`Unary(not,Call(Id(print):…))`), not a sibling | Same family for local and assignment; MultiRhs second-RHS unary uses the same absorb path |
| Multi-line multi-RHS later term after comma | Placeholder second RHS + sibling leftover NAME (`Call(Id(y):)`) under recovery | Strict still accepts well-formed multi-line explists; do not reclassify as REJECTS without product change |
| `goto\nprint(1)` | Absorbs NAME across line break as goto target → `Goto(Id(print))` (no sibling CallStmt) | Empty-name residual requires non-NAME next token (`goto\nlocal …`, `goto 1`, eof) |
| Resource-backed mixed Android-Lua recovery fixture | Bad markers for broken member / malformed `when` call / lambda placeholder required; structured warning text for those constructs may be empty | Diagnostic drift is reported separately from AST reachability (`docs/android-lua-verification.md`) |
| Unclosed empty table followed by `print(config)` | May absorb the call as a table array field rather than a sibling statement | Asserted in table suite; treat as intentional current shape |

## Fixture map (source of truth)

| Suite | Path | Role |
| --- | --- | --- |
| Core inventory | `src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryTddTest.kt` | Required recovery cases + intentionally rejected boundaries + strict gap flags + production-blocked list (empty) |
| Structured diagnostics | `src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryDiagnosticsTddTest.kt` | No-stdout + deterministic `LuaParserRecoveryDiagnostic` messages/ranges |
| If chains | `src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryIfChainTddTest.kt` | Expanded if/elseif/else residual corpus |
| Numeric for / missing end | `src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryNumericForEndTddTest.kt` | Numeric for end/body residual corpus |
| Generic for / missing do | `src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryForInDoTddTest.kt` | for-in do residual corpus |
| Tables | `src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryTableConstructorTddTest.kt` | Table constructor residual corpus |
| Repeat / until | `src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryRepeatUntilTddTest.kt` | Bounded nested until recovery |
| **Local / assign (TASK-320)** | `src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryLocalAssignTddTest.kt` | **37** cases: missing names/`=`/RHS, multi-target, index/member, incomplete RHS, nested blocks + `CURRENTLY_ACCEPTS` (4) / `CURRENTLY_ACCEPTS_MISSING_RHS` (6) honesty |
| **While / do (TASK-319)** | `src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryWhileDoTddTest.kt` | **25** cases: missing `do` body/siblings, missing `do`+`end`, missing `end` only, nested while, incomplete bodies (no CURRENTLY_ACCEPTS flags) |
| **Goto / label (TASK-321)** | `src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryGotoLabelTddTest.kt` | **14** required inventory cases: malformed `goto`/`::label::` residual shapes; empty vs absorbed NAME; nested residual + strict rejection |
| **Multi-RHS line-break (TASK-386)** | `src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryMultiRhsLineBreakTddTest.kt` | **18** cases: multi-line multi-RHS later-term footgun; unary/binary absorb-vs-sibling; local vs assign asymmetry; `CURRENTLY_ACCEPTS` (12) / `CURRENTLY_ACCEPTS_MISSING_RHS` (1) |
| Layout tables (Android-Lua) | `src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryLayoutTableTddTest.kt` | loadlayout / widget table residual corpus |
| AndroLua switch/when | `src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryAndroluaSwitchWhenTddTest.kt` | Version-gated switch/when recovery |
| AndroLua lambda | `src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryAndroluaLambdaTddTest.kt` | `->` / `=>` lambda recovery |
| Regression resource shapes | `src/jvmTest/resources/parser/regressions/recovery/` (when present) | Resource-backed residual shapes |
| Historical regression harness | `parser.ParserRecoveryRegressionTest` (JVM) | Supplemental regression filter for TASK-043 |

### Focused suite maps (TASK-390 refresh)

#### `LuaParserRecoveryLocalAssignTddTest` (37 cases)

Inventory families match `assertEquals` buckets in the suite (`localMissingPiecesCases` 8 / `assignmentMissingPiecesCases` 9 / `indexedMemberAndMultiTargetCases` 7 / `incompleteRhsExpressionCases` 9 / `nestedBlockCases` 4).

| Family (inventory bucket) | Count (asserted) | Residual strategy highlights | Strict flags |
| --- | --- | --- | --- |
| Local missing pieces | 8 | Empty bad `Id()`; missing initializer → `ExpressionNodeSupport`; bare namelist `Local(Id(value)=)` is valid | 1× `CURRENTLY_ACCEPTS` (bare namelist) |
| Assignment missing pieces | 9 | Missing `=` marks Assign bad + later print/local; missing RHS newline keeps print sibling | 3× `CURRENTLY_ACCEPTS_MISSING_RHS` (`a=\nprint`, trailing comma, multi-target first RHS) |
| Indexed / member / multi-target | 7 | Empty index key placeholder; member/index missing RHS; mixed multi-target trailing RHS | 3× `CURRENTLY_ACCEPTS_MISSING_RHS` |
| Incomplete RHS expressions | 9 | Binary sibling vs unary absorb; paren/`call` missing `)` → `')' expected`; unclosed call leaves seed sibling | 3× `CURRENTLY_ACCEPTS` (binary sibling absorb-under-strict; unary absorb ×2 local/assign) |
| Nested blocks | 4 | do/if/while/function bodies keep following top-level print after `end` | Default `REJECTS` |

Strict-flag suite totals (authoritative from `strictParseExpectation =` fields): **4× `CURRENTLY_ACCEPTS` + 6× `CURRENTLY_ACCEPTS_MISSING_RHS` + 27× `REJECTS`**.

Representative residual fragments (must stay reachable under recovery):

- `Local(Id()=ExpressionNodeSupport)` + later `Local` / `Return`
- `Assign(Id(a)=ExpressionNodeSupport)` + `CallStmt(Call(Id(print):Id(a)))`
- `Assign(Id(a),Id(b)=Const(1),ExpressionNodeSupport)` + later print
- `Assign(Id(a)=Binary(+,Id(value),ExpressionNodeSupport))` + sibling print/local
- `Assign(Id(a)=Unary(not,Call(Id(print):Id(a))))` (unary absorb shape note)
- `Assign(Id(a)=Call(Id(factory):ExpressionNodeSupport)); CallStmt(Call(Id(seed):)); CallStmt(Call(Id(print):…))`

#### `LuaParserRecoveryWhileDoTddTest` (25 cases)

| Family | Count | Residual strategy highlights | Diagnostics |
| --- | --- | --- | --- |
| Missing `do` keeps body | 6 | Id/binary/unary/call conditions; multi-stmt body retained | `The <do> expected` |
| Missing `do` keeps later siblings | 6 | Following print/local/return/assign remain top-level when `end` present | `The <do> expected` |
| Missing `do`+`end` / nested | 5 | Body statements stay inside while to EOF; nested missing one/both `do` | `The <do> expected` and/or `<end> expected` |
| Missing `end` only | 4 | `do` present; body to EOF / nested outer end missing | `<end> expected` |
| Incomplete body after missing do/end | 4 | Incomplete assign/call/local inside while; later print when `end` closes | do/end warnings **only when those tokens missing**; incomplete RHS uses `ExpressionNodeSupport` (no do/end diagnostics solely for incomplete expressions) |

Representative residual fragments:

- `While(Id(ready):Block[CallStmt(Call(Id(work):))])` + later `CallStmt(Call(Id(print):…))`
- `While(Binary(>,Id(n),Const(0)):Block[…])`
- Nested `While(Id(outer):Block[While(Id(inner):…)])` + following print
- Incomplete body: `While(…:Block[Assign(Id(total)=Binary(+,Id(total),ExpressionNodeSupport))])`

#### `LuaParserRecoveryGotoLabelTddTest` (14 required inventory cases)

`requiredGotoLabelCases()` / `corpusInventoryCoversRequiredGotoLabelRecoveryFamilies` assert size **14**.

| Family | Residual strategy highlights | Diagnostics / shape notes |
| --- | --- | --- |
| Missing goto name (4) | `Goto(Id())` with bad empty identifier when next token is non-NAME (eof / number / keyword / another goto) | `<name> expected` |
| Missing label name / closing `::` (5) | `Label(Id())` or `Label(Id(again))` kept; later statements when not absorbed | `<name> expected` and/or `'::' expected` |
| Mixed malformed chains + nested (5) | Incomplete label then incomplete goto; nested do/while/function keep outer trailing statements | Combined warning fragments |
| Absorbed NAME shape note (extra deterministic edges, not inventory-count rows) | `goto\nprint(1)` is **not** empty-Id under current product: NAME after line break is accepted as goto target → residual `Goto(Id(print))` (no sibling CallStmt for print); bare `goto\nprint` is well-formed in both recovery and strict | Documented product drift; empty-Id path uses `goto\nlocal …` / `goto 1` / eof |
| Strict mode | Malformed goto/label sources reject under `errorRecovery = false` while recovery does not throw; leftover after absorbed NAME (`goto\nprint(1)`) also rejects strictly | Inventory asserts deterministic strict failures |

#### `LuaParserRecoveryMultiRhsLineBreakTddTest` (18 cases; incomplete-RHS companion)

| Family | Count | Role vs LocalAssign | Strict-flag notes |
| --- | --- | --- | --- |
| Multi-line multi-RHS / later-term footguns (`multiLineMultiRhsCases`) | 6 | Documents recovery placeholder + sibling leftover after comma line-break; multi-line first-RHS residual `Assign(Id(x),Id(y)=)` | 5× `CURRENTLY_ACCEPTS` + 1× `CURRENTLY_ACCEPTS_MISSING_RHS` (`a, b =\nprint(a)`) |
| Unary/binary incomplete RHS matrix (`unaryBinaryIncompleteRhsCases`) | 8 | Second/first RHS absorb-vs-sibling matrix inside multi-RHS lists | 4× `CURRENTLY_ACCEPTS` (binary sibling absorb-under-strict; unary absorb ×2 positions) + 4× `REJECTS` (terminator / non-expression sibling forms) |
| Local multi-RHS asymmetry (`localMultiRhsLineBreakCases`) | 4 | Local keeps first-line NAME as initializer; later term still recovers as placeholder | 3× `CURRENTLY_ACCEPTS` + 1× `REJECTS` |

Use this suite when reviewing multi-line multi-RHS honesty; do not collapse it into LocalAssign alone. Suite totals: **12× `CURRENTLY_ACCEPTS` + 1× `CURRENTLY_ACCEPTS_MISSING_RHS` + 5× `REJECTS`**.

## Cross-links: serialized verification (TASK-043)

Workers in parallel waves **must not** run Gradle. Review-owned serialized verification follows `docs/serialized-verification.md`:

1. Hold verification task lock plus `build` / `.gradle` / daemon / task-progress locks.  
2. Run **one** command at a time.  
3. Prefer compile gate first when a verification wave is released:  
   `JAVA_HOME=… ./gradlew compileTestKotlinJvm` (macOS) or `./gradlew.bat compileTestKotlinJvm` (Windows).  
4. Then focused recovery filters (examples from `docs/android-lua-verification.md` / historical review commands):

```bash
# macOS host example (review-owned only; workers must not run these)
JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home \
  ./gradlew jvmTest --tests parser.recovery.LuaParserRecoveryTddTest

JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home \
  ./gradlew jvmTest --tests parser.recovery.LuaParserRecoveryDiagnosticsTddTest

JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home \
  ./gradlew jvmTest --tests parser.ParserRecoveryRegressionTest

# Family expansions (optional focused filters when a review wave needs them)
JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home \
  ./gradlew jvmTest --tests parser.recovery.LuaParserRecoveryIfChainTddTest
JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home \
  ./gradlew jvmTest --tests parser.recovery.LuaParserRecoveryNumericForEndTddTest
JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home \
  ./gradlew jvmTest --tests parser.recovery.LuaParserRecoveryForInDoTddTest
JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home \
  ./gradlew jvmTest --tests parser.recovery.LuaParserRecoveryTableConstructorTddTest
JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home \
  ./gradlew jvmTest --tests parser.recovery.LuaParserRecoveryRepeatUntilTddTest

# TASK-390 focused suites (LocalAssign / WhileDo / GotoLabel + incomplete multi-RHS companion)
JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home \
  ./gradlew jvmTest --tests parser.recovery.LuaParserRecoveryLocalAssignTddTest
JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home \
  ./gradlew jvmTest --tests parser.recovery.LuaParserRecoveryWhileDoTddTest
JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home \
  ./gradlew jvmTest --tests parser.recovery.LuaParserRecoveryGotoLabelTddTest
JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home \
  ./gradlew jvmTest --tests parser.recovery.LuaParserRecoveryMultiRhsLineBreakTddTest
```

Windows review waves use the same filters with `JAVA_HOME=C:/Users/dingyi/.jdks/temurin-17.0.11` and `./gradlew.bat` as documented in `docs/serialized-verification.md`.

### What serial review should report for recovery

When recording TASK-043 (or successor) results, call out mismatches separately for:

- Required-recovery inventory (including if/for/table/function rows above)  
- Intentionally rejected boundaries  
- Empty production-blocked inventory  
- Structured diagnostics / no-stdout contract  
- Assignment missing-equals bad-shape markers  
- Mixed Android-Lua incomplete-call residual shape  
- Function-body missing-closing-paren residual shape  
- The baseline strict-mode assignment RHS gaps (`CURRENTLY_ACCEPTS_MISSING_RHS`)  
- LocalAssign focused inventory (**37**; 4× `CURRENTLY_ACCEPTS` + 6× `CURRENTLY_ACCEPTS_MISSING_RHS`) + incomplete-RHS absorb-vs-sibling honesty  
- WhileDo focused inventory (**25**) missing do/end residual shapes (incomplete RHS without do/end token loss does **not** emit do/end diagnostics)  
- GotoLabel required inventory (**14**) + empty-Id vs absorbed-NAME shape note (`goto\nprint` vs `goto\nlocal`)  
- MultiRhs inventory (**18**; 12× `CURRENTLY_ACCEPTS` + 1× `CURRENTLY_ACCEPTS_MISSING_RHS`) later-term multi-line footgun + local/assign first-RHS asymmetry  

If a **required** case throws before producing residual AST, route that as parser implementation work — do not weaken the inventory in docs or tests without a review-created task.

## Related docs

- `docs/android-lua-verification.md` — recovery fixture boundaries and inventory policy  
- `docs/serialized-verification.md` — permanent no-parallel-Gradle rule and lock shape  
- `docs/test-strategy.md` — parser/source lane counts and TDD-first guidance  
- `docs/final-verification.md` — final verification command matrix pointers  
- `docs/acceptance-traceability.md` — AC-04 malformed-input recovery traceability  
- `docs/production-readiness.md` — production claims vs open follow-ups  
- `docs/language-server-usage.md` — how LSP surfaces parser recovery diagnostics  

## Out of scope for this doc

- Product or test edits (TASK-308 / TASK-390 are docs-only).  
- Claiming suite green without TASK-043 evidence.  
- Expanding recovery policy to intentionally rejected boundaries.  
- Semantic/type recovery after a bad AST (semantic passes consume residual AST; they are not defined here).  
- Running Gradle/jvmTest from worker waves (review-owned serial verification only).
