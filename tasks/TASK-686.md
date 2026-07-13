id: TASK-686
title: FULL RealProject require-alias barrel reexport completion + shadow dual-path product fix
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
  - Clear FULL jvmTest failures (evidence run 29268552687 / WINFULL-29268552687; remaining reds after 29265516413 partial green):
    - lsp.LspRealProjectRequireAliasExportTddTest#barrel_init_reexport_completion_includes_leaf_members[jvm]
    - lsp.LspRealProjectRequireAliasExportTddTest#shadow_local_foo_does_not_force_only_export_uri[jvm]
  - Observed Windows AssertionErrors (run 29268552687):
    - barrel completion returns only globals/builtins (pkg/assert/…/string/table/utf8), missing leaf reexport members from init barrel.
    - local `foo` dual-path definition jumps only to foreign `lib/util.lua` export URI (must include file-local binding / not force-only foreign).
  - Product must: (1) resolve barrel `init` reexports into completion for leaf members (e.g. leafRun), (2) keep same-name local shadow dual-path file-local (not force-only foreign export URI).
  - Prefer ModuleExportCollector / facade / ReferenceQueries / LSP completion+definition product fix; no CURRENTLY_ACCEPTS weaken; never invent G:/.
  - Serialize exclusive claims on LuaLanguageService / LuaWorkspaceQueryFacade / ModuleExportCollector vs TASK-687 and other export tasks; leave ready if blocked.
  - Prior methods green on 29268552687 (do not regress): barrel_init_reexport_definition_for_leafRun, hover_emmy_param_on_exported_function_surfaces_from_provider.
required_tests:
  - Deferred to TASK-043 / full jvmTest run 29268552687: `./gradlew.bat jvmTest --tests lsp.LspRealProjectRequireAliasExportTddTest.barrel_init_reexport_completion_includes_leaf_members --tests lsp.LspRealProjectRequireAliasExportTddTest.shadow_local_foo_does_not_force_only_export_uri`
notes:
  - Cluster require-alias-export-barrel-shadow from REVIEW-FULL-29268552687 (2 reds in this suite).
  - Reused from WINFULL-29265516413; definition/hover reds cleared on 29268552687; barrel completion + shadow still red.
  - Related lineage TASK-672/673/675 (unit export surfaces) but this owns RealProject LSP require-alias suite reds only.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-686.lock
  - locks/files/tasks__TASK-686.md.lock
related_commits: []
progress:
  - 2026-07-14T materialize WINFULL-29265516413: created ready product fix clustering 4 LspRealProjectRequireAliasExportTddTest reds (evidence run 29265516413).
  - 2026-07-13T16:22:44Z worker-WINFULL-29265516413-TASK-686: blocked by live locks from TASK-685 (LuaLanguageService/LuaWorkspaceQueryFacade/ReferenceQueries); left status=ready owner=unassigned.
  - 2026-07-13T16:57:36Z master full-loop: product fixes for require-alias barrel/shadow/hover; status=review.
  - 2026-07-14T materialize WINFULL-29268552687: still red barrel completion + shadow_local_foo (definition/hover green); reset review→ready; AC narrowed to remaining 2 methods (evidence run 29268552687).
  - 2026-07-13T17:15:05Z worker-WINFULL-29268552687-TASK-686: claimed; implementing barrel init reexport completion + shadow local dual-path definition product fix.
  - 2026-07-13T17:16:42Z master: fixed residual barrel completion needle + shadow occurrence indices; QueryFacade free-id local-before-export; status=review
  - 2026-07-13T17:49:26Z worker-WINFULL-29268552687-TASK-686: product fix for residual reds — incomplete trailing-dot barrel completion recovery (source-aware MemberExpression + require-alias resolve fallbacks) and free-id local shadow dual-path (ReferenceQueries freeIdLocalShadow + QueryFacade call-base identifier recovery). status=review owner=unassigned.
