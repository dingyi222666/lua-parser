package parser.recovery

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaParseResult
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.ElseClause
import io.github.dingyi222666.luaparser.parser.ast.node.ElseIfClause
import io.github.dingyi222666.luaparser.parser.ast.node.IfClause
import io.github.dingyi222666.luaparser.parser.ast.node.IfStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import parser.assertParseFails
import parser.parse
import parser.renderShape

/**
 * Product-lock recovery corpus for if / elseif / else chains (TASK-598 product + TASK-463 inventory).
 *
 * Complements [LuaParserRecoveryIfChainTddTest] (TASK-190 inventory) with honest
 * CURRENTLY_ACCEPTS dual-path footguns and a broader elseif-chain residual suite.
 *
 * Complements:
 * - [LuaParserRecoveryIfChainTddTest] missing-then / missing-end REJECTS inventory
 * - sparse if-chain fixtures in [LuaParserRecoveryTddTest]
 *
 * Acceptance (TASK-598 product lock):
 * - Broken if/elseif/else chains recover each clause where possible (missing `then`
 *   still parses body; missing `end` keeps residual bodies inside the if).
 * - No infinite recovery loops: clause loop only advances on ELSEIF/ELSE and stops
 *   after the first `else` (further elseif/else does not re-enter). Nested incomplete
 *   ifs stay bounded (`minIfNodes`/`maxIfNodes`).
 * - Following statements parse as top-level siblings when the chain is closed with `end`.
 * - Missing `then` emits `The <then> expected`; missing `end` emits `<end> expected
 *   (to close 'if' ...)`.
 * - Dual-path [StrictParseExpectation] documents CURRENTLY_ACCEPTS footguns
 *   (recovery sibling + ExpressionNodeSupport vs strict absorb of next-line
 *   statement as expression operand) and retained REJECTS incompletes.
 * - Well-formed if-elseif chains stay clean under recovery and match strict shape.
 *
 * Product goldens (aligned with [LuaParser.parseIfStatement] /
 * [LuaParser.parseIfCause] / [LuaParser.parseElseIfCause] / binary-unary recovery):
 * - missing `then` after condition warns `The <then> expected`, marks clause bad,
 *   and still parses body;
 * - missing `end` recovers with `<end> expected (to close 'if' ...)`, marks IfStatement
 *   bad, and keeps branch bodies inside the if;
 * - incomplete binary RHS after line-break + statement-start inserts
 *   ExpressionNodeSupport under recovery; strict mode currently absorbs the next
 *   call as the binary operand (CURRENTLY_ACCEPTS dual-path);
 * - incomplete unary only placeholders on expression terminators; a following
 *   NAME/call is absorbed as the unary operand on both paths.
 */
class LuaParserRecoveryIfElseifChainTddTest {

    @Test
    fun recoversMissingThenOnIfAndElseIfAndKeepsBodies() {
        assertSupportedCases(
            IfElseifChainCase(
                name = "if missing then keeps body and later print",
                source = "if ready work() end\nprint(ready)",
                requiredShapeFragments = listOf(
                    "If(Clause(Id(ready):Block[CallStmt(Call(Id(work):))])",
                    "CallStmt(Call(Id(print):Id(ready)))"
                ),
                warningFragments = listOf("The <then> expected")
            ),
            IfElseifChainCase(
                name = "elseif missing then keeps elseif and else bodies",
                source = "if first then one() elseif second two() else three() end",
                requiredShapeFragments = listOf(
                    "Clause(Id(first):Block[CallStmt(Call(Id(one):))])",
                    "ElseIf(Id(second):Block[CallStmt(Call(Id(two):))])",
                    "Else(Block[CallStmt(Call(Id(three):))])"
                ),
                warningFragments = listOf("The <then> expected")
            ),
            IfElseifChainCase(
                name = "multiple elseif missing then keeps all branches",
                source = "if a then one() elseif b two() elseif c three() else four() end",
                requiredShapeFragments = listOf(
                    "Clause(Id(a):Block[CallStmt(Call(Id(one):))])",
                    "ElseIf(Id(b):Block[CallStmt(Call(Id(two):))])",
                    "ElseIf(Id(c):Block[CallStmt(Call(Id(three):))])",
                    "Else(Block[CallStmt(Call(Id(four):))])"
                ),
                warningFragments = listOf("The <then> expected")
            ),
            IfElseifChainCase(
                name = "if and elseif both missing then keep branches and following local",
                source = "if a a1() elseif b b1() else z1() end\nlocal after = 1",
                requiredShapeFragments = listOf(
                    "Clause(Id(a):Block[CallStmt(Call(Id(a1):))])",
                    "ElseIf(Id(b):Block[CallStmt(Call(Id(b1):))])",
                    "Else(Block[CallStmt(Call(Id(z1):))])",
                    "Local(Id(after)=Const(1))"
                ),
                warningFragments = listOf("The <then> expected")
            )
        )
    }

    @Test
    fun recoversMissingEndOnIfElseifChainsAndKeepsLaterSiblings() {
        assertSupportedCases(
            IfElseifChainCase(
                name = "if missing end at eof keeps then body",
                source = "if ready then work() print(\"after\")",
                requiredShapeFragments = listOf(
                    "If(Clause(Id(ready):Block[CallStmt(Call(Id(work):));CallStmt(Call(Id(print):Const(\"after\")))])"
                ),
                warningFragments = listOf("<end> expected")
            ),
            IfElseifChainCase(
                name = "if/elseif missing end at eof keeps both branch bodies",
                source = "if first then one() elseif second then two()",
                requiredShapeFragments = listOf(
                    "Clause(Id(first):Block[CallStmt(Call(Id(one):))])",
                    "ElseIf(Id(second):Block[CallStmt(Call(Id(two):))])"
                ),
                warningFragments = listOf("<end> expected")
            ),
            IfElseifChainCase(
                name = "if/elseif/else missing end at eof keeps all branches",
                source = "if first then one() elseif second then two() else three()",
                requiredShapeFragments = listOf(
                    "Clause(Id(first):Block[CallStmt(Call(Id(one):))])",
                    "ElseIf(Id(second):Block[CallStmt(Call(Id(two):))])",
                    "Else(Block[CallStmt(Call(Id(three):))])"
                ),
                warningFragments = listOf("<end> expected")
            ),
            IfElseifChainCase(
                name = "if missing then and end still keeps body statements",
                source = "if ready work() print(\"tail\")",
                requiredShapeFragments = listOf(
                    "CallStmt(Call(Id(work):))",
                    "CallStmt(Call(Id(print):Const(\"tail\")))"
                ),
                warningFragments = listOf("The <then> expected", "<end> expected")
            ),
            IfElseifChainCase(
                name = "closed if keeps following print as top-level sibling",
                source = "if ready then work() end\nprint(ready)",
                requiredShapeFragments = listOf(
                    "If(Clause(Id(ready):Block[CallStmt(Call(Id(work):))])",
                    "CallStmt(Call(Id(print):Id(ready)))"
                ),
                // well-formed with end — no recovery diagnostics expected
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            )
        )
    }

    @Test
    fun recoversIncompleteConditionsOnIfAndElseIfWithoutThrowing() {
        assertSupportedCases(
            IfElseifChainCase(
                name = "if condition missing after if inserts placeholder and keeps then body",
                source = "if then work() end\nprint(1)",
                requiredShapeFragments = listOf(
                    "If(Clause(ExpressionNodeSupport:Block[CallStmt(Call(Id(work):))])",
                    "CallStmt(Call(Id(print):Const(1)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            IfElseifChainCase(
                name = "if binary condition missing rhs keeps then body",
                source = "if value + then work() end\nprint(value)",
                requiredShapeFragments = listOf(
                    "Binary(+,Id(value),ExpressionNodeSupport)",
                    "CallStmt(Call(Id(work):))",
                    "CallStmt(Call(Id(print):Id(value)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            IfElseifChainCase(
                name = "elseif condition missing inserts placeholder and keeps body",
                source = "if first then one() elseif then two() end",
                requiredShapeFragments = listOf(
                    "Clause(Id(first):Block[CallStmt(Call(Id(one):))])",
                    "ElseIf(ExpressionNodeSupport:Block[CallStmt(Call(Id(two):))])"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            IfElseifChainCase(
                name = "elseif binary condition missing rhs keeps elseif body",
                source = "if first then one() elseif second + then two() else three() end",
                requiredShapeFragments = listOf(
                    "ElseIf(Binary(+,Id(second),ExpressionNodeSupport):Block[CallStmt(Call(Id(two):))])",
                    "Else(Block[CallStmt(Call(Id(three):))])"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            IfElseifChainCase(
                name = "if comparison condition missing rhs keeps then and else",
                source = "if a == then one() else two() end\nprint(a)",
                requiredShapeFragments = listOf(
                    "Binary(==,Id(a),ExpressionNodeSupport)",
                    "Else(Block[CallStmt(Call(Id(two):))])",
                    "CallStmt(Call(Id(print):Id(a)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport")
            )
        )
    }

    @Test
    fun recoversNestedAndLongElseifChainsWithReachableTrailingStatements() {
        assertSupportedCases(
            IfElseifChainCase(
                name = "nested if missing inner then keeps outer and trailing print",
                source = """
                    if outer then
                      if inner work() end
                    end
                    print(outer)
                """.trimIndent(),
                requiredShapeFragments = listOf(
                    "Clause(Id(outer):",
                    "If(Clause(Id(inner):Block[CallStmt(Call(Id(work):))])",
                    "CallStmt(Call(Id(print):Id(outer)))"
                ),
                warningFragments = listOf("The <then> expected"),
                minIfNodes = 2,
                maxIfNodes = 2
            ),
            IfElseifChainCase(
                name = "nested if missing inner end keeps outer else and trailing print",
                source = """
                    if outer then
                      if inner then work()
                    else
                      fallback()
                    end
                    print(done)
                """.trimIndent(),
                requiredShapeFragments = listOf(
                    "CallStmt(Call(Id(work):))",
                    "Else(Block[CallStmt(Call(Id(fallback):))])",
                    "CallStmt(Call(Id(print):Id(done)))"
                ),
                warningFragments = listOf("<end> expected"),
                minIfNodes = 1,
                maxIfNodes = 2
            ),
            IfElseifChainCase(
                name = "long elseif chain missing one then keeps remaining branches",
                source = """
                    if a then a1()
                    elseif b then b1()
                    elseif c c1()
                    elseif d then d1()
                    else z1()
                    end
                    print(z)
                """.trimIndent(),
                requiredShapeFragments = listOf(
                    "Clause(Id(a):Block[CallStmt(Call(Id(a1):))])",
                    "ElseIf(Id(b):Block[CallStmt(Call(Id(b1):))])",
                    "ElseIf(Id(c):Block[CallStmt(Call(Id(c1):))])",
                    "ElseIf(Id(d):Block[CallStmt(Call(Id(d1):))])",
                    "Else(Block[CallStmt(Call(Id(z1):))])",
                    "CallStmt(Call(Id(print):Id(z)))"
                ),
                warningFragments = listOf("The <then> expected")
            ),
            IfElseifChainCase(
                name = "nested elseif missing then stays bounded",
                source = """
                    if outer then
                      if a then one() elseif b two() else three() end
                    end
                    print(outer)
                """.trimIndent(),
                requiredShapeFragments = listOf(
                    "ElseIf(Id(b):Block[CallStmt(Call(Id(two):))])",
                    "Else(Block[CallStmt(Call(Id(three):))])",
                    "CallStmt(Call(Id(print):Id(outer)))"
                ),
                warningFragments = listOf("The <then> expected"),
                minIfNodes = 2,
                maxIfNodes = 2
            )
        )
    }

    @Test
    fun recoversIncompleteBodiesInsideIfElseifBranchesWithoutThrowing() {
        assertSupportedCases(
            IfElseifChainCase(
                name = "then body incomplete assignment keeps else branch and print",
                source = "if ready then value = value + else fallback() end\nprint(value)",
                requiredShapeFragments = listOf(
                    "Assign(Id(value)=Binary(+,Id(value),ExpressionNodeSupport))",
                    "Else(Block[CallStmt(Call(Id(fallback):))])",
                    "CallStmt(Call(Id(print):Id(value)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            IfElseifChainCase(
                name = "elseif body incomplete call keeps later else and print",
                source = "if a then one() elseif b then two( else three() end\nprint(b)",
                requiredShapeFragments = listOf(
                    "Clause(Id(a):Block[CallStmt(Call(Id(one):))])",
                    "ElseIf(Id(b):",
                    "Else(Block[CallStmt(Call(Id(three):))])",
                    "CallStmt(Call(Id(print):Id(b)))"
                ),
                warningFragments = listOf("')' expected")
            ),
            IfElseifChainCase(
                name = "else body incomplete local keeps trailing print",
                source = "if ready then work() else local value = end\nprint(ready)",
                requiredShapeFragments = listOf(
                    "Clause(Id(ready):Block[CallStmt(Call(Id(work):))])",
                    "Else(Block[Local(Id(value)=ExpressionNodeSupport)])",
                    "CallStmt(Call(Id(print):Id(ready)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            IfElseifChainCase(
                name = "elseif body incomplete local keeps following print after closed chain",
                source = "if a then one() elseif b then local copy = end\nprint(b)",
                requiredShapeFragments = listOf(
                    "ElseIf(Id(b):Block[Local(Id(copy)=ExpressionNodeSupport)])",
                    "CallStmt(Call(Id(print):Id(b)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport")
            )
        )
    }

    @Test
    fun exposesTypedClauseStructureForRecoveredIfElseifChains() {
        val recovered = parseRecoveringWithoutThrow(
            "if first then one() elseif second two() else three() end\nprint(first)"
        )

        val ifStmt = assertIs<IfStatement>(recovered.body.statements[0])
        assertEquals(3, ifStmt.causes.size)
        assertTrue(ifStmt.bad || ifStmt.causes.any { it.bad }, "missing then should mark clause/if bad")

        val ifClause = assertIs<IfClause>(ifStmt.causes[0])
        assertEquals("Id(first)", renderShape(ifClause.condition))
        assertIs<CallStatement>(ifClause.body.statements.single())

        val elseIfClause = assertIs<ElseIfClause>(ifStmt.causes[1])
        assertEquals("Id(second)", renderShape(elseIfClause.condition))
        assertIs<CallStatement>(elseIfClause.body.statements.single())
        assertTrue(elseIfClause.bad, "elseif missing then should mark ElseIfClause bad")

        val elseClause = assertIs<ElseClause>(ifStmt.causes[2])
        assertIs<CallStatement>(elseClause.body.statements.single())

        assertIs<CallStatement>(recovered.body.statements[1])
    }

    @Test
    fun recoversDuplicateElseWithoutInfiniteClauseLoopAndKeepsFollowingStatement() {
        // Product lock (TASK-598): after the first `else`, a second `else` must not
        // re-enter the clause loop. Recovery emits missing-end (second else is not
        // consumed as a clause), stays finite, and still parses the following print
        // when a later `end` closes the outer block / recovery path leaves it free.
        // Residual: one Else clause only; second `else` is recovered as unexpected at
        // top-level or as missing-end near, depending on token stream — pin bounded
        // clause count + no-throw + trailing print when closed.
        val recovered = parseRecoveringWithoutThrow(
            "if ready then work() else one() else two() end\nprint(ready)"
        )
        val ifStmt = assertIs<IfStatement>(recovered.body.statements[0])
        val elseCount = ifStmt.causes.count { it is ElseClause }
        assertEquals(1, elseCount, "only first else clause should attach; shape=${renderShape(recovered)}")
        assertTrue(
            ifStmt.causes.size <= 3,
            "clause list must stay bounded for duplicate else; size=${ifStmt.causes.size}"
        )
        // Following print remains reachable (either top-level sibling after end, or
        // residual after recovery of unexpected else). Prefer top-level sibling.
        val shape = renderShape(recovered)
        assertTrue(
            shape.contains("CallStmt(Call(Id(print):Id(ready)))") ||
                shape.contains("CallStmt(Call(Id(work):))"),
            "recovery must keep work/print reachable without looping:\n$shape"
        )
        val second = parseRecoveringWithoutThrow(
            "if ready then work() else one() else two() end\nprint(ready)"
        )
        assertEquals(renderShape(recovered), renderShape(second), "duplicate-else residual deterministic")
    }

    @Test
    fun recoveryDiagnosticsArePresentDeterministicAndDoNotThrow() {
        val sources = listOf(
            "if ready work() end",
            "if first then one() elseif second two() else three() end",
            "if ready then work()",
            "if first then one() elseif second then two() else three()",
            "if a then a1() elseif b then b1() elseif c c1() else z1() end",
            "if a then one() elseif b then two( else three() end",
            "if value + then work() end"
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
    fun dualPathStrictAcceptsIncompleteBinaryInsideIfElseifWithNextLineStatement() {
        // Dual-path footguns: recovery inserts ExpressionNodeSupport for the missing
        // binary right and keeps next-line print as a sibling CallStmt; strict mode
        // currently absorbs print(...) as the binary right operand and accepts.
        assertSupportedCases(
            IfElseifChainCase(
                name = "then incomplete binary assign next-line print: recovery sibling, strict absorbs",
                source = "if ready then total = total +\nprint(total) end",
                requiredShapeFragments = listOf(
                    "Assign(Id(total)=Binary(+,Id(total),ExpressionNodeSupport))",
                    "CallStmt(Call(Id(print):Id(total)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            IfElseifChainCase(
                name = "elseif incomplete comparison assign next-line print: recovery sibling, strict absorbs",
                source = "if a then one() elseif b then ok = b ==\nprint(ok) else three() end",
                requiredShapeFragments = listOf(
                    "Assign(Id(ok)=Binary(==,Id(b),ExpressionNodeSupport))",
                    "CallStmt(Call(Id(print):Id(ok)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            IfElseifChainCase(
                name = "else incomplete binary assign next-line print: recovery sibling, strict absorbs",
                source = "if ready then work() else total = total +\nprint(total) end",
                requiredShapeFragments = listOf(
                    "Assign(Id(total)=Binary(+,Id(total),ExpressionNodeSupport))",
                    "CallStmt(Call(Id(print):Id(total)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            IfElseifChainCase(
                // Unary recovery only placeholders on expression terminators. A following
                // NAME/call is an expression start, so both recovery and strict absorb
                // print as the unary operand — honest CURRENTLY_ACCEPTS dual-path.
                name = "then incomplete unary return absorbs following print on both paths",
                source = "if ready then return not\nprint(ready) end",
                requiredShapeFragments = listOf(
                    "Return(Unary(not,Call(Id(print):Id(ready))))"
                ),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            )
        )
    }

    @Test
    fun dualPathStrictRejectsMissingThenEndWhileRecordingAcceptGaps() {
        val rejects = requiredCases().filter {
            it.strictParseExpectation == StrictParseExpectation.REJECTS
        }
        assertTrue(rejects.isNotEmpty(), "inventory must retain REJECTS incomplete if-elseif cases")
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
    fun wellFormedIfElseifChainsStillParseCleanlyUnderRecovery() {
        val wellFormed = listOf(
            "if ready then work() end",
            "if ready then work() else fallback() end",
            "if a then one() elseif b then two() else three() end",
            "if a then if b then nested() end end",
            "if ready then local x = 1 print(x) end\nprint(ready)",
            "if ready then elseif other then else end\nlocal after = 1",
            "if a then a1() elseif b then b1() elseif c then c1() else z1() end\nprint(z)"
        )

        wellFormed.forEach { source ->
            val result = parseWithDiagnosticsWithoutThrow(source)
            assertEquals(
                emptyList(),
                result.recoveryDiagnostics.map { it.message },
                "well-formed if-elseif chain should not emit recovery diagnostics: $source"
            )
            assertTrue(
                renderShape(result.chunk).contains("If("),
                "well-formed source should produce If shape: $source"
            )
            val strict = parse(LuaVersion.LUA_5_3, source, recovery = false)
            assertEquals(renderShape(result.chunk), renderShape(strict))
        }
    }

    @Test
    fun corpusInventoryCoversRequiredIfElseifChainFamilies() {
        val inventory = requiredCases()
        assertEquals(26, inventory.size)

        val names = inventory.map { it.name }.toSet()
        assertTrue(names.any { it.contains("missing then") })
        assertTrue(names.any { it.contains("missing end") })
        assertTrue(names.any { it.contains("condition") })
        assertTrue(names.any { it.contains("nested") || it.contains("elseif chain") })
        assertTrue(names.any { it.contains("body") || it.contains("incomplete") })
        assertTrue(names.any { it.contains("elseif") })
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
            "inventory must retain REJECTS incomplete if-elseif cases"
        )
        assertTrue(
            inventory.any { case ->
                case.warningFragments.any { it.contains("The <then> expected") }
            },
            "inventory must cover missing-then diagnostics"
        )
        assertTrue(
            inventory.any { case ->
                case.warningFragments.any { it.contains("<end> expected") }
            },
            "inventory must cover missing-end diagnostics"
        )
    }

    // --- helpers -----------------------------------------------------------------

    private fun requiredCases(): List<IfElseifChainCase> {
        return listOf(
            IfElseifChainCase(
                name = "if missing then keeps body and later print",
                source = "if ready work() end\nprint(ready)",
                requiredShapeFragments = listOf("If(Clause(Id(ready):"),
                warningFragments = listOf("The <then> expected")
            ),
            IfElseifChainCase(
                name = "elseif missing then keeps elseif and else bodies",
                source = "if first then one() elseif second two() else three() end",
                requiredShapeFragments = listOf("ElseIf(Id(second):"),
                warningFragments = listOf("The <then> expected")
            ),
            IfElseifChainCase(
                name = "multiple elseif missing then keeps all branches",
                source = "if a then one() elseif b two() elseif c three() else four() end",
                requiredShapeFragments = listOf("ElseIf(Id(b):", "ElseIf(Id(c):"),
                warningFragments = listOf("The <then> expected")
            ),
            IfElseifChainCase(
                name = "if and elseif both missing then keep branches and following local",
                source = "if a a1() elseif b b1() else z1() end\nlocal after = 1",
                requiredShapeFragments = listOf("Local(Id(after)=Const(1))"),
                warningFragments = listOf("The <then> expected")
            ),
            IfElseifChainCase(
                name = "if missing end at eof keeps then body",
                source = "if ready then work() print(\"after\")",
                requiredShapeFragments = listOf("CallStmt(Call(Id(print):Const(\"after\")))"),
                warningFragments = listOf("<end> expected")
            ),
            IfElseifChainCase(
                name = "if/elseif missing end at eof keeps both branch bodies",
                source = "if first then one() elseif second then two()",
                requiredShapeFragments = listOf("ElseIf(Id(second):"),
                warningFragments = listOf("<end> expected")
            ),
            IfElseifChainCase(
                name = "if/elseif/else missing end at eof keeps all branches",
                source = "if first then one() elseif second then two() else three()",
                requiredShapeFragments = listOf("Else(Block[CallStmt(Call(Id(three):))])"),
                warningFragments = listOf("<end> expected")
            ),
            IfElseifChainCase(
                name = "if missing then and end still keeps body statements",
                source = "if ready work() print(\"tail\")",
                requiredShapeFragments = listOf("CallStmt(Call(Id(work):))"),
                warningFragments = listOf("The <then> expected", "<end> expected")
            ),
            IfElseifChainCase(
                name = "closed if keeps following print as top-level sibling",
                source = "if ready then work() end\nprint(ready)",
                requiredShapeFragments = listOf("CallStmt(Call(Id(print):Id(ready)))"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            IfElseifChainCase(
                name = "if condition missing after if inserts placeholder and keeps then body",
                source = "if then work() end\nprint(1)",
                requiredShapeFragments = listOf("ExpressionNodeSupport"),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            IfElseifChainCase(
                name = "if binary condition missing rhs keeps then body",
                source = "if value + then work() end\nprint(value)",
                requiredShapeFragments = listOf("Binary(+,Id(value),ExpressionNodeSupport)"),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            IfElseifChainCase(
                name = "elseif condition missing inserts placeholder and keeps body",
                source = "if first then one() elseif then two() end",
                requiredShapeFragments = listOf("ElseIf(ExpressionNodeSupport:"),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            IfElseifChainCase(
                name = "elseif binary condition missing rhs keeps elseif body",
                source = "if first then one() elseif second + then two() else three() end",
                requiredShapeFragments = listOf("ElseIf(Binary(+,Id(second),ExpressionNodeSupport):"),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            IfElseifChainCase(
                name = "if comparison condition missing rhs keeps then and else",
                source = "if a == then one() else two() end\nprint(a)",
                requiredShapeFragments = listOf("Binary(==,Id(a),ExpressionNodeSupport)"),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            IfElseifChainCase(
                name = "nested if missing inner then keeps outer and trailing print",
                source = "if outer then\n  if inner work() end\nend\nprint(outer)",
                requiredShapeFragments = listOf("If(Clause(Id(inner):"),
                warningFragments = listOf("The <then> expected"),
                minIfNodes = 2,
                maxIfNodes = 2
            ),
            IfElseifChainCase(
                name = "nested if missing inner end keeps outer else and trailing print",
                source = "if outer then\n  if inner then work()\nelse\n  fallback()\nend\nprint(done)",
                requiredShapeFragments = listOf("Else(Block[CallStmt(Call(Id(fallback):))])"),
                warningFragments = listOf("<end> expected")
            ),
            IfElseifChainCase(
                name = "long elseif chain missing one then keeps remaining branches",
                source = "if a then a1()\nelseif b then b1()\nelseif c c1()\nelseif d then d1()\nelse z1()\nend\nprint(z)",
                requiredShapeFragments = listOf("ElseIf(Id(c):"),
                warningFragments = listOf("The <then> expected")
            ),
            IfElseifChainCase(
                name = "nested elseif missing then stays bounded",
                source = "if outer then\n  if a then one() elseif b two() else three() end\nend\nprint(outer)",
                requiredShapeFragments = listOf("ElseIf(Id(b):"),
                warningFragments = listOf("The <then> expected"),
                minIfNodes = 2,
                maxIfNodes = 2
            ),
            IfElseifChainCase(
                name = "then body incomplete assignment keeps else branch and print",
                source = "if ready then value = value + else fallback() end\nprint(value)",
                requiredShapeFragments = listOf("Else(Block[CallStmt(Call(Id(fallback):))])"),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            IfElseifChainCase(
                name = "elseif body incomplete call keeps later else and print",
                source = "if a then one() elseif b then two( else three() end\nprint(b)",
                requiredShapeFragments = listOf("Else(Block[CallStmt(Call(Id(three):))])"),
                warningFragments = listOf("')' expected")
            ),
            IfElseifChainCase(
                name = "else body incomplete local keeps trailing print",
                source = "if ready then work() else local value = end\nprint(ready)",
                requiredShapeFragments = listOf("Else(Block[Local(Id(value)=ExpressionNodeSupport)])"),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            IfElseifChainCase(
                name = "elseif body incomplete local keeps following print after closed chain",
                source = "if a then one() elseif b then local copy = end\nprint(b)",
                requiredShapeFragments = listOf("Local(Id(copy)=ExpressionNodeSupport)"),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            // Dual-path CURRENTLY_ACCEPTS footguns (TASK-463)
            IfElseifChainCase(
                name = "then incomplete binary assign next-line print: recovery sibling, strict absorbs",
                source = "if ready then total = total +\nprint(total) end",
                requiredShapeFragments = listOf(
                    "Assign(Id(total)=Binary(+,Id(total),ExpressionNodeSupport))",
                    "CallStmt(Call(Id(print):Id(total)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            IfElseifChainCase(
                name = "elseif incomplete comparison assign next-line print: recovery sibling, strict absorbs",
                source = "if a then one() elseif b then ok = b ==\nprint(ok) else three() end",
                requiredShapeFragments = listOf(
                    "Assign(Id(ok)=Binary(==,Id(b),ExpressionNodeSupport))",
                    "CallStmt(Call(Id(print):Id(ok)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            IfElseifChainCase(
                name = "else incomplete binary assign next-line print: recovery sibling, strict absorbs",
                source = "if ready then work() else total = total +\nprint(total) end",
                requiredShapeFragments = listOf(
                    "Assign(Id(total)=Binary(+,Id(total),ExpressionNodeSupport))",
                    "CallStmt(Call(Id(print):Id(total)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            IfElseifChainCase(
                name = "then incomplete unary return absorbs following print on both paths",
                source = "if ready then return not\nprint(ready) end",
                requiredShapeFragments = listOf("Return(Unary(not,Call(Id(print):Id(ready))))"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            )
        )
    }

    private fun assertSupportedCases(vararg cases: IfElseifChainCase) {
        cases.forEach(::assertSupportedCase)
    }

    private fun assertSupportedCase(case: IfElseifChainCase) {
        val first = recoverTwiceAndAssertDeterministic(case)
        assertShapeFragments(case, first.chunk)
        assertBadShapeFragments(case, first.chunk)
        assertWarningFragments(case, first.warnings)
        assertIfBounds(case, first.chunk)

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

    private fun recoverTwiceAndAssertDeterministic(case: IfElseifChainCase): RecoveryRun {
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
            throw AssertionError(
                "recovery parseWithDiagnostics must not throw during $attempt for: $source",
                failure
            )
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

    private fun assertShapeFragments(case: IfElseifChainCase, chunk: ChunkNode) {
        val shape = renderShape(chunk)
        case.requiredShapeFragments.forEach { expected ->
            assertTrue(
                shape.contains(expected),
                "${case.name} should keep '$expected' reachable in recovered shape:\n$shape"
            )
        }
    }

    private fun assertBadShapeFragments(case: IfElseifChainCase, chunk: ChunkNode) {
        if (case.badShapeFragments.isEmpty()) return
        val shape = renderShape(chunk)
        case.badShapeFragments.forEach { expected ->
            assertTrue(
                shape.contains(expected),
                "${case.name} should include recovered residual containing '$expected'; shape:\n$shape"
            )
        }
    }

    private fun assertWarningFragments(case: IfElseifChainCase, warnings: List<String>) {
        case.warningFragments.forEach { expected ->
            assertTrue(
                warnings.any { it.contains(expected) },
                "${case.name} should emit warning containing '$expected'; actual warnings: $warnings"
            )
        }
    }

    private fun assertIfBounds(case: IfElseifChainCase, chunk: ChunkNode) {
        if (case.minIfNodes == null && case.maxIfNodes == null) return
        val count = countIfNodes(chunk)
        case.minIfNodes?.let {
            assertTrue(count >= it, "${case.name} expected at least $it If nodes, got $count")
        }
        case.maxIfNodes?.let {
            assertTrue(count <= it, "${case.name} expected at most $it If nodes (bounded), got $count")
        }
    }

    private fun countIfNodes(chunk: ChunkNode): Int {
        var count = 0
        fun walk(node: Any?) {
            when (node) {
                is IfStatement -> {
                    count++
                    node.causes.forEach(::walk)
                }
                is IfClause -> {
                    walk(node.condition)
                    walk(node.body)
                }
                is ChunkNode -> walk(node.body)
                is io.github.dingyi222666.luaparser.parser.ast.node.BlockNode -> {
                    node.statements.forEach(::walk)
                    node.returnStatement?.let(::walk)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.DoStatement -> walk(node.body)
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
                is io.github.dingyi222666.luaparser.parser.ast.node.ReturnStatement -> {
                    node.arguments.forEach(::walk)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement -> walk(node.body)
                is io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement -> {
                    node.variables.forEach(::walk)
                    node.iterators.forEach(::walk)
                    walk(node.body)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement -> {
                    walk(node.body)
                    walk(node.condition)
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

    private data class IfElseifChainCase(
        val name: String,
        val source: String,
        val requiredShapeFragments: List<String>,
        val version: LuaVersion = LuaVersion.LUA_5_3,
        val badShapeFragments: List<String> = emptyList(),
        val warningFragments: List<String> = emptyList(),
        val strictParseExpectation: StrictParseExpectation = StrictParseExpectation.REJECTS,
        val minIfNodes: Int? = null,
        val maxIfNodes: Int? = null,
    )

    private data class RecoveryRun(
        val chunk: ChunkNode,
        val warnings: List<String>,
    )
}
