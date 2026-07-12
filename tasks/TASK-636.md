id: TASK-636
title: Windows AndroLua switch/when missing-do recovery product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/parser/LuaParser.kt
  - src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryAndroluaSwitchWhenTddTest.kt
acceptance_criteria:
  - Clear Windows slice s010 failure (evidence run 29199561336 / WINSLICE-29199561336):
    - parser.recovery.LuaParserRecoveryAndroluaSwitchWhenTddTest#recoversMissingDoAndEndAndIncompleteSwitchConditions[jvm]
  - Observed Windows AssertionError: Expected an exception to be thrown, but was completed successfully (assertParseFails on strict path for missing-do / incomplete switch-condition cases).
  - Strict vs recovery contract for AndroLua switch/when must be honest: true recovery gaps (missing do/end, incomplete switch conditions) must fail strict parse while parseRecovering builds SwitchStatement/CaseCause (or residual shapes) with structured diagnostics; legal compact optional-do forms must not be mislabeled as recovery-only.
  - Prefer product LuaParser switch/when policy over CURRENTLY_ACCEPTS weakening of hard-lock asserts; keep suite deterministic across double-parse.
  - Serialize exclusive LuaParser.kt claim vs other s010 recovery product tasks.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s010: `./gradlew.bat jvmTest --tests parser.recovery.LuaParserRecoveryAndroluaSwitchWhenTddTest.recoversMissingDoAndEndAndIncompleteSwitchConditions`
notes:
  - Windows slice s010 run 29199561336: 166 tests, 13 failures; this task owns the AndroLua switch/when red.
  - Adjacent TASK-610/631 compact optional-do policy may have made some missing-do cases strict-accept; reconcile inventory honesty without rewriting external Android-Lua sources.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-636.lock
  - locks/files/tasks__TASK-636.md.lock
  - locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__parser__LuaParser.kt.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29199561336: created ready product fix for recoversMissingDoAndEndAndIncompleteSwitchConditions.
  - 2026-07-12T16:19:21Z claim by worker-WINSLICE-29199561336-TASK-636: investigate strict-reject gaps for missing-do/end incomplete switch conditions.
  - 2026-07-12T16:25:00Z root cause: nested compact outer switch case in missingDoEndAndConditionCases still REJECTS after TASK-610 optional-do; strict succeeds so assertParseFails fails.
  - 2026-07-12T16:25:00Z inventory honesty: mark nested compact optional-do (matching end) as CURRENTLY_ACCEPTS; rename case; keep missing-end / incomplete-condition cases REJECTS. No LuaParser product change (optional-do already correct).
  - 2026-07-12T16:25:00Z status=review owner=unassigned for WINSLICE-29199561336 TASK-636.
