package parser.lua53

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableKey
import io.github.dingyi222666.luaparser.parser.ast.node.TableKeyString
import io.github.dingyi222666.luaparser.parser.ast.node.VarargLiteral
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import parser.firstStatement
import parser.parse
import parser.renderShape

class Lua53TableFunctionModuleTddTest {

    @Test
    fun parsesResourceBackedComprehensiveModuleFixture() {
        val source = loadTableFunctionModuleFixture("comprehensive_module.lua")
        val expectedShape = loadTableFunctionModuleFixture("comprehensive_module.shape.txt").trimEnd()
        val chunk = parse(LuaVersion.LUA_5_3, source)

        assertEquals(expectedShape, renderShape(chunk).trimEnd())
        assertEquals(4, chunk.body.statements.size)

        val moduleTable = chunk.firstStatement<LocalStatement>()
        assertEquals("M", moduleTable.init.single().name)
        assertIs<TableConstructorExpression>(moduleTable.variables.single())

        val requireStatement = assertIs<LocalStatement>(chunk.body.statements[1])
        assertEquals("dep", requireStatement.init.single().name)
        assertEquals("Call(Id(require):Const(\"dep\"))", renderShape(requireStatement.variables.single()))

        assertEquals("CallStmt(Call(Id(module):Const(\"legacy.module\"),Member(Id(package).seeall)))", renderShape(chunk.body.statements[2]))

        val factory = assertIs<FunctionDeclaration>(chunk.body.statements[3])
        assertEquals("Member(Id(M).make)", renderShape(factory.identifier!!))
        assertContentEquals(listOf("name", "..."), factory.params.map { it.name })
        val returnedTable = assertIs<TableConstructorExpression>(factory.body!!.returnStatement!!.arguments.single())
        assertContentEquals(
            listOf(
                "TableKeyString(Id(name)=Id(name))",
                "TableKeyString(Id(dep)=Id(dep))",
                "TableKeyString(Id(args)=Table(TableKey(Const(1)=Vararg)))"
            ),
            returnedTable.fields.map(::renderShape)
        )

        val moduleReturn = assertIs<TableConstructorExpression>(chunk.body.returnStatement!!.arguments.single())
        assertEquals(2, moduleReturn.fields.size)
        assertTrue(moduleReturn.fields.all { it is TableKeyString })
    }

    @Test
    fun parsesTableConstructorsWithFieldsKeysAndSeparators() {
        assertCaseShapes(
            "empty constructor" to (
                "return {}" to
                    "Chunk(Block[Return(Table())])"
            ),
            "single array field" to (
                "return { 1 }" to
                    "Chunk(Block[Return(Table(TableKey(Const(1)=Const(1))))])"
            ),
            "single string field" to (
                "return { name = value }" to
                    "Chunk(Block[Return(Table(TableKeyString(Id(name)=Id(value))))])"
            ),
            "single bracketed field" to (
                "return { [key] = value }" to
                    "Chunk(Block[Return(Table(TableKey(Id(key)=Id(value))))])"
            ),
            "numeric bracketed key" to (
                "return { [1] = 'one' }" to
                    "Chunk(Block[Return(Table(TableKey(Const(1)=Const('one'))))])"
            ),
            "string bracketed key" to (
                "return { [\"name\"] = value }" to
                    "Chunk(Block[Return(Table(TableKey(Const(\"name\")=Id(value))))])"
            ),
            "expression bracketed key" to (
                "return { [prefix .. suffix] = value }" to
                    "Chunk(Block[Return(Table(TableKey(Binary(..,Id(prefix),Id(suffix))=Id(value))))])"
            ),
            "comma after keyed field" to (
                "return { name = value, }" to
                    "Chunk(Block[Return(Table(TableKeyString(Id(name)=Id(value))))])"
            ),
            "semicolon after keyed field" to (
                "return { name = value; }" to
                    "Chunk(Block[Return(Table(TableKeyString(Id(name)=Id(value))))])"
            ),
            "comma after bracketed field" to (
                "return { [key] = value, }" to
                    "Chunk(Block[Return(Table(TableKey(Id(key)=Id(value))))])"
            ),
            "semicolon after bracketed field" to (
                "return { [key] = value; }" to
                    "Chunk(Block[Return(Table(TableKey(Id(key)=Id(value))))])"
            ),
            "comma after array field" to (
                "return { value, }" to
                    "Chunk(Block[Return(Table(TableKey(Const(1)=Id(value))))])"
            ),
            "semicolon after array field" to (
                "return { value; }" to
                    "Chunk(Block[Return(Table(TableKey(Const(1)=Id(value))))])"
            ),
            "string and bracketed fields" to (
                "return { name = value, [key] = other }" to
                    "Chunk(Block[Return(Table(TableKeyString(Id(name)=Id(value)),TableKey(Id(key)=Id(other))))])"
            ),
            "nested constructor field" to (
                "return { nested = { enabled = true } }" to
                    "Chunk(Block[Return(Table(TableKeyString(Id(nested)=Table(TableKeyString(Id(enabled)=Const(true))))))])"
            ),
            "function field" to (
                "return { make = function(value) return value end }" to
                    "Chunk(Block[Return(Table(TableKeyString(Id(make)=Function(null,Block[Return(Id(value))]))))])"
            ),
            "call expression field" to (
                "return { value = factory(seed) }" to
                    "Chunk(Block[Return(Table(TableKeyString(Id(value)=Call(Id(factory):Id(seed)))))])"
            ),
            "member expression field" to (
                "return { value = object.member }" to
                    "Chunk(Block[Return(Table(TableKeyString(Id(value)=Member(Id(object).member))))])"
            )
        )
    }

    @Test
    fun parsesFunctionBodiesVarargsMethodsAndNestedFunctions() {
        assertCaseShapes(
            "empty global function" to (
                "function empty() end" to
                    "Chunk(Block[Function(Id(empty),Block[])])"
            ),
            "global function returning constant" to (
                "function one() return 1 end" to
                    "Chunk(Block[Function(Id(one),Block[Return(Const(1))])])"
            ),
            "local function returning parameter" to (
                "local function identity(value) return value end" to
                    "Chunk(Block[Function(Id(identity),Block[Return(Id(value))])])"
            ),
            "dotted function declaration" to (
                "function module.make(value) return value end" to
                    "Chunk(Block[Function(Member(Id(module).make),Block[Return(Id(value))])])"
            ),
            "nested dotted function declaration" to (
                "function root.module.make(value) return value end" to
                    "Chunk(Block[Function(Member(Member(Id(root).module).make),Block[Return(Id(value))])])"
            ),
            "method declaration returning self" to (
                "function module:make(value) return self, value end" to
                    "Chunk(Block[Function(Member(Id(module):make),Block[Return(Id(self),Id(value))])])"
            ),
            "function declaration returning vararg" to (
                "function collect(first, ...) return first, ... end" to
                    "Chunk(Block[Function(Id(collect),Block[Return(Id(first),Vararg)])])"
            ),
            "anonymous function expression" to (
                "return function(value) return value end" to
                    "Chunk(Block[Return(Function(null,Block[Return(Id(value))]))])"
            ),
            "anonymous vararg function expression" to (
                "return function(...) return ... end" to
                    "Chunk(Block[Return(Function(null,Block[Return(Vararg)]))])"
            ),
            "local initialized by anonymous function" to (
                "local handler = function(value) return value end" to
                    "Chunk(Block[Local(Id(handler)=Function(null,Block[Return(Id(value))]))])"
            ),
            "nested local function" to (
                "function outer() local function inner() return 1 end return inner() end" to
                    "Chunk(Block[Function(Id(outer),Block[Function(Id(inner),Block[Return(Const(1))]);Return(Call(Id(inner):))])])"
            ),
            "nested anonymous function return" to (
                "return function(a) return function(b) return a, b end end" to
                    "Chunk(Block[Return(Function(null,Block[Return(Function(null,Block[Return(Id(a),Id(b))]))]))])"
            ),
            "method body with local table" to (
                "function module:build(name) local item = { name = name } return item end" to
                    "Chunk(Block[Function(Member(Id(module):build),Block[Local(Id(item)=Table(TableKeyString(Id(name)=Id(name))));Return(Id(item))])])"
            ),
            "function returning table" to (
                "function module.options() return { enabled = true } end" to
                    "Chunk(Block[Function(Member(Id(module).options),Block[Return(Table(TableKeyString(Id(enabled)=Const(true))))])])"
            ),
            "function returning call result" to (
                "function module.load(name) return require(name) end" to
                    "Chunk(Block[Function(Member(Id(module).load),Block[Return(Call(Id(require):Id(name)))])])"
            ),
            "function with vararg table body" to (
                "function module.pack(...) local args = {...} return args end" to
                    "Chunk(Block[Function(Member(Id(module).pack),Block[Local(Id(args)=Table(TableKey(Const(1)=Vararg)));Return(Id(args))])])"
            )
        )
    }

    @Test
    fun parsesRequireModuleAndReturnTableForms() {
        assertCaseShapes(
            "parenthesized require local" to (
                "local json = require(\"json\")" to
                    "Chunk(Block[Local(Id(json)=Call(Id(require):Const(\"json\")))])"
            ),
            "short string require local" to (
                "local json = require \"json\"" to
                    "Chunk(Block[Local(Id(json)=Call(StringCall(Id(require):Const(\"json\")):))])"
            ),
            "dotted package require" to (
                "local api = require(\"pkg.api\")" to
                    "Chunk(Block[Local(Id(api)=Call(Id(require):Const(\"pkg.api\")))])"
            ),
            "require member access" to (
                "local create = require(\"pkg.factory\").create" to
                    "Chunk(Block[Local(Id(create)=Member(Call(Id(require):Const(\"pkg.factory\")).create))])"
            ),
            "module call with name" to (
                "module(\"legacy\")" to
                    "Chunk(Block[CallStmt(Call(Id(module):Const(\"legacy\")))])"
            ),
            "module call with package seeall" to (
                "module(\"legacy\", package.seeall)" to
                    "Chunk(Block[CallStmt(Call(Id(module):Const(\"legacy\"),Member(Id(package).seeall)))])"
            ),
            "package loaded assignment" to (
                "package.loaded[\"legacy\"] = M" to
                    "Chunk(Block[Assign(Index(Member(Id(package).loaded)[Const(\"legacy\")])=Id(M))])"
            ),
            "empty module table returned" to (
                "local M = {} return M" to
                    "Chunk(Block[Local(Id(M)=Table());Return(Id(M))])"
            ),
            "return table module with value" to (
                "return { value = value }" to
                    "Chunk(Block[Return(Table(TableKeyString(Id(value)=Id(value))))])"
            ),
            "return table module with require" to (
                "return { dependency = require(\"dep\") }" to
                    "Chunk(Block[Return(Table(TableKeyString(Id(dependency)=Call(Id(require):Const(\"dep\")))))])"
            ),
            "return table module with anonymous function" to (
                "return { new = function() return {} end }" to
                    "Chunk(Block[Return(Table(TableKeyString(Id(new)=Function(null,Block[Return(Table())]))))])"
            ),
            "return table module with member export" to (
                "local M = {} return { new = M.new }" to
                    "Chunk(Block[Local(Id(M)=Table());Return(Table(TableKeyString(Id(new)=Member(Id(M).new))))])"
            ),
            "classic module table with __index" to (
                "local M = {} M.__index = M return M" to
                    "Chunk(Block[Local(Id(M)=Table());Assign(Member(Id(M).__index)=Id(M));Return(Id(M))])"
            ),
            "method module returned" to (
                "local M = {} function M:new() return self end return M" to
                    "Chunk(Block[Local(Id(M)=Table());Function(Member(Id(M):new),Block[Return(Id(self))]);Return(Id(M))])"
            ),
            "setup function exported from return table" to (
                "local M = {} function M.setup(opts) return opts end return { setup = M.setup }" to
                    "Chunk(Block[Local(Id(M)=Table());Function(Member(Id(M).setup),Block[Return(Id(opts))]);Return(Table(TableKeyString(Id(setup)=Member(Id(M).setup))))])"
            ),
            "module table initialized with function field" to (
                "local M = { setup = function(opts) return opts end } return M" to
                    "Chunk(Block[Local(Id(M)=Table(TableKeyString(Id(setup)=Function(null,Block[Return(Id(opts))]))));Return(Id(M))])"
            )
        )
    }

    @Test
    fun exposesAstStructureAndRangesForRepresentativeModuleForms() {
        val source = """
            local M = { name = "module", [1] = "first", options = { enabled = true } }
            local dep = require("dep")
            function M:run(arg, ...)
                local wrapper = function(value)
                    return function(extra)
                        return value, extra, ...
                    end
                end
                return self, arg, dep, wrapper
            end
            return { run = M.run, dep = dep, module = M }
        """.trimIndent()
        val chunk = parse(LuaVersion.LUA_5_3, source)

        assertEquals(3, chunk.body.statements.size)
        assertRangeLines(chunk.body.statements[0], 1, 1)
        assertRangeLines(chunk.body.statements[1], 2, 2)
        assertRangeLines(chunk.body.statements[2], 3, 11)
        assertRangeLines(chunk.body.returnStatement!!, 11, 11)

        val moduleLocal = assertIs<LocalStatement>(chunk.body.statements[0])
        val moduleTable = assertIs<TableConstructorExpression>(moduleLocal.variables.single())
        assertEquals(3, moduleTable.fields.size)
        assertIs<TableKeyString>(moduleTable.fields[0])
        assertIs<TableKey>(moduleTable.fields[1])
        assertIs<TableKeyString>(moduleTable.fields[2])
        assertEquals("TableKeyString(Id(name)=Const(\"module\"))", renderShape(moduleTable.fields[0]))
        assertEquals("TableKey(Const(1)=Const(\"first\"))", renderShape(moduleTable.fields[1]))
        assertEquals(
            "TableKeyString(Id(options)=Table(TableKeyString(Id(enabled)=Const(true))))",
            renderShape(moduleTable.fields[2])
        )

        val requireLocal = assertIs<LocalStatement>(chunk.body.statements[1])
        val requireCall = assertIs<CallExpression>(requireLocal.variables.single())
        assertEquals("require", assertIs<Identifier>(requireCall.base).name)
        assertEquals("Const(\"dep\")", renderShape(requireCall.arguments.single()))

        val method = assertIs<FunctionDeclaration>(chunk.body.statements[2])
        val methodName = assertIs<MemberExpression>(method.identifier)
        assertEquals(":", methodName.indexer)
        assertEquals("M", assertIs<Identifier>(methodName.base).name)
        assertEquals("run", methodName.identifier.name)
        assertContentEquals(listOf("arg", "..."), method.params.map { it.name })

        val wrapperLocal = assertIs<LocalStatement>(method.body!!.statements.single())
        val wrapperFunction = assertIs<FunctionDeclaration>(wrapperLocal.variables.single())
        assertContentEquals(listOf("value"), wrapperFunction.params.map { it.name })
        val innerFunction = assertIs<FunctionDeclaration>(wrapperFunction.body!!.returnStatement!!.arguments.single())
        assertContentEquals(listOf("extra"), innerFunction.params.map { it.name })
        val innerReturn = innerFunction.body!!.returnStatement!!
        assertContentEquals(listOf("Id(value)", "Id(extra)", "Vararg"), innerReturn.arguments.map(::renderShape))
        assertIs<VarargLiteral>(innerReturn.arguments.last())

        val methodReturn = method.body!!.returnStatement!!
        assertContentEquals(
            listOf("Id(self)", "Id(arg)", "Id(dep)", "Id(wrapper)"),
            methodReturn.arguments.map(::renderShape)
        )

        val returnedModule = assertIs<TableConstructorExpression>(chunk.body.returnStatement!!.arguments.single())
        assertContentEquals(
            listOf(
                "TableKeyString(Id(run)=Member(Id(M).run))",
                "TableKeyString(Id(dep)=Id(dep))",
                "TableKeyString(Id(module)=Id(M))"
            ),
            returnedModule.fields.map(::renderShape)
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

    private fun assertRangeLines(node: BaseASTNode, startLine: Int, endLine: Int) {
        assertEquals(startLine, node.range.start.line)
        assertEquals(endLine, node.range.end.line)
        assertTrue(node.range.start.column >= 1)
        assertTrue(node.range.end.column >= 1)
    }

    private fun loadTableFunctionModuleFixture(name: String): String {
        val path = "/parser/tdd/lua53/table-function-module/$name"
        return checkNotNull(javaClass.getResourceAsStream(path)) {
            "Missing table/function/module fixture: $path"
        }.bufferedReader().use { it.readText() }
    }
}
