id: TASK-668
title: Windows BuiltinOverlayLoader AndroLua luajava helper documented details (no fake self)
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/std/BuiltinOverlayLoader.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/std/AndroidLua53LuaJavaBuiltinOverlaySources.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/binder/BuiltinSymbolSeeder.kt
  - src/jvmTest/kotlin/semantic/workspace/BuiltinOverlayLoaderTddTest.kt
acceptance_criteria:
  - Clear Windows slice s017 failure (evidence run 29219299810 / WINSLICE-29219299810):
    - semantic.workspace.BuiltinOverlayLoaderTddTest#androlua_luajava_helper_completion_and_hover_use_documented_details[jvm]
  - Observed Windows ComparisonFailure: expected `fun([]className: string): ...` but was `fun([self: luajava, ]className: string): ...` — helper callable detail injects a fake `self: luajava` parameter.
  - Product AndroLua luajava helper completion/hover details must match documented free-function shape (no synthetic self receiver param in detail).
  - Prefer BuiltinOverlayLoader / overlay sources / seeder product fix; no CURRENTLY_ACCEPTS weaken of documented detail locks.
  - Shared hot file BuiltinOverlayLoader — honor locks; leave ready if blocked.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s017: `./gradlew.bat jvmTest --tests semantic.workspace.BuiltinOverlayLoaderTddTest.androlua_luajava_helper_completion_and_hover_use_documented_details`
notes:
  - Windows slice s017 run 29219299810: 574 tests, 18 failures. This is the sole BuiltinOverlayLoader red in the current failure set.
  - Prior AC targeted lua53_builtin_generic_returns (not in 29219299810 reds); retargeted to live androlua_luajava_helper red.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-668.lock
  - locks/files/tasks__TASK-668.md.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29210211230: created ready product fix clustering 2 BuiltinOverlayLoader reds.
  - 2026-07-12T22:06:01Z worker-WINSLICE-29210211230-TASK-668: claim in_progress; fix pairs/ipairs generic callable metadata + import-moduleName luajava global surface.
  - 2026-07-12T22:10:05Z worker-WINSLICE-29210211230-TASK-668: hard-lock pairs/ipairs generic FunctionType on globals surface; brand free-id luajava ModuleType moduleName=import displayName=luajava; status=review. No Gradle.
  - 2026-07-13T materialize WINSLICE-29212217687: still red on lua53_builtin_generic_returns; reset review→ready; reuse for product re-fix.
  - 2026-07-13T materialize WINSLICE-29213080707: compileFailure blocked tests; retain ready product task for prior red + evidence 29213080707.
  - 2026-07-12T23:25:24Z worker-WINSLICE-29213080707-TASK-668: claim in_progress; re-fix pairs/ipairs generic metadata into completion+signature help.
  - 2026-07-12T23:29:39Z worker-WINSLICE-29213080707-TASK-668: preserve BuiltinSymbolSeeder FunctionType seeds in TypeResolver.resolveFunctionDeclaration (pairs/ipairs no longer collapse to fun(): unknown); status=review. No Gradle.
  - 2026-07-13T materialize WINSLICE-29219299810: prior generic-returns AC not in live reds; retarget ready product task to androlua_luajava_helper_completion_and_hover_use_documented_details (fake self: luajava in detail).
  - 2026-07-13T02:26:26Z worker-WINSLICE-29219299810-TASK-668: claim in_progress; remove fake self: luajava from AndroLua luajava helper callable details.
  - 2026-07-13T02:43:40Z worker-WINSLICE-29219299810-TASK-668: MemberResolver binds synthetic self only for colon preferMethod=true; AndroLua luajava.* dot helpers keep free-function detail (no self: luajava). status=review. No Gradle.
