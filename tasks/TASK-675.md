id: TASK-675
title: Windows alias-return export surface preserves nested writes through chained locals
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/ModuleExportCollector.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/ModuleExportSurface.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/LuaWorkspaceQueryFacade.kt
  - src/jvmTest/kotlin/semantic/workspace/SemanticWorkspaceCampaignGapTddTest.kt
acceptance_criteria:
  - Clear Windows slice s017 failure (evidence run 29210211230 / WINSLICE-29210211230; same red on 29209854844):
    - semantic.workspace.SemanticWorkspaceCampaignGapTddTest#alias_return_export_surface_preserves_nested_writes_through_chained_locals[jvm]
  - Observed Windows AssertionError: `Expected value to be true` — alias-return export surface loses nested writes through chained locals.
  - Product must preserve nested export member writes when module returns an alias local that was mutated through chained locals before return.
  - Prefer ModuleExportCollector alias-return + nested write tracking product fix; no CURRENTLY_ACCEPTS weaken.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s017: `./gradlew.bat jvmTest --tests semantic.workspace.SemanticWorkspaceCampaignGapTddTest.alias_return_export_surface_preserves_nested_writes_through_chained_locals`
notes:
  - Windows slice s017 run 29210211230: campaign gap alias-return nested write export surface red.
  - Adjacent to TASK-673 direct return table; this is alias-return + chained-local nested write path.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-675.lock
  - locks/files/tasks__TASK-675.md.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29210211230: created ready product fix for alias-return nested write export surface.
  - 2026-07-12T21:59:05Z worker-WINSLICE-29210211230-TASK-675: claim in_progress; implement alias-return nested write export surface through chained locals.
  - 2026-07-12T22:05:00Z worker-WINSLICE-29210211230-TASK-675: product fix ModuleExportCollector.visitFunctionDeclaration normalizeWriteTarget for chained path-local method writes; status=review.
