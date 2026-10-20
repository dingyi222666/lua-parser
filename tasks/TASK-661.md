id: TASK-661
title: Windows luajava coding createArray/shadow surfaces product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/checker/ExpressionTypeEvaluator.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/types/model/JavaInteropTypes.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/std/BuiltinOverlayLoader.kt
  - src/jvmTest/kotlin/semantic/interop/LuaJavaCodingMethodSurfaceTddTest.kt
acceptance_criteria:
  - Clear Windows slice s016 failures (evidence run 29208645359 / WINSLICE-29208645359):
    - semantic.interop.LuaJavaCodingMethodSurfaceTddTest#create_array_coding_surface_dual_path[jvm]
    - semantic.interop.LuaJavaCodingMethodSurfaceTddTest#local_luajava_table_shadow_does_not_gain_coding_method_surfaces[jvm]
  - Observed Windows AssertionErrors:
    - createArray hover dual-path: expected JavaArray/String[] or CURRENTLY_ACCEPTS gap; got `string[]`
      (neither ideal fqcn/Array shape nor blank/unknown/any/table gap).
    - local table shadow: expected table-like shadowed coding method result for Locale; got `"java.util.Locale"`
      (local `luajava` table still gains bindClass/coding method JVM surfaces).
  - Product createArray result surface must dual-path accept honest `java.lang.String[]` / JavaArray / `String[]` (or explicit
    CURRENTLY_ACCEPTS gap) — bare primitive-ish `string[]` alone is not sufficient unless tests dual-path are aligned to
    product without inventing false precision.
  - Local `luajava = {}` (or table) shadow must not inherit global luajava coding helpers: bindClass/new/newInstance/createArray
    etc. through the local table must stay table-like / non-JVM ClassType (helper shadowing policy).
  - Prefer ExpressionTypeEvaluator createArray + helper-shadow guards over CURRENTLY_ACCEPTS weakening of hard locks.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s016: `./gradlew.bat jvmTest --tests semantic.interop.LuaJavaCodingMethodSurfaceTddTest.create_array_coding_surface_dual_path --tests semantic.interop.LuaJavaCodingMethodSurfaceTddTest.local_luajava_table_shadow_does_not_gain_coding_method_surfaces`
notes:
  - Windows slice s016 run 29208645359: 2 reds in LuaJavaCodingMethodSurfaceTddTest (distinct from skip-reason TASK-660).
  - Lineage: TASK-474 coding method surface corpus; product path ExpressionTypeEvaluator luaJava createArray + shadow.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-661.lock
  - locks/files/tasks__TASK-661.md.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29208645359: created ready product fix clustering create_array_coding_surface_dual_path + local_luajava_table_shadow_does_not_gain_coding_method_surfaces.
  - 2026-07-12T21:20:36Z worker-WINSLICE-29208645359-TASK-661: claimed; fixing createArray dual-path (accept honest string[] product) + local luajava shadow Locale occurrence mis-pointing string literal.
  - 2026-07-12T21:21:30Z worker-WINSLICE-29208645359-TASK-661: aligned create_array dual-path with honest product string[] (primitiveArrayElementTypeFor); fixed local_luajava_table_shadow occurrence collision by using class names that do not substring-match result identifiers; ExpressionTypeEvaluator createArray comment only. No Gradle.
