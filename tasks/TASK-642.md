id: TASK-642
title: Windows layout-table recovery inventory ExpressionNodeSupport product fix
status: in_progress
priority: p0
owner: worker-WINSLICE-29199561336-TASK-642
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/parser/LuaParser.kt
  - src/jvmTest/kotlin/parser/recovery/LuaParserRecoveryLayoutTableTddTest.kt
acceptance_criteria:
  - Clear Windows slice s010 failure (evidence run 29199561336 / WINSLICE-29199561336):
    - parser.recovery.LuaParserRecoveryLayoutTableTddTest#documentsLayoutTableRecoveryInventorySizeAndCoverage[jvm]
  - Observed Windows AssertionError: newline-after-`{` footgun should insert ExpressionNodeSupport first field; actual shape was `Local(Id(layout)=Table(TableKey(Const(1)=Id(LinearLayout)),TableKeyString(Id(orientation)=Const("vertical"))));CallStmt(Call(Id(print):Id(layout)))` without ExpressionNodeSupport first field.
  - Layout-table recovery inventory size/coverage must match product: documented footgun cases insert ExpressionNodeSupport (or honest CURRENTLY_ACCEPTS) consistently; well-formed layout tables and sibling print retention remain correct for AndroLua loadlayout editing.
  - Prefer product table-constructor recovery after newline/`{` over inventory-only drift; coordinate with TASK-550 unclosed-table sibling policy without stealing exclusive locks mid-wave.
  - Serialize exclusive LuaParser.kt claim vs other s010 recovery product tasks.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s010: `./gradlew.bat jvmTest --tests parser.recovery.LuaParserRecoveryLayoutTableTddTest.documentsLayoutTableRecoveryInventorySizeAndCoverage`
notes:
  - Windows slice s010 run 29199561336: this task owns the LayoutTable inventory/coverage red.
  - TASK-550 (ready) is broader unclosed-table absorb product; this task is WINSLICE-evidence-owned for documentsLayoutTableRecoveryInventorySizeAndCoverage.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-642.lock
  - locks/files/tasks__TASK-642.md.lock
  - locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__parser__LuaParser.kt.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29199561336: created ready product fix for documentsLayoutTableRecoveryInventorySizeAndCoverage.
  - 2026-07-12T16:28:15Z worker-WINSLICE-29199561336-TASK-642: claimed; inventory footgun probe drifts from product (bare Name after newline keeps LinearLayout table field). Align inventory/KDoc to product; LuaParser.kt exclusive lock held by live TASK-639 so product-only path deferred if needed.
