id: TASK-629
title: Windows SignatureHelp outside-call range-end product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/model/SignatureHelpProvider.kt
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/lsp/LuaLanguageService.kt
  - src/jvmTest/kotlin/lsp/LspSignatureHelpActiveParameterTddTest.kt
  - src/jvmTest/kotlin/lsp/LspSignatureHelpActiveParamTddTest.kt
acceptance_criteria:
  - Clear Windows slice failures (evidence run 29194477445 / WINSLICE-29194477445 / slice s006):
    - lsp.LspSignatureHelpActiveParameterTddTest#outside_call_context_returns_null_without_crash
    - lsp.LspSignatureHelpActiveParamTddTest#outside_call_context_returns_null_without_crash
  - Observed Windows failure: both suites assertNotNull SignatureHelp at exact `call.range.end` (`positionAfter("render(1, \"hi\")")`) with message `exact call.range.end remains inside argument region per SignatureHelpProvider`, but product returns null (AssertionError on assertNotNull).
  - Product SignatureHelpProvider must treat the end-inclusive call argument region so signature help is non-null at exact call.range.end (and, per existing goldens, still non-null on the next significant token that finishNode inflates into call.range.end), while remaining null on callee name, non-call locals, function declarations, empty docs, and identifiers past the inflated end (`sentinel`).
  - Prefer product fix in SignatureHelpProvider argument-region / contains boundary logic (and LSP forward only if needed); no CURRENTLY_ACCEPTS weakening of hard-lock asserts; do not rewrite test goldens to hide product null at call.range.end.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s006: `./gradlew.bat jvmTest --tests lsp.LspSignatureHelpActiveParameterTddTest.outside_call_context_returns_null_without_crash --tests lsp.LspSignatureHelpActiveParamTddTest.outside_call_context_returns_null_without_crash`
notes:
  - Windows slice s006 run 29194477445: 3 reds; this task owns the two SignatureHelp outside_call_context methods (shared product boundary failure).
  - Distinct from done test-only TASK-263 / TASK-447 corpora; this is product SignatureHelpProvider range-end containment fix under WINSLICE evidence.
  - Coordinate exclusive claim on SignatureHelpProvider.kt vs TASK-561 / TASK-627 when overlapping.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-629.lock
  - locks/files/tasks__TASK-629.md.lock
  - locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__semantic__model__SignatureHelpProvider.kt.lock
related_commits: []
progress:
  - 2026-07-12T materialize WINSLICE-29194477445: created ready product fix for two outside_call_context SignatureHelp reds (ActiveParameter + ActiveParam).
  - 2026-07-12T13:49:30Z worker-WINSLICE-29194477445-TASK-629: claimed in_progress; fixing SignatureHelpProvider end-inclusive call.range.end / next-token argument region containment for outside_call_context reds.
  - 2026-07-12T13:51:34Z worker-WINSLICE-29194477445-TASK-629: product fix in SignatureHelpProvider — end-inclusive call.range.end via half-open index probe; inflate argument region to next sibling statement start for historical next-token finishNode policy; keep null on callee/locals/decl/sentinel. No test weakening. No Gradle. status→review owner→unassigned.
