id: TASK-686
title: FULL RealProject require-alias barrel reexport + shadow + hover product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/lsp/LuaLanguageService.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/LuaWorkspaceQueryFacade.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/ModuleExportCollector.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/ModuleExportSurface.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/model/ReferenceQueries.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/WorkspaceModuleResolver.kt
  - src/jvmTest/kotlin/lsp/LspRealProjectRequireAliasExportTddTest.kt
acceptance_criteria:
  - Clear FULL jvmTest failures (evidence run 29265516413 / WINFULL-29265516413):
    - lsp.LspRealProjectRequireAliasExportTddTest#barrel_init_reexport_completion_includes_leaf_members[jvm]
    - lsp.LspRealProjectRequireAliasExportTddTest#barrel_init_reexport_definition_for_leafRun[jvm]
    - lsp.LspRealProjectRequireAliasExportTddTest#hover_emmy_param_on_exported_function_surfaces_from_provider[jvm]
    - lsp.LspRealProjectRequireAliasExportTddTest#shadow_local_foo_does_not_force_only_export_uri[jvm]
  - Observed Windows AssertionErrors (run 29265516413):
    - barrel completion returns only globals/builtins (pkg/assert/…), not leaf reexport members from init barrel.
    - barrel definition for leafRun returns empty `[]`.
    - hover on exported function Emmy param is null (no provider surface).
    - local `foo` dual-path definition jumps only to foreign `lib/util.lua` export URI (must include file-local binding).
  - Product must: (1) resolve barrel `init` reexports into completion/definition for leaf members (e.g. leafRun), (2) surface Emmy param hover from provider export, (3) keep same-name local shadow dual-path file-local (not force-only foreign export URI).
  - Prefer ModuleExportCollector / facade / ReferenceQueries / LSP query product fix; no CURRENTLY_ACCEPTS weaken; never invent G:/.
  - Serialize exclusive claims on LuaLanguageService / LuaWorkspaceQueryFacade / ModuleExportCollector vs TASK-687 and other export tasks; leave ready if blocked.
required_tests:
  - Deferred to TASK-043 / full jvmTest run 29265516413: `./gradlew.bat jvmTest --tests lsp.LspRealProjectRequireAliasExportTddTest.barrel_init_reexport_completion_includes_leaf_members --tests lsp.LspRealProjectRequireAliasExportTddTest.barrel_init_reexport_definition_for_leafRun --tests lsp.LspRealProjectRequireAliasExportTddTest.hover_emmy_param_on_exported_function_surfaces_from_provider --tests lsp.LspRealProjectRequireAliasExportTddTest.shadow_local_foo_does_not_force_only_export_uri`
notes:
  - Cluster require-alias-export-barrel-shadow-hover from REVIEW-FULL-29265516413 (4 reds).
  - Related lineage TASK-672/673/675 (unit export surfaces) but this owns RealProject LSP suite reds only.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-686.lock
  - locks/files/tasks__TASK-686.md.lock
related_commits: []
progress:
  - 2026-07-14T materialize WINFULL-29265516413: created ready product fix clustering 4 LspRealProjectRequireAliasExportTddTest reds (evidence run 29265516413).
  - 2026-07-13T16:22:44Z worker-WINFULL-29265516413-TASK-686: blocked by live locks from TASK-685 (LuaLanguageService/LuaWorkspaceQueryFacade/ReferenceQueries); left status=ready owner=unassigned.
  - 2026-07-13T16:57:36Z master full-loop: product fixes for require-alias barrel/shadow/hover; status=review.
