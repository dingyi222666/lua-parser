package parser.recovery

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaParseResult
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.IfStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import parser.assertParseFails
import parser.parse
import parser.renderShape

/**
 * Focused recovery corpus for incomplete `repeat` / `until` forms
 * (TASK-281 base + TASK-433 dual-path expansion).
 *
 * Acceptance:
 * - Missing until recovers and keeps later statements reachable.
 * - Nested repeat recovery is bounded (no throw / no runaway nesting).
 * - Dual-path strict expectations document honest CURRENTLY_ACCEPTS footguns
 *   where recovery=false still absorbs a next-line statement as an expression
 *   operand (binary/unary until-condition or incomplete body assign RHS).
 * - Test-only; no production edits.
 *
 * Complements the single missing-until-at-eof fixture in [LuaParserRecoveryTddTest]
 * and [ParserRecoveryRegressionTest] with deeper until/condition/nesting cases.
 *
 * Goldens track current product behaviour in [LuaParser.parseRepeatStatement]:
 * - missing `until` emits `"'until' expected"` and inserts bad ExpressionNodeSupport;
 * - body stops at block terminators (END / UNTIL / ELSE / ELSEIF / EOF);
 * - surrounding `end` (do/if/while/function) leaves trailing statements as siblings
 *   after recovery because END terminates the enclosing construct, not the repeat body;
 * - nested `repeat` without an inner `until` still sees the next `until` as the
 *   *inner* terminator/condition (inner consumes it); the outer then recovers with
 *   a placeholder condition. Any statements after that consumed until remain in the
 *   *outer* body until EOF (they are not top-level siblings). Nested recovery stays
 *   bounded to the written depth.
 * - incomplete binary right after line-break: recovery inserts ExpressionNodeSupport
 *   when the next token is statement-start; strict mode currently absorbs the next
 *   call as the binary operand (CURRENTLY_ACCEPTS dual-path).
 * - incomplete unary until-condition only placeholders on expression terminators;
 *   a following NAME/call is absorbed as the unary operand on both paths.
 */
class LuaParserRecoveryRepeatUntilTddTest {

    @Test
    fun recoversMissingUntilAtEofAndKeepsBodyStatements() {
        assertSupportedCases(
            RepeatCase(
                name = "missing until at eof keeps multi-statement body",
                source = "repeat local value = 1 print(value)",
                requiredShapeFragments = listOf(
                    "Repeat(Block[Local(Id(value)=Const(1));CallStmt(Call(Id(print):Id(value)))]:ExpressionNodeSupport)"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("'until' expected")
            ),
            RepeatCase(
                name = "empty body missing until at eof still recovers",
                source = "repeat",
                requiredShapeFragments = listOf(
                    "Repeat(Block[]:ExpressionNodeSupport)"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("'until' expected")
            ),
            RepeatCase(
                name = "missing until after work keeps body call",
                source = "repeat work()",
                requiredShapeFragments = listOf(
                    "Repeat(Block[CallStmt(Call(Id(work):))]:ExpressionNodeSupport)"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("'until' expected")
            )
        )
    }

    @Test
    fun recoversMissingUntilAndKeepsLaterStatementsAfterSurroundingEnd() {
        // Body of repeat stops at END (block terminator). Missing until inserts a
        // placeholder; surrounding construct then consumes END; trailing print remains
        // a sibling statement (reachable later statement).
        assertSupportedCases(
            RepeatCase(
                name = "missing until inside do keeps trailing print after end",
                source = """
                    do
                      repeat work()
                    end
                    print(after)
                """.trimIndent(),
                requiredShapeFragments = listOf(
                    "Do(Block[Repeat(Block[CallStmt(Call(Id(work):))]:ExpressionNodeSupport)])",
                    "CallStmt(Call(Id(print):Id(after)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("'until' expected")
            ),
            RepeatCase(
                name = "missing until inside if then keeps trailing print after end",
                source = """
                    if ready then
                      repeat work()
                    end
                    print(ready)
                """.trimIndent(),
                requiredShapeFragments = listOf(
                    "Repeat(Block[CallStmt(Call(Id(work):))]:ExpressionNodeSupport)",
                    "CallStmt(Call(Id(print):Id(ready)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("'until' expected")
            ),
            RepeatCase(
                name = "missing until inside while keeps trailing local after end",
                source = """
                    while ready do
                      repeat tick()
                    end
                    local after = 1
                """.trimIndent(),
                requiredShapeFragments = listOf(
                    "Repeat(Block[CallStmt(Call(Id(tick):))]:ExpressionNodeSupport)",
                    "Local(Id(after)=Const(1))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("'until' expected")
            ),
            RepeatCase(
                name = "missing until inside function keeps trailing call after end",
                source = """
                    function run()
                      repeat work()
                    end
                    print(run)
                """.trimIndent(),
                requiredShapeFragments = listOf(
                    "Function(Id(run),Block[Repeat(Block[CallStmt(Call(Id(work):))]:ExpressionNodeSupport)])",
                    "CallStmt(Call(Id(print):Id(run)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("'until' expected")
            )
        )
    }

    @Test
    fun recoversIncompleteUntilConditionsWithoutThrowing() {
        assertSupportedCases(
            RepeatCase(
                name = "until at eof inserts placeholder condition",
                source = "repeat work() until",
                requiredShapeFragments = listOf(
                    "Repeat(Block[CallStmt(Call(Id(work):))]:ExpressionNodeSupport)"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            RepeatCase(
                name = "until binary condition missing rhs keeps placeholder",
                source = "repeat work() until value +",
                requiredShapeFragments = listOf(
                    "Repeat(Block[CallStmt(Call(Id(work):))]:Binary(+,Id(value),ExpressionNodeSupport))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            RepeatCase(
                name = "until unary condition missing operand keeps placeholder",
                source = "repeat work() until not",
                requiredShapeFragments = listOf(
                    "Repeat(Block[CallStmt(Call(Id(work):))]:Unary(not,ExpressionNodeSupport))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            RepeatCase(
                name = "until incomplete comparison keeps body and placeholder",
                source = "repeat count = count - 1 until count ==",
                requiredShapeFragments = listOf(
                    "Repeat(Block[Assign(Id(count)=Binary(-,Id(count),Const(1)))]:Binary(==,Id(count),ExpressionNodeSupport))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport")
            )
        )
    }

    @Test
    fun recoversNestedRepeatFormsWithBoundedRecovery() {
        assertSupportedCases(
            RepeatCase(
                name = "nested missing inner until: inner binds next until, outer recovers placeholder",
                source = """
                    repeat
                      repeat work()
                    until outer
                    print(done)
                """.trimIndent(),
                // Inner consumes `until outer`. Outer then continues its body until EOF,
                // so trailing print stays inside the outer Repeat body (not a sibling),
                // and outer recovers with ExpressionNodeSupport.
                requiredShapeFragments = listOf(
                    "Repeat(Block[Repeat(Block[CallStmt(Call(Id(work):))]:Id(outer));CallStmt(Call(Id(print):Id(done)))]:ExpressionNodeSupport)"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("'until' expected"),
                minRepeatNodes = 2,
                maxRepeatNodes = 2
            ),
            RepeatCase(
                name = "nested both missing until recover with two placeholders",
                source = """
                    repeat
                      repeat work()
                    print(done)
                """.trimIndent(),
                // Without any until, inner body absorbs trailing print to EOF; both
                // repeats recover with ExpressionNodeSupport placeholders.
                requiredShapeFragments = listOf(
                    "Repeat(Block[Repeat(Block[CallStmt(Call(Id(work):));CallStmt(Call(Id(print):Id(done)))]:ExpressionNodeSupport)]:ExpressionNodeSupport)"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("'until' expected"),
                minRepeatNodes = 2,
                maxRepeatNodes = 2
            ),
            RepeatCase(
                name = "triple nested missing innermost until stays bounded",
                source = """
                    repeat
                      repeat
                        repeat work()
                      until mid
                    until outer
                    print(done)
                """.trimIndent(),
                // Innermost consumes `until mid`; middle consumes `until outer`;
                // outermost body continues to EOF (print stays inside outermost),
                // then recovers with placeholder.
                requiredShapeFragments = listOf(
                    "Repeat(Block[CallStmt(Call(Id(work):))]:Id(mid))",
                    "Id(outer)",
                    "CallStmt(Call(Id(print):Id(done)))",
                    "ExpressionNodeSupport"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("'until' expected"),
                minRepeatNodes = 3,
                maxRepeatNodes = 3
            ),
            RepeatCase(
                name = "nested incomplete body assignment: outer until binds to incomplete inner",
                source = """
                    repeat
                      repeat value = value +
                    until outer
                    print(value)
                """.trimIndent(),
                // Incomplete `value +` recovers RHS placeholder; next until binds to the
                // *inner* repeat. Outer body then absorbs trailing print until EOF and
                // recovers with ExpressionNodeSupport.
                requiredShapeFragments = listOf(
                    "Assign(Id(value)=Binary(+,Id(value),ExpressionNodeSupport))",
                    "Id(outer)",
                    "CallStmt(Call(Id(print):Id(value)))",
                    "ExpressionNodeSupport"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("'until' expected"),
                minRepeatNodes = 2,
                maxRepeatNodes = 2
            )
        )
    }

    @Test
    fun recoversIncompleteBodiesInsideRepeatWithoutThrowing() {
        assertSupportedCases(
            RepeatCase(
                name = "body incomplete assignment then until keeps condition",
                source = "repeat value = value + until ready\nprint(value)",
                requiredShapeFragments = listOf(
                    "Repeat(Block[Assign(Id(value)=Binary(+,Id(value),ExpressionNodeSupport))]:Id(ready))",
                    "CallStmt(Call(Id(print):Id(value)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            RepeatCase(
                name = "body incomplete call then until keeps condition and trailing print",
                source = "repeat work( until ready\nprint(ready)",
                requiredShapeFragments = listOf(
                    "Id(ready)",
                    "CallStmt(Call(Id(print):Id(ready)))"
                ),
                warningFragments = listOf("')' expected")
            ),
            RepeatCase(
                name = "body incomplete local then missing until keeps placeholder",
                // Local explist uses recoverFirstStatementLineBreak=false, so a next-line
                // expression-start statement (`print(...)`) is absorbed as the local RHS.
                // Missing until still recovers with ExpressionNodeSupport condition.
                source = "repeat local value =\nprint(value)",
                requiredShapeFragments = listOf(
                    "Repeat(Block[Local(Id(value)=Call(Id(print):Id(value)))]:ExpressionNodeSupport)"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("'until' expected")
            )
        )
    }

    @Test
    fun exposesTypedStructureForRecoveredRepeatStatements() {
        val recovered = parseRecoveringWithoutThrow(
            """
                do
                  repeat work()
                end
                print(after)
            """.trimIndent()
        )

        val doStmt = assertIs<DoStatement>(recovered.body.statements[0])
        val repeat = assertIs<RepeatStatement>(doStmt.body.statements.single())
        assertIs<CallStatement>(repeat.body.statements.single())
        assertTrue(repeat.condition.bad)
        assertEquals("ExpressionNodeSupport", renderShape(repeat.condition))

        val trailing = assertIs<CallStatement>(recovered.body.statements[1])
        assertEquals("CallStmt(Call(Id(print):Id(after)))", renderShape(trailing))
    }

    @Test
    fun nestedMissingUntilRecoveryIsBoundedAndDeterministic() {
        val source = """
            repeat
              repeat
                repeat work()
              until mid
            until outer
            print(done)
        """.trimIndent()

        val first = parseWithDiagnosticsWithoutThrow(source, attempt = "first")
        val second = parseWithDiagnosticsWithoutThrow(source, attempt = "second")

        assertEquals(renderShape(first.chunk), renderShape(second.chunk), "nested recovery shape deterministic")
        assertEquals(
            first.recoveryDiagnostics.map { it.message },
            second.recoveryDiagnostics.map { it.message },
            "nested recovery diagnostics deterministic"
        )

        val repeats = countRepeatNodes(first.chunk)
        assertEquals(3, repeats, "triple nested recovery must stay exactly three Repeat nodes")
        assertTrue(
            first.recoveryDiagnostics.any { it.message.contains("'until' expected") },
            "expected missing-until diagnostic on outermost; got ${first.recoveryDiagnostics.map { it.message }}"
        )
        val shape = renderShape(first.chunk)
        assertTrue(
            shape.contains("CallStmt(Call(Id(print):Id(done)))"),
            "trailing print must remain reachable after nested recovery:\n$shape"
        )
        assertTrue(
            shape.contains("Repeat(Block[CallStmt(Call(Id(work):))]:Id(mid))"),
            "innermost should bind until mid:\n$shape"
        )
        assertTrue(
            shape.contains("ExpressionNodeSupport"),
            "outermost should recover with placeholder condition:\n$shape"
        )
        // Outer body absorbs trailing print after middle binds until outer.
        assertTrue(
            shape.contains("CallStmt(Call(Id(print):Id(done)))]:ExpressionNodeSupport") ||
                shape.contains(";CallStmt(Call(Id(print):Id(done)))]:ExpressionNodeSupport"),
            "trailing print must stay inside outermost body with placeholder condition:\n$shape"
        )
    }

    @Test
    fun recoveryDiagnosticsArePresentDeterministicAndDoNotThrow() {
        val cases = listOf(
            "repeat local value = 1 print(value)",
            "repeat work()",
            "do\n  repeat work()\nend\nprint(after)",
            "if ready then\n  repeat work()\nend\nprint(ready)",
            "repeat\n  repeat work()\nuntil outer\nprint(done)",
            "repeat work() until value +"
        )

        cases.forEach { source ->
            val first = parseWithDiagnosticsWithoutThrow(source, attempt = "first")
            val second = parseWithDiagnosticsWithoutThrow(source, attempt = "second")

            assertEquals(
                first.recoveryDiagnostics.map { it.message },
                second.recoveryDiagnostics.map { it.message },
                "diagnostic messages should be deterministic for: $source"
            )
            assertEquals(
                renderShape(first.chunk),
                renderShape(second.chunk),
                "recovered shape should be deterministic for: $source"
            )
            assertTrue(
                first.recoveryDiagnostics.isNotEmpty() ||
                    renderShape(first.chunk).contains("ExpressionNodeSupport"),
                "expected recovery signal (diagnostic or bad placeholder) for: $source"
            )
            first.recoveryDiagnostics.forEach { diagnostic ->
                assertTrue(diagnostic.message.isNotBlank(), "diagnostic message must be non-blank")
                assertTrue(
                    diagnostic.range.start.line >= 1 && diagnostic.range.start.column >= 1,
                    "diagnostic range start must be positive: ${diagnostic.range}"
                )
            }
        }
    }

    @Test
    fun strictParseRejectsIncompleteRepeatsWhileRecoveryDoesNotThrow() {
        val incompleteSources = listOf(
            "repeat local value = 1 print(value)",
            "repeat work()",
            "repeat",
            "do\n  repeat work()\nend\nprint(after)",
            "repeat work() until",
            "repeat work() until value +",
            "repeat\n  repeat work()\nuntil outer"
        )

        incompleteSources.forEach { source ->
            parseRecoveringWithoutThrow(source)

            val first = assertParseFails(LuaVersion.LUA_5_3, source, recovery = false)
            val second = assertParseFails(LuaVersion.LUA_5_3, source, recovery = false)
            assertEquals(first::class, second::class, "strict failure type for: $source")
            assertEquals(first.message, second.message, "strict failure message for: $source")
        }
    }

    @Test
    fun wellFormedRepeatUntilStillParsesCleanlyUnderRecovery() {
        val wellFormed = listOf(
            "repeat until ready",
            "repeat local value = 1 until ready",
            "repeat work() until done",
            "repeat count = count - 1 until count == 0",
            "repeat\n  repeat work()\n  until inner\nuntil outer",
            "do\n  repeat work() until ready\nend\nprint(after)"
        )

        wellFormed.forEach { source ->
            val result = parseWithDiagnosticsWithoutThrow(source)
            assertEquals(
                emptyList(),
                result.recoveryDiagnostics.map { it.message },
                "well-formed repeat-until should not emit recovery diagnostics: $source"
            )
            assertTrue(
                renderShape(result.chunk).contains("Repeat("),
                "well-formed source should produce Repeat shape: $source"
            )
            val strict = parse(LuaVersion.LUA_5_3, source, recovery = false)
            assertEquals(renderShape(result.chunk), renderShape(strict))
        }
    }

    @Test
    fun dualPathStrictAcceptsIncompleteBinaryUntilConditionWithNextLineStatement() {
        // Dual-path footgun: recovery inserts ExpressionNodeSupport for the missing
        // binary right and keeps next-line print as a sibling CallStmt; strict mode
        // currently absorbs print(...) as the binary right operand and accepts.
        assertSupportedCases(
            RepeatCase(
                name = "until binary missing rhs next-line print: recovery placeholder, strict absorbs",
                source = "repeat work() until value +\nprint(done)",
                requiredShapeFragments = listOf(
                    "Repeat(Block[CallStmt(Call(Id(work):))]:Binary(+,Id(value),ExpressionNodeSupport))",
                    "CallStmt(Call(Id(print):Id(done)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            RepeatCase(
                name = "until comparison missing rhs next-line print: recovery placeholder, strict absorbs",
                source = "repeat count = count - 1 until count ==\nprint(count)",
                requiredShapeFragments = listOf(
                    "Binary(==,Id(count),ExpressionNodeSupport)",
                    "CallStmt(Call(Id(print):Id(count)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            RepeatCase(
                name = "body incomplete binary assign next-line print then until: recovery sibling, strict absorbs",
                source = "repeat value = value +\nprint(value) until ready",
                requiredShapeFragments = listOf(
                    "Assign(Id(value)=Binary(+,Id(value),ExpressionNodeSupport))",
                    "CallStmt(Call(Id(print):Id(value)))",
                    "Id(ready)"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            RepeatCase(
                // Unary recovery only placeholders on expression terminators. A following
                // NAME/call is an expression start, so both recovery and strict absorb
                // print as the unary operand — honest CURRENTLY_ACCEPTS dual-path.
                name = "until unary missing operand absorbs following print on both paths",
                source = "repeat work() until not\nprint(done)",
                requiredShapeFragments = listOf(
                    "Repeat(Block[CallStmt(Call(Id(work):))]:Unary(not,Call(Id(print):Id(done))))"
                ),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            )
        )
    }

    @Test
    fun dualPathStrictRejectsMissingUntilWhileRecordingAcceptGaps() {
        val rejects = requiredCases().filter {
            it.strictParseExpectation == StrictParseExpectation.REJECTS
        }
        assertTrue(rejects.isNotEmpty(), "inventory must retain REJECTS missing-until/condition cases")
        rejects.forEach { case ->
            parseRecoveringWithoutThrow(case.source, case.version)
            val fail1 = assertParseFails(case.version, case.source, recovery = false)
            val fail2 = assertParseFails(case.version, case.source, recovery = false)
            assertEquals(fail1::class, fail2::class, "${case.name} strict failure type")
            assertEquals(fail1.message, fail2.message, "${case.name} strict failure message")
        }

        val accepts = requiredCases().filter {
            it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS
        }
        assertTrue(
            accepts.map { it.name }.any { it.contains("strict absorbs") || it.contains("both paths") },
            "inventory should document at least one CURRENTLY_ACCEPTS dual-path footgun"
        )
        accepts.forEach { case ->
            val first = parse(case.version, case.source, recovery = false)
            val second = parse(case.version, case.source, recovery = false)
            assertEquals(renderShape(first), renderShape(second), "${case.name} strict accepted shape")
        }
    }

    @Test
    fun corpusInventoryCoversRequiredRepeatUntilFamilies() {
        val inventory = requiredCases()
        assertEquals(22, inventory.size)

        val names = inventory.map { it.name }.toSet()
        assertTrue(names.any { it.contains("missing until at eof") || it.contains("missing until") })
        assertTrue(names.any { it.contains("trailing print") || it.contains("later") || it.contains("after end") })
        assertTrue(names.any { it.contains("nested") })
        assertTrue(names.any { it.contains("condition") || it.contains("until binary") || it.contains("until at eof") })
        assertTrue(names.any { it.contains("body") })
        assertTrue(
            inventory.any { it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS },
            "inventory must include dual-path CURRENTLY_ACCEPTS cases"
        )
        assertTrue(
            inventory.count { it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS } >= 4,
            "expected at least four dual-path CURRENTLY_ACCEPTS footguns"
        )
        assertTrue(
            inventory.any { it.strictParseExpectation == StrictParseExpectation.REJECTS },
            "inventory must retain REJECTS missing-until cases"
        )
    }

    // --- helpers -----------------------------------------------------------------

    private fun requiredCases(): List<RepeatCase> {
        return listOf(
            RepeatCase("missing until at eof keeps multi-statement body", "repeat local value = 1 print(value)", listOf("Repeat("), warningFragments = listOf("'until' expected")),
            RepeatCase("empty body missing until at eof still recovers", "repeat", listOf("Repeat(Block[]:ExpressionNodeSupport)"), warningFragments = listOf("'until' expected")),
            RepeatCase("missing until after work keeps body call", "repeat work()", listOf("CallStmt(Call(Id(work):))"), warningFragments = listOf("'until' expected")),
            RepeatCase("missing until inside do keeps trailing print after end", "do\n  repeat work()\nend\nprint(after)", listOf("CallStmt(Call(Id(print):Id(after)))"), warningFragments = listOf("'until' expected")),
            RepeatCase("missing until inside if then keeps trailing print after end", "if ready then\n  repeat work()\nend\nprint(ready)", listOf("CallStmt(Call(Id(print):Id(ready)))"), warningFragments = listOf("'until' expected")),
            RepeatCase("missing until inside while keeps trailing local after end", "while ready do\n  repeat tick()\nend\nlocal after = 1", listOf("Local(Id(after)=Const(1))"), warningFragments = listOf("'until' expected")),
            RepeatCase("missing until inside function keeps trailing call after end", "function run()\n  repeat work()\nend\nprint(run)", listOf("CallStmt(Call(Id(print):Id(run)))"), warningFragments = listOf("'until' expected")),
            RepeatCase("until at eof inserts placeholder condition", "repeat work() until", listOf("ExpressionNodeSupport"), badShapeFragments = listOf("ExpressionNodeSupport")),
            RepeatCase("until binary condition missing rhs keeps placeholder", "repeat work() until value +", listOf("Binary(+,Id(value),ExpressionNodeSupport)"), badShapeFragments = listOf("ExpressionNodeSupport")),
            RepeatCase("until unary condition missing operand keeps placeholder", "repeat work() until not", listOf("Unary(not,ExpressionNodeSupport)"), badShapeFragments = listOf("ExpressionNodeSupport")),
            RepeatCase("until incomplete comparison keeps body and placeholder", "repeat count = count - 1 until count ==", listOf("Binary(==,Id(count),ExpressionNodeSupport)"), badShapeFragments = listOf("ExpressionNodeSupport")),
            RepeatCase(
                "nested missing inner until: inner binds next until, outer recovers placeholder",
                "repeat\n  repeat work()\nuntil outer\nprint(done)",
                listOf("Repeat(Block[Repeat(Block[CallStmt(Call(Id(work):))]:Id(outer));CallStmt(Call(Id(print):Id(done)))]:ExpressionNodeSupport)"),
                warningFragments = listOf("'until' expected"),
                minRepeatNodes = 2,
                maxRepeatNodes = 2
            ),
            RepeatCase(
                "nested both missing until recover with two placeholders",
                "repeat\n  repeat work()\nprint(done)",
                listOf("Repeat(Block[Repeat(Block[CallStmt(Call(Id(work):));CallStmt(Call(Id(print):Id(done)))]:ExpressionNodeSupport)]:ExpressionNodeSupport)"),
                warningFragments = listOf("'until' expected"),
                minRepeatNodes = 2,
                maxRepeatNodes = 2
            ),
            RepeatCase("triple nested missing innermost until stays bounded", "repeat\n  repeat\n    repeat work()\n  until mid\nuntil outer\nprint(done)", listOf("CallStmt(Call(Id(print):Id(done)))"), warningFragments = listOf("'until' expected"), minRepeatNodes = 3, maxRepeatNodes = 3),
            RepeatCase("nested incomplete body assignment: outer until binds to incomplete inner", "repeat\n  repeat value = value +\nuntil outer\nprint(value)", listOf("Id(outer)"), warningFragments = listOf("'until' expected"), minRepeatNodes = 2, maxRepeatNodes = 2),
            RepeatCase("body incomplete assignment then until keeps condition", "repeat value = value + until ready\nprint(value)", listOf("Id(ready)"), badShapeFragments = listOf("ExpressionNodeSupport")),
            RepeatCase("body incomplete call then until keeps condition and trailing print", "repeat work( until ready\nprint(ready)", listOf("CallStmt(Call(Id(print):Id(ready)))"), warningFragments = listOf("')' expected")),
            RepeatCase("body incomplete local then missing until keeps placeholder", "repeat local value =\nprint(value)", listOf("Repeat(Block[Local(Id(value)=Call(Id(print):Id(value)))]:ExpressionNodeSupport)"), warningFragments = listOf("'until' expected")),
            // Dual-path CURRENTLY_ACCEPTS footguns (TASK-433): recovery keeps later
            // statement as sibling with ExpressionNodeSupport; strict absorbs next-line
            // call as expression operand and currently accepts the source.
            RepeatCase(
                name = "until binary missing rhs next-line print: recovery placeholder, strict absorbs",
                source = "repeat work() until value +\nprint(done)",
                requiredShapeFragments = listOf(
                    "Binary(+,Id(value),ExpressionNodeSupport)",
                    "CallStmt(Call(Id(print):Id(done)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            RepeatCase(
                name = "until comparison missing rhs next-line print: recovery placeholder, strict absorbs",
                source = "repeat count = count - 1 until count ==\nprint(count)",
                requiredShapeFragments = listOf(
                    "Binary(==,Id(count),ExpressionNodeSupport)",
                    "CallStmt(Call(Id(print):Id(count)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            RepeatCase(
                name = "body incomplete binary assign next-line print then until: recovery sibling, strict absorbs",
                source = "repeat value = value +\nprint(value) until ready",
                requiredShapeFragments = listOf(
                    "Assign(Id(value)=Binary(+,Id(value),ExpressionNodeSupport))",
                    "CallStmt(Call(Id(print):Id(value)))",
                    "Id(ready)"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            RepeatCase(
                name = "until unary missing operand absorbs following print on both paths",
                source = "repeat work() until not\nprint(done)",
                requiredShapeFragments = listOf(
                    "Unary(not,Call(Id(print):Id(done)))"
                ),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            )
        )
    }

    private fun assertSupportedCases(vararg cases: RepeatCase) {
        cases.forEach(::assertSupportedCase)
    }

    private fun assertSupportedCase(case: RepeatCase) {
        val first = recoverTwiceAndAssertDeterministic(case)
        assertShapeFragments(case, first.chunk)
        assertBadShapeFragments(case, first.chunk)
        assertWarningFragments(case, first.warnings)
        assertRepeatBounds(case, first.chunk)

        when (case.strictParseExpectation) {
            StrictParseExpectation.REJECTS -> {
                val fail1 = assertParseFails(case.version, case.source, recovery = false)
                val fail2 = assertParseFails(case.version, case.source, recovery = false)
                assertEquals(fail1::class, fail2::class, "${case.name} strict failure type")
                assertEquals(fail1.message, fail2.message, "${case.name} strict failure message")
            }
            StrictParseExpectation.CURRENTLY_ACCEPTS -> {
                val firstStrict = parse(case.version, case.source, recovery = false)
                val secondStrict = parse(case.version, case.source, recovery = false)
                assertEquals(
                    renderShape(firstStrict),
                    renderShape(secondStrict),
                    "${case.name} strict accepted shape"
                )
            }
        }
    }

    private fun recoverTwiceAndAssertDeterministic(case: RepeatCase): RecoveryRun {
        val first = parseRecoveringWithWarnings(case.version, case.source, case.name, "first")
        val second = parseRecoveringWithWarnings(case.version, case.source, case.name, "second")
        assertEquals(renderShape(first.chunk), renderShape(second.chunk), "${case.name} recovered shape")
        assertEquals(first.warnings, second.warnings, "${case.name} warning stream")
        return first
    }

    private fun parseRecoveringWithoutThrow(source: String, version: LuaVersion = LuaVersion.LUA_5_3): ChunkNode {
        return try {
            LuaParser(luaVersion = version, errorRecovery = true).parse(source)
        } catch (failure: Throwable) {
            throw AssertionError("recovery parse must not throw for: $source", failure)
        }
    }

    private fun parseWithDiagnosticsWithoutThrow(
        source: String,
        attempt: String = "parse",
        version: LuaVersion = LuaVersion.LUA_5_3,
    ): LuaParseResult {
        return try {
            LuaParser(luaVersion = version, errorRecovery = true).parseWithDiagnostics(source)
        } catch (failure: Throwable) {
            throw AssertionError("recovery parseWithDiagnostics must not throw during $attempt for: $source", failure)
        }
    }

    private fun parseRecoveringWithWarnings(
        version: LuaVersion,
        source: String,
        name: String,
        attempt: String,
    ): RecoveryRun {
        val result = try {
            LuaParser(luaVersion = version, errorRecovery = true).parseWithDiagnostics(source)
        } catch (failure: Throwable) {
            throw AssertionError("$name should recover without throwing during $attempt parse", failure)
        }
        return RecoveryRun(
            chunk = result.chunk,
            warnings = result.recoveryDiagnostics.map { it.message }
        )
    }

    private fun assertShapeFragments(case: RepeatCase, chunk: ChunkNode) {
        val shape = renderShape(chunk)
        case.requiredShapeFragments.forEach { expected ->
            assertTrue(
                shape.contains(expected),
                "${case.name} should keep '$expected' reachable in recovered shape:\n$shape"
            )
        }
    }

    private fun assertBadShapeFragments(case: RepeatCase, chunk: ChunkNode) {
        if (case.badShapeFragments.isEmpty()) return
        val shape = renderShape(chunk)
        case.badShapeFragments.forEach { expected ->
            assertTrue(
                shape.contains(expected),
                "${case.name} should mark recovered node containing '$expected' as present; shape:\n$shape"
            )
        }
    }

    private fun assertWarningFragments(case: RepeatCase, warnings: List<String>) {
        case.warningFragments.forEach { expected ->
            assertTrue(
                warnings.any { it.contains(expected) },
                "${case.name} should emit warning containing '$expected'; actual warnings: $warnings"
            )
        }
    }

    private fun assertRepeatBounds(case: RepeatCase, chunk: ChunkNode) {
        if (case.minRepeatNodes == null && case.maxRepeatNodes == null) return
        val count = countRepeatNodes(chunk)
        case.minRepeatNodes?.let {
            assertTrue(count >= it, "${case.name} expected at least $it Repeat nodes, got $count")
        }
        case.maxRepeatNodes?.let {
            assertTrue(count <= it, "${case.name} expected at most $it Repeat nodes (bounded), got $count")
        }
    }

    private fun countRepeatNodes(chunk: ChunkNode): Int {
        var count = 0
        fun walk(node: Any?) {
            when (node) {
                is RepeatStatement -> {
                    count++
                    walk(node.body)
                    walk(node.condition)
                }
                is ChunkNode -> walk(node.body)
                is io.github.dingyi222666.luaparser.parser.ast.node.BlockNode -> {
                    node.statements.forEach(::walk)
                    node.returnStatement?.let(::walk)
                }
                is DoStatement -> walk(node.body)
                is IfStatement -> node.causes.forEach(::walk)
                is io.github.dingyi222666.luaparser.parser.ast.node.IfClause -> {
                    walk(node.condition)
                    walk(node.body)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement -> {
                    walk(node.condition)
                    walk(node.body)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration -> walk(node.body)
                is LocalStatement -> {
                    node.init.forEach(::walk)
                    node.variables.forEach(::walk)
                }
                is CallStatement -> walk(node.expression)
                is io.github.dingyi222666.luaparser.parser.ast.node.CallExpression -> {
                    walk(node.base)
                    node.arguments.forEach(::walk)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.BinaryExpression -> {
                    walk(node.left)
                    walk(node.right)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.UnaryExpression -> walk(node.arg)
                is io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement -> {
                    node.init.forEach(::walk)
                    node.variables.forEach(::walk)
                }
            }
        }
        walk(chunk)
        return count
    }

    private enum class StrictParseExpectation {
        REJECTS,
        CURRENTLY_ACCEPTS,
    }

    private data class RepeatCase(
        val name: String,
        val source: String,
        val requiredShapeFragments: List<String>,
        val version: LuaVersion = LuaVersion.LUA_5_3,
        val badShapeFragments: List<String> = emptyList(),
        val warningFragments: List<String> = emptyList(),
        val strictParseExpectation: StrictParseExpectation = StrictParseExpectation.REJECTS,
        val minRepeatNodes: Int? = null,
        val maxRepeatNodes: Int? = null,
    )

    private data class RecoveryRun(
        val chunk: ChunkNode,
        val warnings: List<String>,
    )
}
