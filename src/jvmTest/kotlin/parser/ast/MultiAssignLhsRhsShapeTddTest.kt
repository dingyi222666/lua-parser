package parser.ast

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.IndexExpression
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.VarargLiteral
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import parser.firstStatement
import parser.parse
import parser.renderShape

/**
 * Multi-assign LHS/RHS AST shape corpus (TASK-316).
 *
 * Locks stable shapes for:
 * - balanced multi-LHS multi-RHS assignment and local declaration
 * - fewer-RHS-than-LHS and more-RHS-than-LHS (valid Lua; extra/missing values)
 * - mixed LHS forms (identifier / member / index)
 * - multi-value RHS call / vararg / table / function expression forms
 *
 * Legacy field naming (do not invert):
 * - [AssignmentStatement.init] = LHS targets
 * - [AssignmentStatement.variables] = RHS values
 * - [LocalStatement.init] = declared names
 * - [LocalStatement.variables] = RHS values
 *
 * Missing trailing RHS after a trailing comma (`a, b = 1,`) is a recovery-policy
 * concern covered by [parser.recovery.LuaParserRecoveryTddTest]; this corpus only
 * exercises well-formed multi-assign shapes under strict parse.
 *
 * Test-only; production parser changes are out of scope unless review re-scopes.
 */
class MultiAssignLhsRhsShapeTddTest {

    // --- balanced multi assignment ------------------------------------------------

    @Test
    fun balancedTwoTargetAssignmentShapeIsStable() {
        assertCaseShapes(
            "swap two identifiers" to (
                "a, b = b, a" to
                    "Chunk(Block[Assign(Id(a),Id(b)=Id(b),Id(a))])"
            ),
            "two constants" to (
                "x, y = 1, 2" to
                    "Chunk(Block[Assign(Id(x),Id(y)=Const(1),Const(2))])"
            ),
            "three targets three values" to (
                "a, b, c = 10, 20, 30" to
                    "Chunk(Block[Assign(Id(a),Id(b),Id(c)=Const(10),Const(20),Const(30))])"
            )
        )
    }

    @Test
    fun balancedTwoTargetLocalDeclarationShapeIsStable() {
        assertCaseShapes(
            "local two names two values" to (
                "local a, b = 1, 2" to
                    "Chunk(Block[Local(Id(a),Id(b)=Const(1),Const(2))])"
            ),
            "local three names three values" to (
                "local a, b, c = 10, 20, 30" to
                    "Chunk(Block[Local(Id(a),Id(b),Id(c)=Const(10),Const(20),Const(30))])"
            ),
            "local nil and boolean multi init" to (
                "local missing, enabled = nil, true" to
                    "Chunk(Block[Local(Id(missing),Id(enabled)=Const(nil),Const(true))])"
            )
        )
    }

    // --- unbalanced but valid multi-assign (no trailing-comma recovery) ------------

    @Test
    fun fewerRhsThanLhsShapesRemainStable() {
        // Lua pads missing RHS with nil at runtime; AST keeps unequal list lengths.
        assertCaseShapes(
            "two lhs one rhs assignment" to (
                "a, b = 1" to
                    "Chunk(Block[Assign(Id(a),Id(b)=Const(1))])"
            ),
            "three lhs one rhs assignment" to (
                "one, two, three = 1" to
                    "Chunk(Block[Assign(Id(one),Id(two),Id(three)=Const(1))])"
            ),
            "three lhs two rhs assignment" to (
                "a, b, c = x, y" to
                    "Chunk(Block[Assign(Id(a),Id(b),Id(c)=Id(x),Id(y))])"
            ),
            "two lhs one rhs local" to (
                "local a, b = 1" to
                    "Chunk(Block[Local(Id(a),Id(b)=Const(1))])"
            ),
            "three lhs one rhs local" to (
                "local one, two, three = 1" to
                    "Chunk(Block[Local(Id(one),Id(two),Id(three)=Const(1))])"
            ),
            "local without initializer list" to (
                "local a, b" to
                    "Chunk(Block[Local(Id(a),Id(b)=)])"
            )
        )
    }

    @Test
    fun moreRhsThanLhsShapesRemainStable() {
        // Extra RHS values are present in the AST and discarded at runtime.
        assertCaseShapes(
            "one lhs two rhs assignment" to (
                "a = 1, 2" to
                    "Chunk(Block[Assign(Id(a)=Const(1),Const(2))])"
            ),
            "two lhs three rhs assignment" to (
                "a, b = 1, 2, 3" to
                    "Chunk(Block[Assign(Id(a),Id(b)=Const(1),Const(2),Const(3))])"
            ),
            "one lhs two rhs local" to (
                "local a = 1, 2" to
                    "Chunk(Block[Local(Id(a)=Const(1),Const(2))])"
            ),
            "two lhs three rhs local" to (
                "local a, b = 1, 2, 3" to
                    "Chunk(Block[Local(Id(a),Id(b)=Const(1),Const(2),Const(3))])"
            )
        )
    }

    // --- mixed LHS forms ----------------------------------------------------------

    @Test
    fun mixedLhsTargetFormsKeepStableShapes() {
        assertCaseShapes(
            "identifier and member" to (
                "a, object.name = 1, value" to
                    "Chunk(Block[Assign(Id(a),Member(Id(object).name)=Const(1),Id(value))])"
            ),
            "identifier and index" to (
                "a, items[i] = 1, value" to
                    "Chunk(Block[Assign(Id(a),Index(Id(items)[Id(i)])=Const(1),Id(value))])"
            ),
            "member and index" to (
                "object.name, items[i] = left, right" to
                    "Chunk(Block[Assign(Member(Id(object).name),Index(Id(items)[Id(i)])=Id(left),Id(right))])"
            ),
            "nested member index chain multi assign" to (
                "root.child[i].name, flag = value, true" to
                    "Chunk(Block[Assign(Member(Index(Member(Id(root).child)[Id(i)]).name),Id(flag)=Id(value),Const(true))])"
            ),
            "three mixed targets" to (
                "a, t.k, t[1] = 1, 2, 3" to
                    "Chunk(Block[Assign(Id(a),Member(Id(t).k),Index(Id(t)[Const(1)])=Const(1),Const(2),Const(3))])"
            )
        )
    }

    // --- multi-value RHS forms ----------------------------------------------------

    @Test
    fun multiValueRhsCallVarargAndTableShapesRemainStable() {
        assertCaseShapes(
            "rhs multi-return call" to (
                "a, b = pair()" to
                    "Chunk(Block[Assign(Id(a),Id(b)=Call(Id(pair):))])"
            ),
            "rhs multi-return call plus extra" to (
                "a, b, c = pair(), extra" to
                    "Chunk(Block[Assign(Id(a),Id(b),Id(c)=Call(Id(pair):),Id(extra))])"
            ),
            "rhs vararg" to (
                "a, b = ..." to
                    "Chunk(Block[Assign(Id(a),Id(b)=Vararg)])"
            ),
            "rhs table and call" to (
                "cfg, ok = { enabled = true }, check()" to
                    "Chunk(Block[Assign(Id(cfg),Id(ok)=Table(TableKeyString(Id(enabled)=Const(true))),Call(Id(check):))])"
            ),
            "rhs function expression" to (
                "handler, tag = function(v) return v end, \"ok\"" to
                    "Chunk(Block[Assign(Id(handler),Id(tag)=Function(null,Block[Return(Id(v))]),Const(\"ok\"))])"
            ),
            "local from multi-return call" to (
                "local left, right = pair()" to
                    "Chunk(Block[Local(Id(left),Id(right)=Call(Id(pair):))])"
            )
        )
    }

    // --- field mapping + parents --------------------------------------------------

    @Test
    fun assignmentStatementUsesLegacyInitLhsVariablesRhsMapping() {
        val assign = parse(LuaVersion.LUA_5_3, "a, b, c = 1, pair()")
            .firstStatement<AssignmentStatement>()

        // init = LHS targets
        assertEquals(3, assign.init.size)
        assertContentEquals(listOf("a", "b", "c"), assign.init.map { assertIs<Identifier>(it).name })
        // variables = RHS values
        assertEquals(2, assign.variables.size)
        assertEquals("Const(1)", renderShape(assign.variables[0]))
        assertIs<CallExpression>(assign.variables[1])
        assertEquals("Call(Id(pair):)", renderShape(assign.variables[1]))

        assertEquals(
            "Assign(Id(a),Id(b),Id(c)=Const(1),Call(Id(pair):))",
            renderShape(assign)
        )

        assign.init.forEach { target ->
            assertSame(assign, target.parent, "LHS parent must be AssignmentStatement")
        }
        assign.variables.forEach { value ->
            assertSame(assign, value.parent, "RHS parent must be AssignmentStatement")
        }
    }

    @Test
    fun localStatementUsesLegacyInitNamesVariablesRhsMapping() {
        val local = parse(LuaVersion.LUA_5_3, "local first, second = 1, true")
            .firstStatement<LocalStatement>()

        assertEquals(2, local.init.size)
        assertContentEquals(listOf("first", "second"), local.init.map { it.name })
        assertTrue(local.init.all { it.isLocal })

        assertEquals(2, local.variables.size)
        assertIs<ConstantNode>(local.variables[0])
        assertIs<ConstantNode>(local.variables[1])
        assertEquals("Const(1)", renderShape(local.variables[0]))
        assertEquals("Const(true)", renderShape(local.variables[1]))

        assertEquals(
            "Local(Id(first),Id(second)=Const(1),Const(true))",
            renderShape(local)
        )

        local.init.forEach { name ->
            assertSame(local, name.parent, "declared-name parent must be LocalStatement")
        }
        local.variables.forEach { value ->
            assertSame(local, value.parent, "RHS parent must be LocalStatement")
        }
    }

    @Test
    fun unbalancedMultiAssignKeepsPerSideListLengthsWithoutInventingNodes() {
        val fewerRhs = parse(LuaVersion.LUA_5_3, "a, b, c = only")
            .firstStatement<AssignmentStatement>()
        assertEquals(3, fewerRhs.init.size)
        assertEquals(1, fewerRhs.variables.size)
        assertEquals("Id(only)", renderShape(fewerRhs.variables.single()))
        assertEquals(
            "Assign(Id(a),Id(b),Id(c)=Id(only))",
            renderShape(fewerRhs)
        )

        val moreRhs = parse(LuaVersion.LUA_5_3, "a = 1, 2, 3")
            .firstStatement<AssignmentStatement>()
        assertEquals(1, moreRhs.init.size)
        assertEquals(3, moreRhs.variables.size)
        assertContentEquals(
            listOf("Const(1)", "Const(2)", "Const(3)"),
            moreRhs.variables.map(::renderShape)
        )
        assertEquals(
            "Assign(Id(a)=Const(1),Const(2),Const(3))",
            renderShape(moreRhs)
        )

        val localFewer = parse(LuaVersion.LUA_5_3, "local x, y, z = seed")
            .firstStatement<LocalStatement>()
        assertEquals(3, localFewer.init.size)
        assertEquals(1, localFewer.variables.size)
        assertEquals(
            "Local(Id(x),Id(y),Id(z)=Id(seed))",
            renderShape(localFewer)
        )
    }

    @Test
    fun mixedLhsTargetsExposeConcreteNodeTypesInOrder() {
        val assign = parse(LuaVersion.LUA_5_3, "name, object.field, items[1] = a, b, c")
            .firstStatement<AssignmentStatement>()

        assertEquals(3, assign.init.size)
        assertEquals("name", assertIs<Identifier>(assign.init[0]).name)
        val member = assertIs<MemberExpression>(assign.init[1])
        assertEquals("object", assertIs<Identifier>(member.base).name)
        assertEquals(".", member.indexer)
        assertEquals("field", member.identifier.name)
        val index = assertIs<IndexExpression>(assign.init[2])
        assertEquals("items", assertIs<Identifier>(index.base).name)
        assertEquals("Const(1)", renderShape(index.index))

        assertEquals(3, assign.variables.size)
        assertContentEquals(
            listOf("Id(a)", "Id(b)", "Id(c)"),
            assign.variables.map(::renderShape)
        )
        assertEquals(
            "Assign(Id(name),Member(Id(object).field),Index(Id(items)[Const(1)])=Id(a),Id(b),Id(c))",
            renderShape(assign)
        )
    }

    @Test
    fun multiAssignRhsTableAndVarargKeepConcreteNodeTypes() {
        val tableAssign = parse(LuaVersion.LUA_5_3, "cfg, tag = { n = 1 }, \"x\"")
            .firstStatement<AssignmentStatement>()
        assertIs<TableConstructorExpression>(tableAssign.variables[0])
        assertIs<ConstantNode>(tableAssign.variables[1])
        assertEquals(
            "Assign(Id(cfg),Id(tag)=Table(TableKeyString(Id(n)=Const(1))),Const(\"x\"))",
            renderShape(tableAssign)
        )

        val varargAssign = parse(LuaVersion.LUA_5_3, "a, b = ...")
            .firstStatement<AssignmentStatement>()
        assertEquals(1, varargAssign.variables.size)
        assertIs<VarargLiteral>(varargAssign.variables.single())
        assertEquals("Vararg", renderShape(varargAssign.variables.single()))
        assertEquals(
            "Assign(Id(a),Id(b)=Vararg)",
            renderShape(varargAssign)
        )
    }

    // --- corpus table -------------------------------------------------------------

    @Test
    fun corpusCoversBalancedUnbalancedAndMixedLhsRhsFamiliesTogether() {
        assertCaseShapes(
            "balanced assign" to (
                "a, b = 1, 2" to "Chunk(Block[Assign(Id(a),Id(b)=Const(1),Const(2))])"
            ),
            "balanced local" to (
                "local a, b = 1, 2" to "Chunk(Block[Local(Id(a),Id(b)=Const(1),Const(2))])"
            ),
            "fewer rhs assign" to (
                "a, b = 1" to "Chunk(Block[Assign(Id(a),Id(b)=Const(1))])"
            ),
            "more rhs assign" to (
                "a = 1, 2" to "Chunk(Block[Assign(Id(a)=Const(1),Const(2))])"
            ),
            "fewer rhs local" to (
                "local a, b = 1" to "Chunk(Block[Local(Id(a),Id(b)=Const(1))])"
            ),
            "more rhs local" to (
                "local a = 1, 2" to "Chunk(Block[Local(Id(a)=Const(1),Const(2))])"
            ),
            "member multi assign" to (
                "t.x, t.y = 1, 2" to
                    "Chunk(Block[Assign(Member(Id(t).x),Member(Id(t).y)=Const(1),Const(2))])"
            ),
            "index multi assign" to (
                "t[1], t[2] = a, b" to
                    "Chunk(Block[Assign(Index(Id(t)[Const(1)]),Index(Id(t)[Const(2)])=Id(a),Id(b))])"
            ),
            "call multi return assign" to (
                "x, y = f()" to "Chunk(Block[Assign(Id(x),Id(y)=Call(Id(f):))])"
            ),
            "swap multi assign" to (
                "a, b = b, a" to "Chunk(Block[Assign(Id(a),Id(b)=Id(b),Id(a))])"
            )
        )
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
