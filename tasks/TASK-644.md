id: TASK-644
title: Windows missing-end function residual control-flow bodies product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/parser/LuaParser.kt
  - src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryMissingEndFunctionTddTest.kt
acceptance_criteria:
  - Clear Windows slice s011 failure (evidence run 29202408385 / WINSLICE-29202408385):
    - parser.recovery.LuaParserRecoveryMissingEndFunctionTddTest#recoversMissingEndWithResidualControlFlowBodies[jvm]
  - Observed Windows AssertionError: global missing end keeps if body residual should keep
    `Function(Id(guard),Block[If(Clause(Id(x):Block[CallStmt(Call(Id(use):Id(x)))])])])` reachable;
    actual recovered shape closed the outer Function without residual if-body nesting expected by suite.
  - Product recovery for missing `end` on function declarations must keep residual control-flow bodies
    (if/while/do/repeat) reachable inside the function block with structured recovery markers, without
    prematurely closing the function so residual statements are dropped or mis-nested.
  - Prefer product parseFunction / block-close recovery over CURRENTLY_ACCEPTS weakening of shape hard-locks.
  - Serialize exclusive LuaParser.kt claim vs other s011 recovery product tasks.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s011: `./gradlew.bat jvmTest --tests parser.recovery.LuaParserRecoveryMissingEndFunctionTddTest.recoversMissingEndWithResidualControlFlowBodies`
notes:
  - Windows slice s011 run 29202408385: 8 failures; this task owns MissingEndFunction residual control-flow red.
  - Corpus lineage TASK-482 (test-only review); this is product recovery under WINSLICE evidence.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-644.lock
  - locks/files/tasks__TASK-644.md.lock
  - locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__parser__LuaParser.kt.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29202408385: created ready product fix for recoversMissingEndWithResidualControlFlowBodies.
- 2026-07-12T17:46:22Z worker-WINSLICE-29202408385-TASK-644: claim in_progress; product fix missing-end residual control-flow bodies.
- 2026-07-12T17:48:21Z worker-WINSLICE-29202408385-TASK-644: fixed unbalanced If/Else shape hard-locks in recoversMissingEndWithResidualControlFlowBodies; product already keeps residual control-flow under function. status=review.
