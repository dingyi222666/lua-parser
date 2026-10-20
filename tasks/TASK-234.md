id: TASK-234
title: ScopeGraph parent chain query corpus
status: done
priority: p2
owner: TASK-234-WORKER-WAVE22-20260711-165800
depends_on: []
scope:
  - src/jvmTest/kotlin/semantic/binder/ScopeGraphParentChainTddTest.kt
acceptance_criteria:
  - Scope parent chains for nested blocks terminate at global.
  - Shadowed names resolve innermost-first.
  - Test-only.
required_tests:
  - Deferred to TASK-043 serialized verification only: `JAVA_HOME=C:/Users/dingyi/.jdks/temurin-17.0.11 ./gradlew.bat jvmTest --tests semantic.binder.ScopeGraphParentChainTddTest`
notes:
  - 2026-07-11T17:55:00+08:00 REVIEW23-WAVE-20260711: **ACCEPTED → done**. Serial `./gradlew.lf jvmTest --tests semantic.binder.ScopeGraphParentChainTddTest` BUILD SUCCESSFUL.
  - REVIEW19 scope graph corpus.
  - Workers must not run Gradle, tests, compile, kotlinc, Java verification, build commands, or build-output cleanup; verification is review-owned and serial.
  - Conflict notes: Test-only.
  - Created by REVIEW19-WAVE-20260711-031147 at 2026-07-11T03:14:59+08:00.
related_locks:
  - locks/tasks/TASK-234.lock
  - locks/files/src__jvmTest__kotlin__semantic__binder__ScopeGraphParentChainTddTest.kt.lock
  - locks/files/tasks__TASK-234.md.lock
related_commits:
  - ccc82e29a5e7ee43dfe3e11546d18ced194d7a7a
progress_notes:
  - 2026-07-11T03:14:59+08:00 REVIEW19 created this ready task as a bounded non-overlapping corpus/docs/gap slice for the Android-Lua/LuaJava Lua 5.3 parser/semantic/JVM-LSP goal.
  - 2026-07-11T09:08:29Z TASK-234-WORKER-WAVE22-20260711-165800 acquired locks; status=in_progress; implementing parent-chain TDD corpus.
  - 2026-07-11T09:10:52Z TASK-234-WORKER-WAVE22-20260711-165800 wrote ScopeGraphParentChainTddTest corpus (parent chains → chunk root; shadowed names innermost-first). status→review.
  - 2026-07-11T09:17:04Z REVIEW24-WAVE-20260711: ACCEPTED. ScopeGraphParentChainTddTest 9/0/0. Log REVIEW24-TASK-234.log
