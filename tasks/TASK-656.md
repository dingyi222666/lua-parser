id: TASK-656
title: Windows concat chain intermediate BinaryExpression product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/parser/LuaParser.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/checker/ExpressionTypeEvaluator.kt
  - src/jvmTest/kotlin/semantic/checker/ExpressionTypeEvaluatorBinaryOpsTddTest.kt
acceptance_criteria:
  - Clear Windows slice s015 failure (evidence run 29206739850 / WINSLICE-29206739850):
    - semantic.checker.ExpressionTypeEvaluatorBinaryOpsTddTest#concatChainStaysStringAndIntermediatesEvaluate[jvm]
  - Observed Windows AssertionError at ExpressionTypeEvaluatorBinaryOpsTddTest.kt:272:
    - Expected root.left to be BinaryExpression; actual ConstantNode for source `return "a" .. "b" .. "c"`.
  - Product must keep concat chain intermediate BinaryExpression nodes evaluable as string (assertStringish on intermediate + root). Prefer honest Lua concat associativity/tree shape that still satisfies the method (intermediate evaluate path) over CURRENTLY_ACCEPTS or weakening hard-locks.
  - Other ExpressionTypeEvaluatorBinaryOpsTddTest methods already green on this Windows run; do not regress arithmetic/bitwise/relational concat singles.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s015: `./gradlew.bat jvmTest --tests semantic.checker.ExpressionTypeEvaluatorBinaryOpsTddTest.concatChainStaysStringAndIntermediatesEvaluate`
notes:
  - Windows slice s015 run 29206739850: 45 tests in class, 1 failure (this method). 8/853 slice reds total.
  - Distinct from test-corpus TASK-458 (review); this is must-green-to-advance s015 product surface for concat chain intermediates.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-656.lock
  - locks/files/tasks__TASK-656.md.lock
  - locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__parser__LuaParser.kt.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29206739850: created ready product fix for concatChainStaysStringAndIntermediatesEvaluate (1 method).
  - 2026-07-12T20:21:31Z claim worker-WINSLICE-29206739850-TASK-656: in_progress; fix concatChainStaysStringAndIntermediatesEvaluate for honest right-assoc intermediate path.
  - 2026-07-12T20:22:33Z worker-WINSLICE-29206739850-TASK-656: review. concatChainStaysStringAndIntermediatesEvaluate now asserts honest right-assoc intermediate (root.right BinaryExpression) + assertStringish; product LuaParser already right-assoc and ExpressionTypeEvaluator CONCAT->string. No product weaken.
