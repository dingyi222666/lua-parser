id: TASK-687
title: FULL RealProject multi-module shadow-local + false member diagnostics product fix
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
  - Clear FULL jvmTest failures (evidence run 29265516413 / WINFULL-29265516413):
    - lsp.LspRealProjectMultiModuleGraphTddTest#clean_multi_module_files_have_no_error_diagnostics[jvm]
    - lsp.LspRealProjectMultiModuleGraphTddTest#shadow_local_same_name_as_export_stays_file_local[jvm]
  - Observed Windows AssertionErrors (run 29265516413):
    - clean multi-module still reports Error diagnostics on `lib/stringx.lua` with `checker.member.missing` for Lua string members (`upper`/`lower`) mis-attributed as "Unknown Java member … on string".
    - local `trim` same-name-as-export use jumps only to foreign `lib/util.lua` (must stay dual-path file-local).
  - Product must: (1) not emit false Error member-missing diagnostics for legitimate Lua string library members in clean multi-module fixtures, (2) keep local shadow same-name-as-export definition dual-path file-local (not force-only foreign export URI).
  - Prefer checker/MemberResolver type-attribution + ReferenceQueries local-shadow product fix; no CURRENTLY_ACCEPTS weaken; never invent G:/.
  - Serialize exclusive claims vs TASK-686 on shared LSP/facade/ReferenceQueries files; leave ready if blocked.
required_tests:
  - Deferred to TASK-043 / full jvmTest run 29265516413: `./gradlew.bat jvmTest --tests lsp.LspRealProjectMultiModuleGraphTddTest.clean_multi_module_files_have_no_error_diagnostics --tests lsp.LspRealProjectMultiModuleGraphTddTest.shadow_local_same_name_as_export_stays_file_local`
notes:
  - Cluster multi-module-graph-shadow-diagnostics from REVIEW-FULL-29265516413 (2 reds).
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
