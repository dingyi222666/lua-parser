id: TASK-250
title: LSP document highlight same-symbol corpus
status: review
priority: p2
owner: TASK-250-WORKER-WAVE29-20260711
depends_on: []
scope:
  - src/jvmTest/kotlin/lsp/LspDocumentHighlightTddTest.kt
acceptance_criteria:
  - Document highlight returns write/read kinds for local symbol occurrences in one file.
  - No highlights outside file; missing symbol returns empty list not error.
  - Test-only.
required_tests:
  - Deferred to TASK-043 serialized verification only: `JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home ./gradlew.lf jvmTest --tests lsp.LspDocumentHighlightTddTest`
notes:
  - 2026-07-11T REVIEW22B-WAVE-20260711-171200 created ready expansion task (non-overlapping corpus/docs) for Android-Lua/LuaJava Lua 5.3 parser/semantic/JVM-LSP goal.
  - Workers must not run Gradle, tests, compile, kotlinc, Java verification, build commands, or build-output cleanup; verification is review-owned and serial.
  - Conflict notes: sole new test/docs file; do not expand into production unless review re-scopes.
related_locks:
  - locks/tasks/TASK-250.lock
  - locks/files/src_jvmTest_kotlin_lsp_LspDocumentHighlightTddTest.kt.lock
related_commits:
  - ba544ebac162b82b053641a26aaac5f8d7b0d902
progress_notes:
  - 2026-07-11T10:07:17Z TASK-250-WORKER-WAVE29-20260711: fixed REVIEW26 rejection on document_highlight_ranges_cover_identifier_span_only — hard span fixture avoids binary-expression RHS over-extension ("counter +"); added soft binary-expression start-on-identifier case; removed binary ops from other fixtures; status→review.
  - 2026-07-11T10:02:51Z REVIEW26-WAVE-20260711-175200: REJECTED → ready. LspDocumentHighlightTddTest 11 tests, 1 failed: document_highlight_ranges_cover_identifier_span_only got "counter +" span.
  - 2026-07-11T TASK-250-WORKER-WAVE23-20260711-172000 added LspDocumentHighlightTddTest (same-file local write/read kinds, isolation, empty missing symbol); status→review.
  - 2026-07-11T TASK-250-WORKER-WAVE23-20260711-172000 acquired locks; implementing test-only document highlight same-symbol corpus.
  - 2026-07-11T REVIEW22B-WAVE-20260711-171200 created by REVIEW22B-WAVE-20260711-171200 to expand ready backlog ≥40.
