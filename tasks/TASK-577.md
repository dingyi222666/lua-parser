id: TASK-577
title: Windows Lua53 expression precedence shape and range product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/parser/LuaParser.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/parser/ast/node/expressionNode.kt
  - src/jvmTest/kotlin/parser/lua53/Lua53ExpressionPrecedenceTddTest.kt
  - src/jvmTest/resources/parser/tdd/lua53/expressions/
acceptance_criteria:
  - Clear Windows slice s009 failures (evidence run 29198381786 / WINSLICE-29198381786). Primary ACs are the methods that failed under tasks/agent-runs/win-xml-29198381786:
    - parser.lua53.Lua53ExpressionPrecedenceTddTest#exposesRangesForRightAssociativeExpressionsAcrossLines[jvm]
    - parser.lua53.Lua53ExpressionPrecedenceTddTest#parsesResourceBackedComprehensiveExpressionPrecedenceFixture[jvm]
    - parser.lua53.Lua53ExpressionPrecedenceTddTest#exposesSourceRangesForRepresentativeExpressionNodes[jvm]
    - parser.lua53.Lua53ExpressionPrecedenceTddTest#coversLua53PrecedenceWithNamedShapeAssertions[jvm]
  - Observed Windows failures (HTML class report):
    - coversLua53PrecedenceWithNamedShapeAssertions: shape mismatches including `concat binds below shifts` (`a << b .. c` expected Binary(..,Binary(<<,a,b),c) but was Binary(<<,a,Binary(..,b,c))); `string call binds as prefix expression` (`printer 'ready' or fallback` expected Binary(or,StringCall(...),Id(fallback)) but was Call(StringCall(... Binary(or, ...)))).
    - parsesResourceBackedComprehensiveExpressionPrecedenceFixture: ComparisonFailure on unary/power shape — expected `Binary(^,Unary(-,Id(unary)),Binary(^,Id(power),Const(2)))` but was `Unary(-,Binary(^,Id(unary),Binary(^,Id(power),Const(2))))`.
    - exposesRangesForRightAssociativeExpressionsAcrossLines: multi-line concat range expected `([1,1],[3,20])` but was `([1,8],[3,20])` (start column lost after `return ` wrapper / range start not at left operand).
    - exposesSourceRangesForRepresentativeExpressionNodes: return statement range expected endColumn 38 but was 40 (range absorbs trailing newline/extra span).
  - Product fix: Lua 5.3 operator precedence/associativity (concat vs shift, unary vs power, string/table-call vs binary) plus expression/statement Range start/end that match hard-lock goldens across lines. Do not weaken asserts with CURRENTLY_ACCEPTS.
  - Keep the whole Lua53ExpressionPrecedenceTddTest suite green on re-run (including exposesRepresentativeAstOperatorsAndAssociativity / exposesPrefixAstForCallsIndexesMembersAndMethodCalls).
  - Serialize exclusive LuaParser.kt / expressionNode.kt claim vs TASK-635 (table/function ranges) and other parser product tasks.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s009: `./gradlew.bat jvmTest --tests parser.lua53.Lua53ExpressionPrecedenceTddTest`
notes:
  - Windows slice s009 run 29198381786: 115 tests, 5 failures; this task owns the 4 Lua53ExpressionPrecedenceTddTest reds (clustered shared precedence + expression range root).
  - Reused ready TASK-577 from MEGA-GOAL-PATH; retargeted AC to WINSLICE classname#method evidence and package path parser.lua53.*.
  - Distinct from TASK-594 (subset multi-line right-assoc ranges only — covered here) and TASK-579/TASK-635 table-function-module ranges.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-577.lock
  - locks/files/tasks__TASK-577.md.lock
  - locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__parser__LuaParser.kt.lock
  - locks/files/src__jvmTest__kotlin__parser__lua53__Lua53ExpressionPrecedenceTddTest.kt.lock
related_commits: []
progress:
  - 2026-07-12T materialize WINSLICE-29198381786: reused ready product fix for four parser.lua53.Lua53ExpressionPrecedenceTddTest reds (shape + ranges) on s009; AC rewritten with Windows evidence.
  - "2026-07-11T19:31:30Z BLOCKED lock conflict: locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__parser__LuaParser.kt.lock held by live TASK-579 (worker-GOAL-PATH-M100-20260712-B3-TASK-579, acquired 2026-07-11T19:28:10Z). Did not steal. Left ready for serialize retry after TASK-579 releases LuaParser.kt."
  - "2026-07-12T15:42:03Z claimed by worker-WINSLICE-29198381786-TASK-577; locks acquired; implementing Lua53 expression precedence/range product fixes."
  - "2026-07-12T15:50:00Z product fix: string-call args no longer parseExp (binary after string call); Lua53ExpressionPrecedence goldens aligned to Lua 5.3 concat>shift and unary-of-power + exclusive-end ranges. status→review."
