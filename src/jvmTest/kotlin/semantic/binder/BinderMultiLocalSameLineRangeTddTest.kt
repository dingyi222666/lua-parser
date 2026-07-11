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
 * Binder multi-local same-line declaration-range corpus (TASK-285).
 *
 * Acceptance:
 * - Multiple locals declared on one line get non-overlapping (pairwise disjoint)
 *   identifier ranges — never a shared / statement-wide range.
 * - Position queries (`getDeclarationAt` / `getDeclarationsAt` / `getSymbolAt`)
 *   hit the correct local at every column of each name and miss between names.
 *
 * Complements [BinderMultiAssignRangeTddTest] (TASK-195) by focusing on dense
 * same-line packing, interior-column hits, half-open end boundaries, and
 * multi-statement same-line corpora rather than unbalanced RHS / assign shapes.
 *
 * Test-only; production defects surface as assertion failures (review-owned
 * verification via `jvmTest --tests semantic.binder.BinderMultiLocalSameLineRangeTddTest`).
 */
class BinderMultiLocalSameLineRangeTddTest {

    private val parser = LuaParser()

    // --- non-overlapping same-line ranges -------------------------------------

    @Test
    fun twoLocalsSameLine_rangesArePairwiseDisjointAndMatchIdentifierSpans() {
        val source = "local left, right = 1, 2"
        val result = bind(source)

        val left = localOf(result, "left")
        val right = localOf(result, "right")

        assertPerNameRange(source, left, "left")
        assertPerNameRange(source, right, "right")
        assertRangesDisjoint(left.range!!, right.range!!)
        assertSameLine(left.range!!, right.range!!)
    }

    @Test
    fun threeLocalsSameLine_allPairsDisjoint() {
        val source = "local a, b, c = 10, 20, 30"
        val result = bind(source)
        val declarations = listOf("a", "b", "c").map { localOf(result, it) }

        for (i in declarations.indices) {
            for (j in i + 1 until declarations.size) {
                assertRangesDisjoint(declarations[i].range!!, declarations[j].range!!)
                assertSameLine(declarations[i].range!!, declarations[j].range!!)
            }
        }
    }

    @Test
    fun fourLocalsSameLine_densePackingStaysNonOverlapping() {
        // Longer names + minimal spaces stress half-open end boundaries.
        val source = "local first,second,third,fourth=1,2,3,4"
        val result = bind(source)
        val names = listOf("first", "second", "third", "fourth")
        val declarations = names.map { localOf(result, it) }

        names.zip(declarations).forEach { (name, declaration) ->
            assertPerNameRange(source, declaration, name)
        }
        for (i in declarations.indices) {
            for (j in i + 1 until declarations.size) {
                assertRangesDisjoint(declarations[i].range!!, declarations[j].range!!)
            }
        }
        // Source order preserved on same line.
        assertEquals(names, nonBuiltinLocals(result).map { it.name })
    }

    @Test
    fun sameLineMultiLocal_rangesMatchAstIdentifierAnchorsNotWholeStatement() {
        val source = "local alpha, beta = true, false"
        val chunk = parser.parse(source)
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val statement = chunk.body.statements.filterIsInstance<LocalStatement>().single()

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
    fun bareMultiLocalSameLine_noRhsStillNonOverlapping() {
        val source = "local bareA, bareB, bareC"
        val result = bind(source)
        val declarations = listOf("bareA", "bareB", "bareC").map { localOf(result, it) }

        declarations.forEach { declaration ->
            assertPerNameRange(source, declaration, declaration.name)
        }
        for (i in declarations.indices) {
            for (j in i + 1 until declarations.size) {
                assertRangesDisjoint(declarations[i].range!!, declarations[j].range!!)
                assertSameLine(declarations[i].range!!, declarations[j].range!!)
            }
        }
    }

    // --- position queries hit the correct local -------------------------------

    @Test
    fun positionAtEachNameStart_resolvesThatLocalOnly() {
        val source = "local first, second, third = 1, 2, 3"
        val result = bind(source)

        listOf("first", "second", "third").forEach { name ->
            val declaration = localOf(result, name)
            val pos = positionOf(source, name)
            assertEquals(declaration, result.positionQueries.getDeclarationAt(pos))
            assertEquals(listOf(declaration), result.positionQueries.getDeclarationsAt(pos))
            assertEquals(declaration.symbolId, result.positionQueries.getSymbolAt(pos)?.id)
        }
    }

    @Test
    fun everyColumnOfEachName_hitsOnlyThatLocal() {
        val source = "local left, right = 1, 2"
        val result = bind(source)
        val left = localOf(result, "left")
        val right = localOf(result, "right")

        assertEveryColumnHits(source, result, left, "left")
        assertEveryColumnHits(source, result, right, "right")
    }

    @Test
    fun halfOpenEndBoundary_endColumnDoesNotHitDeclaration() {
        // Ranges are half-open [start, end): column at end must miss.
        val source = "local name, other = 1, 2"
        val result = bind(source)
        val nameDecl = localOf(result, "name")
        val range = assertNotNull(nameDecl.range)

        assertEquals(nameDecl, result.positionQueries.getDeclarationAt(range.start))
        assertNull(result.positionQueries.getDeclarationAt(range.end))
        assertTrue(result.positionQueries.getDeclarationsAt(range.end).isEmpty())
    }

    @Test
    fun positionBetweenSameLineNames_resolvesNeither() {
        // Comma and surrounding whitespace sit between two identifier ranges.
        val source = "local x, y = 1, 2"
        val result = bind(source)
        val commaPosition = positionOf(source, ",")

        assertNull(result.positionQueries.getDeclarationAt(commaPosition))
        assertTrue(result.positionQueries.getDeclarationsAt(commaPosition).isEmpty())
        assertNull(result.positionQueries.getSymbolAt(commaPosition))

        // Also probe the space after the comma (start of " y").
        val spaceAfterComma = Position(commaPosition.line, commaPosition.column + 1)
        assertNull(result.positionQueries.getDeclarationAt(spaceAfterComma))
    }

    @Test
    fun adjacentSameLineNamesWithoutSpaces_stillHitCorrectLocalPerColumn() {
        // No space after commas: "a,b,c" — end of one name abuts the comma, not the next name.
        val source = "local aa,bb,cc=1,2,3"
        val result = bind(source)

        listOf("aa", "bb", "cc").forEach { name ->
            val declaration = localOf(result, name)
            assertEveryColumnHits(source, result, declaration, name)
        }

        // Positions of commas never resolve a declaration.
        var from = 0
        repeat(2) {
            val idx = source.indexOf(',', from)
            require(idx >= 0)
            val pos = indexToPosition(source, idx)
            assertNull(result.positionQueries.getDeclarationAt(pos), "comma at $pos must not hit a local")
            from = idx + 1
        }
    }

    @Test
    fun sameLineMultiLocal_distinctSymbolIdsPerName() {
        val source = "local left, right = {}, {}"
        val result = bind(source)
        val left = localOf(result, "left")
        val right = localOf(result, "right")

        assertNotNull(left.symbolId)
        assertNotNull(right.symbolId)
        assertNotEquals(left.symbolId, right.symbolId)
        assertNotEquals(left.id, right.id)

        assertEquals(left.symbolId, result.positionQueries.getSymbolAt(positionOf(source, "left"))?.id)
        assertEquals(right.symbolId, result.positionQueries.getSymbolAt(positionOf(source, "right"))?.id)
    }

    // --- multi-statement / nested same-line corpora ---------------------------

    @Test
    fun twoMultiLocalStatementsOnSeparateLines_queriesDoNotCrossBleed() {
        val source = """
            local m1, m2 = 1, 2
            local n1, n2 = 3, 4
            """.trimIndent()
        val result = bind(source)

        listOf("m1", "m2", "n1", "n2").forEach { name ->
            val declaration = localOf(result, name)
            assertEquals(declaration, result.positionQueries.getDeclarationAt(positionOf(source, name)))
            assertEveryColumnHits(source, result, declaration, name)
        }

        assertRangesDisjoint(localOf(result, "m1").range!!, localOf(result, "m2").range!!)
        assertRangesDisjoint(localOf(result, "n1").range!!, localOf(result, "n2").range!!)
        // Cross-statement ranges also remain disjoint.
        assertRangesDisjoint(localOf(result, "m2").range!!, localOf(result, "n1").range!!)
    }

    @Test
    fun nestedBlockSameLineMultiLocals_eachLineIndependent() {
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
        assertSameLine(localOf(result, "outerA").range!!, localOf(result, "outerB").range!!)
        assertSameLine(localOf(result, "innerA").range!!, localOf(result, "innerB").range!!)
    }

    @Test
    fun sameLineMultiLocalThenUsage_usageSitesDoNotReportDeclaration() {
        val source = """
            local left, right = 1, 2
            print(left, right)
            """.trimIndent()
        val result = bind(source)

        val leftDecl = localOf(result, "left")
        val rightDecl = localOf(result, "right")

        assertEquals(leftDecl, result.positionQueries.getDeclarationAt(positionOf(source, "left", occurrence = 1)))
        assertEquals(rightDecl, result.positionQueries.getDeclarationAt(positionOf(source, "right", occurrence = 1)))

        // Usage sites are not declaration anchors.
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "left", occurrence = 2)))
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "right", occurrence = 2)))
    }

    @Test
    fun shortSingleCharNamesSameLine_wordBoundaryPositionsHitCorrectly() {
        // Single-char names are easy to mis-locate with naive substring search;
        // positionOf uses word boundaries, and binder ranges must still be exact.
        val source = "local a, b, c = 1, 2, 3"
        val result = bind(source)

        listOf("a", "b", "c").forEach { name ->
            val declaration = localOf(result, name)
            assertPerNameRange(source, declaration, name)
            assertEveryColumnHits(source, result, declaration, name)
        }
        assertRangesDisjoint(localOf(result, "a").range!!, localOf(result, "b").range!!)
        assertRangesDisjoint(localOf(result, "b").range!!, localOf(result, "c").range!!)
        assertRangesDisjoint(localOf(result, "a").range!!, localOf(result, "c").range!!)
    }

    @Test
    fun declarationIndexListsSameLineMultiLocalsInSourceOrder() {
        val source = "local zed, alpha, mid = 1, 2, 3"
        val result = bind(source)

        assertEquals(listOf("zed", "alpha", "mid"), nonBuiltinLocals(result).map { it.name })
    }

    @Test
    fun forGenericSameLineNames_areQueryablePerNameLikeLocalMulti() {
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
        assertSameLine(key.range!!, value.range!!)
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
        assertEquals(expectedStart.line, range.end.line)
        assertEquals(expectedStart.column + name.length, range.end.column)
        assertTrue(range.end.column > range.start.column)
    }

    private fun assertEveryColumnHits(
        source: String,
        result: BinderPassResult,
        declaration: BinderDeclaration,
        name: String
    ) {
        val start = positionOf(source, name)
        for (offset in 0 until name.length) {
            val pos = Position(start.line, start.column + offset)
            assertEquals(
                declaration,
                result.positionQueries.getDeclarationAt(pos),
                "expected '$name' at column offset $offset ($pos)"
            )
            assertEquals(listOf(declaration), result.positionQueries.getDeclarationsAt(pos))
            assertEquals(declaration.symbolId, result.positionQueries.getSymbolAt(pos)?.id)
        }
    }

    private fun assertRangesDisjoint(a: Range, b: Range) {
        val aEndsBeforeB = comparePositions(a.end, b.start) <= 0
        val bEndsBeforeA = comparePositions(b.end, a.start) <= 0
        assertTrue(
            aEndsBeforeB || bEndsBeforeA,
            "Expected disjoint ranges but got $a and $b"
        )
    }

    private fun assertSameLine(a: Range, b: Range) {
        assertEquals(a.start.line, b.start.line, "Expected same-line declarations")
        assertEquals(a.end.line, b.end.line)
    }

    /**
     * True when [inner] is contained in [outer] and strictly narrower (not equal).
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
     * Identifier needles are matched as whole words so short names such as `a` /
     * `b` / `c` do not hit substrings inside `local`. Punctuation needles keep
     * plain substring match.
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
