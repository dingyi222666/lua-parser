id: TASK-673
title: Windows ModuleExportCollector direct return table literal collection
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/ModuleExportCollector.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/ModuleExportSurface.kt
  - src/commonTest/kotlin/semantic/workspace/ModuleExportCollectorTest.kt
acceptance_criteria:
  - Clear Windows slice s017 failure (evidence run 29213080707 / WINSLICE-29213080707; gate retains prior reds from 29212217687 after compileFailure blocked jvmTest):
    - semantic.workspace.ModuleExportCollectorTest#direct_return_table_literal_is_collected[jvm]
  - Observed Windows AssertionError: expected export member `run` but was `null` for direct `return { run = ... }` table literal collection.
  - Product ModuleExportCollector must collect named fields from direct return table literals into the export surface (member `run` present).
  - Prefer ModuleExportCollector product fix; no CURRENTLY_ACCEPTS weaken.
  - Shared hot file ModuleExportCollector — honor locks; leave ready if blocked.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s017: `./gradlew.bat jvmTest --tests semantic.workspace.ModuleExportCollectorTest.direct_return_table_literal_is_collected`
notes:
  - Windows slice s017 run 29213080707: compileKotlinJvm failed before jvmTest; no TEST-*.xml. Gate retains prior red from 29212217687.
  - Lineage: TASK-087 nested ranges (done); this is top-level direct-return table field collection regression.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-673.lock
  - locks/files/tasks__TASK-673.md.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29210211230: created ready product fix for ModuleExportCollector direct return table literal.
  - 2026-07-12T22:00:10Z worker-WINSLICE-29210211230-TASK-673: STOP leave ready — ModuleExportCollector.kt and ModuleExportSurface.kt locked by live TASK-675 (worker-WINSLICE-29210211230-TASK-675). No product edit.
  - 2026-07-13T materialize WINSLICE-29212217687: still red; reuse ready product task.
  - 2026-07-12T22:54:05Z worker-WINSLICE-29212217687-TASK-673: STOP leave ready — ModuleExportCollector.kt locked by live TASK-672 (worker-WINSLICE-29212217687-TASK-672). No product edit.
  - 2026-07-13T materialize WINSLICE-29213080707: compileFailure blocked tests; retain ready product task for prior red + evidence 29213080707.
  - 2026-07-12T23:27:32Z worker-WINSLICE-29213080707-TASK-673: product fix ModuleExportCollector collectMembersFromTableType promotes callable FunctionType fields from direct return table literals to SymbolKind.METHOD while keeping moduleType.fields["run"] as FunctionType. status=review.
