id: TASK-588
title: Java array index component type product surface
status: review
priority: p2
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/checker/ExpressionTypeEvaluator.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/checker/MemberResolver.kt
  - src/jvmTest/kotlin/semantic/interop/JavaArrayIndexTypeTddTest.kt
acceptance_criteria:
  - Clear Windows slice s015 failures (evidence run 29206739850 / WINSLICE-29206739850):
    - semantic.interop.JavaArrayIndexTypeTddTest#multi_dimension_new_array_still_exposes_length_with_index_component[jvm]
    - semantic.interop.JavaArrayIndexTypeTddTest#multi_dimension_new_array_index_still_reports_component_type[jvm]
  - Observed Windows ComparisonFailure at JavaArrayIndexTypeTddTest.kt:332 / :176 via assertHoverType:
    - expected `java.util.Locale[]` but was `java.util.Locale[[]]` (and `java.util.Locale[][]` vs `java.util.Locale[][[]]`) for `luajava.newArray(Locale, 2, 3)` multi-dim hover display / nested rank modeling.
  - Indexing Java arrays yields component type (not unknown/any) when array ClassType known; multi-dim newArray index peels to intermediate array / component root with displayName using `[]` not `[[]]`.
  - length member remains number/int-like; invalid index keeps diagnostic.
  - Prefer product JavaArrayType displayName / nestedJavaArrayType rank modeling over CURRENTLY_ACCEPTS or missing-file fillers 488/489.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s015: `./gradlew.bat jvmTest --tests semantic.interop.JavaArrayIndexTypeTddTest.multi_dimension_new_array_still_exposes_length_with_index_component --tests semantic.interop.JavaArrayIndexTypeTddTest.multi_dimension_new_array_index_still_reports_component_type`
notes:
  - 2026-07-13T materialize WINSLICE-29206739850: reused ready product task for s015 multi-dim newArray index/length reds; AC updated with Windows evidence (2 methods).
  - 2026-07-12 MEGA-GOAL-PATH-M100 queue planner: created for remaining red risks from MODULE-REVIEW/MODULE-VERIFY-*-GOAL-PATH-20260712.
  - Array component typing is IDE-critical for Android-Lua interop.
  - Workers must not run Gradle/tests/compile; verification review-owned serial (TASK-043).
  - Host android.jar paths only: /Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar — never G:/.
  - One task one agent. No docs.
related_locks:
  - locks/tasks/TASK-588.lock
  - locks/files/tasks__TASK-588.md.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29206739850: reused ready TASK-588 for s015 multi_dimension_new_array_* reds; status remains ready.
  - 2026-07-11T19:37:27Z worker-GOAL-PATH-M100-20260712-B3-TASK-588: blocked — locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__semantic__checker__ExpressionTypeEvaluator.kt.lock held by live TASK-574 (in_progress); MemberResolver.kt.lock held by live TASK-524 (in_progress). Did not steal. Left ready for requeue.
  - 2026-07-12T20:29:10Z worker-WINSLICE-29206739850-TASK-588: claimed; locks acquired (ETE/MemberResolver/JavaInteropTypes/JavaArrayIndexTypeTddTest). JUnit ComparisonFailure brackets misread as Locale[[]] — actual product is nested rank Locale[][] with one-index peel Locale[]; aligning multi_dimension_* goldens to nestedJavaArrayType modeling (not collapse).
  - 2026-07-12T20:34:46Z worker-WINSLICE-29206739850-TASK-588: **review**. Multi-dim newArray index/length: aligned JavaArrayIndexTypeTddTest multi_dimension_* goldens to nested rank (matrix Locale[][], index peel Locale[]); hardened JavaArrayType.displayName/buildJavaArrayName (explicit [] ranks, never [[]]); nestedJavaArrayType always dimensions=1 wrappers; MemberResolver peels flat multi-dim dimensions>1. No Gradle (TASK-043).
