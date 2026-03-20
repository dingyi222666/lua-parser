package semantic.model

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.api.ScopeKind
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.api.TypeInfo
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.model.SemanticModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SemanticModelFacadeTest {

    @Test
    fun getSymbolAtReturnsDeclarationSymbolsAtDeclarationSites() {
        val harness = semanticModelHarness(
            """
            local value = 1
            local function render(input)
                return input
            end
            """.trimIndent()
        )

        assertEquals(SymbolKind.LOCAL, harness.model.getSymbolAt(harness.positionOf("value"))?.kind)
        assertEquals(SymbolKind.FUNCTION, harness.model.getSymbolAt(harness.positionOf("render"))?.kind)
        assertEquals(SymbolKind.PARAMETER, harness.model.getSymbolAt(harness.positionOf("input"))?.kind)
    }

    @Test
    fun getSymbolAtResolvesBareIdentifierUsageToNearestVisibleDeclaration() {
        val harness = semanticModelHarness(
            """
            local value = 1
            do
                local value = "x"
                local current = value
            end
            """.trimIndent()
        )

        val symbol = harness.model.getSymbolAt(harness.positionOf("value", occurrence = 3))

        assertNotNull(symbol)
        assertEquals(SymbolKind.LOCAL, symbol.kind)
        assertEquals("value", symbol.name)
        assertEquals(harness.positionOf("value", occurrence = 2), symbol.range?.start)
    }

    @Test
    fun getSymbolAtReturnsDocOwnedSymbolsAtCommentDeclarationPositions() {
        val harness = semanticModelHarness(
            """
            ---@alias Name string
            ---@class User
            ---@field id integer
            ---@method User:getName(): string
            """.trimIndent()
        )

        assertEquals(SymbolKind.TYPE_ALIAS, harness.model.getSymbolAt(harness.positionOf("Name"))?.kind)
        assertEquals(SymbolKind.CLASS, harness.model.getSymbolAt(harness.positionOf("User"))?.kind)
        assertEquals(SymbolKind.FIELD, harness.model.getSymbolAt(harness.positionOf("id"))?.kind)
        assertEquals(SymbolKind.METHOD, harness.model.getSymbolAt(harness.positionOf("getName"))?.kind)
    }

    @Test
    fun getTypeAtReturnsExpressionTypesAcrossCommonNodes() {
        val harness = semanticModelHarness(
            """
            local seed = 1
            local copy = seed
            local function make()
                return 1
            end
            local called = make()
            ---@class User
            ---@field name string
            ---@type User
            local user = {}
            local label = user.name
            """.trimIndent()
        )

        assertEquals("1", harness.model.getTypeAt(harness.localInitializer("seed"))?.displayName)
        assertEquals("1", harness.model.getTypeAt(harness.localInitializer("copy"))?.displayName)
        assertEquals("1", harness.model.getTypeAt(harness.localInitializer("called"))?.displayName)
        assertEquals("string", harness.model.getTypeAt(harness.localInitializer("label"))?.displayName)
    }

    @Test
    fun getDeclaredTypeReturnsResolvedDeclaredTypes() {
        val harness = semanticModelHarness(
            """
            ---@alias Name string
            ---@class User
            ---@field id integer
            ---@param value number
            ---@return string
            local function render(value)
                return tostring(value)
            end
            ---@type string
            local annotated = 1
            """.trimIndent()
        )

        val annotated = harness.model.getSymbolAt(harness.positionOf("annotated"))
        val render = harness.model.getSymbolAt(harness.positionOf("render"))
        val alias = harness.model.getSymbolAt(harness.positionOf("Name"))
        val klass = harness.model.getSymbolAt(harness.positionOf("User"))

        assertEquals("string", harness.model.getDeclaredType(assertNotNull(annotated))?.displayName)
        assertEquals("fun(value: number): string", harness.model.getDeclaredType(assertNotNull(render))?.displayName)
        assertEquals("Name", harness.model.getDeclaredType(assertNotNull(alias))?.displayName)
        assertEquals("User", harness.model.getDeclaredType(assertNotNull(klass))?.displayName)
    }

    @Test
    fun getInferredTypeReturnsImplementationDrivenFunctionType() {
        val harness = semanticModelHarness(
            """
            ---@param value string
            ---@return number
            local function render(value)
                return 1, 2
            end
            """.trimIndent()
        )

        val render = harness.model.getSymbolAt(harness.positionOf("render"))

        assertEquals("fun(value: string): number", harness.model.getDeclaredType(assertNotNull(render))?.displayName)
        assertEquals("fun(value: string): 1, 2", harness.model.getInferredType(assertNotNull(render))?.displayName)
    }

    @Test
    fun getMembersReturnsResolvedMemberSurfaces() {
        val harness = semanticModelHarness(
            """
            ---@class Base
            ---@field id integer
            ---@method Base:getId(): integer
            ---@class User: Base
            ---@field name string
            ---@method User:getName(): string
            ---@class Box<T>
            ---@field value T
            ---@alias NamedBox Box<string>
            ---@type User
            local user = {}
            local record = { plain = 1, greet = function() return "x" end }
            ---@type NamedBox
            local boxed = {}
            ---@type { shared: string, left: boolean } | { shared: number, right: boolean }
            local mixed = {}
            """.trimIndent()
        )

        val userMembers = harness.model.getMembers(assertNotNull(harness.model.getDeclaredType(assertNotNull(harness.model.getSymbolAt(harness.positionOf("User"))))))
        val recordMembers = harness.model.getMembers(assertNotNull(harness.model.getTypeAt(harness.localInitializer("record"))))
        val boxedMembers = harness.model.getMembers(assertNotNull(harness.model.getTypeAt(harness.identifier("boxed"))))
        val mixedMembers = harness.model.getMembers(assertNotNull(harness.model.getTypeAt(harness.identifier("mixed"))))

        println(userMembers)

        assertTrue(userMembers.any { it.name == "id" && it.kind == SymbolKind.FIELD })
        assertTrue(userMembers.any { it.name == "getName" && it.kind == SymbolKind.METHOD })
        assertTrue(recordMembers.any { it.name == "plain" && it.kind == SymbolKind.FIELD })
        assertTrue(recordMembers.any { it.name == "greet" && it.kind == SymbolKind.METHOD })
        assertTrue(boxedMembers.any { it.name == "value" && it.type?.displayName == "string" })
        assertEquals(listOf("shared"), mixedMembers.map { it.name })
        assertTrue(mixedMembers.single().type?.displayName?.contains("string") == true)
        assertTrue(mixedMembers.single().type?.displayName?.contains("number") == true)
    }

    @Test
    fun getMembersReturnsEmptyForNonModelMintedTypeInfo() {
        val harness = semanticModelHarness("local value = 1")

        assertEquals(emptyList(), harness.model.getMembers(TypeInfo("number")))
    }

    @Test
    fun getScopeAtReturnsInnermostScopeAndVisibleSymbols() {
        val harness = semanticModelHarness(
            """
            local outer = 1
            local function render(param)
                do
                    local outer = "x"
                    local inner = outer
                end
            end
            """.trimIndent()
        )

        val scope = harness.model.getScopeAt(harness.positionOf("inner"))

        assertNotNull(scope)
        assertEquals(ScopeKind.BLOCK, scope.kind)
        assertTrue(scope.symbols.any { it.name == "inner" })
        assertTrue(scope.symbols.any { it.name == "param" })
        assertTrue(scope.symbols.any { it.name == "render" })
        assertEquals(1, scope.symbols.count { it.name == "outer" })
        assertEquals(harness.positionOf("outer", occurrence = 2), scope.symbols.single { it.name == "outer" }.range?.start)
    }
}

internal class SemanticModelHarness(
    val source: String,
    val chunk: ChunkNode,
    val binder: BinderPassResult,
    val model: SemanticModel
) {
    internal inline fun <reified T : BaseASTNode> nodes(): List<T> {
        return buildList {
            visit(chunk) { node ->
                if (node is T) {
                    add(node)
                }
            }
        }
    }

    fun identifier(name: String, occurrence: Int = 1): Identifier {
        return nodes<Identifier>().filter { it.name == name }.elementAt(occurrence - 1)
    }

    fun localInitializer(name: String): ExpressionNode {
        val statement = chunk.body.statements
            .filterIsInstance<io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement>()
            .first { local -> local.init.any { it.name == name } }
        val index = statement.init.indexOfFirst { it.name == name }
        return statement.variables[index]
    }

    fun positionOf(needle: String, occurrence: Int = 1): Position {
        var index = -1
        repeat(occurrence) {
            index = source.indexOf(needle, index + 1)
            check(index >= 0) { "Missing occurrence $occurrence of '$needle'." }
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

    @PublishedApi
    internal fun visit(node: BaseASTNode, block: (BaseASTNode) -> Unit) {
        block(node)
        when (node) {
            is ChunkNode -> visit(node.body, block)
            is io.github.dingyi222666.luaparser.parser.ast.node.BlockNode -> {
                node.statements.forEach { visit(it, block) }
                node.returnStatement?.let { visit(it, block) }
            }

            is io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement -> {
                node.init.forEach { visit(it, block) }
                node.variables.forEach { visit(it, block) }
            }

            is io.github.dingyi222666.luaparser.parser.ast.node.ReturnStatement -> node.arguments.forEach { visit(it, block) }
            is io.github.dingyi222666.luaparser.parser.ast.node.CallStatement -> visit(node.expression, block)
            is io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement -> {
                node.init.forEach { visit(it, block) }
                node.variables.forEach { visit(it, block) }
            }

            is FunctionDeclaration -> {
                node.identifier?.let { visit(it, block) }
                node.params.forEach { visit(it, block) }
                node.body?.let { visit(it, block) }
            }

            is io.github.dingyi222666.luaparser.parser.ast.node.CallExpression -> {
                visit(node.base, block)
                node.arguments.forEach { visit(it, block) }
            }

            is MemberExpression -> {
                visit(node.base, block)
                visit(node.identifier, block)
            }

            is io.github.dingyi222666.luaparser.parser.ast.node.IndexExpression -> {
                visit(node.base, block)
                visit(node.index, block)
            }

            is io.github.dingyi222666.luaparser.parser.ast.node.BinaryExpression -> {
                node.left?.let { visit(it, block) }
                node.right?.let { visit(it, block) }
            }

            is io.github.dingyi222666.luaparser.parser.ast.node.UnaryExpression -> visit(node.arg, block)
            is io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression -> node.fields.forEach { visit(it, block) }
            is io.github.dingyi222666.luaparser.parser.ast.node.TableKey -> {
                runCatching { node.key }.getOrNull()?.let { visit(it, block) }
                visit(node.value, block)
            }

            is io.github.dingyi222666.luaparser.parser.ast.node.ArrayConstructorExpression -> node.values.forEach { visit(it, block) }
            is io.github.dingyi222666.luaparser.parser.ast.node.DoStatement -> visit(node.body, block)
            is io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement -> {
                visit(node.condition, block)
                visit(node.body, block)
            }

            is io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement -> {
                visit(node.body, block)
                visit(node.condition, block)
            }

            is io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement -> {
                visit(node.variable, block)
                visit(node.start, block)
                visit(node.end, block)
                node.step?.let { visit(it, block) }
                visit(node.body, block)
            }

            is io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement -> {
                node.variables.forEach { visit(it, block) }
                node.iterators.forEach { visit(it, block) }
                visit(node.body, block)
            }

            is io.github.dingyi222666.luaparser.parser.ast.node.IfStatement -> node.causes.forEach { visit(it, block) }
            is io.github.dingyi222666.luaparser.parser.ast.node.IfClause -> {
                visit(node.condition, block)
                visit(node.body, block)
            }

            is io.github.dingyi222666.luaparser.parser.ast.node.ElseIfClause -> {
                visit(node.condition, block)
                visit(node.body, block)
            }

            is io.github.dingyi222666.luaparser.parser.ast.node.ElseClause -> visit(node.body, block)
            is io.github.dingyi222666.luaparser.parser.ast.node.SwitchStatement -> {
                visit(node.condition, block)
                node.causes.forEach { visit(it, block) }
            }

            is io.github.dingyi222666.luaparser.parser.ast.node.CaseCause -> {
                node.conditions.forEach { visit(it, block) }
                visit(node.body, block)
            }

            is io.github.dingyi222666.luaparser.parser.ast.node.DefaultCause -> visit(node.body, block)
            is io.github.dingyi222666.luaparser.parser.ast.node.WhenStatement -> {
                visit(node.condition, block)
                visit(node.ifCause, block)
                node.elseCause?.let { visit(it, block) }
            }

            is io.github.dingyi222666.luaparser.parser.ast.node.LambdaDeclaration -> {
                node.params.forEach { visit(it, block) }
                visit(node.expression, block)
            }
        }
    }
}

internal fun semanticModelHarness(source: String): SemanticModelHarness {
    val chunk = LuaParser().parse(source)
    val snapshot = SemanticPipeline().analyzeSnapshot(chunk)
    val binder = snapshot.binder
    val model = snapshot.model
    return SemanticModelHarness(source, chunk, binder, model)
}
