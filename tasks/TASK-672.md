id: TASK-672
title: Windows nested require-alias exports flow through workspace definition and references
status: ready
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/LuaWorkspaceQueryFacade.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/model/ReferenceQueries.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/ModuleExportCollector.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/WorkspaceModuleResolver.kt
  - src/jvmTest/kotlin/semantic/workspace/LuaWorkspaceQueryFacadeTest.kt
acceptance_criteria:
  - Clear Windows slice s017 failure (evidence run 29213080707 / WINSLICE-29213080707; compileFailure blocked jvmTest; gate retains prior red from 29212217687):
    - semantic.workspace.LuaWorkspaceQueryFacadeTest#nested_require_alias_exports_flow_through_workspace_definition_and_references[jvm]
  - Observed Windows AssertionError: expected definition paths `[dep.lua]` but was `[]` for nested require-alias export member definition/reference flow.
  - Product must flow nested require-alias exports through workspace gotoDefinition and findReferences so provider module paths resolve (non-empty dep.lua).
  - P0 compile unblock for WINSLICE-29213080707: fix `LuaWorkspaceQueryFacade.kt:1011` return type mismatch — expected `WorkspaceModuleResolver.ResolvedRequire?`, actual `Any?` in `resolvedRequireForExportPathRootFromExpression` (IndexExpression while-loop when-branch must return `ResolvedRequire?`, not Unit/Any?). compileKotlinJvm must succeed so jvmTest can run.
  - Prefer facade + export surface + ReferenceQueries product fix; no CURRENTLY_ACCEPTS weaken.
  - Shared hot files LuaWorkspaceQueryFacade + ModuleExportCollector — honor locks; leave ready if blocked.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s017: `./gradlew.bat jvmTest --tests semantic.workspace.LuaWorkspaceQueryFacadeTest.nested_require_alias_exports_flow_through_workspace_definition_and_references`
notes:
  - Windows slice s017 run 29213080707: CI compileKotlinJvm failed at LuaWorkspaceQueryFacade.kt:1011 before jvmTest; no TEST-*.xml. Nested-require product path from prior worker likely introduced the return-type mismatch.
  - Distinct from TASK-664 missing-export empty policy; this requires non-empty nested alias export resolution plus compile green.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-672.lock
  - locks/files/tasks__TASK-672.md.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29210211230: created ready product fix for nested require-alias export def/ref flow.
  - 2026-07-12T21:58:39Z worker-WINSLICE-29210211230-TASK-672: STOP leave ready — exclusive locks held by live other tasks: LuaWorkspaceQueryFacade.kt + WorkspaceModuleResolver.kt (TASK-666 in_progress); ReferenceQueries.kt (TASK-664 review lock). Nested require-alias def/ref needs facade nested export-path resolution through alias chains; no product edit.
  - 2026-07-13T materialize WINSLICE-29212217687: still red; reuse ready product task.
  - 2026-07-12T22:53:44Z worker-WINSLICE-29212217687-TASK-672: claim in_progress; implementing nested require-alias export def/ref flow through facade/export surface/ReferenceQueries.
  - 2026-07-12T23:05:20Z worker-WINSLICE-29212217687-TASK-672: claim locks; implement nested require-alias export path through facade receiver chain.
  - 2026-07-12T23:12:00Z worker-WINSLICE-29212217687-TASK-672: product fix LuaWorkspaceQueryFacade nested require-alias export path/receiver chain for gotoDefinition/references/hover; status=review.
  - 2026-07-13T materialize WINSLICE-29213080707: compileFailure at LuaWorkspaceQueryFacade.kt:1011 (ResolvedRequire? vs Any?); reset review→ready; fold compile unblock + retained nested-require red.
- 2026-07-12T23:26:10Z worker-WINSLICE-29213080707-TASK-672: STOP leave ready — exclusive locks held by live other tasks: LuaWorkspaceQueryFacade.kt + WorkspaceModuleResolver.kt (TASK-671 in_progress); ModuleExportCollector.kt (TASK-673 in_progress). P0 compile unblock at resolvedRequireForExportPathRootFromExpression and nested require-alias def/ref need facade; no product edit.
