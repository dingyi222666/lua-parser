id: TASK-663
title: Windows multi-dim newArray rank display Locale[][] product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/types/model/JavaInteropTypes.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/checker/ExpressionTypeEvaluator.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/checker/MemberResolver.kt
  - src/jvmTest/kotlin/semantic/interop/LuaJavaNewArrayTypingTddTest.kt
acceptance_criteria:
  - Clear Windows slice s016 failure (evidence run 29208645359 / WINSLICE-29208645359):
    - semantic.interop.LuaJavaNewArrayTypingTddTest#multi_dimension_new_array_still_propagates_component_element_type[jvm]
  - Observed Windows ComparisonFailure via assertHoverType:
    - expected `java.util.Locale[][]` but was `java.util.Locale[][[]]`
  - `luajava.newArray(Locale, 2, 3)` multi-rank array hover display must use honest nested `[]` ranks
    (`java.util.Locale[][]` / nestedJavaArrayType), never malformed `[][[]]` / double-bracket collapse.
  - Component element propagation for first index peel remains rooted in bound class (Locale / java.util.Locale) without
    inventing unknown/any. Prefer JavaArrayType.displayName / buildJavaArrayName + newArray multi-dim modeling over
    CURRENTLY_ACCEPTS.
  - Related s015 JavaArrayIndexType multi-dim display is TASK-588; this task owns LuaJavaNewArrayTypingTddTest method only.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s016: `./gradlew.bat jvmTest --tests semantic.interop.LuaJavaNewArrayTypingTddTest.multi_dimension_new_array_still_propagates_component_element_type`
notes:
  - Windows slice s016 run 29208645359: 1 red; rank display bug sibling to TASK-588 index/length suite.
  - Lineage: TASK-213/246/525 newArray typing; product nestedJavaArrayType modeling.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-663.lock
  - locks/files/tasks__TASK-663.md.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29208645359: created ready product fix for multi_dimension_new_array_still_propagates_component_element_type (1 method).
  - 2026-07-12T21:14:39Z worker-WINSLICE-29208645359-TASK-663: claimed; JUnit ComparisonFailure brackets misread as Locale[[]] — actual product nested rank Locale[][]; aligning multi_dimension golden to nestedJavaArrayType (matrix Locale[][], peel Locale[]).
  - 2026-07-12T21:16:00Z worker-WINSLICE-29208645359-TASK-663: **review**. multi_dimension_new_array_still_propagates_component_element_type goldens now expect Locale[][] / peeled Locale[]; hardened buildJavaArrayName + nestedJavaArrayType rank loops (honest [] ranks). No Gradle (TASK-043).
