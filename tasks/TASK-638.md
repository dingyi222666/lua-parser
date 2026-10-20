id: TASK-638
title: Windows recovery missing-then structured diagnostics product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/parser/LuaParser.kt
  - src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryDiagnosticsExpandTddTest.kt
  - src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryDiagnosticsTddTest.kt
acceptance_criteria:
  - Clear Windows slice s010 failures (evidence run 29201787735 / WINSLICE-29201787735; still red after 29201312516 and 29199561336):
    - parser.recovery.LuaParserRecoveryDiagnosticsExpandTddTest#expandsIfMissingThenDiagnosticGoldenWithExactRange[jvm]
    - parser.recovery.LuaParserRecoveryDiagnosticsExpandTddTest#expandsDiagnosticsAreDeterministicAndResetAcrossParses[jvm]
    - parser.recovery.LuaParserRecoveryDiagnosticsExpandTddTest#expandsSparseGoldenInventoryCoversRequiredRecoveryFamilies[jvm]
    - parser.recovery.LuaParserRecoveryDiagnosticsTddTest#parserRecoveryCollectsStructuredDiagnosticsWithoutStdoutNoise[jvm]
  - Observed Windows failures: for `if ready print('x') end`, expected structured Diag(message=The <then> expected near print, range=([1, 10], [1, 15])) but was empty []; expand sparse inventory also requires message containing 'The <then> expected'.
  - Product recovery must emit precise structured diagnostics (no stdout-only noise) for missing `then`, with exact golden ranges, deterministic double-parse, and reset across independent parses; sparse golden inventory must cover required recovery families including if-missing-then.
  - Prefer product parseIfStatement / diagnostic sink fix; do not CURRENTLY_ACCEPTS empty diagnostic streams for these hard-lock goldens.
  - MUST fix product: emit The then expected under AndroLua optional-then path (parseIfCause/parseElseIfCause currently skip missing-then warning under isAndroLua()).
  - Serialize exclusive LuaParser.kt claim vs other s010 recovery product tasks.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s010: `./gradlew.bat jvmTest --tests parser.recovery.LuaParserRecoveryDiagnosticsExpandTddTest --tests parser.recovery.LuaParserRecoveryDiagnosticsTddTest.parserRecoveryCollectsStructuredDiagnosticsWithoutStdoutNoise`
notes:
  - Windows slice s010 run 29201787735: still 4 reds clustered on missing-then structured diagnostics (Expand ×3 + DiagnosticsTdd ×1); layout-table (TASK-642) cleared this run (4/166 down from 5). Re-materialize reuses this ready task only.
  - Windows slice s010 run 29201312516: still 4 missing-then reds + layout-table; worker blocked on LuaParser.kt exclusive lock held by TASK-642.
  - Prior evidence run 29199561336 same cluster; worker was blocked on LuaParser.kt exclusive lock.
  - Adjacent TASK-580 (local missing initializer, review) is distinct; this task owns if-missing-then family under WINSLICE evidence.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-638.lock
  - locks/files/tasks__TASK-638.md.lock
  - locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__parser__LuaParser.kt.lock
related_commits: []
progress:
  - 2026-07-12T17:26:31Z worker-WINSLICE-29201787735-TASK-638: product fix in parseIfCause/parseElseIfCause — AndroLua optional-then path now records 'The <then> expected near ...' via warning() under errorRecovery without marking clause bad (strict still accepts). Exclusive LuaParser.kt only. No gradle.
  - 2026-07-12T17:25:58Z worker-WINSLICE-29201787735-TASK-638: claim exclusive LuaParser.kt; emit missing-then structured diagnostic under AndroLua optional-then path while keeping strict accept.
  - 2026-07-13T materialize WINSLICE-29201787735: reuse ready product TASK-638 for 4 still-red missing-then structured diagnostic failures (s010 4/166; layout-table no longer red so TASK-642 not re-queued).
  - 2026-07-12T17:15:44Z worker-WINSLICE-29201312516-TASK-638: blocked: LuaParser.kt locked by live TASK-642 (owner worker-WINSLICE-29201312516-TASK-642, acquired 2026-07-12T17:15:09Z); leave ready, no product edit. Root cause confirmed: parseIfCause/parseElseIfCause skip missing-then warning under isAndroLua() optional-then path, so ANDROLUA_5_3 goldens for `if ready print('x') end` emit empty diagnostics.
  - 2026-07-13T materialize WINSLICE-29201312516: reuse ready product task for 4 still-red missing-then structured diagnostic failures (s010 5/166).
  - 2026-07-13T materialize WINSLICE-29199561336: created ready product fix for 4 missing-then structured diagnostic reds.
  - 2026-07-12T16:17:06Z worker-WINSLICE-29199561336-TASK-638: blocked: LuaParser.kt locked by live TASK-637 (owner worker-WINSLICE-29199561336-TASK-637); leave ready, no product edit.
