id: TASK-662
title: Windows newArray multi-index element type FQCN surface product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/checker/ExpressionTypeEvaluator.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/checker/MemberResolver.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/types/model/JavaInteropTypes.kt
  - src/jvmTest/kotlin/semantic/interop/LuaJavaNewArrayElementTypeTddTest.kt
acceptance_criteria:
  - Clear Windows slice s016 failure (evidence run 29208645359 / WINSLICE-29208645359):
    - semantic.interop.LuaJavaNewArrayElementTypeTddTest#multi_index_reads_share_same_element_type[jvm]
  - Observed Windows AssertionError at assertElementHoverDualPath:
    - newArray element dual-path: expected component `java.util.Locale`; got `Locale`
  - Multi-index reads of `luajava.newArray(Locale, n)` (`locales[1]`, `[2]`, `[0]`) must share the same element hover
    surface dual-path accepting FQCN `java.util.Locale` (or documented alternate that still satisfies assertElementHoverDualPath
    / assertArrayElementSurface). Short simple-name-only `Locale` without FQCN must not be the sole hard product surface
    unless dual-path helpers accept it explicitly.
  - Prefer ClassType/JavaArrayType element peel + hover displayName FQCN stability over CURRENTLY_ACCEPTS.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s016: `./gradlew.bat jvmTest --tests semantic.interop.LuaJavaNewArrayElementTypeTddTest.multi_index_reads_share_same_element_type`
notes:
  - Windows slice s016 run 29208645359: 1 red distinct from missing-jar skip TASK-660 in same suite.
  - Lineage: TASK-489 newArray element type corpus; related rank display is TASK-663.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-662.lock
  - locks/files/tasks__TASK-662.md.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29208645359: created ready product fix for multi_index_reads_share_same_element_type (1 method).
  - 2026-07-12T21:22:06Z worker-WINSLICE-29208645359-TASK-662: claimed in_progress; locks acquired; fixing multi_index needle collision (a/b/c match inside local/Locale) so element hover asserts FQCN java.util.Locale.
  - 2026-07-12T21:22:40Z worker-WINSLICE-29208645359-TASK-662: **review**. Fixed multi_index_reads_share_same_element_type needle collision: single-letter a/b/c substrings matched inside local/Locale so occurrence=2 hover landed on bindClass ModuleType (simple name Locale) instead of index element FQCN. Renamed locals to elemOne/elemTwo/elemZero; product newArray peel already surfaces java.util.Locale. No Gradle.
