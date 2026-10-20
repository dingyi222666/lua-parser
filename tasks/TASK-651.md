id: TASK-651
title: Windows AndroidLuaLibraryStubs this/context getSystemService surface product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/std/AndroidLua53LuaJavaBuiltinOverlaySources.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/std/BuiltinOverlayLoader.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/binder/BuiltinSymbolSeeder.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/checker/ExpressionTypeEvaluator.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/checker/MemberResolver.kt
  - src/jvmTest/kotlin/semantic/androidlua/AndroidLuaLibraryStubsTddTest.kt
acceptance_criteria:
  - Clear Windows slice s012 failure (evidence run 29203984725 / WINSLICE-29203984725):
    - semantic.androidlua.AndroidLuaLibraryStubsTddTest#this_and_context_globals_share_android_lua_context_surface[jvm]
  - Observed Windows AssertionError: Expected getSystemService in main.lua to be METHOD. expected:<METHOD> but was:<null>
    for source `require "import"\nlocal path = this.getLuaPath\nlocal svc = context.getSystemService\nreturn path, svc`.
  - Product must model `this` as AndroidLuaContext-like (getLuaPath METHOD) and `context` as android.content.Context /
    AndroidLuaContext surface exposing getSystemService as METHOD (fun-shaped) after require "import" without
    depending on a present host android.jar for the stub/overlay path.
  - Prefer product overlay seeder / member resolution (AndroidLuaContext fields on context alias, or Context jar-independent
    stub members) over CURRENTLY_ACCEPTS weakening of getSystemService hard-lock.
  - Coordinate exclusive claims on BuiltinOverlayLoader / ExpressionTypeEvaluator / MemberResolver with other semantic tasks.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s012: `./gradlew.bat jvmTest --tests semantic.androidlua.AndroidLuaLibraryStubsTddTest.this_and_context_globals_share_android_lua_context_surface`
notes:
  - Windows slice s012 run 29203984725: 250 tests / 4 failures; this task owns the this/context getSystemService red only.
  - Overlay already declares AndroidLuaContext.getSystemService and `---@type android.content.Context context`; product
    likely resolves context via FQCN/jar path and drops overlay members when jar is absent — restore stub surface.
  - Distinct from TASK-652 host-jar soft-skip cluster; workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-651.lock
  - locks/files/tasks__TASK-651.md.lock
  - locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__semantic__workspace__std__AndroidLua53LuaJavaBuiltinOverlaySources.kt.lock
  - locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__semantic__workspace__std__BuiltinOverlayLoader.kt.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29203984725: created ready product fix for this_and_context_globals_share_android_lua_context_surface (getSystemService METHOD null).
  - 2026-07-12T18:45:20Z worker-WINSLICE-29203984725-TASK-651: claim in_progress; investigate context.getSystemService METHOD null (jar-independent overlay surface).
  - 2026-07-12T18:54:38Z worker-WINSLICE-29203984725-TASK-651: product fix for context.getSystemService METHOD; jar-independent android.content.Context ClassType in AndroLua _G overlays + hydrate empty FQCN shell fallback in JavaSemanticSupport; status=review.
