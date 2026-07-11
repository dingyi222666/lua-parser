package semantic.model

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.BinaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.model.NodePositionIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Edge / binary-search corpus for [NodePositionIndex].
 *
 * NodePositionIndex uses half-open ranges: a position hits when
 * `start <= position < end`. This suite pins start/end boundaries, nested
 * specificity, empty sources, and positions outside any recorded node so
 * future interval-tree / binary-search rewrites cannot regress edge hits.
 *
 * Test-only (TASK-236). Workers must not run Gradle verification.
 */
class NodePositionIndexEdgeTddTest {

    private val parser = LuaParser()

    // -------------------------------------------------------------------------
    // Empty / trivial sources
    // -------------------------------------------------------------------------

    @Test
    fun emptySource_doesNotThrow_andReportsNoInnermostNode() {
        val chunk = parser.parse("")
        val index = NodePositionIndex(chunk)

        // Construction and queries must remain throw-free for empty input.
        assertNull(index.findInnermost(Position(1, 1)))
        assertEquals(emptyList(), index.findEnclosing(Position(1, 1)))
        assertNull(index.findInnermost(Position(1, 0)))
        assertNull(index.findInnermost(Position(2, 1)))
    }

    @Test
    fun whitespaceOnlySource_doesNotThrow() {
        val chunk = parser.parse("   \n\t  \n")
        val index = NodePositionIndex(chunk)

        // Whitespace-only files still produce a chunk/block skeleton; querying
        // must never throw even when no statement nodes exist.
        index.findInnermost(Position(1, 1))
        index.findEnclosing(Position(1, 2))
        index.findInnermost(Position(2, 1))
        index.findInnermost(Position(3, 1))
    }

    // -------------------------------------------------------------------------
    // Half-open range edges on a single identifier / constant
    // -------------------------------------------------------------------------

    @Test
    fun identifier_hitsAtStart_andMissesAtEnd() {
        // "local value = 1"
        //  columns (1-based, line 1):
        //  1:l 2:o 3:c 4:a 5:l 6:  7:v 8:a 9:l 10:u 11:e 12:  13:= 14:  15:1
        val source = "local value = 1"
        val chunk = parser.parse(source)
        val index = NodePositionIndex(chunk)

        val local = assertIs<LocalStatement>(chunk.body.statements.single())
        val valueId = local.init.single()
        assertEquals("value", valueId.name)
        val one = assertIs<ConstantNode>(local.variables.single())

        // Start of identifier is inclusive.
        assertSame(valueId, index.findInnermost(valueId.range.start))
        assertSame(valueId, index.findInnermost(Position(1, valueId.range.start.column)))

        // Mid-identifier still maps to the identifier.
        assertSame(valueId, index.findInnermost(Position(1, valueId.range.start.column + 2)))

        // End is exclusive: the position at range.end is NOT inside value.
        val atValueEnd = valueId.range.end
        val atValueEndNode = index.findInnermost(atValueEnd)
        assertTrue(
            atValueEndNode !== valueId,
            "half-open range must exclude end; got ${describe(atValueEndNode)} at $atValueEnd for value range ${valueId.range}"
        )

        // Constant "1" start inclusive, end exclusive.
        assertSame(one, index.findInnermost(one.range.start))
        val atOneEnd = index.findInnermost(one.range.end)
        assertTrue(
            atOneEnd !== one,
            "half-open range must exclude end of constant; got ${describe(atOneEnd)} at ${one.range.end}"
        )
    }

    @Test
    fun localStatement_coversKeywordThroughInitializerEdges() {
        val source = "local value = 1"
        val chunk = parser.parse(source)
        val index = NodePositionIndex(chunk)
        val local = assertIs<LocalStatement>(chunk.body.statements.single())

        // Start of the statement (column of 'l' in local) hits the local statement
        // (or a more specific child if the range starts on a child — usually the statement).
        val atLocalStart = index.findInnermost(local.range.start)
        assertNotNull(atLocalStart)
        assertTrue(
            atLocalStart === local || isDescendantOf(atLocalStart, local),
            "expected local or descendant at statement start; got ${describe(atLocalStart)}"
        )

        // Position strictly before the statement is outside it.
        if (local.range.start.column > 1) {
            val before = Position(local.range.start.line, local.range.start.column - 1)
            val nodeBefore = index.findInnermost(before)
            assertTrue(
                nodeBefore !== local && (nodeBefore == null || !isDescendantOf(nodeBefore, local)),
                "position before statement must not map into the statement"
            )
        }

        // End of the statement is exclusive for that statement's own range.
        val atLocalEnd = index.findInnermost(local.range.end)
        assertTrue(
            atLocalEnd !== local,
            "half-open: statement end must not map to the statement itself; got ${describe(atLocalEnd)}"
        )
    }

    // -------------------------------------------------------------------------
    // Nested specificity (binary-search / interval specificity order)
    // -------------------------------------------------------------------------

    @Test
    fun nestedCall_memberExpression_returnsInnermostAtEachEdge() {
        // print(obj.field)
        val source = "print(obj.field)"
        val chunk = parser.parse(source)
        val index = NodePositionIndex(chunk)

        val callStmt = assertIs<CallStatement>(chunk.body.statements.single())
        val call = assertIs<CallExpression>(callStmt.expression)
        val printId = assertIs<Identifier>(call.base)
        assertEquals("print", printId.name)

        val member = assertIs<MemberExpression>(call.arguments.single())
        val objId = assertIs<Identifier>(member.base)
        val fieldId = member.identifier
        assertEquals("obj", objId.name)
        assertEquals("field", fieldId.name)

        // Callee identifier edges.
        assertSame(printId, index.findInnermost(printId.range.start))
        assertTrue(index.findInnermost(printId.range.end) !== printId)

        // Base of member expression.
        assertSame(objId, index.findInnermost(objId.range.start))
        assertTrue(index.findInnermost(objId.range.end) !== objId)

        // Member identifier (innermost over MemberExpression / CallExpression).
        assertSame(fieldId, index.findInnermost(fieldId.range.start))
        assertSame(fieldId, index.findInnermost(Position(fieldId.range.start.line, fieldId.range.start.column + 1)))
        assertTrue(index.findInnermost(fieldId.range.end) !== fieldId)

        // Just inside the member expression but on the '.' indexer should still
        // resolve to something inside the member expression tree.
        val dotColumn = objId.range.end.column // typically the '.' position under half-open base
        val atDot = index.findInnermost(Position(1, dotColumn))
        assertNotNull(atDot)
        assertTrue(
            atDot === member || isDescendantOf(atDot, member) || atDot === call || isDescendantOf(atDot, call),
            "dot position should remain inside call/member tree; got ${describe(atDot)}"
        )

        // findEnclosing lists most-specific first.
        val enclosingField = index.findEnclosing(fieldId.range.start)
        assertTrue(enclosingField.isNotEmpty())
        assertSame(fieldId, enclosingField.first())
        assertTrue(
            enclosingField.any { it === member },
            "enclosing chain for field must include MemberExpression: ${enclosingField.map(::describe)}"
        )
        assertTrue(
            enclosingField.any { it === call },
            "enclosing chain for field must include CallExpression: ${enclosingField.map(::describe)}"
        )
    }

    @Test
    fun nestedBinaryExpression_prefersInnermostOperandAtEdges() {
        // a + b * c
        val source = "return a + b * c"
        val chunk = parser.parse(source)
        val index = NodePositionIndex(chunk)

        val ids = collectIdentifiers(chunk)
        val a = ids.single { it.name == "a" }
        val b = ids.single { it.name == "b" }
        val c = ids.single { it.name == "c" }

        assertSame(a, index.findInnermost(a.range.start))
        assertSame(b, index.findInnermost(b.range.start))
        assertSame(c, index.findInnermost(c.range.start))

        // End of each operand is exclusive for that operand.
        assertTrue(index.findInnermost(a.range.end) !== a)
        assertTrue(index.findInnermost(b.range.end) !== b)
        assertTrue(index.findInnermost(c.range.end) !== c)

        // A position on the '*' operator should map to the multiplicative BinaryExpression
        // (or its parent add expression), not to identifiers a/b/c.
        val mul = findBinaryWithOperands(chunk, "b", "c")
        assertNotNull(mul, "expected b * c binary")
        val starPos = Position(mul.range.start.line, b.range.end.column)
        val atStar = index.findInnermost(starPos)
        assertNotNull(atStar)
        assertTrue(
            atStar is BinaryExpression || atStar === mul,
            "operator position should map to binary expression, got ${describe(atStar)}"
        )
        assertTrue(atStar !is Identifier)
    }

    // -------------------------------------------------------------------------
    // Multi-line and block edges
    // -------------------------------------------------------------------------

    @Test
    fun doBlock_innerLocal_edges_and_endKeywordBoundary() {
        val source = """
            do
              local inner = 2
            end
        """.trimIndent()
        val chunk = parser.parse(source)
        val index = NodePositionIndex(chunk)

        val doStmt = assertIs<DoStatement>(chunk.body.statements.single())
        val block = doStmt.body
        val local = assertIs<LocalStatement>(block.statements.single())
        val inner = local.init.single()
        assertEquals("inner", inner.name)

        // Inside the local identifier.
        assertSame(inner, index.findInnermost(inner.range.start))
        assertTrue(index.findInnermost(inner.range.end) !== inner)

        // Start of the do-body block should resolve to the block or a descendant.
        val atBlockStart = index.findInnermost(block.range.start)
        assertNotNull(atBlockStart)
        assertTrue(
            atBlockStart === block || atBlockStart === doStmt || isDescendantOf(atBlockStart, block) || isDescendantOf(atBlockStart, doStmt),
            "block start should map into do/block tree; got ${describe(atBlockStart)}"
        )

        // Half-open: exact block end is outside the block itself.
        val atBlockEnd = index.findInnermost(block.range.end)
        assertTrue(
            atBlockEnd !== block,
            "block end exclusive; got ${describe(atBlockEnd)} for block range ${block.range}"
        )

        // Position on the 'e' of end (if within doStmt range) should still be inside doStmt
        // when the parser attributes 'end' to the do statement range.
        val endKeyword = positionOf(source, "end")
        val atEndKw = index.findInnermost(endKeyword)
        // May be doStmt, outer block, or chunk body — but must not throw and should not
        // falsely claim the inner identifier.
        assertTrue(atEndKw !== inner)
        if (containsHalfOpen(doStmt.range, endKeyword)) {
            assertNotNull(atEndKw)
            assertTrue(
                atEndKw === doStmt || isDescendantOf(atEndKw, doStmt) || atEndKw === chunk || atEndKw === chunk.body,
                "end keyword inside do range should stay in do/chunk tree; got ${describe(atEndKw)}"
            )
        }
    }

    @Test
    fun multiLineFunction_parameterAndBodyEdges() {
        val source = """
            local function render(input)
              return input
            end
        """.trimIndent()
        val chunk = parser.parse(source)
        val index = NodePositionIndex(chunk)

        val fn = collectNodes<FunctionDeclaration>(chunk).single()
        val params = fn.params
        assertEquals(1, params.size)
        val inputParam = params.single()
        assertEquals("input", inputParam.name)

        // Parameter name start/end.
        assertSame(inputParam, index.findInnermost(inputParam.range.start))
        assertTrue(index.findInnermost(inputParam.range.end) !== inputParam)

        // Usage of input inside the body is a distinct Identifier node.
        val bodyIds = collectIdentifiers(fn.body!!).filter { it.name == "input" }
        assertTrue(bodyIds.isNotEmpty(), "expected usage of input in body")
        val usage = bodyIds.last()
        assertSame(usage, index.findInnermost(usage.range.start))
        assertTrue(usage !== inputParam)

        // Function range start should hit the function or a more specific child (name).
        val atFnStart = index.findInnermost(fn.range.start)
        assertNotNull(atFnStart)
        assertTrue(
            atFnStart === fn || isDescendantOf(atFnStart, fn),
            "function start must map into the function tree; got ${describe(atFnStart)}"
        )

        // Function end exclusive for the function node itself.
        assertTrue(index.findInnermost(fn.range.end) !== fn)
    }

    // -------------------------------------------------------------------------
    // Positions completely outside recorded content
    // -------------------------------------------------------------------------

    @Test
    fun positionsBeforeAndAfterContent_doNotThrow() {
        val source = "local x = 1"
        val chunk = parser.parse(source)
        val index = NodePositionIndex(chunk)

        // Far before any content.
        assertNull(index.findInnermost(Position(1, 0)))
        // Line before first line (parsers are 1-based; still must not throw).
        assertNull(index.findInnermost(Position(0, 0)))

        // Far after the last character.
        val after = Position(1, source.length + 5)
        index.findInnermost(after) // may be null or outer block — must not throw
        index.findEnclosing(after)

        // Blank line after content.
        val multi = "local x = 1\n\n"
        val multiIndex = NodePositionIndex(parser.parse(multi))
        multiIndex.findInnermost(Position(2, 1))
        multiIndex.findInnermost(Position(3, 1))
        multiIndex.findEnclosing(Position(3, 1))
    }

    @Test
    fun adjacentStatements_boundaryMapsConsistentlyWithoutOverlapConfusion() {
        val source = "local a = 1\nlocal b = 2"
        val chunk = parser.parse(source)
        val index = NodePositionIndex(chunk)

        val statements = chunk.body.statements.filterIsInstance<LocalStatement>()
        assertEquals(2, statements.size)
        val first = statements[0]
        val second = statements[1]
        val a = first.init.single()
        val b = second.init.single()
        assertEquals("a", a.name)
        assertEquals("b", b.name)

        assertSame(a, index.findInnermost(a.range.start))
        assertSame(b, index.findInnermost(b.range.start))

        // Exact end of first statement is exclusive for that statement.
        val atFirstEnd = index.findInnermost(first.range.end)
        assertTrue(atFirstEnd !== first)
        assertTrue(atFirstEnd !== a)

        // Start of second statement hits second (or its child).
        val atSecondStart = index.findInnermost(second.range.start)
        assertNotNull(atSecondStart)
        assertTrue(
            atSecondStart === second || isDescendantOf(atSecondStart, second),
            "second statement start must map into second; got ${describe(atSecondStart)}"
        )
    }

    @Test
    fun findEnclosing_ordersMostSpecificFirst_forDeepNesting() {
        val source = """
            do
              do
                local z = 9
              end
            end
        """.trimIndent()
        val chunk = parser.parse(source)
        val index = NodePositionIndex(chunk)

        val z = collectIdentifiers(chunk).single { it.name == "z" }
        val enclosing = index.findEnclosing(z.range.start)

        assertTrue(enclosing.isNotEmpty())
        assertSame(z, enclosing.first(), "innermost must be first")
        // Each subsequent node should be a proper ancestor (wider or equal start / later end).
        for (i in 0 until enclosing.lastIndex) {
            val inner = enclosing[i]
            val outer = enclosing[i + 1]
            assertTrue(
                containsHalfOpen(outer.range, inner.range.start) ||
                    rangesEqual(outer.range, inner.range),
                "enclosing[$i]=${describe(inner)} should be nested in enclosing[${i + 1}]=${describe(outer)}"
            )
        }
        assertTrue(enclosing.any { it is BlockNode })
        assertTrue(enclosing.any { it is DoStatement })
        assertTrue(enclosing.any { it is ChunkNode } || enclosing.any { it === chunk.body })
    }

    @Test
    fun blockRoot_andChunkRoot_areQueryableAtTheirStartsWhenRangesAreNonEmpty() {
        val source = "local x = 1"
        val chunk = parser.parse(source)
        val index = NodePositionIndex(chunk)

        if (isNonEmpty(chunk.range)) {
            val atChunkStart = index.findInnermost(chunk.range.start)
            assertNotNull(atChunkStart)
        }
        if (isNonEmpty(chunk.body.range)) {
            val atBodyStart = index.findInnermost(chunk.body.range.start)
            assertNotNull(atBodyStart)
        }

        // Building from BlockNode root must also be safe (index lifts to parent Chunk when present).
        val fromBlock = NodePositionIndex(chunk.body)
        fromBlock.findInnermost(Position(1, 1))
        fromBlock.findEnclosing(Position(1, 7))
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun isNonEmpty(range: Range): Boolean {
        val start = range.start
        val end = range.end
        return start.line < end.line || (start.line == end.line && start.column < end.column)
    }

    private fun containsHalfOpen(range: Range, position: Position): Boolean {
        return comparePos(range.start, position) <= 0 && comparePos(position, range.end) < 0
    }

    private fun rangesEqual(a: Range, b: Range): Boolean =
        a.start.line == b.start.line && a.start.column == b.start.column &&
            a.end.line == b.end.line && a.end.column == b.end.column

    private fun comparePos(a: Position, b: Position): Int {
        val line = a.line.compareTo(b.line)
        return if (line != 0) line else a.column.compareTo(b.column)
    }

    private fun isDescendantOf(node: BaseASTNode, ancestor: BaseASTNode): Boolean {
        var current: BaseASTNode? = node
        val seen = HashSet<BaseASTNode>()
        while (current != null && seen.add(current)) {
            if (current === ancestor) return true
            current = runCatching { current!!.parent }.getOrNull()
        }
        return false
    }

    private fun describe(node: BaseASTNode?): String {
        if (node == null) return "null"
        val name = node::class.simpleName ?: "?"
        val range = runCatching { node.range.toString() }.getOrElse { "?" }
        val extra = when (node) {
            is Identifier -> " name=${node.name}"
            is ConstantNode -> " const=${node.constantType}"
            else -> ""
        }
        return "$name$extra@$range"
    }

    private fun collectIdentifiers(root: BaseASTNode): List<Identifier> =
        collectNodes(root)

    private fun <T : BaseASTNode> collectNodes(root: BaseASTNode, predicate: (BaseASTNode) -> Boolean): List<T> {
        val out = mutableListOf<T>()
        fun walk(node: BaseASTNode) {
            if (predicate(node)) {
                @Suppress("UNCHECKED_CAST")
                out += node as T
            }
            when (node) {
                is ChunkNode -> walk(node.body)
                is BlockNode -> {
                    node.statements.forEach(::walk)
                    node.returnStatement?.let(::walk)
                }
                is LocalStatement -> {
                    node.init.forEach(::walk)
                    node.variables.forEach(::walk)
                }
                is CallStatement -> walk(node.expression)
                is CallExpression -> {
                    walk(node.base)
                    node.arguments.forEach(::walk)
                }
                is MemberExpression -> {
                    walk(node.base)
                    walk(node.identifier)
                }
                is BinaryExpression -> {
                    node.left?.let(::walk)
                    node.right?.let(::walk)
                }
                is FunctionDeclaration -> {
                    node.identifier?.let(::walk)
                    node.params.forEach(::walk)
                    node.body?.let(::walk)
                }
                is DoStatement -> walk(node.body)
                is io.github.dingyi222666.luaparser.parser.ast.node.ReturnStatement ->
                    node.arguments.forEach(::walk)
                is io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement -> {
                    node.init.forEach(::walk)
                    node.variables.forEach(::walk)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.IndexExpression -> {
                    walk(node.base)
                    walk(node.index)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.UnaryExpression -> walk(node.arg)
                is io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement -> {
                    walk(node.condition)
                    walk(node.body)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.IfStatement ->
                    node.causes.forEach(::walk)
                is io.github.dingyi222666.luaparser.parser.ast.node.IfClause -> {
                    walk(node.condition)
                    walk(node.body)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.ElseIfClause -> {
                    walk(node.condition)
                    walk(node.body)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.ElseClause -> walk(node.body)
            }
        }
        walk(root)
        return out
    }

    private inline fun <reified T : BaseASTNode> collectNodes(root: BaseASTNode): List<T> =
        collectNodes(root) { it is T }

    private fun findBinaryWithOperands(root: BaseASTNode, leftName: String, rightName: String): BinaryExpression? {
        return collectNodes<BinaryExpression>(root).firstOrNull { binary ->
            val left = binary.left
            val right = binary.right
            left is Identifier && left.name == leftName &&
                right is Identifier && right.name == rightName
        }
    }

    private fun positionOf(source: String, needle: String, occurrence: Int = 1): Position {
        var index = -1
        repeat(occurrence) {
            index = source.indexOf(needle, index + 1)
            check(index >= 0) { "Missing occurrence $occurrence of '$needle' in:\n$source" }
        }
        var line = 1
        var column = 1
        for (i in 0 until index) {
            if (source[i] == '\n') {
                line += 1
                column = 1
            } else {
                column += 1
            }
        }
        return Position(line, column)
    }
}
