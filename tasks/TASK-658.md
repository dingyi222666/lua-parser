id: TASK-658
title: Windows Java table-to-container reject overprecise conversion product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/types/resolve/TypeRelations.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/checker/JavaSemanticSupport.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/checker/CallChecker.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/checker/ExpressionTypeEvaluator.kt
  - src/jvmTest/kotlin/semantic/interop/JavaChainedCallTddTest.kt
acceptance_criteria:
  - Clear Windows slice s015 failures (evidence run 29206739850 / WINSLICE-29206739850):
    - semantic.interop.JavaChainedCallTddTest#string_array_parameter_rejects_mixed_or_incompatible_lua_table_literal[jvm]
    - semantic.interop.JavaChainedCallTddTest#raw_object_list_parameter_does_not_claim_precise_table_conversion[jvm]
  - Observed Windows ComparisonFailure at JavaChainedCallTddTest.kt:258 / :272 via assertHoverTypeAllowingUnknown:
    - expected hover type `unknown` but was `number` for incompatible named-key table → String[] and raw List<*> table conversions that must not claim precise call success/return.
  - Product must refuse precise Lua table → Java array/List conversion when the table shape is mixed/named-key (not integer array shape) or the Java element type is raw/wildcard (`List<*>` / Object): call result hover stays unknown (or diagnostic path) rather than inventing the method return type `number`.
  - Keep compatible string array/list/map acceptance methods green (string_array_parameter_accepts_*, string_list_*, string_map_*). Prefer TypeRelations/JavaSemanticSupport conservative assignability over CURRENTLY_ACCEPTS.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s015: `./gradlew.bat jvmTest --tests semantic.interop.JavaChainedCallTddTest.string_array_parameter_rejects_mixed_or_incompatible_lua_table_literal --tests semantic.interop.JavaChainedCallTddTest.raw_object_list_parameter_does_not_claim_precise_table_conversion`
notes:
  - Windows slice s015 run 29206739850: 60 tests in class, 3 failures; this task owns the 2 table-conversion reject reds (listener setter is TASK-659).
  - Distinct from done TASK-153 (acceptance historically green); regression under must-green-to-advance s015.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-658.lock
  - locks/files/tasks__TASK-658.md.lock
  - locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__semantic__types__resolve__TypeRelations.kt.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29206739850: created ready product fix for mixed/raw table→Java container overprecise conversion (2 methods).
  - 2026-07-12T20:21:59Z worker-WINSLICE-29206739850-TASK-658: claimed; investigating table→Java container overprecise conversion.
  - 2026-07-12T20:30:00Z worker-WINSLICE-29206739850-TASK-658: product fix for table→Java container overprecise conversion. ExpressionTypeEvaluator javaChainedCallReturnType no longer pure-arity recovers when args are table-shaped (mixed/named→String[] and List<*> stay unknown). CallChecker shares isJavaContainerAssignableFrom; TypeRelations.isKnownType rejects java.lang.Object element types. Compatible string array/list/map paths unchanged. status→review; locks released. No Gradle.
