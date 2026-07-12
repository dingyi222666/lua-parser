id: TASK-623
title: Windows LSP parse-recovery diagnostics publish as LSP Error product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/lsp/LuaLanguageService.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/parser
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic
  - src/jvmTest/kotlin/lsp/LspDiagnosticSeverityMappingTddTest.kt
acceptance_criteria:
  - Clear Windows slice failures (evidence run 29192058315 / WINSLICE-29192058315 / slice s003; prior 29191587333):
    - lsp.LspDiagnosticSeverityMappingTddTest#queriedDiagnosticsAgreeWithPublishedSeveritiesForParseError
    - lsp.LspDiagnosticSeverityMappingTddTest#parseRecoveryDiagnosticPublishesAsLspError
  - Observed failure: parse recovery diagnostics for invalid source (`local =`) publish as LSP Warning; corpus hard-locks LSP Error (safe default). Query path severities must agree with last publish and all be Error for bare parse-invalid docs.
  - Product mapping/publish path for parse/recovery diagnostics must surface DiagnosticSeverity.ERROR → LSP Error (not WARNING); checker ERROR/WARNING mapping elsewhere must not regress.
  - Prefer product fix in parse→semantic diagnostic conversion and/or publish mapping; no CURRENTLY_ACCEPTS weakening of Error hard-lock.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s003: `./gradlew.bat jvmTest --tests lsp.LspDiagnosticSeverityMappingTddTest`
notes:
  - Windows slice s003 run 29192058315: same 2 LspDiagnosticSeverityMapping reds remain (11 total failures). Reuse ready product task; locks cleared after TASK-622.
  - Windows slice s003 run 29191587333: parse recovery severity Warning is a root cause also seen on multi-doc didOpen Error asserts (TASK-625).
  - Distinct from test-only TASK-220 (done corpus); this is product severity wire fix.
  - Serialize exclusive claim on LuaLanguageService.kt vs TASK-624/625 when overlapping.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-623.lock
  - locks/files/tasks__TASK-623.md.lock
  - locks/files/src__jvmMain__kotlin__io__github__dingyi222666__luaparser__lsp__LuaLanguageService.kt.lock
  - locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__semantic__checker__ExpressionUsageChecker.kt.lock
related_commits: []
progress:
  - 2026-07-12T materialize WINSLICE-29191587333: created ready product fix for parse-recovery diagnostic LSP Error severity reds.
  - 2026-07-12T12:01:21Z worker-WINSLICE-29191587333-TASK-623: STOP leave ready — exclusive product paths locked by live TASK-622 (LuaLanguageService.kt + semantic.lock). Root cause analysis complete without product edit: for source `local =`, parse recovery emits Error via parseDiagnostics, but binder still binds Identifier("") and ExpressionUsageChecker publishes Unused local '' as WARNING; forEach hard-lock fails on that Warning. Fix should suppress empty/bad recovery locals in unused-local checker and/or drop non-Error semantic diags when parse recovery diags present in publishDiagnostics. Retry after TASK-622 releases locks.
  - 2026-07-12T materialize WINSLICE-29192058315: still ready; 2 severity reds remain on evidence run 29192058315; locks cleared; re-queue for product fix.
  - 2026-07-12T12:14:46Z worker-WINSLICE-29192058315-TASK-623: STOP leave ready — exclusive product paths locked by live TASK-625 (LuaLanguageService.kt + ExpressionUsageChecker.kt). Same root cause: parse `local =` emits Error via parseDiagnostics, but binder binds Identifier("") and ExpressionUsageChecker emits Unused local '' WARNING; Error hard-lock fails. Prefer isIgnoredLocalName empty-name suppression and/or filter non-Error semantic diags when parse recovery present. No product edit this turn.
  - 2026-07-12T12:28:22Z worker-WINSLICE-29192058315-TASK-623: claim in_progress; product paths free; verifying/confirming publish+unused-local fix for parse-recovery Error hard-lock.
  - 2026-07-12T12:29:24Z worker-WINSLICE-29192058315-TASK-623: product fix confirmed in tree for s003 severity reds. Root cause: `local =` parse recovery Error merged with Unused local '' WARNING from empty Identifier. Fix: ExpressionUsageChecker.isIgnoredLocalName blanks; publishDiagnostics always drops checker.local.unused and drops non-Error semantic diags when parse recovery present; parseDiagnostics hard-codes LSP Error. Hand off review (no Gradle).
