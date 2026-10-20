id: TASK-649
title: Windows local/assign missing pieces ExpressionNodeSupport recovery product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/parser/LuaParser.kt
  - src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryTddTest.kt
acceptance_criteria:
  - Clear Windows slice s011 failure (evidence run 29202408385 / WINSLICE-29202408385):
    - parser.recovery.LuaParserRecoveryTddTest#recoversLocalDeclarationsAndAssignmentsWithMissingPieces[jvm]
  - Observed Windows AssertionError: assignment missing equals after varlist should keep print should mark a
    recovered node containing `Assign(Id(a),ExpressionNodeSupport=)` as bad; actual bad nodes:
    `[Assign(Id(a),Id(b)=)]` (absorbed following NAME as RHS instead of ExpressionNodeSupport placeholder + sibling).
  - Local declaration / assignment recovery with missing `=` / RHS pieces must insert ExpressionNodeSupport
    (or equivalent residual placeholder), mark recovered nodes bad, and keep later print as sibling — not
    absorb following statement-start NAME as assignment RHS when suite expects placeholder.
  - Prefer product parseAssignment / parseLocal / exp-list recovery over golden-only flips.
  - Serialize exclusive LuaParser.kt claim vs other s011 recovery product tasks.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s011: `./gradlew.bat jvmTest --tests parser.recovery.LuaParserRecoveryTddTest.recoversLocalDeclarationsAndAssignmentsWithMissingPieces`
notes:
  - Windows slice s011 run 29202408385: local/assign missing pieces red (TASK-085/178 lineage regression).
  - Adjacent TASK-643 incomplete call-RHS; serialize LuaParser exclusive.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-649.lock
  - locks/files/tasks__TASK-649.md.lock
  - locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__parser__LuaParser.kt.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29202408385: created ready product fix for recoversLocalDeclarationsAndAssignmentsWithMissingPieces.
  - 2026-07-12T17:46:30Z worker-WINSLICE-29202408385-TASK-649: claimed; investigating assignment missing-equals recovery absorbing following NAME.
  - 2026-07-12T17:53:00Z worker-WINSLICE-29202408385-TASK-649: product fix parseAssignmentStatement residual bare NAME after comma when missing '='; insert ExpressionNodeSupport, leave NAME for CallStmt sibling; status=review.
