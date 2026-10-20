id: TASK-674
title: Windows AndroLua socket.url overlay dotted module resolution and parse method
status: in_progress
priority: p0
owner: worker-WINSLICE-29213080707-TASK-674
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/std/BuiltinOverlayLoader.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/WorkspaceModuleResolver.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/LuaWorkspaceQueryFacade.kt
  - src/jvmTest/kotlin/semantic/workspace/SemanticWorkspaceCampaignGapTddTest.kt
acceptance_criteria:
  - Clear Windows slice s017 failure (evidence run 29213080707 / WINSLICE-29213080707; gate retains prior reds from 29212217687 after compileFailure blocked jvmTest):
    - semantic.workspace.SemanticWorkspaceCampaignGapTddTest#androlua_socket_url_overlay_keeps_dotted_module_resolution_and_parse_method[jvm]
  - Observed Windows AssertionError: expected definition paths `[__lua_std__/androlua5.3/socket.url.lua]` but was `[main.lua]` for dotted `socket.url` require/overlay resolution.
  - Product must resolve AndroLua dotted module `socket.url` to the overlay provider path and keep documented `parse` method surface.
  - Prefer overlay mount + WorkspaceModuleResolver dotted-name product fix; no CURRENTLY_ACCEPTS weaken.
  - Shared hot files BuiltinOverlayLoader + LuaWorkspaceQueryFacade — honor locks; leave ready if blocked.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s017: `./gradlew.bat jvmTest --tests semantic.workspace.SemanticWorkspaceCampaignGapTddTest.androlua_socket_url_overlay_keeps_dotted_module_resolution_and_parse_method`
notes:
  - Windows slice s017 run 29213080707: compileKotlinJvm failed before jvmTest; no TEST-*.xml. Gate retains prior red from 29212217687.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-674.lock
  - locks/files/tasks__TASK-674.md.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29210211230: created ready product fix for AndroLua socket.url overlay dotted module resolution.
  - 2026-07-12T21:59:00Z worker-WINSLICE-29210211230-TASK-674: blocked — product paths locked by live TASK-666/668/670; left ready/unassigned.
  - 2026-07-13T materialize WINSLICE-29212217687: still red; reuse ready product task.
  - 2026-07-12T22:53:36Z worker-WINSLICE-29212217687-TASK-674: claimed in_progress; implementing socket.url overlay dotted module resolution.
  - 2026-07-12T22:58:49Z worker-WINSLICE-29212217687-TASK-674: product fix for socket.url dotted overlay resolution + require-backed member local goto; status=review.
  - 2026-07-13T materialize WINSLICE-29213080707: compileFailure blocked tests; reset review→ready for retained red + evidence 29213080707.
  - 2026-07-12T23:25:19Z worker-WINSLICE-29213080707-TASK-674: claimed in_progress; implementing socket.url overlay dotted module resolution and parse method surface.
