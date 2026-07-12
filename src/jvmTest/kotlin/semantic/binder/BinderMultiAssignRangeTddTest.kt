package semantic.binder

import io.github.dingyi222666.luaparser.parser.LuaParser
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
import kotlin.test.assertTrue

/**
 * Binder multi-assign / multi-local declaration-range corpus (TASK-195 / TASK-654).
 *
 * Acceptance:
 * - Local multi-assign declaration ranges are queryable per name (distinct
 *   identifier ranges, positionQueries + declarationIndex).
 * - Unbalanced RHS/LHS remains conservative: every LHS name still binds, no
 *   phantom names from extra RHS, no shared/statement-wide ranges, multi-local
 *   type syntax stays unresolved.
 * - Bare free-name multi-LHS assignment invents per-name AST GLOBAL (identifier
 *   ranges only; commas invent none) — aligned with BinderGlobalAssignment*.
 *
 * Test-only; production defects surface as assertion failures (review-owned
 * verification via `jvmTest --tests semantic.binder.BinderMultiAssignRangeTddTest`).
 */
class BinderMultiAssignRangeTddTest {

    private val parser = LuaParser()

    // --- balanced multi-local: per-name ranges --------------------------------

    @Test
    fun balancedTwoNameLocalAssign_rangesQueryablePerNameAtIdentifierSites() {
        val source = "local first, second = 1, 2"
        val result = bind(source)

        val first = localOf(result, "first")
        val second = localOf(result, "second")

        assertPerNameRange(source, first, "first")
        assertPerNameRange(source, second, "second")
        assertRangesDisjoint(first.range!!, second.range!!)

        assertEquals(first, result.positionQueries.getDeclarationAt(positionOf(source, "first")))
        assertEquals(second, result.positionQueries.getDeclarationAt(positionOf(source, "second")))
        assertEquals(first.symbolId, result.positionQueries.getSymbolAt(positionOf(source, "first"))?.id)
        assertEquals(second.symbolId, result.positionQueries.getSymbolAt(positionOf(source, "second"))?.id)
    }

    @Test
    fun balancedThreeNameLocalAssign_eachNameHasOwnAnchorAndQueryHit() {
        val source = "local a, b, c = 10, 20, 30"
        val result = bind(source)

        val names = listOf("a", "b", "c")
        val declarations = names.map { localOf(result, it) }

        names.zip(declarations).forEach { (name, declaration) ->
            assertPerNameRange(source, declaration, name)
            assertEquals(declaration, result.positionQueries.getDeclarationAt(positionOf(source, name)))
        }

        // pairwise disjoint identifier ranges
        for (i in declarations.indices) {
            for (j in i + 1 until declarations.size) {
                assertRangesDisjoint(declarations[i].range!!, declarations[j].range!!)
            }
        }
    }

    @Test
    fun multiLocalRangesMatchAstIdentifierAnchorsNotWholeStatement() {
        val source = "local alpha, beta = true, false"
        val chunk = parser.parse(source)
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val statement = chunk.body.statements.filterIsInstance<LocalStatement>().single()

        assertEquals(2, statement.init.size)
        assertEquals(2, statement.variables.size)

        val alphaId = statement.init.single { it.name == "alpha" }
        val betaId = statement.init.single { it.name == "beta" }
        val alpha = localOf(result, "alpha")
        val beta = localOf(result, "beta")

        assertEquals(alphaId, alpha.anchorNode)
        assertEquals(betaId, beta.anchorNode)
        assertEquals(alphaId.range, alpha.range)
        assertEquals(betaId.range, beta.range)

        // Declaration range is the identifier token, not the full LocalStatement.
        // LocalStatement.range may start at `local` or at the first name depending on
        // how the parser marks the statement after the LOCAL/FUNCTION peek; either
        // way each declaration must stay a proper sub-span of the statement.
        assertNotEquals(statement.range, alpha.range)
        assertNotEquals(statement.range, beta.range)
        assertTrue(isProperSubRange(alpha.range!!, statement.range), "alpha range must be narrower than LocalStatement")
        assertTrue(isProperSubRange(beta.range!!, statement.range), "beta range must be narrower than LocalStatement")
        assertRangesDisjoint(alpha.range!!, beta.range!!)
    }

    @Test
    fun positionBetweenNamesDoesNotResolveEitherDeclaration() {
        // "local x, y = 1, 2" — column of the comma is between the two names.
        val source = "local x, y = 1, 2"
        val result = bind(source)
        val commaPosition = positionOf(source, ",")

        assertNull(result.positionQueries.getDeclarationAt(commaPosition))
        assertTrue(result.positionQueries.getDeclarationsAt(commaPosition).isEmpty())
    }

    @Test
    fun usageSitesDoNotReportDeclarationRangesOfMultiLocals() {
        val source = """
            local left, right = 1, 2
            print(left, right)
            """.trimIndent()
        val result = bind(source)

        val leftDecl = localOf(result, "left")
        val rightDecl = localOf(result, "right")

        // Declaration sites still resolve.
        assertEquals(leftDecl, result.positionQueries.getDeclarationAt(positionOf(source, "left", occurrence = 1)))
        assertEquals(rightDecl, result.positionQueries.getDeclarationAt(positionOf(source, "right", occurrence = 1)))

        // Usage sites are not declaration anchors (binder position queries are declaration-site only).
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "left", occurrence = 2)))
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "right", occurrence = 2)))
    }

    // --- unbalanced LHS/RHS remains conservative ------------------------------

    @Test
    fun fewerRhsThanLhs_stillBindsEveryLhsNameWithOwnRange() {
        // Unbalanced: three names, one RHS value.
        val source = "local one, two, three = 1"
        val result = bind(source)
        val locals = nonBuiltinLocals(result)

        assertEquals(listOf("one", "two", "three"), locals.map { it.name })
        listOf("one", "two", "three").forEach { name ->
            val declaration = localOf(result, name)
            assertPerNameRange(source, declaration, name)
            assertEquals(declaration, result.positionQueries.getDeclarationAt(positionOf(source, name)))
        }

        // No phantom fourth name invented from missing RHS slots.
        assertEquals(3, locals.size)
        assertNull(result.declarationIndex.declarations.find {
            it.origin != DeclarationOrigin.BUILTIN && it.name !in setOf("one", "two", "three") &&
                it.kind == DeclarationKind.LOCAL
        })
    }

    @Test
    fun moreRhsThanLhs_bindsOnlyLhsNamesAndIgnoresExtraRhs() {
        // Unbalanced: one name, three RHS values.
        val source = "local only = 1, 2, 3"
        val result = bind(source)
        val locals = nonBuiltinLocals(result)

        assertEquals(listOf("only"), locals.map { it.name })
        val only = localOf(result, "only")
        assertPerNameRange(source, only, "only")
        assertEquals(only, result.positionQueries.getDeclarationAt(positionOf(source, "only")))

        // Extra RHS literals must not surface as declarations.
        assertTrue(locals.none { it.name == "1" || it.name == "2" || it.name == "3" })
        assertEquals(1, locals.size)
    }

    @Test
    fun multiLocalWithoutRhs_bindsAllNamesConservatively() {
        val source = "local bareA, bareB, bareC"
        val result = bind(source)
        val locals = nonBuiltinLocals(result)

        assertEquals(listOf("bareA", "bareB", "bareC"), locals.map { it.name })
        listOf("bareA", "bareB", "bareC").forEach { name ->
            val declaration = localOf(result, name)
            assertPerNameRange(source, declaration, name)
            assertEquals(declaration, result.positionQueries.getDeclarationAt(positionOf(source, name)))
        }
    }

    @Test
    fun unbalancedMultiLocal_doesNotShareASingleStatementRangeAcrossNames() {
        val source = "local p, q, r = 0"
        val result = bind(source)

        val p = localOf(result, "p")
        val q = localOf(result, "q")
        val r = localOf(result, "r")

        // Conservative: each name keeps its own identifier range even when RHS is short.
        assertNotEquals(p.range, q.range)
        assertNotEquals(q.range, r.range)
        assertNotEquals(p.range, r.range)
        assertRangesDisjoint(p.range!!, q.range!!)
        assertRangesDisjoint(q.range!!, r.range!!)
        assertRangesDisjoint(p.range!!, r.range!!)
    }

    @Test
    fun multiLocalDocTypeMappingStaysUnresolvedConservatively() {
        // Multi-name + multi-slot @type must not invent per-slot type syntax.
        val source = """
            ---@type string, number, boolean
            local s, n, b = "a", 1, true
            """.trimIndent()
        val result = bind(source)
        val locals = listOf("s", "n", "b").map { localOf(result, it) }

        assertTrue(locals.all { it.declaredTypeSyntax == null })
        assertTrue(locals.all { it.documentation != null })
        // Ranges remain per-name and queryable.
        locals.forEach { declaration ->
            assertPerNameRange(source, declaration, declaration.name)
            assertEquals(
                declaration,
                result.positionQueries.getDeclarationAt(positionOf(source, declaration.name))
            )
        }
    }

    @Test
    fun emptyRhsMultiLocal_stillQueryablePerName() {
        // Grammar allows `local a, b` (no `=`). Extra empty RHS is already covered;
        // this documents the zero-RHS multi-name case used by recovery-ish sources.
        val source = "local emptyLeft, emptyRight"
        val chunk = parser.parse(source)
        val statement = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))

        assertEquals(2, statement.init.size)
        assertTrue(statement.variables.isEmpty())

        val left = localOf(result, "emptyLeft")
        val right = localOf(result, "emptyRight")
        assertEquals(statement.init[0], left.anchorNode)
        assertEquals(statement.init[1], right.anchorNode)
        assertEquals(left, result.positionQueries.getDeclarationAt(positionOf(source, "emptyLeft")))
        assertEquals(right, result.positionQueries.getDeclarationAt(positionOf(source, "emptyRight")))
    }

    // --- multi-assign (assignment statement) invents per-name GLOBAL on free names -

    @Test
    fun multiAssignWithoutLocal_createsGlobalDeclarationsForLhsNames() {
        // Product binder (TASK-558 / TASK-654): bare free-name multi-LHS first-write
        // invents AST GLOBAL per name with identifier-only ranges.
        val source = """
            a, b = 1, 2
            x, y, z = f()
            """.trimIndent()
        val result = bind(source)

        listOf("a", "b", "x", "y", "z").forEach { name ->
            val matches = result.declarationIndex.declarations.filter {
                it.name == name &&
                    it.kind == DeclarationKind.GLOBAL &&
                    it.origin != DeclarationOrigin.BUILTIN
            }
            assertEquals(1, matches.size, "Expected one GLOBAL declaration for multi-LHS '$name'")
            val decl = matches.single()
            assertEquals(DeclarationKind.GLOBAL, decl.kind)
            assertEquals(name, decl.name)
            val range = assertNotNull(decl.range, "GLOBAL multi-LHS '$name' must expose a range")
            val anchor = assertIs<Identifier>(assertNotNull(decl.anchorNode))
            assertEquals(name, anchor.name)
            assertEquals(anchor.range, range)
            assertEquals(decl, result.positionQueries.getDeclarationAt(positionOf(source, name)))
        }
        // Commas invent no declaration ranges.
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, ",")))
    }

    @Test
    fun unbalancedAssignmentMultiLhs_stillCreatesGlobalsPerName() {
        val source = "u, v, w = 1"
        val result = bind(source)

        listOf("u", "v", "w").forEach { name ->
            val matches = result.declarationIndex.declarations.filter {
                it.name == name &&
                    it.kind == DeclarationKind.GLOBAL &&
                    it.origin != DeclarationOrigin.BUILTIN
            }
            assertEquals(1, matches.size, "Expected GLOBAL for unbalanced multi-LHS '$name'")
            val decl = matches.single()
            assertEquals(decl, result.positionQueries.getDeclarationAt(positionOf(source, name)))
            assertEquals(name, assertIs<Identifier>(assertNotNull(decl.anchorNode)).name)
            assertEquals(assertIs<Identifier>(decl.anchorNode).range, decl.range)
        }
    }

    // --- nested / mixed multi-local corpora -----------------------------------

    @Test
    fun nestedBlockMultiLocals_keepIndependentPerNameRanges() {
        val source = """
            local outerA, outerB = 1, 2
            do
                local innerA, innerB = 3, 4
            end
            """.trimIndent()
        val result = bind(source)

        listOf("outerA", "outerB", "innerA", "innerB").forEach { name ->
            val declaration = localOf(result, name)
            assertPerNameRange(source, declaration, name)
            assertEquals(declaration, result.positionQueries.getDeclarationAt(positionOf(source, name)))
        }

        assertRangesDisjoint(localOf(result, "outerA").range!!, localOf(result, "outerB").range!!)
        assertRangesDisjoint(localOf(result, "innerA").range!!, localOf(result, "innerB").range!!)
    }

    @Test
    fun multiLocalThenSingleLocal_rangesDoNotBleedAcrossStatements() {
        val source = """
            local m1, m2 = 1, 2
            local solo = 3
            """.trimIndent()
        val result = bind(source)

        val m1 = localOf(result, "m1")
        val m2 = localOf(result, "m2")
        val solo = localOf(result, "solo")

        assertEquals(m1, result.positionQueries.getDeclarationAt(positionOf(source, "m1")))
        assertEquals(m2, result.positionQueries.getDeclarationAt(positionOf(source, "m2")))
        assertEquals(solo, result.positionQueries.getDeclarationAt(positionOf(source, "solo")))
        assertRangesDisjoint(m1.range!!, solo.range!!)
        assertRangesDisjoint(m2.range!!, solo.range!!)
    }

    @Test
    fun forGenericMultiNames_areQueryablePerNameLikeLocalMultiAssign() {
        // for-generic also introduces multi-name locals; include as related corpus.
        val source = """
            for key, value in pairs({}) do
                print(key, value)
            end
            """.trimIndent()
        val result = bind(source)

        val key = localOf(result, "key")
        val value = localOf(result, "value")

        assertPerNameRange(source, key, "key")
        assertPerNameRange(source, value, "value")
        assertEquals(key, result.positionQueries.getDeclarationAt(positionOf(source, "key", occurrence = 1)))
        assertEquals(value, result.positionQueries.getDeclarationAt(positionOf(source, "value", occurrence = 1)))
        assertRangesDisjoint(key.range!!, value.range!!)
    }

    @Test
    fun declarationIndexListsMultiLocalsInSourceOrder() {
        val source = "local zed, alpha, mid = 1, 2, 3"
        val result = bind(source)
        val locals = nonBuiltinLocals(result)

        assertEquals(listOf("zed", "alpha", "mid"), locals.map { it.name })
    }

    @Test
    fun multiLocalSymbolsHaveDistinctIdsPerName() {
        val source = "local left, right = {}, {}"
        val result = bind(source)

        val left = localOf(result, "left")
        val right = localOf(result, "right")

        assertNotNull(left.symbolId)
        assertNotNull(right.symbolId)
        assertNotEquals(left.symbolId, right.symbolId)
        assertNotEquals(left.id, right.id)
    }

    // --- helpers --------------------------------------------------------------

    private fun bind(source: String): BinderPassResult {
        val chunk = parser.parse(source)
        return BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
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
        // Identifier end is half-open past the last character of the name.
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

    /**
     * True when [inner] is contained in [outer] and strictly narrower (not equal).
     * LocalStatement ranges may begin at `local` or at the first name; either way
     * per-name declaration ranges must remain proper sub-spans.
     */
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

    /**
     * Locate the start Position of [needle] in [source].
     *
     * Identifier needles are matched as whole words so short names such as `c` /
     * `s` / `n` / `b` do not hit substrings inside `local`, `string`, `number`,
     * or `boolean`. Punctuation needles (e.g. `,`) keep plain substring match.
     */
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
            }
            fromIndex = index + 1
        }
    }

    private fun isIdentChar(ch: Char): Boolean {
        return ch == '_' || ch.isLetterOrDigit()
    }
}
