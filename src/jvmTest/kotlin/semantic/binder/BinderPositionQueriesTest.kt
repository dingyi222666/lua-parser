package semantic.binder

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOwner
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.binder.ScopeKind
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BinderPositionQueriesTest {

    private val parser = LuaParser()

    @Test
    fun returnsRootAndInnermostScopesAcrossNestedConstructs() {
        val source = """
            local root = 1

            do
                local outer = 2
                if true then
                    while false do
                        local inner = 3
                    end
                end
            end
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))

        assertEquals(result.scopeGraph.rootScope, result.positionQueries.getScopeAt(Position(2, 1)))

        val innerDeclaration = result.declarationIndex.declarations.single { it.name == "inner" }
        val innerScope = assertNotNull(result.positionQueries.getScopeAt(innerDeclaration.range!!.start))

        assertEquals(ScopeKind.LOOP, innerScope.kind)
    }

    @Test
    fun fallsBackToParentScopeAtNestedScopeEndBoundary() {
        val source = """
            do
                local value = 1
            end
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val doStatement = chunk.body.statements.filterIsInstance<DoStatement>().single()
        val blockScope = assertNotNull(result.scopeGraph.getScope(doStatement.body))

        assertEquals(result.scopeGraph.rootScope, result.positionQueries.getScopeAt(blockScope.range.end))
    }

    @Test
    fun resolvesLocalFunctionParameterAndLoopDeclarationsAtDeclarationSitesOnly() {
        val source = """
            local root = 1

            local function render(input)
                print(input)
            end

            for i = 1, 3 do
                print(i)
            end
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val declarations = result.declarationIndex.declarations.filter { it.origin != DeclarationOrigin.BUILTIN }

        val rootDeclaration = declarations.single { it.name == "root" && it.kind == DeclarationKind.LOCAL }
        val functionDeclaration = declarations.single { it.name == "render" && it.kind == DeclarationKind.FUNCTION }
        val parameterDeclaration = declarations.single { it.name == "input" && it.kind == DeclarationKind.PARAMETER }
        val loopDeclaration = declarations.single { it.name == "i" && it.kind == DeclarationKind.LOCAL }

        assertEquals(rootDeclaration, result.positionQueries.getDeclarationAt(rootDeclaration.range!!.start))
        assertEquals(rootDeclaration.symbolId, result.positionQueries.getSymbolAt(rootDeclaration.range!!.start)?.id)

        assertEquals(functionDeclaration, result.positionQueries.getDeclarationAt(functionDeclaration.range!!.start))
        assertEquals(functionDeclaration.symbolId, result.positionQueries.getSymbolAt(functionDeclaration.range!!.start)?.id)

        assertEquals(parameterDeclaration, result.positionQueries.getDeclarationAt(parameterDeclaration.range!!.start))
        assertEquals(parameterDeclaration.symbolId, result.positionQueries.getSymbolAt(parameterDeclaration.range!!.start)?.id)

        val forStatement = chunk.body.statements.filterIsInstance<ForNumericStatement>().single()
        val loopScope = assertNotNull(result.scopeGraph.getScope(forStatement.body))

        assertEquals(loopDeclaration, result.positionQueries.getDeclarationAt(loopDeclaration.range!!.start))
        assertEquals(loopDeclaration.symbolId, result.positionQueries.getSymbolAt(loopDeclaration.range!!.start)?.id)
        assertEquals(loopScope, result.positionQueries.getScopeAt(loopDeclaration.range!!.start))
    }

    @Test
    fun returnsNullSymbolsForAnonymousFunctionBodiesAndUsageSites() {
        val source = """
            local callback = function(value)
                print(value)
                return value
            end
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val localStatement = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val anonymousFunction = localStatement.variables.filterIsInstance<FunctionDeclaration>().single()
        val functionScope = assertNotNull(result.scopeGraph.getScope(anonymousFunction.body!!))

        val returnPosition = positionOf(source, "return")
        val printUsage = positionOf(source, "print")
        val valueUsage = positionOf(source, "value", occurrence = 3)

        assertEquals(functionScope, result.positionQueries.getScopeAt(returnPosition))
        assertNull(result.positionQueries.getDeclarationAt(returnPosition))
        assertNull(result.positionQueries.getSymbolAt(returnPosition))
        assertNull(result.positionQueries.getSymbolAt(printUsage))
        assertNull(result.positionQueries.getSymbolAt(valueUsage))
    }

    @Test
    fun returnsDocCommentDeclarationsAndKeepsIdentiferTokenOnRealDeclaration() {
        val source = """
            ---@class Widget
            local value = {}
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))

        val docPosition = positionOf(source, "Widget")
        val valuePosition = positionOf(source, "value")
        val classDeclaration = result.declarationIndex.declarations.single { it.kind == DeclarationKind.CLASS }
        val valueDeclaration = result.declarationIndex.declarations.single { it.kind == DeclarationKind.LOCAL && it.name == "value" }

        assertEquals(listOf(classDeclaration), result.positionQueries.getDeclarationsAt(docPosition))
        assertEquals(classDeclaration, result.positionQueries.getDeclarationAt(docPosition))
        assertEquals(valueDeclaration, result.positionQueries.getDeclarationAt(valuePosition))
        assertEquals(valueDeclaration.symbolId, result.positionQueries.getSymbolAt(valuePosition)?.id)
    }

    @Test
    fun reportsAmbiguousOverlappingDocDeclarationsWithoutChoosingOne() {
        val source = """
            ---@class Widget<T>
            ---@field id integer
            ---@method Widget:render(value string): boolean
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))

        val commentPosition = Position(1, 5)
        val declarationsAtPosition = result.positionQueries.getDeclarationsAt(commentPosition)

        assertEquals(4, declarationsAtPosition.size)
        assertNull(result.positionQueries.getDeclarationAt(commentPosition))
        assertNull(result.positionQueries.getSymbolAt(commentPosition))
    }

    @Test
    fun returnsDocOwnedDeclarationsForPositionsInsideFieldMethodAndGenericTags() {
        val source = """
            ---@class Widget<T>
            ---@field id integer
            ---@method Widget:render(value string): boolean

            ---@generic U
            local function build(value)
                return value
            end
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val declarations = result.declarationIndex.declarations.filter { it.origin != DeclarationOrigin.BUILTIN }

        val classDeclaration = declarations.single {
            it.kind == DeclarationKind.CLASS && it.name == "Widget"
        }
        val functionDeclaration = declarations.single {
            it.kind == DeclarationKind.FUNCTION && it.name == "build"
        }
        val classOwner = DeclarationOwner.Declaration(classDeclaration.id)
        val functionOwner = DeclarationOwner.Declaration(functionDeclaration.id)

        val fieldDeclaration = declarations.single {
            it.kind == DeclarationKind.FIELD && it.name == "id" && it.owner == classOwner
        }
        val methodDeclaration = declarations.single {
            it.kind == DeclarationKind.METHOD && it.owner == classOwner
        }
        val classTypeParameter = declarations.single {
            it.kind == DeclarationKind.TYPE_PARAMETER && it.name == "T" && it.owner == classOwner
        }
        val functionTypeParameter = declarations.single {
            it.kind == DeclarationKind.TYPE_PARAMETER && it.name == "U" && it.owner == functionOwner
        }

        assertTrue(result.positionQueries.getDeclarationsAt(positionOf(source, "id")).contains(fieldDeclaration))
        assertTrue(result.positionQueries.getDeclarationsAt(positionOf(source, "render")).contains(methodDeclaration))
        assertTrue(result.positionQueries.getDeclarationsAt(positionOf(source, "T")).contains(classTypeParameter))
        assertTrue(result.positionQueries.getDeclarationsAt(positionOf(source, "U")).contains(functionTypeParameter))
    }

    @Test
    fun neverReturnsBuiltinSymbolsByPositionWithoutRealRanges() {
        val source = "print('hello')"
        val chunk = parser.parse(source)
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))

        assertNull(result.positionQueries.getSymbolAt(positionOf(source, "print")))
    }

    private fun positionOf(source: String, needle: String, occurrence: Int = 1): Position {
        var fromIndex = 0
        repeat(occurrence - 1) {
            fromIndex = source.indexOf(needle, fromIndex) + 1
        }
        val index = source.indexOf(needle, fromIndex)
        require(index >= 0) { "Missing '$needle' occurrence $occurrence." }

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
