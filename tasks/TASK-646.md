id: TASK-646
title: Windows recovery required inventory and strict-parse gaps honesty product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/parser/LuaParser.kt
  - src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryTddTest.kt
acceptance_criteria:
  - Clear Windows slice s011 failure (evidence run 29202408385 / WINSLICE-29202408385):
    - parser.recovery.LuaParserRecoveryTddTest#documentsRequiredRecoveryInventoryAndStrictParseGaps[jvm]
  - Observed Windows AssertionError: expected:<66> but was:<68> (required recovery inventory / strict-gap
    hard-lock count mismatch).
  - Required recovery inventory size and strict-parse gap classification must match product behavior
    end-to-end: true gaps strict-reject; CURRENTLY_ACCEPTS only when product honestly accepts; inventory
    size hard-lock equals actual classified cases (66 vs 68 drift must be resolved via product or honest inventory).
  - Prefer product recovery consistency; do not hide true gaps by weakening hard-lock equality without
    product/policy justification. Coordinate with TASK-645 out-of-scope boundary inventory.
  - Serialize exclusive LuaParser.kt claim vs other s011 recovery product tasks.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s011: `./gradlew.bat jvmTest --tests parser.recovery.LuaParserRecoveryTddTest.documentsRequiredRecoveryInventoryAndStrictParseGaps`
notes:
  - Windows slice s011 run 29202408385: inventory 66 vs 68 red on RecoveryTdd.
  - Prior corpus product changes (optional-then, compact switch, incomplete call) may have shifted inventory.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-646.lock
  - locks/files/tasks__TASK-646.md.lock
  - locks/files/src__jvmTest__kotlin__parser__recovery__LuaParserRecoveryTddTest.kt.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29202408385: created ready product fix for documentsRequiredRecoveryInventoryAndStrictParseGaps (66 vs 68).
  - 2026-07-12T17:49:03Z worker-WINSLICE-29202408385-TASK-646: claim in_progress; required inventory hard-lock 66 vs actual 68 after AndroLua optional-then/C-style extras (TASK-613/614/648 lineage). Inventory honesty only; LuaParser exclusive held by live TASK-644 so no product edit.
  - 2026-07-12T17:49:22Z worker-WINSLICE-29202408385-TASK-646: honest inventory hard-lock 66->68 for requiredRecoveryCases (20 missingDelimiter + 15 malformed + 7 local/assign + 21 function/table/call + 5 return/loop + 0 production-blocked). Aligns with TASK-648 missingDelimiter 20 and AndroLua TASK-613/614 extras.
  - 2026-07-12T17:49:22Z worker-WINSLICE-29202408385-TASK-646: strict-gap assertContentEquals now only CURRENTLY_ACCEPTS_MISSING_RHS name (assignment missing rhs). Trailing-comma multi-RHS remains CURRENTLY_ACCEPTS (TASK-546 valid absorb), not MISSING_RHS. No LuaParser product edit (exclusive held by TASK-644). status->review; locks released.
