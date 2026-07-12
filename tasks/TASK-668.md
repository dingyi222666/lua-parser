id: TASK-668
title: Windows BuiltinOverlayLoader generic returns and import.luajava surface reuse
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/std/BuiltinOverlayLoader.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/std/Lua53BuiltinOverlaySources.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/std/AndroidLua53LuaJavaBuiltinOverlaySources.kt
  - src/commonTest/kotlin/semantic/workspace/BuiltinOverlayLoaderTest.kt
acceptance_criteria:
  - Clear Windows slice s017 failures (evidence run 29210211230 / WINSLICE-29210211230; same reds on 29209854844):
    - semantic.workspace.BuiltinOverlayLoaderTest#lua53_builtin_generic_returns_reach_completion_and_signature_help[jvm]
    - semantic.workspace.BuiltinOverlayLoaderTest#androlua_import_provider_reuses_documented_luajava_module_surface[jvm]
  - Observed Windows AssertionErrors:
    - lua53_builtin_generic_returns: completion/signature detail is `fun(): unknown` but must contain documented generic pairs shape `fun<K, V>(t: { [K]: V } | V[]): fun(tbl: { [K]: V }): K, V`.
    - androlua_import_provider_reuses: expected module name `[import]` but was `[luajava]` for import provider reusing documented luajava module surface.
  - Product overlay loader must preserve documented generic callable metadata for pairs/ipairs into completion + signature help, and expose import provider with moduleName `import` while reusing documented luajava member surface.
  - Prefer BuiltinOverlayLoader / overlay sources product fix; no CURRENTLY_ACCEPTS weaken of documented detail locks.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s017: `./gradlew.bat jvmTest --tests semantic.workspace.BuiltinOverlayLoaderTest.lua53_builtin_generic_returns_reach_completion_and_signature_help --tests semantic.workspace.BuiltinOverlayLoaderTest.androlua_import_provider_reuses_documented_luajava_module_surface`
notes:
  - Windows slice s017 run 29210211230: clustered 2 BuiltinOverlayLoader reds (generic returns + import.luajava surface).
  - Lineage: TASK-146 overlay wiring historically green; regression under s017.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-668.lock
  - locks/files/tasks__TASK-668.md.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29210211230: created ready product fix clustering 2 BuiltinOverlayLoader reds.
  - 2026-07-12T22:06:01Z worker-WINSLICE-29210211230-TASK-668: claim in_progress; fix pairs/ipairs generic callable metadata + import-moduleName luajava global surface.
  - 2026-07-12T22:10:05Z worker-WINSLICE-29210211230-TASK-668: hard-lock pairs/ipairs generic FunctionType on globals surface; brand free-id luajava ModuleType moduleName=import displayName=luajava; status=review. No Gradle.

