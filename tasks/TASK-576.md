id: TASK-576
title: bindClass Map.Entry static method campaign gap product fix
status: review
priority: p1
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/checker/MemberResolver.kt
  - src/jvmTest/kotlin/semantic/interop/JavaAndroidInteropCampaignGapTddTest.kt
  - src/jvmTest/kotlin/semantic/interop/LuaJavaBindClassInnerClassTddTest.kt
acceptance_criteria:
  - Clear Windows slice s015 failure (evidence run 29206739850 / WINSLICE-29206739850):
    - semantic.interop.JavaAndroidInteropCampaignGapTddTest#bind_class_inner_map_entry_static_method_is_callable[jvm]
  - Observed Windows AssertionError at JavaAndroidInteropCampaignGapTddTest.kt:462 via assertCallable:
    - Expected callable type, got OverloadedFunctionType display `fun<K, V>(arg1: java.util.Comparator<unknown>): java.util.Comparator<java.util.Map.Entry<K, V>> & fun<K: java.lang.Comparable<unknown>, V>(): java.util.Comparator<java.util.Map.Entry<K, V>>` for static member on nested Map$Entry after bindClass.
  - Product must expose nested Map.Entry static method as assertCallable / FunctionType-compatible callable (or assertCallable accepts overload set without inventing statics). Prefer MemberResolver/JavaSemanticSupport hydrate path over CURRENTLY_ACCEPTS.
  - bind_class_inner_map_entry_static_method_is_callable hard-locks static member on nested Map$Entry after bindClass.
  - Pairs with TASK-522/526 but focuses campaign-gap fixture path; serialize MemberResolver claims.
  - Host jar/JDK classes only; no invented statics.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s015: `./gradlew.bat jvmTest --tests semantic.interop.JavaAndroidInteropCampaignGapTddTest.bind_class_inner_map_entry_static_method_is_callable`
notes:
  - 2026-07-13T materialize WINSLICE-29206739850: reused ready product task for s015 red bind_class_inner_map_entry_static_method_is_callable; AC updated with Windows evidence.
  - 2026-07-12 MEGA-GOAL-PATH-M100 queue planner: created for remaining red risks from MODULE-REVIEW/MODULE-VERIFY-*-GOAL-PATH-20260712.
  - MODULE-VERIFY: bind_class_inner_map_entry_static_method_is_callable FAILED.
  - Workers must not run Gradle/tests/compile; verification review-owned serial (TASK-043).
  - Host android.jar paths only: /Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar — never G:/.
  - One task one agent. No docs.
related_locks:
  - locks/tasks/TASK-576.lock
  - locks/files/tasks__TASK-576.md.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29206739850: reused ready TASK-576 for s015 Map.Entry static callable red; status remains ready.
  - 2026-07-11T19:06:47Z worker-GOAL-PATH-M100-20260712-B2-TASK-576 blocked: locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__semantic__checker__MemberResolver.kt.lock held by live TASK-524 (owner=worker-GOAL-PATH-M100-20260712-B1-TASK-524, status=in_progress). Did not steal. Leave ready for requeue after MemberResolver free.
  - 2026-07-12T20:22:08Z worker-WINSLICE-29206739850-TASK-576: claimed; fixing assertCallable to accept generic OverloadedFunctionType fun<...>(...) for Map$Entry.comparingByKey campaign gap.
  - 2026-07-12T20:22:24Z worker-WINSLICE-29206739850-TASK-576: review ready; assertCallable/looksCallable accepts generic OverloadedFunctionType fun<...>(...) for Map.Entry.comparingByKey (Windows s015 evidence). Product already hydrates static overload set; no MemberResolver change; no invented statics.
