id: TASK-659
title: Windows non-listener interface setter callback remains unknown product fix
status: ready
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/checker/JavaSemanticSupport.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/checker/ExpressionTypeEvaluator.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/checker/CallChecker.kt
  - src/jvmTest/kotlin/semantic/interop/JavaChainedCallTddTest.kt
acceptance_criteria:
  - Clear Windows slice s015 failure (evidence run 29206739850 / WINSLICE-29206739850):
    - semantic.interop.JavaChainedCallTddTest#non_listener_interface_setter_callback_remains_unknown[jvm]
  - Observed Windows ComparisonFailure at JavaChainedCallTddTest.kt:199 via assertHoverTypeAllowingUnknown:
    - expected hover type `unknown` but was `nil` for `holder.setAction(function() return nil end)` where Action is a non-Listener/non-Callback interface.
  - Product must not treat non-Listener/non-Callback interface setter callbacks as successful void/nil-typed assignability: callback argument / call result surface remains unknown (no invented nil return precision) while real *Listener/*Callback setters keep working (setValueListener fixtures stay green).
  - Prefer JavaSemanticSupport listener/callback policy + call typing over CURRENTLY_ACCEPTS or weakening hard-locks.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s015: `./gradlew.bat jvmTest --tests semantic.interop.JavaChainedCallTddTest.non_listener_interface_setter_callback_remains_unknown`
notes:
  - Windows slice s015 run 29206739850: third JavaChainedCallTddTest red; clustered separately from table-conversion TASK-658.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-659.lock
  - locks/files/tasks__TASK-659.md.lock
  - locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__semantic__checker__JavaSemanticSupport.kt.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29206739850: created ready product fix for non_listener_interface_setter_callback_remains_unknown (1 method).
  - 2026-07-12T20:26:09Z worker-WINSLICE-29206739850-TASK-659: blocked on live locks from TASK-658 (JavaSemanticSupport/CallChecker/JavaChainedCallTddTest); left ready, no claim/edits.
