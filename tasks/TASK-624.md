id: TASK-624
title: Windows LSP diagnostics publish on didChange product fix
status: ready
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/lsp/LuaLanguageService.kt
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/lsp/LuaTextDocumentService.kt
  - src/jvmTest/kotlin/lsp/LspDiagnosticsPublishOnChangeTddTest.kt
acceptance_criteria:
  - Clear Windows slice failures (evidence run 29191587333 / WINSLICE-29191587333 / slice s003):
    - lsp.LspDiagnosticsPublishOnChangeTddTest#range_edit_introducing_parse_error_publishes_diagnostics
    - lsp.LspDiagnosticsPublishOnChangeTddTest#multi_range_content_changes_in_one_notification_apply_in_order
    - lsp.LspDiagnosticsPublishOnChangeTddTest#server_connected_client_receives_change_publishes_in_edit_order
    - lsp.LspDiagnosticsPublishOnChangeTddTest#full_sync_valid_to_invalid_publishes_non_empty_errors_for_that_uri_only
    - lsp.LspDiagnosticsPublishOnChangeTddTest#corpus_table_valid_invalid_repair_matrix_across_uris
    - lsp.LspDiagnosticsPublishOnChangeTddTest#range_edit_repairing_parse_error_clears_diagnostics
  - Observed failures: ranged/full didChange paths that introduce invalid Lua do not publish non-empty Error diagnostics as required; multi-range apply order and server-client publish order fail asserts; corpus valid/invalid/repair matrix across URIs fails; range repair of bare local-name error must clear diagnostics (assert message).
  - Product must apply contentChanges (single/multi range + full sync) then republish diagnostics for that URI only; invalid→non-empty Error diagnostics; repair→clear; sibling URIs isolated; edit-order publish deterministic.
  - Prefer product fix in LuaTextDocumentService/LuaLanguageService publish path; coordinate with TASK-623 if parse diagnostics severity is Warning-only (Error hard-lock). No CURRENTLY_ACCEPTS weakening of green-lock asserts.
  - Related ready TASK-519/TASK-600 remain capability/product locks but this task owns the s003 failed method list with evidence run 29191587333.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s003: `./gradlew.bat jvmTest --tests lsp.LspDiagnosticsPublishOnChangeTddTest`
notes:
  - Windows slice s003 run 29191587333: 6 LspDiagnosticsPublishOnChange reds after prior test-only TASK-449 accepted on Mac.
  - Distinct from TASK-449 (done corpus) and supersedes incomplete TASK-600 AC for these methods under WINSLICE evidence.
  - Serialize exclusive claim on LuaTextDocumentService.kt / LuaLanguageService.kt vs TASK-623/625.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-624.lock
  - locks/files/tasks__TASK-624.md.lock
  - locks/files/src__jvmMain__kotlin__io__github__dingyi222666__luaparser__lsp__LuaTextDocumentService.kt.lock
  - locks/files/src__jvmMain__kotlin__io__github__dingyi222666__luaparser__lsp__LuaLanguageService.kt.lock
related_commits: []
progress:
  - 2026-07-12T materialize WINSLICE-29191587333: created ready product fix for 6 LspDiagnosticsPublishOnChange reds.
  - 2026-07-12T12:01:37Z: worker-WINSLICE-29191587333-TASK-624 STOP leave ready — exclusive locks held by live TASK-622 (LuaLanguageService.kt) and TASK-625 (LuaTextDocumentService.kt). No product files edited.
