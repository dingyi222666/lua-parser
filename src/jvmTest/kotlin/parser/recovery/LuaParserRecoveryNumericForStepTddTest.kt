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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import parser.assertParseFails
import parser.parse
import parser.renderShape

/**
 * Focused dual-path recovery corpus for the optional step expression of numeric
 * `for Name = exp, exp [, exp] do block end` (TASK-464).
 *
 * Complements:
 * - [LuaParserRecoveryNumericForTddTest] (TASK-434) — missing-`do` / bounds / sparse step
 * - [LuaParserRecoveryNumericForEndTddTest] (TASK-283) — missing-`end` with optional step present
 *
 * This suite deepens **missing / incomplete step** coverage after the trailing comma
 * that introduces the optional third expression:
 * - bare trailing comma before `do` / statement / EOF → step ExpressionNodeSupport
 * - incomplete step binary / unary before `do` → placeholder residual
 * - step incomplete combined with missing `do` / missing `end`
 * - nested numeric-for with incomplete / missing step stays bounded
 * - dual-path [StrictParseExpectation] CURRENTLY_ACCEPTS footguns (recovery placeholder
 *   vs strict absorb of next-line statement as step operand)
 * - well-formed numeric-for with step stays clean under recovery and matches strict shape
 *
 * Product goldens (aligned with [LuaParser.parseForNumericStatement] /
 * [LuaParser.parseExpressionOrMissing] / binary/unary recovery):
 * - optional step is only parsed when a COMMA is consumed after the end bound;
 * - missing step after that COMMA inserts ExpressionNodeSupport under recovery
 *   (`isExpressionTerminator` / non-expression-start at `do` / EOF / keywords);
 * - incomplete binary RHS after line-break + statement-start inserts
 *   ExpressionNodeSupport under recovery; strict mode currently absorbs the next
 *   call as the binary operand (CURRENTLY_ACCEPTS dual-path);
 * - incomplete unary only placeholders on expression terminators; a following
 *   NAME/call is absorbed as the unary operand on both paths.
 *
 * Test-only; no production LuaParser edits. Host android.jar: SDK android-35 only; never G:/.
 */
class LuaParserRecoveryNumericForStepTddTest {

    @Test
    fun recoversMissingStepAfterTrailingCommaBeforeDo() {
        assertSupportedCases(
            StepCase(
                name = "missing step after trailing comma before do inserts placeholder",
                source = "for i = 1, 10, do use(i) end",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),Const(10),ExpressionNodeSupport:Block[CallStmt(Call(Id(use):Id(i)))])"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                requireStepPresent = true
            ),
            StepCase(
                name = "missing step after trailing comma with identifier bounds before do",
                source = "for i = start, finish, do handle(i) end",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Id(start),Id(finish),ExpressionNodeSupport:Block[CallStmt(Call(Id(handle):Id(i)))])"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                requireStepPresent = true
            ),
            StepCase(
                name = "missing step after trailing comma keeps following print after end",
                source = "for i = 1, 3, do use(i) end\nprint(i)",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),Const(3),ExpressionNodeSupport:Block[CallStmt(Call(Id(use):Id(i)))])",
                    "CallStmt(Call(Id(print):Id(i)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                requireStepPresent = true
            ),
            StepCase(
                name = "missing step after trailing comma keeps multi-statement body",
                source = "for i = 1, 10, do use(i) print(i) end",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),Const(10),ExpressionNodeSupport:Block[CallStmt(Call(Id(use):Id(i)));CallStmt(Call(Id(print):Id(i)))])"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                requireStepPresent = true
            )
        )
    }

    @Test
    fun recoversIncompleteStepExpressionsBeforeDo() {
        assertSupportedCases(
            StepCase(
                name = "step binary missing rhs before do keeps placeholder",
                source = "for i = 1, 10, step + do use(i) end",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),Const(10),Binary(+,Id(step),ExpressionNodeSupport):Block[CallStmt(Call(Id(use):Id(i)))])"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                requireStepPresent = true
            ),
            StepCase(
                name = "step unary missing operand before do keeps placeholder",
                source = "for i = 1, 10, not do use(i) end",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),Const(10),Unary(not,ExpressionNodeSupport):Block[CallStmt(Call(Id(use):Id(i)))])"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                requireStepPresent = true
            ),
            StepCase(
                name = "step comparison missing rhs before do keeps placeholder",
                source = "for i = 1, 10, n == do use(i) end",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),Const(10),Binary(==,Id(n),ExpressionNodeSupport):Block[CallStmt(Call(Id(use):Id(i)))])"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                requireStepPresent = true
            ),
            StepCase(
                name = "step concat missing rhs before do keeps placeholder",
                source = "for i = 1, 10, prefix .. do use(i) end",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),Const(10),Binary(..,Id(prefix),ExpressionNodeSupport):Block[CallStmt(Call(Id(use):Id(i)))])"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                requireStepPresent = true
            ),
            StepCase(
                name = "step unary minus missing operand before do keeps placeholder",
                source = "for i = 3, 1, - do countdown(i) end",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(3),Const(1),Unary(-,ExpressionNodeSupport):Block[CallStmt(Call(Id(countdown):Id(i)))])"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                requireStepPresent = true
            )
        )
    }

    @Test
    fun recoversMissingOrIncompleteStepCombinedWithMissingDoOrEnd() {
        assertSupportedCases(
            StepCase(
                name = "missing step and do keeps body and following print",
                source = "for i = 1, 10, use(i) end\nprint(i)",
                // Trailing comma then NAME/call: product currently treats the call as the
                // step expression (valid expression start), so step is Call(...) and body
                // still recovers after missing do.
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),Const(10),Call(Id(use):Id(i)):",
                    "CallStmt(Call(Id(print):Id(i)))"
                ),
                warningFragments = listOf("The <do> expected"),
                requireStepPresent = true
            ),
            StepCase(
                name = "missing step and end at eof keeps body statements",
                source = "for i = 1, 10, do use(i) print(\"after\")",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),Const(10),ExpressionNodeSupport:Block[CallStmt(Call(Id(use):Id(i)));CallStmt(Call(Id(print):Const(\"after\")))])"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("<end> expected"),
                requireStepPresent = true
            ),
            StepCase(
                name = "step binary incomplete and missing end keeps body",
                source = "for i = 1, 10, step + do use(i)",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),Const(10),Binary(+,Id(step),ExpressionNodeSupport):Block[CallStmt(Call(Id(use):Id(i)))])"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("<end> expected"),
                requireStepPresent = true
            ),
            StepCase(
                name = "missing step do and end at eof keeps multi-statement body",
                source = "for i = 1, 10, do use(i) print(i)",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),Const(10),ExpressionNodeSupport:Block[CallStmt(Call(Id(use):Id(i)));CallStmt(Call(Id(print):Id(i)))])"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("<end> expected"),
                requireStepPresent = true
            )
        )
    }

    @Test
    fun recoversNestedNumericForMissingOrIncompleteStep() {
        assertSupportedCases(
            StepCase(
                name = "nested missing inner step keeps outer end and following print",
                source = "for i = 1, 3 do for j = 1, 2, do use(i, j) end end\nprint(i)",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),Const(3),null:",
                    "ForNumeric(Id(j)=Const(1),Const(2),ExpressionNodeSupport:",
                    "CallStmt(Call(Id(print):Id(i)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                minForNumericNodes = 2,
                maxForNumericNodes = 2
            ),
            StepCase(
                name = "nested incomplete outer step keeps closed inner and following print",
                source = "for i = 1, 3, step + do for j = 1, 2 do use(i, j) end end\nprint(i)",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),Const(3),Binary(+,Id(step),ExpressionNodeSupport):",
                    "ForNumeric(Id(j)=Const(1),Const(2),null:",
                    "CallStmt(Call(Id(print):Id(i)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                minForNumericNodes = 2,
                maxForNumericNodes = 2
            ),
            StepCase(
                name = "nested both missing step stays bounded",
                source = "for i = 1, 3, do for j = 1, 2, do use(i, j) end end\nprint(i)",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),Const(3),ExpressionNodeSupport:",
                    "ForNumeric(Id(j)=Const(1),Const(2),ExpressionNodeSupport:",
                    "CallStmt(Call(Id(print):Id(i)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                minForNumericNodes = 2,
                maxForNumericNodes = 2
            )
        )
    }

    @Test
    fun recoversIncompleteBodiesWithPresentOrMissingStepWithoutThrowing() {
        assertSupportedCases(
            StepCase(
                name = "present step body incomplete assignment then end keeps following print",
                source = "for i = 1, 10, 2 do total = total + end\nprint(total)",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),Const(10),Const(2):Block[Assign(Id(total)=Binary(+,Id(total),ExpressionNodeSupport))])",
                    "CallStmt(Call(Id(print):Id(total)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                requireStepPresent = true
            ),
            StepCase(
                name = "missing step body incomplete call then end keeps following print",
                source = "for i = 1, 10, do use(i end\nprint(i)",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),Const(10),ExpressionNodeSupport:",
                    "CallStmt(Call(Id(print):Id(i)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("')' expected"),
                requireStepPresent = true
            ),
            StepCase(
                name = "present step body incomplete return then end keeps following print",
                source = "for i = 1, 10, 2 do return i + end\nprint(i)",
                requiredShapeFragments = listOf(
                    "ForNumeric(Id(i)=Const(1),Const(10),Const(2):Block[Return(Binary(+,Id(i),ExpressionNodeSupport))])",
                    "CallStmt(Call(Id(print):Id(i)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                requireStepPresent = true
            )
        )
    }

    @Test
    fun exposesTypedForNumericStepStructureAfterMissingStepRecovery() {
        val chunk = parseRecoveringWithoutThrow(
            "for i = 1, 10, do use(i) end\nprint(i)"
        )

        val forStmt = assertIs<ForNumericStatement>(chunk.body.statements[0])
        assertEquals("Id(i)", renderShape(forStmt.variable))
        assertEquals("Const(1)", renderShape(forStmt.start))
        assertEquals("Const(10)", renderShape(forStmt.end))
        val step = assertNotNull(forStmt.step, "trailing comma should produce a step residual")
        assertEquals("ExpressionNodeSupport", renderShape(step))
        assertTrue(step.bad, "missing step residual should be marked bad")
        assertIs<CallStatement>(forStmt.body.statements.single())
        assertIs<CallStatement>(chunk.body.statements[1])
    }

    @Test
    fun wellFormedNumericForWithOptionalStepStillParsesCleanlyUnderRecovery() {
        val wellFormed = listOf(
            "for i = 1, 10, 2 do use(i) end",
            "for i = 1, 10, 2 do consume(i) end\nlocal after = 1",
            "for i = start, finish, step do handle(i) end\nprint(finish)",
            "for i = 3, 1, -1 do countdown(i) end",
            "for i = 1, 3 do for j = 1, 2, 1 do use(i, j) end end\nprint(done)",
            // optional step absent is still well-formed
            "for i = 1, 3 do use(i) end"
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

        // Explicit typed check: optional step absent stays null; present step is retained.
        val withoutStep = parseRecoveringWithoutThrow("for i = 1, 3 do use(i) end")
        val without = assertIs<ForNumericStatement>(withoutStep.body.statements.single())
        assertNull(without.step)

        val withStep = parseRecoveringWithoutThrow("for i = 1, 10, 2 do use(i) end")
        val with = assertIs<ForNumericStatement>(withStep.body.statements.single())
        assertEquals("Const(2)", renderShape(assertNotNull(with.step)))
    }

    @Test
    fun recoveryDiagnosticsArePresentDeterministicAndDoNotThrow() {
        val sources = listOf(
            "for i = 1, 10, do use(i) end",
            "for i = 1, 10, step + do use(i) end",
            "for i = 1, 10, not do use(i) end",
            "for i = 1, 10, do use(i) print(\"after\")",
            "for i = 1, 3, do for j = 1, 2, do use(i, j) end end\nprint(i)",
            "for i = 1, 10, step +\nprint(i) do use(i) end"
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
    fun dualPathStrictAcceptsIncompleteStepBinaryWithNextLineStatement() {
        // Dual-path footguns: recovery inserts ExpressionNodeSupport for the missing
        // step binary right and keeps next-line print out of the step residual;
        // strict mode currently absorbs print(...) as the binary right operand.
        assertSupportedCases(
            StepCase(
                name = "step binary missing rhs next-line print before do: recovery placeholder, strict absorbs",
                source = "for i = 1, 10, step +\nprint(i) do use(i) end",
                requiredShapeFragments = listOf(
                    "Binary(+,Id(step),ExpressionNodeSupport)"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                // After placeholder step, residual print may force missing-do recovery
                // depending on whether print is left unconsumed — require do diagnostic
                // when product surfaces it; shape still requires the placeholder.
                warningFragments = listOf("The <do> expected"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS,
                requireStepPresent = true
            ),
            StepCase(
                name = "step comparison missing rhs next-line print before do: recovery placeholder, strict absorbs",
                source = "for i = 1, 10, n ==\nprint(i) do use(i) end",
                requiredShapeFragments = listOf(
                    "Binary(==,Id(n),ExpressionNodeSupport)"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("The <do> expected"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS,
                requireStepPresent = true
            ),
            StepCase(
                name = "step incomplete binary assign body next-line print: recovery sibling, strict absorbs",
                source = "for i = 1, 10, 2 do total = total +\nprint(total) end",
                requiredShapeFragments = listOf(
                    "Assign(Id(total)=Binary(+,Id(total),ExpressionNodeSupport))",
                    "CallStmt(Call(Id(print):Id(total)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS,
                requireStepPresent = true
            ),
            StepCase(
                // Unary recovery only placeholders on expression terminators. A following
                // NAME/call is an expression start, so both recovery and strict absorb
                // print as the unary operand — honest CURRENTLY_ACCEPTS dual-path.
                // After the absorbed step, `do` is still present for the body (no missing-do).
                name = "step incomplete unary absorbs following print on both paths",
                source = "for i = 1, 10, not\nprint(i) do use(i) end",
                requiredShapeFragments = listOf(
                    "Unary(not,Call(Id(print):Id(i)))",
                    "ForNumeric(Id(i)=Const(1),Const(10),Unary(not,Call(Id(print):Id(i))):Block[CallStmt(Call(Id(use):Id(i)))])"
                ),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS,
                requireStepPresent = true
            )
        )
    }

    @Test
    fun dualPathStrictRejectsIncompleteStepWhileRecordingAcceptGaps() {
        val rejects = requiredCases().filter {
            it.strictParseExpectation == StrictParseExpectation.REJECTS
        }
        assertTrue(rejects.isNotEmpty(), "inventory must retain REJECTS incomplete step cases")
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
    fun corpusInventoryCoversRequiredNumericForStepFamilies() {
        val inventory = requiredCases()
        assertEquals(23, inventory.size)

        val names = inventory.map { it.name }.toSet()
        assertTrue(names.any { it.contains("missing step") })
        assertTrue(names.any { it.contains("binary") || it.contains("unary") || it.contains("comparison") || it.contains("concat") })
        assertTrue(names.any { it.contains("nested") })
        assertTrue(names.any { it.contains("body") })
        assertTrue(names.any { it.contains("following print") || it.contains("after end") || it.contains("multi-statement") })
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
            "inventory must retain REJECTS incomplete step cases"
        )
        assertTrue(
            inventory.count { it.requireStepPresent } >= 10,
            "inventory should include many cases that assert a step residual is present"
        )
        assertTrue(
            inventory.any { case ->
                case.badShapeFragments.any { it.contains("ExpressionNodeSupport") }
            },
            "inventory must cover step placeholder residuals"
        )
    }

    // --- helpers -----------------------------------------------------------------

    private fun requiredCases(): List<StepCase> {
        return listOf(
            StepCase(
                name = "missing step after trailing comma before do inserts placeholder",
                source = "for i = 1, 10, do use(i) end",
                requiredShapeFragments = listOf("ExpressionNodeSupport"),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                requireStepPresent = true
            ),
            StepCase(
                name = "missing step after trailing comma with identifier bounds before do",
                source = "for i = start, finish, do handle(i) end",
                requiredShapeFragments = listOf("ExpressionNodeSupport"),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                requireStepPresent = true
            ),
            StepCase(
                name = "missing step after trailing comma keeps following print after end",
                source = "for i = 1, 3, do use(i) end\nprint(i)",
                requiredShapeFragments = listOf("CallStmt(Call(Id(print):Id(i)))"),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                requireStepPresent = true
            ),
            StepCase(
                name = "missing step after trailing comma keeps multi-statement body",
                source = "for i = 1, 10, do use(i) print(i) end",
                requiredShapeFragments = listOf("CallStmt(Call(Id(print):Id(i)))"),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                requireStepPresent = true
            ),
            StepCase(
                name = "step binary missing rhs before do keeps placeholder",
                source = "for i = 1, 10, step + do use(i) end",
                requiredShapeFragments = listOf("Binary(+,Id(step),ExpressionNodeSupport)"),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                requireStepPresent = true
            ),
            StepCase(
                name = "step unary missing operand before do keeps placeholder",
                source = "for i = 1, 10, not do use(i) end",
                requiredShapeFragments = listOf("Unary(not,ExpressionNodeSupport)"),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                requireStepPresent = true
            ),
            StepCase(
                name = "step comparison missing rhs before do keeps placeholder",
                source = "for i = 1, 10, n == do use(i) end",
                requiredShapeFragments = listOf("Binary(==,Id(n),ExpressionNodeSupport)"),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                requireStepPresent = true
            ),
            StepCase(
                name = "step concat missing rhs before do keeps placeholder",
                source = "for i = 1, 10, prefix .. do use(i) end",
                requiredShapeFragments = listOf("Binary(..,Id(prefix),ExpressionNodeSupport)"),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                requireStepPresent = true
            ),
            StepCase(
                name = "step unary minus missing operand before do keeps placeholder",
                source = "for i = 3, 1, - do countdown(i) end",
                requiredShapeFragments = listOf("Unary(-,ExpressionNodeSupport)"),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                requireStepPresent = true
            ),
            StepCase(
                name = "missing step and do keeps body and following print",
                source = "for i = 1, 10, use(i) end\nprint(i)",
                requiredShapeFragments = listOf("Call(Id(use):Id(i))"),
                warningFragments = listOf("The <do> expected"),
                requireStepPresent = true
            ),
            StepCase(
                name = "missing step and end at eof keeps body statements",
                source = "for i = 1, 10, do use(i) print(\"after\")",
                requiredShapeFragments = listOf("ExpressionNodeSupport"),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("<end> expected"),
                requireStepPresent = true
            ),
            StepCase(
                name = "step binary incomplete and missing end keeps body",
                source = "for i = 1, 10, step + do use(i)",
                requiredShapeFragments = listOf("Binary(+,Id(step),ExpressionNodeSupport)"),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("<end> expected"),
                requireStepPresent = true
            ),
            StepCase(
                name = "missing step do and end at eof keeps multi-statement body",
                source = "for i = 1, 10, do use(i) print(i)",
                requiredShapeFragments = listOf("ExpressionNodeSupport"),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("<end> expected"),
                requireStepPresent = true
            ),
            StepCase(
                name = "nested missing inner step keeps outer end and following print",
                source = "for i = 1, 3 do for j = 1, 2, do use(i, j) end end\nprint(i)",
                requiredShapeFragments = listOf("ForNumeric(Id(j)=Const(1),Const(2),ExpressionNodeSupport:"),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                minForNumericNodes = 2,
                maxForNumericNodes = 2
            ),
            StepCase(
                name = "nested incomplete outer step keeps closed inner and following print",
                source = "for i = 1, 3, step + do for j = 1, 2 do use(i, j) end end\nprint(i)",
                requiredShapeFragments = listOf("Binary(+,Id(step),ExpressionNodeSupport)"),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                minForNumericNodes = 2,
                maxForNumericNodes = 2
            ),
            StepCase(
                name = "nested both missing step stays bounded",
                source = "for i = 1, 3, do for j = 1, 2, do use(i, j) end end\nprint(i)",
                requiredShapeFragments = listOf("ForNumeric(Id(j)=Const(1),Const(2),ExpressionNodeSupport:"),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                minForNumericNodes = 2,
                maxForNumericNodes = 2
            ),
            StepCase(
                name = "present step body incomplete assignment then end keeps following print",
                source = "for i = 1, 10, 2 do total = total + end\nprint(total)",
                requiredShapeFragments = listOf("Binary(+,Id(total),ExpressionNodeSupport)"),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                requireStepPresent = true
            ),
            StepCase(
                name = "missing step body incomplete call then end keeps following print",
                source = "for i = 1, 10, do use(i end\nprint(i)",
                requiredShapeFragments = listOf("CallStmt(Call(Id(print):Id(i)))"),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("')' expected"),
                requireStepPresent = true
            ),
            StepCase(
                name = "present step body incomplete return then end keeps following print",
                source = "for i = 1, 10, 2 do return i + end\nprint(i)",
                requiredShapeFragments = listOf("Return(Binary(+,Id(i),ExpressionNodeSupport))"),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                requireStepPresent = true
            ),
            // Dual-path CURRENTLY_ACCEPTS footguns (TASK-464)
            StepCase(
                name = "step binary missing rhs next-line print before do: recovery placeholder, strict absorbs",
                source = "for i = 1, 10, step +\nprint(i) do use(i) end",
                requiredShapeFragments = listOf("Binary(+,Id(step),ExpressionNodeSupport)"),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("The <do> expected"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS,
                requireStepPresent = true
            ),
            StepCase(
                name = "step comparison missing rhs next-line print before do: recovery placeholder, strict absorbs",
                source = "for i = 1, 10, n ==\nprint(i) do use(i) end",
                requiredShapeFragments = listOf("Binary(==,Id(n),ExpressionNodeSupport)"),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("The <do> expected"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS,
                requireStepPresent = true
            ),
            StepCase(
                name = "step incomplete binary assign body next-line print: recovery sibling, strict absorbs",
                source = "for i = 1, 10, 2 do total = total +\nprint(total) end",
                requiredShapeFragments = listOf(
                    "Assign(Id(total)=Binary(+,Id(total),ExpressionNodeSupport))",
                    "CallStmt(Call(Id(print):Id(total)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS,
                requireStepPresent = true
            ),
            StepCase(
                name = "step incomplete unary absorbs following print on both paths",
                source = "for i = 1, 10, not\nprint(i) do use(i) end",
                requiredShapeFragments = listOf("Unary(not,Call(Id(print):Id(i)))"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS,
                requireStepPresent = true
            )
        )
    }

    private fun assertSupportedCases(vararg cases: StepCase) {
        cases.forEach(::assertSupportedCase)
    }

    private fun assertSupportedCase(case: StepCase) {
        val first = recoverTwiceAndAssertDeterministic(case)
        assertShapeFragments(case, first.chunk)
        assertBadShapeFragments(case, first.chunk)
        assertWarningFragments(case, first.warnings)
        assertForNumericBounds(case, first.chunk)
        assertStepPresence(case, first.chunk)

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

    private fun recoverTwiceAndAssertDeterministic(case: StepCase): RecoveryRun {
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

    private fun assertShapeFragments(case: StepCase, chunk: ChunkNode) {
        val shape = renderShape(chunk)
        case.requiredShapeFragments.forEach { expected ->
            assertTrue(
                shape.contains(expected),
                "${case.name} should keep '$expected' reachable in recovered shape:\n$shape"
            )
        }
    }

    private fun assertBadShapeFragments(case: StepCase, chunk: ChunkNode) {
        if (case.badShapeFragments.isEmpty()) return
        val shape = renderShape(chunk)
        case.badShapeFragments.forEach { expected ->
            assertTrue(
                shape.contains(expected),
                "${case.name} should mark recovered residual containing '$expected'; shape:\n$shape"
            )
        }
    }

    private fun assertWarningFragments(case: StepCase, warnings: List<String>) {
        case.warningFragments.forEach { expected ->
            assertTrue(
                warnings.any { it.contains(expected) },
                "${case.name} should emit warning containing '$expected'; actual warnings: $warnings"
            )
        }
    }

    private fun assertForNumericBounds(case: StepCase, chunk: ChunkNode) {
        if (case.minForNumericNodes == null && case.maxForNumericNodes == null) return
        val count = countForNumericNodes(chunk)
        case.minForNumericNodes?.let {
            assertTrue(count >= it, "${case.name} expected >= $it ForNumeric nodes, got $count")
        }
        case.maxForNumericNodes?.let {
            assertTrue(count <= it, "${case.name} expected <= $it ForNumeric nodes, got $count")
        }
    }

    private fun assertStepPresence(case: StepCase, chunk: ChunkNode) {
        if (!case.requireStepPresent) return
        val steps = collectForNumericSteps(chunk)
        assertTrue(
            steps.any { it != null },
            "${case.name} expected at least one ForNumeric with non-null step residual; shape:\n${renderShape(chunk)}"
        )
    }

    private fun collectForNumericSteps(chunk: ChunkNode): List<io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode?> {
        val steps = mutableListOf<io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode?>()
        fun walk(node: Any?) {
            when (node) {
                null -> Unit
                is ForNumericStatement -> {
                    steps += node.step
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
                is CallStatement -> walk(node.expression)
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
        return steps
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
                is CallStatement -> walk(node.expression)
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

    private data class StepCase(
        val name: String,
        val source: String,
        val version: LuaVersion = LuaVersion.LUA_5_3,
        val requiredShapeFragments: List<String>,
        val badShapeFragments: List<String> = emptyList(),
        val warningFragments: List<String> = emptyList(),
        val strictParseExpectation: StrictParseExpectation = StrictParseExpectation.REJECTS,
        val minForNumericNodes: Int? = null,
        val maxForNumericNodes: Int? = null,
        val requireStepPresent: Boolean = false,
    )

    private data class RecoveryRun(
        val chunk: ChunkNode,
        val warnings: List<String>,
    )
}
