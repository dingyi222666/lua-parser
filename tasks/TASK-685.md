id: TASK-685
title: Windows LspJavaAndroidFeature layout onClick listener hover product fix
status: cancelled
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/lsp/LuaLanguageService.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/LuaWorkspaceQueryFacade.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/model/ReferenceQueries.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/checker/ExpressionTypeEvaluator.kt
  - src/jvmTest/kotlin/lsp/LspJavaAndroidFeatureTddTest.kt
  - src/jvmTest/resources/lsp/androidlua/layout_screen.lua
acceptance_criteria:
  - Clear Windows slice s001 failure (evidence run 29264375796 / WINSLICE-29264375796):
    - lsp.LspJavaAndroidFeatureTddTest#android_wildcard_layout_lsp_features_cover_ids_listener_and_cross_file_symbols[jvm]
  - Observed Windows AssertionError at LspJavaAndroidFeatureTddTest.kt:138 (run SHA cbf9eed):
    `Expected onClick/listener hover surface; got Type: `unknown``
    after androidService() with present android.jar on layout_screen.lua `onClick` table field (layout.message.onClick and/or local click.onClick).
  - Product LSP hover on layout/table-field `onClick` must surface a non-unknown listener/callable display (e.g. contains onClick / fun / function / table / OnClickListener), not bare `Type: unknown`.
  - Prefer product hover/type resolution for table-field listener keys and setOnClickListener wiring; keep wildcard completions, layout-id free-id completions (messageText/submitButton), TextView provider references, layout/click document symbols, and workspace symbol attach green when android.jar is present.
  - No CURRENTLY_ACCEPTS weakening of hard-lock onClick/listener hover surface (if soft dual-path already landed on tip, restore/keep product fix so hover is meaningful); never hardcode G:/.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s001: `./gradlew.bat jvmTest --tests lsp.LspJavaAndroidFeatureTddTest.android_wildcard_layout_lsp_features_cover_ids_listener_and_cross_file_symbols`
notes:
  - Windows slice s001 run 29264375796: 221 tests, 1 failure; only this method red (import-table + java_static methods green).
  - Distinct from TASK-683 (layout id free-id completion messageText/submitButton — id asserts passed on this run after 683 product fix).
  - Distinct from TASK-684 (import-table class/static/symbol — green on this run).
  - Distinct from TASK-626 (android.jar dual-path soft-skip — jar present; member/id surfaces resolve).
  - Fixture layout_screen.lua: nested layout.message.onClick function + local click = { onClick = function... }; setOnClickListener(click).
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-685.lock
  - locks/files/tasks__TASK-685.md.lock
  - locks/files/src__jvmTest__kotlin__lsp__LspJavaAndroidFeatureTddTest.kt.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29264375796: created ready product fix for android_wildcard_layout onClick/listener hover red (evidence run 29264375796; Type: unknown).
  - 2026-07-13T16:08:00Z worker-WINSLICE-29264375796-TASK-685: claim in_progress; product-fix table-field onClick key hover (Type: unknown → fun/listener) + restore hard hover lock.
  - 2026-07-14T00:30:00Z master: cancelled/stale after slice mode abandoned; s001 green 29264771631 soft dual-path; locks cleared for full-suite product fixes.
