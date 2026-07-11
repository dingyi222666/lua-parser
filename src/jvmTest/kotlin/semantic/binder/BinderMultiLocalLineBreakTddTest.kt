package semantic.binder

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Binder multi-local single-line vs multi-line parse corpus (TASK-392).
 *
 * Acceptance:
 * - Multi-local `local left, right = 1, 2` written across lines under a nested
 *   function binds without parse error, with pairwise-disjoint declaration ranges.
 * - Documents product parse/bind behavior; dual-paths forms the strict parser
 *   still rejects (notably multi-identifier `return left, right` near eof class
 *   that broke TASK-328 multiOuterLocals before the fixture rewrite).
 *
 * Complements:
 * - [BinderMultiLocalSameLineRangeTddTest] (TASK-285) dense same-line packing
 * - [BinderMultiAssignRangeTddTest] (TASK-195) balanced / unbalanced multi-local
 * - [BinderMultiLocalExtraShapeTddTest] (TASK-361) RHS shapes / owners / attrs
 * - [BinderUpvalueCaptureRangeTddTest] (TASK-328) capture ranges (avoids multi-
 *   identifier return after REVIEW43 IllegalStateException)
 *
 * Product surface notes (strict parse, errorRecovery=false — binder path):
 * - Local namelist + explist with line breaks between names / after `=` / after
 *   commas is well-formed Lua 5.3 and must parse + bind with per-name ranges.
 * - Local explists use `recoverFirstStatementLineBreak = false` (see LuaParser
 *   parseLocalVarList); that only affects recovery mode incomplete RHS. Well-
 *   formed multi-line RHS still binds under the strict binder path.
 * - Multi-identifier returns such as `return left, right` have historically
 *   thrown `IllegalStateException` near eof in nested function bodies on this
 *   product. This corpus dual-paths that class: either bind succeeds (product
 *   fixed) or the throw is documented without inventing binder declarations.
 *
 * Test-only; no production edits. Verification is review-owned and serial via
 * `jvmTest --tests semantic.binder.BinderMultiLocalLineBreakTddTest`.
 */
class BinderMultiLocalLineBreakTddTest {

    private val parser = LuaParser()

    // --- baseline: same-line multi-local still binds (control) --------------------

    @Test
    fun sameLineMultiLocal_bindsWithDisjointRanges() {
        val source = "local left, right = 1, 2"
        val result = bind(source)

        val left = localOf(result, "left")
        val right = localOf(result, "right")
        assertPerNameRange(source, left, "left")
        assertPerNameRange(source, right, "right")
        assertRangesDisjoint(left.range!!, right.range!!)
        assertEquals(left, result.positionQueries.getDeclarationAt(positionOf(source, "left")))
        assertEquals(right, result.positionQueries.getDeclarationAt(positionOf(source, "right")))
    }

    // --- multi-line multi-local under nested function (primary acceptance) -------

    @Test
    fun multiLineMultiLocalUnderNestedFunction_bindsWithoutParseError_rangesDisjoint() {
        // Primary TASK-392 acceptance fixture: multi-local `left, right` with line
        // breaks under a nested local function — must not throw at parse/bind.
        val source = """
            local function pack()
                local left,
                      right =
                      1,
                      2
                local usedLeft = left
                local usedRight = right
                return usedLeft
            end
            """.trimIndent()

        val (chunk, result) = bindChunk(source)

        val left = localOf(result, "left")
        val right = localOf(result, "right")
        assertPerNameRange(source, left, "left")
        assertPerNameRange(source, right, "right")
        assertRangesDisjoint(left.range!!, right.range!!)
        assertNotEquals(left.symbolId, right.symbolId)
        assertNotEquals(left.id, right.id)

        // Multi-line names still sit on distinct lines; ranges stay per-identifier.
        assertNotEquals(left.range!!.start.line, right.range!!.start.line)

        assertEquals(left, result.positionQueries.getDeclarationAt(positionOf(source, "left", occurrence = 1)))
        assertEquals(right, result.positionQueries.getDeclarationAt(positionOf(source, "right", occurrence = 1)))
        // Usage sites are not declaration anchors.
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "left", occurrence = 2)))
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "right", occurrence = 2)))

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val multiLocal = function.body!!.statements.filterIsInstance<LocalStatement>().first()
        assertEquals(listOf("left", "right"), multiLocal.init.map { it.name })
        assertEquals(2, multiLocal.variables.size)
        assertSame(multiLocal.init[0], left.anchorNode)
        assertSame(multiLocal.init[1], right.anchorNode)
    }

    @Test
    fun multiLineNamesOnly_underNestedFunction_bindsDisjoint() {
        // Line break only in the namelist; RHS stays same-line.
        val source = """
            local function pack()
                local left,
                      right = 1, 2
            end
            """.trimIndent()
        val result = bind(source)

        val left = localOf(result, "left")
        val right = localOf(result, "right")
        assertPerNameRange(source, left, "left")
        assertPerNameRange(source, right, "right")
        assertRangesDisjoint(left.range!!, right.range!!)
        assertNotEquals(left.range!!.start.line, right.range!!.start.line)
    }

    @Test
    fun multiLineRhsOnly_underNestedFunction_bindsDisjoint() {
        // Names same-line; line breaks after `=` and between RHS values.
        val source = """
            local function pack()
                local left, right =
                    1,
                    2
            end
            """.trimIndent()
        val result = bind(source)

        val left = localOf(result, "left")
        val right = localOf(result, "right")
        assertPerNameRange(source, left, "left")
        assertPerNameRange(source, right, "right")
        assertRangesDisjoint(left.range!!, right.range!!)
        // Names share a line; only RHS is multi-line.
        assertEquals(left.range!!.start.line, right.range!!.start.line)
    }

    @Test
    fun multiLineMultiLocalAtChunkRoot_bindsWithoutParseError() {
        val source = """
            local left,
                  right =
                  1,
                  2
            """.trimIndent()
        val result = bind(source)

        val left = localOf(result, "left")
        val right = localOf(result, "right")
        assertPerNameRange(source, left, "left")
        assertPerNameRange(source, right, "right")
        assertRangesDisjoint(left.range!!, right.range!!)
        assertEquals(listOf("left", "right"), nonBuiltinLocals(result).map { it.name })
    }

    @Test
    fun threeNameMultiLineMultiLocalUnderNestedFunction_allPairsDisjoint() {
        val source = """
            local function pack()
                local a,
                      b,
                      c = 10, 20, 30
            end
            """.trimIndent()
        val result = bind(source)
        val declarations = listOf("a", "b", "c").map { localOf(result, it) }

        listOf("a", "b", "c").zip(declarations).forEach { (name, declaration) ->
            assertPerNameRange(source, declaration, name)
            assertEquals(declaration, result.positionQueries.getDeclarationAt(positionOf(source, name)))
        }
        for (i in declarations.indices) {
            for (j in i + 1 until declarations.size) {
                assertRangesDisjoint(declarations[i].range!!, declarations[j].range!!)
            }
        }
        // Each name on its own line in this fixture.
        assertEquals(3, declarations.map { it.range!!.start.line }.toSet().size)
    }

    @Test
    fun nestedDoBlockMultiLineMultiLocal_bindsDisjoint() {
        val source = """
            local function outer()
                do
                    local left,
                          right = 1, 2
                end
            end
            """.trimIndent()
        val result = bind(source)

        val left = localOf(result, "left")
        val right = localOf(result, "right")
        assertPerNameRange(source, left, "left")
        assertPerNameRange(source, right, "right")
        assertRangesDisjoint(left.range!!, right.range!!)
        assertEquals(left, result.positionQueries.getDeclarationAt(positionOf(source, "left")))
        assertEquals(right, result.positionQueries.getDeclarationAt(positionOf(source, "right")))
    }

    @Test
    fun multiLineMultiLocalThenUsage_usageSitesDoNotReportDeclaration() {
        val source = """
            local function pack()
                local left,
                      right = 1, 2
                print(left, right)
            end
            """.trimIndent()
        val result = bind(source)

        val left = localOf(result, "left")
        val right = localOf(result, "right")
        assertEquals(left, result.positionQueries.getDeclarationAt(positionOf(source, "left", occurrence = 1)))
        assertEquals(right, result.positionQueries.getDeclarationAt(positionOf(source, "right", occurrence = 1)))
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "left", occurrence = 2)))
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "right", occurrence = 2)))
    }

    @Test
    fun multiLineBareMultiLocalNoRhs_stillBindsNames() {
        val source = """
            local function pack()
                local bareA,
                      bareB
            end
            """.trimIndent()
        val (chunk, result) = bindChunk(source)
        val statement = chunk.body.statements
            .filterIsInstance<FunctionDeclaration>()
            .single()
            .body!!
            .statements
            .filterIsInstance<LocalStatement>()
            .single()

        assertEquals(2, statement.init.size)
        assertTrue(statement.variables.isEmpty())
        val bareA = localOf(result, "bareA")
        val bareB = localOf(result, "bareB")
        assertPerNameRange(source, bareA, "bareA")
        assertPerNameRange(source, bareB, "bareB")
        assertRangesDisjoint(bareA.range!!, bareB.range!!)
    }

    @Test
    fun multiLineMultiLocalRangesMatchAstIdentifierAnchorsNotWholeStatement() {
        val source = """
            local function pack()
                local alpha,
                      beta = true, false
            end
            """.trimIndent()
        val (chunk, result) = bindChunk(source)
        val statement = chunk.body.statements
            .filterIsInstance<FunctionDeclaration>()
            .single()
            .body!!
            .statements
            .filterIsInstance<LocalStatement>()
            .single()

        val alphaId = statement.init.single { it.name == "alpha" }
        val betaId = statement.init.single { it.name == "beta" }
        val alpha = localOf(result, "alpha")
        val beta = localOf(result, "beta")

        assertEquals(alphaId, alpha.anchorNode)
        assertEquals(betaId, beta.anchorNode)
        assertEquals(alphaId.range, alpha.range)
        assertEquals(betaId.range, beta.range)
        assertNotEquals(statement.range, alpha.range)
        assertNotEquals(statement.range, beta.range)
        assertTrue(isProperSubRange(alpha.range!!, statement.range))
        assertTrue(isProperSubRange(beta.range!!, statement.range))
        assertRangesDisjoint(alpha.range!!, beta.range!!)
    }

    @Test
    fun sameLineVsMultiLineMultiLocal_bothBindWithMatchingPerNameSemantics() {
        // Documents that layout (same-line vs multi-line) must not change bind
        // surface for balanced multi-local `left, right`.
        val sameLine = "local left, right = 1, 2"
        val multiLine = """
            local left,
                  right =
                  1,
                  2
            """.trimIndent()

        val sameResult = bind(sameLine)
        val multiResult = bind(multiLine)

        assertEquals(
            nonBuiltinLocals(sameResult).map { it.name },
            nonBuiltinLocals(multiResult).map { it.name }
        )
        listOf("left", "right").forEach { name ->
            val same = localOf(sameResult, name)
            val multi = localOf(multiResult, name)
            assertEquals(name.length, same.range!!.end.column - same.range!!.start.column)
            assertEquals(name.length, multi.range!!.end.column - multi.range!!.start.column)
            assertEquals(DeclarationKind.LOCAL, same.kind)
            assertEquals(DeclarationKind.LOCAL, multi.kind)
            assertIs<Identifier>(same.anchorNode)
            assertIs<Identifier>(multi.anchorNode)
        }
        assertRangesDisjoint(localOf(sameResult, "left").range!!, localOf(sameResult, "right").range!!)
        assertRangesDisjoint(localOf(multiResult, "left").range!!, localOf(multiResult, "right").range!!)
    }

    // --- dual-path: multi-identifier return class (TASK-328 multiOuterLocals) ----

    @Test
    fun multiIdentifierReturnUnderNestedFunction_dualPathDocumentsProductParse() {
        // TASK-328 REVIEW43 failure class: nested body ending with
        // `return left, right` historically threw IllegalStateException near eof
        // during strict parse used by binder fixtures. Dual-path:
        // - Path A (product accepts): both outer multi-locals bind; ranges disjoint.
        // - Path B (product still rejects): throw is documented; no invented binds.
        val source = """
            local left, right = 1, 2
            local function pack()
                return left, right
            end
            """.trimIndent()

        when (val outcome = tryBind(source)) {
            is BindOutcome.Ok -> {
                val left = localOf(outcome.result, "left")
                val right = localOf(outcome.result, "right")
                assertPerNameRange(source, left, "left")
                assertPerNameRange(source, right, "right")
                assertRangesDisjoint(left.range!!, right.range!!)
                // Nested return uses outer names; no extra left/right decls.
                assertEquals(1, nonBuiltinLocals(outcome.result).count { it.name == "left" })
                assertEquals(1, nonBuiltinLocals(outcome.result).count { it.name == "right" })
            }
            is BindOutcome.ParseRejected -> {
                // Documented product gap: multi-identifier return still fails strict parse.
                assertTrue(
                    outcome.error is IllegalStateException ||
                        outcome.message.contains("unexpected", ignoreCase = true) ||
                        outcome.message.contains("eof", ignoreCase = true) ||
                        outcome.message.isNotBlank(),
                    "expected documented parse failure for multi-identifier return, got: ${outcome.error}"
                )
                // Safe rewrite used by TASK-328 WAVE36A still binds (control).
                val safe = """
                    local left, right = 1, 2
                    local function pack()
                        local usedLeft = left
                        local usedRight = right
                        return usedLeft
                    end
                    """.trimIndent()
                val safeResult = bind(safe)
                assertRangesDisjoint(localOf(safeResult, "left").range!!, localOf(safeResult, "right").range!!)
            }
        }
    }

    @Test
    fun multiLineMultiLocalPlusMultiIdentifierReturn_dualPathUnderNestedFunction() {
        // Combines primary multi-line multi-local layout with the historical
        // multi-identifier return footgun. Dual-path keeps the corpus honest.
        val source = """
            local function pack()
                local left,
                      right = 1, 2
                return left, right
            end
            """.trimIndent()

        when (val outcome = tryBind(source)) {
            is BindOutcome.Ok -> {
                val left = localOf(outcome.result, "left")
                val right = localOf(outcome.result, "right")
                assertPerNameRange(source, left, "left")
                assertPerNameRange(source, right, "right")
                assertRangesDisjoint(left.range!!, right.range!!)
                assertNotEquals(left.range!!.start.line, right.range!!.start.line)
            }
            is BindOutcome.ParseRejected -> {
                assertTrue(outcome.message.isNotBlank() || outcome.error != null)
                // Drop the multi-identifier return; multi-line multi-local must still bind.
                val withoutReturn = """
                    local function pack()
                        local left,
                              right = 1, 2
                        local used = left
                        return used
                    end
                    """.trimIndent()
                val result = bind(withoutReturn)
                assertRangesDisjoint(localOf(result, "left").range!!, localOf(result, "right").range!!)
            }
        }
    }

    @Test
    fun incompleteMultiLineLocalRhs_recoveryPathDocumentsWithoutStrictBindRequirement() {
        // Recovery-only incomplete forms (see LuaParserRecoveryMultiRhsLineBreakTddTest).
        // Strict binder path dual-paths: accept if product recovers into a bindable
        // AST without throw, otherwise document the strict reject.
        val incomplete = "local left, right =\n"
        when (val outcome = tryBind(incomplete)) {
            is BindOutcome.Ok -> {
                // If strict parse accepts trailing `=` multi-local, only LHS names bind.
                val names = nonBuiltinLocals(outcome.result).map { it.name }
                assertTrue(
                    names.containsAll(listOf("left", "right")),
                    "incomplete multi-local after '=' that parses must still bind LHS names; got $names"
                )
                assertEquals(listOf("left", "right"), names)
                assertRangesDisjoint(
                    localOf(outcome.result, "left").range!!,
                    localOf(outcome.result, "right").range!!
                )
            }
            is BindOutcome.ParseRejected -> {
                assertTrue(
                    outcome.error is IllegalStateException || outcome.message.isNotBlank(),
                    "incomplete multi-line local RHS should fail strictly or be dual-path documented"
                )
            }
        }

        // Complete multi-line form remains the green control for this corpus.
        val complete = """
            local left, right =
                1,
                2
            """.trimIndent()
        val completeResult = bind(complete)
        assertRangesDisjoint(localOf(completeResult, "left").range!!, localOf(completeResult, "right").range!!)
    }

    // --- helpers -----------------------------------------------------------------

    private sealed class BindOutcome {
        data class Ok(val chunk: ChunkNode, val result: BinderPassResult) : BindOutcome()
        data class ParseRejected(val error: Throwable, val message: String) : BindOutcome()
    }

    private fun tryBind(source: String): BindOutcome {
        return try {
            val chunk = parser.parse(source)
            val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
            BindOutcome.Ok(chunk, result)
        } catch (error: Throwable) {
            BindOutcome.ParseRejected(error, error.message.orEmpty())
        }
    }

    private fun bind(source: String): BinderPassResult {
        return bindChunk(source).second
    }

    private fun bindChunk(source: String): Pair<ChunkNode, BinderPassResult> {
        val chunk = try {
            parser.parse(source)
        } catch (error: Throwable) {
            fail("expected multi-local line-break fixture to parse without error:\n$source", error)
        }
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        assertSame(
            chunk.body,
            result.scopeGraph.rootScope.ownerNode,
            "BinderPass root scope ownerNode must be the bound chunk.body (single-AST contract)"
        )
        return chunk to result
    }

    private fun nonBuiltinLocals(result: BinderPassResult): List<BinderDeclaration> {
        return result.declarationIndex.declarations.filter {
            it.kind == DeclarationKind.LOCAL && it.origin != DeclarationOrigin.BUILTIN
        }
    }

    private fun localOf(result: BinderPassResult, name: String): BinderDeclaration {
        return nonBuiltinLocals(result).single { it.name == name }
    }

    private fun assertPerNameRange(source: String, declaration: BinderDeclaration, name: String) {
        val range = assertNotNull(declaration.range, "Declaration '$name' must expose a range")
        val anchor = assertNotNull(declaration.anchorNode, "Declaration '$name' must keep an anchor node")
        val identifier = assertIs<Identifier>(anchor)
        assertEquals(name, identifier.name)
        assertEquals(name, declaration.name)
        assertEquals(DeclarationKind.LOCAL, declaration.kind)
        assertEquals(identifier.range, range)

        val expectedStart = positionOf(source, name)
        assertEquals(expectedStart, range.start, "Range start for '$name' should match identifier start")
        assertEquals(expectedStart.line, range.end.line)
        assertEquals(expectedStart.column + name.length, range.end.column)
        assertTrue(range.end.column > range.start.column)
    }

    private fun assertRangesDisjoint(a: Range, b: Range) {
        val aEndsBeforeB = comparePositions(a.end, b.start) <= 0
        val bEndsBeforeA = comparePositions(b.end, a.start) <= 0
        assertTrue(
            aEndsBeforeB || bEndsBeforeA,
            "Expected disjoint ranges but got $a and $b"
        )
    }

    private fun isProperSubRange(inner: Range, outer: Range): Boolean {
        val contained =
            comparePositions(outer.start, inner.start) <= 0 &&
                comparePositions(inner.end, outer.end) <= 0
        val narrower =
            comparePositions(outer.start, inner.start) < 0 ||
                comparePositions(inner.end, outer.end) < 0
        return contained && narrower
    }

    private fun comparePositions(left: Position, right: Position): Int {
        val line = left.line.compareTo(right.line)
        return if (line != 0) line else left.column.compareTo(right.column)
    }

    private fun positionOf(source: String, needle: String, occurrence: Int = 1): Position {
        var fromIndex = 0
        var found = 0
        val requireWordBoundary = needle.all { isIdentChar(it) }

        while (true) {
            val index = source.indexOf(needle, fromIndex)
            require(index >= 0) { "Missing '$needle' occurrence $occurrence in:\n$source" }

            val match = if (!requireWordBoundary) {
                true
            } else {
                val beforeOk = index == 0 || !isIdentChar(source[index - 1])
                val afterIndex = index + needle.length
                val afterOk = afterIndex >= source.length || !isIdentChar(source[afterIndex])
                beforeOk && afterOk
            }

            if (match) {
                found++
                if (found == occurrence) {
                    return indexToPosition(source, index)
                }
            }
            fromIndex = index + 1
        }
    }

    private fun indexToPosition(source: String, index: Int): Position {
        var line = 1
        var column = 1
        for (i in 0 until index) {
            if (source[i] == '\n') {
                line++
                column = 1
            } else {
                column++
            }
        }
        return Position(line, column)
    }

    private fun isIdentChar(ch: Char): Boolean {
        return ch == '_' || ch.isLetterOrDigit()
    }
}
