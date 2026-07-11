package parser.ast

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableKey
import io.github.dingyi222666.luaparser.parser.ast.node.VarargLiteral
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import parser.firstStatement
import parser.parse
import parser.renderShape
import parser.returnExpression

/**
 * Corpus locking Lua 5.3 function parameter shapes that end with `...`, plus
 * body-side [VarargLiteral] reachability for local, global, and anonymous forms.
 *
 * Parameter `...` is reified as an [Identifier] whose [Identifier.name] is `"..."`.
 * Uses of `...` in function bodies are reified as [VarargLiteral] (shape `"Vararg"`).
 */
class VarargFunctionParamShapeTddTest {

    @Test
    fun localFunctionVarargOnlyParamParsesAndBodyVarargIsReachable() {
        val declaration = parse(LuaVersion.LUA_5_3, "local function pack(...) return ... end")
            .firstStatement<FunctionDeclaration>()

        assertTrue(declaration.isLocal)
        assertEquals("pack", assertIs<Identifier>(declaration.identifier).name)
        assertContentEquals(listOf("..."), declaration.params.map { it.name })
        assertVarargParamShape(declaration.params.single())

        val bodyReturn = assertNotNull(declaration.body!!.returnStatement)
        assertEquals(1, bodyReturn.arguments.size)
        assertIs<VarargLiteral>(bodyReturn.arguments.single())
        assertEquals("Vararg", renderShape(bodyReturn.arguments.single()))
        assertEquals("Function(Id(pack),Block[Return(Vararg)])", renderShape(declaration))
    }

    @Test
    fun localFunctionTrailingVarargAfterNamedParamsParses() {
        val declaration = parse(
            LuaVersion.LUA_5_3,
            "local function greet(first, second, ...) return first, second, ... end"
        ).firstStatement<FunctionDeclaration>()

        assertTrue(declaration.isLocal)
        assertContentEquals(listOf("first", "second", "..."), declaration.params.map { it.name })
        assertVarargParamShape(declaration.params.last())
        declaration.params.dropLast(1).forEach { param ->
            assertFalse(param.name == "...")
            assertIs<Identifier>(param)
        }

        val bodyReturn = assertNotNull(declaration.body!!.returnStatement)
        assertEquals(3, bodyReturn.arguments.size)
        assertEquals("Id(first)", renderShape(bodyReturn.arguments[0]))
        assertEquals("Id(second)", renderShape(bodyReturn.arguments[1]))
        assertIs<VarargLiteral>(bodyReturn.arguments[2])
        assertEquals("Vararg", renderShape(bodyReturn.arguments[2]))
    }

    @Test
    fun globalFunctionVarargParamParsesAndBodyVarargIsReachable() {
        val declaration = parse(LuaVersion.LUA_5_3, "function collect(a, ...) return a, ... end")
            .firstStatement<FunctionDeclaration>()

        assertFalse(declaration.isLocal)
        assertEquals("Id(collect)", renderShape(assertNotNull(declaration.identifier)))
        assertContentEquals(listOf("a", "..."), declaration.params.map { it.name })
        assertVarargParamShape(declaration.params.last())

        val bodyReturn = assertNotNull(declaration.body!!.returnStatement)
        assertEquals(2, bodyReturn.arguments.size)
        assertEquals("Id(a)", renderShape(bodyReturn.arguments[0]))
        assertIs<VarargLiteral>(bodyReturn.arguments[1])
        assertEquals("Return(Id(a),Vararg)", renderShape(bodyReturn))
    }

    @Test
    fun globalMethodStyleFunctionKeepsExplicitParamsPlusTrailingVararg() {
        // Method sugar injects `self` at call time; params list keeps declared names only.
        val declaration = parse(
            LuaVersion.LUA_5_3,
            "function obj:run(x, ...) return self, x, ... end"
        ).firstStatement<FunctionDeclaration>()

        assertFalse(declaration.isLocal)
        val identifier = assertIs<MemberExpression>(declaration.identifier)
        assertEquals(":", identifier.indexer)
        assertEquals("run", identifier.identifier.name)
        assertEquals("Member(Id(obj):run)", renderShape(identifier))
        assertContentEquals(listOf("x", "..."), declaration.params.map { it.name })
        assertVarargParamShape(declaration.params.last())

        val bodyReturn = assertNotNull(declaration.body!!.returnStatement)
        assertContentEquals(
            listOf("Id(self)", "Id(x)", "Vararg"),
            bodyReturn.arguments.map(::renderShape)
        )
        assertIs<VarargLiteral>(bodyReturn.arguments.last())
    }

    @Test
    fun anonymousFunctionVarargOnlyParamParsesAsExpression() {
        val anonymous = assertIs<FunctionDeclaration>(
            parse(LuaVersion.LUA_5_3, "return function(...) return ... end").returnExpression()
        )

        assertNull(anonymous.identifier)
        assertFalse(anonymous.isLocal)
        assertContentEquals(listOf("..."), anonymous.params.map { it.name })
        assertVarargParamShape(anonymous.params.single())

        val bodyReturn = assertNotNull(anonymous.body!!.returnStatement)
        assertIs<VarargLiteral>(bodyReturn.arguments.single())
        assertEquals("Function(null,Block[Return(Vararg)])", renderShape(anonymous))
    }

    @Test
    fun anonymousFunctionTrailingVarargWithNamedParamsParses() {
        val anonymous = assertIs<FunctionDeclaration>(
            parse(
                LuaVersion.LUA_5_3,
                "return function(head, ...) return head, ... end"
            ).returnExpression()
        )

        assertNull(anonymous.identifier)
        assertContentEquals(listOf("head", "..."), anonymous.params.map { it.name })
        assertVarargParamShape(anonymous.params.last())

        val bodyReturn = assertNotNull(anonymous.body!!.returnStatement)
        assertEquals(2, bodyReturn.arguments.size)
        assertEquals("Id(head)", renderShape(bodyReturn.arguments[0]))
        assertIs<VarargLiteral>(bodyReturn.arguments[1])
    }

    @Test
    fun localAssignedAnonymousFunctionVarargParamIsReachableInBody() {
        val chunk = parse(
            LuaVersion.LUA_5_3,
            "local wrap = function(first, ...) return first, ... end"
        )
        val local = chunk.firstStatement<LocalStatement>()
        val anonymous = assertIs<FunctionDeclaration>(local.variables.single())

        assertEquals("wrap", local.init.single().name)
        assertNull(anonymous.identifier)
        assertContentEquals(listOf("first", "..."), anonymous.params.map { it.name })
        assertVarargParamShape(anonymous.params.last())

        val bodyReturn = assertNotNull(anonymous.body!!.returnStatement)
        assertIs<VarargLiteral>(bodyReturn.arguments.last())
        assertEquals(
            "Local(Id(wrap)=Function(null,Block[Return(Id(first),Vararg)]))",
            renderShape(local)
        )
    }

    @Test
    fun varargUsageInTableConstructorBodyRemainsReachable() {
        val declaration = parse(
            LuaVersion.LUA_5_3,
            """
            local function pack(first, ...)
                local args = { first, ... }
                return args
            end
            """.trimIndent()
        ).firstStatement<FunctionDeclaration>()

        assertContentEquals(listOf("first", "..."), declaration.params.map { it.name })
        assertVarargParamShape(declaration.params.last())

        val packLocal = assertIs<LocalStatement>(declaration.body!!.statements.single())
        assertEquals("args", packLocal.init.single().name)
        val table = assertIs<TableConstructorExpression>(packLocal.variables.single())
        assertEquals(2, table.fields.size)

        val firstField = assertIs<TableKey>(table.fields[0])
        assertEquals("Const(1)", renderShape(firstField.key))
        assertEquals("Id(first)", renderShape(firstField.value))

        val varargField = assertIs<TableKey>(table.fields[1])
        assertEquals("Const(2)", renderShape(varargField.key))
        assertIs<VarargLiteral>(varargField.value)
        assertEquals("Vararg", renderShape(varargField.value))

        val bodyReturn = assertNotNull(declaration.body!!.returnStatement)
        assertEquals("Id(args)", renderShape(bodyReturn.arguments.single()))
    }

    @Test
    fun varargUsageAsCallArgumentInBodyRemainsReachable() {
        val declaration = parse(
            LuaVersion.LUA_5_3,
            "function forward(tag, ...) return consume(tag, ...) end"
        ).firstStatement<FunctionDeclaration>()

        assertContentEquals(listOf("tag", "..."), declaration.params.map { it.name })
        assertVarargParamShape(declaration.params.last())

        val bodyReturn = assertNotNull(declaration.body!!.returnStatement)
        val call = assertIs<CallExpression>(bodyReturn.arguments.single())
        assertEquals("consume", assertIs<Identifier>(call.base).name)
        assertEquals(2, call.arguments.size)
        assertEquals("Id(tag)", renderShape(call.arguments[0]))
        assertIs<VarargLiteral>(call.arguments[1])
        assertEquals(
            "Call(Id(consume):Id(tag),Vararg)",
            renderShape(call)
        )
    }

    @Test
    fun multipleVarargUsagesInSameBodyRemainReachable() {
        val declaration = parse(
            LuaVersion.LUA_5_3,
            """
            local function dual(...)
                local packed = { ... }
                return packed, ...
            end
            """.trimIndent()
        ).firstStatement<FunctionDeclaration>()

        assertContentEquals(listOf("..."), declaration.params.map { it.name })
        assertVarargParamShape(declaration.params.single())

        val packedLocal = assertIs<LocalStatement>(declaration.body!!.statements.single())
        val packedTable = assertIs<TableConstructorExpression>(packedLocal.variables.single())
        assertEquals(1, packedTable.fields.size)
        assertIs<VarargLiteral>(assertIs<TableKey>(packedTable.fields.single()).value)

        val bodyReturn = assertNotNull(declaration.body!!.returnStatement)
        assertEquals(2, bodyReturn.arguments.size)
        assertEquals("Id(packed)", renderShape(bodyReturn.arguments[0]))
        assertIs<VarargLiteral>(bodyReturn.arguments[1])
        assertEquals("Return(Id(packed),Vararg)", renderShape(bodyReturn))
    }

    @Test
    fun dottedGlobalFunctionTrailingVarargParamParses() {
        val declaration = parse(
            LuaVersion.LUA_5_3,
            "function module.pack(...) return { ... } end"
        ).firstStatement<FunctionDeclaration>()

        assertFalse(declaration.isLocal)
        assertEquals("Member(Id(module).pack)", renderShape(assertNotNull(declaration.identifier)))
        assertContentEquals(listOf("..."), declaration.params.map { it.name })
        assertVarargParamShape(declaration.params.single())

        val bodyReturn = assertNotNull(declaration.body!!.returnStatement)
        val table = assertIs<TableConstructorExpression>(bodyReturn.arguments.single())
        assertEquals(1, table.fields.size)
        assertIs<VarargLiteral>(assertIs<TableKey>(table.fields.single()).value)
        assertEquals("Table(TableKey(Const(1)=Vararg))", renderShape(table))
    }

    @Test
    fun corpusCoversLocalGlobalAndAnonymousVarargParamFormsTogether() {
        val cases = listOf(
            Triple(
                "local function L(...)\n  return ...\nend",
                true,
                listOf("...")
            ),
            Triple(
                "function G(a, ...)\n  return a, ...\nend",
                false,
                listOf("a", "...")
            ),
            Triple(
                "return function(x, y, ...)\n  return x, y, ...\nend",
                false,
                listOf("x", "y", "...")
            )
        )

        cases.forEach { (source, expectLocal, expectedParams) ->
            val chunk = parse(LuaVersion.LUA_5_3, source)
            val declaration = if (source.trimStart().startsWith("return")) {
                assertIs<FunctionDeclaration>(chunk.returnExpression())
            } else {
                chunk.firstStatement<FunctionDeclaration>()
            }

            if (source.trimStart().startsWith("return")) {
                assertNull(declaration.identifier)
            } else {
                assertEquals(expectLocal, declaration.isLocal)
            }
            assertContentEquals(expectedParams, declaration.params.map { it.name }, source)
            assertVarargParamShape(declaration.params.last(), source)

            val bodyReturn = assertNotNull(declaration.body!!.returnStatement, source)
            assertIs<VarargLiteral>(bodyReturn.arguments.last(), source)
            assertEquals("Vararg", renderShape(bodyReturn.arguments.last()), source)
        }
    }

    private fun assertVarargParamShape(param: Identifier, context: String = param.name) {
        assertEquals("...", param.name, "vararg param name must be '...' ($context)")
        // Parameter slots are Identifiers named "..."; body uses of `...` are VarargLiteral.
        assertEquals("Id(...)", renderShape(param), context)
        assertEquals(Identifier::class, param::class, "param slot class ($context)")
    }
}
