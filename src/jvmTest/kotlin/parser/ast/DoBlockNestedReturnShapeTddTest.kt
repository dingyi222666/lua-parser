package parser.ast

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ReturnStatement
import io.github.dingyi222666.luaparser.parser.ast.node.VarargLiteral
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import parser.firstStatement
import parser.parse
import parser.renderShape

/**
 * Nested do/end + return-shape AST corpus (TASK-317).
 *
 * Locks stable shapes for:
 * - empty `do ... end` blocks (including nested empty shells)
 * - nested do/end blocks carrying single- and multi-value returns
 * - return attached to the owning [io.github.dingyi222666.luaparser.parser.ast.node.BlockNode.returnStatement]
 *   (not the statement list)
 * - do bodies with statements preceding a terminal return
 * - function-body do nests that keep return shapes reachable
 *
 * Shapes use the shared [parser.renderShape] vocabulary:
 * - `Do(Block[...])` for [DoStatement]
 * - `Return(...)` for [ReturnStatement] (including empty `Return()`)
 *
 * Test-only; production parser changes are out of scope unless review re-scopes.
 */
class DoBlockNestedReturnShapeTddTest {

    // --- empty do blocks ----------------------------------------------------------

    @Test
    fun emptyDoBlockIsValidAndHasEmptyBody() {
        val doStatement = parse(LuaVersion.LUA_5_3, "do end")
            .firstStatement<DoStatement>()

        assertTrue(doStatement.body.statements.isEmpty())
        assertNull(doStatement.body.returnStatement)
        assertEquals("Do(Block[])", renderShape(doStatement))
        assertEquals("Chunk(Block[Do(Block[])])", renderShape(parse(LuaVersion.LUA_5_3, "do end")))
    }

    @Test
    fun nestedEmptyDoBlocksRemainValid() {
        assertCaseShapes(
            "two-level empty nest" to (
                "do do end end" to
                    "Chunk(Block[Do(Block[Do(Block[])])])"
            ),
            "three-level empty nest" to (
                "do do do end end end" to
                    "Chunk(Block[Do(Block[Do(Block[Do(Block[])])])])"
            ),
            "sibling empty dos" to (
                "do end do end" to
                    "Chunk(Block[Do(Block[]);Do(Block[])])"
            ),
            "empty do then empty do nested under outer" to (
                "do do end do end end" to
                    "Chunk(Block[Do(Block[Do(Block[]);Do(Block[])])])"
            )
        )
    }

    @Test
    fun emptyDoBlocksExposeTypedNodesAndParents() {
        val outer = parse(LuaVersion.LUA_5_3, "do do end end")
            .firstStatement<DoStatement>()
        val outerBody = outer.body
        val inner = assertIs<DoStatement>(outerBody.statements.single())

        assertSame(outer, outerBody.parent)
        assertSame(outerBody, inner.parent)
        assertSame(inner, inner.body.parent)
        assertTrue(inner.body.statements.isEmpty())
        assertNull(inner.body.returnStatement)
        assertNull(outerBody.returnStatement)
        assertEquals("Do(Block[Do(Block[])])", renderShape(outer))
    }

    // --- do + return shapes -------------------------------------------------------

    @Test
    fun doWithBareReturnKeepsEmptyReturnShape() {
        val doStatement = parse(LuaVersion.LUA_5_3, "do return end")
            .firstStatement<DoStatement>()

        val ret = assertNotNull(doStatement.body.returnStatement)
        assertTrue(ret.arguments.isEmpty())
        assertTrue(doStatement.body.statements.isEmpty(), "return lives on returnStatement, not statements")
        assertEquals("Return()", renderShape(ret))
        assertEquals("Do(Block[Return()])", renderShape(doStatement))
        assertEquals(
            "Chunk(Block[Do(Block[Return()])])",
            renderShape(parse(LuaVersion.LUA_5_3, "do return end"))
        )
    }

    @Test
    fun doWithSingleAndMultiValueReturnShapesAreStable() {
        assertCaseShapes(
            "single constant return" to (
                "do return 1 end" to
                    "Chunk(Block[Do(Block[Return(Const(1))])])"
            ),
            "single identifier return" to (
                "do return value end" to
                    "Chunk(Block[Do(Block[Return(Id(value))])])"
            ),
            "two-value return" to (
                "do return a, b end" to
                    "Chunk(Block[Do(Block[Return(Id(a),Id(b))])])"
            ),
            "three-value mixed return" to (
                "do return 1, \"ok\", true end" to
                    "Chunk(Block[Do(Block[Return(Const(1),Const(\"ok\"),Const(true))])])"
            ),
            "call multi-return" to (
                "do return pair() end" to
                    "Chunk(Block[Do(Block[Return(Call(Id(pair):))])])"
            ),
            "vararg return" to (
                "do return ... end" to
                    "Chunk(Block[Do(Block[Return(Vararg)])])"
            )
        )
    }

    @Test
    fun doBodyStatementsPrecedingReturnKeepBothSides() {
        assertCaseShapes(
            "local then return" to (
                "do local x = 1 return x end" to
                    "Chunk(Block[Do(Block[Local(Id(x)=Const(1));Return(Id(x))])])"
            ),
            "assign then multi return" to (
                "do a = 1 return a, 2 end" to
                    "Chunk(Block[Do(Block[Assign(Id(a)=Const(1));Return(Id(a),Const(2))])])"
            ),
            "call then bare return" to (
                "do work() return end" to
                    "Chunk(Block[Do(Block[CallStmt(Call(Id(work):));Return()])])"
            )
        )

        val doStatement = parse(LuaVersion.LUA_5_3, "do local x = 1 return x, 2 end")
            .firstStatement<DoStatement>()
        val local = assertIs<LocalStatement>(doStatement.body.statements.single())
        val ret = assertNotNull(doStatement.body.returnStatement)
        assertEquals("x", local.init.single().name)
        assertContentEquals(
            listOf("Id(x)", "Const(2)"),
            ret.arguments.map(::renderShape)
        )
        assertSame(doStatement.body, local.parent)
        assertSame(doStatement.body, ret.parent)
    }

    // --- nested do + return -------------------------------------------------------

    @Test
    fun nestedDoWithInnerReturnShapesAreStable() {
        assertCaseShapes(
            "inner single return" to (
                "do do return a end end" to
                    "Chunk(Block[Do(Block[Do(Block[Return(Id(a))])])])"
            ),
            "inner multi return" to (
                "do do return a, b end end" to
                    "Chunk(Block[Do(Block[Do(Block[Return(Id(a),Id(b))])])])"
            ),
            "three-level inner return" to (
                "do do do return 1, 2, 3 end end end" to
                    "Chunk(Block[Do(Block[Do(Block[Do(Block[Return(Const(1),Const(2),Const(3))])])])])"
            ),
            "inner empty return" to (
                "do do return end end" to
                    "Chunk(Block[Do(Block[Do(Block[Return()])])])"
            )
        )
    }

    @Test
    fun nestedDoWithOuterReturnAfterInnerEmptyIsStable() {
        assertCaseShapes(
            "outer return after empty inner" to (
                "do do end return x end" to
                    "Chunk(Block[Do(Block[Do(Block[]);Return(Id(x))])])"
            ),
            "outer multi return after nested empty" to (
                "do do do end end return a, b end" to
                    "Chunk(Block[Do(Block[Do(Block[Do(Block[])]);Return(Id(a),Id(b))])])"
            ),
            "statement then empty inner then outer return" to (
                "do local t = 0 do end return t end" to
                    "Chunk(Block[Do(Block[Local(Id(t)=Const(0));Do(Block[]);Return(Id(t))])])"
            )
        )
    }

    @Test
    fun nestedDoInnerReturnExposesTypedNodesAndDoesNotLeakToOuter() {
        val outer = parse(LuaVersion.LUA_5_3, "do do return left, right end end")
            .firstStatement<DoStatement>()
        val outerBody = outer.body
        val inner = assertIs<DoStatement>(outerBody.statements.single())
        val innerReturn = assertNotNull(inner.body.returnStatement)

        assertNull(outerBody.returnStatement, "inner return must not attach to outer body")
        assertTrue(inner.body.statements.isEmpty())
        assertEquals(2, innerReturn.arguments.size)
        assertEquals("left", assertIs<Identifier>(innerReturn.arguments[0]).name)
        assertEquals("right", assertIs<Identifier>(innerReturn.arguments[1]).name)
        assertEquals("Return(Id(left),Id(right))", renderShape(innerReturn))
        assertEquals("Do(Block[Do(Block[Return(Id(left),Id(right))])])", renderShape(outer))

        assertSame(outer, outerBody.parent)
        assertSame(outerBody, inner.parent)
        assertSame(inner, inner.body.parent)
        assertSame(inner.body, innerReturn.parent)
        innerReturn.arguments.forEach { arg ->
            assertSame(innerReturn, arg.parent)
        }
    }

    @Test
    fun nestedDoSiblingInnersWithIndependentReturnShapes() {
        // Only the last statement in a block may be return; sibling inners that each
        // return are independent do statements under the outer body (no outer return).
        val source = """
            do
              do return 1 end
              do return 2, 3 end
            end
        """.trimIndent()

        val outer = parse(LuaVersion.LUA_5_3, source).firstStatement<DoStatement>()
        assertEquals(2, outer.body.statements.size)
        assertNull(outer.body.returnStatement)

        val first = assertIs<DoStatement>(outer.body.statements[0])
        val second = assertIs<DoStatement>(outer.body.statements[1])
        assertEquals("Return(Const(1))", renderShape(assertNotNull(first.body.returnStatement)))
        assertEquals(
            "Return(Const(2),Const(3))",
            renderShape(assertNotNull(second.body.returnStatement))
        )
        assertEquals(
            "Do(Block[Do(Block[Return(Const(1))]);Do(Block[Return(Const(2),Const(3))])])",
            renderShape(outer)
        )
    }

    // --- function / chunk context -------------------------------------------------

    @Test
    fun functionBodyNestedDoReturnShapesRemainReachable() {
        val declaration = parse(
            LuaVersion.LUA_5_3,
            "local function run() do do return a, b end end end"
        ).firstStatement<FunctionDeclaration>()

        assertTrue(declaration.isLocal)
        val body = assertNotNull(declaration.body)
        assertNull(body.returnStatement, "function return is nested inside do, not function body")
        val outerDo = assertIs<DoStatement>(body.statements.single())
        val innerDo = assertIs<DoStatement>(outerDo.body.statements.single())
        val ret = assertNotNull(innerDo.body.returnStatement)
        assertContentEquals(
            listOf("Id(a)", "Id(b)"),
            ret.arguments.map(::renderShape)
        )
        assertEquals(
            "Function(Id(run),Block[Do(Block[Do(Block[Return(Id(a),Id(b))])])])",
            renderShape(declaration)
        )
    }

    @Test
    fun chunkLevelDoThenChunkReturnKeepsBothReturnSites() {
        val source = """
            do
              local x = 1
            end
            return x
        """.trimIndent()
        val chunk = parse(LuaVersion.LUA_5_3, source)
        val doStatement = assertIs<DoStatement>(chunk.body.statements.single())
        assertNull(doStatement.body.returnStatement)
        assertIs<LocalStatement>(doStatement.body.statements.single())
        val chunkReturn = assertNotNull(chunk.body.returnStatement)
        assertEquals("Return(Id(x))", renderShape(chunkReturn))
        assertEquals(
            "Chunk(Block[Do(Block[Local(Id(x)=Const(1))]);Return(Id(x))])",
            renderShape(chunk)
        )
    }

    @Test
    fun nestedDoReturnArgumentNodeTypesMatchShapeTokens() {
        val doStatement = parse(
            LuaVersion.LUA_5_3,
            "do do return 1, name, pair(), ... end end"
        ).firstStatement<DoStatement>()
        val inner = assertIs<DoStatement>(doStatement.body.statements.single())
        val ret = assertNotNull(inner.body.returnStatement)
        assertEquals(4, ret.arguments.size)
        assertIs<ConstantNode>(ret.arguments[0])
        assertEquals("name", assertIs<Identifier>(ret.arguments[1]).name)
        assertIs<CallExpression>(ret.arguments[2])
        assertIs<VarargLiteral>(ret.arguments[3])
        assertEquals(
            "Return(Const(1),Id(name),Call(Id(pair):),Vararg)",
            renderShape(ret)
        )
    }

    // --- combined corpus table ----------------------------------------------------

    @Test
    fun corpusCoversEmptyNestedAndReturnShapeFamiliesTogether() {
        assertCaseShapes(
            "empty do" to (
                "do end" to "Chunk(Block[Do(Block[])])"
            ),
            "nested empty" to (
                "do do end end" to "Chunk(Block[Do(Block[Do(Block[])])])"
            ),
            "bare return do" to (
                "do return end" to "Chunk(Block[Do(Block[Return()])])"
            ),
            "single return do" to (
                "do return 1 end" to "Chunk(Block[Do(Block[Return(Const(1))])])"
            ),
            "multi return do" to (
                "do return a, b end" to "Chunk(Block[Do(Block[Return(Id(a),Id(b))])])"
            ),
            "nested inner return" to (
                "do do return x end end" to
                    "Chunk(Block[Do(Block[Do(Block[Return(Id(x))])])])"
            ),
            "outer return after empty inner" to (
                "do do end return y end" to
                    "Chunk(Block[Do(Block[Do(Block[]);Return(Id(y))])])"
            ),
            "local then return in do" to (
                "do local n = 0 return n end" to
                    "Chunk(Block[Do(Block[Local(Id(n)=Const(0));Return(Id(n))])])"
            ),
            "assign then nested return" to (
                "do a = 1 do return a end end" to
                    "Chunk(Block[Do(Block[Assign(Id(a)=Const(1));Do(Block[Return(Id(a))])])])"
            ),
            "triple nest multi return" to (
                "do do do return 1, 2 end end end" to
                    "Chunk(Block[Do(Block[Do(Block[Do(Block[Return(Const(1),Const(2))])])])])"
            )
        )
    }

    @Test
    fun emptyAndReturnShapesAreVersionPortableAcrossPlainLua() {
        val cases = listOf(
            "do end" to "Chunk(Block[Do(Block[])])",
            "do do end end" to "Chunk(Block[Do(Block[Do(Block[])])])",
            "do return end" to "Chunk(Block[Do(Block[Return()])])",
            "do return a, b end" to "Chunk(Block[Do(Block[Return(Id(a),Id(b))])])",
            "do do return 1 end end" to "Chunk(Block[Do(Block[Do(Block[Return(Const(1))])])])"
        )
        val versions = listOf(LuaVersion.LUA_5_3, LuaVersion.LUA_5_4, LuaVersion.ANDROLUA_5_3)
        cases.forEach { (source, expected) ->
            versions.forEach { version ->
                assertEquals(
                    expected,
                    renderShape(parse(version, source)),
                    "shape mismatch for `$source` under $version"
                )
            }
        }
    }

    private fun assertCaseShapes(vararg cases: Pair<String, Pair<String, String>>) {
        val failures = cases.mapNotNull { (name, case) ->
            val (source, expectedShape) = case
            runCatching {
                assertEquals(expectedShape, renderShape(parse(LuaVersion.LUA_5_3, source)), name)
            }.exceptionOrNull()?.let { failure ->
                "$name\nsource: $source\n${failure.message}"
            }
        }

        if (failures.isNotEmpty()) {
            fail(failures.joinToString(separator = "\n\n"))
        }
    }
}
