id: TASK-676
title: Windows AST2Lua nested if+outer-else no-if-end shape-drift product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/source/AST2Lua.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/parser/LuaParser.kt
  - src/jvmTest/kotlin/source/AST2LuaIfElseifRoundTripTddTest.kt
acceptance_criteria:
  - Clear Windows slice s018 failure (evidence run 29220594557 / WINSLICE-29220594557):
    - source.AST2LuaIfElseifRoundTripTddTest#nestedIfWithOuterSiblingRequiresDoTerminatorForShapeStableReparse[jvm]
  - Observed Windows AssertionError: `Expected bare nested if+outer-else to drift under no-if-end policy` with printed shape that no longer drifts on reparse for bare nested if+else then outer else inside `do … end`.
  - Product/test alignment under established no-if-end if-print policy (policy §3/§6): bare nested if+outer-else must keep the documented shape-drift behavior that requires an inner `do … end` terminator for shape-stable reparse; do-isolated nested if corpus must remain shape-stable; pure-if print must not emit trailing `end`.
  - Prefer AST2Lua IfClause/IfStatement print + parseIfStatement ownership product fix over CURRENTLY_ACCEPTS; if printer already intentionally shape-stable, update only the failing assertion/corpus to match product while preserving do-isolation requirement docs — do not weaken other round-trip locks.
  - Serialize exclusive claims on AST2Lua.kt / LuaParser.kt vs other s018 product tasks.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s018: `./gradlew.bat jvmTest --tests source.AST2LuaIfElseifRoundTripTddTest.nestedIfWithOuterSiblingRequiresDoTerminatorForShapeStableReparse`
notes:
  - Windows slice s018 run 29220594557: 3 failures; this task owns the AST2Lua if-elseif nested shape red only.
  - Lineage: TASK-303 (done) added the regression corpus; product no longer matches the bare-drift expectation on Windows CI.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-676.lock
  - locks/files/tasks__TASK-676.md.lock
  - locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__source__AST2Lua.kt.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29220594557: created ready product fix for nestedIfWithOuterSiblingRequiresDoTerminatorForShapeStableReparse.
  - 2026-07-13T03:02:33Z worker-WINSLICE-29220594557-TASK-676: claim in_progress; investigate nestedIfWithOuterSibling bare-drift vs product no-if-end print/parse.
  - 2026-07-13T03:05:14Z worker-WINSLICE-29220594557-TASK-676: product already shape-stable under no-if-end + parseIfStatement missing-end recovery; updated nestedIfWithOuterSiblingRequiresDoTerminatorForShapeStableReparse to assert bare shape-stable + single outer do end, keep do-isolation corpus locks; policy §6 docs aligned. No AST2Lua/LuaParser product change. status→review.
