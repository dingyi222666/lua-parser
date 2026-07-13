id: TASK-687
title: FULL RealProject multi-module shadow-local definition dual-path product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/lsp/LuaLanguageService.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/LuaWorkspaceQueryFacade.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/model/ReferenceQueries.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/checker/MemberResolver.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/checker/ExpressionTypeEvaluator.kt
  - src/jvmTest/kotlin/lsp/LspRealProjectMultiModuleGraphTddTest.kt
acceptance_criteria:
  - Clear FULL jvmTest failure (evidence run 29268552687 / WINFULL-29268552687; remaining red after 29265516413 partial green):
    - lsp.LspRealProjectMultiModuleGraphTddTest#shadow_local_same_name_as_export_stays_file_local[jvm]
  - Observed Windows AssertionError (run 29268552687):
    - local `trim` same-name-as-export use jumps only to foreign `lib/util.lua` under Windows temp paths (must stay dual-path file-local / not force-only foreign export URI).
  - Product must keep local shadow same-name-as-export definition dual-path file-local (include current-file local binding; not force-only foreign export URI).
  - Prefer ReferenceQueries local-shadow + facade/LSP definition product fix; no CURRENTLY_ACCEPTS weaken; never invent G:/.
  - Serialize exclusive claims vs TASK-686 on shared LSP/facade/ReferenceQueries files; leave ready if blocked.
  - Prior method green on 29268552687 (do not regress): clean_multi_module_files_have_no_error_diagnostics.
required_tests:
  - Deferred to TASK-043 / full jvmTest run 29268552687: `./gradlew.bat jvmTest --tests lsp.LspRealProjectMultiModuleGraphTddTest.shadow_local_same_name_as_export_stays_file_local`
notes:
  - Cluster multi-module-graph-shadow from REVIEW-FULL-29268552687 (1 red in this suite).
  - Reused from WINFULL-29265516413; clean multi-module diagnostics green on 29268552687; shadow still red.
  - Distinct from TASK-686 RealProject require-alias suite (own fixture + methods); shadow symptom is related but file/suite ownership stays here.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-687.lock
  - locks/files/tasks__TASK-687.md.lock
related_commits: []
progress:
  - 2026-07-14T materialize WINFULL-29265516413: created ready product fix clustering 2 LspRealProjectMultiModuleGraphTddTest reds (evidence run 29265516413).
  - 2026-07-13T16:23:10Z worker-WINFULL-29265516413-TASK-687: STOP leave ready; blocked on live TASK-685 locks for shared LSP/facade/ReferenceQueries/ExpressionTypeEvaluator.
  - 2026-07-13T16:57:36Z master full-loop: product fixes for multi-module shadow + false string member diagnostics; status=review.
  - 2026-07-14T materialize WINFULL-29268552687: clean diagnostics green; shadow still red; reset review→ready; AC narrowed to shadow_local_same_name_as_export_stays_file_local (evidence run 29268552687).
  - 2026-07-13T17:15:45Z worker-WINFULL-29268552687-TASK-687: STOP leave ready; blocked on live TASK-686 locks for shared LSP/facade/ReferenceQueries (same wave); no product edits.
  - 2026-07-13T17:16:42Z master: fixed shadow_local occurrence indices + QueryFacade free-id local-before-export; status=review
