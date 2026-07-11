package parser.recovery

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaParseResult
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import parser.assertParseFails
import parser.parse
import parser.renderShape

/**
 * Focused dual-path recovery corpus for numeric `for Name = exp, exp [, exp] do block end`
 * (TASK-434; complements TASK-283 [LuaParserRecoveryNumericForEndTddTest] missing-`end`
 * suite and the sparse "missing numeric for do" fixture in [LuaParserRecoveryTddTest]).
 *
 * Acceptance:
 * - Missing `do` recovers a ForNumeric, keeps loop body reachable, emits
 *   `The <do> expected`.
 * - Incomplete / missing start/end/step expressions recover with ExpressionNodeSupport
 *   when product inserts placeholders; later body statements remain reachable when
 *   `end` is present.
 * - Nested numeric-for recovery stays bounded.
 * - Dual-path [StrictParseExpectation] documents honest CURRENTLY_ACCEPTS footguns
 *   (recovery sibling + placeholder vs strict absorb of next-line statement as
 *   expression operand) and retained REJECTS incompletes.
 * - Well-formed numeric-for stays clean under recovery and matches strict shape.
 * - Test-only; no production LuaParser edits.
 *
 * Product goldens (aligned with [LuaParser.parseForNumericStatement] /
 * [LuaParser.parseForBody] / binary/unary recovery):
 * - missing `do` after bounds warns `The <do> expected` and still parses the body;
 * - missing `end` recovers with `<end> expected` and keeps body statements inside
 *   the for (same residual as other block constructs);
 * - incomplete binary RHS after line-break + statement-start inserts
 *   ExpressionNodeSupport under recovery; strict mode currently absorbs the next
 *   call as the binary operand (CURRENTLY_ACCEPTS dual-path);
 * - incomplete unary only placeholders on expression terminators; a following
 *   NAME/call is absorbed as the unary operand on both paths.
 */
class LuaParserRecoveryNumericForTddTest {

    @Test
    fun recoversMissingDoAndKeepsLoopBody() {
        assertSupportedCases(
            NumericForCase(
                name = "missing do keeps call body",
                source = "for i = 1, 3 print(i) end",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),Const(3),null:Block[CallStmt(Call(Id(print):Id(i)))])"
                ),
                warningFragments = listOf("The <do> expected")
            ),
            NumericForCase(
                name = "missing do with step keeps multi-statement body",
                source = "for i = 1, 10, 2 use(i) print(i) end",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),Const(10),Const(2):Block[CallStmt(Call(Id(use):Id(i)));CallStmt(Call(Id(print):Id(i)))])"
                ),
                warningFragments = listOf("The <do> expected")
            ),
            NumericForCase(
                name = "missing do keeps local and call body",
                source = "for i = start, finish local copy = i work(copy) end",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Id(start),Id(finish),null:Block[Local(Id(copy)=Id(i));CallStmt(Call(Id(work):Id(copy)))])"
                ),
                warningFragments = listOf("The <do> expected")
            ),
            NumericForCase(
                name = "missing do keeps following print after end",
                source = "for i = 1, 3 use(i) end\nprint(i)",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),Const(3),null:Block[CallStmt(Call(Id(use):Id(i)))])",
                    "CallStmt(Call(Id(print):Id(i)))"
                ),
                warningFragments = listOf("The <do> expected")
            )
        )
    }

    @Test
    fun recoversMissingDoCombinedWithMissingEnd() {
        assertSupportedCases(
            NumericForCase(
                name = "missing do and end at eof keeps body statements",
                source = "for i = 1, 3 work(i) print(\"after\")",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),Const(3),null:Block[CallStmt(Call(Id(work):Id(i)));CallStmt(Call(Id(print):Const(\"after\")))])"
                ),
                warningFragments = listOf("The <do> expected", "<end> expected")
            ),
            NumericForCase(
                name = "missing do and end with step keeps multi-statement body",
                source = "for i = 1, 10, 2 use(i) print(i)",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),Const(10),Const(2):Block[CallStmt(Call(Id(use):Id(i)));CallStmt(Call(Id(print):Id(i)))])"
                ),
                warningFragments = listOf("The <do> expected", "<end> expected")
            ),
            NumericForCase(
                name = "missing do and end keeps local and call body",
                source = "for i = 1, n local copy = i work(copy)",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),Id(n),null:Block[Local(Id(copy)=Id(i));CallStmt(Call(Id(work):Id(copy)))])"
                ),
                warningFragments = listOf("The <do> expected", "<end> expected")
            )
        )
    }

    @Test
    fun recoversIncompleteBoundsAndStepWithoutThrowing() {
        assertSupportedCases(
            NumericForCase(
                name = "missing start expression before comma inserts placeholder",
                source = "for i = , 3 do use(i) end",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=ExpressionNodeSupport,Const(3),null:Block[CallStmt(Call(Id(use):Id(i)))])"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            NumericForCase(
                name = "end binary missing rhs before do keeps placeholder",
                source = "for i = 1, value + do use(i) end",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),Binary(+,Id(value),ExpressionNodeSupport),null:Block[CallStmt(Call(Id(use):Id(i)))])"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            NumericForCase(
                name = "step unary missing operand before do keeps placeholder",
                source = "for i = 1, 10, not do use(i) end",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),Const(10),Unary(not,ExpressionNodeSupport):Block[CallStmt(Call(Id(use):Id(i)))])"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            NumericForCase(
                name = "missing end expression before do inserts placeholder",
                source = "for i = 1, do use(i) end",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),ExpressionNodeSupport,null:Block[CallStmt(Call(Id(use):Id(i)))])"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            NumericForCase(
                name = "missing step after trailing comma before do inserts placeholder",
                source = "for i = 1, 10, do use(i) end",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),Const(10),ExpressionNodeSupport:Block[CallStmt(Call(Id(use):Id(i)))])"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport")
            )
        )
    }

    @Test
    fun recoversNestedNumericForMissingDoForms() {
        assertSupportedCases(
            NumericForCase(
                name = "nested missing inner do keeps outer end and following print",
                source = "for i = 1, 3 do for j = 1, 2 use(i, j) end end\nprint(i)",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),Const(3),null:Block[ForNumeric(Id(j)=Const(1),Const(2),null:Block[CallStmt(Call(Id(use):Id(i),Id(j)))])])",
                    "CallStmt(Call(Id(print):Id(i)))"
                ),
                warningFragments = listOf("The <do> expected"),
                minForNumericNodes = 2,
                maxForNumericNodes = 2
            ),
            NumericForCase(
                name = "nested missing outer do keeps closed inner and following print",
                source = "for i = 1, 3 for j = 1, 2 do use(i, j) end end\nprint(i)",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),Const(3),null:Block[ForNumeric(Id(j)=Const(1),Const(2),null:Block[CallStmt(Call(Id(use):Id(i),Id(j)))])])",
                    "CallStmt(Call(Id(print):Id(i)))"
                ),
                warningFragments = listOf("The <do> expected"),
                minForNumericNodes = 2,
                maxForNumericNodes = 2
            ),
            NumericForCase(
                name = "nested both missing do stays bounded",
                source = "for i = 1, 3 for j = 1, 2 use(i, j) end end\nprint(i)",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),Const(3),null:",
                    "ForNumeric(Id(j)=Const(1),Const(2),null:",
                    "CallStmt(Call(Id(print):Id(i)))"
                ),
                warningFragments = listOf("The <do> expected"),
                minForNumericNodes = 2,
                maxForNumericNodes = 2
            )
        )
    }

    @Test
    fun recoversIncompleteBodiesInsideNumericForWithoutThrowing() {
        assertSupportedCases(
            NumericForCase(
                name = "body incomplete assignment then end keeps following print",
                source = "for i = 1, 3 do total = total + end\nprint(total)",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),Const(3),null:Block[Assign(Id(total)=Binary(+,Id(total),ExpressionNodeSupport))])",
                    "CallStmt(Call(Id(print):Id(total)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            NumericForCase(
                name = "body incomplete call then end keeps following print",
                source = "for i = 1, 3 do use(i end\nprint(i)",
                requiredShapeFragments = listOf(
                    "ForNumeric(",
                    "CallStmt(Call(Id(print):Id(i)))"
                ),
                warningFragments = listOf("')' expected")
            ),
            NumericForCase(
                name = "body incomplete return then end keeps following print",
                source = "for i = 1, 3 do return i + end\nprint(i)",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),Const(3),null:Block[Return(Binary(+,Id(i),ExpressionNodeSupport))])",
                    "CallStmt(Call(Id(print):Id(i)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport")
            )
        )
    }

    @Test
    fun exposesTypedForNumericStructureAfterMissingDoRecovery() {
        val chunk = parseRecoveringWithoutThrow(
            "for i = 1, 3, 1 use(i) end\nprint(i)"
        )

        val forStmt = assertIs<ForNumericStatement>(chunk.body.statements[0])
        assertEquals("Id(i)", renderShape(forStmt.variable))
        assertEquals("Const(1)", renderShape(forStmt.start))
        assertEquals("Const(3)", renderShape(forStmt.end))
        assertEquals("Const(1)", renderShape(forStmt.step!!))
        assertIs<CallStatement>(forStmt.body.statements.single())
        assertIs<CallStatement>(chunk.body.statements[1])
    }

    @Test
    fun recoveryDiagnosticsArePresentDeterministicAndDoNotThrow() {
        val sources = listOf(
            "for i = 1, 3 print(i) end",
            "for i = 1, 10, 2 use(i) print(i) end",
            "for i = 1, 3 work(i) print(\"after\")",
            "for i = 1, value + do use(i) end",
            "for i = 1, 10, do use(i) end",
            "for i = 1, 3 for j = 1, 2 use(i, j) end end\nprint(i)"
        )

        sources.forEach { source ->
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
    fun dualPathStrictAcceptsIncompleteBinaryInsideNumericForWithNextLineStatement() {
        // Dual-path footguns: recovery inserts ExpressionNodeSupport for the missing
        // binary right and keeps next-line print as a sibling CallStmt; strict mode
        // currently absorbs print(...) as the binary right operand and accepts.
        assertSupportedCases(
            NumericForCase(
                name = "body incomplete binary assign next-line print: recovery sibling, strict absorbs",
                source = "for i = 1, 3 do total = total +\nprint(total) end",
                requiredShapeFragments = listOf(
                    "Assign(Id(total)=Binary(+,Id(total),ExpressionNodeSupport))",
                    "CallStmt(Call(Id(print):Id(total)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            NumericForCase(
                name = "body incomplete comparison assign next-line print: recovery sibling, strict absorbs",
                source = "for i = 1, 3 do ok = i ==\nprint(ok) end",
                requiredShapeFragments = listOf(
                    "Assign(Id(ok)=Binary(==,Id(i),ExpressionNodeSupport))",
                    "CallStmt(Call(Id(print):Id(ok)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            NumericForCase(
                name = "end bound binary missing rhs next-line print before do: recovery placeholder, strict absorbs",
                source = "for i = 1, value +\nprint(i) do use(i) end",
                requiredShapeFragments = listOf(
                    "Binary(+,Id(value),ExpressionNodeSupport)"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                // Recovery inserts placeholder for end bound then continues; residual
                // body layout depends on whether print is absorbed into body after missing
                // do — require at least the placeholder + ForNumeric residual.
                warningFragments = listOf("The <do> expected"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            NumericForCase(
                // Unary recovery only placeholders on expression terminators. A following
                // NAME/call is an expression start, so both recovery and strict absorb
                // print as the unary operand — honest CURRENTLY_ACCEPTS dual-path.
                name = "body incomplete unary return absorbs following print on both paths",
                source = "for i = 1, 3 do return not\nprint(i) end",
                requiredShapeFragments = listOf(
                    "Return(Unary(not,Call(Id(print):Id(i))))"
                ),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            )
        )
    }

    @Test
    fun dualPathStrictRejectsIncompleteNumericForWhileRecordingAcceptGaps() {
        val rejects = requiredCases().filter {
            it.strictParseExpectation == StrictParseExpectation.REJECTS
        }
        assertTrue(rejects.isNotEmpty(), "inventory must retain REJECTS incomplete numeric-for cases")
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
    fun wellFormedNumericForStillParsesCleanlyUnderRecovery() {
        val wellFormed = listOf(
            "for i = 1, 3 do use(i) end",
            "for i = 1, 10, 2 do consume(i) end\nlocal after = 1",
            "for i = start, finish, step do handle(i) end\nprint(finish)",
            "for i = 1, n do local copy = i work(copy) end\nprint(n)",
            "for i = 1, 3 do for j = 1, 2 do use(i, j) end end\nprint(done)"
        )

        wellFormed.forEach { source ->
            val result = parseWithDiagnosticsWithoutThrow(source)
            assertEquals(
                emptyList(),
                result.recoveryDiagnostics.map { it.message },
                "well-formed numeric-for should not emit recovery diagnostics: $source"
            )
            assertTrue(
                renderShape(result.chunk).contains("ForNumeric("),
                "well-formed source should produce ForNumeric shape: $source"
            )
            val strict = parse(LuaVersion.LUA_5_3, source, recovery = false)
            assertEquals(renderShape(result.chunk), renderShape(strict))
        }
    }

    @Test
    fun corpusInventoryCoversRequiredNumericForFamilies() {
        val inventory = requiredCases()
        assertEquals(22, inventory.size)

        val names = inventory.map { it.name }.toSet()
        assertTrue(names.any { it.contains("missing do") })
        assertTrue(names.any { it.contains("step") || it.contains("bound") || it.contains("start") || it.contains("end bound") || it.contains("missing end expression") || it.contains("missing step") })
        assertTrue(names.any { it.contains("nested") })
        assertTrue(names.any { it.contains("body") })
        assertTrue(names.any { it.contains("following print") || it.contains("later") || it.contains("after end") })
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
            "inventory must retain REJECTS incomplete numeric-for cases"
        )
        assertTrue(
            inventory.any { case ->
                case.warningFragments.any { it.contains("The <do> expected") }
            },
            "inventory must cover missing-do diagnostics"
        )
    }

    // --- helpers -----------------------------------------------------------------

    private fun requiredCases(): List<NumericForCase> {
        return listOf(
            NumericForCase(
                name = "missing do keeps call body",
                source = "for i = 1, 3 print(i) end",
                requiredShapeFragments = listOf("ForNumeric(Id(i)=Const(1),Const(3),null:"),
                warningFragments = listOf("The <do> expected")
            ),
            NumericForCase(
                name = "missing do with step keeps multi-statement body",
                source = "for i = 1, 10, 2 use(i) print(i) end",
                requiredShapeFragments = listOf("Const(2)"),
                warningFragments = listOf("The <do> expected")
            ),
            NumericForCase(
                name = "missing do keeps local and call body",
                source = "for i = start, finish local copy = i work(copy) end",
                requiredShapeFragments = listOf("Local(Id(copy)=Id(i))"),
                warningFragments = listOf("The <do> expected")
            ),
            NumericForCase(
                name = "missing do keeps following print after end",
                source = "for i = 1, 3 use(i) end\nprint(i)",
                requiredShapeFragments = listOf("CallStmt(Call(Id(print):Id(i)))"),
                warningFragments = listOf("The <do> expected")
            ),
            NumericForCase(
                name = "missing do and end at eof keeps body statements",
                source = "for i = 1, 3 work(i) print(\"after\")",
                requiredShapeFragments = listOf("CallStmt(Call(Id(print):Const(\"after\")))"),
                warningFragments = listOf("The <do> expected", "<end> expected")
            ),
            NumericForCase(
                name = "missing do and end with step keeps multi-statement body",
                source = "for i = 1, 10, 2 use(i) print(i)",
                requiredShapeFragments = listOf("Const(2)"),
                warningFragments = listOf("The <do> expected", "<end> expected")
            ),
            NumericForCase(
                name = "missing do and end keeps local and call body",
                source = "for i = 1, n local copy = i work(copy)",
                requiredShapeFragments = listOf("Local(Id(copy)=Id(i))"),
                warningFragments = listOf("The <do> expected", "<end> expected")
            ),
            NumericForCase(
                name = "missing start expression before comma inserts placeholder",
                source = "for i = , 3 do use(i) end",
                requiredShapeFragments = listOf("ExpressionNodeSupport"),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            NumericForCase(
                name = "end binary missing rhs before do keeps placeholder",
                source = "for i = 1, value + do use(i) end",
                requiredShapeFragments = listOf("Binary(+,Id(value),ExpressionNodeSupport)"),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            NumericForCase(
                name = "step unary missing operand before do keeps placeholder",
                source = "for i = 1, 10, not do use(i) end",
                requiredShapeFragments = listOf("Unary(not,ExpressionNodeSupport)"),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            NumericForCase(
                name = "missing end expression before do inserts placeholder",
                source = "for i = 1, do use(i) end",
                requiredShapeFragments = listOf("ExpressionNodeSupport"),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            NumericForCase(
                name = "missing step after trailing comma before do inserts placeholder",
                source = "for i = 1, 10, do use(i) end",
                requiredShapeFragments = listOf("ExpressionNodeSupport"),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            NumericForCase(
                name = "nested missing inner do keeps outer end and following print",
                source = "for i = 1, 3 do for j = 1, 2 use(i, j) end end\nprint(i)",
                requiredShapeFragments = listOf("ForNumeric(Id(j)=Const(1),Const(2),null:"),
                warningFragments = listOf("The <do> expected"),
                minForNumericNodes = 2,
                maxForNumericNodes = 2
            ),
            NumericForCase(
                name = "nested missing outer do keeps closed inner and following print",
                source = "for i = 1, 3 for j = 1, 2 do use(i, j) end end\nprint(i)",
                requiredShapeFragments = listOf("CallStmt(Call(Id(print):Id(i)))"),
                warningFragments = listOf("The <do> expected"),
                minForNumericNodes = 2,
                maxForNumericNodes = 2
            ),
            NumericForCase(
                name = "nested both missing do stays bounded",
                source = "for i = 1, 3 for j = 1, 2 use(i, j) end end\nprint(i)",
                requiredShapeFragments = listOf("ForNumeric(Id(j)=Const(1),Const(2),null:"),
                warningFragments = listOf("The <do> expected"),
                minForNumericNodes = 2,
                maxForNumericNodes = 2
            ),
            NumericForCase(
                name = "body incomplete assignment then end keeps following print",
                source = "for i = 1, 3 do total = total + end\nprint(total)",
                requiredShapeFragments = listOf("Binary(+,Id(total),ExpressionNodeSupport)"),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            NumericForCase(
                name = "body incomplete call then end keeps following print",
                source = "for i = 1, 3 do use(i end\nprint(i)",
                requiredShapeFragments = listOf("CallStmt(Call(Id(print):Id(i)))"),
                warningFragments = listOf("')' expected")
            ),
            NumericForCase(
                name = "body incomplete return then end keeps following print",
                source = "for i = 1, 3 do return i + end\nprint(i)",
                requiredShapeFragments = listOf("Return(Binary(+,Id(i),ExpressionNodeSupport))"),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            // Dual-path CURRENTLY_ACCEPTS footguns (TASK-434)
            NumericForCase(
                name = "body incomplete binary assign next-line print: recovery sibling, strict absorbs",
                source = "for i = 1, 3 do total = total +\nprint(total) end",
                requiredShapeFragments = listOf(
                    "Assign(Id(total)=Binary(+,Id(total),ExpressionNodeSupport))",
                    "CallStmt(Call(Id(print):Id(total)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            NumericForCase(
                name = "body incomplete comparison assign next-line print: recovery sibling, strict absorbs",
                source = "for i = 1, 3 do ok = i ==\nprint(ok) end",
                requiredShapeFragments = listOf(
                    "Assign(Id(ok)=Binary(==,Id(i),ExpressionNodeSupport))",
                    "CallStmt(Call(Id(print):Id(ok)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            NumericForCase(
                name = "end bound binary missing rhs next-line print before do: recovery placeholder, strict absorbs",
                source = "for i = 1, value +\nprint(i) do use(i) end",
                requiredShapeFragments = listOf("Binary(+,Id(value),ExpressionNodeSupport)"),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("The <do> expected"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            NumericForCase(
                name = "body incomplete unary return absorbs following print on both paths",
                source = "for i = 1, 3 do return not\nprint(i) end",
                requiredShapeFragments = listOf("Return(Unary(not,Call(Id(print):Id(i))))"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            )
        )
    }

    private fun assertSupportedCases(vararg cases: NumericForCase) {
        cases.forEach(::assertSupportedCase)
    }

    private fun assertSupportedCase(case: NumericForCase) {
        val first = recoverTwiceAndAssertDeterministic(case)
        assertShapeFragments(case, first.chunk)
        assertBadShapeFragments(case, first.chunk)
        assertWarningFragments(case, first.warnings)
        assertForNumericBounds(case, first.chunk)

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

    private fun recoverTwiceAndAssertDeterministic(case: NumericForCase): RecoveryRun {
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

    private fun assertShapeFragments(case: NumericForCase, chunk: ChunkNode) {
        val shape = renderShape(chunk)
        case.requiredShapeFragments.forEach { expected ->
            assertTrue(
                shape.contains(expected),
                "${case.name} should keep '$expected' reachable in recovered shape:\n$shape"
            )
        }
    }

    private fun assertBadShapeFragments(case: NumericForCase, chunk: ChunkNode) {
        if (case.badShapeFragments.isEmpty()) return
        val shape = renderShape(chunk)
        case.badShapeFragments.forEach { expected ->
            assertTrue(
                shape.contains(expected),
                "${case.name} should mark recovered residual containing '$expected'; shape:\n$shape"
            )
        }
    }

    private fun assertWarningFragments(case: NumericForCase, warnings: List<String>) {
        case.warningFragments.forEach { expected ->
            assertTrue(
                warnings.any { it.contains(expected) },
                "${case.name} should emit warning containing '$expected'; actual warnings: $warnings"
            )
        }
    }

    private fun assertForNumericBounds(case: NumericForCase, chunk: ChunkNode) {
        if (case.minForNumericNodes == null && case.maxForNumericNodes == null) return
        val count = countForNumericNodes(chunk)
        case.minForNumericNodes?.let {
            assertTrue(count >= it, "${case.name} expected >= $it ForNumeric nodes, got $count")
        }
        case.maxForNumericNodes?.let {
            assertTrue(count <= it, "${case.name} expected <= $it ForNumeric nodes, got $count")
        }
    }

    private fun countForNumericNodes(chunk: ChunkNode): Int {
        var count = 0
        fun walk(node: Any?) {
            when (node) {
                null -> Unit
                is ForNumericStatement -> {
                    count += 1
                    walk(node.body)
                }
                is ChunkNode -> walk(node.body)
                is io.github.dingyi222666.luaparser.parser.ast.node.BlockNode -> {
                    node.statements.forEach(::walk)
                    walk(node.returnStatement)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.DoStatement -> walk(node.body)
                is io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement -> {
                    walk(node.condition)
                    walk(node.body)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement -> {
                    walk(node.body)
                    walk(node.condition)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.IfStatement -> node.causes.forEach(::walk)
                is io.github.dingyi222666.luaparser.parser.ast.node.IfClause -> {
                    walk(node.condition)
                    walk(node.body)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration -> walk(node.body)
                is io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement -> walk(node.body)
                is io.github.dingyi222666.luaparser.parser.ast.node.CallStatement -> walk(node.expression)
                is io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement -> {
                    node.init.forEach(::walk)
                    node.variables.forEach(::walk)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement -> {
                    node.init.forEach(::walk)
                    node.variables.forEach(::walk)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.ReturnStatement -> node.arguments.forEach(::walk)
                is io.github.dingyi222666.luaparser.parser.ast.node.CallExpression -> {
                    walk(node.base)
                    node.arguments.forEach(::walk)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.BinaryExpression -> {
                    walk(node.left)
                    walk(node.right)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.UnaryExpression -> walk(node.arg)
                else -> Unit
            }
        }
        walk(chunk)
        return count
    }

    private enum class StrictParseExpectation {
        REJECTS,
        CURRENTLY_ACCEPTS,
    }

    private data class NumericForCase(
        val name: String,
        val source: String,
        val version: LuaVersion = LuaVersion.LUA_5_3,
        val requiredShapeFragments: List<String>,
        val badShapeFragments: List<String> = emptyList(),
        val warningFragments: List<String> = emptyList(),
        val strictParseExpectation: StrictParseExpectation = StrictParseExpectation.REJECTS,
        val minForNumericNodes: Int? = null,
        val maxForNumericNodes: Int? = null,
    )

    private data class RecoveryRun(
        val chunk: ChunkNode,
        val warnings: List<String>,
    )
}
