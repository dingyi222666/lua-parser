id: TASK-643
title: Windows local/assign incomplete call-RHS recovery product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/parser/LuaParser.kt
  - src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryLocalAssignTddTest.kt
acceptance_criteria:
  - Clear Windows slice s010 failure (evidence run 29199561336 / WINSLICE-29199561336):
    - parser.recovery.LuaParserRecoveryLocalAssignTddTest#recoversIncompleteRhsExpressionsAndKeepsLaterStatements[jvm]
  - Observed Windows AssertionError: assignment call rhs missing close keeps later print should keep `Assign(Id(a)=Call(Id(factory):ExpressionNodeSupport))` reachable; actual shape was `Assign(Id(a)=Call(Id(factory):Id(seed)));CallStmt(Call(Id(print):Id(a)))` (seed absorbed as arg instead of placeholder + sibling).
  - Incomplete call-RHS recovery must insert ExpressionNodeSupport for missing close/arg gaps, keep later print sibling, and leave incomplete call args recoverable without absorbing following NAME as argument when suite expects placeholder.
  - Prefer product parseCallArgumentList / parseExpList recovery over golden-only flips when product previously matched ExpressionNodeSupport contract; keep multi-RHS incomplete inventory honest.
  - Serialize exclusive LuaParser.kt claim vs other s010 recovery product tasks.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s010: `./gradlew.bat jvmTest --tests parser.recovery.LuaParserRecoveryLocalAssignTddTest.recoversIncompleteRhsExpressionsAndKeepsLaterStatements`
notes:
  - Windows slice s010 run 29199561336: this task owns the LocalAssign incomplete RHS red.
  - Prior TASK-320 test-only done; product shape now absorbs Id(seed) as arg — restore incomplete-call recovery product contract under WINSLICE.
  - Adjacent TASK-546/553 multi-RHS review lineage; exclusive LuaParser serialize.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-643.lock
  - locks/files/tasks__TASK-643.md.lock
  - locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__parser__LuaParser.kt.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29199561336: created ready product fix for recoversIncompleteRhsExpressionsAndKeepsLaterStatements.
  - 2026-07-12T16:16:33Z claim by worker-WINSLICE-29199561336-TASK-643: product fix incomplete call-RHS recovery (ExpressionNodeSupport + sibling seed).

  - 2026-07-12T16:45:00Z worker-WINSLICE-29199561336-TASK-643: exclusive LuaParser.kt lock acquired; implementing incomplete call-RHS ExpressionNodeSupport + sibling seed recovery.
  - 2026-07-12T16:55:00Z worker-WINSLICE-29199561336-TASK-643: product fix in parseCallExpression — assignment/local RHS bare NAME arg after `(` with linebreak + following statement-start leaves NAME unconsumed (ExpressionNodeSupport + CallStmt(seed) + print sibling); top-level TASK-551 path unchanged. status→review; locks released.
