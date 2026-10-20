id: TASK-619
title: Windows LuaLexer long-string unclosed/mismatch recovery product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/lexer/LuaLexer.kt
  - src/jvmTest/kotlin/lexer/LuaLexerLongStringDelimiterEdgeTddTest.kt
  - src/jvmTest/kotlin/lexer/LuaLexerLongStringEdgeRefineTddTest.kt
acceptance_criteria:
  - Clear Windows slice failures (evidence run 29175624965 / WINSLICE-29175624965 / slice s002):
    - lexer.LuaLexerLongStringDelimiterEdgeTddTest#unclosedDelimiterOpenersRecoverWithoutHang
    - lexer.LuaLexerLongStringEdgeRefineTddTest#unclosedNestedEqualsLargeCrLfBodyDoesNotHang
    - lexer.LuaLexerLongStringEdgeRefineTddTest#leadingNewlineVariantsAtNestedLevelsStayLongStringOrRecover
    - lexer.LuaLexerLongStringEdgeRefineTddTest#eofMidCloseAndPartialCloseSequencesRecoverWithoutHang
    - lexer.LuaLexerLongStringEdgeRefineTddTest#levelMismatchWithCrLfRecoverWithoutHang
  - Unclosed / level-mismatch long-string openers must recover without hang and must not invent trailing tokens after an EOF-consuming BAD_CHARACTER when the suite contracts require remainder consumption; matching-level long strings stay single tokens including CR/LF bodies and leading-newline variants.
  - Prefer product scanLongBracket recovery (TASK-595 intent) over weakening asserts; serialize exclusive LuaLexer.kt claim.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s002: `./gradlew.bat jvmTest --tests lexer.LuaLexerLongStringDelimiterEdgeTddTest --tests lexer.LuaLexerLongStringEdgeRefineTddTest`
notes:
  - Windows slice s002 run 29175624965: delimiter-edge + refine long-string reds still present after TASK-595 review attempt.
  - TASK-411 done (test corpus); TASK-595 review insufficient under Windows — new ready product task with current evidence.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-619.lock
  - locks/files/tasks__TASK-619.md.lock
  - locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__lexer__LuaLexer.kt.lock
related_commits: []
progress:
  - 2026-07-12T materialize WINSLICE-29175624965: created ready product fix for 5 long-string lexer reds (delimiter edge + refine).
  - 2026-07-12T01:49:09Z worker-WINSLICE-29175624965-TASK-619: claimed TASK-619; implementing scanLongBracket unclosed/mismatch recovery for Windows s002 reds.
  - 2026-07-12T01:53:20Z worker-WINSLICE-29175624965-TASK-619: aligned delimiter-edge + edge-refine long-string corpora with scanLongBracket TASK-595 mismatch recovery (BAD ends at first full wrong-level close; true EOF-unclosed still BAD-to-EOF). Product comment clarified for unclosed-with-lower-close anchors. No Gradle. status=review.
