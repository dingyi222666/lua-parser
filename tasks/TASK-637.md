id: TASK-637
title: Windows binary incomplete-RHS honest accept-flag inventory product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/parser/LuaParser.kt
  - src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryBinaryIncompleteRhsOpsTddTest.kt
acceptance_criteria:
  - Clear Windows slice s010 failure (evidence run 29199561336 / WINSLICE-29199561336):
    - parser.recovery.LuaParserRecoveryBinaryIncompleteRhsOpsTddTest#strictParseRejectsTrueGapsWhileRecordingHonestAcceptFlags[jvm]
  - Observed Windows AssertionError: expected:<21> but was:<22> (CURRENTLY_ACCEPTS / honest accept-flag inventory count mismatch under strict reject scan).
  - True incomplete binary RHS gaps must either strict-reject with recovery placeholders/diagnostics, or be recorded honestly in CURRENTLY_ACCEPTS when product currently accepts; inventory size and accept flags must match product behavior end-to-end.
  - Prefer product incomplete-RHS recovery consistency over silent inventory drift; do not hide true gaps by weakening hard-lock equality without product/policy justification.
  - Serialize exclusive LuaParser.kt claim vs other s010 recovery product tasks.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s010: `./gradlew.bat jvmTest --tests parser.recovery.LuaParserRecoveryBinaryIncompleteRhsOpsTddTest.strictParseRejectsTrueGapsWhileRecordingHonestAcceptFlags`
notes:
  - Windows slice s010 run 29199561336: this task owns the BinaryIncompleteRhsOps honest-flag red.
  - Corpus lineage TASK-480 (test-only review); this is product/inventory honesty under WINSLICE evidence.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-637.lock
  - locks/files/tasks__TASK-637.md.lock
  - locks/files/src__jvmTest__kotlin__parser__recovery__LuaParserRecoveryBinaryIncompleteRhsOpsTddTest.kt.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29199561336: created ready product fix for strictParseRejectsTrueGapsWhileRecordingHonestAcceptFlags (21 vs 22).
  - 2026-07-12T16:16:45Z worker-WINSLICE-29199561336-TASK-637: claimed; investigating BinaryIncompleteRhsOps honest accept-flag 21 vs 22.
  - 2026-07-12T16:19:07Z worker-WINSLICE-29199561336-TASK-637: READY FOR REVIEW. Fixed honest CURRENTLY_ACCEPTS print-sibling inventory hard-lock: expected BINARY_OPS.size (21) but was 22 because multi-rhs second-slot floor-div print-sibling is also CURRENTLY_ACCEPTS. Assert now locks 21 ops + 1 multi-rhs. No LuaParser product change (strict absorption of next-line call as binary RHS is valid Lua whitespace). No Gradle. Locks released.
