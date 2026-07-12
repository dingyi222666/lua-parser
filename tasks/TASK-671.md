id: TASK-671
title: Windows LuaWorkspaceQueryFacade resolve_require only real builtin call sites
status: ready
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/LuaWorkspaceQueryFacade.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/WorkspaceModuleResolver.kt
  - src/jvmTest/kotlin/semantic/workspace/LuaWorkspaceQueryFacadeTest.kt
acceptance_criteria:
  - Clear Windows slice s017 failure (evidence run 29210211230 / WINSLICE-29210211230; same red on 29209854844):
    - semantic.workspace.LuaWorkspaceQueryFacadeTest#resolve_require_only_reports_modules_for_real_builtin_call_sites[jvm]
  - Observed Windows AssertionError: expected `null` but was `ModuleProvider(moduleName=dep, path=dep.lua, source=VIRTUAL_PATH)` — non-builtin / non-real require call sites still report modules.
  - Product resolveRequire must only report modules for real builtin `require` call sites; shadowed or non-call-site identifiers must stay null.
  - Prefer require-call recognition (builtin visibility + call form) product fix; no CURRENTLY_ACCEPTS weaken.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s017: `./gradlew.bat jvmTest --tests semantic.workspace.LuaWorkspaceQueryFacadeTest.resolve_require_only_reports_modules_for_real_builtin_call_sites`
notes:
  - Windows slice s017 run 29210211230: resolve_require over-reporting non-builtin call sites.
  - Adjacent to TASK-664 require recognition hardening for cross-module defs; this AC is resolve_require null for non-real sites.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-671.lock
  - locks/files/tasks__TASK-671.md.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29210211230: created ready product fix for resolve_require real-builtin-call-site gating.
