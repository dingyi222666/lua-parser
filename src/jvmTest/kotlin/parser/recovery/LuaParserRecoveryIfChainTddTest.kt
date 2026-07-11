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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import parser.assertParseFails
import parser.parse
import parser.renderShape

/**
 * Focused recovery corpus for incomplete if / elseif / else chains.
 *
 * Acceptance (TASK-190):
 * - Cover incomplete if/elseif/else chains.
 * - Diagnostics ok; throws not (with errorRecovery=true).
 * - Test-only; no production edits.
 *
 * Complements the broader [LuaParserRecoveryTddTest] inventory with deeper
 * multi-clause if-chain edge cases: missing then/end, incomplete conditions,
 * nested chains, trailing statements after broken chains, and structured
 * diagnostic presence/determinism.
 */
class LuaParserRecoveryIfChainTddTest {

    @Test
    fun recoversMissingThenOnIfAndElseIfWithoutThrowing() {
        assertSupportedIfChainCases(
            IfChainCase(
                name = "if missing then keeps body and later print",
                source = "if ready work() end\nprint(ready)",
                requiredShapeFragments = listOf(
                    "If(Clause(Id(ready):Block[CallStmt(Call(Id(work):))])",
                    "CallStmt(Call(Id(print):Id(ready)))"
                ),
                warningFragments = listOf("The <then> expected")
            ),
            IfChainCase(
                name = "elseif missing then keeps elseif and else bodies",
                source = "if first then one() elseif second two() else three() end",
                requiredShapeFragments = listOf(
                    "Clause(Id(first):Block[CallStmt(Call(Id(one):))])",
                    "ElseIf(Id(second):Block[CallStmt(Call(Id(two):))])",
                    "Else(Block[CallStmt(Call(Id(three):))])"
                ),
                warningFragments = listOf("The <then> expected")
            ),
            IfChainCase(
                name = "multiple elseif missing then keeps all branches",
                source = "if a then one() elseif b two() elseif c three() else four() end",
                requiredShapeFragments = listOf(
                    "Clause(Id(a):Block[CallStmt(Call(Id(one):))])",
                    "ElseIf(Id(b):Block[CallStmt(Call(Id(two):))])",
                    "ElseIf(Id(c):Block[CallStmt(Call(Id(three):))])",
                    "Else(Block[CallStmt(Call(Id(four):))])"
                ),
                warningFragments = listOf("The <then> expected")
            )
        )
    }

    @Test
    fun recoversMissingEndOnIfChainsAtEofWithoutThrowing() {
        assertSupportedIfChainCases(
            IfChainCase(
                name = "if missing end at eof keeps then body",
                source = "if ready then work() print(\"after\")",
                requiredShapeFragments = listOf(
                    "If(Clause(Id(ready):Block[CallStmt(Call(Id(work):));CallStmt(Call(Id(print):Const(\"after\")))])"
                ),
                warningFragments = listOf("<end> expected")
            ),
            IfChainCase(
                name = "if/elseif missing end at eof keeps both branch bodies",
                source = "if first then one() elseif second then two()",
                requiredShapeFragments = listOf(
                    "Clause(Id(first):Block[CallStmt(Call(Id(one):))])",
                    "ElseIf(Id(second):Block[CallStmt(Call(Id(two):))])"
                ),
                warningFragments = listOf("<end> expected")
            ),
            IfChainCase(
                name = "if/elseif/else missing end at eof keeps all branches",
                source = "if first then one() elseif second then two() else three()",
                requiredShapeFragments = listOf(
                    "Clause(Id(first):Block[CallStmt(Call(Id(one):))])",
                    "ElseIf(Id(second):Block[CallStmt(Call(Id(two):))])",
                    "Else(Block[CallStmt(Call(Id(three):))])"
                ),
                warningFragments = listOf("<end> expected")
            ),
            IfChainCase(
                name = "if missing then and end still keeps body statements",
                source = "if ready work() print(\"tail\")",
                requiredShapeFragments = listOf(
                    "CallStmt(Call(Id(work):))",
                    "CallStmt(Call(Id(print):Const(\"tail\")))"
                ),
                warningFragments = listOf("The <then> expected", "<end> expected")
            )
        )
    }

    @Test
    fun recoversIncompleteIfAndElseIfConditionsWithoutThrowing() {
        assertSupportedIfChainCases(
            IfChainCase(
                name = "if condition missing after if inserts placeholder and keeps then body",
                source = "if then work() end\nprint(1)",
                requiredShapeFragments = listOf(
                    "If(Clause(ExpressionNodeSupport:Block[CallStmt(Call(Id(work):))])",
                    "CallStmt(Call(Id(print):Const(1)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            IfChainCase(
                name = "if binary condition missing rhs keeps then body",
                source = "if value + then work() end\nprint(value)",
                requiredShapeFragments = listOf(
                    "Binary(+,Id(value),ExpressionNodeSupport)",
                    "CallStmt(Call(Id(work):))",
                    "CallStmt(Call(Id(print):Id(value)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            IfChainCase(
                name = "elseif condition missing inserts placeholder and keeps body",
                source = "if first then one() elseif then two() end",
                requiredShapeFragments = listOf(
                    "Clause(Id(first):Block[CallStmt(Call(Id(one):))])",
                    "ElseIf(ExpressionNodeSupport:Block[CallStmt(Call(Id(two):))])"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            IfChainCase(
                name = "elseif binary condition missing rhs keeps elseif body",
                source = "if first then one() elseif second + then two() else three() end",
                requiredShapeFragments = listOf(
                    "ElseIf(Binary(+,Id(second),ExpressionNodeSupport):Block[CallStmt(Call(Id(two):))])",
                    "Else(Block[CallStmt(Call(Id(three):))])"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport")
            )
        )
    }

    @Test
    fun recoversNestedAndChainedIfFormsWithReachableTrailingStatements() {
        assertSupportedIfChainCases(
            IfChainCase(
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
                warningFragments = listOf("The <then> expected")
            ),
            IfChainCase(
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
                warningFragments = listOf("<end> expected")
            ),
            IfChainCase(
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
            IfChainCase(
                name = "if then empty elseif empty else keeps structure and trailing local",
                source = "if ready then elseif other then else end\nlocal after = 1",
                requiredShapeFragments = listOf(
                    "Clause(Id(ready):Block[])",
                    "ElseIf(Id(other):Block[])",
                    "Else(Block[])",
                    "Local(Id(after)=Const(1))"
                )
            )
        )
    }

    @Test
    fun recoversIncompleteBodiesInsideIfBranchesWithoutThrowing() {
        assertSupportedIfChainCases(
            IfChainCase(
                name = "then body incomplete assignment keeps else branch and print",
                source = "if ready then value = value + else fallback() end\nprint(value)",
                requiredShapeFragments = listOf(
                    "Assign(Id(value)=Binary(+,Id(value),ExpressionNodeSupport))",
                    "Else(Block[CallStmt(Call(Id(fallback):))])",
                    "CallStmt(Call(Id(print):Id(value)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            IfChainCase(
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
            IfChainCase(
                name = "else body incomplete local keeps trailing return via following print",
                source = "if ready then work() else local value = end\nprint(ready)",
                requiredShapeFragments = listOf(
                    "Clause(Id(ready):Block[CallStmt(Call(Id(work):))])",
                    "Else(Block[Local(Id(value)=ExpressionNodeSupport)])",
                    "CallStmt(Call(Id(print):Id(ready)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport")
            )
        )
    }

    @Test
    fun exposesTypedClauseStructureForRecoveredIfChains() {
        val recovered = parseRecoveringWithoutThrow(
            "if first then one() elseif second two() else three() end\nprint(first)"
        )

        val ifStmt = assertIs<IfStatement>(recovered.body.statements[0])
        assertEquals(3, ifStmt.causes.size)

        val ifClause = assertIs<IfClause>(ifStmt.causes[0])
        assertEquals("Id(first)", renderShape(ifClause.condition))
        assertIs<CallStatement>(ifClause.body.statements.single())

        val elseIfClause = assertIs<ElseIfClause>(ifStmt.causes[1])
        assertEquals("Id(second)", renderShape(elseIfClause.condition))
        assertIs<CallStatement>(elseIfClause.body.statements.single())

        val elseClause = assertIs<ElseClause>(ifStmt.causes[2])
        assertIs<CallStatement>(elseClause.body.statements.single())

        assertIs<CallStatement>(recovered.body.statements[1])
    }

    @Test
    fun recoveryDiagnosticsArePresentDeterministicAndDoNotThrow() {
        val cases = listOf(
            "if ready work() end",
            "if first then one() elseif second two() else three() end",
            "if ready then work()",
            "if first then one() elseif second then two() else three()",
            "if then work() end",
            "if a then a1() elseif b then b1() elseif c c1() else z1() end"
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
            // Incomplete chains must surface at least one recovery diagnostic.
            assertTrue(
                first.recoveryDiagnostics.isNotEmpty(),
                "expected recovery diagnostics for incomplete if chain: $source"
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
    fun strictParseRejectsIncompleteIfChainsWhileRecoveryDoesNotThrow() {
        val incompleteSources = listOf(
            "if ready work() end",
            "if first then one() elseif second two() else three() end",
            "if ready then work()",
            "if first then one() elseif second then two()",
            "if first then one() elseif second then two() else three()",
            "if then work() end",
            "if value + then work() end",
            "if a then a1() elseif b then b1() elseif c c1() else z1() end"
        )

        incompleteSources.forEach { source ->
            // recovery path: no throw
            parseRecoveringWithoutThrow(source)

            // strict path: deterministic failure
            val first = assertParseFails(LuaVersion.LUA_5_3, source, recovery = false)
            val second = assertParseFails(LuaVersion.LUA_5_3, source, recovery = false)
            assertEquals(first::class, second::class, "strict failure type for: $source")
            assertEquals(first.message, second.message, "strict failure message for: $source")
        }
    }

    @Test
    fun wellFormedIfChainsStillParseCleanlyUnderRecovery() {
        val wellFormed = listOf(
            "if ready then work() end",
            "if ready then work() else fallback() end",
            "if a then one() elseif b then two() else three() end",
            "if a then if b then nested() end end",
            "if ready then local x = 1 print(x) end\nprint(ready)"
        )

        wellFormed.forEach { source ->
            val result = parseWithDiagnosticsWithoutThrow(source)
            assertEquals(
                emptyList(),
                result.recoveryDiagnostics.map { it.message },
                "well-formed if chain should not emit recovery diagnostics: $source"
            )
            assertTrue(
                renderShape(result.chunk).contains("If("),
                "well-formed source should produce If shape: $source"
            )
            // strict parse also succeeds
            val strict = parse(LuaVersion.LUA_5_3, source, recovery = false)
            assertEquals(renderShape(result.chunk), renderShape(strict))
        }
    }

    @Test
    fun corpusInventoryCoversRequiredIfChainFamilies() {
        val inventory = requiredIfChainCases()
        assertEquals(18, inventory.size)

        val names = inventory.map { it.name }.toSet()
        assertTrue(names.any { it.contains("missing then") })
        assertTrue(names.any { it.contains("missing end") })
        assertTrue(names.any { it.contains("condition") })
        assertTrue(names.any { it.contains("nested") || it.contains("elseif chain") })
        assertTrue(names.any { it.contains("body") })
    }

    // --- helpers -----------------------------------------------------------------

    private fun requiredIfChainCases(): List<IfChainCase> {
        // Mirrors the cases exercised by the focused tests above so inventory
        // assertions stay coupled to the corpus surface.
        return listOf(
            IfChainCase("if missing then keeps body and later print", "if ready work() end\nprint(ready)", listOf("If("), warningFragments = listOf("The <then> expected")),
            IfChainCase("elseif missing then keeps elseif and else bodies", "if first then one() elseif second two() else three() end", listOf("ElseIf("), warningFragments = listOf("The <then> expected")),
            IfChainCase("multiple elseif missing then keeps all branches", "if a then one() elseif b two() elseif c three() else four() end", listOf("ElseIf(Id(b):"), warningFragments = listOf("The <then> expected")),
            IfChainCase("if missing end at eof keeps then body", "if ready then work() print(\"after\")", listOf("If("), warningFragments = listOf("<end> expected")),
            IfChainCase("if/elseif missing end at eof keeps both branch bodies", "if first then one() elseif second then two()", listOf("ElseIf("), warningFragments = listOf("<end> expected")),
            IfChainCase("if/elseif/else missing end at eof keeps all branches", "if first then one() elseif second then two() else three()", listOf("Else("), warningFragments = listOf("<end> expected")),
            IfChainCase("if missing then and end still keeps body statements", "if ready work() print(\"tail\")", listOf("CallStmt(Call(Id(work):))"), warningFragments = listOf("The <then> expected", "<end> expected")),
            IfChainCase("if condition missing after if inserts placeholder and keeps then body", "if then work() end\nprint(1)", listOf("ExpressionNodeSupport"), badShapeFragments = listOf("ExpressionNodeSupport")),
            IfChainCase("if binary condition missing rhs keeps then body", "if value + then work() end\nprint(value)", listOf("Binary(+,Id(value),ExpressionNodeSupport)"), badShapeFragments = listOf("ExpressionNodeSupport")),
            IfChainCase("elseif condition missing inserts placeholder and keeps body", "if first then one() elseif then two() end", listOf("ElseIf(ExpressionNodeSupport:"), badShapeFragments = listOf("ExpressionNodeSupport")),
            IfChainCase("elseif binary condition missing rhs keeps elseif body", "if first then one() elseif second + then two() else three() end", listOf("ElseIf(Binary(+,Id(second),ExpressionNodeSupport):"), badShapeFragments = listOf("ExpressionNodeSupport")),
            IfChainCase("nested if missing inner then keeps outer and trailing print", "if outer then\n  if inner work() end\nend\nprint(outer)", listOf("If(Clause(Id(inner):"), warningFragments = listOf("The <then> expected")),
            IfChainCase("nested if missing inner end keeps outer else and trailing print", "if outer then\n  if inner then work()\nelse\n  fallback()\nend\nprint(done)", listOf("Else(Block[CallStmt(Call(Id(fallback):))])"), warningFragments = listOf("<end> expected")),
            IfChainCase("long elseif chain missing one then keeps remaining branches", "if a then a1()\nelseif b then b1()\nelseif c c1()\nelseif d then d1()\nelse z1()\nend\nprint(z)", listOf("ElseIf(Id(c):"), warningFragments = listOf("The <then> expected")),
            IfChainCase("if then empty elseif empty else keeps structure and trailing local", "if ready then elseif other then else end\nlocal after = 1", listOf("Local(Id(after)=Const(1))")),
            IfChainCase("then body incomplete assignment keeps else branch and print", "if ready then value = value + else fallback() end\nprint(value)", listOf("Else(Block[CallStmt(Call(Id(fallback):))])"), badShapeFragments = listOf("ExpressionNodeSupport")),
            IfChainCase("elseif body incomplete call keeps later else and print", "if a then one() elseif b then two( else three() end\nprint(b)", listOf("Else(Block[CallStmt(Call(Id(three):))])"), warningFragments = listOf("')' expected")),
            IfChainCase("else body incomplete local keeps trailing return via following print", "if ready then work() else local value = end\nprint(ready)", listOf("Else(Block[Local(Id(value)=ExpressionNodeSupport)])"), badShapeFragments = listOf("ExpressionNodeSupport"))
        )
    }

    private fun assertSupportedIfChainCases(vararg cases: IfChainCase) {
        cases.forEach(::assertSupportedIfChainCase)
    }

    private fun assertSupportedIfChainCase(case: IfChainCase) {
        val first = recoverTwiceAndAssertDeterministic(case)
        assertShapeFragments(case, first.chunk)
        assertBadShapeFragments(case, first.chunk)
        assertWarningFragments(case, first.warnings)

        if (case.expectStrictRejection) {
            val fail1 = assertParseFails(case.version, case.source, recovery = false)
            val fail2 = assertParseFails(case.version, case.source, recovery = false)
            assertEquals(fail1::class, fail2::class, "${case.name} strict failure type")
            assertEquals(fail1.message, fail2.message, "${case.name} strict failure message")
        }
    }

    private fun recoverTwiceAndAssertDeterministic(case: IfChainCase): RecoveryRun {
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

    private fun assertShapeFragments(case: IfChainCase, chunk: ChunkNode) {
        val shape = renderShape(chunk)
        case.requiredShapeFragments.forEach { expected ->
            assertTrue(
                shape.contains(expected),
                "${case.name} should keep '$expected' reachable in recovered shape:\n$shape"
            )
        }
    }

    private fun assertBadShapeFragments(case: IfChainCase, chunk: ChunkNode) {
        if (case.badShapeFragments.isEmpty()) return
        val shape = renderShape(chunk)
        case.badShapeFragments.forEach { expected ->
            assertTrue(
                shape.contains(expected),
                "${case.name} should mark recovered node containing '$expected' as present; shape:\n$shape"
            )
        }
    }

    private fun assertWarningFragments(case: IfChainCase, warnings: List<String>) {
        case.warningFragments.forEach { expected ->
            assertTrue(
                warnings.any { it.contains(expected) },
                "${case.name} should emit warning containing '$expected'; actual warnings: $warnings"
            )
        }
    }

    private data class IfChainCase(
        val name: String,
        val source: String,
        val requiredShapeFragments: List<String>,
        val version: LuaVersion = LuaVersion.LUA_5_3,
        val badShapeFragments: List<String> = emptyList(),
        val warningFragments: List<String> = emptyList(),
        val expectStrictRejection: Boolean = true,
    )

    private data class RecoveryRun(
        val chunk: ChunkNode,
        val warnings: List<String>,
    )
}
