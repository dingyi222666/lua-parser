id: TASK-641
title: Windows incomplete table-field recovery product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/parser/LuaParser.kt
  - src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryIncompleteTableFieldTddTest.kt
acceptance_criteria:
  - Clear Windows slice s010 failures (evidence run 29199561336 / WINSLICE-29199561336):
    - parser.recovery.LuaParserRecoveryIncompleteTableFieldTddTest#recoversNestedIncompleteFieldsAndStatementContexts[jvm]
    - parser.recovery.LuaParserRecoveryIncompleteTableFieldTddTest#strictParseRejectsTrueGapsWhileRecordingHonestAcceptFlags[jvm]
    - parser.recovery.LuaParserRecoveryIncompleteTableFieldTddTest#recoveryDiagnosticsArePresentDeterministicAndDoNotThrow[jvm]
  - Observed Windows failures:
    - recovery throws / parseRecoveringWithoutThrow fails for `return incomplete named field keeps later print sibling`
    - diagnostics path IllegalStateException: `(2,1): unexpected print near '<eof>'` from LuaParser.error during parseWithDiagnostics
  - Incomplete nested table fields in statement contexts must recover without throw, keep later print sibling, emit deterministic structured diagnostics (e.g. `'}' expected` / field incompleteness), and strict must reject true gaps with honest accept flags.
  - Prefer product table constructor / field recovery close policy that does not absorb or hard-error on following statement-start; no CURRENTLY_ACCEPTS weakening of no-throw hard-locks.
  - Serialize exclusive LuaParser.kt claim vs other s010 recovery product tasks.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s010: `./gradlew.bat jvmTest --tests parser.recovery.LuaParserRecoveryIncompleteTableFieldTddTest`
notes:
  - Windows slice s010 run 29199561336: 3 reds clustered on IncompleteTableField suite.
  - Corpus lineage TASK-481 (test-only review); this is product recovery under WINSLICE evidence.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-641.lock
  - locks/files/tasks__TASK-641.md.lock
  - locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__parser__LuaParser.kt.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29199561336: created ready product fix for 3 IncompleteTableField recovery reds.
  - 2026-07-12T16:16:15Z claim TASK-641; product fix incomplete table-field recovery (return/print sibling, diagnostics no-throw).
  - 2026-07-12T16:30:00Z product: after return under recovery continue residual statements as siblings; parseChunk recovery drains residual EOF instead of throwing. Addresses return incomplete table field + diagnostics no-throw.
