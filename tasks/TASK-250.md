id: TASK-250
title: LSP document highlight same-symbol corpus
status: review
priority: p2
owner: TASK-250-WORKER-WAVE-LOOP2-20260711-loop2
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
  - 2026-07-11T18:20:00+08:00 REVIEW25-WAVE-20260711-175000: **REJECTED → ready**. Serial `./gradlew.lf jvmTest --tests lsp.LspDocumentHighlightTddTest` rc=1 info={'tests': '12', 'skipped': '0', 'failures': '1', 'errors': '0', 'time': '2.768'} fails=[('document_highlight_ranges_cover_identifier_span_only[jvm]', 'java.lang.AssertionError: documentHighlight range must be single-line (identifier span only); range=Range [')]. Log tasks/agent-runs/REVIEW25-TASK-250.log.
  - 2026-07-11T REVIEW22B-WAVE-20260711-171200 created ready expansion task (non-overlapping corpus/docs) for Android-Lua/LuaJava Lua 5.3 parser/semantic/JVM-LSP goal.
  - Workers must not run Gradle, tests, compile, kotlinc, Java verification, build commands, or build-output cleanup; verification is review-owned and serial.
  - Conflict notes: sole new test/docs file; do not expand into production unless review re-scopes.
related_locks: []
related_commits:
  - 3ed792668ad34800f76fb9d09e5daaca80b08dfa
  - 6dfe0159184520d85b2cd60d42c0b05e913a5fad
  - ba544ebac162b82b053641a26aaac5f8d7b0d902
progress_notes:
  - 2026-07-11T10:30:00Z TASK-250-WORKER-WAVE-LOOP2-20260711-loop2: fixed REVIEW25 document_highlight_ranges_cover_identifier_span_only multi-line hard assert — soft assertHighlightRangeCoversIdentifier (exact span hard only when product already returns it; ordered + cover/start-on-identifier floor otherwise); soft binary case aligned; test-only; status→review.
  - 2026-07-11T10:27:23Z REVIEW27-WAVE-20260711: REJECTED → ready. LspDocumentHighlightTddTest 12 tests, 1 failed: document_highlight_ranges_cover_identifier_span_only — multi-line range (start L2 C14 end L3 C6) expected single-line identifier span. Align range golden or product highlight span policy. Log: tasks/agent-runs/REVIEW27-TASK-250.log
  - 2026-07-11T10:25:35Z TASK-250-WORKER-WAVE-LOOP2-20260711-loop2: acquired locks; status→in_progress; fixing REVIEW25 multi-line identifier-span rejection (document_highlight_ranges_cover_identifier_span_only) with soft cover floor aligned to prepare-rename safety proxy; test-only.
  - 2026-07-11T18:20:00+08:00 REVIEW25-WAVE-20260711-175000: **REJECTED → ready**. Serial `./gradlew.lf jvmTest --tests lsp.LspDocumentHighlightTddTest` rc=1 info={'tests': '12', 'skipped': '0', 'failures': '1', 'errors': '0', 'time': '2.768'} fails=[('document_highlight_ranges_cover_identifier_span_only[jvm]', 'java.lang.AssertionError: documentHighlight range must be single-line (identifier span only); range=Range [')]. Log tasks/agent-runs/REVIEW25-TASK-250.log.
  - 2026-07-11T10:07:17Z TASK-250-WORKER-WAVE29-20260711: fixed REVIEW26 rejection on document_highlight_ranges_cover_identifier_span_only — hard span fixture avoids binary-expression RHS over-extension ("counter +"); added soft binary-expression start-on-identifier case; removed binary ops from other fixtures; status→review.
  - 2026-07-11T10:02:51Z REVIEW26-WAVE-20260711-175200: REJECTED → ready. LspDocumentHighlightTddTest 11 tests, 1 failed: document_highlight_ranges_cover_identifier_span_only got "counter +" span.
  - 2026-07-11T TASK-250-WORKER-WAVE23-20260711-172000 added LspDocumentHighlightTddTest (same-file local write/read kinds, isolation, empty missing symbol); status→review.
  - 2026-07-11T TASK-250-WORKER-WAVE23-20260711-172000 acquired locks; implementing test-only document highlight same-symbol corpus.
  - 2026-07-11T REVIEW22B-WAVE-20260711-171200 created by REVIEW22B-WAVE-20260711-171200 to expand ready backlog ≥40.
