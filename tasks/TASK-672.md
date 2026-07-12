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
  - Clear Windows slice s017 failure (evidence run 29210211230 / WINSLICE-29210211230; same red on 29209854844):
    - semantic.workspace.LuaWorkspaceQueryFacadeTest#nested_require_alias_exports_flow_through_workspace_definition_and_references[jvm]
  - Observed Windows AssertionError: expected definition paths `[dep.lua]` but was `[]` for nested require-alias export member definition/reference flow.
  - Product must flow nested require-alias exports through workspace gotoDefinition and findReferences so provider module paths resolve (non-empty dep.lua).
  - Prefer facade + export surface + ReferenceQueries product fix; no CURRENTLY_ACCEPTS weaken.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s017: `./gradlew.bat jvmTest --tests semantic.workspace.LuaWorkspaceQueryFacadeTest.nested_require_alias_exports_flow_through_workspace_definition_and_references`
notes:
  - Windows slice s017 run 29210211230: nested require-alias export def/ref empty red.
  - Distinct from TASK-664 missing-export empty policy; this requires non-empty nested alias export resolution.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-672.lock
  - locks/files/tasks__TASK-672.md.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29210211230: created ready product fix for nested require-alias export def/ref flow.
  - 2026-07-12T21:58:39Z worker-WINSLICE-29210211230-TASK-672: STOP leave ready — exclusive locks held by live other tasks: LuaWorkspaceQueryFacade.kt + WorkspaceModuleResolver.kt (TASK-666 in_progress); ReferenceQueries.kt (TASK-664 review lock). Nested require-alias def/ref needs facade nested export-path resolution through alias chains; no product edit.
