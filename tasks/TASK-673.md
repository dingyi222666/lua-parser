id: TASK-673
title: Windows ModuleExportCollector export surface keeps callable table fields as FIELD
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/ModuleExportCollector.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/ModuleExportSurface.kt
  - src/jvmTest/kotlin/semantic/workspace/ModuleExportTableFieldTddTest.kt
  - src/jvmTest/kotlin/semantic/workspace/ModuleExportNestedFunctionTddTest.kt
  - src/jvmTest/kotlin/semantic/workspace/ModuleExportCollectorNestedRangeTddTest.kt
acceptance_criteria:
  - Clear Windows slice s017 failures (evidence run 29219299810 / WINSLICE-29219299810). Clustered ModuleExport* reds — product must keep table-field export members as SymbolKind.FIELD (not METHOD) while still collecting nested function/table exports:
    - semantic.workspace.ModuleExportCollectorNestedRangeTddTest#direct_table_return_preserves_nested_member_ranges_for_provider_navigation_and_references[jvm]
    - semantic.workspace.ModuleExportNestedFunctionTddTest#string_index_nested_function_write_is_exported[jvm]
    - semantic.workspace.ModuleExportNestedFunctionTddTest#aliased_export_root_keeps_nested_function_writes[jvm]
    - semantic.workspace.ModuleExportNestedFunctionTddTest#deep_nested_function_path_is_exported[jvm]
    - semantic.workspace.ModuleExportNestedFunctionTddTest#returned_m_table_exposes_nested_dot_function_on_export_surface[jvm]
    - semantic.workspace.ModuleExportNestedFunctionTddTest#mixed_nested_function_forms_appear_and_locals_stay_hidden[jvm]
    - semantic.workspace.ModuleExportNestedFunctionTddTest#nested_local_inside_exported_function_body_is_not_exported[jvm]
    - semantic.workspace.ModuleExportNestedFunctionTddTest#non_exported_locals_stay_hidden_on_export_surface[jvm]
    - semantic.workspace.ModuleExportNestedFunctionTddTest#direct_table_return_nested_function_literals_are_exported[jvm]
    - semantic.workspace.ModuleExportNestedFunctionTddTest#nested_function_assignment_write_is_exported[jvm]
    - semantic.workspace.ModuleExportTableFieldTddTest#nested_m_table_field_writes_visible_to_require_consumer[jvm]
    - semantic.workspace.ModuleExportTableFieldTddTest#direct_table_return_exports_function_field_f[jvm]
    - semantic.workspace.ModuleExportTableFieldTddTest#non_exported_local_table_fields_stay_hidden[jvm]
    - semantic.workspace.ModuleExportTableFieldTddTest#direct_table_return_exports_scalar_and_function_fields[jvm]
    - semantic.workspace.ModuleExportTableFieldTddTest#returned_m_table_field_writes_export_function_and_scalar[jvm]
    - semantic.workspace.ModuleExportTableFieldTddTest#returned_m_table_dot_function_declaration_exports_field_f[jvm]
    - semantic.workspace.ModuleExportTableFieldTddTest#nested_table_literal_export_fields_are_collected[jvm]
  - Observed Windows AssertionError pattern: `expected:<FIELD> but was:<METHOD>` for nested/table-field export members (e.g. nested.run, f, outer.f, tools.compute, a.b.c.leaf). nested_m_table_field_writes_visible_to_require_consumer fails assertTrue on `members.any { exportPath == ["f"] && kind == FIELD }`.
  - Root cause regression: collectMembersFromTableType promoted callable FunctionType fields to SymbolKind.METHOD (prior TASK-673 direct-return fix). Product must collect named fields from direct return / returned-M / nested writes without flipping table-field export kind away from FIELD; moduleType field types may remain FunctionType.
  - Prefer ModuleExportCollector product fix; no CURRENTLY_ACCEPTS weaken.
  - Shared hot file ModuleExportCollector — honor locks; leave ready if blocked.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s017: `./gradlew.bat jvmTest --tests semantic.workspace.ModuleExportTableFieldTddTest --tests semantic.workspace.ModuleExportNestedFunctionTddTest --tests semantic.workspace.ModuleExportCollectorNestedRangeTddTest`
notes:
  - Windows slice s017 run 29219299810: 17/18 failures are ModuleExport* FIELD vs METHOD kind regressions after METHOD promotion in collectMembersFromTableType.
  - Lineage: TASK-087 nested ranges (done); prior TASK-673 AC was direct_return_table_literal_is_collected (not in live reds). Retargeted to live export-kind corpus.
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
  - 2026-07-13T materialize WINSLICE-29219299810: reset review→ready; retarget AC to 17 ModuleExport* FIELD-vs-METHOD reds (METHOD promotion regression). Evidence run 29219299810.
  - 2026-07-13T02:26:40Z worker-WINSLICE-29219299810-TASK-673: claim in_progress; fix collectMembersFromTableType to keep table-field exports as FIELD (not METHOD promotion).
  - 2026-07-13T02:27:30Z worker-WINSLICE-29219299810-TASK-673: product fix ModuleExportCollector.collectMembersFromTableType keeps table-field export members as SymbolKind.FIELD (no FunctionType→METHOD promotion); colon methods remain METHOD via methods map. Removed unused isCallableExportType/OverloadedFunctionType. status=review.
