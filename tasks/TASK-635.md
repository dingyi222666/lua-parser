id: TASK-635
title: Windows Lua53 table/function/module AST ranges product fix
status: ready
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/parser/LuaParser.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/parser/ast/node/statementNode.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/parser/ast/node/AbstractNode.kt
  - src/jvmTest/kotlin/parser/lua53/Lua53TableFunctionModuleTddTest.kt
acceptance_criteria:
  - Clear Windows slice s009 failure (evidence run 29198381786 / WINSLICE-29198381786). Primary AC is the method that failed under tasks/agent-runs/win-xml-29198381786:
    - parser.lua53.Lua53TableFunctionModuleTddTest#exposesAstStructureAndRangesForRepresentativeModuleForms[jvm]
  - Observed Windows AssertionError: function declaration range end line expected `<11>` but was `<10>` at assertRangeLines (Lua53TableFunctionModuleTddTest.kt:301) for `function M:run(...) ... end` spanning lines 3-11 (must include the `end` line before the following `return` on line 11 of the return-table statement — wait: statements[2] is the function, expected lines 3-11; returnStatement also line 11).
  - Representative module forms must expose stable AST structure (local table fields, require call, method with nested functions/varargs, return table) and line ranges that cover full syntactic constructs without absorbing following statements incorrectly and without truncating function `end`.
  - Prefer product finishNode / last-consumed-token range emission over dual-path test weakening. Do not CURRENTLY_ACCEPTS the range goldens.
  - Keep the whole Lua53TableFunctionModuleTddTest suite green on re-run (table constructors, function bodies, require/module, resource fixture).
  - Serialize exclusive LuaParser.kt claim vs TASK-577 (expression precedence) when overlapping.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s009: `./gradlew.bat jvmTest --tests parser.lua53.Lua53TableFunctionModuleTddTest.exposesAstStructureAndRangesForRepresentativeModuleForms`
notes:
  - Windows slice s009 run 29198381786: 115 tests, 5 failures; this task owns the single Lua53TableFunctionModuleTddTest range red.
  - Related prior TASK-579 (review) attempted lastCommittedEndPosition fix for the same method under MODULE-VERIFY package path; Windows s009 still red — fresh WINSLICE product task with correct parser.lua53.* AC.
  - Distinct from TASK-632 AstShape fixture CRLF ranges (parser.ast package).
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-635.lock
  - locks/files/tasks__TASK-635.md.lock
  - locks/files/src__jvmTest__kotlin__parser__lua53__Lua53TableFunctionModuleTddTest.kt.lock
related_commits: []
progress:
  - 2026-07-12T materialize WINSLICE-29198381786: created ready product fix for parser.lua53.Lua53TableFunctionModuleTddTest#exposesAstStructureAndRangesForRepresentativeModuleForms range red on s009.
  - 2026-07-12T15:44:10Z worker-WINSLICE-29198381786-TASK-635: STOP leave ready — exclusive LuaParser.kt lock held by live TASK-577 (worker-WINSLICE-29198381786-TASK-577, acquired 2026-07-12T15:42:03Z). Cannot product-fix finishNode/lastCommittedEndPosition ranges for function end line (expected 11 was 10) without LuaParser.kt. Retry after TASK-577 releases locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__parser__LuaParser.kt.lock.
