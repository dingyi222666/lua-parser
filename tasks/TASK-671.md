id: TASK-671
title: Windows LuaWorkspaceQueryFacade resolve_require only real builtin call sites
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/LuaWorkspaceQueryFacade.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/WorkspaceModuleResolver.kt
  - src/jvmTest/kotlin/semantic/workspace/LuaWorkspaceQueryFacadeTest.kt
acceptance_criteria:
  - Clear Windows slice s017 failure (evidence run 29213080707 / WINSLICE-29213080707; gate retains prior reds from 29212217687 after compileFailure blocked jvmTest):
    - semantic.workspace.LuaWorkspaceQueryFacadeTest#resolve_require_only_reports_modules_for_real_builtin_call_sites[jvm]
  - Observed Windows AssertionError: expected `null` but was `ModuleProvider(moduleName=dep, path=dep.lua, source=VIRTUAL_PATH)` — non-builtin / non-real require call sites still report modules.
  - Product resolveRequire must only report modules for real builtin `require` call sites; shadowed or non-call-site identifiers must stay null.
  - Prefer require-call recognition (builtin visibility + call form) product fix; no CURRENTLY_ACCEPTS weaken.
  - Shared hot file LuaWorkspaceQueryFacade — honor locks; leave ready if blocked. Must not reintroduce compileKotlinJvm return-type mismatch at resolveRequire helpers.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s017: `./gradlew.bat jvmTest --tests semantic.workspace.LuaWorkspaceQueryFacadeTest.resolve_require_only_reports_modules_for_real_builtin_call_sites`
notes:
  - Windows slice s017 run 29213080707: compileKotlinJvm failed before jvmTest (LuaWorkspaceQueryFacade.kt:1011); no TEST-*.xml. Gate retains prior red from 29212217687.
  - Adjacent to TASK-664 require recognition hardening for cross-module defs; this AC is resolve_require null for non-real sites.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-671.lock
  - locks/files/tasks__TASK-671.md.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29210211230: created ready product fix for resolve_require real-builtin-call-site gating.
  - 2026-07-13T materialize WINSLICE-29212217687: still red; reuse ready product task.
  - 2026-07-12T22:53:37Z worker-WINSLICE-29212217687-TASK-671: claim in_progress; gate resolveRequire(path,moduleName) to real builtin require call sites only.
  - 2026-07-12T22:56:00Z worker-WINSLICE-29212217687-TASK-671: product fix in LuaWorkspaceQueryFacade — gate name-based resolveRequire via hasBuiltinRequireCallSite (AST walk + builtinRequireModuleName); position path reuses same recognition; status=review.
  - 2026-07-13T materialize WINSLICE-29213080707: compileFailure blocked tests; reset review→ready for retained red + evidence 29213080707.
  - 2026-07-12T23:25:13Z worker-WINSLICE-29213080707-TASK-671: claim in_progress; fix resolveRequire real-builtin gating and compileKotlinJvm return-type mismatch near resolve helpers.
  - 2026-07-12T23:35:00Z worker-WINSLICE-29213080707-TASK-671: product fix — hasBuiltinRequireCallSite uses explicit AST walk (not ASTVisitor) + documentFacts fast-reject; resolveRequire(path,moduleName) still gates provider; compile-safe resolveRequireByWalkingExpressionBases already present; status=review.
