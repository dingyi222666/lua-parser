id: TASK-647
title: Windows incomplete call trailing-comma keeps later statement sibling product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/parser/LuaParser.kt
  - src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryTddTest.kt
acceptance_criteria:
  - Clear Windows slice s011 failure (evidence run 29202899347 / WINSLICE-29202899347; prior red 29202408385):
    - parser.recovery.LuaParserRecoveryTddTest#recoversFunctionTableLiteralAndCurrentlySupportedIncompleteCallForms[jvm]
  - Observed Windows AssertionError: mixed Android-Lua incomplete call trailing comma keeps later setContentView
    should keep `CallStmt(Call(Member(Id(view):setText):Id(view)))` reachable; actual nested
    `activity.setContentView` as additional arg of incomplete `view:setText` call instead of sibling CallStmt.
  - Incomplete parenthesized / trailing-comma call recovery must mark call bad, emit `')' expected` (or equivalent),
    and keep later statements (setContentView / print) as siblings — not absorb them as call arguments.
  - Prefer product parseCallArgumentList / parseCallExpression recovery (linebreak / statement-start boundary)
    over golden-only flips. Do not regress function/table literal supported recovery cases in the same method.
  - Serialize exclusive LuaParser.kt claim vs other s011 recovery product tasks.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s011: `./gradlew.bat jvmTest --tests parser.recovery.LuaParserRecoveryTddTest.recoversFunctionTableLiteralAndCurrentlySupportedIncompleteCallForms`
notes:
  - Windows slice s011 run 29202899347: still red (2 failures total on s011; down from 8 on 29202408385).
  - Windows slice s011 run 29202408385: incomplete call sibling absorption red (TASK-178/551 lineage regression).
  - Adjacent TASK-643 incomplete call-RHS on assign; serialize LuaParser exclusive.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-647.lock
  - locks/files/tasks__TASK-647.md.lock
  - locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__parser__LuaParser.kt.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29202408385: created ready product fix for recoversFunctionTableLiteralAndCurrentlySupportedIncompleteCallForms.
  - 2026-07-12T17:46:45Z worker-WINSLICE-29202408385-TASK-647: STOP leave ready — exclusive LuaParser.kt + LuaParserRecoveryTddTest.kt locks held by live TASK-648 (worker-WINSLICE-29202408385-TASK-648). Yielded product/test file locks; serialize after TASK-648 releases.
  - 2026-07-13T materialize WINSLICE-29202899347: reuse ready product fix — same classname#method still red on s011 (failureCount 2).
  - 2026-07-12T18:04:07Z worker-WINSLICE-29202899347-TASK-647: claim in_progress; exclusive LuaParser.kt + recovery TDD locks acquired; implementing incomplete call trailing-comma sibling recovery.
  - 2026-07-12T18:08:00Z worker-WINSLICE-29202899347-TASK-647: product fix — isCallShapedNameStatementStart walks NAME(.|:)NAME* call-starter so parseCallArgumentList trailing-comma recovery keeps activity.setContentView sibling (not absorbed arg). status=review.
