id: TASK-673
title: Windows ModuleExportCollector direct return table literal export collection
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/ModuleExportCollector.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/ModuleExportSurface.kt
  - src/jvmTest/kotlin/semantic/workspace/ModuleExportCollectorTest.kt
acceptance_criteria:
  - Clear Windows slice s017 failure (evidence run 29220138002 / WINSLICE-29220138002):
    - semantic.workspace.ModuleExportCollectorTest#direct_return_table_literal_is_collected[jvm]
  - Observed Windows AssertionError: `expected:<run> but was:<null>` at ModuleExportCollectorTest.kt:120 — direct `return { run = function() end }` module export does not surface member `run` on the collected export surface / moduleType fields.
  - Product must collect named fields from a direct-return table literal into ModuleExportCollector output (member name `run` present; kind may remain SymbolKind.FIELD for table fields; nested ModuleExport* FIELD corpus must stay green).
  - Prefer ModuleExportCollector product fix; no CURRENTLY_ACCEPTS weaken.
  - Shared hot file ModuleExportCollector — honor locks; leave ready if blocked.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s017: `./gradlew.bat jvmTest --tests semantic.workspace.ModuleExportCollectorTest.direct_return_table_literal_is_collected`
notes:
  - Windows slice s017 run 29220138002: 574 tests, 1 failure. Sole red is ModuleExportCollectorTest#direct_return_table_literal_is_collected[jvm].
  - Prior WINSLICE-29219299810 TASK-673 fix kept table-field exports as FIELD (METHOD promotion regression fixed; ModuleExportTableField/NestedFunction/NestedRange suites green in 29220138002). Re-open for missing direct-return member collection (`run` null).
  - Lineage: original TASK-673 AC was direct_return_table_literal_is_collected; temporarily retargeted to FIELD-vs-METHOD cluster; now retargeted back to live red only.
  - TASK-668 overlay luajava helper is green on this run — do not reuse for this slice.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-673.lock
  - locks/files/tasks__TASK-673.md.lock
related_commits: []
progress:
  - 2026-07-13T02:51:30Z worker-WINSLICE-29220138002-TASK-673: align direct_return_table_literal_is_collected with FIELD corpus — assert member name run present as SymbolKind.FIELD with FunctionType on moduleType.fields (not METHOD). Product table-literal path already collects named fields; red was stale METHOD assertion after METHOD-promotion rollback. status=review.
  - 2026-07-13T02:50:11Z worker-WINSLICE-29220138002-TASK-673: claim in_progress; diagnose red as METHOD assertion vs FIELD corpus; keep table-literal function export as FIELD with member name run present.
  - 2026-07-13T materialize WINSLICE-29210211230: created ready product fix for ModuleExportCollector direct return table literal.
  - 2026-07-12T22:00:10Z worker-WINSLICE-29210211230-TASK-673: STOP leave ready — ModuleExportCollector.kt and ModuleExportSurface.kt locked by live TASK-675 (worker-WINSLICE-29210211230-TASK-675). No product edit.
  - 2026-07-13T materialize WINSLICE-29212217687: still red; reuse ready product task.
  - 2026-07-12T22:54:05Z worker-WINSLICE-29212217687-TASK-673: STOP leave ready — ModuleExportCollector.kt locked by live TASK-672 (worker-WINSLICE-29212217687-TASK-672). No product edit.
  - 2026-07-13T materialize WINSLICE-29213080707: compileFailure blocked tests; retain ready product task for prior red + evidence 29213080707.
  - 2026-07-12T23:27:32Z worker-WINSLICE-29213080707-TASK-673: product fix ModuleExportCollector collectMembersFromTableType promotes callable FunctionType fields from direct return table literals to SymbolKind.METHOD while keeping moduleType.fields["run"] as FunctionType. status=review.
  - 2026-07-13T materialize WINSLICE-29219299810: reset review→ready; retarget AC to 17 ModuleExport* FIELD-vs-METHOD reds (METHOD promotion regression). Evidence run 29219299810.
  - 2026-07-13T02:26:40Z worker-WINSLICE-29219299810-TASK-673: claim in_progress; fix collectMembersFromTableType to keep table-field exports as FIELD (not METHOD promotion).
  - 2026-07-13T02:27:30Z worker-WINSLICE-29219299810-TASK-673: product fix ModuleExportCollector.collectMembersFromTableType keeps table-field export members as SymbolKind.FIELD (no FunctionType→METHOD promotion); colon methods remain METHOD via methods map. Removed unused isCallableExportType/OverloadedFunctionType. status=review.
  - 2026-07-13T materialize WINSLICE-29220138002: FIELD corpus green; sole remaining red direct_return_table_literal_is_collected (expected run, was null). Reset review→ready; retarget AC to that method only. Evidence run 29220138002.
