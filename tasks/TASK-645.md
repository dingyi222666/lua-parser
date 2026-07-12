id: TASK-645
title: Windows recovery out-of-scope boundary strict-reject product fix
status: ready
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/parser/LuaParser.kt
  - src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryTddTest.kt
acceptance_criteria:
  - Clear Windows slice s011 failure (evidence run 29202899347 / WINSLICE-29202899347; prior red 29202408385):
    - parser.recovery.LuaParserRecoveryTddTest#rejectsOutOfScopeRecoveryBoundariesEvenWithRecoveryEnabled[jvm]
  - Observed Windows AssertionError: Expected an exception to be thrown, but was completed successfully
    (out-of-scope recovery boundary cases must still throw / hard-fail even with recovery enabled).
  - Product recovery must not silently accept out-of-scope recovery boundaries; true out-of-scope
    residual/resync paths must reject (throw or fail parse) while in-scope recovery cases remain recoverable.
  - Prefer product boundary guards over CURRENTLY_ACCEPTS weakening of assertThrows hard-locks.
  - Serialize exclusive LuaParser.kt claim vs other s011 recovery product tasks.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s011: `./gradlew.bat jvmTest --tests parser.recovery.LuaParserRecoveryTddTest.rejectsOutOfScopeRecoveryBoundariesEvenWithRecoveryEnabled`
notes:
  - Windows slice s011 run 29202899347: still red (2 failures total on s011; down from 8 on 29202408385).
  - Windows slice s011 run 29202408385: RecoveryTdd out-of-scope boundary red (origin).
  - Adjacent inventory honesty (TASK-646) may share inventory constants; coordinate without stealing exclusive locks mid-wave.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-645.lock
  - locks/files/tasks__TASK-645.md.lock
  - locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__parser__LuaParser.kt.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29202408385: created ready product fix for rejectsOutOfScopeRecoveryBoundariesEvenWithRecoveryEnabled.
  - 2026-07-12T17:46:59Z worker-WINSLICE-29202408385-TASK-645: STOP blocked — locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__parser__LuaParser.kt.lock held by live other task TASK-649 (owner worker-WINSLICE-29202408385-TASK-649). Left status=ready; no product edit.
  - 2026-07-13T materialize WINSLICE-29202899347: reuse ready product fix — same classname#method still red on s011 (failureCount 2).
