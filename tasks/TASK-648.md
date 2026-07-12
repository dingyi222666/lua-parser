id: TASK-648
title: Windows missing end/then/do/until recovery inventory count product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/parser/LuaParser.kt
  - src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryTddTest.kt
acceptance_criteria:
  - Clear Windows slice s011 failure (evidence run 29202408385 / WINSLICE-29202408385):
    - parser.recovery.LuaParserRecoveryTddTest#recoversMissingEndThenDoAndUntilWhileKeepingStatementsReachable[jvm]
  - Observed Windows AssertionError: expected:<18> but was:<20> (missing end/then/do/until recovery case
    inventory hard-lock count mismatch while keeping statements reachable).
  - Missing end / then / do / until recovery cases must keep later statements reachable and inventory
    size/classification must match product (18 vs 20 drift resolved via product recovery consistency or
    honest inventory update when product intentionally accepts extra cases).
  - Prefer product block-close recovery over silent inventory drift; coordinate with TASK-644 missing-end
    function residual and TASK-646 required inventory.
  - Serialize exclusive LuaParser.kt claim vs other s011 recovery product tasks.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s011: `./gradlew.bat jvmTest --tests parser.recovery.LuaParserRecoveryTddTest.recoversMissingEndThenDoAndUntilWhileKeepingStatementsReachable`
notes:
  - Windows slice s011 run 29202408385: missing end/then/do/until inventory 18 vs 20 red.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-648.lock
  - locks/files/tasks__TASK-648.md.lock
  - locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__parser__LuaParser.kt.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29202408385: created ready product fix for recoversMissingEndThenDoAndUntilWhileKeepingStatementsReachable (18 vs 20).
  - 2026-07-12T17:47:50Z worker-WINSLICE-29202408385-TASK-648: claim in_progress; inventory hard-lock 18 vs actual 20 (AndroLua optional-then / C-style cases). Prefer honest inventory update; yield LuaParser.kt exclusive to TASK-644.
  - 2026-07-12T17:48:30Z worker-WINSLICE-29202408385-TASK-648: status=review; honest inventory hard-lock 18->20 for missingDelimiterCases (AndroLua optional-then TASK-613 + C-style !=/&& TASK-614 intentional CURRENTLY_ACCEPTS). No LuaParser product change; exclusive LuaParser claim left with TASK-644. TASK-646 still owns required total 66->68 honesty.
  - 2026-07-12T17:51:40Z worker-WINSLICE-29202408385-TASK-648: re-claim in_progress; re-verify missingDelimiterCases size==20 and assertEquals(20); inventory already honest; no further product/test edit needed.
  - 2026-07-12T17:52:10Z worker-WINSLICE-29202408385-TASK-648: status=review owner=unassigned; AC satisfied via inventory hard-lock 20 matching product (20 cases). No LuaParser.kt edit (exclusive left free/TASK-644). Workers no Gradle.
