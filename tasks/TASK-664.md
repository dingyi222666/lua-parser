id: TASK-664
title: Windows ReferenceQueries cross-module require/export definition product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/model/ReferenceQueries.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/LuaWorkspaceQueryFacade.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/WorkspaceModuleResolver.kt
  - src/jvmTest/kotlin/semantic/model/ReferenceQueriesCrossModuleTddTest.kt
acceptance_criteria:
  - Clear Windows slice s016 failures (evidence run 29208645359 / WINSLICE-29208645359):
    - semantic.model.ReferenceQueriesCrossModuleTddTest#two_file_require_local_definition_resolves_to_provider_module[jvm]
    - semantic.model.ReferenceQueriesCrossModuleTddTest#missing_export_member_definition_stays_empty[jvm]
    - semantic.model.ReferenceQueriesCrossModuleTddTest#known_export_still_resolves_when_sibling_missing_export_present[jvm]
  - Observed Windows AssertionErrors:
    - two_file_require_local_definition: expected definition paths `[dep.lua]` but was `[main.lua]` for local `dep =
      require("dep")` binding + usage (must resolve to provider module, not the local binding site only).
    - missing_export_member_definition_stays_empty: expected empty definitions for `dep.missing` but got
      `[WorkspaceLocation(path=dep.lua, range=([1,1],[1,4]))]` (must not invent a definition for absent export member).
    - known_export_still_resolves_when_sibling_missing_export_present: sibling missing-export path still leaks a dep.lua
      definition where empty was expected (missing member stays empty while known export still resolves).
  - Product gotoDefinition across require/module exports must (1) land require-local + usage on provider module export
    table / return, (2) keep missing export members empty, (3) not let a sibling missing member invent definitions that
    poison known-export cases.
  - Prefer ReferenceQueries + WorkspaceModuleResolver export-member resolution over CURRENTLY_ACCEPTS or weakening hard locks.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s016: `./gradlew.bat jvmTest --tests semantic.model.ReferenceQueriesCrossModuleTddTest.two_file_require_local_definition_resolves_to_provider_module --tests semantic.model.ReferenceQueriesCrossModuleTddTest.missing_export_member_definition_stays_empty --tests semantic.model.ReferenceQueriesCrossModuleTddTest.known_export_still_resolves_when_sibling_missing_export_present`
notes:
  - Windows slice s016 run 29208645359: 3 reds in ReferenceQueriesCrossModuleTddTest (prior TASK-223 corpus was accepted; regression under must-green-to-advance s016).
  - Distinct from hover preferred-type TASK-601 / createProxy ReferenceQueries TASK-630.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-664.lock
  - locks/files/tasks__TASK-664.md.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29208645359: created ready product fix clustering 3 cross-module require/export definition reds.
  - 2026-07-12T21:02:40Z worker-WINSLICE-29208645359-TASK-664: claim in_progress; fix require-local goto + missing-export empty defs.
  - 2026-07-12T21:07:45Z worker-WINSLICE-29208645359-TASK-664: product fix in LuaWorkspaceQueryFacade — prefer require-local provider definition; empty defs/refs for missing require-backed export members; status=review.
  - 2026-07-12T21:16:30Z worker-WINSLICE-29208645359-TASK-664: harden require recognition (builtin visibility + StringCall/callArguments); keep require-local provider goto + empty missing-export defs/refs; status=review.
