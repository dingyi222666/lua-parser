id: TASK-639
title: Windows goto residual target absorption product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/parser/LuaParser.kt
  - src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryGotoLabelTddTest.kt
acceptance_criteria:
  - Clear Windows slice s010 failure (evidence run 29199561336 / WINSLICE-29199561336):
    - parser.recovery.LuaParserRecoveryGotoLabelTddTest#residualGotoAndLabelNodesExposeEmptyOrNamedIdentifiers[jvm]
  - Observed Windows AssertionError: print should not remain a sibling CallStmt when absorbed as goto target; shape=Chunk(Block[Goto(Id(print));CallStmt(Call(Const(1):))]) — product left CallStmt sibling instead of absorbing `print` as goto target (or empty residual Id per golden policy).
  - Residual goto/label nodes must expose empty or named identifiers per suite contract: when product absorbs NAME after `goto` as target, no sibling CallStmt remains for that NAME; empty-Id residual cases stay deterministic; recovery without throw.
  - Prefer product parseGoto/parseStatementNameOrMissing fix over test-only golden flip unless product policy is already intentional and goldens are wrong — under WINSLICE, restore absorption consistency.
  - Serialize exclusive LuaParser.kt claim vs other s010 recovery product tasks.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s010: `./gradlew.bat jvmTest --tests parser.recovery.LuaParserRecoveryGotoLabelTddTest.residualGotoAndLabelNodesExposeEmptyOrNamedIdentifiers`
notes:
  - Windows slice s010 run 29199561336: this task owns the GotoLabel residual red.
  - Prior TASK-321 was test-only done; product regressed under current parser (sibling CallStmt retained).
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-639.lock
  - locks/files/tasks__TASK-639.md.lock
  - locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__parser__LuaParser.kt.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29199561336: created ready product fix for residualGotoAndLabelNodesExposeEmptyOrNamedIdentifiers.

  - 2026-07-12T16:16:38Z worker-WINSLICE-29199561336-TASK-639: claim in_progress; fixing residual goto target absorption in LuaParser.
  - 2026-07-12T16:25:08Z worker-WINSLICE-29199561336-TASK-639: claim locks; drain same-line leftover funcargs after goto target so residual has no CallStmt sibling.
  - 2026-07-12T16:40:00Z worker-WINSLICE-29199561336-TASK-639: product fix parseGotoStatement drains same-line leftover funcargs after absorbed NAME so residualGotoAndLabelNodesExposeEmptyOrNamedIdentifiers has no sibling CallStmt; status=review.
