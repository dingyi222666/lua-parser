package semantic.binder

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationIndex
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.binder.PositionRangeIndex
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DeclarationIndex range-lookup corpus (TASK-235).
 *
 * Acceptance:
 * - Range lookups return declarations intersecting positions for locals/params.
 * - Out-of-range positions return empty.
 *
 * DeclarationIndex itself stores ranges on [BinderDeclaration]; positional
 * intersection is performed via [PositionRangeIndex] (same substrate as
 * [io.github.dingyi222666.luaparser.semantic.binder.BinderPositionQueries]).
 * This corpus exercises both the raw index-built range query and the binder
 * positionQueries surface over declarationIndex entries.
 *
 * Fixture note: avoid multi-identifier returns such as `return left, right` —
 * the current parser rejects those with IllegalStateException near eof. Use
 * single returns / body locals instead (same constraint as sibling binder
 * corpora).
 *
 * Test-only; production defects surface as assertion failures (review-owned
 * verification via `jvmTest --tests semantic.binder.DeclarationIndexRangeLookupTddTest`).
 */
class DeclarationIndexRangeLookupTddTest {

    private val parser = LuaParser()

    // --- locals: intersecting positions ---------------------------------------

    @Test
    fun localDeclaration_rangeLookupHitsAtStartAndInterior() {
        val source = "local value = 1"
        val result = bind(source)
        val local = nonBuiltinOf(result, name = "value", kind = DeclarationKind.LOCAL)
        val range = assertNotNull(local.range)
        val index = rangeIndexOf(result.declarationIndex)

        // Half-open [start, end): start and interior must hit.
        assertEquals(listOf(local), index.query(range.start))
        assertEquals(listOf(local), index.query(midpoint(range)))
        assertEquals(local, result.positionQueries.getDeclarationAt(range.start))
        assertEquals(listOf(local), result.positionQueries.getDeclarationsAt(midpoint(range)))
    }

    @Test
    fun localDeclaration_rangeLookupMatchesIdentifierTokenOnly() {
        val source = "local alpha = 42"
        val result = bind(source)
        val local = nonBuiltinOf(result, name = "alpha", kind = DeclarationKind.LOCAL)
        val range = assertNotNull(local.range)
        val index = rangeIndexOf(result.declarationIndex)

        val expectedStart = positionOf(source, "alpha")
        assertEquals(expectedStart, range.start)
        assertEquals(expectedStart.line, range.end.line)
        assertEquals(expectedStart.column + "alpha".length, range.end.column)

        // Every column of the identifier name intersects the declaration range.
        for (offset in 0 until "alpha".length) {
            val pos = Position(expectedStart.line, expectedStart.column + offset)
            assertEquals(listOf(local), index.query(pos), "expected hit at column offset $offset")
            assertEquals(local, result.positionQueries.getDeclarationAt(pos))
        }
    }

    @Test
    fun multiLocal_rangeLookupReturnsOnlyIntersectingName() {
        val source = "local first, second = 1, 2"
        val result = bind(source)
        val first = nonBuiltinOf(result, name = "first", kind = DeclarationKind.LOCAL)
        val second = nonBuiltinOf(result, name = "second", kind = DeclarationKind.LOCAL)
        val index = rangeIndexOf(result.declarationIndex)

        assertEquals(listOf(first), index.query(positionOf(source, "first")))
        assertEquals(listOf(second), index.query(positionOf(source, "second")))
        assertEquals(first, result.positionQueries.getDeclarationAt(positionOf(source, "first")))
        assertEquals(second, result.positionQueries.getDeclarationAt(positionOf(source, "second")))
        // Comma between names intersects neither declaration range.
        assertEquals(emptyList(), index.query(positionOf(source, ",")))
        assertTrue(result.positionQueries.getDeclarationsAt(positionOf(source, ",")).isEmpty())
    }

    // --- parameters: intersecting positions -----------------------------------

    @Test
    fun parameterDeclaration_rangeLookupHitsAtStartAndInterior() {
        val source = """
            local function render(input)
                return input
            end
            """.trimIndent()
        val result = bind(source)
        val param = nonBuiltinOf(result, name = "input", kind = DeclarationKind.PARAMETER)
        val range = assertNotNull(param.range)
        val index = rangeIndexOf(result.declarationIndex)

        assertEquals(listOf(param), index.query(range.start))
        assertEquals(listOf(param), index.query(midpoint(range)))
        assertEquals(param, result.positionQueries.getDeclarationAt(range.start))
        assertEquals(listOf(param), result.positionQueries.getDeclarationsAt(midpoint(range)))
    }

    @Test
    fun multiParameter_rangeLookupReturnsOnlyIntersectingParam() {
        // Body uses both params without multi-identifier return (`return left, right`
        // currently fails parse with IllegalStateException near eof).
        val source = """
            local function pack(left, right)
                local combined = left
                local other = right
                return combined
            end
            """.trimIndent()
        val result = bind(source)
        val left = nonBuiltinOf(result, name = "left", kind = DeclarationKind.PARAMETER)
        val right = nonBuiltinOf(result, name = "right", kind = DeclarationKind.PARAMETER)
        val index = rangeIndexOf(result.declarationIndex)

        assertEquals(listOf(left), index.query(positionOf(source, "left", occurrence = 1)))
        assertEquals(listOf(right), index.query(positionOf(source, "right", occurrence = 1)))
        assertEquals(left, result.positionQueries.getDeclarationAt(positionOf(source, "left", occurrence = 1)))
        assertEquals(right, result.positionQueries.getDeclarationAt(positionOf(source, "right", occurrence = 1)))
        // Comma between params is outside both parameter ranges.
        assertEquals(emptyList(), index.query(positionOf(source, ",")))
        // Usage sites are not declaration-range hits.
        assertEquals(emptyList(), index.query(positionOf(source, "left", occurrence = 2)))
        assertEquals(emptyList(), index.query(positionOf(source, "right", occurrence = 2)))
    }

    @Test
    fun localAndParameter_rangeLookupDoesNotCrossKinds() {
        val source = """
            local outer = 1
            local function work(arg)
                local inner = arg
                return outer + inner
            end
            """.trimIndent()
        val result = bind(source)
        val outer = nonBuiltinOf(result, name = "outer", kind = DeclarationKind.LOCAL)
        val arg = nonBuiltinOf(result, name = "arg", kind = DeclarationKind.PARAMETER)
        val inner = nonBuiltinOf(result, name = "inner", kind = DeclarationKind.LOCAL)
        val index = rangeIndexOf(result.declarationIndex)

        assertEquals(listOf(outer), index.query(positionOf(source, "outer", occurrence = 1)))
        assertEquals(listOf(arg), index.query(positionOf(source, "arg", occurrence = 1)))
        assertEquals(listOf(inner), index.query(positionOf(source, "inner", occurrence = 1)))

        // Usage sites are not declaration-range hits.
        assertEquals(emptyList(), index.query(positionOf(source, "arg", occurrence = 2)))
        assertEquals(emptyList(), index.query(positionOf(source, "outer", occurrence = 2)))
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "arg", occurrence = 2)))
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "outer", occurrence = 2)))
    }

    // --- out-of-range positions return empty ----------------------------------

    @Test
    fun localDeclaration_outOfRangePositionsReturnEmpty() {
        val source = "local value = 1"
        val result = bind(source)
        val local = nonBuiltinOf(result, name = "value", kind = DeclarationKind.LOCAL)
        val range = assertNotNull(local.range)
        val index = rangeIndexOf(result.declarationIndex)

        // Half-open end boundary: position at range.end is NOT contained.
        assertEquals(emptyList(), index.query(range.end))
        assertNull(result.positionQueries.getDeclarationAt(range.end))
        assertTrue(result.positionQueries.getDeclarationsAt(range.end).isEmpty())

        // Before the identifier.
        val before = Position(range.start.line, range.start.column - 1)
        assertEquals(emptyList(), index.query(before))
        assertNull(result.positionQueries.getDeclarationAt(before))

        // After the identifier end.
        val after = Position(range.end.line, range.end.column + 1)
        assertEquals(emptyList(), index.query(after))
        assertNull(result.positionQueries.getDeclarationAt(after))

        // Far away line.
        assertEquals(emptyList(), index.query(Position(99, 1)))
        assertNull(result.positionQueries.getDeclarationAt(Position(99, 1)))
    }

    @Test
    fun parameterDeclaration_outOfRangePositionsReturnEmpty() {
        val source = """
            local function render(input)
                print(input)
            end
            """.trimIndent()
        val result = bind(source)
        val param = nonBuiltinOf(result, name = "input", kind = DeclarationKind.PARAMETER)
        val range = assertNotNull(param.range)
        val index = rangeIndexOf(result.declarationIndex)

        assertEquals(emptyList(), index.query(range.end))
        assertTrue(result.positionQueries.getDeclarationsAt(range.end).isEmpty())

        // Keyword / body positions that are not the param identifier.
        assertEquals(emptyList(), index.query(positionOf(source, "function")))
        assertEquals(emptyList(), index.query(positionOf(source, "print")))
        // Second "input" is a usage site, not the declaration range.
        assertEquals(emptyList(), index.query(positionOf(source, "input", occurrence = 2)))
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "input", occurrence = 2)))
    }

    @Test
    fun emptySourceBody_rangeLookupAtArbitraryPositionIsEmpty() {
        val source = "return 1"
        val result = bind(source)
        val index = rangeIndexOf(result.declarationIndex)

        // No user locals/params; only builtins (null ranges) — no range hits.
        assertTrue(
            result.declarationIndex.declarations
                .filter { it.origin != DeclarationOrigin.BUILTIN }
                .none { it.kind == DeclarationKind.LOCAL || it.kind == DeclarationKind.PARAMETER }
        )
        assertEquals(emptyList(), index.query(Position(1, 1)))
        assertEquals(emptyList(), index.query(Position(1, 8)))
        assertNull(result.positionQueries.getDeclarationAt(Position(1, 1)))
    }

    @Test
    fun builtinsWithNullRange_neverAppearInRangeLookupHits() {
        val source = "local x = 1"
        val result = bind(source)
        val index = rangeIndexOf(result.declarationIndex)
        val local = nonBuiltinOf(result, name = "x", kind = DeclarationKind.LOCAL)

        val hits = index.query(assertNotNull(local.range).start)
        assertEquals(listOf(local), hits)
        assertTrue(hits.none { it.origin == DeclarationOrigin.BUILTIN })
        assertTrue(
            result.declarationIndex.declarations.any { it.origin == DeclarationOrigin.BUILTIN },
            "fixture expects builtins to be present in the declaration index"
        )
    }

    // --- nested / mixed corpus ------------------------------------------------

    @Test
    fun nestedBlockLocals_rangeLookupIsolatesEachDeclaration() {
        val source = """
            local outer = 1
            do
                local inner = 2
            end
            """.trimIndent()
        val result = bind(source)
        val outer = nonBuiltinOf(result, name = "outer", kind = DeclarationKind.LOCAL)
        val inner = nonBuiltinOf(result, name = "inner", kind = DeclarationKind.LOCAL)
        val index = rangeIndexOf(result.declarationIndex)

        assertEquals(listOf(outer), index.query(positionOf(source, "outer")))
        assertEquals(listOf(inner), index.query(positionOf(source, "inner")))
        assertEquals(emptyList(), index.query(positionOf(source, "do")))
        assertEquals(emptyList(), index.query(positionOf(source, "end")))
    }

    @Test
    fun forLoopLocal_rangeLookupHitsIteratorNameOnly() {
        val source = """
            for i = 1, 3 do
                print(i)
            end
            """.trimIndent()
        val result = bind(source)
        val loopVar = nonBuiltinOf(result, name = "i", kind = DeclarationKind.LOCAL)
        val index = rangeIndexOf(result.declarationIndex)

        assertEquals(listOf(loopVar), index.query(positionOf(source, "i", occurrence = 1)))
        assertEquals(emptyList(), index.query(positionOf(source, "i", occurrence = 2)))
        assertEquals(emptyList(), index.query(positionOf(source, "for")))
        assertEquals(emptyList(), index.query(positionOf(source, "print")))
    }

    @Test
    fun declarationIndexRangeIndex_respectsHalfOpenContainmentAcrossManyLocals() {
        val source = """
            local a = 1
            local bb = 2
            local ccc = 3
            """.trimIndent()
        val result = bind(source)
        val index = rangeIndexOf(result.declarationIndex)
        val names = listOf("a", "bb", "ccc")

        names.forEach { name ->
            val declaration = nonBuiltinOf(result, name = name, kind = DeclarationKind.LOCAL)
            val range = assertNotNull(declaration.range)
            val start = positionOf(source, name)

            assertEquals(start, range.start)
            assertEquals(listOf(declaration), index.query(start))
            assertEquals(emptyList(), index.query(range.end))
            // Interior of longer names.
            if (name.length > 1) {
                assertEquals(listOf(declaration), index.query(midpoint(range)))
            }
        }

        // Between statements (blank-ish: column after newline start of next local keyword).
        assertEquals(emptyList(), index.query(positionOf(source, "local", occurrence = 2).let {
            // one column before second `local` keyword start is still out of any decl range
            Position(it.line, 1).also { pos ->
                // ensure we are at the line of second local but before any identifier
                assertTrue(pos.column < positionOf(source, "bb").column)
            }
        }))
    }

    // --- helpers --------------------------------------------------------------

    private fun bind(source: String): BinderPassResult {
        val chunk = parser.parse(source)
        return BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
    }

    /**
     * Build a [PositionRangeIndex] over the declarations stored in [DeclarationIndex],
     * mirroring how binder position queries index declaration ranges.
     */
    private fun rangeIndexOf(declarationIndex: DeclarationIndex): PositionRangeIndex<BinderDeclaration> {
        return PositionRangeIndex(
            declarationIndex.declarations.mapIndexed { index, declaration ->
                PositionRangeIndex.Entry(
                    range = declaration.range,
                    payload = declaration,
                    stableOrder = index
                )
            }
        )
    }

    private fun nonBuiltinOf(
        result: BinderPassResult,
        name: String,
        kind: DeclarationKind
    ): BinderDeclaration {
        return result.declarationIndex.declarations.single {
            it.name == name && it.kind == kind && it.origin != DeclarationOrigin.BUILTIN
        }
    }

    private fun midpoint(range: Range): Position {
        require(range.start.line == range.end.line) {
            "midpoint helper expects single-line ranges, got $range"
        }
        val column = range.start.column + ((range.end.column - range.start.column) / 2)
        require(column >= range.start.column && column < range.end.column) {
            "midpoint $column not strictly inside $range"
        }
        return Position(range.start.line, column)
    }

    /**
     * Locate the start [Position] of [needle] in [source].
     *
     * Identifier needles are matched as whole words so short names do not hit
     * substrings inside keywords (`local`, `function`, `print`, …).
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
