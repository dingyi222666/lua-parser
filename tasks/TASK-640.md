id: TASK-640
title: Windows if-elseif duplicate-else recovery product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/parser/LuaParser.kt
  - src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryIfElseifChainTddTest.kt
acceptance_criteria:
  - Clear FULL jvmTest failure (evidence run 29225343255 / FULLJVM-29225343255):
    - parser.recovery.LuaParserRecoveryIfElseifChainTddTest#recoversDuplicateElseWithoutInfiniteClauseLoopAndKeepsFollowingStatement[jvm]
  - Observed full-suite AssertionError: recovery parse must not throw for `if ready then work() else one() else two() end\nprint(ready)`.
  - Clear Windows slice s010 failure (evidence run 29199561336 / WINSLICE-29199561336):
    - parser.recovery.LuaParserRecoveryIfElseifChainTddTest#recoversDuplicateElseWithoutInfiniteClauseLoopAndKeepsFollowingStatement[jvm]
  - Observed Windows AssertionError: recovery parse must not throw for `if ready then work() else one() else two() end\nprint(ready)` (parseRecoveringWithoutThrow).
  - Product must recover duplicate `else` without infinite clause loop, without throw, and keep following statement (print) as sibling; mark residual clauses/if as bad with structured diagnostics where appropriate.
  - Prefer product parseIfStatement bounded clause loop (stop after first else; no re-entry on duplicate else/elseif) over CURRENTLY_ACCEPTS; do not leave recovery path throwing IllegalStateException.
  - Serialize exclusive LuaParser.kt claim vs other s010 recovery product tasks.
required_tests:
  - Deferred to TASK-043 / full jvmTest run 29225343255: `./gradlew.bat jvmTest --tests parser.recovery.LuaParserRecoveryIfElseifChainTddTest.recoversDuplicateElseWithoutInfiniteClauseLoopAndKeepsFollowingStatement`
  - Deferred to TASK-043 / windows-jvmtest slice s010: `./gradlew.bat jvmTest --tests parser.recovery.LuaParserRecoveryIfElseifChainTddTest.recoversDuplicateElseWithoutInfiniteClauseLoopAndKeepsFollowingStatement`
notes:
  - Windows slice s010 run 29199561336: this task owns the IfElseifChain duplicate-else red.
  - TASK-598 (review) claimed similar product lock earlier; still red under WINSLICE — new evidence-owned product task for s010.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-640.lock
  - locks/files/tasks__TASK-640.md.lock
  - locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__parser__LuaParser.kt.lock
related_commits: []
progress:
  - 2026-07-13T05:33:41Z FULLJVM product fix: absorbDuplicateIfClause under recovery so residual else after first else does not hard-throw as chunk-scope out-of-scope terminator. Local green recoversDuplicateElseWithoutInfiniteClauseLoopAndKeepsFollowingStatement.
  - 2026-07-13T materialize WINSLICE-29199561336: created ready product fix for recoversDuplicateElseWithoutInfiniteClauseLoopAndKeepsFollowingStatement.
  - 2026-07-12T16:16:31Z worker-WINSLICE-29199561336-TASK-640: claimed; implementing bounded parseIfStatement clause loop for duplicate else recovery (s010 red).
  - 2026-07-12T16:33:02Z worker-WINSLICE-29199561336-TASK-640: STOP leave ready — exclusive LuaParser.kt file lock held by live TASK-639 (worker-WINSLICE-29199561336-TASK-639, acquired 2026-07-12T16:25:08Z). parseIfStatement already has sawElse break; need product re-check after 639 releases lock (no-throw / residual duplicate else path). No product edit this turn.
  - 2026-07-13T materialize FULLJVM-29225343255: still red on full suite (parseRecoveringWithoutThrow); reuse TASK-640 ready product fix; evidence 29225343255.
  - 2026-07-13T05:13:45Z worker-FULLJVM-29225343255-TASK-640: STOP leave ready — exclusive LuaParser.kt file lock held by live TASK-612 (worker-FULLJVM-29225343255-TASK-612, lock acquired ~2026-07-13T13:13 local). No product edit this turn; re-check after 612 releases lock (duplicate-else no-throw / bounded clause loop).
