# Test Strategy and Inventory

TASK-006 established the original baseline used by the Android-Lua TDD
campaign. The inventory below was refreshed from `src/commonTest/kotlin` and
`src/jvmTest/kotlin` on 2026-07-14 (Monaco numeric for-loop control var number
hover TDD inventory bump; prior greeter/`build`/Button locksteps are historical)
using the same counting rules as `testinventory.NewTestInventoryTddTest`. It
excludes `testinventory.NewTestInventoryTddTest` because that file is an
accounting fixture, not campaign or product coverage.

These inventory numbers are a point-in-time source-tree snapshot only. They do
**not** claim that the full suite is green, that TASK-037/TASK-043 verification
passed, or that the campaign is production-ready. Serialized verification remains
review-owned. Post-cull lower file/method totals are intentional inventory
lockstep after suite cull, not a silent drop of product coverage bars.

## Current Test Inventory

| Area | Source set | Test files | `@Test` methods | Coverage focus |
| --- | --- | ---: | ---: | --- |
| Parser/source | JVM | 31 | 107 | Lua 5.3/5.4/AndroLua syntax, parser recovery, comments, regression fixtures, literal comments, Android-Lua grammar, AST visitor/modifier behavior, AST to Lua round trips, lexer corpora. |
| Semantic core | Common | 7 | 77 | Pipeline integration, public model facade, completion, checker expression evaluation, module type bridge, type info shape. |
| Semantic core | JVM | 89 | 601 | Binder, declarations, position queries, comment interop, doc comment parsing, type model/syntax/resolution, call/member/return checking, public API compatibility, Android-Lua interop semantics, LuaJava helper typing. |
| Workspace | Common | 9 | 128 | Document facts, module graphs, module exports, workspace engine behavior, overlays, legacy module environment support, Android import facts. |
| Workspace | JVM | 15 | 105 | JVM workspace updates, query facade behavior, Android-Lua import workspace behavior, builtin overlay loading, dirty-set planning, module graph cycles, path styles, fingerprint stability, semantic workspace campaign coverage. |
| Interop | JVM | 17 | 87 | JVM class module provider, reflection-backed workspace modeling, Java class metadata, classloader configuration, Android jar reflection, package enumeration, luajava import modeling. |
| LSP | JVM | 60 | 608 | Language service, lifecycle diagnostics, navigation symbols, text document service, hover/completion/definition/declaration/references/signature help, shutdown/idempotency, watched files/workspace folders, Android-Lua JVM resolution paths, Monaco demo URI/`utils.`/`button.`/`build`/greeter.hello/numeric-for `i` hover locks. |
| Integration | JVM | 2 | 6 | Android-Lua corpus semantic verification across parser, workspace, and interop fixtures; mixed LuaJava integration coverage. |
| **Total current inventory** | Common + JVM | **230** | **1719** | Current parser, semantic, workspace, interop, LSP, and integration coverage, excluding the inventory fixture. |

Inventory notes:

- `@Test` method counts use the Kotlin test annotation at the start of a line.
- Only Kotlin source files whose file name ends with `Test.kt` are counted.
- `src/commonTest/kotlin` is included because those tests also execute through
  the JVM target.
- Generated Gradle/build directories and production sources are excluded.
- The inventory fixture added by TASK-006 is infrastructure and is not part of
  the current inventory total above.
- Kotlin files that contain `@Test` but do not end with `Test.kt` are treated as
  excluded historical smoke fixtures, not renamed by this accounting task, and
  excluded from current inventory and campaign totals:

| Excluded Kotlin file | `@Test` methods | Policy |
| --- | ---: | --- |
| `src/commonTest/kotlin/parser.common.kt` | 2 | Excluded until a review task renames or retires the historical parser smoke fixture. |
| `src/jvmTest/kotlin/parser.jvm.kt` | 2 | Excluded until a review task renames or retires the JVM parser smoke fixture. |

## 500-Test Accounting Plan

The 500-test goal counts new TDD coverage added after the TASK-006 baseline.
Each counted test must satisfy all of these rules:

1. It lives under `src/commonTest/kotlin` or `src/jvmTest/kotlin`.
2. Its file name ends with `TddTest.kt`.
3. It is not under the `testinventory` package/path.
4. It contains one or more Kotlin test methods annotated with `@Test`.
5. The test is tied to an implementation or documentation task by task notes,
   resource path, package name, or class name.

Resource-backed cases count by the number of `@Test` methods that execute them,
not by the number of fixture files, unless a suite explicitly enumerates each
fixture through a separate `@Test` method. This keeps the accounting stable
across JVM/JUnit filtering and prevents fixture-only churn from inflating the
campaign total.

The campaign can be reported as:

```text
campaign_total = sum(@Test methods in included *TddTest.kt files)
remaining = max(0, 500 - campaign_total)
```

As of 2026-07-14 (Monaco numeric for-loop control var number hover TDD inventory
bump; not a global success claim), the current source-tree accounting reports:

```text
current_campaign_files = 174
current_campaign_total = 1279
remaining_to_500 = 0
```

The pre-campaign baseline remains 498 existing `@Test` methods, and those are
not credited toward the campaign total. They are retained as regression
coverage while new TDD suites drive the 500-test campaign.

`remaining_to_500 = 0` means only that the counted campaign method total is at
least 500 under the rules above. It is not evidence that all tests pass, that
serialized verification succeeded, or that remaining product gaps are closed.

## TASK-006 Accounting Fixture

`src/jvmTest/kotlin/testinventory/NewTestInventoryTddTest.kt` is a JVM-only,
non-invasive fixture. It reads test source files and this document from disk,
then asserts that:

- common and JVM parser, semantic, workspace, interop, LSP, and integration
  tests are present;
- the documented current inventory totals match the source tree, excluding the
  inventory fixture itself;
- any new counted TDD campaign suites follow the path and annotation rules
  above;
- the strategy document records the baseline and campaign formula.

The fixture prints a concise inventory report during the required test run. It
does not import or mutate production code and can run before future
implementation tasks are complete.

Inventory fixture constants stay lockstep with the live source tree (baseline
230/1719, campaign 174/1279) after provider-binder require typing regressions
on top of the Monaco greeter/`build` baseline. `testinventory` remains excluded from campaign totals.
Area classification maps `/parser/`, `/source/`, and `/lexer/` into parser so no
counted suite lands in `other`. Full Windows suite authority remains TASK-043 /
windows-full-jvmtest.
