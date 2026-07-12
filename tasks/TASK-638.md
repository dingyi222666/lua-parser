id: TASK-638
title: Windows recovery missing-then structured diagnostics product fix
status: ready
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/parser/LuaParser.kt
  - src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryDiagnosticsExpandTddTest.kt
  - src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryDiagnosticsTddTest.kt
acceptance_criteria:
  - Clear Windows slice s010 failures (evidence run 29199561336 / WINSLICE-29199561336):
    - parser.recovery.LuaParserRecoveryDiagnosticsExpandTddTest#expandsIfMissingThenDiagnosticGoldenWithExactRange[jvm]
    - parser.recovery.LuaParserRecoveryDiagnosticsExpandTddTest#expandsDiagnosticsAreDeterministicAndResetAcrossParses[jvm]
    - parser.recovery.LuaParserRecoveryDiagnosticsExpandTddTest#expandsSparseGoldenInventoryCoversRequiredRecoveryFamilies[jvm]
    - parser.recovery.LuaParserRecoveryDiagnosticsTddTest#parserRecoveryCollectsStructuredDiagnosticsWithoutStdoutNoise[jvm]
  - Observed Windows failures: for `if ready print('x') end`, expected structured Diag(message=The <then> expected near print, range=([1, 10], [1, 15])) but was empty []; expand sparse inventory also requires message containing 'The <then> expected'.
  - Product recovery must emit precise structured diagnostics (no stdout-only noise) for missing `then`, with exact golden ranges, deterministic double-parse, and reset across independent parses; sparse golden inventory must cover required recovery families including if-missing-then.
  - Prefer product parseIfStatement / diagnostic sink fix; do not CURRENTLY_ACCEPTS empty diagnostic streams for these hard-lock goldens.
  - Serialize exclusive LuaParser.kt claim vs other s010 recovery product tasks.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s010: `./gradlew.bat jvmTest --tests parser.recovery.LuaParserRecoveryDiagnosticsExpandTddTest --tests parser.recovery.LuaParserRecoveryDiagnosticsTddTest.parserRecoveryCollectsStructuredDiagnosticsWithoutStdoutNoise`
notes:
  - Windows slice s010 run 29199561336: 4 reds clustered on missing-then structured diagnostics (Expand ×3 + DiagnosticsTdd ×1).
  - Adjacent TASK-580 (local missing initializer, review) is distinct; this task owns if-missing-then family under WINSLICE evidence.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-638.lock
  - locks/files/tasks__TASK-638.md.lock
  - locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__parser__LuaParser.kt.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29199561336: created ready product fix for 4 missing-then structured diagnostic reds.
  - 2026-07-12T16:17:06Z worker-WINSLICE-29199561336-TASK-638: blocked: LuaParser.kt locked by live TASK-637 (owner worker-WINSLICE-29199561336-TASK-637); leave ready, no product edit.
