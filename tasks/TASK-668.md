id: TASK-668
title: Windows BuiltinOverlayLoader generic returns and import.luajava surface reuse
status: ready
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/std/BuiltinOverlayLoader.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/std/Lua53BuiltinOverlaySources.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/std/AndroidLua53LuaJavaBuiltinOverlaySources.kt
  - src/commonTest/kotlin/semantic/workspace/BuiltinOverlayLoaderTest.kt
acceptance_criteria:
  - Clear Windows slice s017 failure (evidence run 29212217687 / WINSLICE-29212217687; still red after 29210211230 review attempt):
    - semantic.workspace.BuiltinOverlayLoaderTest#lua53_builtin_generic_returns_reach_completion_and_signature_help[jvm]
  - Product overlay loader must preserve documented generic callable metadata for pairs/ipairs into completion + signature help (detail must contain generic pairs shape, not `fun(): unknown`).
  - Prefer BuiltinOverlayLoader / overlay sources product fix; no CURRENTLY_ACCEPTS weaken of documented detail locks.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s017: `./gradlew.bat jvmTest --tests semantic.workspace.BuiltinOverlayLoaderTest.lua53_builtin_generic_returns_reach_completion_and_signature_help`
notes:
  - Windows slice s017 run 29212217687: still red on lua53_builtin_generic_returns (6 total s017 failures).
  - Prior AC also covered androlua_import_provider_reuses (not in 29212217687 reds); keep product import surface if touched but AC gate is the still-red generic returns test.
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

