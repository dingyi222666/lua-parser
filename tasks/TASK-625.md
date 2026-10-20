id: TASK-625
title: Windows LSP didOpen multi-doc diagnostics product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/lsp/LuaLanguageService.kt
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/lsp/LuaTextDocumentService.kt
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/lsp/LuaLanguageServer.kt
  - src/jvmTest/kotlin/lsp/LspDidOpenMultiDocDiagnosticsTddTest.kt
acceptance_criteria:
  - Clear Windows slice failures (evidence run 29192058315 / WINSLICE-29192058315 / slice s003; prior 29191587333):
    - lsp.LspDidOpenMultiDocDiagnosticsTddTest#mixed_valid_and_invalid_opens_isolate_diagnostics_per_uri
    - lsp.LspDidOpenMultiDocDiagnosticsTddTest#server_connected_client_receives_multi_doc_open_diagnostics_in_order
    - lsp.LspDidOpenMultiDocDiagnosticsTddTest#multi_doc_corpus_table_covers_valid_invalid_and_reopen_matrix
  - Observed failures: mixed valid/invalid opens report Warning instead of Error for invalid URI; multi-doc open publish order and corpus table (valid/invalid/reopen) assertTrue failures.
  - Product didOpen must publish diagnostics per URI with isolation (valid empty / invalid non-empty Error), deterministic multi-doc open order to connected client, and reopen matrix without sibling bleed.
  - Prefer product publish/open path fix; align parse invalid severity with TASK-623 Error hard-lock. No CURRENTLY_ACCEPTS weakening.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s003: `./gradlew.bat jvmTest --tests lsp.LspDidOpenMultiDocDiagnosticsTddTest`
notes:
  - Windows slice s003 run 29192058315: same 3 LspDidOpenMultiDocDiagnostics reds remain among 11 failures. Reuse ready product task; locks cleared after TASK-622.
  - Windows slice s003 run 29191587333: 3 LspDidOpenMultiDocDiagnostics reds after prior test-only TASK-374 accepted on Mac.
  - Coordinate severity with TASK-623; serialize exclusive claim on LuaTextDocumentService.kt vs TASK-624.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-625.lock
  - locks/files/tasks__TASK-625.md.lock
  - locks/files/src__jvmMain__kotlin__io__github__dingyi222666__luaparser__lsp__LuaTextDocumentService.kt.lock
related_commits: []
progress:
  - 2026-07-12T materialize WINSLICE-29191587333: created ready product fix for 3 LspDidOpenMultiDocDiagnostics reds.
  - 2026-07-12T12:02:33Z worker-WINSLICE-29191587333-TASK-625: STOP leave ready — exclusive LuaLanguageService.kt locked by live TASK-622; semantic.lock also held by TASK-622. Root cause (shared with TASK-623): for invalid `local =`, parseDiagnostics correctly publishes LSP Error (lua-parse), but binder binds Identifier("") and ExpressionUsageChecker emits Unused local '' as WARNING; multi-doc Error hard-locks (mixed_valid L99, server_connected L307, corpus L388) fail on that Warning. Product fix needs publishDiagnostics filter and/or isIgnoredLocalName empty-name suppression in LanguageService/semantic — cannot edit under live locks. Held only task+TextDocumentService+LanguageServer locks briefly; restored TASK-622 LanguageService lock after accidental overwrite. No product edit. Retry after TASK-622 releases.
  - 2026-07-12T materialize WINSLICE-29192058315: still ready; 3 multi-doc didOpen reds remain on evidence run 29192058315; locks cleared; re-queue for product fix.
  - 2026-07-12T12:14:03Z worker-WINSLICE-29192058315-TASK-625: claimed locks; implementing publishDiagnostics filter so parse-invalid multi-doc opens are Error-only (suppress recovery Unused local '' Warning).
  - 2026-07-12T12:15:00Z worker-WINSLICE-29192058315-TASK-625: product fix — (1) ExpressionUsageChecker.isIgnoredLocalName treats blank/empty recovery names as ignored; (2) LuaLanguageService.publishDiagnostics drops non-Error semantic diags when parse recovery diags present so multi-doc invalid opens publish Error-only. status=review. Workers no Gradle.
